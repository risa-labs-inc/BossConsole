-- A sticky tier only exists to keep a prompt cache warm, and that cache dies a fixed time after its
-- last use. The decision is valid while its last use is within that TTL, and each hit renews it.
BEGIN;

ALTER TABLE public.boss_ai_route_decisions
  ADD COLUMN last_used_at timestamptz NOT NULL DEFAULT now();

DROP FUNCTION public.boss_ai_route_get(text, integer);
CREATE FUNCTION public.boss_ai_route_get(p_key text, p_ttl_seconds integer)
RETURNS text LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
  UPDATE public.boss_ai_route_decisions SET last_used_at = now()
  WHERE key = p_key AND last_used_at > now() - make_interval(secs => p_ttl_seconds)
  RETURNING tier;
$$;

CREATE OR REPLACE FUNCTION public.boss_ai_route_put(p_key text, p_user_id uuid, p_model_id text, p_tier text)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
  DELETE FROM public.boss_ai_route_decisions
    WHERE user_id = p_user_id AND last_used_at < now() - interval '2 hours';
  INSERT INTO public.boss_ai_route_decisions(key, user_id, model_id, tier, created_at, last_used_at)
    VALUES (p_key, p_user_id, p_model_id, p_tier, now(), now())
    ON CONFLICT (key) DO UPDATE
      SET tier = excluded.tier, created_at = excluded.created_at, last_used_at = excluded.last_used_at;
END;
$$;

REVOKE ALL ON FUNCTION public.boss_ai_route_get(text,integer) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.boss_ai_route_get(text,integer) TO service_role;
COMMIT;
