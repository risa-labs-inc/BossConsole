-- ============================================================================
-- Email invites: an invite link for one person, emailed by BossConsole (org-invite-mail POST /link).
-- Single use, bound to the recipient address: redeeming from another address is wrong_email (no use consumed).
-- invite_link_mail_prepare is service_role only (the edge function calls it with the caller's id from their JWT); it is the
-- only place a token is handed out for mailing. Builds on 20261111150000_invite_links.sql.
--
-- Mail limits are the ones of the other invite mails: per org 30/hour; per recipient 3/hour and 10/day across org, agent
-- and link mails. Agent-link sends are mirrored into agent_invite_mail_log so those functions' limits see them; platform
-- sends (no org) are limited per sender, 30/hour.
-- ============================================================================

ALTER TABLE public.invite_links ADD COLUMN IF NOT EXISTS recipient_email extensions.citext;
ALTER TABLE public.invite_links ADD COLUMN IF NOT EXISTS last_sent_at timestamptz;
ALTER TABLE public.invite_links DROP CONSTRAINT IF EXISTS invite_links_email_single_use;
ALTER TABLE public.invite_links ADD CONSTRAINT invite_links_email_single_use CHECK (recipient_email IS NULL OR max_uses = 1);
GRANT SELECT (recipient_email, last_sent_at) ON public.invite_links TO authenticated;

CREATE TABLE IF NOT EXISTS public.invite_link_mail_log (
    id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    target   text NOT NULL,
    org_id   uuid,
    agent_id uuid,
    email    extensions.citext NOT NULL,
    actor    uuid NOT NULL,
    sent_at  timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS idx_invite_link_mail_log_email ON public.invite_link_mail_log (email, sent_at DESC);
CREATE INDEX IF NOT EXISTS idx_invite_link_mail_log_actor ON public.invite_link_mail_log (actor, target, sent_at DESC);
ALTER TABLE public.invite_link_mail_log ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.invite_link_mail_log FROM PUBLIC, anon, authenticated, service_role;
COMMENT ON TABLE public.invite_link_mail_log IS 'One row per invite-link mail sent (invite_link_mail_prepare). Rate limits only.';

-- The audit trail names the actor for service-role writes too (agents.audit_actor, set by invite_link_mail_prepare).
CREATE OR REPLACE FUNCTION public.invite_links_audit() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    l public.invite_links;
    act text;
    det jsonb;
BEGIN
    IF TG_TABLE_NAME = 'invite_links' THEN
        l := NEW;
        IF TG_OP = 'INSERT' THEN
            act := 'invite_link.create';
            det := pg_catalog.jsonb_build_object('link_id', l.id, 'target', l.target, 'max_uses', l.max_uses,
                                                 'expires_at', l.expires_at, 'via', l.via);
        ELSIF OLD.revoked_at IS NULL AND NEW.revoked_at IS NOT NULL THEN
            act := 'invite_link.revoke';
            det := pg_catalog.jsonb_build_object('link_id', l.id, 'target', l.target, 'created_by', l.created_by);
        ELSE
            RETURN NULL;
        END IF;
    ELSE
        SELECT * INTO l FROM public.invite_links k WHERE k.id = NEW.link_id;
        act := 'invite_link.redeem';
        det := pg_catalog.jsonb_build_object('link_id', l.id, 'target', l.target, 'redeemer', NEW.user_id,
                                             'inviter', l.created_by);
    END IF;
    IF l.agent_id IS NOT NULL THEN
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
            VALUES (l.org_id, l.agent_id, public.agent_audit_actor(), act, det);
    ELSE
        INSERT INTO public.platform_audit_log (actor, action, detail) VALUES (public.agent_audit_actor(), act, det);
    END IF;
    RETURN NULL;
END;
$$;

-- ----------------------------------------------------------------------------
-- Shared pre-checks and insert, used by create_invite_link and invite_link_mail_prepare.
-- ----------------------------------------------------------------------------
/** Policy, audience, active-link cap and quotas for p_uid on p_target. {success:true, info} or {success:false, error}. Takes the per-creator lock. */
CREATE OR REPLACE FUNCTION public.invite_link_precheck(p_uid uuid, p_email text, p_target text) RETURNS jsonb
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_info jsonb;
    v_pol jsonb;
BEGIN
    IF p_uid IS NULL OR p_target IS NULL THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'not_allowed');
    END IF;
    v_info := public.invite_target_info(p_target);
    IF v_info IS NULL THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'not_found');
    END IF;
    v_info := v_info || CASE WHEN p_target IN ('optimist', 'max') THEN pg_catalog.jsonb_build_object('platform', p_target) ELSE '{}'::jsonb END;
    v_pol := v_info->'policy';
    IF NOT (v_pol->>'enabled')::boolean OR v_pol->>'who' = 'off' THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'invites_off');
    END IF;
    IF NOT public.invite_audience_ok(p_uid, p_email, v_pol->>'who', v_info) THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'not_allowed');
    END IF;
    -- One creator at a time per target, so the active-link cap holds under concurrency.
    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext('invite_create:' || p_uid::text || ':' || p_target));
    IF (SELECT count(*) FROM public.invite_links l
        WHERE l.created_by = p_uid AND l.target = p_target AND l.revoked_at IS NULL AND l.expires_at > now()
          AND l.uses < l.max_uses) >= (v_pol->>'active_links_per_user')::int THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'too_many_active_links');
    END IF;
    IF (SELECT count(*) FROM public.invite_link_redemptions r
        WHERE r.inviter_id = p_uid AND r.target = p_target AND r.redeemed_at > now() - interval '30 days')
           >= (v_pol->>'per_user_per_month')::int
       OR (SELECT count(*) FROM public.invite_link_redemptions r
           WHERE r.target = p_target AND r.redeemed_at > now() - interval '30 days') >= (v_pol->>'org_per_month')::int THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'quota_exceeded');
    END IF;
    RETURN pg_catalog.jsonb_build_object('success', true, 'info', v_info);
