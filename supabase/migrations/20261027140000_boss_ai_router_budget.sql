-- Gateway-side model routing and a per-user monthly USD budget.
-- boss_ai_routers: a virtual model (clients request it, e.g. optimist) is served per call by one of
-- three tier models chosen by a decider model; tier models may be unpublished. Authorization for a
-- routed call is the user's policy for the router model; usage and cost are recorded against the
-- served model (routed_from = the router) and counted toward the user's monthly USD budget.
-- Token allowances on a tier model, when any exist for the caller, still apply (and its
-- over_allowance chain still downgrades); a tier model with none has no token quota.
-- boss_ai_lookup/catalog bodies are copied from 20261027130000 and extended; reserve_routed and the
-- settle with a usage breakdown are new.
BEGIN;
ALTER TABLE public.boss_ai_models
  ADD COLUMN usd_per_mtok_input numeric CHECK (usd_per_mtok_input >= 0),
  ADD COLUMN usd_per_mtok_output numeric CHECK (usd_per_mtok_output >= 0),
  ADD COLUMN usd_per_mtok_cache_read numeric CHECK (usd_per_mtok_cache_read >= 0),
  ADD COLUMN usd_per_mtok_cache_write numeric CHECK (usd_per_mtok_cache_write >= 0);
COMMENT ON COLUMN public.boss_ai_models.usd_per_mtok_input IS
  'USD per million tokens. NULL = unknown: estimates fall back to the input price and log price_missing.';

ALTER TABLE public.boss_ai_requests
  ADD COLUMN input_tokens bigint, ADD COLUMN output_tokens bigint,
  ADD COLUMN cache_read_tokens bigint, ADD COLUMN cache_write_tokens bigint,
  ADD COLUMN cost_usd numeric, ADD COLUMN est_cost_usd numeric,
  ADD COLUMN routed_from text, ADD COLUMN tier text CHECK (tier IN ('fast','standard','strong')),
  ADD COLUMN route_source text CHECK (route_source IN ('decider','sticky','default','budget','forced'));
CREATE INDEX boss_ai_requests_routed ON public.boss_ai_requests(user_id, routed_from, started_at)
  WHERE routed_from IS NOT NULL;

CREATE FUNCTION public.boss_ai_router_choices_valid(c jsonb)
RETURNS boolean LANGUAGE sql IMMUTABLE SET search_path = '' AS $$
  SELECT CASE WHEN c IS NULL OR jsonb_typeof(c) <> 'array' THEN false
    WHEN jsonb_array_length(c) NOT BETWEEN 2 AND 255 THEN false
    ELSE NOT EXISTS (SELECT 1 FROM jsonb_array_elements(c) AS e(v) WHERE NOT coalesce(CASE
        WHEN jsonb_typeof(v) <> 'object' THEN false
        ELSE NOT EXISTS (SELECT 1 FROM jsonb_object_keys(v) AS k WHERE k NOT IN ('tier','label','description'))
          AND (v->>'tier') IN ('fast','standard','strong')
          AND jsonb_typeof(v->'label') = 'string' AND length(btrim(v->>'label')) > 0
          AND jsonb_typeof(v->'description') = 'string' AND length(btrim(v->>'description')) > 0
        END, false))
      AND (SELECT count(DISTINCT v->>'label') FROM jsonb_array_elements(c) AS e(v)) = jsonb_array_length(c)
  END
$$;

