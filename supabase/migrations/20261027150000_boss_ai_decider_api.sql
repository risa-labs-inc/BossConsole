-- Decider API per router: the hosted LiteLLM embeddings decider (default) or a SystemOne-style
-- /v1/systemone endpoint (Ollaya/Jev, for local use). Also lets a connection point at a loopback or
-- host.docker.internal http URL; the edge function still refuses plain http unless
-- BOSS_AI_ALLOW_LOCAL_AGENT=1, so production behaviour is unchanged.
BEGIN;
ALTER TABLE public.boss_ai_routers
  ADD COLUMN decider_api text NOT NULL DEFAULT 'litellm_embeddings'
    CHECK (decider_api IN ('litellm_embeddings', 'systemone'));
ALTER TABLE public.boss_ai_connections DROP CONSTRAINT IF EXISTS boss_ai_connections_base_url_check;
ALTER TABLE public.boss_ai_connections ADD CONSTRAINT boss_ai_connections_base_url_check
  CHECK (base_url ~ '^https://[^/?#]+'
    OR base_url ~ '^http://(127\.0\.0\.1|localhost|host\.docker\.internal)(:[0-9]{1,5})?(/|$)');
COMMIT;
