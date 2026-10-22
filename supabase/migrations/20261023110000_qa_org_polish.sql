-- Agents console QA polish (org pages and Platform > People and pods).
-- 1. Custom roles keep the name as the administrator typed it (organisation_roles.display_name); the stored key stays
--    <org slug>_<suffix>. A role made without a description keeps none (no 'Custom role of ...' filler).
-- 2. platform_people_pods lists people whose pod came from a web BOSS request (approved or set up), not only people
--    with a tier or an agent install.

-- ----------------------------------------------------------------------------
-- 1. Role display names
-- ----------------------------------------------------------------------------
ALTER TABLE public.organisation_roles ADD COLUMN IF NOT EXISTS display_name text;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'organisation_roles_display_name_check') THEN
        ALTER TABLE public.organisation_roles ADD CONSTRAINT organisation_roles_display_name_check
            CHECK (display_name IS NULL OR pg_catalog.char_length(display_name) BETWEEN 1 AND 40);
    END IF;
END $$;
COMMENT ON COLUMN public.organisation_roles.display_name IS
    'The role''s name as typed in the agents console (custom roles); null: derived from roles.name.';

-- One signature only: PostgREST cannot choose between overloads that differ by a defaulted argument.
DROP FUNCTION IF EXISTS public.org_team_create_role(uuid, text, text[], text);
CREATE OR REPLACE FUNCTION public.org_team_create_role(p_org_id uuid, p_suffix text, p_permissions text[],
                                                      p_description text DEFAULT NULL, p_display_name text DEFAULT NULL)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_actor uuid := public.org_team_require_admin(p_org_id);
    v_perms text[] := public.org_team_normalise_permissions(p_permissions);
    v_display text := NULLIF(pg_catalog.btrim(COALESCE(p_display_name, '')), '');
    v_role uuid;
BEGIN
    IF p_description IS NOT NULL AND pg_catalog.char_length(p_description) > 200 THEN
        RAISE EXCEPTION 'description_too_long' USING ERRCODE = 'P0001';
    END IF;
    IF v_display IS NOT NULL AND pg_catalog.char_length(v_display) > 40 THEN
        RAISE EXCEPTION 'display_name_too_long' USING ERRCODE = 'P0001';
    END IF;
    v_role := (public.org_team_unwrap(public.create_organisation_role(p_org_id, p_suffix, p_description, NULL))->>'role_id')::uuid;
    -- create_organisation_role fills a missing description with a generic sentence; none given means none.
    UPDATE public.roles SET description = NULLIF(pg_catalog.btrim(COALESCE(p_description, '')), '') WHERE id = v_role;
    UPDATE public.organisation_roles SET display_name = v_display WHERE org_id = p_org_id AND role_id = v_role;
    INSERT INTO public.role_permissions (role_id, permission_id)
        SELECT v_role, p.id FROM public.permissions p WHERE p.name = ANY (v_perms)
        ON CONFLICT (role_id, permission_id) DO NOTHING;
    RETURN v_role;
