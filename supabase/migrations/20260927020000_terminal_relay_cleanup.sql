-- Global retention is maintenance work, never part of a user admission request.
CREATE INDEX terminal_relay_rooms_expiry ON public.terminal_relay_rooms(expires_at);
CREATE INDEX terminal_relay_tickets_expiry ON public.terminal_relay_tickets(expires_at);

CREATE FUNCTION public.cleanup_expired_terminal_relay_records(p_limit integer DEFAULT 500)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    cutoff timestamptz := now() - interval '5 minutes';
    tickets_deleted integer;
    rooms_deleted integer;
    handoffs_deleted integer;
BEGIN
    IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 1000 THEN
        RAISE EXCEPTION 'Invalid cleanup batch size' USING ERRCODE = '22023';
    END IF;
    -- SKIP LOCKED avoids delaying in-progress consumption or host lease renewal.
    WITH expired AS (
        SELECT token_hash FROM public.terminal_relay_tickets
        WHERE expires_at <= cutoff ORDER BY expires_at
        LIMIT p_limit FOR UPDATE SKIP LOCKED
    ) DELETE FROM public.terminal_relay_tickets t USING expired e WHERE t.token_hash = e.token_hash;
    GET DIAGNOSTICS tickets_deleted = ROW_COUNT;

    -- Room FK cascades are bounded by 100 rooms and the admission limit of 32 tickets per room.
    WITH expired AS (
        SELECT id FROM public.terminal_relay_rooms
        WHERE expires_at <= cutoff ORDER BY expires_at
        LIMIT LEAST(p_limit, 100) FOR UPDATE SKIP LOCKED
    ) DELETE FROM public.terminal_relay_rooms r USING expired e WHERE r.id = e.id;
    GET DIAGNOSTICS rooms_deleted = ROW_COUNT;

    WITH expired AS (
        SELECT token_hash FROM public.user_settings_handoffs
        WHERE expires_at <= cutoff ORDER BY expires_at
        LIMIT p_limit FOR UPDATE SKIP LOCKED
    ) DELETE FROM public.user_settings_handoffs h USING expired e WHERE h.token_hash = e.token_hash;
    GET DIAGNOSTICS handoffs_deleted = ROW_COUNT;

    RETURN jsonb_build_object('tickets', tickets_deleted, 'rooms', rooms_deleted, 'handoffs', handoffs_deleted);
END;
$$;
REVOKE ALL ON FUNCTION public.cleanup_expired_terminal_relay_records(integer) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.cleanup_expired_terminal_relay_records(integer) TO service_role;
COMMENT ON FUNCTION public.cleanup_expired_terminal_relay_records(integer) IS
    'Run every minute from a trusted scheduler. Bounded cleanup of records expired at least five minutes ago; ticket count excludes room FK cascades.';
