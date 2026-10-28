-- Per-user allowance overrides and over-allowance downgrade.
-- boss_ai_user_allowances: a non-null field REPLACES the permission-derived value for one user and
-- model (or its allowance pool); it never grants access, boss_ai_policy still requires a permission.
-- over_allowance_model_id (model-level, or per-user on the override row): when the requested model's
-- allowance cannot fit a request, boss_ai_reserve admits against that model instead (own policy,
-- usage and concurrency), at most 3 hops. The ledger row records the model actually served.
-- policy/catalog/lookup/reserve bodies are copied from 20261015000000 / 20261027120000 and extended.
BEGIN;
CREATE TABLE public.boss_ai_user_allowances (
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  model_id text NOT NULL REFERENCES public.boss_ai_models(id),
  tokens_per_day bigint CHECK (tokens_per_day > 0),
  tokens_per_week bigint CHECK (tokens_per_week > 0),
  tokens_per_month bigint CHECK (tokens_per_month > 0),
  max_concurrent integer CHECK (max_concurrent BETWEEN 1 AND 100),
  over_allowance_model_id text REFERENCES public.boss_ai_models(id),
  note text,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, model_id),
  CHECK (over_allowance_model_id IS DISTINCT FROM model_id)
);
ALTER TABLE public.boss_ai_user_allowances ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.boss_ai_user_allowances FROM PUBLIC, anon, authenticated;
GRANT ALL ON public.boss_ai_user_allowances TO service_role;

ALTER TABLE public.boss_ai_models
  ADD COLUMN over_allowance_model_id text REFERENCES public.boss_ai_models(id),
  ADD CONSTRAINT boss_ai_models_over_not_self CHECK (over_allowance_model_id IS DISTINCT FROM id);
COMMENT ON COLUMN public.boss_ai_models.over_allowance_model_id IS
  'Served instead when the user''s allowance for this model cannot fit a request (chain of at most 3).';

-- The model to fall back to: this user's override for exactly this model, else the model's own.
CREATE FUNCTION public.boss_ai_next_model(p_user_id uuid, p_model_id text)
RETURNS text LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT coalesce(
    (SELECT u.over_allowance_model_id FROM public.boss_ai_user_allowances u
      WHERE u.user_id = p_user_id AND u.model_id = p_model_id),
    (SELECT m.over_allowance_model_id FROM public.boss_ai_models m WHERE m.id = p_model_id));
$$;

