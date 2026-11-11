-- ============================================================================
-- Org-controlled invite links: any user of an agent, Optimist or Max may send a link; the invitee gets their OWN
-- private agent/pod and is not added to the inviter's org.
-- Targets: an agent slug (public.agents, live) or a platform ('optimist' | 'max').
-- Redeeming an agent link inserts an agent_invites row for the caller (exception = true, invited_by = link creator),
-- so agent_can_join is unchanged. agent_invites_guard checks only the address and the exception mark, never the
-- caller, and redeem runs as the definer, so the insert passes it without any bypass; RLS and column grants stay as is.
-- Redeeming a platform link inserts public.platform_access(email, platform, link_id).
-- Tokens: 32 random bytes, base64url; only sha256 (hex) is stored, never granted to clients.
--
-- Optimist organisation: the org the Optimist chat enrols people into is identified in the DB by the platform_flags key
-- 'optimist_org_id' (uuid string). Seeded here from the organisation with slug 'optimist' when one exists; null/absent
-- means "only platform_access holders" count as Optimist/Max users and there is no org to be a member of.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Policy
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.invite_policy_default() RETURNS jsonb
    LANGUAGE sql IMMUTABLE SET search_path = '' AS $$
    SELECT '{"enabled":true,"who":"users","max_uses":10,"expiry_days":7,"per_user_per_month":10,"active_links_per_user":5,"org_per_month":200}'::jsonb;
$$;

/** A stored policy is a (possibly partial) object of known keys; missing keys take the defaults. */
CREATE OR REPLACE FUNCTION public.invite_policy_valid(p jsonb) RETURNS boolean
    LANGUAGE sql IMMUTABLE SET search_path = '' AS $$
    SELECT pg_catalog.jsonb_typeof(p) = 'object'
       AND NOT EXISTS (
           SELECT 1 FROM pg_catalog.jsonb_each(p) e
           WHERE CASE e.key
                   WHEN 'enabled' THEN pg_catalog.jsonb_typeof(e.value) <> 'boolean'
                   WHEN 'who' THEN NOT (e.value IN ('"users"', '"members"', '"admins"', '"off"'))
                   WHEN 'max_uses' THEN NOT (pg_catalog.jsonb_typeof(e.value) = 'number' AND e.value::text ~ '^[1-9][0-9]{0,3}$')
                   WHEN 'expiry_days' THEN NOT (pg_catalog.jsonb_typeof(e.value) = 'number' AND e.value::text ~ '^[1-9][0-9]{0,3}$')
                   WHEN 'per_user_per_month' THEN NOT (pg_catalog.jsonb_typeof(e.value) = 'number' AND e.value::text ~ '^[1-9][0-9]{0,5}$')
                   WHEN 'active_links_per_user' THEN NOT (pg_catalog.jsonb_typeof(e.value) = 'number' AND e.value::text ~ '^[1-9][0-9]{0,3}$')
                   WHEN 'org_per_month' THEN NOT (pg_catalog.jsonb_typeof(e.value) = 'number' AND e.value::text ~ '^[1-9][0-9]{0,6}$')
                   ELSE true END);
$$;

ALTER TABLE public.agents ADD COLUMN IF NOT EXISTS invite_policy jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE public.agents DROP CONSTRAINT IF EXISTS agents_invite_policy_valid;
ALTER TABLE public.agents ADD CONSTRAINT agents_invite_policy_valid CHECK (public.invite_policy_valid(invite_policy));
COMMENT ON COLUMN public.agents.invite_policy IS
    'Invite-link policy overrides (keys of invite_policy_default); missing keys take the defaults. Edited with agents.access.';
GRANT UPDATE (invite_policy) ON public.agents TO authenticated;

-- Editing the policy needs agents.access (the row is reachable by agents.edit/capabilities holders too).
CREATE OR REPLACE FUNCTION public.agents_invite_policy_check() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NOT NULL AND NOT public.user_has_org_permission(auth.uid(), OLD.org_id, 'agents.access') THEN
        RAISE EXCEPTION 'Permission denied: agents.access' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.agents_invite_policy_check() FROM PUBLIC, anon, authenticated;