CREATE TABLE public.boss_ai_routers (
  model_id text PRIMARY KEY REFERENCES public.boss_ai_models(id),
  enabled boolean NOT NULL DEFAULT true,
  decider_connection_id text NOT NULL REFERENCES public.boss_ai_connections(id),
  decider_model text NOT NULL DEFAULT 'risa-pplx-decider-v1-27b' CHECK (length(decider_model) > 0),
  decider_temperature double precision NOT NULL DEFAULT 1.0087417621345625
    CHECK (decider_temperature > 0),
  decider_timeout_ms integer NOT NULL DEFAULT 2500 CHECK (decider_timeout_ms BETWEEN 100 AND 30000),
  state_char_budget integer NOT NULL DEFAULT 12000 CHECK (state_char_budget BETWEEN 500 AND 40000),
  question text NOT NULL DEFAULT 'Which model tier should handle this request?'
    CHECK (length(btrim(question)) > 0),
  choices jsonb NOT NULL DEFAULT '[
    {"tier":"fast","label":"Quick reply","description":"A greeting, thanks, a one-line factual answer or a simple lookup."},
    {"tier":"standard","label":"Everyday task","description":"An ordinary request or routine tool use such as browsing, sheets, email or small edits."},
    {"tier":"strong","label":"Hard task","description":"Hard reasoning, code or debugging, careful long writing (PRDs, RCAs, reports), multi-step analysis, or a retry after a wrong answer."}
  ]'::jsonb CHECK (public.boss_ai_router_choices_valid(choices)),
  min_confidence double precision NOT NULL DEFAULT 0.55 CHECK (min_confidence BETWEEN 0 AND 1),
  default_tier text NOT NULL DEFAULT 'standard' CHECK (default_tier IN ('fast','standard','strong')),
  fast_model_id text NOT NULL REFERENCES public.boss_ai_models(id),
  standard_model_id text NOT NULL REFERENCES public.boss_ai_models(id),
  strong_model_id text NOT NULL REFERENCES public.boss_ai_models(id),
  -- Served once the monthly budget is used up (NULL = fast_model_id); nobody is refused for budget
  -- unless hard_stop_at is set.
  exhausted_model_id text REFERENCES public.boss_ai_models(id),
  hard_stop_at numeric CHECK (hard_stop_at > 0),
  sticky_within_turn boolean NOT NULL DEFAULT true,
  monthly_usd_limit numeric NOT NULL DEFAULT 100 CHECK (monthly_usd_limit > 0),
  strong_cap_at numeric NOT NULL DEFAULT 0.60 CHECK (strong_cap_at > 0 AND strong_cap_at <= 1),
  fast_only_at numeric NOT NULL DEFAULT 0.85 CHECK (fast_only_at > 0 AND fast_only_at <= 1),
  note text,
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK (strong_cap_at <= fast_only_at),
  CHECK (model_id NOT IN (fast_model_id, standard_model_id, strong_model_id, coalesce(exhausted_model_id, fast_model_id)))
);
CREATE TABLE public.boss_ai_user_budgets (
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  router_model_id text NOT NULL REFERENCES public.boss_ai_routers(model_id),
  monthly_usd_limit numeric NOT NULL CHECK (monthly_usd_limit > 0),
  note text,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, router_model_id)
);
-- Tier decided for a turn, so a tool loop keeps one model (and one prompt cache). Key = sha256 of
-- user, router and the messages up to the last user message.
CREATE TABLE public.boss_ai_route_decisions (
  key text PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  model_id text NOT NULL,
  tier text NOT NULL CHECK (tier IN ('fast','standard','strong')),
  created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE public.boss_ai_routers ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.boss_ai_user_budgets ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.boss_ai_route_decisions ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.boss_ai_routers, public.boss_ai_user_budgets, public.boss_ai_route_decisions
  FROM PUBLIC, anon, authenticated;
GRANT ALL ON public.boss_ai_routers, public.boss_ai_user_budgets, public.boss_ai_route_decisions
  TO service_role;

CREATE FUNCTION public.boss_ai_touch_updated_at()
RETURNS trigger LANGUAGE plpgsql SET search_path = '' AS $$
BEGIN NEW.updated_at := now(); RETURN NEW; END;
$$;
CREATE TRIGGER boss_ai_routers_touch BEFORE UPDATE ON public.boss_ai_routers
  FOR EACH ROW EXECUTE FUNCTION public.boss_ai_touch_updated_at();
CREATE TRIGGER boss_ai_user_budgets_touch BEFORE UPDATE ON public.boss_ai_user_budgets
  FOR EACH ROW EXECUTE FUNCTION public.boss_ai_touch_updated_at();

CREATE FUNCTION public.boss_ai_route_get(p_key text, p_ttl_minutes integer DEFAULT 30)
RETURNS text LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT tier FROM public.boss_ai_route_decisions
  WHERE key = p_key AND created_at > now() - make_interval(mins => p_ttl_minutes);
$$;
CREATE FUNCTION public.boss_ai_route_put(p_key text, p_user_id uuid, p_model_id text, p_tier text)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
  DELETE FROM public.boss_ai_route_decisions
    WHERE user_id = p_user_id AND created_at < now() - interval '2 hours';
  INSERT INTO public.boss_ai_route_decisions(key, user_id, model_id, tier, created_at)
    VALUES (p_key, p_user_id, p_model_id, p_tier, now())
    ON CONFLICT (key) DO UPDATE SET tier = excluded.tier, created_at = excluded.created_at;
END;
$$;

-- Router config, decider connection, tier model rows (with prices; `exhausted` is the model served
-- over budget) and this user's month budget.
CREATE FUNCTION public.boss_ai_route_context(p_user_id uuid, p_model_id text)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  WITH m AS (
    SELECT date_trunc('month', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC' AS s
  ), b AS (
    SELECT r.*, c AS dc, f AS fm, sd AS sm, g AS gm, x AS xm, m.s AS month_start,
      coalesce((SELECT u.monthly_usd_limit FROM public.boss_ai_user_budgets u
        WHERE u.user_id = p_user_id AND u.router_model_id = r.model_id), r.monthly_usd_limit) AS lim,
      (SELECT coalesce(sum(coalesce(q.cost_usd, q.est_cost_usd, 0)), 0)
        FROM public.boss_ai_requests q
        WHERE q.user_id = p_user_id AND q.routed_from = r.model_id AND q.started_at >= m.s) AS spent
    FROM public.boss_ai_routers r CROSS JOIN m
    JOIN public.boss_ai_connections c ON c.id = r.decider_connection_id
    JOIN public.boss_ai_models f ON f.id = r.fast_model_id
    JOIN public.boss_ai_models sd ON sd.id = r.standard_model_id
    JOIN public.boss_ai_models g ON g.id = r.strong_model_id
    JOIN public.boss_ai_models x ON x.id = coalesce(r.exhausted_model_id, r.fast_model_id)
    WHERE r.model_id = p_model_id AND r.enabled
  )
  SELECT jsonb_build_object(
    'router', to_jsonb(b) - 'dc' - 'fm' - 'sm' - 'gm' - 'xm' - 'month_start' - 'lim' - 'spent',
    'decider_connection', to_jsonb(b.dc),
    'models', jsonb_build_object('fast', to_jsonb(b.fm), 'standard', to_jsonb(b.sm), 'strong', to_jsonb(b.gm),
      'exhausted', to_jsonb(b.xm)),
    'budget', jsonb_build_object(
      'monthly_usd_limit', b.lim, 'spent_usd', b.spent,
      'remaining_usd', greatest(0, b.lim - b.spent),
      'resets_at', (b.month_start AT TIME ZONE 'UTC' + interval '1 month') AT TIME ZONE 'UTC',
      'state', CASE WHEN b.hard_stop_at IS NOT NULL AND b.spent >= b.hard_stop_at * b.lim THEN 'hard_stopped'
        WHEN b.spent >= b.lim THEN 'exhausted (serving fast model)'
        WHEN b.spent >= b.fast_only_at * b.lim THEN 'fast_only'
        WHEN b.spent >= b.strong_cap_at * b.lim THEN 'strong_capped' ELSE 'normal' END))
  FROM b;
$$;

-- Policy/usage with an opt-in for unpublished models: a hidden tier model is not callable directly
-- (the wrappers keep the published filter) but its allowances must still bind routed calls.
CREATE FUNCTION public.boss_ai_policy_for(p_user_id uuid, p_model_id text, p_include_hidden boolean)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT CASE WHEN b.n = 0 THEN NULL ELSE jsonb_build_object(
    'day', coalesce(o.tokens_per_day, b.d), 'week', coalesce(o.tokens_per_week, b.w),
    'month', coalesce(o.tokens_per_month, b.mo), 'concurrent', coalesce(o.max_concurrent, b.c)) END
  FROM (
    SELECT count(*) AS n, max(a.tokens_per_day) AS d, max(a.tokens_per_week) AS w,
      max(a.tokens_per_month) AS mo, max(a.max_concurrent) AS c
    FROM public.boss_ai_models m
    JOIN public.boss_ai_connections c ON c.id = m.connection_id AND c.enabled
    JOIN public.boss_ai_allowances a ON a.model_id = coalesce(m.allowance_model_id, m.id)
    WHERE m.id = p_model_id AND (m.published OR p_include_hidden) AND m.enabled
      AND EXISTS (SELECT 1 FROM auth.users u WHERE u.id = p_user_id
        AND (u.banned_until IS NULL OR u.banned_until < now()))
      AND public.user_has_permission(p_user_id, a.permission_name)
  ) b
  LEFT JOIN LATERAL (
    SELECT u.* FROM public.boss_ai_user_allowances u
    WHERE u.user_id = p_user_id AND u.model_id IN (p_model_id,
      (SELECT coalesce(m2.allowance_model_id, m2.id) FROM public.boss_ai_models m2
        WHERE m2.id = p_model_id))
    ORDER BY (u.model_id = p_model_id) DESC LIMIT 1
  ) o ON true;
$$;

CREATE FUNCTION public.boss_ai_usage_for(p_user_id uuid, p_model_id text, p_include_hidden boolean)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  v_policy jsonb := public.boss_ai_policy_for(p_user_id, p_model_id, p_include_hidden);
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

CREATE OR REPLACE FUNCTION public.boss_ai_policy(p_user_id uuid, p_model_id text)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT public.boss_ai_policy_for(p_user_id, p_model_id, false);
$$;
CREATE OR REPLACE FUNCTION public.boss_ai_usage(p_user_id uuid, p_model_id text)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT public.boss_ai_usage_for(p_user_id, p_model_id, false);
$$;

CREATE OR REPLACE FUNCTION public.boss_ai_catalog(p_user_id uuid)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT coalesce(jsonb_agg(jsonb_build_object(
    'id', m.id, 'object', 'model', 'name', m.display_name, 'kind', m.kind,
    'context_length', m.context_length, 'max_output_tokens', m.max_output_tokens,
    'capabilities', m.capabilities, 'is_default', m.is_default,
    'allowance', public.boss_ai_usage(p_user_id, m.id))
    || public.boss_ai_over_allowance(p_user_id, m.id)
    || coalesce((SELECT jsonb_build_object('budget', x->'budget')
        FROM public.boss_ai_route_context(p_user_id, m.id) AS x), '{}'::jsonb)
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
      AND public.boss_ai_next_model(p_user_id, p_model_id) IS NULL
    THEN jsonb_build_object('error','misconfigured_allowance')
    ELSE jsonb_build_object('model',model,'connection',connection,
      'fallbacks',public.boss_ai_resolve_fallbacks(model->'fallbacks'),
      'router',public.boss_ai_route_context(p_user_id, p_model_id)) END
  FROM config WHERE policy IS NOT NULL;
$$;

CREATE FUNCTION public.boss_ai_reserve_routed(
  p_user_id uuid, p_model_id text, p_served_model_id text, p_request_id uuid,
  p_tier text, p_route_source text, p_est_cost numeric)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  r public.boss_ai_routers;
  v_model public.boss_ai_models;
  v_connection public.boss_ai_connections;
  v_policy jsonb; v_sp jsonb; v_usage jsonb; v_first_usage jsonb;
  v_limit numeric; v_spent numeric; v_start timestamptz;
  v_cur text := p_served_model_id; v_next text; v_hop integer := 0;
  v_exh text; v_tier text := p_tier; v_source text := p_route_source; v_over boolean := false;
  v_amount bigint; v_period text; v_exhausted boolean; v_active integer; v_pool text;
BEGIN
  IF current_setting('transaction_isolation') <> 'read committed' THEN
    RAISE EXCEPTION 'BOSS AI admission requires READ COMMITTED' USING ERRCODE='25000';
  END IF;
  SELECT * INTO r FROM public.boss_ai_routers WHERE model_id = p_model_id AND enabled;
  v_exh := coalesce(r.exhausted_model_id, r.fast_model_id);
  IF NOT FOUND OR p_served_model_id NOT IN (r.fast_model_id, r.standard_model_id, r.strong_model_id, v_exh)
    THEN RETURN jsonb_build_object('error','forbidden'); END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended(p_user_id::text || ':router:' || p_model_id, 0));
  v_policy := public.boss_ai_policy(p_user_id, p_model_id);
  IF v_policy IS NULL THEN RETURN jsonb_build_object('error','forbidden'); END IF;
  IF EXISTS (SELECT 1 FROM public.boss_ai_requests WHERE id = p_request_id) THEN
    RETURN jsonb_build_object('error','duplicate');
  END IF;
  v_start := date_trunc('month', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC';
  v_limit := coalesce((SELECT u.monthly_usd_limit FROM public.boss_ai_user_budgets u
    WHERE u.user_id = p_user_id AND u.router_model_id = p_model_id), r.monthly_usd_limit);
  SELECT coalesce(sum(coalesce(q.cost_usd, q.est_cost_usd, 0)), 0) INTO v_spent
    FROM public.boss_ai_requests q
    WHERE q.user_id = p_user_id AND q.routed_from = p_model_id AND q.started_at >= v_start;
  IF r.hard_stop_at IS NOT NULL AND v_spent >= r.hard_stop_at * v_limit THEN
    RETURN jsonb_build_object('error','budget_exhausted','budget',jsonb_build_object(
      'monthly_usd_limit', v_limit, 'spent_usd', v_spent,
      'resets_at', (v_start AT TIME ZONE 'UTC' + interval '1 month') AT TIME ZONE 'UTC'));
  END IF;
  -- Under the lock the budget is re-checked: a call that no longer fits is served on the exhausted
  -- model instead of overspending.
  IF p_served_model_id <> v_exh AND v_spent + coalesce(p_est_cost, 0) > v_limit THEN
    v_cur := v_exh; v_tier := 'fast'; v_source := 'budget';
  END IF;
  v_over := v_spent >= v_limit OR v_spent + coalesce(p_est_cost, 0) > v_limit;
  SELECT count(*) INTO v_active FROM public.boss_ai_requests q
    WHERE q.user_id = p_user_id AND q.routed_from = p_model_id AND NOT q.settled
      AND q.lease_until > now();
  IF v_active >= (v_policy->>'concurrent')::integer THEN
    RETURN jsonb_build_object('error','concurrency_exceeded');
  END IF;
  -- Token allowances on the tier model apply only where the caller has some for it; tier models are
  -- unpublished, so the lookup includes hidden models.
  LOOP
    SELECT coalesce(allowance_model_id, id) INTO v_pool FROM public.boss_ai_models WHERE id = v_cur;
    PERFORM pg_advisory_xact_lock(hashtextextended(p_user_id::text || ':' || coalesce(v_pool, v_cur), 0));
    v_sp := public.boss_ai_policy_for(p_user_id, v_cur, true);
    IF v_sp IS NULL THEN EXIT; END IF; -- no allowance for the caller on this model: no token quota
    SELECT * INTO v_model FROM public.boss_ai_models WHERE id = v_cur;
    v_amount := CASE WHEN v_model.kind = 'chat' THEN v_model.context_length ELSE v_model.max_output_tokens END;
    v_usage := public.boss_ai_usage_for(p_user_id, v_cur, true);
    IF v_hop = 0 THEN v_first_usage := v_usage; END IF;
    v_exhausted := false;
    FOREACH v_period IN ARRAY ARRAY['day','week','month'] LOOP
      IF (v_usage->v_period->>'remaining')::bigint < v_amount THEN v_exhausted := true; END IF;
    END LOOP;
    EXIT WHEN NOT v_exhausted;
    v_next := public.boss_ai_next_model(p_user_id, v_cur);
    IF v_next IS NULL OR v_hop >= 3 THEN
      RETURN jsonb_build_object('error','allowance_exceeded','usage',v_first_usage);
    END IF;
    v_cur := v_next; v_hop := v_hop + 1;
  END LOOP;
  SELECT * INTO v_model FROM public.boss_ai_models WHERE id = v_cur FOR SHARE;
  SELECT * INTO v_connection FROM public.boss_ai_connections WHERE id = v_model.connection_id FOR SHARE;
  IF NOT v_model.enabled OR NOT v_connection.enabled THEN
    RETURN jsonb_build_object('error','unavailable');
  END IF;
  v_amount := CASE WHEN v_model.kind = 'chat' THEN v_model.context_length ELSE v_model.max_output_tokens END;
  INSERT INTO public.boss_ai_requests(id,user_id,model_id,reserved_tokens,charged_tokens,
      routed_from,tier,route_source,est_cost_usd)
    VALUES(p_request_id,p_user_id,v_cur,v_amount,v_amount,p_model_id,v_tier,v_source,p_est_cost);
  RETURN jsonb_build_object('model',to_jsonb(v_model),'connection',to_jsonb(v_connection),
    'fallbacks',public.boss_ai_resolve_fallbacks(v_model.fallbacks),
    'routed_from',p_model_id,'tier',v_tier,'route_source',v_source,'budget_exhausted',v_over)
    || CASE WHEN v_cur <> p_served_model_id AND NOT v_over
         THEN jsonb_build_object('downgraded_from',p_served_model_id) ELSE '{}'::jsonb END;
END;
$$;

-- Same settlement as before (capped at the reservation) plus the usage breakdown and cost.
DROP FUNCTION public.boss_ai_settle(uuid, bigint);
CREATE FUNCTION public.boss_ai_settle(
  p_request_id uuid, p_tokens bigint,
  p_input bigint DEFAULT NULL, p_output bigint DEFAULT NULL,
  p_cache_read bigint DEFAULT NULL, p_cache_write bigint DEFAULT NULL, p_cost_usd numeric DEFAULT NULL)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
  IF p_tokens IS NOT NULL AND p_tokens < 0 THEN RAISE EXCEPTION 'Invalid usage'; END IF;
  IF least(coalesce(p_input,0), coalesce(p_output,0), coalesce(p_cache_read,0),
    coalesce(p_cache_write,0), coalesce(p_cost_usd,0)) < 0 THEN RAISE EXCEPTION 'Invalid usage'; END IF;
  UPDATE public.boss_ai_requests
    SET charged_tokens = least(coalesce(p_tokens,reserved_tokens),reserved_tokens), settled = true,
        input_tokens = p_input, output_tokens = p_output,
        cache_read_tokens = p_cache_read, cache_write_tokens = p_cache_write, cost_usd = p_cost_usd
    WHERE id = p_request_id AND NOT settled;
END;
$$;

REVOKE ALL ON FUNCTION public.boss_ai_policy_for(uuid,text,boolean),
  public.boss_ai_usage_for(uuid,text,boolean) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.boss_ai_policy_for(uuid,text,boolean),
  public.boss_ai_usage_for(uuid,text,boolean) TO service_role;
REVOKE ALL ON FUNCTION public.boss_ai_settle(uuid,bigint,bigint,bigint,bigint,bigint,numeric),
  public.boss_ai_route_context(uuid,text), public.boss_ai_route_get(text,integer),
  public.boss_ai_route_put(text,uuid,text,text),
  public.boss_ai_reserve_routed(uuid,text,text,uuid,text,text,numeric)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.boss_ai_settle(uuid,bigint,bigint,bigint,bigint,bigint,numeric),
  public.boss_ai_route_context(uuid,text), public.boss_ai_route_get(text,integer),
  public.boss_ai_route_put(text,uuid,text,text),
  public.boss_ai_reserve_routed(uuid,text,text,uuid,text,text,numeric) TO service_role;
REVOKE ALL ON FUNCTION public.boss_ai_touch_updated_at() FROM PUBLIC, anon, authenticated;
COMMIT;
