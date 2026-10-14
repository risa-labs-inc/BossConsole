-- ============================================================================
-- fluck_peer_links + fluck_a2a_tickets: Fluck-to-Fluck (A2A) peering
-- (follows 20261006120000_fluck_web_instances and 20261007090000_fluck_web_limits)
-- ============================================================================
--
-- Every Fluck already heartbeats a row in fluck_web_instances AS ITS USER. A2A reuses
-- that directory: a user's own Flucks may call each other freely, and another user's
-- Fluck may be called only after that user ACCEPTED a link request.
--
--   * fluck_peer_links: one row per unordered user pair (requester -> recipient, with a
--     status). Written only through the definer RPCs below; either party can read its rows.
--   * fluck_a2a_tickets: deny-all, sha256-hashed, single-use, 60 s tickets. The CALLING
--     Fluck mints one for a (target user, target instance); the RECEIVING Fluck redeems it
--     as its own signed-in user, which also re-checks that the link is still accepted.
--
-- What is stored is the link state and the ticket hash, never a message: no A2A content,
-- ever. Requests never reveal whether an email has an account (unknown email and
-- self are silent no-ops). Modelled on fluck_web_instances / fluck_web_tickets.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Links: one row per unordered pair.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.fluck_peer_links (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    requester_id  uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    recipient_id  uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    status        text NOT NULL CHECK (status IN ('pending', 'accepted', 'declined', 'revoked')),
    requested_at  timestamptz NOT NULL DEFAULT now(),
    responded_at  timestamptz,
    -- Stamped once by fluck_peer_claim_notice so exactly one Fluck announces a request.
    notified_at   timestamptz,
    CHECK (requester_id <> recipient_id)
);

COMMENT ON TABLE public.fluck_peer_links IS
    'Fluck A2A link requests between two accounts (one row per unordered pair). Link state only, never message content.';

CREATE UNIQUE INDEX IF NOT EXISTS idx_fluck_peer_links_pair
    ON public.fluck_peer_links (
        (least(requester_id, recipient_id)), (greatest(requester_id, recipient_id))
    );
CREATE INDEX IF NOT EXISTS idx_fluck_peer_links_requester
    ON public.fluck_peer_links (requester_id, status);
CREATE INDEX IF NOT EXISTS idx_fluck_peer_links_recipient
    ON public.fluck_peer_links (recipient_id, status);

-- RLS: either party may read; there are no write policies (writes go through definer RPCs).
ALTER TABLE public.fluck_peer_links ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "fluck_peer_links party select" ON public.fluck_peer_links;
CREATE POLICY "fluck_peer_links party select" ON public.fluck_peer_links
    FOR SELECT TO authenticated
    USING ((( SELECT auth.uid() ) = requester_id) OR (( SELECT auth.uid() ) = recipient_id));

REVOKE ALL ON public.fluck_peer_links FROM PUBLIC, anon, authenticated;
GRANT SELECT ON public.fluck_peer_links TO authenticated;
GRANT ALL ON public.fluck_peer_links TO service_role;

