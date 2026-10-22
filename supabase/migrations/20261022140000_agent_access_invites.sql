-- ============================================================================
-- Agents platform: who may join an agent, one rule for the console and the database, and invite mail.
-- Builds on 20261016130000, 20261019120000, 20261019140000 and 20261019150000.
--
-- The rule (agent_can_join, which chat asks at every sign-in):
--   open   ("Anyone")       anyone with a confirmed email.
--   domain ("Email domain") people at one of the domains, plus people invited outside them. An outside invite is an
--                           explicit exception: inserting one needs exception = true (the console asks first), else
--                           invite_outside_domain.
--   invite ("Invite only")  only invited people.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Invites: the exception mark and the last invite mail.
-- ----------------------------------------------------------------------------
ALTER TABLE public.agent_invites ADD COLUMN IF NOT EXISTS exception boolean NOT NULL DEFAULT false;
ALTER TABLE public.agent_invites ADD COLUMN IF NOT EXISTS last_sent_at timestamptz;
COMMENT ON COLUMN public.agent_invites.exception IS
    'Invited outside the agent''s sign-up domains, confirmed by whoever invited (domain mode only).';
COMMENT ON COLUMN public.agent_invites.last_sent_at IS
    'When the invite email last went out (agent_invite_mail_prepare); null: never.';

-- People write the address, the agent, who invited and the exception mark; the mail time is the mailer's.
REVOKE INSERT, UPDATE ON public.agent_invites FROM authenticated;
GRANT INSERT (agent_id, email, invited_by, exception) ON public.agent_invites TO authenticated;

/** The domain part of an address, lowercased; '' when it is not one. */
CREATE OR REPLACE FUNCTION public.agent_email_domain(p_email text) RETURNS text
    LANGUAGE sql IMMUTABLE SET search_path = '' AS $$
    SELECT CASE WHEN pg_catalog.lower(pg_catalog.btrim(COALESCE(p_email, ''))) ~ '^[^@\s]+@[^@\s]+$'
                THEN pg_catalog.split_part(pg_catalog.lower(pg_catalog.btrim(p_email)), '@', 2) ELSE '' END;
$$;

/** Whether an address is at one of the domains of a domain-mode sign-up policy. */
CREATE OR REPLACE FUNCTION public.agent_policy_domain_match(p_policy jsonb, p_email text) RETURNS boolean
    LANGUAGE sql IMMUTABLE SET search_path = '' AS $$
    SELECT public.agent_email_domain(p_email) <> '' AND EXISTS (
        SELECT 1 FROM pg_catalog.jsonb_array_elements_text(
            CASE WHEN pg_catalog.jsonb_typeof(p_policy->'domains') = 'array' THEN p_policy->'domains' ELSE '[]'::jsonb END) d
        WHERE pg_catalog.lower(pg_catalog.btrim(d, ' @')) = public.agent_email_domain(p_email));
