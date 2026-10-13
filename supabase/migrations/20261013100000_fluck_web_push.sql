-- ============================================================================
-- fluck_web_push: Web Push for the fluck.risaboss.com / fluck.ai portal
-- ============================================================================
--
-- The framed chat is cross-origin and cannot ask for notification permission, so the portal (a
-- stable origin) owns the push subscription. A signed-in owner stores a browser PushSubscription
-- here; the Fluck plugin, as the same signed-in user, mints a short-lived ticket per message and
-- POSTs it to the fluck-web function, which redeems it with the anon key and sends the push.
--
-- What is stored is the subscription (an endpoint on an allow-listed push service plus its two
-- public keys), never message content, names or PHI; the push payload carries only the agent name.
-- Same patterns as 20261006120000_fluck_web_instances: owner-only RLS, invoker RPCs, sha256-hashed
-- single-use tickets behind definer RPCs, deny-all ticket table.
-- ============================================================================

CREATE TABLE IF NOT EXISTS public.fluck_web_push_subscriptions (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
    -- sha256 hex of the endpoint: the unsubscribe key, so the endpoint never travels in a URL.
    endpoint_hash  text NOT NULL CHECK (endpoint_hash ~ '^[0-9a-f]{64}$'),
    -- {endpoint, keys: {p256dh, auth}} and nothing else (expirationTime is dropped by the RPC).
    subscription   jsonb NOT NULL,
    portal_origin  text NOT NULL CHECK (portal_origin IN ('https://fluck.risaboss.com', 'https://fluck.ai')),
    created_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, endpoint_hash),
    -- Push services only (keep aligned with isAllowedPushEndpoint in fluck-web/utils/webpush.ts):
    -- the function POSTs to this URL, so an arbitrary https host would be a request-forgery sink.
    CONSTRAINT fluck_web_push_subscriptions_endpoint_check CHECK (
        jsonb_typeof(subscription) = 'object'
        AND jsonb_typeof(subscription -> 'endpoint') = 'string'
        AND char_length(subscription ->> 'endpoint') <= 1024
        AND (subscription ->> 'endpoint') ~ '^https://(fcm\.googleapis\.com|android\.googleapis\.com|web\.push\.apple\.com|updates\.push\.services\.mozilla\.com|([A-Za-z0-9-]+\.)+notify\.windows\.com)/[^[:space:]]*$'
    ),
    CONSTRAINT fluck_web_push_subscriptions_keys_check CHECK (
        jsonb_typeof(subscription -> 'keys') = 'object'
        AND jsonb_typeof(subscription -> 'keys' -> 'p256dh') = 'string'
        AND jsonb_typeof(subscription -> 'keys' -> 'auth') = 'string'
        AND (subscription -> 'keys' ->> 'p256dh') ~ '^[A-Za-z0-9_-]{80,100}$'
        AND (subscription -> 'keys' ->> 'auth') ~ '^[A-Za-z0-9_-]{16,32}$'
    )
);

COMMENT ON TABLE public.fluck_web_push_subscriptions IS
    'Portal Web Push subscriptions per account (max 10, oldest evicted). Endpoint and public keys only, never message content.';

-- Keep the newest 10 per owner. Runs as the inserting user, so RLS limits it to their own rows.
CREATE OR REPLACE FUNCTION public.fluck_web_push_subscriptions_cap() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = ''
    AS $$
BEGIN
    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(NEW.user_id::text, 25));
    DELETE FROM public.fluck_web_push_subscriptions s
    WHERE s.user_id = NEW.user_id
      AND s.id NOT IN (
          SELECT k.id FROM public.fluck_web_push_subscriptions k
          WHERE k.user_id = NEW.user_id
          ORDER BY k.created_at DESC, k.id DESC
          LIMIT 10
      );
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION public.fluck_web_push_subscriptions_cap() FROM PUBLIC, anon, authenticated;