END;
$$;

/** New 43-char base64url token and its sha256 hex. */
CREATE OR REPLACE FUNCTION public.invite_link_new_token(OUT token text, OUT token_hash text) RETURNS record
    LANGUAGE sql SECURITY DEFINER SET search_path = '' AS $$
    SELECT t, pg_catalog.encode(extensions.digest(t, 'sha256'), 'hex')
    FROM (SELECT pg_catalog.translate(pg_catalog.encode(extensions.gen_random_bytes(32), 'base64'), '+/=', '-_') AS t) s;
$$;

/** Inserts the link (caps clamped to the policy in p_info) and returns {id, token, expires_at, max_uses}. */
CREATE OR REPLACE FUNCTION public.invite_link_insert(p_uid uuid, p_target text, p_info jsonb, p_max_uses int, p_expires_days int,
                                                     p_via text, p_recipient text) RETURNS jsonb
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_pol jsonb := p_info->'policy';
    v_max int := CASE WHEN p_recipient IS NOT NULL THEN 1
                      ELSE LEAST(COALESCE(p_max_uses, (v_pol->>'max_uses')::int), (v_pol->>'max_uses')::int) END;
    v_days int := LEAST(COALESCE(p_expires_days, (v_pol->>'expiry_days')::int), (v_pol->>'expiry_days')::int);
    v_exp timestamptz := now() + pg_catalog.make_interval(days => v_days);
    v_id uuid := gen_random_uuid();
    v_tok record;
