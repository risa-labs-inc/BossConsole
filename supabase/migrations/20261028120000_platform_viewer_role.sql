-- Platform viewer: a global role that reads everything the agents-platform console shows and changes nothing.
-- Read RPCs move from is_user_admin to is_platform_reader (admin or viewer); every write keeps its admin check.
-- Also: platform_access() for the console and chat/server, platform_set_viewer() for admins, and
-- platform_record_access() so chat/server can audit a reader opening someone's Optimist conversation.

INSERT INTO public.roles (name, description, is_system) VALUES
    ('platform_viewer', 'Reads the agents platform console (agents, people, pods, requests, settings, conversations) without taking any action.', true)
ON CONFLICT (name) DO NOTHING;

-- Under admin, like every other global role (get_grantable_role_ids, get_role_descendants).
INSERT INTO public.role_hierarchy (parent_role_id, child_role_id)
SELECT parent.id, child.id FROM public.roles parent, public.roles child
 WHERE parent.name = 'admin' AND child.name = 'platform_viewer'
ON CONFLICT DO NOTHING;

-- The org-request queue is gated by this permission, not by is_user_admin.
INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM public.roles r, public.permissions p
 WHERE r.name = 'platform_viewer' AND p.name = 'organisation.request_read'
ON CONFLICT DO NOTHING;

CREATE OR REPLACE FUNCTION public.is_platform_viewer(p_user_id uuid) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT p_user_id IS NOT NULL AND EXISTS (
        SELECT 1 FROM public.user_roles ur JOIN public.roles r ON r.id = ur.role_id
         WHERE ur.user_id = p_user_id AND r.name = 'platform_viewer');
$$;
REVOKE ALL ON FUNCTION public.is_platform_viewer(uuid) FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.is_platform_reader(p_user_id uuid) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT p_user_id IS NOT NULL AND (public.is_user_admin(p_user_id) OR public.is_platform_viewer(p_user_id));
$$;
REVOKE ALL ON FUNCTION public.is_platform_reader(uuid) FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.require_platform_reader() RETURNS uuid
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v uuid := auth.uid();
BEGIN
    IF v IS NULL OR NOT public.is_platform_reader(v) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    RETURN v;
END;
$$;
REVOKE ALL ON FUNCTION public.require_platform_reader() FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.platform_role_of(p_user_id uuid) RETURNS text
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT CASE WHEN p_user_id IS NULL THEN NULL
                WHEN public.is_user_admin(p_user_id) THEN 'admin'
                WHEN public.is_platform_viewer(p_user_id) THEN 'viewer' END;
$$;
REVOKE ALL ON FUNCTION public.platform_role_of(uuid) FROM PUBLIC, anon, authenticated;

-- The caller's platform access: 'admin', 'viewer' or NULL.
CREATE OR REPLACE FUNCTION public.platform_access() RETURNS text
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT public.platform_role_of(auth.uid());
$$;
REVOKE ALL ON FUNCTION public.platform_access() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.platform_access() TO authenticated;

-- Grants or revokes the viewer role. Admins only; audited.
CREATE OR REPLACE FUNCTION public.platform_set_viewer(p_user_id uuid, p_viewer boolean, p_note text DEFAULT NULL)
RETURNS text
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_actor uuid := public.require_platform_admin();
    v_role uuid;
    v_changed int;
BEGIN
    IF p_user_id IS NULL OR p_viewer IS NULL THEN
        RAISE EXCEPTION 'bad_input' USING ERRCODE = '22023';
    END IF;
    IF pg_catalog.char_length(COALESCE(p_note, '')) > 500 THEN
        RAISE EXCEPTION 'note_too_long' USING ERRCODE = '22023';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM auth.users u WHERE u.id = p_user_id AND u.deleted_at IS NULL) THEN
        RAISE EXCEPTION 'user_not_found' USING ERRCODE = 'P0002';
    END IF;
    SELECT r.id INTO v_role FROM public.roles r WHERE r.name = 'platform_viewer';
    IF p_viewer THEN
        INSERT INTO public.user_roles (user_id, role_id, assigned_by) VALUES (p_user_id, v_role, v_actor)
            ON CONFLICT (user_id, role_id) DO NOTHING;
    ELSE
        DELETE FROM public.user_roles ur WHERE ur.user_id = p_user_id AND ur.role_id = v_role;
    END IF;
    GET DIAGNOSTICS v_changed = ROW_COUNT;
    IF v_changed > 0 THEN
        INSERT INTO public.platform_audit_log (actor, action, target_user, detail)
            VALUES (v_actor, CASE WHEN p_viewer THEN 'platform_role.viewer_grant' ELSE 'platform_role.viewer_revoke' END, p_user_id,
                    pg_catalog.jsonb_build_object('note', NULLIF(pg_catalog.btrim(COALESCE(p_note, '')), '')));
    END IF;
    RETURN public.platform_role_of(p_user_id);
