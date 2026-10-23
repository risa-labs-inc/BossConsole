-- Preserve old edge-function calls after the already-deployed native push migration.
-- PostgreSQL defaults let PostgREST resolve both the legacy two-argument call and the
-- native three-argument call against the same function; grants and identity are retained.
CREATE OR REPLACE FUNCTION public.fluck_web_push_prune(p_ticket text, p_ids uuid[], p_device_ids uuid[] DEFAULT '{}'::uuid[])
RETURNS integer LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    owner_id uuid;
    removed integer;
    removed_devices integer;
BEGIN
    IF p_ticket IS NULL OR p_ticket !~ '^[A-Za-z0-9_-]{43}$' OR p_ids IS NULL OR p_device_ids IS NULL
       OR pg_catalog.cardinality(p_ids) > 50 OR pg_catalog.cardinality(p_device_ids) > 50 THEN
        RETURN 0;
    END IF;
    SELECT t.user_id INTO owner_id FROM public.fluck_web_push_tickets t
        WHERE t.ticket_hash = pg_catalog.encode(extensions.digest(p_ticket, 'sha256'), 'hex')
          AND t.redeemed_at IS NOT NULL
          AND t.expires_at > now();
    IF owner_id IS NULL THEN
        RETURN 0;
    END IF;
    DELETE FROM public.fluck_web_push_subscriptions s
        WHERE s.user_id = owner_id AND s.id = ANY (p_ids);
    GET DIAGNOSTICS removed = ROW_COUNT;
    DELETE FROM public.fluck_web_push_devices d
        WHERE d.user_id = owner_id AND d.id = ANY (p_device_ids);
    GET DIAGNOSTICS removed_devices = ROW_COUNT;
    RETURN removed + removed_devices;
END;
$$;

-- Reassert intended ticket-RPC grants after DDL permission guards run.
REVOKE ALL ON FUNCTION public.fluck_web_push_prune(text, uuid[], uuid[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.fluck_web_push_prune(text, uuid[], uuid[]) TO anon, authenticated;