$$;
REVOKE ALL ON FUNCTION public.agent_email_domain(text), public.agent_policy_domain_match(jsonb, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.agent_email_domain(text), public.agent_policy_domain_match(jsonb, text) TO authenticated, service_role;

/** An invite is a lowercased address; outside the domains of a domain-mode agent it must be marked an exception. */
CREATE OR REPLACE FUNCTION public.agent_invites_guard() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_policy jsonb;
BEGIN
    NEW.email := pg_catalog.lower(pg_catalog.btrim(NEW.email::text));
    IF NEW.email::text !~ '^[^@\s]+@[^@\s]+\.[^@\s]+$' THEN
        RAISE EXCEPTION 'invite_bad_email' USING ERRCODE = 'P0001';
    END IF;
    SELECT a.signup_policy INTO v_policy FROM public.agents a WHERE a.id = NEW.agent_id;
    IF v_policy->>'mode' = 'domain' THEN
        IF public.agent_policy_domain_match(v_policy, NEW.email::text) THEN
            NEW.exception := false;
        ELSIF NOT NEW.exception THEN
            RAISE EXCEPTION 'invite_outside_domain' USING ERRCODE = 'P0001',
                HINT = 'The address is not at the agent''s sign-up domains: invite it as an exception (exception = true).';
        END IF;
    ELSE
        NEW.exception := false;
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.agent_invites_guard() FROM PUBLIC, anon, authenticated;
DROP TRIGGER IF EXISTS agent_invites_guard_on_insert ON public.agent_invites;
CREATE TRIGGER agent_invites_guard_on_insert BEFORE INSERT ON public.agent_invites
    FOR EACH ROW EXECUTE FUNCTION public.agent_invites_guard();

-- Invites made before this rule, outside a domain-mode agent's domains: someone invited them, so they are exceptions.
UPDATE public.agent_invites i SET exception = true
  FROM public.agents a
 WHERE a.id = i.agent_id AND a.signup_policy->>'mode' = 'domain'
   AND NOT public.agent_policy_domain_match(a.signup_policy, i.email::text) AND NOT i.exception;

-- ----------------------------------------------------------------------------
-- 2. agent_can_join: domain mode also admits invited people (the exceptions). Same caller rule as before: a signed-in
--    caller may ask about their own email only.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.agent_can_join(p_slug text, p_email text)
RETURNS boolean LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    a public.agents%ROWTYPE;
    norm text := pg_catalog.lower(pg_catalog.btrim(COALESCE(p_email, '')));
    mode text;
    invited boolean;
BEGIN
    IF norm !~ '^[^@\s]+@[^@\s]+$' THEN
        RETURN false;
    END IF;
    IF auth.uid() IS NOT NULL AND norm <> pg_catalog.lower(pg_catalog.btrim(COALESCE(auth.email(), ''))) THEN
        RETURN false;
    END IF;
    SELECT * INTO a FROM public.agents WHERE slug = p_slug AND status = 'live';
    IF NOT FOUND THEN
        RETURN false;
    END IF;
    mode := a.signup_policy->>'mode';
    IF mode = 'open' THEN
        RETURN true;
    END IF;
    invited := EXISTS (SELECT 1 FROM public.agent_invites i WHERE i.agent_id = a.id AND pg_catalog.lower(i.email::text) = norm);
    IF mode = 'domain' THEN
        RETURN invited OR public.agent_policy_domain_match(a.signup_policy, norm);
    ELSIF mode = 'invite' THEN
        RETURN invited;
    END IF;
    RETURN false;
END;
$$;
REVOKE ALL ON FUNCTION public.agent_can_join(text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.agent_can_join(text, text) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- 3. Invite mail (org-invite-mail's /agent route). The function calls this as the service role with the caller's id from
--    their JWT; it re-checks agents.access on the agent's org, that the invite exists and the agent is live, applies the
--    limits and records the send. Limits: per org 30/hour; per recipient 3/hour and 10/day (org and agent invites alike).
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.agent_invite_mail_log (
    id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_id   uuid NOT NULL,
    agent_id uuid NOT NULL,
    email    extensions.citext NOT NULL,
    actor    uuid NOT NULL,
    sent_at  timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS idx_agent_invite_mail_log_org ON public.agent_invite_mail_log (org_id, sent_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_invite_mail_log_email ON public.agent_invite_mail_log (email, sent_at DESC);
ALTER TABLE public.agent_invite_mail_log ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.agent_invite_mail_log FROM PUBLIC, anon, authenticated, service_role;
COMMENT ON TABLE public.agent_invite_mail_log IS
    'One row per agent invite mail sent (org-invite-mail /agent). Rate limits only; written by agent_invite_mail_prepare.';

CREATE OR REPLACE FUNCTION public.agent_invite_mail_prepare(p_actor uuid, p_agent_id uuid, p_email text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_agent public.agents;
    v_org public.organisations;
    v_email text := pg_catalog.lower(pg_catalog.btrim(COALESCE(p_email, '')));
    v_inv public.agent_invites;
    v_host text;
    v_from_email text;
    v_from_name text;
    v_recipient_hour int;
    v_recipient_day int;
BEGIN
    IF p_actor IS NULL OR p_agent_id IS NULL OR NOT public.user_has_agent_permission(p_actor, p_agent_id, 'agents.access') THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    SELECT * INTO v_agent FROM public.agents a WHERE a.id = p_agent_id;
    SELECT * INTO v_inv FROM public.agent_invites i WHERE i.agent_id = p_agent_id AND i.email = v_email::extensions.citext FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'invite_not_found' USING ERRCODE = 'P0001';
    END IF;
    IF v_agent.status <> 'live' THEN
        RAISE EXCEPTION 'agent_not_live' USING ERRCODE = 'P0001';
    END IF;

    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext('agent_invite_mail:' || v_agent.org_id::text));
    IF (SELECT count(*) FROM public.agent_invite_mail_log l
        WHERE l.org_id = v_agent.org_id AND l.sent_at > now() - interval '1 hour') >= 30 THEN
        RAISE EXCEPTION 'rate_limited_org' USING ERRCODE = 'P0001';
    END IF;
    SELECT (SELECT count(*) FROM public.agent_invite_mail_log l WHERE l.email = v_inv.email AND l.sent_at > now() - interval '1 hour')
         + (SELECT count(*) FROM public.org_invite_mail_log l WHERE l.email = v_inv.email AND l.sent_at > now() - interval '1 hour'),
           (SELECT count(*) FROM public.agent_invite_mail_log l WHERE l.email = v_inv.email AND l.sent_at > now() - interval '1 day')
         + (SELECT count(*) FROM public.org_invite_mail_log l WHERE l.email = v_inv.email AND l.sent_at > now() - interval '1 day')
      INTO v_recipient_hour, v_recipient_day;
    IF v_recipient_hour >= 3 OR v_recipient_day >= 10 THEN
        RAISE EXCEPTION 'rate_limited_recipient' USING ERRCODE = 'P0001';
    END IF;

    INSERT INTO public.agent_invite_mail_log (org_id, agent_id, email, actor) VALUES (v_agent.org_id, v_agent.id, v_inv.email, p_actor);
    UPDATE public.agent_invites SET last_sent_at = now() WHERE agent_id = v_agent.id AND email = v_inv.email;
    INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
        VALUES (v_agent.org_id, v_agent.id, p_actor, 'invite.mail', jsonb_build_object('email', v_inv.email::text));

    SELECT * INTO v_org FROM public.organisations o WHERE o.id = v_agent.org_id;
    -- The agent's own address: its oldest active custom domain, else <slug>.<base> (the function knows the base).
    SELECT d.hostname::text INTO v_host FROM public.agent_domains d
        WHERE d.agent_id = v_agent.id AND d.status = 'active' ORDER BY d.verified_at NULLS LAST, d.created_at LIMIT 1;
    SELECT u.email::text,
           COALESCE(NULLIF(pg_catalog.btrim(u.raw_user_meta_data->>'full_name'), ''),
                    NULLIF(pg_catalog.btrim(u.raw_user_meta_data->>'name'), ''))
      INTO v_from_email, v_from_name
      FROM auth.users u WHERE u.id = p_actor;
    RETURN jsonb_build_object(
        'email', v_inv.email::text, 'agent_name', COALESCE(NULLIF(pg_catalog.btrim(v_agent.brand->>'name'), ''), v_agent.name),
        'agent_slug', v_agent.slug, 'custom_host', v_host, 'org_name', v_org.name,
        'inviter_email', v_from_email, 'inviter_name', v_from_name);
END;
$$;
REVOKE ALL ON FUNCTION public.agent_invite_mail_prepare(uuid, uuid, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.agent_invite_mail_prepare(uuid, uuid, text) TO service_role;
