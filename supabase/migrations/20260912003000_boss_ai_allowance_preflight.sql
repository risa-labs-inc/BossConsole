BEGIN;
-- Distinguish an allowance that can never fit one request from temporary exhaustion.
-- CREATE OR REPLACE retains the service-role-only grants on this read-only RPC.
CREATE OR REPLACE FUNCTION public.boss_ai_lookup(p_user_id uuid, p_model_id text)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  WITH config AS MATERIALIZED (
    SELECT to_jsonb(m) AS model, to_jsonb(c) AS connection, m.context_length,
      public.boss_ai_policy(p_user_id,p_model_id) AS policy
    FROM public.boss_ai_models m JOIN public.boss_ai_connections c ON c.id=m.connection_id
    WHERE m.id=p_model_id
  )
  SELECT CASE WHEN least((policy->>'day')::bigint,(policy->>'week')::bigint,
    (policy->>'month')::bigint) < context_length
    THEN jsonb_build_object('error','misconfigured_allowance')
    ELSE jsonb_build_object('model',model,'connection',connection) END
  FROM config WHERE policy IS NOT NULL;
$$;
COMMIT;