DROP TRIGGER IF EXISTS agents_invite_policy_check_on_write ON public.agents;
CREATE TRIGGER agents_invite_policy_check_on_write BEFORE UPDATE OF invite_policy ON public.agents
    FOR EACH ROW WHEN (NEW.invite_policy IS DISTINCT FROM OLD.invite_policy)
    EXECUTE FUNCTION public.agents_invite_policy_check();

CREATE OR REPLACE FUNCTION public.agents_invite_policy_audit() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
        VALUES (NEW.org_id, NEW.id, public.agent_audit_actor(), 'invite_policy.change',
                pg_catalog.jsonb_build_object('from', OLD.invite_policy, 'to', NEW.invite_policy));
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION public.agents_invite_policy_audit() FROM PUBLIC, anon, authenticated;
DROP TRIGGER IF EXISTS agents_invite_policy_audit_on_change ON public.agents;
CREATE TRIGGER agents_invite_policy_audit_on_change AFTER UPDATE OF invite_policy ON public.agents
    FOR EACH ROW WHEN (NEW.invite_policy IS DISTINCT FROM OLD.invite_policy)
    EXECUTE FUNCTION public.agents_invite_policy_audit();

CREATE TABLE IF NOT EXISTS public.platform_invite_policies (
    platform   text PRIMARY KEY CHECK (platform IN ('optimist', 'max')),
    policy     jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (public.invite_policy_valid(policy)),
    updated_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO public.platform_invite_policies (platform) VALUES ('optimist'), ('max') ON CONFLICT DO NOTHING;
ALTER TABLE public.platform_invite_policies ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.platform_invite_policies FROM PUBLIC, anon, authenticated;
GRANT SELECT ON public.platform_invite_policies TO service_role;

CREATE OR REPLACE FUNCTION public.platform_invite_policies_audit() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF NEW.policy IS DISTINCT FROM OLD.policy THEN
        INSERT INTO public.platform_audit_log (actor, action, detail)
            VALUES (auth.uid(), 'invite_policy.change',
                    pg_catalog.jsonb_build_object('platform', NEW.platform, 'from', OLD.policy, 'to', NEW.policy));
    END IF;
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION public.platform_invite_policies_audit() FROM PUBLIC, anon, authenticated;
DROP TRIGGER IF EXISTS platform_invite_policies_audit_on_change ON public.platform_invite_policies;
CREATE TRIGGER platform_invite_policies_audit_on_change AFTER UPDATE ON public.platform_invite_policies
    FOR EACH ROW EXECUTE FUNCTION public.platform_invite_policies_audit();

-- The Optimist organisation (see header). Flags are readable by signed-in users and editable by platform admins.
INSERT INTO public.platform_flags (key, value)
    SELECT 'optimist_org_id', COALESCE((SELECT pg_catalog.to_jsonb(o.id::text) FROM public.organisations o WHERE o.slug = 'optimist'),
                                       'null'::jsonb)
    ON CONFLICT (key) DO NOTHING;

-- ----------------------------------------------------------------------------
-- 2. Tables
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.platform_access (
    email      extensions.citext NOT NULL,
    platform   text NOT NULL CHECK (platform IN ('optimist', 'max')),
    link_id    uuid,
    invited_by uuid REFERENCES auth.users(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (email, platform)
);
ALTER TABLE public.platform_access ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.platform_access FROM PUBLIC, anon, authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.platform_access TO service_role;
COMMENT ON TABLE public.platform_access IS
    'Emails admitted to a platform (optimist/max) outside the waitlist; written by redeem_invite_link and the platform servers.';

CREATE TABLE IF NOT EXISTS public.invite_links (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    target     text NOT NULL,
    agent_id   uuid REFERENCES public.agents(id) ON DELETE CASCADE,
    org_id     uuid,
    created_by uuid REFERENCES auth.users(id) ON DELETE SET NULL,
    token_hash text NOT NULL UNIQUE,
    max_uses   int NOT NULL CHECK (max_uses >= 1),
    uses       int NOT NULL DEFAULT 0 CHECK (uses >= 0),
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    revoked_by uuid,
    via        text NOT NULL CHECK (via IN ('max', 'console', 'api')),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((agent_id IS NOT NULL AND org_id IS NOT NULL) OR (agent_id IS NULL AND target IN ('optimist', 'max')))
);
CREATE INDEX IF NOT EXISTS idx_invite_links_creator ON public.invite_links (created_by, target, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_invite_links_agent ON public.invite_links (agent_id) WHERE agent_id IS NOT NULL;

-- inviter_id/target are copied from the link so the quota counts are single-index range scans.
CREATE TABLE IF NOT EXISTS public.invite_link_redemptions (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    link_id     uuid NOT NULL REFERENCES public.invite_links(id) ON DELETE CASCADE,
    user_id     uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    inviter_id  uuid,
    target      text NOT NULL,
    redeemed_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (link_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_invite_redemptions_inviter ON public.invite_link_redemptions (inviter_id, redeemed_at DESC);
CREATE INDEX IF NOT EXISTS idx_invite_redemptions_target ON public.invite_link_redemptions (target, redeemed_at DESC);

ALTER TABLE public.invite_links ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.invite_link_redemptions ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.invite_links, public.invite_link_redemptions FROM PUBLIC, anon, authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.invite_links, public.invite_link_redemptions TO service_role;
-- Everything but token_hash.
GRANT SELECT (id, target, agent_id, org_id, created_by, max_uses, uses, expires_at, revoked_at, revoked_by, via, created_at)
    ON public.invite_links TO authenticated;

DROP POLICY IF EXISTS "invite_links creator or admin select" ON public.invite_links;
CREATE POLICY "invite_links creator or admin select" ON public.invite_links FOR SELECT TO authenticated
    USING (created_by = (SELECT auth.uid())
           OR (agent_id IS NOT NULL AND public.has_agent_permission(agent_id, 'agents.access'))
           OR (agent_id IS NULL AND (SELECT public.is_user_admin((SELECT auth.uid())))));

-- ----------------------------------------------------------------------------
-- 3. Audit (agent links to the agent trail, platform links to the platform trail)
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.invite_links_audit() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    l public.invite_links;
    act text;
    det jsonb;
BEGIN
    IF TG_TABLE_NAME = 'invite_links' THEN
        l := NEW;
        IF TG_OP = 'INSERT' THEN
            act := 'invite_link.create';
            det := pg_catalog.jsonb_build_object('link_id', l.id, 'target', l.target, 'max_uses', l.max_uses,
                                                 'expires_at', l.expires_at, 'via', l.via);
        ELSIF OLD.revoked_at IS NULL AND NEW.revoked_at IS NOT NULL THEN
            act := 'invite_link.revoke';
            det := pg_catalog.jsonb_build_object('link_id', l.id, 'target', l.target, 'created_by', l.created_by);
        ELSE
            RETURN NULL;
        END IF;
    ELSE
        SELECT * INTO l FROM public.invite_links k WHERE k.id = NEW.link_id;
        act := 'invite_link.redeem';
        det := pg_catalog.jsonb_build_object('link_id', l.id, 'target', l.target, 'redeemer', NEW.user_id,
                                             'inviter', l.created_by);
    END IF;
    IF l.agent_id IS NOT NULL THEN
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
            VALUES (l.org_id, l.agent_id, public.agent_audit_actor(), act, det);
    ELSE
        INSERT INTO public.platform_audit_log (actor, action, detail) VALUES (auth.uid(), act, det);
    END IF;
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION public.invite_links_audit() FROM PUBLIC, anon, authenticated;
DROP TRIGGER IF EXISTS invite_links_audit_on_change ON public.invite_links;
CREATE TRIGGER invite_links_audit_on_change AFTER INSERT OR UPDATE ON public.invite_links
    FOR EACH ROW EXECUTE FUNCTION public.invite_links_audit();
DROP TRIGGER IF EXISTS invite_redemptions_audit_on_insert ON public.invite_link_redemptions;
CREATE TRIGGER invite_redemptions_audit_on_insert AFTER INSERT ON public.invite_link_redemptions
    FOR EACH ROW EXECUTE FUNCTION public.invite_links_audit();

-- ----------------------------------------------------------------------------
-- 4. Internals (no client access)
-- ----------------------------------------------------------------------------
/** The target as {kind, agent_id, org_id, name, policy}: policy merged over the defaults. NULL when it does not exist. */
CREATE OR REPLACE FUNCTION public.invite_target_info(p_target text) RETURNS jsonb
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    a public.agents;
    v_org text;
BEGIN
    IF p_target IN ('optimist', 'max') THEN
        SELECT f.value #>> '{}' INTO v_org FROM public.platform_flags f WHERE f.key = 'optimist_org_id';
        RETURN pg_catalog.jsonb_build_object('kind', 'platform', 'agent_id', NULL, 'org_id', NULLIF(v_org, ''),
            'name', CASE p_target WHEN 'optimist' THEN 'Optimist' ELSE 'Max' END,
            'policy', public.invite_policy_default() || COALESCE(
                (SELECT p.policy FROM public.platform_invite_policies p WHERE p.platform = p_target), '{}'::jsonb));
    END IF;
    SELECT * INTO a FROM public.agents g WHERE g.slug = p_target AND g.status = 'live';
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    RETURN pg_catalog.jsonb_build_object('kind', 'agent', 'agent_id', a.id, 'org_id', a.org_id,
        'name', COALESCE(NULLIF(pg_catalog.btrim(a.brand->>'name'), ''), a.name),
        'policy', public.invite_policy_default() || a.invite_policy);
END;
$$;

/** Whether the user belongs to the audience p_who for the target described by p_info. */
CREATE OR REPLACE FUNCTION public.invite_audience_ok(p_uid uuid, p_email text, p_who text, p_info jsonb) RETURNS boolean
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_org uuid := NULLIF(p_info->>'org_id', '')::uuid;
    v_agent uuid := NULLIF(p_info->>'agent_id', '')::uuid;
    v_target text := p_info->>'kind';
BEGIN
    IF p_uid IS NULL OR p_who = 'off' THEN
        RETURN false;
    END IF;
    IF v_target = 'agent' THEN
        IF p_who = 'admins' THEN
            RETURN public.user_has_org_permission(p_uid, v_org, 'agents.access');
        ELSIF p_who = 'members' THEN
            RETURN public.user_is_org_member(p_uid, v_org);
        END IF;
        RETURN EXISTS (SELECT 1 FROM public.agent_installs i WHERE i.user_id = p_uid AND i.agent_id = v_agent AND i.state <> 'removed')
            OR public.user_is_org_member(p_uid, v_org);
    END IF;
    IF p_who = 'admins' THEN
        RETURN public.is_user_admin(p_uid);
    ELSIF p_who = 'members' THEN
        RETURN v_org IS NOT NULL AND public.user_is_org_member(p_uid, v_org);
    END IF;
    RETURN (p_info->>'platform') IS NOT NULL AND EXISTS (
            SELECT 1 FROM public.platform_access pa
            WHERE pa.email = pg_catalog.lower(pg_catalog.btrim(COALESCE(p_email, '')))::extensions.citext
              AND pa.platform = p_info->>'platform')
        OR (v_org IS NOT NULL AND public.user_is_org_member(p_uid, v_org));
END;
$$;
REVOKE ALL ON FUNCTION public.invite_policy_valid(jsonb), public.invite_policy_default(), public.invite_target_info(text),
    public.invite_audience_ok(uuid, text, text, jsonb) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.invite_policy_valid(jsonb), public.invite_policy_default() TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 5. RPCs
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.create_invite_link(p_target text, p_max_uses int DEFAULT NULL,
                                                      p_expires_days int DEFAULT NULL, p_via text DEFAULT 'max')
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_uid uuid := auth.uid();
    v_info jsonb;
    v_pol jsonb;
    v_max int;
    v_days int;
    v_token text;
    v_id uuid := gen_random_uuid();
    v_exp timestamptz;
BEGIN
    IF v_uid IS NULL THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'not_allowed');
    END IF;
    IF p_via IS NULL OR p_via NOT IN ('max', 'console', 'api') OR p_target IS NULL
       OR (p_max_uses IS NOT NULL AND p_max_uses < 1) OR (p_expires_days IS NOT NULL AND p_expires_days < 1) THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'bad_request');
    END IF;
    v_info := public.invite_target_info(p_target);
    IF v_info IS NULL THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'not_found');
    END IF;
    v_info := v_info || CASE WHEN p_target IN ('optimist', 'max') THEN pg_catalog.jsonb_build_object('platform', p_target) ELSE '{}'::jsonb END;
    v_pol := v_info->'policy';
    IF NOT (v_pol->>'enabled')::boolean OR v_pol->>'who' = 'off' THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'invites_off');
    END IF;
    IF NOT public.invite_audience_ok(v_uid, auth.email(), v_pol->>'who', v_info) THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'not_allowed');
    END IF;

    -- One creator at a time per target, so the active-link cap holds under concurrency.
    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext('invite_create:' || v_uid::text || ':' || p_target));
    IF (SELECT count(*) FROM public.invite_links l
        WHERE l.created_by = v_uid AND l.target = p_target AND l.revoked_at IS NULL AND l.expires_at > now()
          AND l.uses < l.max_uses) >= (v_pol->>'active_links_per_user')::int THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'too_many_active_links');
    END IF;
    IF (SELECT count(*) FROM public.invite_link_redemptions r
        WHERE r.inviter_id = v_uid AND r.target = p_target AND r.redeemed_at > now() - interval '30 days')
           >= (v_pol->>'per_user_per_month')::int
       OR (SELECT count(*) FROM public.invite_link_redemptions r
           WHERE r.target = p_target AND r.redeemed_at > now() - interval '30 days') >= (v_pol->>'org_per_month')::int THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'quota_exceeded');
    END IF;

    v_max := LEAST(COALESCE(p_max_uses, (v_pol->>'max_uses')::int), (v_pol->>'max_uses')::int);
    v_days := LEAST(COALESCE(p_expires_days, (v_pol->>'expiry_days')::int), (v_pol->>'expiry_days')::int);
    v_exp := now() + pg_catalog.make_interval(days => v_days);
    v_token := pg_catalog.translate(pg_catalog.encode(extensions.gen_random_bytes(32), 'base64'), '+/=', '-_');
    INSERT INTO public.invite_links (id, target, agent_id, org_id, created_by, token_hash, max_uses, expires_at, via)
        VALUES (v_id, p_target, NULLIF(v_info->>'agent_id', '')::uuid,
                CASE WHEN v_info->>'kind' = 'agent' THEN NULLIF(v_info->>'org_id', '')::uuid END, v_uid,
                pg_catalog.encode(extensions.digest(v_token, 'sha256'), 'hex'), v_max, v_exp, p_via);
    RETURN pg_catalog.jsonb_build_object('success', true, 'id', v_id, 'token', v_token, 'path', '/i/' || v_token,
        'target', p_target, 'max_uses', v_max, 'expires_at', v_exp);
