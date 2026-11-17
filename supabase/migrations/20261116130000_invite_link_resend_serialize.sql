-- ============================================================================
-- Overlapping resends of one email invite no longer strand the invitee: a resend is refused (resend_in_progress) while the
-- previous one is pending (prev_token_hash set and last_sent_at within 5 minutes: above the edge function's worst-case SMTP
-- time, 15 s per step over ~13 steps). A pending one older than that (edge crashed) is taken over. mail_failed / mail_sent
-- now carry the token the edge function issued and act only while it is still the link's current token.
-- Builds on 20261116120000_invite_link_resend_restore_token.sql.
-- ============================================================================

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
    RETURN public.invite_link_mail_facts(l, v_new->>'token', v_info);
END;
$$;

DROP FUNCTION IF EXISTS public.invite_link_mail_failed(uuid, boolean);
DROP FUNCTION IF EXISTS public.invite_link_mail_sent(uuid);

CREATE OR REPLACE FUNCTION public.invite_link_mail_failed(p_link_id uuid, p_resend boolean, p_token text) RETURNS jsonb
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    l public.invite_links;
    v_log public.invite_link_mail_log;
    v_action text;
BEGIN
    SELECT * INTO l FROM public.invite_links k WHERE k.id = p_link_id FOR UPDATE;
    IF NOT FOUND THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'not_found');
    END IF;
    IF l.recipient_email IS NULL OR l.uses > 0 OR (NOT COALESCE(p_resend, false) AND l.created_at < now() - interval '10 minutes') THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'not_pending');
    END IF;
    -- Only the send that issued the current token may undo it; a late report from a taken-over send does nothing.
    IF l.token_hash IS DISTINCT FROM pg_catalog.encode(extensions.digest(pg_catalog.btrim(COALESCE(p_token, '')), 'sha256'), 'hex')
       OR (COALESCE(p_resend, false) AND l.prev_token_hash IS NULL) THEN
        RETURN pg_catalog.jsonb_build_object('success', true, 'result', 'stale');
    END IF;
    SELECT * INTO v_log FROM public.invite_link_mail_log g WHERE g.link_id = l.id ORDER BY g.id DESC LIMIT 1;
    IF FOUND THEN
        IF v_log.mirror_id IS NOT NULL THEN
            DELETE FROM public.agent_invite_mail_log WHERE id = v_log.mirror_id;
        END IF;
        DELETE FROM public.invite_link_mail_log WHERE id = v_log.id;
    END IF;
    PERFORM pg_catalog.set_config('agents.audit_actor', COALESCE(v_log.actor, l.created_by)::text, true);
    IF COALESCE(p_resend, false) THEN
        UPDATE public.invite_links SET last_sent_at = prev_sent_at, prev_sent_at = NULL,
            token_hash = prev_token_hash, prev_token_hash = NULL WHERE id = l.id;
        v_action := 'restored';
    ELSE
        DELETE FROM public.invite_link_mail_log WHERE link_id = l.id;
        DELETE FROM public.invite_links WHERE id = l.id;
        v_action := 'deleted';
    END IF;
    IF l.agent_id IS NOT NULL THEN
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
            VALUES (l.org_id, l.agent_id, public.agent_audit_actor(), 'invite_link.mail_failed',
                    pg_catalog.jsonb_build_object('link_id', l.id, 'target', l.target, 'resend', COALESCE(p_resend, false), 'result', v_action));
    ELSE
        INSERT INTO public.platform_audit_log (actor, action, detail)
            VALUES (public.agent_audit_actor(), 'invite_link.mail_failed',
                    pg_catalog.jsonb_build_object('link_id', l.id, 'target', l.target, 'resend', COALESCE(p_resend, false), 'result', v_action));
    END IF;
    RETURN pg_catalog.jsonb_build_object('success', true, 'result', v_action);
END;
$$;

/** The mail went out: forget the saved previous token and send time, if `p_token` is still the current one. service_role only. */
CREATE OR REPLACE FUNCTION public.invite_link_mail_sent(p_link_id uuid, p_token text) RETURNS void
    LANGUAGE sql SECURITY DEFINER SET search_path = '' AS $$
    UPDATE public.invite_links SET prev_token_hash = NULL, prev_sent_at = NULL
     WHERE id = p_link_id
       AND token_hash = pg_catalog.encode(extensions.digest(pg_catalog.btrim(COALESCE(p_token, '')), 'sha256'), 'hex');
$$;

REVOKE ALL ON FUNCTION public.invite_link_mail_failed(uuid, boolean, text), public.invite_link_mail_sent(uuid, text)
    FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.invite_link_mail_failed(uuid, boolean, text), public.invite_link_mail_sent(uuid, text) TO service_role;
