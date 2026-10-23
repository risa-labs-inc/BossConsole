-- ============================================================================
-- fluck_web_push_native: APNs devices and end-to-end encrypted push previews
-- ============================================================================
--
-- Adds the Fluck iPhone app's APNs device token (bundle app.fluck.ios), registered by the Fluck
-- plugin as the signed-in BOSS user, and the public keys the plugin encrypts message previews to.
-- The server only ever sees ciphertext produced on the user's own Fluck: the p256dh/auth stored
-- here are PUBLIC encryption targets (RFC 8291 style), never message content or private keys.
-- Same patterns as 20261013100000_fluck_web_push: owner-only RLS, invoker RPCs where RLS decides,
-- explicit grants, no service role.
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.fluck_web_push_devices (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
    installation_id  uuid NOT NULL,
    -- APNs device token, lowercase hex. The only credential for pushing to this device.
    token            text NOT NULL CHECK (token ~ '^[0-9a-f]+$' AND char_length(token) BETWEEN 64 AND 512),
    -- sha256 hex of the token: lets a new owner of the device evict the previous owner's row.
    token_hash       text NOT NULL CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    environment      text NOT NULL CHECK (environment IN ('sandbox', 'production')),
    topic            text NOT NULL DEFAULT 'app.fluck.ios' CHECK (topic = 'app.fluck.ios'),
    -- Public encryption target for the sealed preview; both or neither.
    p256dh           text CHECK (p256dh ~ '^[A-Za-z0-9_-]{80,100}$'),
    auth             text CHECK (auth ~ '^[A-Za-z0-9_-]{16,32}$'),
    created_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, installation_id),
    CONSTRAINT fluck_web_push_devices_keys_check CHECK ((p256dh IS NULL) = (auth IS NULL))
);
CREATE INDEX IF NOT EXISTS idx_fluck_web_push_devices_token_hash
    ON public.fluck_web_push_devices (token_hash);

COMMENT ON TABLE public.fluck_web_push_devices IS
    'APNs device tokens per account (max 10, oldest evicted). Token and public keys only, never message content.';

CREATE OR REPLACE FUNCTION public.fluck_web_push_devices_cap() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = ''
    AS $$
BEGIN
    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(NEW.user_id::text, 27));
    DELETE FROM public.fluck_web_push_devices d
    WHERE d.user_id = NEW.user_id
      AND d.id NOT IN (
          SELECT k.id FROM public.fluck_web_push_devices k
          WHERE k.user_id = NEW.user_id
          ORDER BY k.created_at DESC, k.id DESC
          LIMIT 10
      );
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION public.fluck_web_push_devices_cap() FROM PUBLIC, anon, authenticated;

DROP TRIGGER IF EXISTS fluck_web_push_devices_cap_after_insert ON public.fluck_web_push_devices;
CREATE TRIGGER fluck_web_push_devices_cap_after_insert
    AFTER INSERT ON public.fluck_web_push_devices
    FOR EACH ROW EXECUTE FUNCTION public.fluck_web_push_devices_cap();

ALTER TABLE public.fluck_web_push_devices ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "fluck_web_push_devices owner select" ON public.fluck_web_push_devices;
CREATE POLICY "fluck_web_push_devices owner select" ON public.fluck_web_push_devices
    FOR SELECT TO authenticated USING ((( SELECT auth.uid() ) = user_id));

DROP POLICY IF EXISTS "fluck_web_push_devices owner insert" ON public.fluck_web_push_devices;
CREATE POLICY "fluck_web_push_devices owner insert" ON public.fluck_web_push_devices
    FOR INSERT TO authenticated WITH CHECK ((( SELECT auth.uid() ) = user_id));

DROP POLICY IF EXISTS "fluck_web_push_devices owner update" ON public.fluck_web_push_devices;
CREATE POLICY "fluck_web_push_devices owner update" ON public.fluck_web_push_devices
    FOR UPDATE TO authenticated
    USING ((( SELECT auth.uid() ) = user_id)) WITH CHECK ((( SELECT auth.uid() ) = user_id));