-- True when no chain (this user's overrides applied; NULL = model-level only) loops or exceeds 3 hops.
CREATE FUNCTION public.boss_ai_chain_valid(p_user_id uuid)
RETURNS boolean LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  v_start text; v_cur text; v_next text; v_path text[]; v_hop integer;
BEGIN
  FOR v_start IN SELECT id FROM public.boss_ai_models LOOP
    v_cur := v_start; v_path := ARRAY[v_start];
    FOR v_hop IN 1..4 LOOP
      v_next := public.boss_ai_next_model(p_user_id, v_cur);
      EXIT WHEN v_next IS NULL;
      IF v_next = ANY(v_path) OR v_hop = 4 THEN RETURN false; END IF;
      v_path := v_path || v_next; v_cur := v_next;
    END LOOP;
  END LOOP;
  RETURN true;
END;
$$;

CREATE FUNCTION public.boss_ai_over_allowance_guard()
RETURNS trigger LANGUAGE plpgsql SET search_path = '' AS $$
DECLARE
  v_source text;
  v_user uuid;
BEGIN
  IF TG_TABLE_NAME = 'boss_ai_models' THEN v_source := NEW.id; ELSE v_source := NEW.model_id; END IF;
  IF NEW.over_allowance_model_id IS NOT NULL AND
    (SELECT kind FROM public.boss_ai_models WHERE id = NEW.over_allowance_model_id) IS DISTINCT FROM
    (SELECT kind FROM public.boss_ai_models WHERE id = v_source) THEN
    RAISE EXCEPTION 'Over-allowance model must have the same kind' USING ERRCODE = '23514';
  END IF;
  IF TG_TABLE_NAME = 'boss_ai_models' THEN
    IF NOT public.boss_ai_chain_valid(NULL) THEN
      RAISE EXCEPTION 'Over-allowance chains must not loop or exceed 3 hops' USING ERRCODE = '23514';
    END IF;
    FOR v_user IN SELECT DISTINCT user_id FROM public.boss_ai_user_allowances
      WHERE over_allowance_model_id IS NOT NULL LOOP
      IF NOT public.boss_ai_chain_valid(v_user) THEN
        RAISE EXCEPTION 'Over-allowance chains must not loop or exceed 3 hops' USING ERRCODE = '23514';
      END IF;
    END LOOP;
  ELSIF NOT public.boss_ai_chain_valid(NEW.user_id) THEN
    RAISE EXCEPTION 'Over-allowance chains must not loop or exceed 3 hops' USING ERRCODE = '23514';
  END IF;
  RETURN NULL;
END;
$$;
CREATE TRIGGER boss_ai_models_over_guard AFTER INSERT OR UPDATE OF over_allowance_model_id
  ON public.boss_ai_models FOR EACH ROW EXECUTE FUNCTION public.boss_ai_over_allowance_guard();
CREATE TRIGGER boss_ai_user_allowances_over_guard AFTER INSERT OR UPDATE
  ON public.boss_ai_user_allowances FOR EACH ROW EXECUTE FUNCTION public.boss_ai_over_allowance_guard();

CREATE FUNCTION public.boss_ai_user_allowances_touch()
RETURNS trigger LANGUAGE plpgsql SET search_path = '' AS $$
BEGIN NEW.updated_at := now(); RETURN NEW; END;
$$;
CREATE TRIGGER boss_ai_user_allowances_touch BEFORE UPDATE
  ON public.boss_ai_user_allowances FOR EACH ROW EXECUTE FUNCTION public.boss_ai_user_allowances_touch();

-- Permission-derived limits (max over permissions), each replaced by this user's non-null override
-- for the model itself, else for its allowance pool. No permission still means no policy.
CREATE OR REPLACE FUNCTION public.boss_ai_policy(p_user_id uuid, p_model_id text)
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
    WHERE m.id = p_model_id AND m.published AND m.enabled
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

-- Effective downgrade target for this user and whether it is active now (any period cannot fit a
-- request), with the time the last exhausted period resets.
CREATE FUNCTION public.boss_ai_over_allowance(p_user_id uuid, p_model_id text)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  WITH t AS (
    SELECT public.boss_ai_next_model(p_user_id, m.id) AS target,
      public.boss_ai_usage(p_user_id, m.id) AS u,
      CASE WHEN m.kind = 'chat' THEN m.context_length ELSE m.max_output_tokens END AS amount
    FROM public.boss_ai_models m WHERE m.id = p_model_id
  ), x AS (
    SELECT t.target, (SELECT max((t.u->p->>'resets_at')::timestamptz)
      FROM unnest(ARRAY['day','week','month']) AS p
      WHERE (t.u->p->>'remaining')::bigint < t.amount) AS resets_at FROM t
  )
  SELECT jsonb_build_object('over_allowance_model_id', x.target,
    'downgraded', x.target IS NOT NULL AND x.resets_at IS NOT NULL,
    'downgrade_resets_at', CASE WHEN x.target IS NOT NULL THEN x.resets_at END) FROM x;
$$;

CREATE OR REPLACE FUNCTION public.boss_ai_catalog(p_user_id uuid)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT coalesce(jsonb_agg(jsonb_build_object(
    'id', m.id, 'object', 'model', 'name', m.display_name, 'kind', m.kind,
    'context_length', m.context_length, 'max_output_tokens', m.max_output_tokens,
    'capabilities', m.capabilities, 'is_default', m.is_default,
    'allowance', public.boss_ai_usage(p_user_id, m.id))
    || public.boss_ai_over_allowance(p_user_id, m.id)
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
  -- An allowance that can never fit one request is a configuration fault unless a downgrade
  -- target exists, in which case reserve serves from it.
  SELECT CASE WHEN least((policy->>'day')::bigint,(policy->>'week')::bigint,
    (policy->>'month')::bigint) < reserve
      AND public.boss_ai_next_model(p_user_id, p_model_id) IS NULL
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
  v_cur text := p_model_id;
  v_next text;
  v_hop integer := 0;
  v_pool text;
  v_amount bigint;
  v_policy jsonb;
  v_usage jsonb;
  v_first_usage jsonb;
  v_period text;
  v_active integer;
  v_exhausted boolean;
BEGIN
  IF current_setting('transaction_isolation') <> 'read committed' THEN
    RAISE EXCEPTION 'BOSS AI admission requires READ COMMITTED' USING ERRCODE='25000';
  END IF;
  LOOP
    SELECT coalesce(allowance_model_id, id) INTO v_pool FROM public.boss_ai_models
      WHERE id = v_cur;
    PERFORM pg_advisory_xact_lock(hashtextextended(
      p_user_id::text || ':' || coalesce(v_pool, v_cur), 0));
    SELECT * INTO v_model FROM public.boss_ai_models WHERE id = v_cur FOR SHARE;
    SELECT * INTO v_connection FROM public.boss_ai_connections WHERE id = v_model.connection_id FOR SHARE;
    v_policy := public.boss_ai_policy(p_user_id, v_cur);
    IF v_policy IS NULL THEN
      -- A downgrade target the user may not use is an exhausted allowance, not a permission error.
      IF v_hop = 0 THEN RETURN jsonb_build_object('error','forbidden'); END IF;
      RETURN jsonb_build_object('error','allowance_exceeded','usage',v_first_usage);
    END IF;
    IF v_hop = 0 AND EXISTS (SELECT 1 FROM public.boss_ai_requests WHERE id = p_request_id) THEN
      RETURN jsonb_build_object('error','duplicate');
    END IF;
    v_amount := CASE WHEN v_model.kind = 'chat' THEN v_model.context_length
      ELSE v_model.max_output_tokens END;
    v_usage := public.boss_ai_usage(p_user_id, v_cur);
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
    v_cur := v_next;
    v_hop := v_hop + 1;
  END LOOP;
  SELECT count(*) INTO v_active FROM public.boss_ai_requests r
    JOIN public.boss_ai_models m ON m.id = r.model_id
    WHERE r.user_id = p_user_id AND coalesce(m.allowance_model_id, m.id) = v_pool
      AND NOT r.settled AND r.lease_until > now();
  IF v_active >= (v_policy->>'concurrent')::integer THEN
    RETURN jsonb_build_object('error','concurrency_exceeded');
  END IF;
  INSERT INTO public.boss_ai_requests(id,user_id,model_id,reserved_tokens,charged_tokens)
    VALUES(p_request_id,p_user_id,v_cur,v_amount,v_amount);
  RETURN jsonb_build_object('model',to_jsonb(v_model),'connection',to_jsonb(v_connection),
    'fallbacks',public.boss_ai_resolve_fallbacks(v_model.fallbacks))
    || CASE WHEN v_cur <> p_model_id THEN jsonb_build_object('downgraded_from',p_model_id)
       ELSE '{}'::jsonb END;
END;
$$;

REVOKE ALL ON FUNCTION public.boss_ai_next_model(uuid,text), public.boss_ai_chain_valid(uuid),
  public.boss_ai_over_allowance(uuid,text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.boss_ai_next_model(uuid,text), public.boss_ai_chain_valid(uuid),
  public.boss_ai_over_allowance(uuid,text) TO service_role;
REVOKE ALL ON FUNCTION public.boss_ai_over_allowance_guard(),
  public.boss_ai_user_allowances_touch() FROM PUBLIC, anon, authenticated;
COMMIT;
