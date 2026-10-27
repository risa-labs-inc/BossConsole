-- Native Anthropic Messages connections and per-model ordered fallbacks.
-- boss_ai_models.fallbacks = [{connection_id, upstream_model, provider_routing?, usd_per_mtok_input?,
-- usd_per_mtok_output?, usd_per_mtok_cache_read?, usd_per_mtok_cache_write?}] (at most 4); the
-- prices bill a call served by that fallback (absent: the model's own prices).
-- boss_ai_lookup/reserve return the resolved list as `fallbacks`, each entry carrying its
-- connection row (disabled or missing connections are dropped at resolution).
-- lookup/reserve bodies are copied from 20261015000000_boss_ai_voice.sql plus the `fallbacks` key.
BEGIN;
ALTER TABLE public.boss_ai_connections DROP CONSTRAINT IF EXISTS boss_ai_connections_api_type_check;
ALTER TABLE public.boss_ai_connections ADD CONSTRAINT boss_ai_connections_api_type_check
  CHECK (api_type IN ('openai_chat', 'openai_responses', 'anthropic_messages'));

CREATE FUNCTION public.boss_ai_fallbacks_valid(f jsonb)
RETURNS boolean LANGUAGE sql IMMUTABLE SET search_path = '' AS $$
  SELECT CASE
    WHEN f IS NULL OR jsonb_typeof(f) <> 'array' THEN false
    WHEN jsonb_array_length(f) > 4 THEN false
    ELSE NOT EXISTS (
      SELECT 1 FROM jsonb_array_elements(f) AS e(v) WHERE NOT coalesce(CASE
        WHEN jsonb_typeof(v) <> 'object' THEN false
        ELSE NOT EXISTS (SELECT 1 FROM jsonb_object_keys(v) AS k
            WHERE k NOT IN ('connection_id','upstream_model','provider_routing',
              'usd_per_mtok_input','usd_per_mtok_output','usd_per_mtok_cache_read','usd_per_mtok_cache_write'))
          AND NOT EXISTS (SELECT 1 FROM jsonb_each(v) AS p(k, x)
            WHERE k LIKE 'usd_per_mtok_%' AND CASE WHEN jsonb_typeof(x) = 'number' THEN (x#>>'{}')::numeric < 0 ELSE true END)
          AND jsonb_typeof(v->'connection_id') = 'string' AND length(v->>'connection_id') > 0
          AND jsonb_typeof(v->'upstream_model') = 'string' AND length(v->>'upstream_model') > 0
          AND (NOT v ? 'provider_routing' OR (jsonb_typeof(v->'provider_routing') = 'object'
            AND public.boss_ai_provider_routing_valid(v->'provider_routing')))
        END, false))
  END
$$;

ALTER TABLE public.boss_ai_models
  ADD COLUMN fallbacks jsonb NOT NULL DEFAULT '[]'::jsonb,
  ADD CONSTRAINT boss_ai_models_fallbacks_check CHECK (public.boss_ai_fallbacks_valid(fallbacks));
COMMENT ON COLUMN public.boss_ai_models.fallbacks IS
  'Ordered alternate routes tried after the primary: [{connection_id, upstream_model, provider_routing?, usd_per_mtok_*?}], at most 4.';

-- A CHECK cannot read other tables: existence is enforced here, and a connection a fallback uses
-- cannot be deleted or renamed.
CREATE FUNCTION public.boss_ai_models_fallback_guard()
RETURNS trigger LANGUAGE plpgsql SET search_path = '' AS $$
BEGIN
  IF jsonb_typeof(NEW.fallbacks) = 'array' AND EXISTS (
    SELECT 1 FROM jsonb_array_elements(NEW.fallbacks) AS e(v)
    WHERE NOT EXISTS (SELECT 1 FROM public.boss_ai_connections c WHERE c.id = e.v->>'connection_id')
  ) THEN
    RAISE EXCEPTION 'Fallback connection does not exist' USING ERRCODE = '23503';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER boss_ai_models_fallback_guard BEFORE INSERT OR UPDATE OF fallbacks
  ON public.boss_ai_models FOR EACH ROW EXECUTE FUNCTION public.boss_ai_models_fallback_guard();

CREATE FUNCTION public.boss_ai_connections_fallback_guard()
RETURNS trigger LANGUAGE plpgsql SET search_path = '' AS $$
BEGIN
  IF EXISTS (SELECT 1 FROM public.boss_ai_models m WHERE jsonb_typeof(m.fallbacks) = 'array'
      AND m.fallbacks @> jsonb_build_array(jsonb_build_object('connection_id', OLD.id))) THEN
    RAISE EXCEPTION 'Connection is used by a model fallback' USING ERRCODE = '23503';
  END IF;
  RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;
CREATE TRIGGER boss_ai_connections_fallback_guard BEFORE DELETE OR UPDATE OF id
  ON public.boss_ai_connections FOR EACH ROW EXECUTE FUNCTION public.boss_ai_connections_fallback_guard();

CREATE FUNCTION public.boss_ai_resolve_fallbacks(f jsonb)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT coalesce(jsonb_agg(
    jsonb_build_object('connection_id', e.v->>'connection_id', 'upstream_model', e.v->>'upstream_model')
    || CASE WHEN e.v ? 'provider_routing'
         THEN jsonb_build_object('provider_routing', e.v->'provider_routing') ELSE '{}'::jsonb END
    || coalesce((SELECT jsonb_object_agg(k, e.v->k) FROM unnest(ARRAY['usd_per_mtok_input',
         'usd_per_mtok_output','usd_per_mtok_cache_read','usd_per_mtok_cache_write']) AS k
         WHERE e.v ? k), '{}'::jsonb)
    || jsonb_build_object('connection', to_jsonb(c)) ORDER BY e.ord), '[]'::jsonb)
  FROM jsonb_array_elements(CASE WHEN jsonb_typeof(f) = 'array' THEN f ELSE '[]'::jsonb END)
    WITH ORDINALITY AS e(v, ord)
  JOIN public.boss_ai_connections c ON c.id = e.v->>'connection_id' AND c.enabled
$$;
REVOKE ALL ON FUNCTION public.boss_ai_resolve_fallbacks(jsonb) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.boss_ai_resolve_fallbacks(jsonb) TO service_role;
REVOKE ALL ON FUNCTION public.boss_ai_models_fallback_guard() FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.boss_ai_connections_fallback_guard() FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.boss_ai_lookup(p_user_id uuid, p_model_id text)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  WITH config AS MATERIALIZED (
    SELECT to_jsonb(m) AS model, to_jsonb(c) AS connection,
      CASE WHEN m.kind = 'chat' THEN m.context_length ELSE m.max_output_tokens END AS reserve,
      public.boss_ai_policy(p_user_id,p_model_id) AS policy
    FROM public.boss_ai_models m JOIN public.boss_ai_connections c ON c.id=m.connection_id
    WHERE m.id=p_model_id
  )
  SELECT CASE WHEN least((policy->>'day')::bigint,(policy->>'week')::bigint,
    (policy->>'month')::bigint) < reserve
    THEN jsonb_build_object('error','misconfigured_allowance')
    ELSE jsonb_build_object('model',model,'connection',connection,
      'fallbacks',public.boss_ai_resolve_fallbacks(model->'fallbacks')) END
  FROM config WHERE policy IS NOT NULL;
$$;

CREATE OR REPLACE FUNCTION public.boss_ai_reserve(p_user_id uuid, p_model_id text, p_request_id uuid)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  v_model public.boss_ai_models;
  v_connection public.boss_ai_connections;
  v_pool text;
  v_amount bigint;
  v_policy jsonb;
  v_usage jsonb;
  v_period text;
  v_active integer;
BEGIN
  IF current_setting('transaction_isolation') <> 'read committed' THEN
    RAISE EXCEPTION 'BOSS AI admission requires READ COMMITTED' USING ERRCODE='25000';
  END IF;
  SELECT coalesce(allowance_model_id, id) INTO v_pool FROM public.boss_ai_models
    WHERE id = p_model_id;
  PERFORM pg_advisory_xact_lock(hashtextextended(
    p_user_id::text || ':' || coalesce(v_pool, p_model_id), 0));
  SELECT * INTO v_model FROM public.boss_ai_models WHERE id = p_model_id FOR SHARE;
  SELECT * INTO v_connection FROM public.boss_ai_connections WHERE id = v_model.connection_id FOR SHARE;
  v_policy := public.boss_ai_policy(p_user_id, p_model_id);
  IF v_policy IS NULL THEN RETURN jsonb_build_object('error','forbidden'); END IF;
  IF EXISTS (SELECT 1 FROM public.boss_ai_requests WHERE id = p_request_id) THEN
    RETURN jsonb_build_object('error','duplicate');
  END IF;
  v_amount := CASE WHEN v_model.kind = 'chat' THEN v_model.context_length
    ELSE v_model.max_output_tokens END;
  v_usage := public.boss_ai_usage(p_user_id, p_model_id);
  FOREACH v_period IN ARRAY ARRAY['day','week','month'] LOOP
    IF (v_usage->v_period->>'remaining')::bigint < v_amount THEN
      RETURN jsonb_build_object('error','allowance_exceeded', 'usage',v_usage);
    END IF;
  END LOOP;
  SELECT count(*) INTO v_active FROM public.boss_ai_requests r
    JOIN public.boss_ai_models m ON m.id = r.model_id
    WHERE r.user_id = p_user_id AND coalesce(m.allowance_model_id, m.id) = v_pool
      AND NOT r.settled AND r.lease_until > now();
  IF v_active >= (v_policy->>'concurrent')::integer THEN
    RETURN jsonb_build_object('error','concurrency_exceeded');
  END IF;
  INSERT INTO public.boss_ai_requests(id,user_id,model_id,reserved_tokens,charged_tokens)
    VALUES(p_request_id,p_user_id,p_model_id,v_amount,v_amount);
  RETURN jsonb_build_object('model',to_jsonb(v_model),'connection',to_jsonb(v_connection),
    'fallbacks',public.boss_ai_resolve_fallbacks(v_model.fallbacks));
END;
$$;
COMMIT;
