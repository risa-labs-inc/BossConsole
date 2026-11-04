-- ============================================================================
-- A2A org auto-connect: an organisation can connect all its members' agents
-- (follows 20261014220000_fluck_a2a_peers and 20261016120000_fluck_owner_active)
-- ============================================================================
--
-- An org admin may turn on organisations.a2a_auto_connect (OFF by default). While it is on,
-- every two ACTIVE members of that org are "colleagues" for A2A:
--   * without a link request, but
--   * only between instances of the SAME product (agent_kind: Max talks to Max, Optimist to
--     Optimist), and
--   * never for a member who opted out of that org, nor for a pair where either side declined or
--     revoked (a per-colleague block).
--
-- Nothing is materialized: colleagues are computed at list / mint / consume time from
-- organisation_members, so joining, leaving, opting out and turning the policy off take effect
-- on the next call (a ticket already in flight is refused at consume).
--
-- Callers learn HOW they are related (source = 'self' | 'link' | 'org'). The receiving agent uses
-- consume's source to choose the trust tier (org colleagues get a read-only tier there).
--
-- The system org (is_system, every account is in it) can never turn this on. Membership is read
-- from organisation_members directly: user_is_org_member() counts global admins as members of
-- every org and is deliberately not used here.
--
-- agent_kind is what the instance reports about itself: it partitions colleagues by product so Max
-- talks to Max, and is not an access control. Who may reach whom is the org membership, the policy and
-- the opt-outs; a colleague is read-only on the receiving side whatever kind it claims.
--
-- Kill switch: platform_flags 'a2a_org_auto_connect' = false disables every org grant at once.
-- It fails closed: with the flag row missing, org grants are off (this migration inserts it as true).
-- Explicit links and a user's own instances are unchanged by this migration.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Columns
-- ----------------------------------------------------------------------------
ALTER TABLE public.organisations
    ADD COLUMN IF NOT EXISTS a2a_auto_connect boolean NOT NULL DEFAULT false,
    -- When the policy was last turned on: members are told once per turn-on.
    ADD COLUMN IF NOT EXISTS a2a_auto_connect_at timestamptz;

COMMENT ON COLUMN public.organisations.a2a_auto_connect IS
    'A2A org auto-connect: active members'' agents of the same product may talk without a link request. Off by default; org admins only (org_set_a2a_auto_connect).';

-- Which product an instance is. NULL = an older client that does not say: such an instance is
-- never reached through an org grant (fails closed). Max fills it from its install identity; a
-- managed agent is 'agent:<slug>' (agents.slug is unique), so it only meets the same managed agent.
ALTER TABLE public.fluck_web_instances
    ADD COLUMN IF NOT EXISTS agent_kind text;
DO $$ BEGIN
    ALTER TABLE public.fluck_web_instances
        ADD CONSTRAINT fluck_web_instances_agent_kind_check
        CHECK (agent_kind IS NULL OR agent_kind IN ('max', 'optimist', 'fluck')
               OR agent_kind ~ '^agent:[a-z][a-z0-9-]{1,30}[a-z0-9]$');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- Who ended a link, so only they can lift a block (fluck_peer_unblock).
ALTER TABLE public.fluck_peer_links
    ADD COLUMN IF NOT EXISTS revoked_by uuid REFERENCES auth.users(id) ON DELETE SET NULL;

INSERT INTO public.platform_flags (key, value) VALUES ('a2a_org_auto_connect', 'true'::jsonb)
    ON CONFLICT (key) DO NOTHING;

