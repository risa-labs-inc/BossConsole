-- ============================================================================
-- Console feedback (owner's hand-testing pass).
--
-- 1. Platform settings: a new flag agent_update_review ("review every new version of a live agent"), and
--    every flag change audited in platform_audit_log (platform.flag_set) with its history for the console.
-- 2. Updates under review keep the agent live: publish_agent parks the new version in pending_version
--    (the live one keeps serving) instead of taking the agent out of service. review_agent takes a reason.
-- 3. Suspend can be undone (unsuspend_agent restores the status it had), records why, and the agent's
--    address can tell "suspended" from "no such agent" (agent_unavailable, anon).
-- 4. Review detail: platform_agent_detail carries everything a platform admin decides on;
--    platform_org_request_detail the same for an organisation request.
-- 5. organisation_domain_problem: the Create organisation dialog asks before submitting (org_create re-checks).
-- 6. brand.logo keeps the uploaded original and its crop (validate_agent_brand).
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Flags
-- ----------------------------------------------------------------------------
-- Today an update to a live agent waits for review exactly when publishing is not open; the new flag starts there.
INSERT INTO public.platform_flags (key, value)
    SELECT 'agent_update_review', pg_catalog.to_jsonb(NOT COALESCE(
        (SELECT f.value = 'true'::jsonb FROM public.platform_flags f WHERE f.key = 'agent_creation_open'), false))
    ON CONFLICT (key) DO NOTHING;

CREATE OR REPLACE FUNCTION public.platform_flags_audit() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF NEW.value IS DISTINCT FROM OLD.value THEN
        INSERT INTO public.platform_audit_log (actor, action, detail)
            VALUES (auth.uid(), 'platform.flag_set',
                    pg_catalog.jsonb_build_object('key', NEW.key, 'from', OLD.value, 'to', NEW.value));
    END IF;
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION public.platform_flags_audit() FROM PUBLIC, anon, authenticated;
DROP TRIGGER IF EXISTS platform_flags_audit ON public.platform_flags;
CREATE TRIGGER platform_flags_audit AFTER UPDATE ON public.platform_flags
    FOR EACH ROW EXECUTE FUNCTION public.platform_flags_audit();

-- Recent setting changes, newest first (platform admins).
CREATE OR REPLACE FUNCTION public.platform_settings_history(p_limit int DEFAULT 20)
RETURNS TABLE (at timestamptz, actor_email text, key text, from_value jsonb, to_value jsonb)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_user_admin(auth.uid()) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    RETURN QUERY
        SELECT l.at, u.email::text, l.detail->>'key', l.detail->'from', l.detail->'to'
        FROM public.platform_audit_log l LEFT JOIN auth.users u ON u.id = l.actor
        WHERE l.action = 'platform.flag_set'
        ORDER BY l.at DESC, l.id DESC
        LIMIT LEAST(GREATEST(COALESCE(p_limit, 20), 1), 100);
END;
$$;
REVOKE ALL ON FUNCTION public.platform_settings_history(int) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.platform_settings_history(int) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 2. Review state on the agent row (read by the org console; written only by the definer RPCs below)
-- ----------------------------------------------------------------------------
ALTER TABLE public.agents
    ADD COLUMN IF NOT EXISTS pending_version text
        CHECK (pending_version IS NULL OR pending_version ~ '^[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.+-]+)?$'),
    ADD COLUMN IF NOT EXISTS submitted_by uuid REFERENCES auth.users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS submitted_at timestamptz,
    ADD COLUMN IF NOT EXISTS review_note text CHECK (review_note IS NULL OR pg_catalog.char_length(review_note) <= 500),
    ADD COLUMN IF NOT EXISTS suspended_at timestamptz,
    ADD COLUMN IF NOT EXISTS suspended_reason text
        CHECK (suspended_reason IS NULL OR pg_catalog.char_length(suspended_reason) <= 500),
    ADD COLUMN IF NOT EXISTS status_before_suspend public.agent_status;
COMMENT ON COLUMN public.agents.pending_version IS
    'A new version of a live agent waiting for review (agent_update_review). live_version keeps serving until it is approved.';

CREATE OR REPLACE FUNCTION public.publish_agent(p_agent_id uuid, p_version text)
RETURNS public.agent_status LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    a public.agents%ROWTYPE;
    new_status public.agent_status;
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    SELECT * INTO a FROM public.agents WHERE id = p_agent_id FOR UPDATE;
    IF NOT FOUND OR NOT public.has_org_permission(a.org_id, 'agents.publish') THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    IF a.status = 'suspended' THEN
        RAISE EXCEPTION 'agent_suspended' USING ERRCODE = 'P0001';
    END IF;
    IF p_version IS NULL OR p_version !~ '^[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.+-]+)?$' THEN
        RAISE EXCEPTION 'invalid_version' USING ERRCODE = 'P0001';
    END IF;
    IF a.plugin_id IS NULL THEN
        RAISE EXCEPTION 'plugin_id_not_set' USING ERRCODE = 'P0001';
    END IF;
    -- A live agent: the new version replaces the live one, or waits beside it for review.
    IF a.status = 'live' AND a.live_version IS NOT NULL THEN
        IF COALESCE((SELECT f.value = 'true'::jsonb FROM public.platform_flags f WHERE f.key = 'agent_update_review'), true) THEN
            UPDATE public.agents SET pending_version = p_version, submitted_by = auth.uid(), submitted_at = pg_catalog.now(),
                                     review_note = NULL
                WHERE id = p_agent_id;
            INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
                VALUES (a.org_id, a.id, auth.uid(), 'agent.submit',
                        pg_catalog.jsonb_build_object('version', p_version, 'live_version', a.live_version));
            RETURN 'pending_review';
        END IF;
        UPDATE public.agents SET live_version = p_version, pending_version = NULL, submitted_by = auth.uid(),
                                 submitted_at = pg_catalog.now(), review_note = NULL
            WHERE id = p_agent_id;
        RETURN 'live';
    END IF;
    new_status := CASE WHEN COALESCE((SELECT f.value = 'true'::jsonb FROM public.platform_flags f
                                      WHERE f.key = 'agent_creation_open'), false)
                       THEN 'live' ELSE 'pending_review' END;
    UPDATE public.agents SET live_version = p_version, status = new_status, pending_version = NULL,
                             submitted_by = auth.uid(), submitted_at = pg_catalog.now(), review_note = NULL
        WHERE id = p_agent_id;
    RETURN new_status;
END;
$$;

DROP FUNCTION IF EXISTS public.review_agent(uuid, boolean);
CREATE OR REPLACE FUNCTION public.review_agent(p_agent_id uuid, p_approve boolean, p_reason text DEFAULT NULL)
RETURNS public.agent_status LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    a public.agents%ROWTYPE;
    reason text := NULLIF(pg_catalog.btrim(COALESCE(p_reason, '')), '');
    new_status public.agent_status;
    version text;
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_user_admin(auth.uid()) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    IF pg_catalog.char_length(COALESCE(reason, '')) > 500 THEN
        RAISE EXCEPTION 'reason_too_long' USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO a FROM public.agents WHERE id = p_agent_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'agent_not_found' USING ERRCODE = 'P0001';
    END IF;
    IF a.status = 'live' AND a.pending_version IS NOT NULL THEN
        version := a.pending_version;
        new_status := 'live';
        IF p_approve THEN
            UPDATE public.agents SET live_version = a.pending_version, pending_version = NULL, review_note = NULL
                WHERE id = p_agent_id;
        ELSE
            UPDATE public.agents SET pending_version = NULL, review_note = reason WHERE id = p_agent_id;
        END IF;
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
            VALUES (a.org_id, a.id, auth.uid(), CASE WHEN p_approve THEN 'agent.approve' ELSE 'agent.reject' END,
                    pg_catalog.jsonb_strip_nulls(pg_catalog.jsonb_build_object('version', version, 'reason', reason)));
    ELSIF a.status = 'pending_review' THEN
        version := a.live_version;
        new_status := CASE WHEN p_approve THEN 'live' ELSE 'draft' END;
        -- agents_audit writes agent.approve / agent.reject on the status change.
        UPDATE public.agents SET status = new_status, review_note = CASE WHEN p_approve THEN NULL ELSE reason END
            WHERE id = p_agent_id;
    ELSE
        RAISE EXCEPTION 'agent_not_pending_review' USING ERRCODE = 'P0001';
    END IF;
    INSERT INTO public.platform_audit_log (actor, action, detail)
        VALUES (auth.uid(), CASE WHEN p_approve THEN 'agent.approve' ELSE 'agent.reject' END,
                pg_catalog.jsonb_strip_nulls(pg_catalog.jsonb_build_object(
                    'agent_id', a.id, 'slug', a.slug, 'version', version, 'reason', reason)));
    RETURN new_status;
END;
$$;

DROP FUNCTION IF EXISTS public.suspend_agent(uuid);
CREATE OR REPLACE FUNCTION public.suspend_agent(p_agent_id uuid, p_reason text DEFAULT NULL)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    a public.agents%ROWTYPE;
    reason text := NULLIF(pg_catalog.btrim(COALESCE(p_reason, '')), '');
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_user_admin(auth.uid()) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    IF pg_catalog.char_length(COALESCE(reason, '')) > 500 THEN
        RAISE EXCEPTION 'reason_too_long' USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO a FROM public.agents WHERE id = p_agent_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'agent_not_found' USING ERRCODE = 'P0001';
    END IF;
    IF a.status = 'suspended' THEN
        RAISE EXCEPTION 'agent_already_suspended' USING ERRCODE = 'P0001';
    END IF;
    UPDATE public.agents SET status = 'suspended', status_before_suspend = a.status,
                             suspended_at = pg_catalog.now(), suspended_reason = reason
        WHERE id = p_agent_id;
    INSERT INTO public.platform_audit_log (actor, action, detail)
        VALUES (auth.uid(), 'agent.suspend', pg_catalog.jsonb_strip_nulls(pg_catalog.jsonb_build_object(
            'agent_id', a.id, 'slug', a.slug, 'from_status', a.status, 'reason', reason)));
END;
$$;

-- Back to the status it had before (live keeps its live version; pods get it back on the provisioner's next pass).
CREATE OR REPLACE FUNCTION public.unsuspend_agent(p_agent_id uuid)
RETURNS public.agent_status LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    a public.agents%ROWTYPE;
    back public.agent_status;
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_user_admin(auth.uid()) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    SELECT * INTO a FROM public.agents WHERE id = p_agent_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'agent_not_found' USING ERRCODE = 'P0001';
    END IF;
    IF a.status <> 'suspended' THEN
        RAISE EXCEPTION 'agent_not_suspended' USING ERRCODE = 'P0001';
    END IF;
    back := COALESCE(a.status_before_suspend, CASE WHEN a.live_version IS NULL THEN 'draft' ELSE 'live' END::public.agent_status);
    IF back = 'live' AND a.live_version IS NULL THEN
        back := 'draft';
    END IF;
    UPDATE public.agents SET status = back, status_before_suspend = NULL, suspended_at = NULL, suspended_reason = NULL
        WHERE id = p_agent_id;
    INSERT INTO public.platform_audit_log (actor, action, detail)
        VALUES (auth.uid(), 'agent.unsuspend', pg_catalog.jsonb_build_object('agent_id', a.id, 'slug', a.slug, 'status', back));
    RETURN back;
END;
$$;

REVOKE ALL ON FUNCTION public.publish_agent(uuid, text), public.review_agent(uuid, boolean, text),
    public.suspend_agent(uuid, text), public.unsuspend_agent(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.publish_agent(uuid, text), public.review_agent(uuid, boolean, text),
    public.suspend_agent(uuid, text), public.unsuspend_agent(uuid) TO authenticated, service_role;

-- An agent address (<slug>.<base> or an active custom domain) that belongs to a suspended agent: its site says
-- "unavailable" rather than "no such assistant". Reveals one boolean about a public address.
CREATE OR REPLACE FUNCTION public.agent_unavailable(p_host text) RETURNS boolean
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    host text := pg_catalog.rtrim(pg_catalog.regexp_replace(pg_catalog.lower(pg_catalog.btrim(COALESCE(p_host, ''))), ':[0-9]{1,5}$', ''), '.');
    base text;
BEGIN
    IF host = '' OR pg_catalog.char_length(host) > 253 THEN
        RETURN false;
    END IF;
    SELECT pg_catalog.lower(f.value #>> '{}') INTO base FROM public.platform_flags f
        WHERE f.key = 'agents_base_domain' AND pg_catalog.jsonb_typeof(f.value) = 'string';
    IF base IS NOT NULL AND pg_catalog.right(host, pg_catalog.char_length(base) + 1) = '.' || base THEN
        RETURN EXISTS (SELECT 1 FROM public.agents a
                       WHERE a.slug = pg_catalog.left(host, pg_catalog.char_length(host) - pg_catalog.char_length(base) - 1)
                         AND a.status = 'suspended');
    END IF;
    RETURN EXISTS (SELECT 1 FROM public.agent_domains d JOIN public.agents a ON a.id = d.agent_id
                   WHERE d.hostname = host::extensions.citext AND d.status = 'active' AND a.status = 'suspended');
END;
$$;
REVOKE ALL ON FUNCTION public.agent_unavailable(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.agent_unavailable(text) TO anon, authenticated, service_role;

-- The review queue: new agents in review and live agents with a version waiting.
CREATE OR REPLACE FUNCTION public.list_pending_agents()
RETURNS TABLE (id uuid, slug text, name text, org_id uuid, org_name text, created_at timestamptz)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_user_admin(auth.uid()) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    RETURN QUERY
        SELECT a.id, a.slug, a.name, a.org_id, o.name, a.created_at
        FROM public.agents a JOIN public.organisations o ON o.id = a.org_id
        WHERE a.status = 'pending_review' OR (a.status = 'live' AND a.pending_version IS NOT NULL)
        ORDER BY COALESCE(a.submitted_at, a.created_at);
END;
$$;

DROP FUNCTION IF EXISTS public.platform_agents_overview();
CREATE FUNCTION public.platform_agents_overview()
RETURNS TABLE (id uuid, slug text, name text, org_id uuid, org_name text, status public.agent_status,
               live_version text, plugin_id text, created_at timestamptz,
               installs_total int, installs_running int, installs_failed int, installs_pending int,
               signups_7d int, signups_30d int, last_activity_at timestamptz, running_versions jsonb,
               pending_version text, submitted_at timestamptz)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
BEGIN
    IF auth.uid() IS NULL OR NOT (SELECT public.is_user_admin((SELECT auth.uid()))) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    RETURN QUERY
        SELECT a.id, a.slug, a.name, a.org_id, o.name, a.status, a.live_version, a.plugin_id, a.created_at,
               (count(i.user_id) FILTER (WHERE i.state <> 'removed'))::int,
               (count(i.user_id) FILTER (WHERE i.state = 'running'))::int,
               (count(i.user_id) FILTER (WHERE i.state = 'failed'))::int,
               (count(i.user_id) FILTER (WHERE i.state = 'pending'))::int,
               (count(i.user_id) FILTER (WHERE i.created_at >= now() - interval '7 days'))::int,
               (count(i.user_id) FILTER (WHERE i.created_at >= now() - interval '30 days'))::int,
               max(i.updated_at),
               COALESCE((SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object('version', v.version, 'count', v.n)
                                                     ORDER BY v.n DESC, v.version)
                         FROM (SELECT r.running_version AS version, count(*)::int AS n
                               FROM public.agent_installs r
                               WHERE r.agent_id = a.id AND r.state <> 'removed' AND r.running_version IS NOT NULL
                               GROUP BY r.running_version) v), '[]'::jsonb),
               a.pending_version, a.submitted_at
        FROM public.agents a
        JOIN public.organisations o ON o.id = a.org_id
        LEFT JOIN public.agent_installs i ON i.agent_id = a.id
        GROUP BY a.id, o.name
        ORDER BY a.created_at DESC;
END;
$$;
REVOKE ALL ON FUNCTION public.platform_agents_overview() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.platform_agents_overview() TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 4. What a platform admin decides on
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.platform_agent_detail(p_agent_id uuid) RETURNS jsonb
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    result jsonb;
BEGIN
    IF auth.uid() IS NULL OR NOT (SELECT public.is_user_admin((SELECT auth.uid()))) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    SELECT jsonb_build_object(
        'agent', jsonb_build_object(
            'id', a.id, 'slug', a.slug, 'name', a.name, 'org_id', a.org_id, 'org_name', o.name, 'org_slug', o.slug,
            'status', a.status, 'live_version', a.live_version, 'plugin_id', a.plugin_id,
            'brand', a.brand, 'model', a.model, 'signup_policy', a.signup_policy,
            'allowed_tools_count', pg_catalog.cardinality(a.allowed_tools),
            'allowed_connectors_count', pg_catalog.cardinality(a.allowed_connectors),
            'allowed_tools', pg_catalog.to_jsonb(a.allowed_tools),
            'allowed_connectors', pg_catalog.to_jsonb(a.allowed_connectors),
            'pending_version', a.pending_version, 'submitted_at', a.submitted_at, 'submitted_by_email', su.email,
            'review_note', a.review_note, 'suspended_at', a.suspended_at, 'suspended_reason', a.suspended_reason,
            'status_before_suspend', a.status_before_suspend,
            'invites_count', (SELECT count(*) FROM public.agent_invites v WHERE v.agent_id = a.id),
            'domains', COALESCE((SELECT jsonb_agg(jsonb_build_object('hostname', d.hostname, 'status', d.status) ORDER BY d.created_at)
                                 FROM public.agent_domains d WHERE d.agent_id = a.id), '[]'::jsonb),
            'created_at', a.created_at, 'created_by_email', u.email),
        'signups_by_day', (
            SELECT jsonb_agg(jsonb_build_object('day', d.day::text, 'count', COALESCE(c.n, 0)) ORDER BY d.day)
            FROM (SELECT g::date AS day FROM pg_catalog.generate_series(
                      (now() AT TIME ZONE 'UTC')::date - 29, (now() AT TIME ZONE 'UTC')::date, interval '1 day') g) d
            LEFT JOIN (SELECT (i.created_at AT TIME ZONE 'UTC')::date AS day, count(*)::int AS n
                       FROM public.agent_installs i WHERE i.agent_id = a.id GROUP BY 1) c ON c.day = d.day),
        'version_adoption', COALESCE((
            SELECT jsonb_agg(jsonb_build_object('version', v.version, 'count', v.n) ORDER BY v.n DESC, v.version)
            FROM (SELECT i.running_version AS version, count(*)::int AS n FROM public.agent_installs i
                  WHERE i.agent_id = a.id AND i.state <> 'removed' AND i.running_version IS NOT NULL
                  GROUP BY 1) v), '[]'::jsonb),
        'states', jsonb_build_object(
            'running', (SELECT count(*) FROM public.agent_installs i WHERE i.agent_id = a.id AND i.state = 'running'),
            'failed', (SELECT count(*) FROM public.agent_installs i WHERE i.agent_id = a.id AND i.state = 'failed'),
            'pending', (SELECT count(*) FROM public.agent_installs i WHERE i.agent_id = a.id AND i.state = 'pending'),
            'removed', (SELECT count(*) FROM public.agent_installs i WHERE i.agent_id = a.id AND i.state = 'removed')),
        'installs', COALESCE((
            SELECT jsonb_agg(jsonb_build_object('email', w.email, 'state', w.state, 'running_version', w.running_version,
                                                'detail', w.detail, 'created_at', w.created_at, 'updated_at', w.updated_at)
                             ORDER BY w.created_at DESC)
            FROM (SELECT iu.email, i.state, i.running_version, i.detail, i.created_at, i.updated_at
                  FROM public.agent_installs i JOIN auth.users iu ON iu.id = i.user_id
                  WHERE i.agent_id = a.id ORDER BY i.created_at DESC LIMIT 500) w), '[]'::jsonb))
    INTO result
    FROM public.agents a
    JOIN public.organisations o ON o.id = a.org_id
    LEFT JOIN auth.users u ON u.id = a.created_by
    LEFT JOIN auth.users su ON su.id = a.submitted_by
    WHERE a.id = p_agent_id;
    IF result IS NULL THEN
        RAISE EXCEPTION 'agent_not_found' USING ERRCODE = 'P0001';
    END IF;
    RETURN result;
END;
$$;

-- An organisation request with what is known about the requester and the domain.
CREATE OR REPLACE FUNCTION public.platform_org_request_detail(p_request_id uuid) RETURNS jsonb
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    r public.organisation_requests%ROWTYPE;
    u auth.users%ROWTYPE;
    claim record;
    result jsonb;
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_user_admin(auth.uid()) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    SELECT * INTO r FROM public.organisation_requests WHERE id = p_request_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'request_not_found' USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO u FROM auth.users WHERE id = r.requester_id;
    SELECT o.name, o.slug, d.verified INTO claim
        FROM public.organisation_domains d JOIN public.organisations o ON o.id = d.org_id
        WHERE r.domain IS NOT NULL AND d.domain = pg_catalog.lower(r.domain);
    result := pg_catalog.jsonb_build_object(
        'request', pg_catalog.jsonb_build_object(
            'id', r.id, 'name', r.name, 'slug', r.slug, 'description', r.description, 'domain', r.domain,
            'justification', r.justification, 'status', r.status, 'review_notes', r.review_notes,
            'reviewed_at', r.reviewed_at, 'reviewer_email', (SELECT x.email FROM auth.users x WHERE x.id = r.reviewer_id),
            'created_org_id', r.created_org_id, 'created_at', r.created_at),
        'requester', CASE WHEN u.id IS NULL THEN NULL ELSE pg_catalog.jsonb_build_object(
            'id', u.id, 'email', u.email,
            'display_name', COALESCE(u.raw_user_meta_data->>'full_name', u.raw_user_meta_data->>'name', u.raw_user_meta_data->>'display_name'),
            'created_at', u.created_at, 'email_confirmed_at', u.email_confirmed_at, 'last_sign_in_at', u.last_sign_in_at,
            'is_platform_admin', public.is_user_admin(u.id)) END,
        'owned_orgs', COALESCE((SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
                'id', o.id, 'name', o.name, 'slug', o.slug, 'created_at', o.created_at) ORDER BY o.created_at)
            FROM public.organisations o WHERE o.owner_id = r.requester_id AND NOT o.is_system), '[]'::jsonb),
        'member_orgs', COALESCE((SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
                'id', o.id, 'name', o.name, 'slug', o.slug, 'status', m.status, 'joined_at', m.joined_at) ORDER BY o.name)
            FROM public.organisation_members m JOIN public.organisations o ON o.id = m.org_id
            WHERE m.user_id = r.requester_id AND o.owner_id <> r.requester_id AND NOT o.is_system), '[]'::jsonb),
        'prior_requests', COALESCE((SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
                'id', p.id, 'name', p.name, 'slug', p.slug, 'status', p.status, 'review_notes', p.review_notes,
                'reviewed_at', p.reviewed_at, 'created_at', p.created_at) ORDER BY p.created_at DESC)
            FROM public.organisation_requests p WHERE p.requester_id = r.requester_id AND p.id <> r.id), '[]'::jsonb),
        'domain', CASE WHEN r.domain IS NULL THEN NULL ELSE pg_catalog.jsonb_build_object(
            'domain', pg_catalog.lower(r.domain),
            'reserved', EXISTS (SELECT 1 FROM public.reserved_email_domains red WHERE red.domain = pg_catalog.lower(r.domain)),
            'claimed_by', CASE WHEN claim.name IS NULL THEN NULL
                               ELSE pg_catalog.jsonb_build_object('name', claim.name, 'slug', claim.slug, 'verified', claim.verified) END,
            'requester_email_at_domain', pg_catalog.lower(pg_catalog.split_part(COALESCE(u.email, ''), '@', 2)) = pg_catalog.lower(r.domain),
            'requester_email_confirmed', u.email_confirmed_at IS NOT NULL) END);
    RETURN result;
