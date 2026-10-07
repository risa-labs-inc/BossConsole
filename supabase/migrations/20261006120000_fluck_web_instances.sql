-- ============================================================================
-- fluck_web_instances + fluck_web_tickets: the fluck.risaboss.com directory
-- ============================================================================
--
-- A signed-in BOSS whose Fluck has web chat on (with a reachable https
-- endpoint) heartbeats one row here every ~30 s, AS THE USER, through
-- fluck_web_upsert_instance. The `fluck-web` edge function lists the caller's
-- rows and, when the owner picks one, mints a single-use ticket that the
-- Fluck redeems (again as its own signed-in user) to admit the owner.
--
-- What is stored is the ENDPOINT, never chat: no message content, ever.
-- Modelled on terminal_sessions (owner-only RLS, trigger-stamped heartbeat,
-- probabilistic sweep, invoker RPCs) and terminal_relay_tickets (sha256-hashed
-- single-use tickets behind definer RPCs, deny-all table).
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.fluck_web_instances (
    user_id       uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
    -- Fluck's existing per-install id. Opaque; bounded and URL-safe so it can ride in ?instance=.
    instance_id   text NOT NULL CHECK (instance_id ~ '^[A-Za-z0-9._:-]{1,128}$'),
    label         text NOT NULL CHECK (char_length(label) BETWEEN 1 AND 80),
    agent_name    text NOT NULL CHECK (char_length(agent_name) BETWEEN 1 AND 40),
    -- An https ORIGIN (no path, query, fragment or userinfo): the page navigates to
    -- endpoint_url || '/#/t/' || ticket, so anything after the authority would be ambiguous.
    endpoint_url  text NOT NULL CHECK (
        char_length(endpoint_url) <= 512
        AND endpoint_url ~ '^https://[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:[0-9]{1,5})?$'
    ),
    app_version   text CHECK (char_length(app_version) <= 40),
    started_at    timestamptz NOT NULL DEFAULT now(),
    last_seen_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, instance_id)
);

COMMENT ON TABLE public.fluck_web_instances IS
    'Fluck web chat endpoints per account, heartbeated by the plugin; live when last_seen_at is within 90 s. Endpoint only, never message content.';

CREATE INDEX IF NOT EXISTS idx_fluck_web_instances_seen
    ON public.fluck_web_instances (last_seen_at);

-- Server-side heartbeat stamp: the client sends no timestamps.
CREATE OR REPLACE FUNCTION public.fluck_web_instances_touch() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = ''
    AS $$
BEGIN
    NEW.last_seen_at := now();
    IF TG_OP = 'UPDATE' THEN
        NEW.started_at := OLD.started_at;
        NEW.user_id := OLD.user_id;
    ELSE
        NEW.started_at := now();
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS fluck_web_instances_touch_on_write ON public.fluck_web_instances;
CREATE TRIGGER fluck_web_instances_touch_on_write
    BEFORE INSERT OR UPDATE ON public.fluck_web_instances
    FOR EACH ROW EXECUTE FUNCTION public.fluck_web_instances_touch();

-- Sweep rows idle > 15 min on ~10% of writes (a crashed BOSS never deletes its row).
-- Tickets for a swept instance go with it (FK cascade below).
CREATE OR REPLACE FUNCTION public.trigger_cleanup_stale_fluck_web_instances() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = ''
    AS $$
BEGIN
    IF random() < 0.1 THEN
        DELETE FROM public.fluck_web_instances
        WHERE last_seen_at < now() - interval '15 minutes';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.trigger_cleanup_stale_fluck_web_instances() FROM PUBLIC, anon, authenticated;

DROP TRIGGER IF EXISTS trigger_cleanup_stale_fluck_web_instances_on_write ON public.fluck_web_instances;
CREATE TRIGGER trigger_cleanup_stale_fluck_web_instances_on_write
    AFTER INSERT OR UPDATE ON public.fluck_web_instances
    FOR EACH ROW EXECUTE FUNCTION public.trigger_cleanup_stale_fluck_web_instances();

-- RLS: owner only, session call hoisted (see 20260923161000).
ALTER TABLE public.fluck_web_instances ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "fluck_web_instances owner select" ON public.fluck_web_instances;
CREATE POLICY "fluck_web_instances owner select" ON public.fluck_web_instances
    FOR SELECT TO authenticated USING ((( SELECT auth.uid() ) = user_id));

DROP POLICY IF EXISTS "fluck_web_instances owner insert" ON public.fluck_web_instances;
CREATE POLICY "fluck_web_instances owner insert" ON public.fluck_web_instances
    FOR INSERT TO authenticated WITH CHECK ((( SELECT auth.uid() ) = user_id));

DROP POLICY IF EXISTS "fluck_web_instances owner update" ON public.fluck_web_instances;
CREATE POLICY "fluck_web_instances owner update" ON public.fluck_web_instances
    FOR UPDATE TO authenticated
    USING ((( SELECT auth.uid() ) = user_id)) WITH CHECK ((( SELECT auth.uid() ) = user_id));

DROP POLICY IF EXISTS "fluck_web_instances owner delete" ON public.fluck_web_instances;
CREATE POLICY "fluck_web_instances owner delete" ON public.fluck_web_instances
    FOR DELETE TO authenticated USING ((( SELECT auth.uid() ) = user_id));

REVOKE ALL ON public.fluck_web_instances FROM PUBLIC, anon;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.fluck_web_instances TO authenticated;
GRANT ALL ON public.fluck_web_instances TO service_role;