BEGIN
    SELECT * INTO v_tok FROM public.invite_link_new_token();
    INSERT INTO public.invite_links (id, target, agent_id, org_id, created_by, token_hash, max_uses, expires_at, via, recipient_email, last_sent_at)
        VALUES (v_id, p_target, NULLIF(p_info->>'agent_id', '')::uuid,
                CASE WHEN p_info->>'kind' = 'agent' THEN NULLIF(p_info->>'org_id', '')::uuid END, p_uid,
                v_tok.token_hash, v_max, v_exp, p_via, p_recipient::extensions.citext,
                CASE WHEN p_recipient IS NOT NULL THEN now() END);
    RETURN pg_catalog.jsonb_build_object('id', v_id, 'token', v_tok.token, 'expires_at', v_exp, 'max_uses', v_max);
END;
$$;

CREATE OR REPLACE FUNCTION public.create_invite_link(p_target text, p_max_uses int DEFAULT NULL,
                                                      p_expires_days int DEFAULT NULL, p_via text DEFAULT 'max')
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_uid uuid := auth.uid();
    v_pre jsonb;
    v_new jsonb;
BEGIN
    IF v_uid IS NULL THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'not_allowed');
    END IF;
    IF p_via IS NULL OR p_via NOT IN ('max', 'console', 'api') OR p_target IS NULL
       OR (p_max_uses IS NOT NULL AND p_max_uses < 1) OR (p_expires_days IS NOT NULL AND p_expires_days < 1) THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'bad_request');
    END IF;
    v_pre := public.invite_link_precheck(v_uid, auth.email(), p_target);
    IF NOT (v_pre->>'success')::boolean THEN
        RETURN v_pre;
    END IF;
    v_new := public.invite_link_insert(v_uid, p_target, v_pre->'info', p_max_uses, p_expires_days, p_via, NULL);
    RETURN pg_catalog.jsonb_build_object('success', true, 'id', v_new->'id', 'token', v_new->>'token',
        'path', '/i/' || (v_new->>'token'), 'target', p_target, 'max_uses', v_new->'max_uses', 'expires_at', v_new->'expires_at');
END;
$$;

-- ----------------------------------------------------------------------------
-- Mail limits (see header). Caller holds the lock taken here until commit.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.invite_link_mail_limits_ok(p_target text, p_org uuid, p_actor uuid, p_email extensions.citext)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_hour int;
    v_day int;
BEGIN
    IF p_org IS NOT NULL THEN
        PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext('agent_invite_mail:' || p_org::text));
        IF (SELECT count(*) FROM public.agent_invite_mail_log l WHERE l.org_id = p_org AND l.sent_at > now() - interval '1 hour') >= 30 THEN
            RETURN false;
        END IF;
    ELSE
        PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext('invite_link_mail:' || p_actor::text));
        IF (SELECT count(*) FROM public.invite_link_mail_log l
            WHERE l.actor = p_actor AND l.target = p_target AND l.sent_at > now() - interval '1 hour') >= 30 THEN
            RETURN false;
        END IF;
    END IF;
    -- Agent-link sends are mirrored into agent_invite_mail_log, so only platform sends are counted from the link log.
    SELECT (SELECT count(*) FROM public.agent_invite_mail_log l WHERE l.email = p_email AND l.sent_at > now() - interval '1 hour')
         + (SELECT count(*) FROM public.org_invite_mail_log l WHERE l.email = p_email AND l.sent_at > now() - interval '1 hour')
         + (SELECT count(*) FROM public.invite_link_mail_log l WHERE l.email = p_email AND l.agent_id IS NULL AND l.sent_at > now() - interval '1 hour'),
           (SELECT count(*) FROM public.agent_invite_mail_log l WHERE l.email = p_email AND l.sent_at > now() - interval '1 day')
         + (SELECT count(*) FROM public.org_invite_mail_log l WHERE l.email = p_email AND l.sent_at > now() - interval '1 day')
         + (SELECT count(*) FROM public.invite_link_mail_log l WHERE l.email = p_email AND l.agent_id IS NULL AND l.sent_at > now() - interval '1 day')
      INTO v_hour, v_day;
    RETURN v_hour < 3 AND v_day < 10;