END;
$$;
REVOKE ALL ON FUNCTION public.org_team_create_role(uuid, text, text[], text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.org_team_create_role(uuid, text, text[], text, text) TO authenticated, service_role;

-- Same rows as before plus display_name (a new output column needs a drop).
DROP FUNCTION IF EXISTS public.org_team_roles(uuid);
CREATE FUNCTION public.org_team_roles(p_org_id uuid)
RETURNS TABLE (role_id uuid, name text, kind text, preset text, description text, permissions text[],
               agent_permissions text[], member_count int, grantable boolean, display_name text)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
DECLARE
    uid uuid := auth.uid();
BEGIN
    IF uid IS NULL OR p_org_id IS NULL OR NOT public.is_org_member(p_org_id) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    RETURN QUERY
        SELECT orl.role_id, r.name, orl.kind, orl.preset, r.description,
               COALESCE((SELECT array_agg(p.name ORDER BY p.name) FROM public.role_permissions rp
                         JOIN public.permissions p ON p.id = rp.permission_id WHERE rp.role_id = orl.role_id),
                        ARRAY[]::text[]),
               CASE WHEN orl.kind = 'admin' THEN public.agents_permission_catalog()
                    ELSE COALESCE(ARRAY(
                        SELECT c.perm FROM unnest(public.agents_permission_catalog()) WITH ORDINALITY AS c(perm, n)
                        WHERE EXISTS (
                            SELECT 1 FROM public.get_role_descendants(orl.role_id) AS d(role_id)
                            JOIN public.organisation_roles dorl ON dorl.role_id = d.role_id AND dorl.org_id = p_org_id
                            JOIN public.role_permissions rp ON rp.role_id = d.role_id
                            JOIN public.permissions p ON p.id = rp.permission_id
                            WHERE p.name = c.perm
                               OR (c.perm = 'agents.view' AND p.name = ANY (public.agents_permission_catalog())))
                        ORDER BY c.n), ARRAY[]::text[]) END,
               (SELECT count(*)::int FROM public.user_roles ur
                JOIN public.organisation_members m ON m.user_id = ur.user_id AND m.org_id = p_org_id AND m.status = 'active'
                WHERE ur.role_id = orl.role_id),
               public.org_team_can_grant(uid, p_org_id, orl.role_id),
               orl.display_name
        FROM public.organisation_roles orl
        JOIN public.roles r ON r.id = orl.role_id
        WHERE orl.org_id = p_org_id
        ORDER BY CASE orl.kind WHEN 'admin' THEN 0 WHEN 'user' THEN 2 ELSE 1 END,
                 CASE orl.preset WHEN 'agent_manager' THEN 0 WHEN 'agent_editor' THEN 1 WHEN 'agent_analyst' THEN 2 ELSE 3 END,
                 r.name;
END;
$$;
REVOKE ALL ON FUNCTION public.org_team_roles(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.org_team_roles(uuid) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 2. People and pods: web BOSS pods count as having a pod
-- ----------------------------------------------------------------------------
-- People for the "People and pods" tab: with p_query, accounts whose email contains it; without, everyone with a
-- tier, an agent install, or a web BOSS request that was approved or set up. Service accounts are never listed.
-- installs: [{agent_id, slug, name, state, instance_id, last_seen_at, running_version}] (no data_dir, no detail).
CREATE OR REPLACE FUNCTION public.platform_people_pods(p_query text DEFAULT NULL, p_limit int DEFAULT 50)
RETURNS TABLE (user_id uuid, email text, tier text, tier_note text, tier_assigned_at timestamptz,
               tier_assigned_by_email text, last_seen_at timestamptz, installs jsonb)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_q text := pg_catalog.lower(NULLIF(pg_catalog.btrim(COALESCE(p_query, '')), ''));
    v_limit int := LEAST(GREATEST(COALESCE(p_limit, 50), 1), 200);
BEGIN
    PERFORM public.require_platform_admin();
    IF pg_catalog.char_length(COALESCE(v_q, '')) > 320 THEN
        RAISE EXCEPTION 'query_too_long' USING ERRCODE = '22023';
    END IF;
    RETURN QUERY
    WITH people AS (
        SELECT u.id, u.email::text AS email
          FROM auth.users u
         WHERE NOT EXISTS (SELECT 1 FROM public.agents_service_accounts s WHERE s.user_id = u.id)
           AND u.email IS NOT NULL
           AND (CASE WHEN v_q IS NOT NULL
                     THEN pg_catalog.strpos(pg_catalog.lower(u.email::text), v_q) > 0
                     ELSE EXISTS (SELECT 1 FROM public.pod_tiers t WHERE t.user_id = u.id)
                       OR EXISTS (SELECT 1 FROM public.agent_installs i WHERE i.user_id = u.id AND i.state <> 'removed')
                       OR EXISTS (SELECT 1 FROM public.web_pod_requests w
                                   WHERE w.user_id = u.id AND w.status IN ('approved', 'provisioned'))
                END)
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
    SELECT p.id, p.email, t.tier, t.note, t.assigned_at, ab.email::text, inst.last_seen_at,
           COALESCE(inst.installs, '[]'::jsonb)
      FROM people p
      LEFT JOIN public.pod_tiers t ON t.user_id = p.id
      LEFT JOIN auth.users ab ON ab.id = t.assigned_by
      LEFT JOIN inst ON inst.user_id = p.id
     ORDER BY inst.last_seen_at DESC NULLS LAST, p.email
     LIMIT v_limit;
END;
$$;
REVOKE ALL ON FUNCTION public.platform_people_pods(text, int) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.platform_people_pods(text, int) TO authenticated;