DROP TRIGGER IF EXISTS fluck_web_push_subscriptions_cap_after_insert ON public.fluck_web_push_subscriptions;
CREATE TRIGGER fluck_web_push_subscriptions_cap_after_insert
    AFTER INSERT ON public.fluck_web_push_subscriptions
    FOR EACH ROW EXECUTE FUNCTION public.fluck_web_push_subscriptions_cap();

-- RLS: owner only, session call hoisted (see 20260923161000).
ALTER TABLE public.fluck_web_push_subscriptions ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "fluck_web_push_subscriptions owner select" ON public.fluck_web_push_subscriptions;
CREATE POLICY "fluck_web_push_subscriptions owner select" ON public.fluck_web_push_subscriptions
    FOR SELECT TO authenticated USING ((( SELECT auth.uid() ) = user_id));

DROP POLICY IF EXISTS "fluck_web_push_subscriptions owner insert" ON public.fluck_web_push_subscriptions;
CREATE POLICY "fluck_web_push_subscriptions owner insert" ON public.fluck_web_push_subscriptions
    FOR INSERT TO authenticated WITH CHECK ((( SELECT auth.uid() ) = user_id));

DROP POLICY IF EXISTS "fluck_web_push_subscriptions owner update" ON public.fluck_web_push_subscriptions;
CREATE POLICY "fluck_web_push_subscriptions owner update" ON public.fluck_web_push_subscriptions
    FOR UPDATE TO authenticated
    USING ((( SELECT auth.uid() ) = user_id)) WITH CHECK ((( SELECT auth.uid() ) = user_id));

DROP POLICY IF EXISTS "fluck_web_push_subscriptions owner delete" ON public.fluck_web_push_subscriptions;
CREATE POLICY "fluck_web_push_subscriptions owner delete" ON public.fluck_web_push_subscriptions
    FOR DELETE TO authenticated USING ((( SELECT auth.uid() ) = user_id));

REVOKE ALL ON public.fluck_web_push_subscriptions FROM PUBLIC, anon;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.fluck_web_push_subscriptions TO authenticated;
GRANT ALL ON public.fluck_web_push_subscriptions TO service_role;

