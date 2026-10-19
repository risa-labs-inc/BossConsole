-- ============================================================================
-- Agents platform: web team invites (console /join/<token> + invite mail), anon surface of the
-- agents functions, and audit actors for role changes made through the organisation function.
-- Builds on 20261016130000, 20261019120000 and 20261019140000.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Anonymous surface (identity_disclosure_audit CHECK 1).
--    agent_can_join: chat asks it with the person's own session, so anon is not needed. A signed-in
--    caller may ask only about their own email (otherwise any account could probe invite lists).
--    agent_for_host / domain_tls_allowed stay anon: chat resolves a Host and answers Caddy's TLS ask
--    before anyone signs in, and they reveal only host -> live slug (public: agent_public, DNS).
--    They are on the audit's sanctioned list.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.agent_can_join(p_slug text, p_email text)
RETURNS boolean LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    a public.agents%ROWTYPE;
    norm text := pg_catalog.lower(pg_catalog.btrim(COALESCE(p_email, '')));
    mode text;
BEGIN
    IF norm !~ '^[^@\s]+@[^@\s]+$' THEN
        RETURN false;
    END IF;
    IF auth.uid() IS NOT NULL AND norm <> pg_catalog.lower(pg_catalog.btrim(COALESCE(auth.email(), ''))) THEN
        RETURN false;
    END IF;
    SELECT * INTO a FROM public.agents WHERE slug = p_slug AND status = 'live';
    IF NOT FOUND THEN
        RETURN false;
    END IF;
    mode := a.signup_policy->>'mode';
    IF mode = 'open' THEN
        RETURN true;
    ELSIF mode = 'domain' THEN
        RETURN EXISTS (
            SELECT 1 FROM pg_catalog.jsonb_array_elements_text(
                CASE WHEN pg_catalog.jsonb_typeof(a.signup_policy->'domains') = 'array'
                     THEN a.signup_policy->'domains' ELSE '[]'::jsonb END) d
            WHERE pg_catalog.lower(pg_catalog.btrim(d, ' @')) = pg_catalog.split_part(norm, '@', 2));
    ELSIF mode = 'invite' THEN
        RETURN EXISTS (SELECT 1 FROM public.agent_invites i
                       WHERE i.agent_id = a.id AND pg_catalog.lower(i.email::text) = norm);
    END IF;
    RETURN false;
