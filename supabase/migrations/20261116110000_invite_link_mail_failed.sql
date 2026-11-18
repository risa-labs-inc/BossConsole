-- ============================================================================
-- Email invites, follow-ups: a failed send leaves nothing behind (invite_link_mail_failed), and the chat can ask whether a
-- link admits an address before sign-in (invite_link_admits). Builds on 20261116100000_invite_link_email.sql.
-- ============================================================================

ALTER TABLE public.invite_links ADD COLUMN IF NOT EXISTS prev_sent_at timestamptz;
ALTER TABLE public.invite_link_mail_log ADD COLUMN IF NOT EXISTS link_id uuid;
ALTER TABLE public.invite_link_mail_log ADD COLUMN IF NOT EXISTS mirror_id bigint;
CREATE INDEX IF NOT EXISTS idx_invite_link_mail_log_link ON public.invite_link_mail_log (link_id, id DESC);
COMMENT ON COLUMN public.invite_links.prev_sent_at IS 'last_sent_at before the latest resend; invite_link_mail_failed restores it.';

/** Records one send in the link mail log (and, for an agent link, its mirror row in the agent mail log). */
CREATE OR REPLACE FUNCTION public.invite_link_log_send(p_link public.invite_links, p_actor uuid) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_mirror bigint;
BEGIN
    IF p_link.agent_id IS NOT NULL THEN
        INSERT INTO public.agent_invite_mail_log (org_id, agent_id, email, actor)
            VALUES (p_link.org_id, p_link.agent_id, p_link.recipient_email, p_actor) RETURNING id INTO v_mirror;
    END IF;
    INSERT INTO public.invite_link_mail_log (target, org_id, agent_id, email, actor, link_id, mirror_id)
        VALUES (p_link.target, p_link.org_id, p_link.agent_id, p_link.recipient_email, p_actor, p_link.id, v_mirror);
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
        IF NOT public.invite_link_mail_limits_ok(l.target, l.org_id, p_actor, l.recipient_email) THEN
            RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'rate_limited');
        END IF;
        -- A new token, so the link in the earlier mail stops working.
        SELECT * INTO v_tok FROM public.invite_link_new_token();
        UPDATE public.invite_links SET token_hash = v_tok.token_hash, prev_sent_at = last_sent_at, last_sent_at = now() WHERE id = l.id RETURNING * INTO l;
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

/**
 * The mail did not go out. A new email invite (nothing redeemed, made moments ago) is deleted; a resend keeps its link (the
 * token already rotated) and gets its earlier last_sent_at back. Either way that send's mail-log rows are removed, so the
 * recipient and org mail limits are not spent on mail that never left. service_role only; audited without token or address.
 */
CREATE OR REPLACE FUNCTION public.invite_link_mail_failed(p_link_id uuid, p_resend boolean) RETURNS jsonb
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
    SELECT * INTO v_log FROM public.invite_link_mail_log g WHERE g.link_id = l.id ORDER BY g.id DESC LIMIT 1;
    IF FOUND THEN
        IF v_log.mirror_id IS NOT NULL THEN
            DELETE FROM public.agent_invite_mail_log WHERE id = v_log.mirror_id;
        END IF;
        DELETE FROM public.invite_link_mail_log WHERE id = v_log.id;
    END IF;
    PERFORM pg_catalog.set_config('agents.audit_actor', COALESCE(v_log.actor, l.created_by)::text, true);
    IF COALESCE(p_resend, false) THEN
        UPDATE public.invite_links SET last_sent_at = prev_sent_at, prev_sent_at = NULL WHERE id = l.id;
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

/**
 * Whether a link admits an address right now (not expired, used up or revoked; invites on; target live; and, when bound,
 * the address is the recipient's). For the chat's sign-in pre-check on invite-only agents. {admits, target}; target only
 * when it admits. Never says who a bound link is for.
 */
CREATE OR REPLACE FUNCTION public.invite_link_admits(p_token text, p_email text) RETURNS jsonb
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    l public.invite_links;
    v_pv jsonb;
    v_email text := pg_catalog.lower(pg_catalog.btrim(COALESCE(p_email, '')));
BEGIN
    SELECT * INTO l FROM public.invite_links k
        WHERE k.token_hash = pg_catalog.encode(extensions.digest(pg_catalog.btrim(COALESCE(p_token, '')), 'sha256'), 'hex');
    IF NOT FOUND THEN
        RETURN pg_catalog.jsonb_build_object('admits', false, 'target', NULL);
    END IF;
    v_pv := public.invite_link_preview(p_token);
    IF NOT (v_pv->>'valid')::boolean
       OR (l.recipient_email IS NOT NULL AND (v_email = '' OR pg_catalog.lower(l.recipient_email::text) <> v_email)) THEN
        RETURN pg_catalog.jsonb_build_object('admits', false, 'target', NULL);
    END IF;
    RETURN pg_catalog.jsonb_build_object('admits', true, 'target', l.target);
END;
$$;

REVOKE ALL ON FUNCTION public.invite_link_log_send(public.invite_links, uuid), public.invite_link_mail_prepare(uuid, text, text, uuid, text),
    public.invite_link_mail_failed(uuid, boolean), public.invite_link_admits(text, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.invite_link_mail_prepare(uuid, text, text, uuid, text), public.invite_link_mail_failed(uuid, boolean) TO service_role;
GRANT EXECUTE ON FUNCTION public.invite_link_admits(text, text) TO anon, authenticated, service_role;