END;
$$;

CREATE OR REPLACE FUNCTION public.invite_link_preview(p_token text) RETURNS jsonb
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    l public.invite_links;
    v_info jsonb;
    v_reason text;
    v_inviter text;
BEGIN
    SELECT * INTO l FROM public.invite_links k
        WHERE k.token_hash = pg_catalog.encode(extensions.digest(pg_catalog.btrim(COALESCE(p_token, '')), 'sha256'), 'hex');
    IF NOT FOUND THEN
        RETURN pg_catalog.jsonb_build_object('valid', false, 'reason', 'not_found', 'target', NULL, 'target_name', NULL,
                                             'inviter_name', NULL);
    END IF;
    v_info := public.invite_target_info(l.target);
    v_reason := CASE WHEN v_info IS NULL THEN 'not_found'
                     WHEN l.revoked_at IS NOT NULL THEN 'revoked'
                     WHEN l.expires_at <= now() THEN 'expired'
                     WHEN l.uses >= l.max_uses THEN 'used_up'
                     WHEN NOT (v_info->'policy'->>'enabled')::boolean OR v_info->'policy'->>'who' = 'off' THEN 'invites_off' END;
    SELECT NULLIF(pg_catalog.split_part(pg_catalog.btrim(COALESCE(u.raw_user_meta_data->>'full_name', u.raw_user_meta_data->>'name', '')), ' ', 1), '')
      INTO v_inviter FROM auth.users u WHERE u.id = l.created_by;
    IF v_info IS NULL THEN
        RETURN pg_catalog.jsonb_build_object('valid', false, 'reason', 'not_found', 'target', NULL, 'target_name', NULL,
                                             'inviter_name', NULL);
    END IF;
    RETURN pg_catalog.jsonb_build_object('valid', v_reason IS NULL, 'reason', v_reason, 'target', l.target,
        'target_name', v_info->>'name', 'inviter_name', v_inviter);