END;
$$;
REVOKE ALL ON FUNCTION public.agent_can_join(text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.agent_can_join(text, text) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 2. Audit actor for the organisation function's service-role path. It names the acting user via
--    p_actor_id, which resolve_org_actor accepts for service_role only; recording it in the
--    transaction-local agents.audit_actor makes the role/permission triggers (agent_audit_actor())
--    attribute the change. Identical to 20260801010000 apart from the set_config (hence VOLATILE).
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION "public"."resolve_org_actor"("p_actor_id" "uuid" DEFAULT NULL::"uuid")
RETURNS "uuid"
    LANGUAGE "plpgsql" VOLATILE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_uid UUID;
BEGIN
    v_uid := auth.uid();

    -- A signed-in caller is always themselves. p_actor_id is ignored.
    IF v_uid IS NOT NULL THEN
        RETURN v_uid;
    END IF;

    -- No session: only the service role may name an actor, and only one that
    -- actually exists.
    IF COALESCE(auth.jwt() ->> 'role', '') = 'service_role'
       AND p_actor_id IS NOT NULL
       AND EXISTS (SELECT 1 FROM auth.users u WHERE u.id = p_actor_id) THEN
        PERFORM pg_catalog.set_config('agents.audit_actor', p_actor_id::text, true);
        RETURN p_actor_id;
    END IF;

    RETURN NULL;
END;
$$;
REVOKE EXECUTE ON FUNCTION "public"."resolve_org_actor"("uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."resolve_org_actor"("uuid") TO "authenticated", "service_role";

-- ----------------------------------------------------------------------------
-- 3. The built-in role seed is logged once per org as a system action (actor NULL, role.seed);
--    the per-row role/permission entries stay suppressed. Identical to 20261019140000 apart from
--    v_created and the final insert.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.seed_org_agent_roles(p_org_id uuid) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_slug text;
    v_org_name text;
    v_admin uuid;
    v_user uuid;
    v_role uuid;
    v_name text;
    v_created text[] := ARRAY[]::text[];
    r record;
BEGIN
    SELECT o.slug, o.name INTO v_slug, v_org_name FROM public.organisations o WHERE o.id = p_org_id;
    IF NOT FOUND THEN
        RETURN;
    END IF;
    PERFORM pg_catalog.set_config('agents.audit_suppress', 'on', true);
    SELECT orl.role_id INTO v_admin FROM public.organisation_roles orl WHERE orl.org_id = p_org_id AND orl.kind = 'admin';
    SELECT orl.role_id INTO v_user FROM public.organisation_roles orl WHERE orl.org_id = p_org_id AND orl.kind = 'user';

    IF v_admin IS NOT NULL THEN
        INSERT INTO public.role_permissions (role_id, permission_id)
            SELECT v_admin, p.id FROM public.permissions p WHERE p.name = ANY (public.agents_permission_catalog())
            ON CONFLICT (role_id, permission_id) DO NOTHING;
    END IF;

    FOR r IN SELECT * FROM (VALUES
        ('agent_manager', 'Agent manager: view, edit, publish, access and domains',
         ARRAY['agents.view', 'agents.edit', 'agents.publish', 'agents.access', 'agents.domains']),
        ('agent_editor', 'Agent editor: view and edit agents', ARRAY['agents.view', 'agents.edit']),
        ('agent_analyst', 'Agent analyst: read-only agents console', ARRAY['agents.view'])
    ) AS t(preset, description, perms) LOOP
        CONTINUE WHEN EXISTS (SELECT 1 FROM public.organisation_roles orl
                              WHERE orl.org_id = p_org_id AND orl.preset = r.preset);
        v_name := public.organisation_role_name(v_slug, r.preset);
        IF pg_catalog.char_length(v_name) > 50 OR EXISTS (SELECT 1 FROM public.roles x WHERE x.name = v_name) THEN
            RAISE NOTICE 'seed_org_agent_roles: skipped % for org % (name taken or too long)', v_name, p_org_id;
            CONTINUE;
        END IF;
        INSERT INTO public.roles (name, description, is_system) VALUES (v_name, r.description, false)
            RETURNING id INTO v_role;
        INSERT INTO public.organisation_roles (org_id, role_id, kind, created_by, preset)
            VALUES (p_org_id, v_role, 'custom', NULL, r.preset);
        IF v_admin IS NOT NULL THEN
            INSERT INTO public.role_hierarchy (parent_role_id, child_role_id) VALUES (v_admin, v_role) ON CONFLICT DO NOTHING;
        END IF;
        IF v_user IS NOT NULL THEN
            INSERT INTO public.role_hierarchy (parent_role_id, child_role_id) VALUES (v_role, v_user) ON CONFLICT DO NOTHING;
        END IF;
        INSERT INTO public.role_permissions (role_id, permission_id)
            SELECT v_role, p.id FROM public.permissions p WHERE p.name = ANY (r.perms || ARRAY['organisation.read'])
            ON CONFLICT (role_id, permission_id) DO NOTHING;
        v_created := v_created || v_name;
    END LOOP;

    -- A preset seeded before the admin/user role existed gets its edges now.
    IF v_admin IS NOT NULL THEN
        INSERT INTO public.role_hierarchy (parent_role_id, child_role_id)
            SELECT v_admin, orl.role_id FROM public.organisation_roles orl
            WHERE orl.org_id = p_org_id AND orl.preset IS NOT NULL ON CONFLICT DO NOTHING;
    END IF;
    IF v_user IS NOT NULL THEN
        INSERT INTO public.role_hierarchy (parent_role_id, child_role_id)
            SELECT orl.role_id, v_user FROM public.organisation_roles orl
            WHERE orl.org_id = p_org_id AND orl.preset IS NOT NULL ON CONFLICT DO NOTHING;
    END IF;
    PERFORM pg_catalog.set_config('agents.audit_suppress', '', true);
    IF pg_catalog.cardinality(v_created) > 0 THEN
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
            VALUES (p_org_id, NULL, NULL, 'role.seed', jsonb_build_object('system', true, 'roles', pg_catalog.to_jsonb(v_created)));
    END IF;
END;
$$;
REVOKE ALL ON FUNCTION public.seed_org_agent_roles(uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.seed_org_agent_roles(uuid) TO service_role;

-- Orgs seeded by 20261019140000 (unlogged then) get their one system entry now.
INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
    SELECT orl.org_id, NULL, NULL, 'role.seed',
           jsonb_build_object('system', true, 'roles', jsonb_agg(r.name ORDER BY r.name), 'backfill', true)
    FROM public.organisation_roles orl
    JOIN public.roles r ON r.id = orl.role_id
    WHERE orl.preset IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM public.agent_audit_log l WHERE l.org_id = orl.org_id AND l.action = 'role.seed')
    GROUP BY orl.org_id;

-- ----------------------------------------------------------------------------
-- 4. Web join: what the signed-in invitee's console shows before redeeming. The caller holds the
--    token (it came in their mail), so the state is theirs to see; redeem_organisation_invite keeps
--    its single "invalid or expired" answer. Unknown tokens answer {state: invalid} and nothing else.
--    state: valid | already_member | wrong_account | revoked | expired | used | invalid
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.org_invite_check(p_token text) RETURNS jsonb
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    uid uuid := auth.uid();
    v_inv public.organisation_invites;
    v_org public.organisations;
    v_role text;
    v_email text;
    v_state text;
    v_hint text;
BEGIN
    IF uid IS NULL THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    IF p_token IS NULL OR pg_catalog.btrim(p_token) = '' OR pg_catalog.char_length(p_token) > 200 THEN
        RETURN jsonb_build_object('state', 'invalid');
    END IF;
    SELECT * INTO v_inv FROM public.organisation_invites i
        WHERE i.token_hash = pg_catalog.encode(extensions.digest(pg_catalog.btrim(p_token), 'sha256'), 'hex');
    IF NOT FOUND THEN
        RETURN jsonb_build_object('state', 'invalid');
    END IF;
    SELECT * INTO v_org FROM public.organisations o WHERE o.id = v_inv.org_id;
    SELECT r.name INTO v_role FROM public.roles r WHERE r.id = v_inv.role_id;
    SELECT u.email::text INTO v_email FROM auth.users u WHERE u.id = uid AND u.email_confirmed_at IS NOT NULL;

    IF EXISTS (SELECT 1 FROM public.organisation_members m
               WHERE m.org_id = v_inv.org_id AND m.user_id = uid AND m.status = 'active') THEN
        v_state := 'already_member';
    ELSIF v_inv.revoked_at IS NOT NULL THEN
        v_state := 'revoked';
    ELSIF v_inv.expires_at <= now() THEN
        v_state := 'expired';
    ELSIF v_inv.max_uses IS NOT NULL AND v_inv.uses >= v_inv.max_uses THEN
        v_state := 'used';
    ELSIF v_inv.email IS NOT NULL AND pg_catalog.lower(COALESCE(v_email, '')) <> pg_catalog.lower(v_inv.email::text) THEN
        v_state := 'wrong_account';
        -- Enough to pick the right account, not the address: first letter and the domain.
        v_hint := pg_catalog.left(v_inv.email::text, 1) || '…@' || pg_catalog.split_part(v_inv.email::text, '@', 2);
    ELSE
        v_state := 'valid';
    END IF;
    RETURN jsonb_build_object('state', v_state, 'org_id', v_org.id, 'org_name', v_org.name, 'org_slug', v_org.slug,
                              'role_name', v_role, 'expires_at', v_inv.expires_at, 'email_hint', v_hint);
END;
$$;
REVOKE ALL ON FUNCTION public.org_invite_check(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.org_invite_check(text) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 5. Invite mail (edge function org-invite-mail). The function verifies the caller's JWT, then asks
--    this service-role RPC, which re-checks everything server-side: the actor administers the org
--    (who may invite: org_team_invite's rule), the invite is this org's, pending, email-bound, and the
--    token is the invite's own (so the mailer cannot be used to send arbitrary links), then applies
--    the rate limits and records the send. Limits: per org 30/hour; per recipient 3/hour, 10/day.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.org_invite_mail_log (
    id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_id    uuid NOT NULL,
    invite_id uuid NOT NULL,
    email     extensions.citext NOT NULL,
    actor     uuid NOT NULL,
    sent_at   timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS idx_org_invite_mail_log_org ON public.org_invite_mail_log (org_id, sent_at DESC);
CREATE INDEX IF NOT EXISTS idx_org_invite_mail_log_email ON public.org_invite_mail_log (email, sent_at DESC);
ALTER TABLE public.org_invite_mail_log ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.org_invite_mail_log FROM PUBLIC, anon, authenticated, service_role;
COMMENT ON TABLE public.org_invite_mail_log IS
    'One row per org invite mail sent (org-invite-mail). Rate limits only; written by org_invite_mail_prepare.';

CREATE OR REPLACE FUNCTION public.org_invite_mail_prepare(p_actor uuid, p_org_id uuid, p_invite_id uuid, p_token text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_inv public.organisation_invites;
    v_org public.organisations;
    v_role text;
    v_from_email text;
    v_from_name text;
BEGIN
    IF p_actor IS NULL OR p_org_id IS NULL OR NOT public.user_is_org_admin(p_actor, p_org_id) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    SELECT * INTO v_inv FROM public.organisation_invites i
        WHERE i.id = p_invite_id AND i.org_id = p_org_id
          AND i.token_hash = pg_catalog.encode(extensions.digest(pg_catalog.btrim(COALESCE(p_token, '')), 'sha256'), 'hex')
        FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'invite_not_found' USING ERRCODE = 'P0001';
    END IF;
    IF v_inv.email IS NULL THEN
        RAISE EXCEPTION 'invite_not_email' USING ERRCODE = 'P0001';
    END IF;
    IF v_inv.revoked_at IS NOT NULL OR v_inv.expires_at <= now()
       OR (v_inv.max_uses IS NOT NULL AND v_inv.uses >= v_inv.max_uses) THEN
        RAISE EXCEPTION 'invite_not_pending' USING ERRCODE = 'P0001';
    END IF;

    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext('org_invite_mail:' || p_org_id::text));
    IF (SELECT count(*) FROM public.org_invite_mail_log l
        WHERE l.org_id = p_org_id AND l.sent_at > now() - interval '1 hour') >= 30 THEN
        RAISE EXCEPTION 'rate_limited_org' USING ERRCODE = 'P0001';
    END IF;
    IF (SELECT count(*) FROM public.org_invite_mail_log l
        WHERE l.email = v_inv.email AND l.sent_at > now() - interval '1 hour') >= 3
       OR (SELECT count(*) FROM public.org_invite_mail_log l
           WHERE l.email = v_inv.email AND l.sent_at > now() - interval '1 day') >= 10 THEN
        RAISE EXCEPTION 'rate_limited_recipient' USING ERRCODE = 'P0001';
    END IF;

    INSERT INTO public.org_invite_mail_log (org_id, invite_id, email, actor)
        VALUES (p_org_id, v_inv.id, v_inv.email, p_actor);
    INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
        VALUES (p_org_id, NULL, p_actor, 'team.invite_mail',
                jsonb_build_object('invite_id', v_inv.id, 'email', v_inv.email::text));

    SELECT * INTO v_org FROM public.organisations o WHERE o.id = p_org_id;
    SELECT r.name INTO v_role FROM public.roles r WHERE r.id = v_inv.role_id;
    SELECT u.email::text,
           COALESCE(NULLIF(pg_catalog.btrim(u.raw_user_meta_data->>'full_name'), ''),
                    NULLIF(pg_catalog.btrim(u.raw_user_meta_data->>'name'), ''))
      INTO v_from_email, v_from_name
      FROM auth.users u WHERE u.id = p_actor;
    RETURN jsonb_build_object('email', v_inv.email::text, 'org_name', v_org.name, 'org_slug', v_org.slug,
                              'role_name', v_role, 'expires_at', v_inv.expires_at,
                              'inviter_email', v_from_email, 'inviter_name', v_from_name);
END;
$$;
REVOKE ALL ON FUNCTION public.org_invite_mail_prepare(uuid, uuid, uuid, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.org_invite_mail_prepare(uuid, uuid, uuid, text) TO service_role;
