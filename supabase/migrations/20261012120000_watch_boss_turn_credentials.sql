-- Short-lived, one-use authorization for the Watch Boss TURN credential broker.
-- The caller is the signed-in Fluck owner. Only the Edge service role can redeem.

CREATE TABLE public.fluck_web_turn_tickets (
    ticket_hash text PRIMARY KEY CHECK (ticket_hash ~ '^[0-9a-f]{64}$'),
    user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    instance_id text NOT NULL,
    attempt_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    used_at timestamptz,
    UNIQUE (user_id, instance_id, attempt_id),
    FOREIGN KEY (user_id, instance_id)
        REFERENCES public.fluck_web_instances(user_id, instance_id) ON DELETE CASCADE
);

CREATE INDEX idx_fluck_web_turn_tickets_owner_created
    ON public.fluck_web_turn_tickets (user_id, created_at DESC);

ALTER TABLE public.fluck_web_turn_tickets ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.fluck_web_turn_tickets FROM PUBLIC, anon, authenticated;
GRANT ALL ON public.fluck_web_turn_tickets TO service_role;

CREATE OR REPLACE FUNCTION public.fluck_web_request_turn(
    p_instance_id text, p_attempt_id text
) RETURNS TABLE (ticket text, expires_at timestamptz)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
DECLARE
    actor uuid := auth.uid();
    attempt uuid;
    token text;
    expiry timestamptz := now() + interval '60 seconds';
BEGIN
    IF actor IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    IF p_attempt_id IS NULL THEN
        RAISE EXCEPTION 'invalid_request' USING ERRCODE = '22023';
    END IF;
    BEGIN
        attempt := p_attempt_id::uuid;
    EXCEPTION WHEN invalid_text_representation THEN
        RAISE EXCEPTION 'invalid_request' USING ERRCODE = '22023';
    END;
    IF p_instance_id IS NULL OR NOT EXISTS (
        SELECT 1 FROM public.fluck_web_instances i
        WHERE i.user_id = actor AND i.instance_id = p_instance_id
          AND i.last_seen_at > now() - interval '90 seconds'
    ) THEN
        RAISE EXCEPTION 'instance_unavailable' USING ERRCODE = 'P0001';
    END IF;

    -- One lock makes the rolling quota and attempt uniqueness exact. Used tickets stay in
    -- the one-minute accounting window, so mint-then-redeem cannot evade the limit.
    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(actor::text, 26));
    DELETE FROM public.fluck_web_turn_tickets t
        WHERE t.user_id = actor AND t.created_at <= now() - interval '10 minutes';
    IF (SELECT count(*) FROM public.fluck_web_turn_tickets t
        WHERE t.user_id = actor AND t.created_at > now() - interval '1 minute') >= 20 THEN
        RAISE EXCEPTION 'rate_limited' USING ERRCODE = 'P0001';
    END IF;
    IF EXISTS (SELECT 1 FROM public.fluck_web_turn_tickets t
        WHERE t.user_id = actor AND t.instance_id = p_instance_id AND t.attempt_id = attempt) THEN
        RAISE EXCEPTION 'duplicate_attempt' USING ERRCODE = 'P0001';
    END IF;

    token := pg_catalog.translate(
        pg_catalog.encode(extensions.gen_random_bytes(32), 'base64'), '+/=', '-_'
    );
    INSERT INTO public.fluck_web_turn_tickets
        (ticket_hash, user_id, instance_id, attempt_id, expires_at)
    VALUES (
        pg_catalog.encode(extensions.digest(token, 'sha256'), 'hex'),
        actor, p_instance_id, attempt, expiry
    );
    RETURN QUERY SELECT token, expiry;
END;
$$;

CREATE OR REPLACE FUNCTION public.fluck_web_consume_turn_ticket(
    p_ticket text, p_instance_id text, p_attempt_id text
) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    attempt uuid;
    owner_id uuid;
BEGIN
    IF p_ticket IS NULL OR p_ticket !~ '^[A-Za-z0-9_-]{43}$' OR p_attempt_id IS NULL THEN
        RAISE EXCEPTION 'ticket_invalid' USING ERRCODE = 'P0001';
    END IF;
    BEGIN
        attempt := p_attempt_id::uuid;
    EXCEPTION WHEN invalid_text_representation THEN
        RAISE EXCEPTION 'ticket_invalid' USING ERRCODE = 'P0001';
    END;
    UPDATE public.fluck_web_turn_tickets t SET used_at = now()
        WHERE t.ticket_hash = pg_catalog.encode(extensions.digest(p_ticket, 'sha256'), 'hex')
          AND t.used_at IS NULL AND t.expires_at > now()
          AND t.instance_id = p_instance_id AND t.attempt_id = attempt
        RETURNING t.user_id INTO owner_id;
    IF owner_id IS NULL THEN
        RAISE EXCEPTION 'ticket_invalid' USING ERRCODE = 'P0001';
    END IF;
    RETURN owner_id;
END;
$$;

REVOKE ALL ON FUNCTION public.fluck_web_request_turn(text, text) FROM PUBLIC, anon, service_role;
GRANT EXECUTE ON FUNCTION public.fluck_web_request_turn(text, text) TO authenticated;
REVOKE ALL ON FUNCTION public.fluck_web_consume_turn_ticket(text, text, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.fluck_web_consume_turn_ticket(text, text, text) TO service_role;