END;
$$;

CREATE OR REPLACE FUNCTION public.redeem_invite_link(p_token text) RETURNS jsonb
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_uid uuid := auth.uid();
    v_email text := pg_catalog.lower(pg_catalog.btrim(COALESCE(auth.email(), '')));
    l public.invite_links;
    v_info jsonb;
    v_pol jsonb;
BEGIN
    IF v_uid IS NULL OR v_email = '' THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'not_found');
    END IF;
    -- The link row lock serialises redeems of one link, so uses never pass max_uses.
    SELECT * INTO l FROM public.invite_links k
        WHERE k.token_hash = pg_catalog.encode(extensions.digest(pg_catalog.btrim(COALESCE(p_token, '')), 'sha256'), 'hex')
        FOR UPDATE;
    IF NOT FOUND THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'not_found');
    END IF;
    IF l.created_by = v_uid OR EXISTS (SELECT 1 FROM public.invite_link_redemptions r WHERE r.link_id = l.id AND r.user_id = v_uid) THEN
        RETURN pg_catalog.jsonb_build_object('success', true, 'target', l.target, 'already', true);
    END IF;
    v_info := public.invite_target_info(l.target);
    IF v_info IS NULL THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'not_found');
    END IF;
    v_pol := v_info->'policy';
    IF l.revoked_at IS NOT NULL THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'revoked');
    ELSIF l.expires_at <= now() THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'expired');
    ELSIF l.uses >= l.max_uses THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'used_up');
    ELSIF NOT (v_pol->>'enabled')::boolean OR v_pol->>'who' = 'off' THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'invites_off');
    END IF;

    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext('invite_redeem:' || l.target));
    IF (SELECT count(*) FROM public.invite_link_redemptions r
        WHERE r.inviter_id = l.created_by AND r.target = l.target AND r.redeemed_at > now() - interval '30 days')
           >= (v_pol->>'per_user_per_month')::int
       OR (SELECT count(*) FROM public.invite_link_redemptions r
           WHERE r.target = l.target AND r.redeemed_at > now() - interval '30 days') >= (v_pol->>'org_per_month')::int THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'quota_exceeded');
    END IF;

    IF l.agent_id IS NOT NULL THEN
        INSERT INTO public.agent_invites (agent_id, email, invited_by, exception)
            VALUES (l.agent_id, v_email, l.created_by, true) ON CONFLICT (agent_id, email) DO NOTHING;
    ELSE
        INSERT INTO public.platform_access (email, platform, link_id, invited_by)
            VALUES (v_email, l.target, l.id, l.created_by) ON CONFLICT (email, platform) DO NOTHING;
    END IF;
    INSERT INTO public.invite_link_redemptions (link_id, user_id, inviter_id, target) VALUES (l.id, v_uid, l.created_by, l.target);
    UPDATE public.invite_links SET uses = uses + 1 WHERE id = l.id;
    RETURN pg_catalog.jsonb_build_object('success', true, 'target', l.target, 'already', false);
