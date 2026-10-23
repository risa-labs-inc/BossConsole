-- platform_agents_overview(): also the versions the pods actually run. After a rollback (or while a rollout is
-- under way) a pod runs something other than live_version; the overview table shows that without a detail
-- call per agent. running_versions = [{version, count}] over installs that are not removed and report a
-- running version, most common first.
DROP FUNCTION IF EXISTS public.platform_agents_overview();
CREATE FUNCTION public.platform_agents_overview()
RETURNS TABLE (id uuid, slug text, name text, org_id uuid, org_name text, status public.agent_status,
               live_version text, plugin_id text, created_at timestamptz,
               installs_total int, installs_running int, installs_failed int, installs_pending int,
               signups_7d int, signups_30d int, last_activity_at timestamptz, running_versions jsonb)
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
                               GROUP BY r.running_version) v), '[]'::jsonb)
        FROM public.agents a
        JOIN public.organisations o ON o.id = a.org_id
        LEFT JOIN public.agent_installs i ON i.agent_id = a.id
        GROUP BY a.id, o.name
        ORDER BY a.created_at DESC;
END;
$$;
REVOKE ALL ON FUNCTION public.platform_agents_overview() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.platform_agents_overview() TO authenticated, service_role;