-- ----------------------------------------------------------------------------
-- Tickets: deny-all table, reached only through the definer RPCs below.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.fluck_web_push_tickets (
    ticket_hash  text PRIMARY KEY CHECK (ticket_hash ~ '^[0-9a-f]{64}$'),
    user_id      uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    instance_id  text NOT NULL,
    expires_at   timestamptz NOT NULL,
    redeemed_at  timestamptz,
    -- Deleting or sweeping an instance invalidates its outstanding tickets.
    FOREIGN KEY (user_id, instance_id)
        REFERENCES public.fluck_web_instances(user_id, instance_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_fluck_web_push_tickets_owner
    ON public.fluck_web_push_tickets (user_id, expires_at);

ALTER TABLE public.fluck_web_push_tickets ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.fluck_web_push_tickets FROM PUBLIC, anon, authenticated;

-- ----------------------------------------------------------------------------
-- Invoker RPCs (the portal calls these as the signed-in owner; RLS is authoritative).
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.fluck_web_push_subscribe(p_subscription jsonb, p_origin text)
RETURNS void LANGUAGE plpgsql SECURITY INVOKER SET search_path = '' AS $$
DECLARE
    sub jsonb;
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    IF p_subscription IS NULL OR jsonb_typeof(p_subscription) <> 'object'
       OR jsonb_typeof(p_subscription -> 'keys') <> 'object' THEN
        RAISE EXCEPTION 'invalid_subscription' USING ERRCODE = 'P0001';
    END IF;
    -- Rebuild so nothing but the three fields is stored.
    sub := jsonb_build_object(
        'endpoint', p_subscription -> 'endpoint',
        'keys', jsonb_build_object(
            'p256dh', p_subscription -> 'keys' -> 'p256dh',
            'auth', p_subscription -> 'keys' -> 'auth'
        )
    );
    INSERT INTO public.fluck_web_push_subscriptions AS s (user_id, endpoint_hash, subscription, portal_origin)
    VALUES (
        auth.uid(),
        pg_catalog.encode(extensions.digest(sub ->> 'endpoint', 'sha256'), 'hex'),
        sub, p_origin
    ) ON CONFLICT (user_id, endpoint_hash) DO UPDATE SET
        subscription = EXCLUDED.subscription, portal_origin = EXCLUDED.portal_origin, created_at = now();
EXCEPTION WHEN data_exception OR integrity_constraint_violation OR null_value_not_allowed THEN
    -- Keep the failing row out of the client-visible DETAIL.
    RAISE EXCEPTION 'invalid_subscription' USING ERRCODE = 'P0001';
END;
$$;

CREATE OR REPLACE FUNCTION public.fluck_web_push_unsubscribe(p_endpoint_hash text)
RETURNS void LANGUAGE plpgsql SECURITY INVOKER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    DELETE FROM public.fluck_web_push_subscriptions
    WHERE user_id = auth.uid() AND endpoint_hash = p_endpoint_hash;
END;
$$;

-- ----------------------------------------------------------------------------
-- Definer RPCs: tickets.
-- ----------------------------------------------------------------------------
-- Called by the plugin as its signed-in BOSS user, once per notification. Raw ticket = 32 random
-- bytes, base64url (43 chars); only its sha256 hex is stored.
CREATE OR REPLACE FUNCTION public.fluck_web_push_ticket(p_instance_id text)
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
    ) THEN
        RAISE EXCEPTION 'instance_unavailable' USING ERRCODE = 'P0001';
    END IF;
    -- Serialize this owner's mints so the pending cap below is exact.
    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(actor::text, 26));
    -- A redeemed ticket stays until it expires: prune is only honoured within that window.
    DELETE FROM public.fluck_web_push_tickets t WHERE t.user_id = actor AND t.expires_at <= now();
    IF (SELECT count(*) FROM public.fluck_web_push_tickets t WHERE t.user_id = actor) >= 30 THEN
        RAISE EXCEPTION 'too_many_tickets' USING ERRCODE = 'P0001';
    END IF;
    token := pg_catalog.translate(pg_catalog.encode(extensions.gen_random_bytes(32), 'base64'), '+/=', '-_');
    INSERT INTO public.fluck_web_push_tickets (ticket_hash, user_id, instance_id, expires_at)
        VALUES (pg_catalog.encode(extensions.digest(token, 'sha256'), 'hex'), actor, p_instance_id,
                now() + interval '60 seconds');
    RETURN token;
END;
$$;

-- Called by the fluck-web function with the ANON key: the ticket is the only credential. Single
-- use; every refusal is the same 'ticket_invalid'.
CREATE OR REPLACE FUNCTION public.fluck_web_push_redeem(p_ticket text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    owner_id uuid;
    inst text;
    agent text;
    subs jsonb;
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
    RETURN pg_catalog.jsonb_build_object('instance_id', inst, 'agent_name', agent, 'subscriptions', subs);
END;
$$;

-- Drops dead subscriptions (the push service answered 404/410). Only the ticket owner's rows, only
-- while a REDEEMED ticket is unexpired; anything else deletes nothing.
CREATE OR REPLACE FUNCTION public.fluck_web_push_prune(p_ticket text, p_ids uuid[])
RETURNS integer LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    owner_id uuid;
    removed integer;
BEGIN
    IF p_ticket IS NULL OR p_ticket !~ '^[A-Za-z0-9_-]{43}$' OR p_ids IS NULL
       OR pg_catalog.cardinality(p_ids) > 50 THEN
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
    RETURN removed;
END;
$$;

REVOKE ALL ON FUNCTION public.fluck_web_push_subscribe(jsonb, text) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.fluck_web_push_unsubscribe(text) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.fluck_web_push_ticket(text) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.fluck_web_push_redeem(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.fluck_web_push_prune(text, uuid[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.fluck_web_push_subscribe(jsonb, text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.fluck_web_push_unsubscribe(text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.fluck_web_push_ticket(text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.fluck_web_push_redeem(text) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.fluck_web_push_prune(text, uuid[]) TO anon, authenticated;