END;
$$;

CREATE OR REPLACE FUNCTION public.revoke_invite_link(p_id uuid) RETURNS jsonb
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_uid uuid := auth.uid();
    l public.invite_links;
BEGIN
    IF v_uid IS NULL THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'not_allowed');
    END IF;
    SELECT * INTO l FROM public.invite_links k WHERE k.id = p_id FOR UPDATE;
    IF NOT FOUND THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'not_found');
    END IF;
    IF l.created_by IS DISTINCT FROM v_uid AND NOT (
           CASE WHEN l.agent_id IS NOT NULL THEN public.user_has_org_permission(v_uid, l.org_id, 'agents.access')
                ELSE public.is_user_admin(v_uid) END) THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'not_allowed');
    END IF;
    IF l.revoked_at IS NULL THEN
        UPDATE public.invite_links SET revoked_at = now(), revoked_by = v_uid WHERE id = l.id;
    END IF;
    RETURN pg_catalog.jsonb_build_object('success', true);
END;
$$;

CREATE OR REPLACE FUNCTION public.list_my_invite_links(p_target text DEFAULT NULL) RETURNS jsonb
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT COALESCE(pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
        'id', l.id, 'target', l.target, 'max_uses', l.max_uses, 'uses', l.uses, 'expires_at', l.expires_at,
        'revoked_at', l.revoked_at, 'created_at', l.created_at,
        'status', CASE WHEN l.revoked_at IS NOT NULL THEN 'revoked' WHEN l.expires_at <= now() THEN 'expired'
                       WHEN l.uses >= l.max_uses THEN 'used_up' ELSE 'active' END) ORDER BY l.created_at DESC), '[]'::jsonb)
    FROM public.invite_links l
    WHERE l.created_by = auth.uid() AND auth.uid() IS NOT NULL AND (p_target IS NULL OR l.target = p_target);
