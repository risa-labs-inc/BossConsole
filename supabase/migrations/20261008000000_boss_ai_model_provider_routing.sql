-- Operator-set upstream routing per model, forwarded by boss-ai as the OpenRouter `provider`
-- object. NULL sends nothing. Callers still cannot send `provider`. boss_ai_lookup/reserve
-- return to_jsonb(m), so no RPC change is needed; the function re-validates element shapes.
BEGIN;
ALTER TABLE public.boss_ai_models ADD COLUMN IF NOT EXISTS provider_routing jsonb;
ALTER TABLE public.boss_ai_models DROP CONSTRAINT IF EXISTS boss_ai_models_provider_routing_check;
ALTER TABLE public.boss_ai_models ADD CONSTRAINT boss_ai_models_provider_routing_check CHECK (
  CASE WHEN provider_routing IS NULL THEN true
  WHEN jsonb_typeof(provider_routing) <> 'object' THEN false
  ELSE provider_routing - ARRAY['order','only','ignore','allow_fallbacks','require_parameters','sort'] = '{}'::jsonb
    AND coalesce(jsonb_typeof(provider_routing -> 'order'), 'array') = 'array'
    AND coalesce(jsonb_typeof(provider_routing -> 'only'), 'array') = 'array'
    AND coalesce(jsonb_typeof(provider_routing -> 'ignore'), 'array') = 'array'
    AND coalesce(jsonb_typeof(provider_routing -> 'allow_fallbacks'), 'boolean') = 'boolean'
    AND coalesce(jsonb_typeof(provider_routing -> 'require_parameters'), 'boolean') = 'boolean'
    AND coalesce(provider_routing ->> 'sort', 'price') IN ('price','throughput','latency')
  END
);
COMMENT ON COLUMN public.boss_ai_models.provider_routing IS
  'OpenRouter provider object sent upstream (order/only/ignore/allow_fallbacks/require_parameters/sort). NULL = none.';
COMMIT;
