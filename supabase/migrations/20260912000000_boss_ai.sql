-- Managed inference. Only the Edge Function's service role can access this surface.
-- Usage is per user/model, regardless of which permission supplied the allowance.
BEGIN;
CREATE TABLE public.boss_ai_connections (
  id text PRIMARY KEY,
  base_url text NOT NULL CHECK (base_url ~ '^https://[^/?#]+'),
  api_key_secret text NOT NULL CHECK (api_key_secret ~ '^BOSS_AI_[A-Z0-9_]+$'),
  api_type text NOT NULL CHECK (api_type IN ('openai_chat', 'openai_responses')),
  enabled boolean NOT NULL DEFAULT true
);
CREATE TABLE public.boss_ai_models (
  id text PRIMARY KEY CHECK (id ~ '^[a-z0-9][a-z0-9._-]{0,99}$'),
  display_name text NOT NULL,
  connection_id text NOT NULL REFERENCES public.boss_ai_connections(id),
  upstream_model text NOT NULL CHECK (length(upstream_model) > 0),
  capabilities text[] NOT NULL DEFAULT ARRAY['text'],
  context_length integer NOT NULL CHECK (context_length BETWEEN 1024 AND 2000000),
  max_output_tokens integer NOT NULL CHECK (max_output_tokens BETWEEN 1 AND 2000000),
  published boolean NOT NULL DEFAULT false,
  enabled boolean NOT NULL DEFAULT true,
  is_default boolean NOT NULL DEFAULT false,
  CHECK (max_output_tokens <= context_length),
  CHECK (capabilities <@ ARRAY['text','tools','vision','structured_output','reasoning']::text[])
);
CREATE UNIQUE INDEX boss_ai_one_default ON public.boss_ai_models(is_default) WHERE is_default;
CREATE TABLE public.boss_ai_allowances (
  model_id text NOT NULL REFERENCES public.boss_ai_models(id),
  permission_name text NOT NULL REFERENCES public.permissions(name),
  tokens_per_day bigint NOT NULL CHECK (tokens_per_day > 0),
  tokens_per_week bigint NOT NULL CHECK (tokens_per_week > 0),
  tokens_per_month bigint NOT NULL CHECK (tokens_per_month > 0),
  max_concurrent integer NOT NULL DEFAULT 2 CHECK (max_concurrent BETWEEN 1 AND 100),
  PRIMARY KEY (model_id, permission_name)
);
CREATE TABLE public.boss_ai_requests (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  model_id text NOT NULL REFERENCES public.boss_ai_models(id),
  started_at timestamptz NOT NULL DEFAULT now(),
  lease_until timestamptz NOT NULL DEFAULT now() + interval '5 minutes',
  reserved_tokens bigint NOT NULL CHECK (reserved_tokens > 0),
  charged_tokens bigint NOT NULL CHECK (charged_tokens >= 0),
  settled boolean NOT NULL DEFAULT false
);
CREATE INDEX boss_ai_usage_lookup ON public.boss_ai_requests(user_id, model_id, started_at);

ALTER TABLE public.boss_ai_connections ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.boss_ai_models ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.boss_ai_allowances ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.boss_ai_requests ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.boss_ai_connections, public.boss_ai_models, public.boss_ai_allowances,
  public.boss_ai_requests FROM PUBLIC, anon, authenticated;
GRANT ALL ON public.boss_ai_connections, public.boss_ai_models, public.boss_ai_allowances,
  public.boss_ai_requests TO service_role;

INSERT INTO public.permissions(name, description, is_system)
VALUES ('ai.use', 'Use the included BOSS AI models within their allowance', true)
ON CONFLICT (name) DO NOTHING;
INSERT INTO public.role_permissions(role_id, permission_id)
SELECT r.id, p.id FROM public.roles r CROSS JOIN public.permissions p
WHERE r.name = 'user' AND p.name = 'ai.use'
ON CONFLICT (role_id, permission_id) DO NOTHING;

CREATE FUNCTION public.boss_ai_policy(p_user_id uuid, p_model_id text)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT CASE WHEN count(*) = 0 THEN NULL ELSE jsonb_build_object(
    'day', max(a.tokens_per_day), 'week', max(a.tokens_per_week),
    'month', max(a.tokens_per_month), 'concurrent', max(a.max_concurrent)) END
  FROM public.boss_ai_allowances a
  JOIN public.boss_ai_models m ON m.id = a.model_id AND m.published AND m.enabled
  JOIN public.boss_ai_connections c ON c.id = m.connection_id AND c.enabled
  WHERE a.model_id = p_model_id
    AND EXISTS (SELECT 1 FROM auth.users u WHERE u.id = p_user_id
      AND (u.banned_until IS NULL OR u.banned_until < now()))
    AND public.user_has_permission(p_user_id, a.permission_name);
$$;

