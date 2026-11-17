-- People and pods: see and change each person's monthly BOSS AI budget override (boss_ai_user_budgets).
-- Enforcement is unchanged (boss_ai_router_reserve). Platform readers see the caps; changing them needs
-- platform.ai_caps.manage, held by admins and by platform_ai_cap_manager, which admins grant.

INSERT INTO public.permissions (name, description, is_system) VALUES
    ('platform.ai_caps.manage', 'Change people''s monthly BOSS AI caps in the agents platform console (audited).', true)
ON CONFLICT (name) DO UPDATE SET is_system = true, description = EXCLUDED.description;

INSERT INTO public.roles (name, description, is_system) VALUES
    ('platform_ai_cap_manager', 'Changes people''s monthly BOSS AI caps in the agents platform console. Add-on to platform_viewer.', true)
ON CONFLICT (name) DO NOTHING;

INSERT INTO public.role_hierarchy (parent_role_id, child_role_id)
SELECT parent.id, child.id FROM public.roles parent, public.roles child
 WHERE parent.name = 'admin' AND child.name = 'platform_ai_cap_manager'
ON CONFLICT DO NOTHING;

INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM public.roles r, public.permissions p
 WHERE r.name = 'platform_ai_cap_manager' AND p.name = 'platform.ai_caps.manage'
ON CONFLICT DO NOTHING;

-- Admin, or the permission through a global role. Org roles are excluded: 'platform' is not a reserved
-- domain in is_org_grantable_permission, so user_has_permission would let an org grant it to itself.
CREATE OR REPLACE FUNCTION public.is_platform_ai_cap_manager(p_user_id uuid) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT p_user_id IS NOT NULL AND (public.is_user_admin(p_user_id) OR EXISTS (
        SELECT 1 FROM public.user_roles ur
          JOIN public.role_permissions rp ON rp.role_id = ur.role_id
          JOIN public.permissions p ON p.id = rp.permission_id
         WHERE ur.user_id = p_user_id AND p.name = 'platform.ai_caps.manage'
           AND NOT EXISTS (SELECT 1 FROM public.organisation_roles orl WHERE orl.role_id = ur.role_id)));
$$;
REVOKE ALL ON FUNCTION public.is_platform_ai_cap_manager(uuid) FROM PUBLIC, anon, authenticated;

-- Whether the caller may change caps (the console needs platform access as well).
CREATE OR REPLACE FUNCTION public.platform_ai_cap_access() RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT public.is_platform_reader(auth.uid()) AND public.is_platform_ai_cap_manager(auth.uid());
$$;
REVOKE ALL ON FUNCTION public.platform_ai_cap_access() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.platform_ai_cap_access() TO authenticated;

