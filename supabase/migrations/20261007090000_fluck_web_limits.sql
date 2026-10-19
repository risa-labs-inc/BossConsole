-- ============================================================================
-- fluck_web limits (follows 20261006120000_fluck_web_instances, already applied)
-- ============================================================================
--   1. Per-user cap on live fluck_web_instances rows: instance_id is caller-chosen, so without it
--      one account can grow the table without bound (the 15-minute sweep never reclaims fresh rows).
--   2. endpoint_url port limited to 1-65535, the range the fluck-web function can open; a row
--      outside it was stored but silently never listed. Prod had 0 violating rows when written.
--   3. too_many_tickets raised as P0001 (PostgREST 400) rather than 54000 (a 5xx).
-- ============================================================================

-- ---- 1. instance cap ----
CREATE OR REPLACE FUNCTION public.fluck_web_instances_cap() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = ''
    AS $$
BEGIN
    -- Serialize this owner's inserts so the count is exact.
    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(NEW.user_id::text, 24));
    -- A heartbeat for an existing instance (upsert) always passes. Rows idle past the sweep
    -- window do not count, so a crashed BOSS never locks its owner out.
    IF NOT EXISTS (
        SELECT 1 FROM public.fluck_web_instances i
        WHERE i.user_id = NEW.user_id AND i.instance_id = NEW.instance_id
    ) AND (
        SELECT count(*) FROM public.fluck_web_instances i
        WHERE i.user_id = NEW.user_id AND i.last_seen_at > now() - interval '15 minutes'
    ) >= 20 THEN
        RAISE EXCEPTION 'too_many_instances' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.fluck_web_instances_cap() FROM PUBLIC, anon, authenticated;

DROP TRIGGER IF EXISTS fluck_web_instances_cap_on_insert ON public.fluck_web_instances;
CREATE TRIGGER fluck_web_instances_cap_on_insert
    BEFORE INSERT ON public.fluck_web_instances
    FOR EACH ROW EXECUTE FUNCTION public.fluck_web_instances_cap();

-- ---- 2. port range ----
ALTER TABLE public.fluck_web_instances DROP CONSTRAINT IF EXISTS fluck_web_instances_endpoint_url_check;
ALTER TABLE public.fluck_web_instances ADD CONSTRAINT fluck_web_instances_endpoint_url_check CHECK (
    char_length(endpoint_url) <= 512
    AND endpoint_url ~ '^https://[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:([1-9][0-9]{0,3}|[1-5][0-9]{4}|6[0-4][0-9]{3}|65[0-4][0-9]{2}|655[0-2][0-9]|6553[0-5]))?$'
);

-- ---- 3. ticket cap error code (body otherwise unchanged from 20261006120000) ----
CREATE OR REPLACE FUNCTION public.fluck_web_mint_ticket(p_instance_id text)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    actor uuid := auth.uid();
    token text;
BEGIN
    IF actor IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    IF p_instance_id IS NULL OR NOT EXISTS (
        SELECT 1 FROM public.fluck_web_instances i
        WHERE i.user_id = actor AND i.instance_id = p_instance_id
          AND i.last_seen_at > now() - interval '90 seconds'
    ) THEN
        RAISE EXCEPTION 'instance_unavailable' USING ERRCODE = 'P0001';
    END IF;
    -- Serialize this owner's mints so the pending cap below is exact.
    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(actor::text, 23));
    DELETE FROM public.fluck_web_tickets t
        WHERE t.user_id = actor AND (t.expires_at <= now() OR t.used_at IS NOT NULL);
    IF (SELECT count(*) FROM public.fluck_web_tickets t WHERE t.user_id = actor) >= 20 THEN
        RAISE EXCEPTION 'too_many_tickets' USING ERRCODE = 'P0001';
    END IF;
    token := pg_catalog.translate(pg_catalog.encode(extensions.gen_random_bytes(32), 'base64'), '+/=', '-_');
    INSERT INTO public.fluck_web_tickets (ticket_hash, user_id, instance_id, expires_at)
        VALUES (pg_catalog.encode(extensions.digest(token, 'sha256'), 'hex'), actor, p_instance_id,
                now() + interval '60 seconds');
    RETURN token;
END;
$$;
REVOKE ALL ON FUNCTION public.fluck_web_mint_ticket(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.fluck_web_mint_ticket(text) TO authenticated;