END;
$$;

/** What the mail needs, for a link row; p_token is the one just issued. */
CREATE OR REPLACE FUNCTION public.invite_link_mail_facts(p_link public.invite_links, p_token text, p_info jsonb) RETURNS jsonb
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_slug text;
    v_host text;
    v_org text;
    v_iname text;
    v_iemail text;
BEGIN
    IF p_link.agent_id IS NOT NULL THEN
        SELECT a.slug INTO v_slug FROM public.agents a WHERE a.id = p_link.agent_id;
        SELECT d.hostname::text INTO v_host FROM public.agent_domains d
            WHERE d.agent_id = p_link.agent_id AND d.status = 'active' ORDER BY d.verified_at NULLS LAST, d.created_at LIMIT 1;
        SELECT o.name INTO v_org FROM public.organisations o WHERE o.id = p_link.org_id;
    END IF;
    SELECT u.email::text, COALESCE(NULLIF(pg_catalog.btrim(u.raw_user_meta_data->>'full_name'), ''),
                                   NULLIF(pg_catalog.btrim(u.raw_user_meta_data->>'name'), ''))
      INTO v_iemail, v_iname FROM auth.users u WHERE u.id = p_link.created_by;
    RETURN pg_catalog.jsonb_build_object('success', true, 'link_id', p_link.id, 'token', p_token, 'target', p_link.target,
        'target_name', p_info->>'name', 'agent_slug', v_slug, 'custom_host', v_host, 'org_name', v_org,
        'inviter_name', v_iname, 'inviter_email', v_iemail, 'recipient_email', p_link.recipient_email::text,
        'expires_at', p_link.expires_at);
END;
$$;

-- ----------------------------------------------------------------------------
-- invite_link_mail_prepare: new email invite, or resend (p_link_id) with token rotation. service_role only.
-- ----------------------------------------------------------------------------
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
        UPDATE public.invite_links SET token_hash = v_tok.token_hash, last_sent_at = now() WHERE id = l.id RETURNING * INTO l;
        INSERT INTO public.invite_link_mail_log (target, org_id, agent_id, email, actor) VALUES (l.target, l.org_id, l.agent_id, l.recipient_email, p_actor);
        IF l.agent_id IS NOT NULL THEN
            INSERT INTO public.agent_invite_mail_log (org_id, agent_id, email, actor) VALUES (l.org_id, l.agent_id, l.recipient_email, p_actor);
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
    INSERT INTO public.invite_link_mail_log (target, org_id, agent_id, email, actor) VALUES (l.target, l.org_id, l.agent_id, l.recipient_email, p_actor);
    IF l.agent_id IS NOT NULL THEN
        INSERT INTO public.agent_invite_mail_log (org_id, agent_id, email, actor) VALUES (l.org_id, l.agent_id, l.recipient_email, p_actor);
    END IF;
    RETURN public.invite_link_mail_facts(l, v_new->>'token', v_info);
END;
$$;

-- ----------------------------------------------------------------------------
-- Redeem, preview and lists, with the recipient fields.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.redeem_invite_link(p_token text) RETURNS jsonb
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_uid uuid := auth.uid();
    v_email text := pg_catalog.lower(pg_catalog.btrim(COALESCE(auth.email(), '')));
    l public.invite_links;
    v_info jsonb;
    v_pol jsonb;