-- Each enabled router's default, and per person: the effective cap, their override, and this month's spend
-- on it, counted exactly as boss_ai_router_reserve counts it (routed calls, UTC month).
CREATE OR REPLACE FUNCTION public.platform_ai_caps(p_user_ids uuid[])
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_actor uuid := public.require_platform_reader();
    v_month timestamptz := date_trunc('month', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC';
    result jsonb;
BEGIN
    IF pg_catalog.cardinality(p_user_ids) > 500 THEN
        RAISE EXCEPTION 'too_many_users' USING ERRCODE = '22023';
    END IF;
    WITH routers AS (
        SELECT r.model_id, m.display_name, r.monthly_usd_limit
          FROM public.boss_ai_routers r JOIN public.boss_ai_models m ON m.id = r.model_id
         WHERE r.enabled
    ), spend AS (
        SELECT q.user_id, q.routed_from, pg_catalog.sum(COALESCE(q.cost_usd, q.est_cost_usd, 0)) AS spent
          FROM public.boss_ai_requests q
         WHERE q.user_id = ANY (p_user_ids) AND q.started_at >= v_month
           AND q.routed_from IN (SELECT model_id FROM routers)
         GROUP BY q.user_id, q.routed_from
    ), cells AS (
        SELECT u.id AS user_id, r.model_id, pg_catalog.jsonb_build_object(
                   'limit_usd', COALESCE(b.monthly_usd_limit, r.monthly_usd_limit),
                   'override_usd', b.monthly_usd_limit, 'note', b.note, 'updated_at', b.updated_at,
                   'spent_usd', pg_catalog.round(COALESCE(s.spent, 0), 4)) AS cap
          FROM pg_catalog.unnest(p_user_ids) AS u(id)
          CROSS JOIN routers r
          LEFT JOIN public.boss_ai_user_budgets b ON b.user_id = u.id AND b.router_model_id = r.model_id
          LEFT JOIN spend s ON s.user_id = u.id AND s.routed_from = r.model_id
    )
    SELECT pg_catalog.jsonb_build_object(
               'month_start', v_month,
               'resets_at', (v_month AT TIME ZONE 'UTC' + interval '1 month') AT TIME ZONE 'UTC',
               'can_edit', public.is_platform_ai_cap_manager(v_actor),
               'routers', COALESCE((SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
                   'model_id', r.model_id, 'name', r.display_name, 'default_usd', r.monthly_usd_limit) ORDER BY r.model_id)
                   FROM routers r), '[]'::jsonb),
               'users', COALESCE((SELECT pg_catalog.jsonb_object_agg(x.user_id::text, pg_catalog.jsonb_build_object(
                   'cap_manager', public.is_platform_ai_cap_manager(x.user_id),
                   'caps', COALESCE((SELECT pg_catalog.jsonb_object_agg(c.model_id, c.cap) FROM cells c WHERE c.user_id = x.user_id), '{}'::jsonb)))
                   FROM (SELECT DISTINCT u.id AS user_id FROM pg_catalog.unnest(p_user_ids) AS u(id)) x), '{}'::jsonb))
      INTO result;
    RETURN result;
END;
$$;
REVOKE ALL ON FUNCTION public.platform_ai_caps(uuid[]) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.platform_ai_caps(uuid[]) TO authenticated;

-- Sets (p_monthly_usd > 0) or clears (NULL: back to the router default) a person's cap. Audited with old and new.
CREATE OR REPLACE FUNCTION public.platform_set_ai_cap(p_user_id uuid, p_router_model_id text, p_monthly_usd numeric, p_note text DEFAULT NULL)
RETURNS jsonb
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_actor uuid := public.require_platform_reader();
    v_old numeric;
    v_old_note text;
    v_default numeric;
    v_note text := NULLIF(pg_catalog.btrim(COALESCE(p_note, '')), '');
BEGIN
    IF NOT public.is_platform_ai_cap_manager(v_actor) THEN
        RAISE EXCEPTION 'no_ai_cap_access' USING ERRCODE = '42501';
    END IF;
    IF p_user_id IS NULL OR p_router_model_id IS NULL THEN
        RAISE EXCEPTION 'bad_input' USING ERRCODE = '22023';
    END IF;
    IF p_monthly_usd IS NOT NULL AND (p_monthly_usd <= 0 OR p_monthly_usd > 100000) THEN
        RAISE EXCEPTION 'cap_out_of_range' USING ERRCODE = '22023';
    END IF;
    IF pg_catalog.char_length(COALESCE(v_note, '')) > 500 THEN
        RAISE EXCEPTION 'note_too_long' USING ERRCODE = '22023';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM auth.users u WHERE u.id = p_user_id AND u.deleted_at IS NULL) THEN
        RAISE EXCEPTION 'user_not_found' USING ERRCODE = 'P0002';
    END IF;
    SELECT r.monthly_usd_limit INTO v_default FROM public.boss_ai_routers r WHERE r.model_id = p_router_model_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'router_not_found' USING ERRCODE = 'P0002';
    END IF;
    SELECT b.monthly_usd_limit, b.note INTO v_old, v_old_note FROM public.boss_ai_user_budgets b
     WHERE b.user_id = p_user_id AND b.router_model_id = p_router_model_id FOR UPDATE;
    IF p_monthly_usd IS NULL THEN
        DELETE FROM public.boss_ai_user_budgets b WHERE b.user_id = p_user_id AND b.router_model_id = p_router_model_id;
    ELSE
        p_monthly_usd := pg_catalog.round(p_monthly_usd, 2);
        INSERT INTO public.boss_ai_user_budgets (user_id, router_model_id, monthly_usd_limit, note)
            VALUES (p_user_id, p_router_model_id, p_monthly_usd, v_note)
        ON CONFLICT (user_id, router_model_id) DO UPDATE SET monthly_usd_limit = EXCLUDED.monthly_usd_limit, note = EXCLUDED.note;
    END IF;
    -- The stored note changes with the amount, so either change is audited.
    IF v_old IS DISTINCT FROM p_monthly_usd OR (p_monthly_usd IS NOT NULL AND v_old_note IS DISTINCT FROM v_note) THEN
        INSERT INTO public.platform_audit_log (actor, action, target_user, detail)
            VALUES (v_actor, CASE WHEN p_monthly_usd IS NULL THEN 'ai_cap.clear' ELSE 'ai_cap.set' END, p_user_id,
                    pg_catalog.jsonb_build_object('router', p_router_model_id, 'from_usd', v_old, 'to_usd', p_monthly_usd,
                                                  'default_usd', v_default, 'note', v_note, 'from_note', v_old_note));
    END IF;
    RETURN pg_catalog.jsonb_build_object('limit_usd', COALESCE(p_monthly_usd, v_default), 'override_usd', p_monthly_usd);