DROP POLICY IF EXISTS "fluck_web_push_devices owner delete" ON public.fluck_web_push_devices;
CREATE POLICY "fluck_web_push_devices owner delete" ON public.fluck_web_push_devices
    FOR DELETE TO authenticated USING ((( SELECT auth.uid() ) = user_id));

REVOKE ALL ON public.fluck_web_push_devices FROM PUBLIC, anon;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.fluck_web_push_devices TO authenticated;
GRANT ALL ON public.fluck_web_push_devices TO service_role;

-- ----------------------------------------------------------------------------
-- Device RPCs (the plugin calls these as the signed-in BOSS user).
-- ----------------------------------------------------------------------------
-- DEFINER, not invoker: a device handed to a new account must stop receiving the previous
-- account's pushes, and RLS would hide that other row. Every write is pinned to auth.uid().
CREATE OR REPLACE FUNCTION public.fluck_web_push_register_native(
    p_installation_id uuid, p_token text, p_environment text,
    p_p256dh text DEFAULT NULL, p_auth text DEFAULT NULL)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    actor uuid := auth.uid();
    tok text := pg_catalog.lower(p_token);
    hash text;
BEGIN
    IF actor IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    IF p_installation_id IS NULL OR tok IS NULL OR tok !~ '^[0-9a-f]+$' OR pg_catalog.char_length(tok) NOT BETWEEN 64 AND 512
       OR p_environment IS NULL OR p_environment NOT IN ('sandbox', 'production')
       OR (p_p256dh IS NULL) <> (p_auth IS NULL)
       OR p_p256dh !~ '^[A-Za-z0-9_-]{80,100}$' OR p_auth !~ '^[A-Za-z0-9_-]{16,32}$' THEN
        RAISE EXCEPTION 'invalid_device' USING ERRCODE = 'P0001';
    END IF;
    hash := pg_catalog.encode(extensions.digest(tok, 'sha256'), 'hex');
    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(hash, 28));
    -- One device token belongs to one account. Also drops this owner's stale rows for the token.
    DELETE FROM public.fluck_web_push_devices d
    WHERE d.token_hash = hash
      AND (d.user_id <> actor OR d.installation_id <> p_installation_id);
    INSERT INTO public.fluck_web_push_devices AS d (user_id, installation_id, token, token_hash, environment, p256dh, auth)
    VALUES (actor, p_installation_id, tok, hash, p_environment, p_p256dh, p_auth)
    ON CONFLICT (user_id, installation_id) DO UPDATE SET
        token = EXCLUDED.token, token_hash = EXCLUDED.token_hash, environment = EXCLUDED.environment,
        p256dh = EXCLUDED.p256dh, auth = EXCLUDED.auth, created_at = now();
EXCEPTION WHEN data_exception OR integrity_constraint_violation OR null_value_not_allowed THEN
    -- Keep the failing row out of the client-visible DETAIL.
    RAISE EXCEPTION 'invalid_device' USING ERRCODE = 'P0001';
END;
$$;

CREATE OR REPLACE FUNCTION public.fluck_web_push_unregister_native(p_installation_id uuid)
RETURNS void LANGUAGE plpgsql SECURITY INVOKER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    DELETE FROM public.fluck_web_push_devices
    WHERE user_id = auth.uid() AND installation_id = p_installation_id;
END;
$$;

CREATE OR REPLACE FUNCTION public.fluck_web_push_native_status(p_installation_id uuid)
RETURNS boolean LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    RETURN EXISTS (
        SELECT 1 FROM public.fluck_web_push_devices d
        WHERE d.user_id = auth.uid() AND d.installation_id = p_installation_id
    );
END;
$$;