BEGIN
    IF v_uid IS NULL OR v_email = '' THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'not_found');
    END IF;
    -- The link row lock serialises redeems of one link, so uses never pass max_uses.
    SELECT * INTO l FROM public.invite_links k
        WHERE k.token_hash = pg_catalog.encode(extensions.digest(pg_catalog.btrim(COALESCE(p_token, '')), 'sha256'), 'hex')
        FOR UPDATE;
    IF NOT FOUND THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'not_found');
    END IF;
    IF l.created_by = v_uid OR EXISTS (SELECT 1 FROM public.invite_link_redemptions r WHERE r.link_id = l.id AND r.user_id = v_uid) THEN
        RETURN pg_catalog.jsonb_build_object('success', true, 'target', l.target, 'already', true);
    END IF;
    IF l.recipient_email IS NOT NULL AND l.recipient_email::text <> v_email THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'wrong_email');
    END IF;
    v_info := public.invite_target_info(l.target);
    IF v_info IS NULL THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'not_found');
    END IF;
    v_pol := v_info->'policy';
    IF l.revoked_at IS NOT NULL THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'revoked');
    ELSIF l.expires_at <= now() THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'expired');
    ELSIF l.uses >= l.max_uses THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'used_up');
    ELSIF NOT (v_pol->>'enabled')::boolean OR v_pol->>'who' = 'off' THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'invites_off');
    END IF;

    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext('invite_redeem:' || l.target));
    IF (SELECT count(*) FROM public.invite_link_redemptions r
        WHERE r.inviter_id = l.created_by AND r.target = l.target AND r.redeemed_at > now() - interval '30 days')
           >= (v_pol->>'per_user_per_month')::int
       OR (SELECT count(*) FROM public.invite_link_redemptions r
           WHERE r.target = l.target AND r.redeemed_at > now() - interval '30 days') >= (v_pol->>'org_per_month')::int THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'quota_exceeded');
    END IF;

    IF l.agent_id IS NOT NULL THEN
        INSERT INTO public.agent_invites (agent_id, email, invited_by, exception)
            VALUES (l.agent_id, v_email, l.created_by, true) ON CONFLICT (agent_id, email) DO NOTHING;
    ELSE
        INSERT INTO public.platform_access (email, platform, link_id, invited_by)
            VALUES (v_email, l.target, l.id, l.created_by) ON CONFLICT (email, platform) DO NOTHING;
    END IF;
    INSERT INTO public.invite_link_redemptions (link_id, user_id, inviter_id, target) VALUES (l.id, v_uid, l.created_by, l.target);
    UPDATE public.invite_links SET uses = uses + 1 WHERE id = l.id;
    RETURN pg_catalog.jsonb_build_object('success', true, 'target', l.target, 'already', false);
END;
$$;

CREATE OR REPLACE FUNCTION public.invite_link_preview(p_token text) RETURNS jsonb
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    l public.invite_links;
    v_info jsonb;
    v_reason text;
    v_inviter text;
BEGIN
    SELECT * INTO l FROM public.invite_links k
        WHERE k.token_hash = pg_catalog.encode(extensions.digest(pg_catalog.btrim(COALESCE(p_token, '')), 'sha256'), 'hex');
    IF NOT FOUND THEN
        RETURN pg_catalog.jsonb_build_object('valid', false, 'reason', 'not_found', 'target', NULL, 'target_name', NULL,
                                             'inviter_name', NULL, 'recipient_hint', NULL);
    END IF;
    v_info := public.invite_target_info(l.target);
    v_reason := CASE WHEN v_info IS NULL THEN 'not_found'
                     WHEN l.revoked_at IS NOT NULL THEN 'revoked'
                     WHEN l.expires_at <= now() THEN 'expired'
                     WHEN l.uses >= l.max_uses THEN 'used_up'
                     WHEN NOT (v_info->'policy'->>'enabled')::boolean OR v_info->'policy'->>'who' = 'off' THEN 'invites_off' END;
    SELECT NULLIF(pg_catalog.split_part(pg_catalog.btrim(COALESCE(u.raw_user_meta_data->>'full_name', u.raw_user_meta_data->>'name', '')), ' ', 1), '')
      INTO v_inviter FROM auth.users u WHERE u.id = l.created_by;
    IF v_info IS NULL THEN
        RETURN pg_catalog.jsonb_build_object('valid', false, 'reason', 'not_found', 'target', NULL, 'target_name', NULL,
                                             'inviter_name', NULL, 'recipient_hint', NULL);
    END IF;
    RETURN pg_catalog.jsonb_build_object('valid', v_reason IS NULL, 'reason', v_reason, 'target', l.target,
        'target_name', v_info->>'name', 'inviter_name', v_inviter,
        'recipient_hint', CASE WHEN l.recipient_email IS NULL THEN NULL ELSE
            pg_catalog.left(l.recipient_email::text, 1) || '***@' || pg_catalog.split_part(l.recipient_email::text, '@', 2) END);