END;
$$;
REVOKE ALL ON FUNCTION public.platform_set_ai_cap(uuid, text, numeric, text) FROM PUBLIC, anon, service_role;
GRANT EXECUTE ON FUNCTION public.platform_set_ai_cap(uuid, text, numeric, text) TO authenticated;

-- Grants or revokes platform_ai_cap_manager. Admins only; audited. Returns whether they can change caps now.
CREATE OR REPLACE FUNCTION public.platform_set_ai_cap_manager(p_user_id uuid, p_grant boolean, p_note text DEFAULT NULL)
RETURNS boolean
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_actor uuid := public.require_platform_admin();
    v_role uuid;
    v_changed int;
BEGIN
    IF p_user_id IS NULL OR p_grant IS NULL THEN
        RAISE EXCEPTION 'bad_input' USING ERRCODE = '22023';
    END IF;
    IF pg_catalog.char_length(COALESCE(p_note, '')) > 500 THEN
        RAISE EXCEPTION 'note_too_long' USING ERRCODE = '22023';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM auth.users u WHERE u.id = p_user_id AND u.deleted_at IS NULL) THEN
        RAISE EXCEPTION 'user_not_found' USING ERRCODE = 'P0002';
    END IF;
    SELECT r.id INTO v_role FROM public.roles r WHERE r.name = 'platform_ai_cap_manager';
    IF p_grant THEN
        INSERT INTO public.user_roles (user_id, role_id, assigned_by) VALUES (p_user_id, v_role, v_actor)
            ON CONFLICT (user_id, role_id) DO NOTHING;
    ELSE
        DELETE FROM public.user_roles ur WHERE ur.user_id = p_user_id AND ur.role_id = v_role;
    END IF;
    GET DIAGNOSTICS v_changed = ROW_COUNT;
    IF v_changed > 0 THEN
        INSERT INTO public.platform_audit_log (actor, action, target_user, detail)
            VALUES (v_actor, CASE WHEN p_grant THEN 'platform_role.ai_cap_grant' ELSE 'platform_role.ai_cap_revoke' END, p_user_id,
                    pg_catalog.jsonb_build_object('note', NULLIF(pg_catalog.btrim(COALESCE(p_note, '')), '')));
    END IF;
    RETURN public.is_platform_ai_cap_manager(p_user_id);
END;
$$;
REVOKE ALL ON FUNCTION public.platform_set_ai_cap_manager(uuid, boolean, text) FROM PUBLIC, anon, service_role;
GRANT EXECUTE ON FUNCTION public.platform_set_ai_cap_manager(uuid, boolean, text) TO authenticated;
