-- Complete administrator directory, keyset-paginated independently of provisioning records.
CREATE FUNCTION public.platform_people_page(p_after uuid DEFAULT NULL, p_limit int DEFAULT 100, p_query text DEFAULT NULL)
RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_q text := pg_catalog.lower(NULLIF(pg_catalog.btrim(COALESCE(p_query, '')), ''));
    v_limit int := LEAST(GREATEST(COALESCE(p_limit, 100), 1), 200);
    result jsonb;
BEGIN
    PERFORM public.require_platform_admin();
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
           COALESCE(inst.installs, '[]'::jsonb) AS installs
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
REVOKE ALL ON FUNCTION public.platform_people_page(uuid,int,text) FROM PUBLIC,anon,service_role;
GRANT EXECUTE ON FUNCTION public.platform_people_page(uuid,int,text) TO authenticated;
