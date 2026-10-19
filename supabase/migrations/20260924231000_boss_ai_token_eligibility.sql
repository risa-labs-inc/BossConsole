-- /auth/token must not mint an AI token for users the /auth/exchange path
-- would refuse. The exchange path returns NULL for banned, anonymous, and
-- ai.use-less users via boss_ai_consume_exchange_ticket; the direct path
-- previously only checked that the session was valid, so a banned user
-- holding an AI token could probe /v1/models and /v1/usage (the result was
-- the same 403 downstream, but the token-mint itself was a distinguishable
-- signal a careful probe could use). Both paths now call one eligibility predicate.
BEGIN;
CREATE FUNCTION public.boss_ai_token_eligible(p_user_id uuid)
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT
    p_user_id IS NOT NULL
    AND EXISTS (
      SELECT 1 FROM auth.users u
      WHERE u.id = p_user_id
        AND NOT COALESCE(u.is_anonymous, false)
        AND (u.banned_until IS NULL OR u.banned_until < now())
    )
    AND public.user_has_permission(p_user_id, 'ai.use') IS TRUE;
$$;
REVOKE ALL ON FUNCTION public.boss_ai_token_eligible(uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.boss_ai_token_eligible(uuid) TO service_role;

-- Keep ticket creation and redemption on the same predicate as direct token minting.
-- CREATE OR REPLACE retains the existing grants from the exchange-ticket migration.
CREATE OR REPLACE FUNCTION public.boss_ai_create_exchange_ticket()
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  caller uuid := auth.uid();
  ticket text;
BEGIN
  IF public.boss_ai_token_eligible(caller) IS NOT TRUE THEN
    RAISE EXCEPTION 'BOSS AI access is required' USING ERRCODE = '42501';
  END IF;
  PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('boss-ai-ticket:' || caller::text, 0));
  DELETE FROM public.boss_ai_exchange_tickets WHERE user_id = caller AND expires_at <= now();
  IF (SELECT count(*) FROM public.boss_ai_exchange_tickets WHERE user_id = caller) >= 8 THEN
    RAISE EXCEPTION 'Too many pending BOSS AI exchanges; retry shortly' USING ERRCODE = '54000';
  END IF;
  ticket := replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', '');
  INSERT INTO public.boss_ai_exchange_tickets(ticket_hash, user_id)
    VALUES (sha256(convert_to(ticket, 'UTF8')), caller);
  RETURN jsonb_build_object('ticket', ticket);
END;
$$;

CREATE OR REPLACE FUNCTION public.boss_ai_consume_exchange_ticket(p_ticket text)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  caller uuid;
BEGIN
  IF p_ticket IS NULL OR p_ticket !~ '^[a-f0-9]{64}$' THEN RETURN NULL; END IF;
  DELETE FROM public.boss_ai_exchange_tickets
    WHERE ticket_hash = sha256(convert_to(p_ticket, 'UTF8')) AND expires_at > now()
    RETURNING user_id INTO caller;
  IF public.boss_ai_token_eligible(caller) IS NOT TRUE THEN RETURN NULL; END IF;
  RETURN caller;
END;
$$;
COMMIT;