END;
$$;
REVOKE ALL ON FUNCTION public.platform_set_viewer(uuid, boolean, text) FROM PUBLIC, anon, service_role;
GRANT EXECUTE ON FUNCTION public.platform_set_viewer(uuid, boolean, text) TO authenticated;

-- chat/server records a reader opening someone's conversation before it returns any of it (fails closed).
CREATE OR REPLACE FUNCTION public.platform_record_access(p_target_email text, p_what text)
RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_actor uuid := public.require_platform_reader();
    v_email text := pg_catalog.lower(pg_catalog.btrim(COALESCE(p_target_email, '')));
BEGIN
    IF p_what IS NULL OR p_what NOT IN ('optimist.conversation', 'optimist.activity') THEN
        RAISE EXCEPTION 'bad_what' USING ERRCODE = '22023';
    END IF;
    IF v_email = '' OR pg_catalog.char_length(v_email) > 320 THEN
        RAISE EXCEPTION 'bad_email' USING ERRCODE = '22023';
    END IF;
    INSERT INTO public.platform_audit_log (actor, action, target_user, detail)
        VALUES (v_actor, 'view.' || p_what,
                (SELECT u.id FROM auth.users u WHERE pg_catalog.lower(u.email::text) = v_email LIMIT 1),
                pg_catalog.jsonb_build_object('email', v_email, 'role', public.platform_role_of(v_actor)));
END;
$$;
REVOKE ALL ON FUNCTION public.platform_record_access(text, text) FROM PUBLIC, anon, service_role;
GRANT EXECUTE ON FUNCTION public.platform_record_access(text, text) TO authenticated;

-- ----------------------------------------------------------------------------
-- Read RPCs: the newest body of each, with is_platform_reader in place of the admin check.
-- ----------------------------------------------------------------------------
-- list_pending_agents: body from 20261022120000_console_feedback.sql; only the guard changes.
CREATE OR REPLACE FUNCTION public.list_pending_agents()
RETURNS TABLE (id uuid, slug text, name text, org_id uuid, org_name text, created_at timestamptz)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_platform_reader(auth.uid()) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    RETURN QUERY
        SELECT a.id, a.slug, a.name, a.org_id, o.name, a.created_at
        FROM public.agents a JOIN public.organisations o ON o.id = a.org_id
        WHERE a.status = 'pending_review' OR (a.status = 'live' AND a.pending_version IS NOT NULL)
        ORDER BY COALESCE(a.submitted_at, a.created_at);
END;
$$;

-- platform_agents_overview: body from 20261022120000_console_feedback.sql; only the guard changes.
CREATE OR REPLACE FUNCTION public.platform_agents_overview()
RETURNS TABLE (id uuid, slug text, name text, org_id uuid, org_name text, status public.agent_status,
               live_version text, plugin_id text, created_at timestamptz,
               installs_total int, installs_running int, installs_failed int, installs_pending int,
               signups_7d int, signups_30d int, last_activity_at timestamptz, running_versions jsonb,
               pending_version text, submitted_at timestamptz)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
BEGIN
    IF auth.uid() IS NULL OR NOT (SELECT public.is_platform_reader((SELECT auth.uid()))) THEN
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

-- platform_agent_detail: body from 20261022120000_console_feedback.sql; only the guard changes.
CREATE OR REPLACE FUNCTION public.platform_agent_detail(p_agent_id uuid) RETURNS jsonb
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    result jsonb;
BEGIN
    IF auth.uid() IS NULL OR NOT (SELECT public.is_platform_reader((SELECT auth.uid()))) THEN
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

-- platform_settings_history: body from 20261023100000_qa_walk_fixes.sql; only the guard changes.
CREATE OR REPLACE FUNCTION public.platform_settings_history(p_limit int DEFAULT 20, p_before timestamptz DEFAULT NULL)
RETURNS TABLE (at timestamptz, actor_email text, key text, from_value jsonb, to_value jsonb)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_platform_reader(auth.uid()) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    RETURN QUERY
        SELECT l.at, u.email::text, l.detail->>'key', l.detail->'from', l.detail->'to'
        FROM public.platform_audit_log l LEFT JOIN auth.users u ON u.id = l.actor
        WHERE l.action = 'platform.flag_set' AND (p_before IS NULL OR l.at < p_before)
        ORDER BY l.at DESC, l.id DESC
        LIMIT LEAST(GREATEST(COALESCE(p_limit, 20), 1), 100);
