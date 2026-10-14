-- Operator-set upstream routing per model, forwarded by boss-ai as the OpenRouter `provider`
-- object. NULL sends nothing. Callers still cannot send `provider`. boss_ai_lookup/reserve
-- return to_jsonb(m), so no RPC change is needed. Rules mirror providerRouting() in wire.ts.
BEGIN;
CREATE OR REPLACE FUNCTION public.boss_ai_provider_routing_valid(r jsonb)
RETURNS boolean LANGUAGE sql IMMUTABLE SET search_path = '' AS $$
  SELECT CASE WHEN r IS NULL THEN true
  WHEN jsonb_typeof(r) <> 'object' THEN false
  ELSE NOT EXISTS (
    SELECT 1 FROM jsonb_each(r) AS e(k, v) WHERE NOT CASE
      WHEN k IN ('order','only','ignore') THEN CASE
        WHEN jsonb_typeof(v) <> 'array' THEN false
        ELSE jsonb_array_length(v) BETWEEN 1 AND 32 AND NOT EXISTS (
          SELECT 1 FROM jsonb_array_elements(v) AS s(slug)
          WHERE jsonb_typeof(slug) <> 'string' OR (slug #>> '{}') !~ '^[a-z0-9][a-z0-9._/-]{0,63}$')
        END
      WHEN k IN ('allow_fallbacks','require_parameters') THEN jsonb_typeof(v) = 'boolean'
      WHEN k = 'sort' THEN jsonb_typeof(v) = 'string' AND (v #>> '{}') IN ('price','throughput','latency')
      ELSE false END)
  END
$$;
ALTER TABLE public.boss_ai_models ADD COLUMN IF NOT EXISTS provider_routing jsonb;
ALTER TABLE public.boss_ai_models DROP CONSTRAINT IF EXISTS boss_ai_models_provider_routing_check;
ALTER TABLE public.boss_ai_models ADD CONSTRAINT boss_ai_models_provider_routing_check
  CHECK (public.boss_ai_provider_routing_valid(provider_routing));
COMMENT ON COLUMN public.boss_ai_models.provider_routing IS
  'OpenRouter provider object sent upstream (order/only/ignore/allow_fallbacks/require_parameters/sort). NULL = none.';
COMMIT;
