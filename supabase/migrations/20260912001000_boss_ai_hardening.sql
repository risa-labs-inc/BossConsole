BEGIN;
-- Never allow a routing configuration to disclose the AI-token signing key.
ALTER TABLE public.boss_ai_connections
  DROP CONSTRAINT boss_ai_connections_api_key_secret_check,
  ADD CONSTRAINT boss_ai_connections_api_key_secret_check
    CHECK (api_key_secret ~ '^BOSS_AI_[A-Z0-9_]+$' AND api_key_secret <> 'BOSS_AI_SIGNING_SECRET');

-- A malformed upstream counter must not charge beyond the admitted request bound.
CREATE OR REPLACE FUNCTION public.boss_ai_settle(p_request_id uuid, p_tokens bigint)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
  IF p_tokens IS NOT NULL AND p_tokens < 0 THEN RAISE EXCEPTION 'Invalid usage'; END IF;
  UPDATE public.boss_ai_requests
    SET charged_tokens = least(coalesce(p_tokens,reserved_tokens),reserved_tokens), settled = true
    WHERE id = p_request_id AND NOT settled;
END;
$$;
COMMIT;