-- ----------------------------------------------------------------------------
-- Tickets: deny-all table, reached only through the two definer RPCs.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.fluck_a2a_tickets (
    ticket_hash      text PRIMARY KEY CHECK (ticket_hash ~ '^[0-9a-f]{64}$'),
    issuer_id        uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    issuer_instance  text NOT NULL,
    target_user      uuid NOT NULL,
    target_instance  text NOT NULL,
    expires_at       timestamptz NOT NULL,
    used_at          timestamptz,
    -- Deleting or sweeping the target instance invalidates its outstanding tickets.
    FOREIGN KEY (target_user, target_instance)
        REFERENCES public.fluck_web_instances(user_id, instance_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_fluck_a2a_tickets_issuer
    ON public.fluck_a2a_tickets (issuer_id);
CREATE INDEX IF NOT EXISTS idx_fluck_a2a_tickets_target
    ON public.fluck_a2a_tickets (target_user, target_instance);

ALTER TABLE public.fluck_a2a_tickets ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.fluck_a2a_tickets FROM PUBLIC, anon, authenticated;

-- ----------------------------------------------------------------------------
-- Definer RPCs: link management. All write paths live here; the caller is auth.uid().
-- ----------------------------------------------------------------------------

-- Ask another account (by email) to link. Never reveals whether the email exists.
CREATE OR REPLACE FUNCTION public.fluck_peer_request(p_email text)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    actor uuid := auth.uid();
    norm_email text;
    target uuid;
    existing public.fluck_peer_links%ROWTYPE;
BEGIN
    IF actor IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    -- Serialize this requester's requests so the pending cap below is exact. The cap is checked
    -- before the lookup so it can never be used to probe which emails have accounts.
    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(actor::text, 25));
    IF (SELECT count(*) FROM public.fluck_peer_links l
        WHERE l.requester_id = actor AND l.status = 'pending') >= 20 THEN
        RAISE EXCEPTION 'too_many_requests' USING ERRCODE = 'P0001';
    END IF;

    norm_email := pg_catalog.lower(pg_catalog.btrim(p_email));
    IF norm_email IS NULL OR norm_email = '' THEN
        RETURN;
    END IF;
    SELECT u.id INTO target FROM auth.users u
        WHERE u.email = norm_email
        LIMIT 1;
    IF target IS NULL OR target = actor THEN
        RETURN;
    END IF;

    SELECT * INTO existing FROM public.fluck_peer_links l
        WHERE least(l.requester_id, l.recipient_id) = least(actor, target)
          AND greatest(l.requester_id, l.recipient_id) = greatest(actor, target)
        FOR UPDATE;
    IF FOUND THEN
        -- Accepted or pending: nothing to do. Declined or revoked: re-request only after a
        -- 1-day cool-down, so a refusal cannot be nagged away.
        IF existing.status IN ('declined', 'revoked')
           AND existing.responded_at IS NOT NULL
           AND existing.responded_at < now() - interval '1 day' THEN
            UPDATE public.fluck_peer_links l
                SET status = 'pending', requester_id = actor, recipient_id = target,
                    requested_at = now(), responded_at = NULL, notified_at = NULL
                WHERE l.id = existing.id;
        END IF;
        RETURN;
    END IF;

    -- A concurrent request from the other side loses to the unique pair index silently.
    INSERT INTO public.fluck_peer_links (requester_id, recipient_id, status)
        VALUES (actor, target, 'pending')
        ON CONFLICT DO NOTHING;
END;
$$;

-- Incoming requests awaiting the caller's answer.
CREATE OR REPLACE FUNCTION public.fluck_peer_pending()
RETURNS TABLE (id uuid, requester_email text, requested_at timestamptz)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    RETURN QUERY
        SELECT l.id, u.email::text, l.requested_at
        FROM public.fluck_peer_links l
        JOIN auth.users u ON u.id = l.requester_id
        WHERE l.recipient_id = auth.uid() AND l.status = 'pending'
        ORDER BY l.requested_at DESC
        LIMIT 50;
END;
$$;

-- Atomic "I will announce this request": true for exactly one caller, once.
CREATE OR REPLACE FUNCTION public.fluck_peer_claim_notice(p_id uuid)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    actor uuid := auth.uid();
    claimed uuid;
BEGIN
    IF actor IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    UPDATE public.fluck_peer_links l SET notified_at = now()
        WHERE l.id = p_id AND l.recipient_id = actor
          AND l.status = 'pending' AND l.notified_at IS NULL
        RETURNING l.id INTO claimed;
    RETURN claimed IS NOT NULL;
END;
$$;

-- The recipient answers a pending request.
CREATE OR REPLACE FUNCTION public.fluck_peer_respond(p_id uuid, p_accept boolean)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    actor uuid := auth.uid();
    updated uuid;
BEGIN
    IF actor IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    IF p_id IS NULL OR p_accept IS NULL THEN
        RAISE EXCEPTION 'request_invalid' USING ERRCODE = 'P0001';
    END IF;
    UPDATE public.fluck_peer_links l
        SET status = CASE WHEN p_accept THEN 'accepted' ELSE 'declined' END,
            responded_at = now()
        WHERE l.id = p_id AND l.recipient_id = actor AND l.status = 'pending'
        RETURNING l.id INTO updated;
    IF updated IS NULL THEN
        RAISE EXCEPTION 'request_invalid' USING ERRCODE = 'P0001';
    END IF;
END;
$$;

-- Either party ends a pending or accepted link; outstanding tickets between them die too.
CREATE OR REPLACE FUNCTION public.fluck_peer_revoke(p_peer_id uuid)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    actor uuid := auth.uid();
BEGIN
    IF actor IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    IF p_peer_id IS NULL OR p_peer_id = actor THEN
        RETURN;
    END IF;
    UPDATE public.fluck_peer_links l
        SET status = 'revoked', responded_at = now()
        WHERE least(l.requester_id, l.recipient_id) = least(actor, p_peer_id)
          AND greatest(l.requester_id, l.recipient_id) = greatest(actor, p_peer_id)
          AND l.status IN ('pending', 'accepted');
    DELETE FROM public.fluck_a2a_tickets t
        WHERE (t.issuer_id = actor AND t.target_user = p_peer_id)
           OR (t.issuer_id = p_peer_id AND t.target_user = actor);
END;
$$;

-- Every link row involving the caller, seen from the caller's side.
CREATE OR REPLACE FUNCTION public.fluck_peer_links()
RETURNS TABLE (peer_id uuid, peer_email text, status text, outgoing boolean)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
DECLARE
    actor uuid := auth.uid();
BEGIN
    IF actor IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    RETURN QUERY
        SELECT u.id, u.email::text, l.status, (l.requester_id = actor)
        FROM public.fluck_peer_links l
        JOIN auth.users u
          ON u.id = CASE WHEN l.requester_id = actor THEN l.recipient_id ELSE l.requester_id END
        WHERE l.requester_id = actor OR l.recipient_id = actor
        ORDER BY l.requested_at DESC;
END;
$$;

-- ----------------------------------------------------------------------------
-- Definer RPCs: directory and tickets.
-- ----------------------------------------------------------------------------
-- The caller's own Flucks plus those of every account with an accepted link. Live = server-clock
-- heartbeat within 90 s (same window as fluck_web_list_instances); offline rows are listed too.
CREATE OR REPLACE FUNCTION public.fluck_peer_list()
RETURNS TABLE (
    user_id uuid, email text, same_user boolean, instance_id text, label text,
    agent_name text, endpoint_url text, online boolean
) LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
DECLARE
    actor uuid := auth.uid();
BEGIN
    IF actor IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    RETURN QUERY
        SELECT i.user_id, u.email::text, (i.user_id = actor), i.instance_id, i.label,
               i.agent_name, i.endpoint_url, (i.last_seen_at > now() - interval '90 seconds')
        FROM public.fluck_web_instances i
        JOIN auth.users u ON u.id = i.user_id
        WHERE i.user_id = actor
           OR EXISTS (
                SELECT 1 FROM public.fluck_peer_links l
                WHERE l.status = 'accepted'
                  AND ((l.requester_id = actor AND l.recipient_id = i.user_id)
                    OR (l.recipient_id = actor AND l.requester_id = i.user_id))
           )
        ORDER BY (i.user_id = actor) DESC, i.last_seen_at DESC
        LIMIT 200;
END;
$$;

-- Raw ticket = 32 random bytes, base64url (43 chars); only its sha256 hex is stored.
CREATE OR REPLACE FUNCTION public.fluck_a2a_mint_ticket(
    p_from_instance text, p_target_user uuid, p_target_instance text
) RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    actor uuid := auth.uid();
    token text;
BEGIN
    IF actor IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    IF p_from_instance IS NULL OR p_target_user IS NULL OR p_target_instance IS NULL THEN
        RAISE EXCEPTION 'peer_unavailable' USING ERRCODE = 'P0001';
    END IF;
    -- The calling Fluck need not be listed: one without a public endpoint still calls out and polls.
    -- Its instance id is informational; the caller's identity is auth.uid().
    IF p_from_instance !~ '^[A-Za-z0-9._:-]{1,128}$' THEN
        RAISE EXCEPTION 'peer_unavailable' USING ERRCODE = 'P0001';
    END IF;
    -- The target is the caller's own account or an accepted peer, and its instance is live.
    IF p_target_user <> actor AND NOT EXISTS (
        SELECT 1 FROM public.fluck_peer_links l
        WHERE l.status = 'accepted'
          AND ((l.requester_id = actor AND l.recipient_id = p_target_user)
            OR (l.recipient_id = actor AND l.requester_id = p_target_user))
    ) THEN
        RAISE EXCEPTION 'peer_unavailable' USING ERRCODE = 'P0001';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM public.fluck_web_instances i
        WHERE i.user_id = p_target_user AND i.instance_id = p_target_instance
          AND i.last_seen_at > now() - interval '90 seconds'
    ) THEN
        RAISE EXCEPTION 'peer_unavailable' USING ERRCODE = 'P0001';
    END IF;
    -- Serialize this issuer's mints so the pending cap below is exact.
    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(actor::text, 26));
    DELETE FROM public.fluck_a2a_tickets t
        WHERE t.issuer_id = actor AND (t.expires_at <= now() OR t.used_at IS NOT NULL);
    IF (SELECT count(*) FROM public.fluck_a2a_tickets t WHERE t.issuer_id = actor) >= 50 THEN
        RAISE EXCEPTION 'too_many_tickets' USING ERRCODE = 'P0001';
    END IF;
    token := pg_catalog.translate(pg_catalog.encode(extensions.gen_random_bytes(32), 'base64'), '+/=', '-_');
    INSERT INTO public.fluck_a2a_tickets (
        ticket_hash, issuer_id, issuer_instance, target_user, target_instance, expires_at
    ) VALUES (
        pg_catalog.encode(extensions.digest(token, 'sha256'), 'hex'), actor, p_from_instance,
        p_target_user, p_target_instance, now() + interval '60 seconds'
    );
    RETURN token;