END;
$$;

-- platform_org_request_detail: body from 20261022120000_console_feedback.sql; only the guard changes.
CREATE OR REPLACE FUNCTION public.platform_org_request_detail(p_request_id uuid) RETURNS jsonb
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    r public.organisation_requests%ROWTYPE;
    u auth.users%ROWTYPE;
    claim record;
    result jsonb;
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_platform_reader(auth.uid()) THEN
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

-- platform_web_pod_requests: body from 20261021140000_web_pod_requests.sql; only the guard changes.
CREATE OR REPLACE FUNCTION public.platform_web_pod_requests(p_include_decided boolean DEFAULT false, p_limit int DEFAULT 200)
RETURNS TABLE (id uuid, user_id uuid, email text, status text, requested_at timestamptz, decided_at timestamptz,
               decider_email text, note text, provisioned_at timestamptz, detail text)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_platform_reader(auth.uid()) THEN RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501'; END IF;
    RETURN QUERY SELECT w.id, w.user_id, u.email::text, w.status, w.requested_at, w.decided_at, d.email::text, w.note, w.provisioned_at, w.detail
        FROM public.web_pod_requests w
        JOIN auth.users u ON u.id = w.user_id
        LEFT JOIN auth.users d ON d.id = w.decided_by
        WHERE p_include_decided OR w.status = 'pending'
        ORDER BY (w.status = 'pending') DESC, COALESCE(w.decided_at, w.requested_at) DESC
        LIMIT LEAST(GREATEST(COALESCE(p_limit, 200), 1), 500);
END;
$$;

-- platform_web_pod_request_count: body from 20261021140000_web_pod_requests.sql; only the guard changes.
CREATE OR REPLACE FUNCTION public.platform_web_pod_request_count() RETURNS int
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_platform_reader(auth.uid()) THEN RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501'; END IF;
    RETURN (SELECT count(*)::int FROM public.web_pod_requests WHERE status = 'pending');
END;
$$;

-- platform_inventory: body from 20261023200000_platform_runtime_inventory.sql; only the guard changes.
CREATE OR REPLACE FUNCTION public.platform_inventory() RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $$
DECLARE result jsonb;
BEGIN
 PERFORM public.require_platform_reader();
 WITH sources AS (
  SELECT s.*,s.synced_at IS NULL OR NOT s.runtime_available OR s.observed_at<statement_timestamp()-interval '180 seconds' AS stale
  FROM public.platform_inventory_sources s
 ), pods AS (
  SELECT p.deployment,p.instance_id,p.state,p.capacity,p.image,p.tier,s.stale,
    jsonb_build_object('state',p.runtime_state,'health',p.health,'started_at',p.started_at,'restart_count',p.restart_count) AS runtime,
    COALESCE((SELECT jsonb_agg(jsonb_build_object('email',u.email,'boss_user_id',o.user_id,'state',o.state) ORDER BY u.email)
      FROM public.platform_pod_owners o JOIN auth.users u ON u.id=o.user_id
      WHERE o.deployment=p.deployment AND o.instance_id=p.instance_id),'[]'::jsonb) AS users
  FROM public.platform_pod_inventory p JOIN sources s ON s.deployment=p.deployment
 ) SELECT jsonb_build_object(
  'instances',COALESCE((SELECT jsonb_agg(to_jsonb(p) ORDER BY deployment,instance_id) FROM pods p),'[]'::jsonb),
  'builtins',jsonb_build_object('optimist',COALESCE((SELECT bool_or(optimist_configured) FROM sources),false),
                             'max',COALESCE((SELECT bool_or(max_configured) FROM sources),false)),
  'runtime_available',COALESCE((SELECT bool_and(runtime_available) FROM sources),false),
  'synced_at',(SELECT max(synced_at) FROM sources),
  'stale',COALESCE((SELECT bool_or(stale) FROM sources),true),
  'deployments',COALESCE((SELECT jsonb_agg(jsonb_build_object('deployment',deployment,'observed_at',observed_at,
     'synced_at',synced_at,'last_attempt_at',last_attempt_at,'runtime_available',runtime_available,'stale',stale) ORDER BY deployment) FROM sources),'[]'::jsonb)
 ) INTO result;
 RETURN result;
END;
$$;