END;
$$;

CREATE OR REPLACE FUNCTION public.list_my_invite_links(p_target text DEFAULT NULL) RETURNS jsonb
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT COALESCE(pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
        'id', l.id, 'target', l.target, 'max_uses', l.max_uses, 'uses', l.uses, 'expires_at', l.expires_at,
        'revoked_at', l.revoked_at, 'created_at', l.created_at,
        'recipient_email', l.recipient_email::text, 'last_sent_at', l.last_sent_at,
        'status', CASE WHEN l.revoked_at IS NOT NULL THEN 'revoked' WHEN l.expires_at <= now() THEN 'expired'
                       WHEN l.uses >= l.max_uses THEN 'used_up' ELSE 'active' END) ORDER BY l.created_at DESC), '[]'::jsonb)
    FROM public.invite_links l
    WHERE l.created_by = auth.uid() AND auth.uid() IS NOT NULL AND (p_target IS NULL OR l.target = p_target);
$$;

CREATE OR REPLACE FUNCTION public.list_invite_links(p_target text) RETURNS jsonb
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_uid uuid := auth.uid();
    v_info jsonb := CASE WHEN p_target IS NULL THEN NULL ELSE public.invite_target_info(p_target) END;
BEGIN
    IF v_uid IS NULL OR v_info IS NULL OR NOT public.invite_audience_ok(v_uid, auth.email(), 'admins', v_info) THEN
        RAISE EXCEPTION 'not_allowed' USING ERRCODE = '42501';
    END IF;
    RETURN COALESCE((SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
        'id', l.id, 'target', l.target, 'max_uses', l.max_uses, 'uses', l.uses, 'expires_at', l.expires_at,
        'revoked_at', l.revoked_at, 'created_at', l.created_at, 'via', l.via, 'created_by', l.created_by,
        'creator_email', u.email::text, 'recipient_email', l.recipient_email::text, 'last_sent_at', l.last_sent_at,
        'status', CASE WHEN l.revoked_at IS NOT NULL THEN 'revoked' WHEN l.expires_at <= now() THEN 'expired'
                       WHEN l.uses >= l.max_uses THEN 'used_up' ELSE 'active' END) ORDER BY l.created_at DESC, l.id)
        FROM public.invite_links l LEFT JOIN auth.users u ON u.id = l.created_by
        WHERE l.target = p_target), '[]'::jsonb);
END;
$$;

REVOKE ALL ON FUNCTION public.invite_link_precheck(uuid, text, text), public.invite_link_new_token(),
    public.invite_link_insert(uuid, text, jsonb, int, int, text, text), public.invite_link_mail_limits_ok(text, uuid, uuid, extensions.citext),
    public.invite_link_mail_facts(public.invite_links, text, jsonb), public.invite_link_mail_prepare(uuid, text, text, uuid, text)
    FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.invite_link_mail_prepare(uuid, text, text, uuid, text) TO service_role;
-- The functions replaced above get their grants again (the platform strips anon from replaced functions).
REVOKE ALL ON FUNCTION public.create_invite_link(text, int, int, text), public.invite_link_preview(text),
    public.redeem_invite_link(text), public.list_my_invite_links(text), public.list_invite_links(text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.create_invite_link(text, int, int, text), public.redeem_invite_link(text),
    public.list_my_invite_links(text), public.list_invite_links(text) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.invite_link_preview(text) TO anon, authenticated, service_role;
