BEGIN;
-- Read-only preflight. Admission rechecks policy and returns its own current config,
-- so an edit/revocation between validation and reservation cannot authorize a call.
CREATE FUNCTION public.boss_ai_lookup(p_user_id uuid, p_model_id text)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT jsonb_build_object('model',to_jsonb(m),'connection',to_jsonb(c))
  FROM public.boss_ai_models m JOIN public.boss_ai_connections c ON c.id=m.connection_id
  WHERE m.id=p_model_id AND public.boss_ai_policy(p_user_id,p_model_id) IS NOT NULL;
$$;
REVOKE ALL ON FUNCTION public.boss_ai_lookup(uuid,text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.boss_ai_lookup(uuid,text) TO service_role;

CREATE OR REPLACE FUNCTION public.boss_ai_reserve(p_user_id uuid, p_model_id text, p_request_id uuid)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  v_model public.boss_ai_models;
  v_connection public.boss_ai_connections;
  v_policy jsonb;
  v_usage jsonb;
  v_period text;
  v_active integer;
BEGIN
  -- The post-lock recount needs a fresh snapshot, not a repeatable-read snapshot
  -- taken before waiting. PostgREST's default is READ COMMITTED; fail other callers closed.
  IF current_setting('transaction_isolation') <> 'read committed' THEN
    RAISE EXCEPTION 'BOSS AI admission requires READ COMMITTED' USING ERRCODE='25000';
  END IF;
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
  INSERT INTO public.boss_ai_requests(id,user_id,model_id,reserved_tokens,charged_tokens)
    VALUES(p_request_id,p_user_id,p_model_id,v_model.context_length,v_model.context_length);
  RETURN jsonb_build_object('model',to_jsonb(v_model),'connection',to_jsonb(v_connection));
END;
$$;
COMMIT;
