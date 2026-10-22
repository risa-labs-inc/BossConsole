-- ============================================================================
-- QA chat polish: the agent host's sign-in precheck.
--
-- Chat asks before it emails a sign-in link: an address the agent would refuse gets no mail. agent_can_join answers
-- only for the caller's own email (or the service role), and chat holds no service key, so the provisioner bot asks
-- on its behalf. Chat answers every address the same way; the domain list is the agent's public policy.
-- ============================================================================

-- {"allowed": bool, "domains": [..] | null}. allowed: agent_can_join's rule for this address on a live agent.
-- domains: the sign-up domains when the agent is in domain mode and the address is outside them (invited or not, so
-- it never tells an invite apart); null otherwise. Provisioner bot or service role only.
CREATE OR REPLACE FUNCTION public.agent_signin_check(p_slug text, p_email text)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    a public.agents%ROWTYPE;
    norm text := pg_catalog.lower(pg_catalog.btrim(COALESCE(p_email, '')));
    mode text;
    invited boolean;
    at_domain boolean;
BEGIN
    -- The provisioner bot, or a caller with no end-user identity at all (the operator, tests); never anon or a person.
    IF NOT (public.is_agents_provisioner() OR (auth.uid() IS NULL AND current_user NOT IN ('anon', 'authenticated'))) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    SELECT * INTO a FROM public.agents WHERE slug = p_slug AND status = 'live';
    IF NOT FOUND OR norm !~ '^[^@\s]+@[^@\s]+$' THEN
        RETURN pg_catalog.jsonb_build_object('allowed', false, 'domains', NULL);
    END IF;
    mode := a.signup_policy->>'mode';
    IF mode = 'open' THEN
        RETURN pg_catalog.jsonb_build_object('allowed', true, 'domains', NULL);
    END IF;
    invited := EXISTS (SELECT 1 FROM public.agent_invites i WHERE i.agent_id = a.id AND pg_catalog.lower(i.email::text) = norm);
    IF mode = 'domain' THEN
        at_domain := public.agent_policy_domain_match(a.signup_policy, norm);
        RETURN pg_catalog.jsonb_build_object('allowed', invited OR at_domain, 'domains', CASE WHEN at_domain THEN NULL ELSE (
            SELECT COALESCE(pg_catalog.jsonb_agg(DISTINCT pg_catalog.lower(pg_catalog.btrim(d, ' @'))), '[]'::jsonb)
            FROM pg_catalog.jsonb_array_elements_text(CASE WHEN pg_catalog.jsonb_typeof(a.signup_policy->'domains') = 'array'
                THEN a.signup_policy->'domains' ELSE '[]'::jsonb END) d
            WHERE pg_catalog.btrim(d, ' @') <> '') END);
    END IF;
    RETURN pg_catalog.jsonb_build_object('allowed', mode = 'invite' AND invited, 'domains', NULL);
END;
$$;
REVOKE ALL ON FUNCTION public.agent_signin_check(text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.agent_signin_check(text, text) TO authenticated, service_role;
COMMENT ON FUNCTION public.agent_signin_check(text, text) IS
    'Chat''s sign-in precheck via the provisioner bot: whether to email a link, and the domain hint. Bot or service role only.';
