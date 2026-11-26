-- ============================================================================
-- Email invites: a prepare nobody is waiting for leaves nothing behind.
--
-- org-invite-mail stops waiting for invite_link_mail_prepare after a few seconds. When the database was slow (a queue for
-- a connection, a lock, a brownout) the call still finished afterwards and committed: a new, active, unredeemed link whose
-- token nobody ever saw, counted against the sender's active-link cap (five of them blocked every further invite on
-- 2026-11-26), or, for a resend, a rotated token that killed the link in the earlier mail.
--
-- The edge function now sends the moment it stops caring as a request header (X-Invite-Deadline, epoch milliseconds,
-- which PostgREST exposes in request.headers). Past it the function raises, so the whole call rolls back: no link, no
-- rotation, no mail-log rows. PT504 makes PostgREST answer 504 with message deadline_exceeded.
-- No header (direct callers, an older edge function) means no deadline, as before; the signature is unchanged, so the
-- function and this migration can ship in either order.
-- Builds on 20261116130000_invite_link_resend_serialize.sql.
-- ============================================================================

/** Whether the caller's X-Invite-Deadline (epoch ms) has passed. No header, or one that is not a plain number: false. */
CREATE OR REPLACE FUNCTION public.invite_link_mail_deadline_passed() RETURNS boolean
    LANGUAGE plpgsql VOLATILE SET search_path = '' AS $$
DECLARE
    v_raw text;
BEGIN
    v_raw := NULLIF(pg_catalog.current_setting('request.headers', true), '')::json->>'x-invite-deadline';
    IF v_raw IS NULL OR v_raw !~ '^[0-9]{10,16}$' THEN
        RETURN false;
    END IF;
    RETURN pg_catalog.extract(epoch FROM pg_catalog.clock_timestamp()) * 1000 > v_raw::numeric;
EXCEPTION WHEN OTHERS THEN
    -- Headers that are not JSON: there is no deadline to enforce.
    RETURN false;
END;
$$;

CREATE OR REPLACE FUNCTION public.invite_link_mail_prepare(p_actor uuid, p_target text, p_email text, p_link_id uuid DEFAULT NULL,
                                                           p_via text DEFAULT 'api')
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_actor_email text;
    v_email text := pg_catalog.lower(pg_catalog.btrim(COALESCE(p_email, '')));
    l public.invite_links;
    v_pre jsonb;
    v_info jsonb;
    v_new jsonb;
    v_tok record;
BEGIN
    IF p_actor IS NULL THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'not_allowed');
    END IF;
    IF p_via IS NULL OR p_via NOT IN ('max', 'console', 'api') THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'bad_request');
    END IF;
    SELECT u.email::text INTO v_actor_email FROM auth.users u WHERE u.id = p_actor;
    PERFORM pg_catalog.set_config('agents.audit_actor', p_actor::text, true);

    IF p_link_id IS NOT NULL THEN
        SELECT * INTO l FROM public.invite_links k WHERE k.id = p_link_id FOR UPDATE;
        IF NOT FOUND THEN
            RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'not_found');
        END IF;
        IF l.created_by IS DISTINCT FROM p_actor AND NOT (
               CASE WHEN l.agent_id IS NOT NULL THEN public.user_has_org_permission(p_actor, l.org_id, 'agents.access')
                    ELSE public.is_user_admin(p_actor) END) THEN
            RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'not_allowed');
        END IF;
        IF l.recipient_email IS NULL OR l.revoked_at IS NOT NULL OR l.expires_at <= now() OR l.uses > 0 THEN
            RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'not_pending');
        END IF;
        v_info := public.invite_target_info(l.target);
        IF v_info IS NULL THEN
            RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'not_found');
        END IF;
        IF NOT (v_info->'policy'->>'enabled')::boolean OR v_info->'policy'->>'who' = 'off' THEN
            RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'invites_off');
        END IF;
        -- One resend at a time: a pending one (prev_token_hash set) holds the lease until it reports or goes stale.
        IF l.prev_token_hash IS NOT NULL AND l.last_sent_at > now() - interval '5 minutes' THEN
            RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'resend_in_progress');
        END IF;
        IF NOT public.invite_link_mail_limits_ok(l.target, l.org_id, p_actor, l.recipient_email) THEN
            RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'rate_limited');
        END IF;
        -- A new token, so the link in the earlier mail stops working.
        SELECT * INTO v_tok FROM public.invite_link_new_token();
        UPDATE public.invite_links SET prev_token_hash = token_hash, token_hash = v_tok.token_hash, prev_sent_at = last_sent_at, last_sent_at = now() WHERE id = l.id RETURNING * INTO l;
        PERFORM public.invite_link_log_send(l, p_actor);
        IF l.agent_id IS NOT NULL THEN
            INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
                VALUES (l.org_id, l.agent_id, p_actor, 'invite_link.resend', pg_catalog.jsonb_build_object('link_id', l.id, 'target', l.target));
        ELSE
            INSERT INTO public.platform_audit_log (actor, action, detail)
                VALUES (p_actor, 'invite_link.resend', pg_catalog.jsonb_build_object('link_id', l.id, 'target', l.target));
        END IF;
        -- Past the caller's deadline: undo the rotation, so the earlier mail's link keeps working.
        IF public.invite_link_mail_deadline_passed() THEN
            RAISE EXCEPTION 'deadline_exceeded' USING ERRCODE = 'PT504';
        END IF;
        RETURN public.invite_link_mail_facts(l, v_tok.token, v_info);
    END IF;

    IF v_email = '' OR pg_catalog.length(v_email) > 320 OR v_email !~ '^[^@\s]+@[^@\s]+\.[^@\s]+$' THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'bad_email');
    END IF;
    v_pre := public.invite_link_precheck(p_actor, v_actor_email, p_target);
    IF NOT (v_pre->>'success')::boolean THEN
        RETURN v_pre;
    END IF;
    v_info := v_pre->'info';
    IF NOT public.invite_link_mail_limits_ok(p_target, CASE WHEN v_info->>'kind' = 'agent' THEN (v_info->>'org_id')::uuid END,
                                             p_actor, v_email::extensions.citext) THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'rate_limited');
    END IF;
    v_new := public.invite_link_insert(p_actor, p_target, v_info, 1, NULL, p_via, v_email);
    SELECT * INTO l FROM public.invite_links k WHERE k.id = (v_new->>'id')::uuid;
    PERFORM public.invite_link_log_send(l, p_actor);
    -- Past the caller's deadline: nobody is waiting for this token, so the link must not exist.
    IF public.invite_link_mail_deadline_passed() THEN
        RAISE EXCEPTION 'deadline_exceeded' USING ERRCODE = 'PT504';
    END IF;
    RETURN public.invite_link_mail_facts(l, v_new->>'token', v_info);
END;
$$;

REVOKE ALL ON FUNCTION public.invite_link_mail_deadline_passed() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.invite_link_mail_deadline_passed() TO service_role;
REVOKE ALL ON FUNCTION public.invite_link_mail_prepare(uuid, text, text, uuid, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.invite_link_mail_prepare(uuid, text, text, uuid, text) TO service_role;