-- ----------------------------------------------------------------------------
-- 2. Opt-outs (per org; visible to the member and to that org's admins) and notices
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.organisation_a2a_opt_outs (
    org_id        uuid NOT NULL REFERENCES public.organisations(id) ON DELETE CASCADE,
    user_id       uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    opted_out_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (org_id, user_id)
);
COMMENT ON TABLE public.organisation_a2a_opt_outs IS
    'Members who opted out of their org''s A2A auto-connect. Org admins can see who (org_a2a_policy).';
CREATE INDEX IF NOT EXISTS idx_organisation_a2a_opt_outs_user ON public.organisation_a2a_opt_outs (user_id);

ALTER TABLE public.organisation_a2a_opt_outs ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "organisation_a2a_opt_outs self select" ON public.organisation_a2a_opt_outs;
CREATE POLICY "organisation_a2a_opt_outs self select" ON public.organisation_a2a_opt_outs
    FOR SELECT TO authenticated USING (( SELECT auth.uid() ) = user_id);
REVOKE ALL ON public.organisation_a2a_opt_outs FROM PUBLIC, anon, authenticated;
GRANT SELECT ON public.organisation_a2a_opt_outs TO authenticated;
GRANT ALL ON public.organisation_a2a_opt_outs TO service_role;

-- One "your org connected you" notice per member per turn-on, claimed by exactly one agent.
CREATE TABLE IF NOT EXISTS public.organisation_a2a_notices (
    org_id       uuid NOT NULL REFERENCES public.organisations(id) ON DELETE CASCADE,
    user_id      uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    notified_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (org_id, user_id)
);
ALTER TABLE public.organisation_a2a_notices ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.organisation_a2a_notices FROM PUBLIC, anon, authenticated;
GRANT ALL ON public.organisation_a2a_notices TO service_role;

-- ----------------------------------------------------------------------------
-- 3. The one decision: how (if at all) may A's instance talk to B's
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.fluck_a2a_org_enabled() RETURNS boolean
    LANGUAGE sql STABLE SET search_path = '' AS $$
    SELECT COALESCE((SELECT f.value = 'true'::jsonb FROM public.platform_flags f WHERE f.key = 'a2a_org_auto_connect'), false);
$$;

-- Whether A and B share an org with auto-connect on, both active, neither opted out.
-- The first such org's name (for "connected via <org>"), else NULL.
CREATE OR REPLACE FUNCTION public.fluck_a2a_shared_org(p_a uuid, p_b uuid) RETURNS text
    LANGUAGE sql STABLE SET search_path = '' AS $$
    SELECT o.name
    FROM public.organisation_members ma
    JOIN public.organisation_members mb ON mb.org_id = ma.org_id
    JOIN public.organisations o ON o.id = ma.org_id
    WHERE ma.user_id = p_a AND mb.user_id = p_b
      AND ma.status = 'active' AND mb.status = 'active'
      AND o.a2a_auto_connect AND NOT o.is_system
      AND NOT EXISTS (SELECT 1 FROM public.organisation_a2a_opt_outs x
                      WHERE x.org_id = o.id AND x.user_id IN (p_a, p_b))
    ORDER BY o.name
    LIMIT 1;
$$;

-- 'self' | 'link' | 'org' | NULL (may not talk). Kind match applies to the org grant only.
CREATE OR REPLACE FUNCTION public.fluck_a2a_relation(p_a uuid, p_b uuid, p_a_kind text, p_b_kind text)
RETURNS text LANGUAGE plpgsql STABLE SET search_path = '' AS $$
BEGIN
    IF p_a IS NULL OR p_b IS NULL THEN
        RETURN NULL;
    END IF;
    IF p_a = p_b THEN
        RETURN 'self';
    END IF;
    IF EXISTS (
        SELECT 1 FROM public.fluck_peer_links l
        WHERE l.status = 'accepted'
          AND least(l.requester_id, l.recipient_id) = least(p_a, p_b)
          AND greatest(l.requester_id, l.recipient_id) = greatest(p_a, p_b)
    ) THEN
        RETURN 'link';
    END IF;
    IF NOT public.fluck_a2a_org_enabled()
       OR p_a_kind IS NULL OR p_b_kind IS NULL OR p_a_kind <> p_b_kind THEN
        RETURN NULL;
    END IF;
    -- A decline or revoke between the two blocks the org grant for that pair.
    IF EXISTS (
        SELECT 1 FROM public.fluck_peer_links l
        WHERE l.status IN ('declined', 'revoked')
          AND least(l.requester_id, l.recipient_id) = least(p_a, p_b)
          AND greatest(l.requester_id, l.recipient_id) = greatest(p_a, p_b)
    ) THEN
        RETURN NULL;
    END IF;
    IF public.fluck_a2a_shared_org(p_a, p_b) IS NOT NULL THEN
        RETURN 'org';
    END IF;
    RETURN NULL;
END;
$$;

REVOKE ALL ON FUNCTION public.fluck_a2a_org_enabled() FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.fluck_a2a_shared_org(uuid, uuid) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.fluck_a2a_relation(uuid, uuid, text, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.fluck_a2a_relation(uuid, uuid, text, text) TO service_role;

-- ----------------------------------------------------------------------------
-- 4. Heartbeat carries the product (an extra trailing parameter; older 5-argument calls still work)
-- ----------------------------------------------------------------------------
DROP FUNCTION IF EXISTS public.fluck_web_upsert_instance(text, text, text, text, text);
CREATE FUNCTION public.fluck_web_upsert_instance(
    p_instance_id text, p_label text, p_agent_name text, p_endpoint_url text, p_app_version text,
    p_agent_kind text DEFAULT NULL
) RETURNS void LANGUAGE plpgsql SECURITY INVOKER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    INSERT INTO public.fluck_web_instances AS i (
        user_id, instance_id, label, agent_name, endpoint_url, app_version, agent_kind
    ) VALUES (
        auth.uid(), p_instance_id, p_label, p_agent_name,
        -- Tolerate one trailing slash on an origin; the CHECK refuses anything else.
        pg_catalog.rtrim(p_endpoint_url, '/'), p_app_version, p_agent_kind
    ) ON CONFLICT (user_id, instance_id) DO UPDATE SET
        label = EXCLUDED.label, agent_name = EXCLUDED.agent_name,
        endpoint_url = EXCLUDED.endpoint_url, app_version = EXCLUDED.app_version,
        -- A heartbeat without a kind clears it, on purpose. A client from before org auto-connect ignores
        -- consume's source and would run a colleague as a full link, so a downgraded instance must stop
        -- receiving org tickets at once rather than keep the kind a newer build recorded (fails closed).
        agent_kind = EXCLUDED.agent_kind;
EXCEPTION WHEN data_exception OR integrity_constraint_violation THEN
    -- Keep the failing row out of the client-visible DETAIL.
    RAISE EXCEPTION 'Invalid Fluck web instance' USING ERRCODE = '22023';
END;
$$;
REVOKE ALL ON FUNCTION public.fluck_web_upsert_instance(text, text, text, text, text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.fluck_web_upsert_instance(text, text, text, text, text, text) TO authenticated;

-- ----------------------------------------------------------------------------
-- 5. Directory: own instances, linked peers', and same-product org colleagues'
-- ----------------------------------------------------------------------------
-- p_from_instance: the CALLING instance (one of the caller's own). Its agent_kind selects which
-- colleagues' instances are listed; without it (older clients) no colleagues are listed.
DROP FUNCTION IF EXISTS public.fluck_peer_list();
CREATE FUNCTION public.fluck_peer_list(p_from_instance text DEFAULT NULL)
RETURNS TABLE (
    user_id uuid, email text, same_user boolean, instance_id text, label text, agent_name text,
    endpoint_url text, online boolean, owner_active_at timestamptz,
    agent_kind text, source text, org_name text
) LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
DECLARE
    actor uuid := auth.uid();
    my_kind text;
BEGIN
    IF actor IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    IF p_from_instance IS NOT NULL THEN
        SELECT i.agent_kind INTO my_kind FROM public.fluck_web_instances i
            WHERE i.user_id = actor AND i.instance_id = p_from_instance;
    END IF;
    RETURN QUERY
        WITH related AS (
            SELECT actor AS uid, 'self'::text AS src, NULL::text AS org, 0 AS rank
            UNION ALL
            SELECT CASE WHEN l.requester_id = actor THEN l.recipient_id ELSE l.requester_id END,
                   'link', NULL, 1
            FROM public.fluck_peer_links l
            WHERE l.status = 'accepted' AND (l.requester_id = actor OR l.recipient_id = actor)
            UNION ALL
            SELECT mb.user_id, 'org', min(o.name), 2
            FROM public.organisation_members ma
            JOIN public.organisations o ON o.id = ma.org_id
            JOIN public.organisation_members mb ON mb.org_id = ma.org_id
            WHERE my_kind IS NOT NULL AND public.fluck_a2a_org_enabled()
              AND ma.user_id = actor AND ma.status = 'active'
              AND mb.user_id <> actor AND mb.status = 'active'
              AND o.a2a_auto_connect AND NOT o.is_system
              AND NOT EXISTS (SELECT 1 FROM public.organisation_a2a_opt_outs x
                              WHERE x.org_id = o.id AND x.user_id IN (actor, mb.user_id))
              AND NOT EXISTS (SELECT 1 FROM public.fluck_peer_links l
                              WHERE l.status IN ('declined', 'revoked')
                                AND least(l.requester_id, l.recipient_id) = least(actor, mb.user_id)
                                AND greatest(l.requester_id, l.recipient_id) = greatest(actor, mb.user_id))
            GROUP BY mb.user_id
        ),
        best AS (
            SELECT DISTINCT ON (r.uid) r.uid, r.src, r.org FROM related r ORDER BY r.uid, r.rank
        )
        SELECT i.user_id, u.email::text, (i.user_id = actor), i.instance_id, i.label, i.agent_name,
               i.endpoint_url, (i.last_seen_at > now() - interval '90 seconds'), i.owner_active_at,
               i.agent_kind, b.src, b.org
        FROM best b
        JOIN public.fluck_web_instances i ON i.user_id = b.uid
        JOIN auth.users u ON u.id = i.user_id
        -- A colleague is reachable only on an instance of the caller's own product.
        WHERE b.src <> 'org' OR i.agent_kind = my_kind
        ORDER BY (i.user_id = actor) DESC, (b.src = 'link') DESC,
                 (i.last_seen_at > now() - interval '90 seconds') DESC, i.last_seen_at DESC
        LIMIT 500;
END;
$$;
REVOKE ALL ON FUNCTION public.fluck_peer_list(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.fluck_peer_list(text) TO authenticated;

-- ----------------------------------------------------------------------------
-- 6. Tickets: mint and consume decide with fluck_a2a_relation
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.fluck_a2a_mint_ticket(p_from_instance text, p_target_user uuid, p_target_instance text)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    actor uuid := auth.uid();
    token text;
    from_kind text;
    target_kind text;
    target_live boolean;
BEGIN
    IF actor IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    IF p_from_instance IS NULL OR p_target_user IS NULL OR p_target_instance IS NULL THEN
        RAISE EXCEPTION 'peer_unavailable' USING ERRCODE = 'P0001';
    END IF;
    -- The calling instance need not be listed (one without a public endpoint still calls out and
    -- polls) for self and link calls. An org call needs it listed, because its kind decides.
    IF p_from_instance !~ '^[A-Za-z0-9._:-]{1,128}$' THEN
        RAISE EXCEPTION 'peer_unavailable' USING ERRCODE = 'P0001';
    END IF;
    SELECT i.agent_kind INTO from_kind FROM public.fluck_web_instances i
        WHERE i.user_id = actor AND i.instance_id = p_from_instance;
    SELECT i.agent_kind, (i.last_seen_at > now() - interval '90 seconds') INTO target_kind, target_live
        FROM public.fluck_web_instances i
        WHERE i.user_id = p_target_user AND i.instance_id = p_target_instance;
    -- The target must be the caller's own account, an accepted peer, or a same-product org
    -- colleague, and its instance must be live. Every refusal looks the same.
    IF public.fluck_a2a_relation(actor, p_target_user, from_kind, target_kind) IS NULL
       OR target_live IS NOT TRUE THEN
        RAISE EXCEPTION 'peer_unavailable' USING ERRCODE = 'P0001';
    END IF;
    -- Serialize this issuer's mints so the pending cap below is exact.
    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(actor::text, 26));
    DELETE FROM public.fluck_a2a_tickets t
        WHERE t.issuer_id = actor AND (t.expires_at <= now() OR t.used_at IS NOT NULL);
    IF (SELECT count(*) FROM public.fluck_a2a_tickets t WHERE t.issuer_id = actor) >= 50 THEN
        RAISE EXCEPTION 'too_many_tickets' USING ERRCODE = 'P0001';
    END IF;
    token := pg_catalog.translate(pg_catalog.encode(extensions.gen_random_bytes(32), 'base64'), '+/=', '-_');
    INSERT INTO public.fluck_a2a_tickets (
        ticket_hash, issuer_id, issuer_instance, target_user, target_instance, expires_at
    ) VALUES (
        pg_catalog.encode(extensions.digest(token, 'sha256'), 'hex'), actor, p_from_instance,
        p_target_user, p_target_instance, now() + interval '60 seconds'
    );
    RETURN token;
END;
$$;

-- Adds `source`: how the caller is related ('self' | 'link' | 'org'), decided here, at redemption,
-- so the receiver picks its trust tier from Supabase and never from the caller. A grant that ended
-- since the mint (left the org, opted out, policy off, revoked) refuses the ticket. A refusal rolls back
-- the burn, so the ticket stays redeemable by its target until it expires (60 s) and keeps being refused
-- unless the grant comes back in that window.
DROP FUNCTION IF EXISTS public.fluck_a2a_consume_ticket(text, text);
CREATE FUNCTION public.fluck_a2a_consume_ticket(p_ticket text, p_instance_id text)
RETURNS TABLE (user_id uuid, email text, instance_id text, same_user boolean, source text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
DECLARE
    actor uuid := auth.uid();
    from_user uuid;
    from_instance text;
    from_kind text;
    my_kind text;
    rel text;
BEGIN
    IF actor IS NULL OR p_instance_id IS NULL OR p_ticket IS NULL
       OR p_ticket !~ '^[A-Za-z0-9_-]{43}$' THEN
        RAISE EXCEPTION 'ticket_invalid' USING ERRCODE = 'P0001';
    END IF;
    UPDATE public.fluck_a2a_tickets t SET used_at = now()
        WHERE t.ticket_hash = pg_catalog.encode(extensions.digest(p_ticket, 'sha256'), 'hex')
          AND t.used_at IS NULL
          AND t.expires_at > now()
          AND t.target_user = actor
          AND t.target_instance = p_instance_id
        RETURNING t.issuer_id, t.issuer_instance INTO from_user, from_instance;
    IF from_user IS NULL THEN
        RAISE EXCEPTION 'ticket_invalid' USING ERRCODE = 'P0001';
    END IF;
    SELECT i.agent_kind INTO from_kind FROM public.fluck_web_instances i
        WHERE i.user_id = from_user AND i.instance_id = from_instance;
    SELECT i.agent_kind INTO my_kind FROM public.fluck_web_instances i
        WHERE i.user_id = actor AND i.instance_id = p_instance_id;
    rel := public.fluck_a2a_relation(from_user, actor, from_kind, my_kind);
    IF rel IS NULL THEN
        RAISE EXCEPTION 'ticket_invalid' USING ERRCODE = 'P0001';
    END IF;
    RETURN QUERY
        SELECT u.id, u.email::text, from_instance, (from_user = actor), rel
        FROM auth.users u WHERE u.id = from_user;
END;
$$;
REVOKE ALL ON FUNCTION public.fluck_a2a_consume_ticket(text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.fluck_a2a_consume_ticket(text, text) TO authenticated;

-- ----------------------------------------------------------------------------
-- 7. Blocking one colleague: revoke now also blocks an org-only pair, and only its author lifts it
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.fluck_peer_revoke(p_peer_id uuid)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    actor uuid := auth.uid();
    touched uuid;
BEGIN
    IF actor IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    IF p_peer_id IS NULL OR p_peer_id = actor THEN
        RETURN;
    END IF;
    UPDATE public.fluck_peer_links l
        SET status = 'revoked', responded_at = now(), revoked_by = actor
        WHERE least(l.requester_id, l.recipient_id) = least(actor, p_peer_id)
          AND greatest(l.requester_id, l.recipient_id) = greatest(actor, p_peer_id)
          AND l.status IN ('pending', 'accepted')
        RETURNING l.id INTO touched;
    -- No link row yet: block an org colleague (someone who shares an org with auto-connect on).
    -- Anyone else is a silent no-op, so this cannot probe accounts.
    IF touched IS NULL AND EXISTS (
        SELECT 1 FROM public.organisation_members ma
        JOIN public.organisation_members mb ON mb.org_id = ma.org_id
        JOIN public.organisations o ON o.id = ma.org_id
        WHERE ma.user_id = actor AND mb.user_id = p_peer_id
          AND ma.status = 'active' AND mb.status = 'active' AND NOT o.is_system AND o.a2a_auto_connect
    ) THEN
        INSERT INTO public.fluck_peer_links (requester_id, recipient_id, status, responded_at, revoked_by)
            VALUES (actor, p_peer_id, 'revoked', now(), actor)
            ON CONFLICT ((least(requester_id, recipient_id)), (greatest(requester_id, recipient_id))) DO NOTHING;
    END IF;
    DELETE FROM public.fluck_a2a_tickets t
        WHERE (t.issuer_id = actor AND t.target_user = p_peer_id)
           OR (t.issuer_id = p_peer_id AND t.target_user = actor);
END;
$$;

-- Lift a block the caller made, so the org grant applies again. A block made by the other side
-- stays. Declines (written by fluck_peer_respond, without revoked_by) are the recipient's to lift. A
-- revoke from before this migration has no known author, so it is not lifted here (fails closed); the
-- pair can still connect again through fluck_peer_request and an accept.
CREATE OR REPLACE FUNCTION public.fluck_peer_unblock(p_peer_id uuid)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    actor uuid := auth.uid();
BEGIN
    IF actor IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    IF p_peer_id IS NULL OR p_peer_id = actor THEN
        RETURN;
    END IF;
    DELETE FROM public.fluck_peer_links l
        WHERE least(l.requester_id, l.recipient_id) = least(actor, p_peer_id)
          AND greatest(l.requester_id, l.recipient_id) = greatest(actor, p_peer_id)
          AND (
              (l.status = 'revoked' AND l.revoked_by = actor)
              OR (l.status = 'declined' AND l.revoked_by IS NULL AND l.recipient_id = actor)
          );
END;
$$;
REVOKE ALL ON FUNCTION public.fluck_peer_unblock(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.fluck_peer_unblock(uuid) TO authenticated;

-- ----------------------------------------------------------------------------
-- 8. Member side: my orgs' policy, opt out / back in, the one-time notice
-- ----------------------------------------------------------------------------
-- The caller's active, non-system orgs with the policy and the caller's own opt-out.
CREATE OR REPLACE FUNCTION public.fluck_a2a_orgs()
RETURNS TABLE (org_id uuid, org_name text, auto_connect boolean, opted_out boolean, colleagues integer)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
DECLARE
    actor uuid := auth.uid();
BEGIN
    IF actor IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    RETURN QUERY
        -- auto_connect reports what is in force, so the platform kill switch reads as off.
        SELECT o.id, o.name, o.a2a_auto_connect AND public.fluck_a2a_org_enabled(),
               EXISTS (SELECT 1 FROM public.organisation_a2a_opt_outs x WHERE x.org_id = o.id AND x.user_id = actor),
               (SELECT count(*)::integer FROM public.organisation_members m
                WHERE m.org_id = o.id AND m.status = 'active' AND m.user_id <> actor
                  AND NOT EXISTS (SELECT 1 FROM public.organisation_a2a_opt_outs x
                                  WHERE x.org_id = o.id AND x.user_id = m.user_id))
        FROM public.organisation_members ma
        JOIN public.organisations o ON o.id = ma.org_id
        WHERE ma.user_id = actor AND ma.status = 'active' AND NOT o.is_system
        ORDER BY o.name;
END;
$$;

CREATE OR REPLACE FUNCTION public.fluck_a2a_set_org_opt_out(p_org_id uuid, p_opt_out boolean)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    actor uuid := auth.uid();
    changed integer;
BEGIN
    IF actor IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    IF p_org_id IS NULL OR p_opt_out IS NULL OR NOT EXISTS (
        SELECT 1 FROM public.organisation_members m JOIN public.organisations o ON o.id = m.org_id
        WHERE m.org_id = p_org_id AND m.user_id = actor AND m.status = 'active' AND NOT o.is_system
    ) THEN
        RAISE EXCEPTION 'org_invalid' USING ERRCODE = 'P0001';
    END IF;
    IF p_opt_out THEN
        INSERT INTO public.organisation_a2a_opt_outs (org_id, user_id) VALUES (p_org_id, actor)
            ON CONFLICT DO NOTHING;
    ELSE
        DELETE FROM public.organisation_a2a_opt_outs x WHERE x.org_id = p_org_id AND x.user_id = actor;
    END IF;
    GET DIAGNOSTICS changed = ROW_COUNT;
    IF changed > 0 THEN
        INSERT INTO public.agent_audit_log (org_id, actor, action, detail)
            VALUES (p_org_id, actor, CASE WHEN p_opt_out THEN 'org.a2a_opt_out' ELSE 'org.a2a_opt_in' END, '{}'::jsonb);
    END IF;
END;
$$;

-- Orgs whose turn-on the caller has not been told about yet (policy on, caller active, not opted out).
CREATE OR REPLACE FUNCTION public.fluck_a2a_org_notices()
RETURNS TABLE (org_id uuid, org_name text, colleagues integer)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
DECLARE
    actor uuid := auth.uid();
BEGIN
    IF actor IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    RETURN QUERY
        SELECT f.org_id, f.org_name, f.colleagues FROM public.fluck_a2a_orgs() f
        JOIN public.organisations o ON o.id = f.org_id
        WHERE f.auto_connect AND NOT f.opted_out AND public.fluck_a2a_org_enabled()
          AND NOT EXISTS (SELECT 1 FROM public.organisation_a2a_notices n
                          WHERE n.org_id = f.org_id AND n.user_id = actor
                            AND n.notified_at >= COALESCE(o.a2a_auto_connect_at, '-infinity'::timestamptz));
END;
$$;

-- True for exactly one of the caller's agents per turn-on.
CREATE OR REPLACE FUNCTION public.fluck_a2a_claim_org_notice(p_org_id uuid)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    actor uuid := auth.uid();
    since timestamptz;
    claimed uuid;
BEGIN
    IF actor IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    SELECT COALESCE(o.a2a_auto_connect_at, '-infinity'::timestamptz) INTO since
        FROM public.organisations o
        JOIN public.organisation_members m ON m.org_id = o.id
        WHERE o.id = p_org_id AND o.a2a_auto_connect AND m.user_id = actor AND m.status = 'active'
          AND NOT o.is_system AND public.fluck_a2a_org_enabled()
          AND NOT EXISTS (SELECT 1 FROM public.organisation_a2a_opt_outs x WHERE x.org_id = o.id AND x.user_id = actor);
    -- The same conditions as fluck_a2a_org_notices: a notice that would not be listed cannot be burned.
    IF since IS NULL THEN
        RETURN false;
    END IF;
    INSERT INTO public.organisation_a2a_notices AS n (org_id, user_id, notified_at)
        VALUES (p_org_id, actor, now())
        ON CONFLICT (org_id, user_id) DO UPDATE SET notified_at = EXCLUDED.notified_at
            WHERE n.notified_at < since
        RETURNING n.org_id INTO claimed;
    RETURN claimed IS NOT NULL;
END;
$$;

REVOKE ALL ON FUNCTION public.fluck_a2a_orgs() FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.fluck_a2a_set_org_opt_out(uuid, boolean) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.fluck_a2a_org_notices() FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.fluck_a2a_claim_org_notice(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.fluck_a2a_orgs() TO authenticated;
GRANT EXECUTE ON FUNCTION public.fluck_a2a_set_org_opt_out(uuid, boolean) TO authenticated;
GRANT EXECUTE ON FUNCTION public.fluck_a2a_org_notices() TO authenticated;
GRANT EXECUTE ON FUNCTION public.fluck_a2a_claim_org_notice(uuid) TO authenticated;

-- ----------------------------------------------------------------------------
-- 9. Admin side: read and set the policy (BossConsole org page via service_role + p_actor_id,
--    Optimist console as the signed-in admin). Every change is in the org audit log.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION "public"."org_a2a_policy"("p_org_id" uuid, "p_actor_id" uuid DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_actor uuid;
    o public.organisations%ROWTYPE;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;
    IF NOT public.user_is_org_admin(v_actor, p_org_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;
    SELECT * INTO o FROM public.organisations WHERE id = p_org_id;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'Organisation not found');
    END IF;
    RETURN jsonb_build_object(
        'success', true,
        'auto_connect', o.a2a_auto_connect,
        'enabled_at', o.a2a_auto_connect_at,
        'available', NOT o.is_system,
        'platform_enabled', public.fluck_a2a_org_enabled(),
        'members', (SELECT count(*) FROM public.organisation_members m WHERE m.org_id = p_org_id AND m.status = 'active'),
        'opted_out', COALESCE((
            SELECT jsonb_agg(jsonb_build_object('user_id', x.user_id, 'email', u.email, 'opted_out_at', x.opted_out_at)
                             ORDER BY x.opted_out_at DESC)
            FROM public.organisation_a2a_opt_outs x
            JOIN auth.users u ON u.id = x.user_id
            JOIN public.organisation_members m ON m.org_id = x.org_id AND m.user_id = x.user_id AND m.status = 'active'
            WHERE x.org_id = p_org_id), '[]'::jsonb)
    );
END;
$$;

CREATE OR REPLACE FUNCTION "public"."org_set_a2a_auto_connect"("p_org_id" uuid, "p_enabled" boolean, "p_actor_id" uuid DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_actor uuid;
    o public.organisations%ROWTYPE;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;
    IF p_enabled IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'enabled is required');
    END IF;
    IF NOT public.user_is_org_admin(v_actor, p_org_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;
    SELECT * INTO o FROM public.organisations WHERE id = p_org_id FOR UPDATE;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'Organisation not found');
    END IF;
    IF o.is_system THEN
        RETURN jsonb_build_object('success', false, 'error', 'Agent auto-connect is not available for this organisation');
    END IF;
    IF o.a2a_auto_connect IS DISTINCT FROM p_enabled THEN
        UPDATE public.organisations
           SET a2a_auto_connect = p_enabled,
               a2a_auto_connect_at = CASE WHEN p_enabled THEN now() ELSE a2a_auto_connect_at END
         WHERE id = p_org_id;
        INSERT INTO public.agent_audit_log (org_id, actor, action, detail)
            VALUES (p_org_id, v_actor, 'org.a2a_auto_connect',
                    jsonb_build_object('from', o.a2a_auto_connect, 'to', p_enabled));
    END IF;
    RETURN jsonb_build_object('success', true, 'auto_connect', p_enabled);
END;
$$;

REVOKE ALL ON FUNCTION public.org_a2a_policy(uuid, uuid) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.org_set_a2a_auto_connect(uuid, boolean, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.org_a2a_policy(uuid, uuid) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.org_set_a2a_auto_connect(uuid, boolean, uuid) TO authenticated, service_role;