END;
$$;

-- Called by the RECEIVING Fluck AS ITS OWN signed-in BOSS user: a ticket aimed at another account
-- or instance can never be redeemed, because target_user must equal the caller. Every refusal is
-- the same 'ticket_invalid' (a refused cross-user redemption rolls back, leaving the ticket
-- unused until its 60 s expiry).
CREATE OR REPLACE FUNCTION public.fluck_a2a_consume_ticket(p_ticket text, p_instance_id text)
RETURNS TABLE (user_id uuid, email text, instance_id text, same_user boolean)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
DECLARE
    actor uuid := auth.uid();
    from_user uuid;
    from_instance text;
BEGIN
    IF actor IS NULL OR p_instance_id IS NULL OR p_ticket IS NULL
       OR p_ticket !~ '^[A-Za-z0-9_-]{43}$' THEN
        RAISE EXCEPTION 'ticket_invalid' USING ERRCODE = 'P0001';
    END IF;
    UPDATE public.fluck_a2a_tickets t SET used_at = now()
        WHERE t.ticket_hash = pg_catalog.encode(extensions.digest(p_ticket, 'sha256'), 'hex')
          AND t.used_at IS NULL
          AND t.expires_at > now()
          AND t.target_user = actor
          AND t.target_instance = p_instance_id
        RETURNING t.issuer_id, t.issuer_instance INTO from_user, from_instance;
    IF from_user IS NULL THEN
        RAISE EXCEPTION 'ticket_invalid' USING ERRCODE = 'P0001';
    END IF;
    -- A revoked link voids tickets already in flight.
    IF from_user <> actor AND NOT EXISTS (
        SELECT 1 FROM public.fluck_peer_links l
        WHERE l.status = 'accepted'
          AND ((l.requester_id = actor AND l.recipient_id = from_user)
            OR (l.recipient_id = actor AND l.requester_id = from_user))
    ) THEN
        RAISE EXCEPTION 'ticket_invalid' USING ERRCODE = 'P0001';
    END IF;
    RETURN QUERY
        SELECT u.id, u.email::text, from_instance, (from_user = actor)
        FROM auth.users u WHERE u.id = from_user;
END;
$$;

REVOKE ALL ON FUNCTION public.fluck_peer_request(text) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.fluck_peer_pending() FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.fluck_peer_claim_notice(uuid) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.fluck_peer_respond(uuid, boolean) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.fluck_peer_revoke(uuid) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.fluck_peer_links() FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.fluck_peer_list() FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.fluck_a2a_mint_ticket(text, uuid, text) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.fluck_a2a_consume_ticket(text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.fluck_peer_request(text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.fluck_peer_pending() TO authenticated;
GRANT EXECUTE ON FUNCTION public.fluck_peer_claim_notice(uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.fluck_peer_respond(uuid, boolean) TO authenticated;
GRANT EXECUTE ON FUNCTION public.fluck_peer_revoke(uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.fluck_peer_links() TO authenticated;
GRANT EXECUTE ON FUNCTION public.fluck_peer_list() TO authenticated;
GRANT EXECUTE ON FUNCTION public.fluck_a2a_mint_ticket(text, uuid, text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.fluck_a2a_consume_ticket(text, text) TO authenticated;