-- ----------------------------------------------------------------------------
-- Tickets: deny-all table, reached only through the two definer RPCs.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.fluck_web_tickets (
    ticket_hash  text PRIMARY KEY CHECK (ticket_hash ~ '^[0-9a-f]{64}$'),
    user_id      uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    instance_id  text NOT NULL,
    expires_at   timestamptz NOT NULL,
    used_at      timestamptz,
    -- Deleting or sweeping an instance invalidates its outstanding tickets.
    FOREIGN KEY (user_id, instance_id)
        REFERENCES public.fluck_web_instances(user_id, instance_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_fluck_web_tickets_owner
    ON public.fluck_web_tickets (user_id, instance_id);

ALTER TABLE public.fluck_web_tickets ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.fluck_web_tickets FROM PUBLIC, anon, authenticated;

-- ----------------------------------------------------------------------------
-- Invoker RPCs (the plugin heartbeats as the user; RLS is authoritative).
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.fluck_web_upsert_instance(
    p_instance_id text, p_label text, p_agent_name text, p_endpoint_url text, p_app_version text
) RETURNS void LANGUAGE plpgsql SECURITY INVOKER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    INSERT INTO public.fluck_web_instances AS i (
        user_id, instance_id, label, agent_name, endpoint_url, app_version
    ) VALUES (
        auth.uid(), p_instance_id, p_label, p_agent_name,
        -- Tolerate one trailing slash on an origin; the CHECK refuses anything else.
        pg_catalog.rtrim(p_endpoint_url, '/'), p_app_version
    ) ON CONFLICT (user_id, instance_id) DO UPDATE SET
        label = EXCLUDED.label, agent_name = EXCLUDED.agent_name,
        endpoint_url = EXCLUDED.endpoint_url, app_version = EXCLUDED.app_version;
EXCEPTION WHEN data_exception OR integrity_constraint_violation THEN
    -- Keep the failing row out of the client-visible DETAIL.
    RAISE EXCEPTION 'Invalid Fluck web instance' USING ERRCODE = '22023';
END;
$$;

CREATE OR REPLACE FUNCTION public.fluck_web_delete_instance(p_instance_id text)
RETURNS void LANGUAGE plpgsql SECURITY INVOKER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    DELETE FROM public.fluck_web_instances WHERE user_id = auth.uid() AND instance_id = p_instance_id;
END;
$$;

-- Live = server-clock heartbeat within 90 s (keep aligned with LIVE_WINDOW_SECONDS in
-- fluck-web/utils/config.ts). Offline rows are listed too, until the 15-minute sweep.
CREATE OR REPLACE FUNCTION public.fluck_web_list_instances()
RETURNS TABLE (
    instance_id text, label text, agent_name text, endpoint_url text, app_version text,
    started_at timestamptz, last_seen_at timestamptz, online boolean
) LANGUAGE plpgsql SECURITY INVOKER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    RETURN QUERY
        SELECT i.instance_id, i.label, i.agent_name, i.endpoint_url, i.app_version,
               i.started_at, i.last_seen_at, i.last_seen_at > now() - interval '90 seconds'
        FROM public.fluck_web_instances i
        WHERE i.user_id = auth.uid()
        ORDER BY i.last_seen_at DESC
        LIMIT 100;
END;
$$;

-- ----------------------------------------------------------------------------
-- Definer RPCs: tickets.
-- ----------------------------------------------------------------------------
-- Raw ticket = 32 random bytes, base64url (43 chars); only its sha256 hex is stored.
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
        RAISE EXCEPTION 'too_many_tickets' USING ERRCODE = '54000';
    END IF;
    token := pg_catalog.translate(pg_catalog.encode(extensions.gen_random_bytes(32), 'base64'), '+/=', '-_');
    INSERT INTO public.fluck_web_tickets (ticket_hash, user_id, instance_id, expires_at)
        VALUES (pg_catalog.encode(extensions.digest(token, 'sha256'), 'hex'), actor, p_instance_id,
                now() + interval '60 seconds');
    RETURN token;
END;
$$;

-- Called by the Fluck AS ITS OWN signed-in BOSS user: a ticket minted by another account can never
-- be redeemed, because t.user_id must equal the caller. Every refusal is the same 'ticket_invalid'.
CREATE OR REPLACE FUNCTION public.fluck_web_consume_ticket(p_ticket text, p_instance_id text)
RETURNS TABLE (user_id uuid, email text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
DECLARE
    actor uuid := auth.uid();
    owner_id uuid;
BEGIN
    IF actor IS NULL OR p_instance_id IS NULL OR p_ticket IS NULL
       OR p_ticket !~ '^[A-Za-z0-9_-]{43}$' THEN
        RAISE EXCEPTION 'ticket_invalid' USING ERRCODE = 'P0001';
    END IF;
    UPDATE public.fluck_web_tickets t SET used_at = now()
        WHERE t.ticket_hash = pg_catalog.encode(extensions.digest(p_ticket, 'sha256'), 'hex')
          AND t.used_at IS NULL
          AND t.expires_at > now()
          AND t.user_id = actor
          AND t.instance_id = p_instance_id
        RETURNING t.user_id INTO owner_id;
    IF owner_id IS NULL THEN
        RAISE EXCEPTION 'ticket_invalid' USING ERRCODE = 'P0001';
    END IF;
    RETURN QUERY SELECT u.id, u.email::text FROM auth.users u WHERE u.id = owner_id;
END;
$$;

REVOKE ALL ON FUNCTION public.fluck_web_upsert_instance(text, text, text, text, text) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.fluck_web_delete_instance(text) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.fluck_web_list_instances() FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.fluck_web_mint_ticket(text) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.fluck_web_consume_ticket(text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.fluck_web_upsert_instance(text, text, text, text, text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.fluck_web_delete_instance(text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.fluck_web_list_instances() TO authenticated;
GRANT EXECUTE ON FUNCTION public.fluck_web_mint_ticket(text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.fluck_web_consume_ticket(text, text) TO authenticated;