END;
$$;
REVOKE ALL ON FUNCTION public.platform_org_request_detail(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.platform_org_request_detail(uuid) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 5. Email domain for a new organisation: what org_create would answer, asked before submitting.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.organisation_domain_problem(p_domain text)
RETURNS text LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT CASE
        WHEN x.d IS NULL THEN NULL
        WHEN x.d !~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$' THEN 'domain_invalid'
        WHEN EXISTS (SELECT 1 FROM public.reserved_email_domains red WHERE red.domain = x.d) THEN 'domain_reserved'
        WHEN EXISTS (SELECT 1 FROM public.organisation_domains od WHERE od.domain = x.d) THEN 'domain_taken'
    END
    FROM (SELECT NULLIF(pg_catalog.lower(pg_catalog.ltrim(pg_catalog.btrim(COALESCE(p_domain, '')), '@')), '') AS d) x;
$$;
REVOKE ALL ON FUNCTION public.organisation_domain_problem(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.organisation_domain_problem(text) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 6. brand.logo{source, crop} (validate_agent_brand, unchanged otherwise) and the unsuspend audit label.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.validate_agent_brand(p_brand jsonb, p_org_id uuid DEFAULT NULL,
                                                      p_agent_id uuid DEFAULT NULL)
RETURNS text LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    hex constant text := '^#[0-9a-fA-F]{6}$';
    k text;
    v jsonb;
    s jsonb;
    bg jsonb;
    link jsonb;
BEGIN
    IF p_brand IS NULL OR pg_catalog.jsonb_typeof(p_brand) <> 'object' THEN
        RETURN 'brand_not_object';
    END IF;
    IF pg_catalog.octet_length(p_brand::text) > 100000 THEN
        RETURN 'brand_too_large';
    END IF;
    FOR k, v IN SELECT * FROM pg_catalog.jsonb_each(p_brand) LOOP
        CASE k
        WHEN 'name', 'short_name' THEN
            IF pg_catalog.jsonb_typeof(v) <> 'string'
               OR pg_catalog.char_length(v #>> '{}') > (ARRAY[80, 30])[pg_catalog.array_position(ARRAY['name', 'short_name'], k)] THEN
                RETURN k || '_invalid';
            END IF;
        WHEN 'accent', 'accent_dark' THEN
            -- "" and null both mean "unset"; the console writes "" for no accent.
            IF v <> 'null'::jsonb AND NOT (pg_catalog.jsonb_typeof(v) = 'string'
                                           AND ((v #>> '{}') = '' OR (v #>> '{}') ~ hex)) THEN
                RETURN k || '_invalid';
            END IF;
        WHEN 'favicon_url', 'apple_touch_url' THEN
            IF v <> 'null'::jsonb AND NOT (pg_catalog.jsonb_typeof(v) = 'string'
                                           AND public.agent_brand_url_ok(v #>> '{}', p_org_id, p_agent_id)) THEN
                RETURN k || '_invalid';
            END IF;
        WHEN 'logo' THEN
            IF v = 'null'::jsonb THEN
                CONTINUE;
            END IF;
            IF pg_catalog.jsonb_typeof(v) <> 'object'
               OR EXISTS (SELECT 1 FROM pg_catalog.jsonb_object_keys(v) x
                          WHERE x NOT IN ('url', 'shape', 'background', 'source', 'crop')) THEN
                RETURN 'logo_invalid';
            END IF;
            -- The uploaded original (re-cropped later without uploading again), in the agent's own folder.
            IF v ? 'source' AND v->'source' <> 'null'::jsonb
               AND NOT (pg_catalog.jsonb_typeof(v->'source') = 'string'
                        AND public.agent_brand_url_ok(v->>'source', p_org_id, p_agent_id)) THEN
                RETURN 'logo_source_invalid';
            END IF;
            -- Where the original sits in the cropper's frame: numbers and one flag, nothing else.
            IF v ? 'crop' AND v->'crop' <> 'null'::jsonb AND (
                   pg_catalog.jsonb_typeof(v->'crop') <> 'object'
                   OR EXISTS (SELECT 1 FROM pg_catalog.jsonb_object_keys(v->'crop') x
                              WHERE x NOT IN ('x', 'y', 'scale', 'padding', 'trim', 'frame'))
                   OR EXISTS (SELECT 1 FROM pg_catalog.jsonb_each(v->'crop') e
                              WHERE (e.key = 'trim' AND pg_catalog.jsonb_typeof(e.value) <> 'boolean')
                                 OR (e.key <> 'trim' AND (pg_catalog.jsonb_typeof(e.value) <> 'number'
                                                          OR pg_catalog.abs((e.value #>> '{}')::numeric) > 100000)))
                   OR COALESCE((v->'crop'->>'scale')::numeric, 1) <= 0
                   OR COALESCE((v->'crop'->>'padding')::numeric, 0) NOT BETWEEN 0 AND 0.4
                   OR COALESCE((v->'crop'->>'frame')::numeric, 280) <= 0) THEN
                RETURN 'logo_crop_invalid';
            END IF;
            IF pg_catalog.jsonb_typeof(v->'url') IS DISTINCT FROM 'string'
               OR NOT public.agent_brand_url_ok(v->>'url', p_org_id, p_agent_id) THEN
                RETURN 'logo_url_invalid';
            END IF;
            IF (v->>'shape') IS NULL OR pg_catalog.jsonb_typeof(v->'shape') <> 'string'
               OR (v->>'shape') NOT IN ('square', 'rounded', 'circle') THEN
                RETURN 'logo_shape_invalid';
            END IF;
            IF v ? 'background' AND v->'background' <> 'null'::jsonb
               AND NOT (pg_catalog.jsonb_typeof(v->'background') = 'string' AND (v->>'background') ~ hex) THEN
                RETURN 'logo_background_invalid';
            END IF;
        WHEN 'signin' THEN
            s := v;
            IF pg_catalog.jsonb_typeof(s) <> 'object'
               OR EXISTS (SELECT 1 FROM pg_catalog.jsonb_object_keys(s) x
                          WHERE x NOT IN ('mode', 'layout', 'headline', 'subtext', 'notice', 'background',
                                          'show_powered_by', 'footer_links', 'custom_css', 'custom_html')) THEN
                RETURN 'signin_invalid';
            END IF;
            IF pg_catalog.jsonb_typeof(s->'mode') IS DISTINCT FROM 'string'
               OR (s->>'mode') NOT IN ('standard', 'custom_html') THEN
                RETURN 'signin_mode_invalid';
            END IF;
            IF s ? 'layout' AND (pg_catalog.jsonb_typeof(s->'layout') <> 'string'
                                 OR (s->>'layout') NOT IN ('centered', 'split', 'minimal')) THEN
                RETURN 'signin_layout_invalid';
            END IF;
            IF s ? 'headline' AND (pg_catalog.jsonb_typeof(s->'headline') <> 'string'
                                   OR pg_catalog.char_length(s->>'headline') > 80) THEN
                RETURN 'signin_headline_invalid';
            END IF;
            IF s ? 'subtext' AND (pg_catalog.jsonb_typeof(s->'subtext') <> 'string'
                                  OR pg_catalog.char_length(s->>'subtext') > 240) THEN
                RETURN 'signin_subtext_invalid';
            END IF;
            -- Plain text under the form (a compliance line, say); the page renders it as text, never markup.
            IF s ? 'notice' AND (pg_catalog.jsonb_typeof(s->'notice') <> 'string'
                                 OR pg_catalog.char_length(s->>'notice') > 300) THEN
                RETURN 'signin_notice_invalid';
            END IF;
            IF s ? 'show_powered_by' AND pg_catalog.jsonb_typeof(s->'show_powered_by') <> 'boolean' THEN
                RETURN 'signin_show_powered_by_invalid';
            END IF;
            IF s ? 'background' THEN
                bg := s->'background';
                IF pg_catalog.jsonb_typeof(bg) <> 'object'
                   OR EXISTS (SELECT 1 FROM pg_catalog.jsonb_object_keys(bg) x WHERE x NOT IN ('type', 'image_url'))
                   OR pg_catalog.jsonb_typeof(bg->'type') IS DISTINCT FROM 'string'
                   OR (bg->>'type') NOT IN ('plain', 'gradient', 'image') THEN
                    RETURN 'signin_background_invalid';
                END IF;
                IF (bg->>'type') = 'image' AND (pg_catalog.jsonb_typeof(bg->'image_url') IS DISTINCT FROM 'string'
                                                OR NOT public.agent_brand_url_ok(bg->>'image_url', p_org_id, p_agent_id)) THEN
                    RETURN 'signin_background_image_invalid';
                END IF;
                IF (bg->>'type') <> 'image' AND bg ? 'image_url' AND bg->'image_url' <> 'null'::jsonb THEN
                    RETURN 'signin_background_image_invalid';
                END IF;
            END IF;
            IF s ? 'footer_links' THEN
                IF pg_catalog.jsonb_typeof(s->'footer_links') <> 'array'
                   OR pg_catalog.jsonb_array_length(s->'footer_links') > 5 THEN
                    RETURN 'signin_footer_links_invalid';
                END IF;
                FOR link IN SELECT * FROM pg_catalog.jsonb_array_elements(s->'footer_links') LOOP
                    IF pg_catalog.jsonb_typeof(link) <> 'object'
                       OR EXISTS (SELECT 1 FROM pg_catalog.jsonb_object_keys(link) x WHERE x NOT IN ('label', 'url'))
                       OR pg_catalog.jsonb_typeof(link->'label') IS DISTINCT FROM 'string'
                       OR pg_catalog.btrim(link->>'label') = '' OR pg_catalog.char_length(link->>'label') > 40
                       OR pg_catalog.jsonb_typeof(link->'url') IS DISTINCT FROM 'string'
                       OR pg_catalog.char_length(link->>'url') > 2048
                       OR (link->>'url') !~ '^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[^\s"''<>\\]*)?$' THEN
                        RETURN 'signin_footer_link_invalid';
                    END IF;
                END LOOP;
            END IF;
            IF s ? 'custom_css' AND (pg_catalog.jsonb_typeof(s->'custom_css') <> 'string'
                                     OR pg_catalog.char_length(s->>'custom_css') > 20000) THEN
                RETURN 'signin_custom_css_invalid';
            END IF;
            IF s ? 'custom_html' AND (pg_catalog.jsonb_typeof(s->'custom_html') <> 'string'
                                      OR pg_catalog.char_length(s->>'custom_html') > 50000) THEN
                RETURN 'signin_custom_html_invalid';
            END IF;
            -- The real form mounts at exactly one <agent-signin></agent-signin>.
            IF (s->>'mode') = 'custom_html' AND (
                   (s->>'custom_html') IS NULL
                   OR (pg_catalog.char_length(pg_catalog.lower(s->>'custom_html'))
                       - pg_catalog.char_length(pg_catalog.replace(pg_catalog.lower(s->>'custom_html'), '<agent-signin', '')))
                      / pg_catalog.char_length('<agent-signin') <> 1
                   OR pg_catalog.strpos(pg_catalog.lower(s->>'custom_html'), '<agent-signin></agent-signin>') = 0) THEN
                RETURN 'signin_custom_html_mount_invalid';
            END IF;
        ELSE
            RETURN 'brand_unknown_key:' || pg_catalog.left(k, 40);
        END CASE;
    END LOOP;
    RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION public.agents_audit() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    actor uuid := public.agent_audit_actor();
    changed text[];
    brand_keys text[];
    act text;
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
            VALUES (NEW.org_id, NEW.id, actor, 'agent.create', jsonb_build_object('slug', NEW.slug, 'name', NEW.name));
        RETURN NULL;
    ELSIF TG_OP = 'DELETE' THEN
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
            VALUES (OLD.org_id, OLD.id, actor, 'agent.delete', jsonb_build_object('slug', OLD.slug));
        RETURN NULL;
    END IF;
    IF NEW.brand IS DISTINCT FROM OLD.brand THEN
        SELECT array_agg(k ORDER BY k) INTO brand_keys FROM (
            SELECT k FROM pg_catalog.jsonb_object_keys(NEW.brand) k
            UNION SELECT k FROM pg_catalog.jsonb_object_keys(OLD.brand) k) ks
        WHERE NEW.brand->k IS DISTINCT FROM OLD.brand->k;
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
            VALUES (NEW.org_id, NEW.id, actor, 'agent.brand_change', jsonb_build_object('keys', to_jsonb(brand_keys)));
    END IF;
    changed := array_remove(ARRAY[
        CASE WHEN NEW.name IS DISTINCT FROM OLD.name THEN 'name' END,
        CASE WHEN NEW.model IS DISTINCT FROM OLD.model THEN 'model' END,
        CASE WHEN NEW.signup_policy IS DISTINCT FROM OLD.signup_policy THEN 'signup_policy' END,
        CASE WHEN NEW.allowed_connectors IS DISTINCT FROM OLD.allowed_connectors THEN 'allowed_connectors' END,
        CASE WHEN NEW.allowed_tools IS DISTINCT FROM OLD.allowed_tools THEN 'allowed_tools' END,
        CASE WHEN NEW.plugin_id IS DISTINCT FROM OLD.plugin_id THEN 'plugin_id' END], NULL);
    IF cardinality(changed) > 0 THEN
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
            VALUES (NEW.org_id, NEW.id, actor, 'agent.settings_change', jsonb_build_object('fields', to_jsonb(changed)));
    END IF;
    IF NEW.live_version IS DISTINCT FROM OLD.live_version THEN
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
            VALUES (NEW.org_id, NEW.id, actor, 'agent.publish', jsonb_build_object(
                'version', NEW.live_version, 'from_version', OLD.live_version,
                'status', NEW.status, 'from_status', OLD.status));
    ELSIF NEW.status IS DISTINCT FROM OLD.status THEN
        act := CASE WHEN NEW.status = 'suspended' THEN 'agent.suspend'
                    WHEN OLD.status = 'suspended' THEN 'agent.unsuspend'
                    WHEN OLD.status = 'pending_review' AND NEW.status = 'live' THEN 'agent.approve'
                    WHEN OLD.status = 'pending_review' AND NEW.status = 'draft' THEN 'agent.reject'
                    ELSE 'agent.status' END;
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
            VALUES (NEW.org_id, NEW.id, actor, act, jsonb_build_object('status', NEW.status, 'from_status', OLD.status));
    END IF;
    RETURN NULL;
END;
$$;
DROP TRIGGER IF EXISTS agents_audit_on_change ON public.agents;
CREATE TRIGGER agents_audit_on_change AFTER INSERT OR UPDATE OR DELETE ON public.agents
    FOR EACH ROW EXECUTE FUNCTION public.agents_audit();
