-- Audio models (transcription, speech) metered in seconds, pooled into one allowance.
-- kind: chat reserves context_length; audio reserves max_output_tokens (max seconds per request).
-- allowance_model_id: this model draws from that model's allowance and usage pool (one level).
-- tokens_* columns hold seconds for audio models. Ledger rows keep the actual model_id.
BEGIN;
ALTER TABLE public.boss_ai_models
  ADD COLUMN kind text NOT NULL DEFAULT 'chat'
    CHECK (kind IN ('chat', 'transcription', 'speech')),
  ADD COLUMN voice text,
  ADD COLUMN allowance_model_id text REFERENCES public.boss_ai_models(id),
  ADD CONSTRAINT boss_ai_models_pool_not_self CHECK (allowance_model_id IS DISTINCT FROM id);

CREATE FUNCTION public.boss_ai_models_pool_guard()
RETURNS trigger LANGUAGE plpgsql SET search_path = '' AS $$
BEGIN
  IF NEW.allowance_model_id IS NOT NULL AND (
    EXISTS (SELECT 1 FROM public.boss_ai_models t
      WHERE t.id = NEW.allowance_model_id AND t.allowance_model_id IS NOT NULL)
    OR EXISTS (SELECT 1 FROM public.boss_ai_models d WHERE d.allowance_model_id = NEW.id)
  ) THEN
    RAISE EXCEPTION 'Allowance pools are one level deep' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER boss_ai_models_pool_guard BEFORE INSERT OR UPDATE OF allowance_model_id, id
  ON public.boss_ai_models FOR EACH ROW EXECUTE FUNCTION public.boss_ai_models_pool_guard();

-- Policy and allowances belong to the pool; publication, enablement and connection to the model.
CREATE OR REPLACE FUNCTION public.boss_ai_policy(p_user_id uuid, p_model_id text)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT CASE WHEN count(*) = 0 THEN NULL ELSE jsonb_build_object(
    'day', max(a.tokens_per_day), 'week', max(a.tokens_per_week),
    'month', max(a.tokens_per_month), 'concurrent', max(a.max_concurrent)) END
  FROM public.boss_ai_models m
  JOIN public.boss_ai_connections c ON c.id = m.connection_id AND c.enabled
  JOIN public.boss_ai_allowances a ON a.model_id = coalesce(m.allowance_model_id, m.id)
  WHERE m.id = p_model_id AND m.published AND m.enabled
    AND EXISTS (SELECT 1 FROM auth.users u WHERE u.id = p_user_id
      AND (u.banned_until IS NULL OR u.banned_until < now()))
    AND public.user_has_permission(p_user_id, a.permission_name);
$$;

CREATE OR REPLACE FUNCTION public.boss_ai_usage(p_user_id uuid, p_model_id text)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  v_policy jsonb := public.boss_ai_policy(p_user_id, p_model_id);
  v_pool text := (SELECT coalesce(allowance_model_id, id) FROM public.boss_ai_models
    WHERE id = p_model_id);
  v_result jsonb := '{}'::jsonb;
  v_period text;
  v_start timestamptz;
  v_used bigint;
BEGIN
  IF v_policy IS NULL THEN RETURN NULL; END IF;
  FOREACH v_period IN ARRAY ARRAY['day','week','month'] LOOP
    v_start := date_trunc(v_period, now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC';
    SELECT coalesce(sum(r.charged_tokens),0) INTO v_used FROM public.boss_ai_requests r
      JOIN public.boss_ai_models m ON m.id = r.model_id
      WHERE r.user_id = p_user_id AND coalesce(m.allowance_model_id, m.id) = v_pool
        AND r.started_at >= v_start;
    v_result := v_result || jsonb_build_object(v_period, jsonb_build_object(
      'limit', (v_policy->>v_period)::bigint, 'used', v_used,
      'remaining', greatest(0, (v_policy->>v_period)::bigint - v_used),
      'resets_at', (v_start AT TIME ZONE 'UTC' + ('1 ' || v_period)::interval) AT TIME ZONE 'UTC'));
  END LOOP;
  RETURN v_result;
END;
$$;

CREATE OR REPLACE FUNCTION public.boss_ai_catalog(p_user_id uuid)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT coalesce(jsonb_agg(jsonb_build_object(
    'id', m.id, 'object', 'model', 'name', m.display_name, 'kind', m.kind,
    'context_length', m.context_length, 'max_output_tokens', m.max_output_tokens,
    'capabilities', m.capabilities, 'is_default', m.is_default,
    'allowance', public.boss_ai_usage(p_user_id, m.id))
    ORDER BY m.is_default DESC, m.id), '[]'::jsonb)
  FROM public.boss_ai_models m WHERE public.boss_ai_policy(p_user_id, m.id) IS NOT NULL;
$$;

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
    ELSE jsonb_build_object('model',model,'connection',connection) END
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
  RETURN jsonb_build_object('model',to_jsonb(v_model),'connection',to_jsonb(v_connection));
END;
$$;

REVOKE ALL ON FUNCTION public.boss_ai_models_pool_guard() FROM PUBLIC, anon, authenticated;
COMMIT;