$$;

CREATE OR REPLACE FUNCTION public.platform_access_allowed(p_platform text) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT auth.uid() IS NOT NULL AND EXISTS (
        SELECT 1 FROM public.platform_access pa
        WHERE pa.platform = p_platform
          AND pa.email = pg_catalog.lower(pg_catalog.btrim(COALESCE(auth.email(), '')))::extensions.citext);
$$;

CREATE OR REPLACE FUNCTION public.set_platform_invite_policy(p_platform text, p_policy jsonb) RETURNS jsonb
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_user_admin(auth.uid()) THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'not_allowed');
    END IF;
    IF p_platform IS NULL OR p_platform NOT IN ('optimist', 'max') OR NOT public.invite_policy_valid(p_policy) THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'bad_request');
    END IF;
    INSERT INTO public.platform_invite_policies (platform, policy) VALUES (p_platform, p_policy)
        ON CONFLICT (platform) DO UPDATE SET policy = EXCLUDED.policy, updated_at = now();
    RETURN pg_catalog.jsonb_build_object('success', true, 'policy', public.invite_policy_default() || p_policy);
END;
$$;

/** Every link of a target, for its admins (agents.access on the owning org; platform admins for platforms). Raises 42501 otherwise. */
CREATE OR REPLACE FUNCTION public.list_invite_links(p_target text) RETURNS jsonb
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_uid uuid := auth.uid();
    v_info jsonb := CASE WHEN p_target IS NULL THEN NULL ELSE public.invite_target_info(p_target) END;
