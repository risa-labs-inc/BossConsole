-- Platform console: each person's BOSS AI usage this month, last month and all time (UTC months, as the
-- router budget counts them). Tokens are charged_tokens on chat models (audio models meter seconds there).
-- USD is cost_usd (else the pre-call estimate); calls before per-call pricing (2026-10-28) have neither and
-- are counted in `unpriced`, so the console can say a total is partial instead of inventing a price.
CREATE OR REPLACE FUNCTION public.platform_ai_usage(p_user_ids uuid[])
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_month timestamptz := date_trunc('month', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC';
    v_last timestamptz := date_trunc('month', (now() AT TIME ZONE 'UTC') - interval '1 month') AT TIME ZONE 'UTC';
    result jsonb;
BEGIN
    PERFORM public.require_platform_reader();
    IF pg_catalog.cardinality(p_user_ids) > 500 THEN
        RAISE EXCEPTION 'too_many_users' USING ERRCODE = '22023';
    END IF;
    WITH r AS (
        SELECT q.user_id, q.started_at, q.charged_tokens, m.kind,
               COALESCE(q.cost_usd, q.est_cost_usd) AS usd
          FROM public.boss_ai_requests q
          JOIN public.boss_ai_models m ON m.id = q.model_id
         WHERE q.user_id = ANY (p_user_ids)
    ), agg AS (
        SELECT r.user_id, p.period,
               pg_catalog.count(*)::bigint AS requests,
               COALESCE(pg_catalog.sum(r.charged_tokens) FILTER (WHERE r.kind = 'chat'), 0)::bigint AS tokens,
               COALESCE(pg_catalog.sum(r.usd), 0) AS cost_usd,
               (pg_catalog.count(*) FILTER (WHERE r.usd IS NULL))::bigint AS unpriced,
               pg_catalog.max(r.started_at) AS last_at
          FROM r
          CROSS JOIN LATERAL (VALUES ('all_time'),
                                     (CASE WHEN r.started_at >= v_month THEN 'month' END),
                                     (CASE WHEN r.started_at >= v_last AND r.started_at < v_month THEN 'last_month' END)) AS p(period)
         WHERE p.period IS NOT NULL
         GROUP BY r.user_id, p.period
    ), per_user AS (
        SELECT a.user_id, pg_catalog.jsonb_object_agg(a.period, pg_catalog.jsonb_build_object(
                   'requests', a.requests, 'tokens', a.tokens, 'cost_usd', pg_catalog.round(a.cost_usd, 4),
                   'unpriced', a.unpriced, 'last_at', a.last_at)) AS usage
          FROM agg a GROUP BY a.user_id
    )
    SELECT pg_catalog.jsonb_build_object(
               'month_start', v_month, 'last_month_start', v_last,
               'priced_since', (SELECT pg_catalog.min(q.started_at) FROM public.boss_ai_requests q
                                 WHERE q.cost_usd IS NOT NULL OR q.est_cost_usd IS NOT NULL),
               'users', COALESCE((SELECT pg_catalog.jsonb_object_agg(u.user_id::text, u.usage) FROM per_user u), '{}'::jsonb))
      INTO result;
    RETURN result;
END;
$$;
REVOKE ALL ON FUNCTION public.platform_ai_usage(uuid[]) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.platform_ai_usage(uuid[]) TO authenticated;