-- The caller's encryption targets, for sealing a preview before asking fluck-web to send it.
-- Public keys only; the ids are what notify's `sealed` map is keyed by.
CREATE OR REPLACE FUNCTION public.fluck_web_push_keys()
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY INVOKER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    RETURN COALESCE((
        SELECT pg_catalog.jsonb_agg(t.row ORDER BY t.created_at DESC, t.id)
        FROM (
            SELECT s.id, s.created_at, pg_catalog.jsonb_build_object(
                'id', s.id, 'kind', 'web',
                'p256dh', s.subscription -> 'keys' -> 'p256dh',
                'auth', s.subscription -> 'keys' -> 'auth',
                'portal_origin', s.portal_origin) AS row
            FROM public.fluck_web_push_subscriptions s WHERE s.user_id = auth.uid()
            UNION ALL
            SELECT d.id, d.created_at, pg_catalog.jsonb_build_object(
                'id', d.id, 'kind', 'apns', 'p256dh', d.p256dh, 'auth', d.auth) AS row
            FROM public.fluck_web_push_devices d
            WHERE d.user_id = auth.uid() AND d.p256dh IS NOT NULL
        ) t
    ), '[]'::jsonb);
END;
$$;

-- ----------------------------------------------------------------------------
-- Ticket RPCs: redeem also hands back the owner's devices; prune also drops dead devices.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.fluck_web_push_redeem(p_ticket text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    owner_id uuid;
    inst text;
    agent text;
    subs jsonb;
    devs jsonb;
BEGIN
    IF p_ticket IS NULL OR p_ticket !~ '^[A-Za-z0-9_-]{43}$' THEN
        RAISE EXCEPTION 'ticket_invalid' USING ERRCODE = 'P0001';
    END IF;
    UPDATE public.fluck_web_push_tickets t SET redeemed_at = now()
        WHERE t.ticket_hash = pg_catalog.encode(extensions.digest(p_ticket, 'sha256'), 'hex')
          AND t.redeemed_at IS NULL
          AND t.expires_at > now()
        RETURNING t.user_id, t.instance_id INTO owner_id, inst;
    IF owner_id IS NULL THEN
        RAISE EXCEPTION 'ticket_invalid' USING ERRCODE = 'P0001';
    END IF;
    SELECT i.agent_name INTO agent FROM public.fluck_web_instances i
        WHERE i.user_id = owner_id AND i.instance_id = inst;
    SELECT COALESCE(pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
               'id', s.id, 'subscription', s.subscription, 'portal_origin', s.portal_origin
           ) ORDER BY s.created_at DESC), '[]'::jsonb)
        INTO subs
        FROM public.fluck_web_push_subscriptions s WHERE s.user_id = owner_id;
    SELECT COALESCE(pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
               'id', d.id, 'token', d.token, 'environment', d.environment, 'topic', d.topic
           ) ORDER BY d.created_at DESC), '[]'::jsonb)
        INTO devs
        FROM public.fluck_web_push_devices d WHERE d.user_id = owner_id;
    RETURN pg_catalog.jsonb_build_object('instance_id', inst, 'agent_name', agent, 'subscriptions', subs, 'devices', devs);
END;
$$;

DROP FUNCTION IF EXISTS public.fluck_web_push_prune(text, uuid[]);

-- Drops dead subscriptions and devices (the push service answered 404/410, or APNs said the token
-- is bad). Only the ticket owner's rows, only while a REDEEMED ticket is unexpired.
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

REVOKE ALL ON FUNCTION public.fluck_web_push_register_native(uuid, text, text, text, text) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.fluck_web_push_unregister_native(uuid) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.fluck_web_push_native_status(uuid) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.fluck_web_push_keys() FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.fluck_web_push_redeem(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.fluck_web_push_prune(text, uuid[], uuid[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.fluck_web_push_register_native(uuid, text, text, text, text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.fluck_web_push_unregister_native(uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.fluck_web_push_native_status(uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.fluck_web_push_keys() TO authenticated;
GRANT EXECUTE ON FUNCTION public.fluck_web_push_redeem(text) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.fluck_web_push_prune(text, uuid[], uuid[]) TO anon, authenticated;
