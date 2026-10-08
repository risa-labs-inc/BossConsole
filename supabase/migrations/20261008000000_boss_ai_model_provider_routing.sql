-- Operator-set upstream routing per model, forwarded as the OpenRouter `provider` object
-- (order, allow_fallbacks, ignore, ...). Callers still cannot send `provider`. NULL = none.
BEGIN;
ALTER TABLE public.boss_ai_models ADD COLUMN IF NOT EXISTS provider_routing jsonb
  CHECK (provider_routing IS NULL OR jsonb_typeof(provider_routing) = 'object');
COMMIT;