-- platform_pod_tier_history: body from 20261021120000_pod_tiers.sql; only the guard changes.
CREATE OR REPLACE FUNCTION public.platform_pod_tier_history(p_user_id uuid, p_limit int DEFAULT 50)
RETURNS TABLE (id bigint, event text, tier text, previous_tier text, actor_email text, note text, method text, at timestamptz)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    PERFORM public.require_platform_reader();
    RETURN QUERY
    SELECT h.id, h.event, h.tier, h.previous_tier, u.email::text, h.note, h.method, h.at
      FROM public.pod_tier_history h
      LEFT JOIN auth.users u ON u.id = h.actor
     WHERE h.user_id = p_user_id
     ORDER BY h.at DESC, h.id DESC
     LIMIT LEAST(GREATEST(COALESCE(p_limit, 50), 1), 200);
END;
$$;

-- user_can_view_plugin_row: body from 20260803000000_plugins_org_ownership.sql; only the guard changes.
CREATE OR REPLACE FUNCTION "public"."user_can_view_plugin_row"(
    "p_user_id" "uuid",
    "p_visibility" "text",
    "p_org_id" "uuid",
    "p_author_id" "uuid",
    "p_published" boolean
) RETURNS boolean
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    SELECT CASE
        -- Anonymous browsing of the public store keeps working.
        WHEN p_visibility = 'public'  AND p_published THEN true
        WHEN p_user_id IS NULL        THEN false
        WHEN public.is_platform_reader(p_user_id) THEN true
        -- An author always sees their own work, including unpublished drafts.
        WHEN p_author_id = p_user_id  THEN true
        WHEN p_visibility = 'org'      THEN p_published AND public.user_is_org_member(p_user_id, p_org_id)
        -- 'unlisted' is installable-by-link, so it is deliberately NOT visible to
        -- ordinary members -- only to the author (above) and organisation admins.
        WHEN p_visibility = 'unlisted' THEN public.user_is_org_admin(p_user_id, p_org_id)
        ELSE false
    END;
$$;

-- platform_people_page: body from 20261026090000_platform_people_page.sql; reader guard, plus each person's platform_role.
CREATE OR REPLACE FUNCTION public.platform_people_page(p_after uuid DEFAULT NULL, p_limit int DEFAULT 100, p_query text DEFAULT NULL)
RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_q text := pg_catalog.lower(NULLIF(pg_catalog.btrim(COALESCE(p_query, '')), ''));
    v_limit int := LEAST(GREATEST(COALESCE(p_limit, 100), 1), 200);
    result jsonb;
BEGIN
    PERFORM public.require_platform_reader();
    IF pg_catalog.char_length(COALESCE(v_q, '')) > 320 THEN
        RAISE EXCEPTION 'query_too_long' USING ERRCODE = '22023';
    END IF;
        WITH people AS (
        SELECT u.id, u.email::text AS email
          FROM auth.users u
         WHERE NOT EXISTS (SELECT 1 FROM public.agents_service_accounts s WHERE s.user_id = u.id)
           AND u.email IS NOT NULL
           AND u.deleted_at IS NULL
           AND (p_after IS NULL OR u.id > p_after)
           AND (v_q IS NULL OR pg_catalog.strpos(pg_catalog.lower(u.email::text),v_q)>0)
         ORDER BY u.id LIMIT v_limit+1
    ), inst AS (
        SELECT i.user_id,
               pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
                   'agent_id', a.id, 'slug', a.slug, 'name', a.name, 'state', i.state, 'instance_id', i.instance_id,
                   'last_seen_at', i.last_seen_at, 'running_version', i.running_version) ORDER BY a.slug) AS installs,
               pg_catalog.max(i.last_seen_at) AS last_seen_at
          FROM public.agent_installs i JOIN public.agents a ON a.id = i.agent_id
         WHERE i.state <> 'removed' AND i.user_id IN (SELECT p.id FROM people p)
         GROUP BY i.user_id
    )
    , page AS (
    SELECT p.id AS user_id, p.email, t.tier, t.note AS tier_note, t.assigned_at AS tier_assigned_at,
           ab.email::text AS tier_assigned_by_email, inst.last_seen_at,
           COALESCE(inst.installs, '[]'::jsonb) AS installs,
           public.platform_role_of(p.id) AS platform_role
      FROM people p
      LEFT JOIN public.pod_tiers t ON t.user_id = p.id
      LEFT JOIN auth.users ab ON ab.id = t.assigned_by
      LEFT JOIN inst ON inst.user_id = p.id
     ORDER BY p.id LIMIT v_limit
    )
    SELECT pg_catalog.jsonb_build_object('people',COALESCE((SELECT pg_catalog.jsonb_agg(pg_catalog.to_jsonb(p) ORDER BY p.user_id) FROM page p),'[]'::jsonb),
      'next_cursor',CASE WHEN (SELECT count(*) FROM people)>v_limit THEN (SELECT user_id FROM page ORDER BY user_id DESC LIMIT 1) ELSE NULL END)
      INTO result;
    RETURN result;
END;
$$;
