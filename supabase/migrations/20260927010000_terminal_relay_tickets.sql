-- Single-use admission credentials. These never carry terminal E2E keys.
CREATE TABLE public.terminal_relay_rooms (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
 expires_at timestamptz NOT NULL
);
CREATE INDEX terminal_relay_rooms_owner_expiry ON public.terminal_relay_rooms(user_id, expires_at);
CREATE TABLE public.terminal_relay_tickets (
 token_hash text PRIMARY KEY, room_id uuid NOT NULL REFERENCES public.terminal_relay_rooms(id) ON DELETE CASCADE,
 role text NOT NULL CHECK(role IN ('host','account')), expires_at timestamptz NOT NULL
);
-- Also indexes FK cascade deletes; cleanup and admission never scan other rooms' tickets.
CREATE INDEX terminal_relay_tickets_room_expiry ON public.terminal_relay_tickets(room_id, expires_at);
ALTER TABLE public.terminal_relay_rooms ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.terminal_relay_tickets ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.terminal_relay_rooms, public.terminal_relay_tickets FROM PUBLIC, anon, authenticated;
CREATE FUNCTION public.mint_terminal_relay_ticket(p_room_id uuid, p_role text, p_expected_user_id uuid DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE actor uuid := public.terminal_settings_actor(); token text;
BEGIN
 IF p_expected_user_id IS NOT NULL AND actor IS DISTINCT FROM p_expected_user_id THEN
  RAISE EXCEPTION 'Account changed' USING ERRCODE='42501';
 END IF;
 IF p_room_id IS NULL OR p_role IS NULL OR p_role NOT IN ('host','account') THEN
  RAISE EXCEPTION 'Invalid relay admission' USING ERRCODE='22023';
 END IF;
 PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(actor::text,19));
 -- Keep cleanup bounded by this owner's room limit, off the global admission hot path.
 -- An expired room remains reserved to its owner until that owner's next request.
 DELETE FROM public.terminal_relay_rooms WHERE user_id=actor AND expires_at<=now();
 IF p_role='host' THEN
  IF NOT EXISTS(SELECT 1 FROM public.terminal_relay_rooms WHERE id=p_room_id)
   AND (SELECT count(*) FROM public.terminal_relay_rooms WHERE user_id=actor)>=20 THEN
   RAISE EXCEPTION 'Too many relay rooms' USING ERRCODE='54000';
  END IF;
  INSERT INTO public.terminal_relay_rooms VALUES(p_room_id,actor,now()+interval '12 hours')
   ON CONFLICT(id) DO UPDATE SET expires_at=EXCLUDED.expires_at WHERE terminal_relay_rooms.user_id=actor;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM public.terminal_relay_rooms WHERE id=p_room_id AND user_id=actor AND expires_at>now()) THEN
  RAISE EXCEPTION 'Relay unavailable' USING ERRCODE='42501';
 END IF;
 DELETE FROM public.terminal_relay_tickets WHERE room_id=p_room_id AND expires_at<=now();
 IF (SELECT count(*) FROM public.terminal_relay_tickets WHERE room_id=p_room_id)>=32 THEN
  RAISE EXCEPTION 'Too many pending connections' USING ERRCODE='54000';
 END IF;
 token:=translate(encode(extensions.gen_random_bytes(32),'base64'),'+/=','-_');
 INSERT INTO public.terminal_relay_tickets VALUES(encode(extensions.digest(token,'sha256'),'hex'),p_room_id,p_role,now()+interval '60 seconds');
 RETURN jsonb_build_object('ticket',token,'room_id',p_room_id,'expires_in',60);
END; $$;
CREATE FUNCTION public.consume_terminal_relay_ticket(p_token text,p_room_id uuid)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE admitted text;
BEGIN
 DELETE FROM public.terminal_relay_tickets WHERE token_hash=encode(extensions.digest(p_token,'sha256'),'hex')
  AND room_id=p_room_id AND expires_at>now()
  AND EXISTS(SELECT 1 FROM public.terminal_relay_rooms WHERE id=p_room_id AND expires_at>now())
  RETURNING role INTO admitted;
 IF admitted IS NULL THEN RAISE EXCEPTION 'Relay unavailable' USING ERRCODE='42501'; END IF;
 RETURN jsonb_build_object('role',admitted);
END; $$;
REVOKE ALL ON FUNCTION public.mint_terminal_relay_ticket(uuid,text,uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.mint_terminal_relay_ticket(uuid,text,uuid) TO authenticated;
REVOKE ALL ON FUNCTION public.consume_terminal_relay_ticket(text,uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.consume_terminal_relay_ticket(text,uuid) TO service_role;