BEGIN
    IF v_uid IS NULL OR v_info IS NULL OR NOT public.invite_audience_ok(v_uid, auth.email(), 'admins', v_info) THEN
        RAISE EXCEPTION 'not_allowed' USING ERRCODE = '42501';
    END IF;
    RETURN COALESCE((SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
        'id', l.id, 'target', l.target, 'max_uses', l.max_uses, 'uses', l.uses, 'expires_at', l.expires_at,
        'revoked_at', l.revoked_at, 'created_at', l.created_at, 'via', l.via, 'created_by', l.created_by,
        'creator_email', u.email::text,
        'status', CASE WHEN l.revoked_at IS NOT NULL THEN 'revoked' WHEN l.expires_at <= now() THEN 'expired'
                       WHEN l.uses >= l.max_uses THEN 'used_up' ELSE 'active' END) ORDER BY l.created_at DESC, l.id)
        FROM public.invite_links l LEFT JOIN auth.users u ON u.id = l.created_by
        WHERE l.target = p_target), '[]'::jsonb);
END;
$$;

/** The effective policy (stored row over the defaults). Platform admins only; raises 42501 otherwise. */
CREATE OR REPLACE FUNCTION public.get_platform_invite_policy(p_platform text) RETURNS jsonb
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_user_admin(auth.uid()) THEN
        RAISE EXCEPTION 'not_allowed' USING ERRCODE = '42501';
    END IF;
    IF p_platform IS NULL OR p_platform NOT IN ('optimist', 'max') THEN
        RAISE EXCEPTION 'bad_request' USING ERRCODE = '22023';
    END IF;
    RETURN public.invite_policy_default() || COALESCE(
        (SELECT p.policy FROM public.platform_invite_policies p WHERE p.platform = p_platform), '{}'::jsonb);
END;
$$;

REVOKE ALL ON FUNCTION public.create_invite_link(text, int, int, text), public.invite_link_preview(text),
    public.redeem_invite_link(text), public.revoke_invite_link(uuid), public.list_my_invite_links(text),
    public.platform_access_allowed(text), public.set_platform_invite_policy(text, jsonb), public.list_invite_links(text),
    public.get_platform_invite_policy(text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.create_invite_link(text, int, int, text), public.redeem_invite_link(text),
    public.revoke_invite_link(uuid), public.list_my_invite_links(text), public.platform_access_allowed(text),
    public.set_platform_invite_policy(text, jsonb), public.list_invite_links(text), public.get_platform_invite_policy(text)
    TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.invite_link_preview(text) TO anon, authenticated, service_role;
