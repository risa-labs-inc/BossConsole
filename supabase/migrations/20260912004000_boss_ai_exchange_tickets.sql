-- Plugin-owned BOSS AI authentication through the existing generic authenticated RPC API.
-- Tickets are single-use and AI-only; plugins never receive the BOSS login session.
BEGIN;
CREATE TABLE public.boss_ai_exchange_tickets (
  ticket_hash bytea PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  expires_at timestamptz NOT NULL DEFAULT now() + interval '60 seconds'
);
CREATE INDEX boss_ai_exchange_ticket_user ON public.boss_ai_exchange_tickets(user_id);
ALTER TABLE public.boss_ai_exchange_tickets ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.boss_ai_exchange_tickets FROM PUBLIC, anon, authenticated;
GRANT ALL ON public.boss_ai_exchange_tickets TO service_role;

CREATE FUNCTION public.boss_ai_create_exchange_ticket()
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  caller uuid := auth.uid();
  ticket text;
BEGIN
  IF caller IS NULL OR NOT EXISTS (
    SELECT 1 FROM auth.users u WHERE u.id = caller AND NOT COALESCE(u.is_anonymous, false)
      AND (u.banned_until IS NULL OR u.banned_until < now())
  ) OR public.user_has_permission(caller, 'ai.use') IS NOT TRUE THEN
    RAISE EXCEPTION 'BOSS AI access is required' USING ERRCODE = '42501';
  END IF;
  -- Bound pending tickets without invalidating another window's in-flight exchange.
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

CREATE FUNCTION public.boss_ai_consume_exchange_ticket(p_ticket text)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  caller uuid;
BEGIN
  IF p_ticket IS NULL OR p_ticket !~ '^[a-f0-9]{64}$' THEN RETURN NULL; END IF;
  -- DELETE RETURNING makes simultaneous redemption single-use as well.
  DELETE FROM public.boss_ai_exchange_tickets
    WHERE ticket_hash = sha256(convert_to(p_ticket, 'UTF8')) AND expires_at > now()
    RETURNING user_id INTO caller;
  IF caller IS NULL OR NOT EXISTS (
    SELECT 1 FROM auth.users u WHERE u.id = caller AND NOT COALESCE(u.is_anonymous, false)
      AND (u.banned_until IS NULL OR u.banned_until < now())
  ) OR public.user_has_permission(caller, 'ai.use') IS NOT TRUE THEN RETURN NULL; END IF;
  RETURN caller;
END;
$$;
REVOKE ALL ON FUNCTION public.boss_ai_create_exchange_ticket() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.boss_ai_create_exchange_ticket() TO authenticated;
REVOKE ALL ON FUNCTION public.boss_ai_consume_exchange_ticket(text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.boss_ai_consume_exchange_ticket(text) TO service_role;
COMMIT;