CREATE FUNCTION public.boss_ai_usage(p_user_id uuid, p_model_id text)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  v_policy jsonb := public.boss_ai_policy(p_user_id, p_model_id);
  v_result jsonb := '{}'::jsonb;
  v_period text;
  v_start timestamptz;
  v_used bigint;
BEGIN
  IF v_policy IS NULL THEN RETURN NULL; END IF;
  FOREACH v_period IN ARRAY ARRAY['day','week','month'] LOOP
    v_start := date_trunc(v_period, now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC';
    SELECT coalesce(sum(charged_tokens),0) INTO v_used FROM public.boss_ai_requests
      WHERE user_id = p_user_id AND model_id = p_model_id AND started_at >= v_start;
    v_result := v_result || jsonb_build_object(v_period, jsonb_build_object(
      'limit', (v_policy->>v_period)::bigint, 'used', v_used,
      'remaining', greatest(0, (v_policy->>v_period)::bigint - v_used),
      'resets_at', (v_start AT TIME ZONE 'UTC' + ('1 ' || v_period)::interval) AT TIME ZONE 'UTC'));
  END LOOP;
  RETURN v_result;
END;
$$;

CREATE FUNCTION public.boss_ai_catalog(p_user_id uuid)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT coalesce(jsonb_agg(jsonb_build_object(
    'id', m.id, 'object', 'model', 'name', m.display_name,
    'context_length', m.context_length, 'max_output_tokens', m.max_output_tokens,
    'capabilities', m.capabilities, 'is_default', m.is_default,
    'allowance', public.boss_ai_usage(p_user_id, m.id))
    ORDER BY m.is_default DESC, m.id), '[]'::jsonb)
  FROM public.boss_ai_models m WHERE public.boss_ai_policy(p_user_id, m.id) IS NOT NULL;
$$;

-- Authorize and reserve under the same transaction lock. Every period counts the
-- reservation immediately, including requests whose worker dies before settlement.
CREATE FUNCTION public.boss_ai_reserve(p_user_id uuid, p_model_id text, p_request_id uuid)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  v_model public.boss_ai_models;
  v_connection public.boss_ai_connections;
  v_policy jsonb;
  v_usage jsonb;
  v_period text;
  v_active integer;
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended(p_user_id::text || ':' || p_model_id, 0));
  SELECT * INTO v_model FROM public.boss_ai_models WHERE id = p_model_id FOR SHARE;
  SELECT * INTO v_connection FROM public.boss_ai_connections WHERE id = v_model.connection_id FOR SHARE;
  v_policy := public.boss_ai_policy(p_user_id, p_model_id);
  IF v_policy IS NULL THEN RETURN jsonb_build_object('error','forbidden'); END IF;
  IF EXISTS (SELECT 1 FROM public.boss_ai_requests WHERE id = p_request_id) THEN
    RETURN jsonb_build_object('error','duplicate');
  END IF;
  v_usage := public.boss_ai_usage(p_user_id, p_model_id);
  FOREACH v_period IN ARRAY ARRAY['day','week','month'] LOOP
    IF (v_usage->v_period->>'remaining')::bigint < v_model.context_length THEN
      RETURN jsonb_build_object('error','allowance_exceeded', 'usage',v_usage);
    END IF;
  END LOOP;
  SELECT count(*) INTO v_active FROM public.boss_ai_requests WHERE user_id = p_user_id
    AND model_id = p_model_id AND NOT settled AND lease_until > now();
  IF v_active >= (v_policy->>'concurrent')::integer THEN
    RETURN jsonb_build_object('error','concurrency_exceeded');
  END IF;
  -- Reserve the full configured context as an upper bound, including image and
  -- reasoning tokens. No client estimate is trusted. Successful calls release excess.
  INSERT INTO public.boss_ai_requests(id,user_id,model_id,reserved_tokens,charged_tokens)
    VALUES(p_request_id,p_user_id,p_model_id,v_model.context_length,v_model.context_length);
  RETURN jsonb_build_object('model',to_jsonb(v_model),'connection',to_jsonb(v_connection));
END;
$$;

CREATE FUNCTION public.boss_ai_settle(p_request_id uuid, p_tokens bigint)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
  IF p_tokens IS NOT NULL AND p_tokens < 0 THEN RAISE EXCEPTION 'Invalid usage'; END IF;
  UPDATE public.boss_ai_requests SET charged_tokens = coalesce(p_tokens,reserved_tokens), settled = true
    WHERE id = p_request_id AND NOT settled;
END;
$$;

REVOKE ALL ON FUNCTION public.boss_ai_policy(uuid,text), public.boss_ai_usage(uuid,text),
  public.boss_ai_catalog(uuid), public.boss_ai_reserve(uuid,text,uuid),
  public.boss_ai_settle(uuid,bigint) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.boss_ai_policy(uuid,text), public.boss_ai_usage(uuid,text),
  public.boss_ai_catalog(uuid), public.boss_ai_reserve(uuid,text,uuid),
  public.boss_ai_settle(uuid,bigint) TO service_role;
COMMIT;
