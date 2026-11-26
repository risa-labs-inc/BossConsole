-- Numeric operational budgets only. No secrets. The console writes through the admin-only CAS RPC.
BEGIN;
CREATE TABLE public.platform_limits (
  key text PRIMARY KEY,
  value jsonb NOT NULL,
  default_value jsonb NOT NULL,
  revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
  updated_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE public.platform_limits ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.platform_limits FROM PUBLIC, anon, authenticated;
GRANT SELECT ON public.platform_limits TO anon, authenticated, service_role;
CREATE POLICY "numeric limits read" ON public.platform_limits FOR SELECT TO anon, authenticated USING (true);
INSERT INTO public.platform_limits (key, value, default_value) VALUES
  ('magicLinkPerIp', '[[100, 900000], [1000, 86400000]]'::jsonb, '[[100, 900000], [1000, 86400000]]'::jsonb),
  ('magicLinkPerEmail', '[[10, 900000], [50, 86400000]]'::jsonb, '[[10, 900000], [50, 86400000]]'::jsonb),
  ('oauthStartPerIp', '[[20, 300000]]'::jsonb, '[[20, 300000]]'::jsonb),
  ('oauthReturnPerIp', '[[20, 300000]]'::jsonb, '[[20, 300000]]'::jsonb),
  ('handoffPerIp', '[[20, 300000]]'::jsonb, '[[20, 300000]]'::jsonb),
  ('waitlistPerIp', '[[5, 600000], [30, 86400000]]'::jsonb, '[[5, 600000], [30, 86400000]]'::jsonb),
  ('invitePerIp', '[[30, 300000], [300, 86400000]]'::jsonb, '[[30, 300000], [300, 86400000]]'::jsonb),
  ('signupPerIp', '[[60, 3600000], [300, 86400000]]'::jsonb, '[[60, 3600000], [300, 86400000]]'::jsonb),
  ('portalPushPerAccount', '[[20, 60000], [100, 3600000]]'::jsonb, '[[20, 60000], [100, 3600000]]'::jsonb),
  ('setupRetryPerAccount', '[[10, 600000]]'::jsonb, '[[10, 600000]]'::jsonb),
  ('webApiPerAccount', '[[120, 60000], [3000, 3600000]]'::jsonb, '[[120, 60000], [3000, 3600000]]'::jsonb),
  ('webPodPathPerAccount', '[[300, 60000], [6000, 3600000]]'::jsonb, '[[300, 60000], [6000, 3600000]]'::jsonb),
  ('webRestartPerAccount', '[[3, 600000], [10, 86400000]]'::jsonb, '[[3, 600000], [10, 86400000]]'::jsonb),
  ('webRequestPerAccount', '[[3, 86400000]]'::jsonb, '[[3, 86400000]]'::jsonb),
  ('loginFailureWindowMs', '900000'::jsonb, '900000'::jsonb),
  ('loginLockMs', '900000'::jsonb, '900000'::jsonb),
  ('loginFailuresPerEmail', '5'::jsonb, '5'::jsonb),
  ('loginFailuresPerIp', '20'::jsonb, '20'::jsonb),
  ('orgsPerOwner', '3'::jsonb, '3'::jsonb),
  ('orgRequestsPerDay', '5'::jsonb, '5'::jsonb),
  ('marketplaceSubmissionsPerDay', '20'::jsonb, '20'::jsonb),
  ('inviteMailPerOrgHour', '30'::jsonb, '30'::jsonb),
  ('inviteMailPerActorHour', '30'::jsonb, '30'::jsonb),
  ('inviteMailPerEmailHour', '3'::jsonb, '3'::jsonb),
  ('inviteMailPerEmailDay', '10'::jsonb, '10'::jsonb);

CREATE FUNCTION public.platform_limit_valid(p_key text, p_value jsonb, p_default jsonb)
RETURNS boolean LANGUAGE plpgsql IMMUTABLE SET search_path = '' AS $$
DECLARE w jsonb; n numeric;
BEGIN
  IF p_value IS NULL OR p_default IS NULL THEN RETURN false; END IF;
  IF pg_catalog.jsonb_typeof(p_default) = 'number' THEN
    IF pg_catalog.jsonb_typeof(p_value) <> 'number' THEN RETURN false; END IF;
    n := p_value::text::numeric;
    RETURN n = pg_catalog.trunc(n) AND n >= 1 AND n <= CASE WHEN p_key LIKE '%Ms' THEN 86400000 ELSE 1000000 END;
  END IF;
  IF pg_catalog.jsonb_typeof(p_value) <> 'array' THEN RETURN false; END IF;
  IF pg_catalog.jsonb_array_length(p_value) NOT BETWEEN 1 AND 6 THEN RETURN false; END IF;
  FOR w IN SELECT * FROM pg_catalog.jsonb_array_elements(p_value) LOOP
    IF pg_catalog.jsonb_typeof(w) <> 'array' THEN RETURN false; END IF;
    IF pg_catalog.jsonb_array_length(w) <> 2 THEN RETURN false; END IF;
    IF pg_catalog.jsonb_typeof(w->0) <> 'number' OR pg_catalog.jsonb_typeof(w->1) <> 'number' THEN RETURN false; END IF;
    n := (w->>0)::numeric;
    IF n <> pg_catalog.trunc(n) OR n < 1 OR n > 1000000 THEN RETURN false; END IF;
    n := (w->>1)::numeric;
    IF n <> pg_catalog.trunc(n) OR n < 1000 OR n > 86400000 THEN RETURN false; END IF;
  END LOOP;
  RETURN true;
END;
$$;
REVOKE ALL ON FUNCTION public.platform_limit_valid(text,jsonb,jsonb) FROM PUBLIC, anon, authenticated;
ALTER TABLE public.platform_limits ADD CONSTRAINT valid_limit CHECK (public.platform_limit_valid(key, value, default_value));

CREATE FUNCTION public.set_platform_limit(p_key text, p_value jsonb, p_revision bigint)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE old public.platform_limits%ROWTYPE;
BEGIN
  IF auth.uid() IS NULL OR NOT public.is_user_admin(auth.uid()) THEN
    RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
  END IF;
  SELECT * INTO old FROM public.platform_limits WHERE key = p_key FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'unknown_limit' USING ERRCODE = '22023'; END IF;
  IF p_revision IS DISTINCT FROM old.revision THEN
    RAISE EXCEPTION 'limit_changed_refresh_required' USING ERRCODE = '40001';
  END IF;
  IF NOT public.platform_limit_valid(p_key, p_value, old.default_value) THEN
    RAISE EXCEPTION 'invalid_limit' USING ERRCODE = '22023';
  END IF;
  IF p_value = old.value THEN RETURN; END IF;
  UPDATE public.platform_limits SET value = p_value, revision = revision + 1, updated_at = now() WHERE key = p_key;
  INSERT INTO public.platform_audit_log (actor, action, detail)
    VALUES (auth.uid(), 'platform.flag_set', pg_catalog.jsonb_build_object('key', 'limit.' || p_key, 'from', old.value, 'to', p_value));
END;
$$;
REVOKE ALL ON FUNCTION public.set_platform_limit(text,jsonb,bigint) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.set_platform_limit(text,jsonb,bigint) TO authenticated;

-- Database-enforced budgets use the same registry, taking effect on the next transaction.
CREATE FUNCTION public.platform_limit_number(p_key text, p_fallback int)
RETURNS int LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT COALESCE((SELECT (value::text)::int FROM public.platform_limits WHERE key = p_key), p_fallback);
$$;
REVOKE ALL ON FUNCTION public.platform_limit_number(text,int) FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.org_create(p_name text, p_slug text, p_description text DEFAULT NULL,
    p_domain text DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_actor uuid := auth.uid();
    v_name text := pg_catalog.btrim(COALESCE(p_name, ''));
    v_desc text := NULLIF(pg_catalog.btrim(COALESCE(p_description, '')), '');
    v_domain text := NULLIF(pg_catalog.lower(pg_catalog.btrim(COALESCE(p_domain, ''))), '');
    v_problem text;
    v_open boolean;
    v_res jsonb;
    v_req public.organisation_requests%ROWTYPE;
    v_org uuid;
BEGIN
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'Not authenticated' USING ERRCODE = '42501';
    END IF;
    IF NOT public.user_holds_permission(v_actor, 'organisation.create') THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('org_create:' || v_actor::text, 0));

    IF pg_catalog.char_length(v_name) < 2 OR pg_catalog.char_length(v_name) > 100 THEN
        RAISE EXCEPTION 'name_invalid' USING ERRCODE = 'P0001';
    END IF;
    IF pg_catalog.char_length(COALESCE(v_desc, '')) > 500 THEN
        RAISE EXCEPTION 'description_too_long' USING ERRCODE = 'P0001';
    END IF;
    v_problem := public.organisation_slug_problem(p_slug);
    IF v_problem IS NOT NULL THEN
        RAISE EXCEPTION '%', v_problem USING ERRCODE = 'P0001';
    END IF;
    IF v_domain IS NOT NULL THEN
        IF v_domain !~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$' THEN
            RAISE EXCEPTION 'domain_invalid' USING ERRCODE = 'P0001';
        END IF;
        IF EXISTS (SELECT 1 FROM public.reserved_email_domains red WHERE red.domain = v_domain) THEN
            RAISE EXCEPTION 'domain_reserved' USING ERRCODE = 'P0001';
        END IF;
        IF EXISTS (SELECT 1 FROM public.organisation_domains d WHERE d.domain = v_domain) THEN
            RAISE EXCEPTION 'domain_taken' USING ERRCODE = 'P0001';
        END IF;
    END IF;

    IF NOT public.is_user_admin(v_actor) THEN
        IF (SELECT pg_catalog.count(*) FROM public.organisations o WHERE o.owner_id = v_actor AND NOT o.is_system)
         + (SELECT pg_catalog.count(*) FROM public.organisation_requests r WHERE r.requester_id = v_actor AND r.status = 'pending')
           >= public.platform_limit_number('orgsPerOwner', 3) THEN
            RAISE EXCEPTION 'org_limit_reached' USING ERRCODE = 'P0001';
        END IF;
        IF (SELECT pg_catalog.count(*) FROM public.organisation_requests r
            WHERE r.requester_id = v_actor AND r.created_at > pg_catalog.now() - interval '24 hours') >= public.platform_limit_number('orgRequestsPerDay', 5) THEN
            RAISE EXCEPTION 'request_limit_reached' USING ERRCODE = 'P0001';
        END IF;
    END IF;

    v_res := public.submit_organisation_request(p_name => v_name, p_slug => p_slug, p_description => v_desc,
        p_domain => v_domain);
    IF COALESCE((v_res->>'success')::boolean, false) IS NOT TRUE THEN
        RAISE EXCEPTION '%', COALESCE(v_res->>'error', 'request_failed') USING ERRCODE = 'P0001';
    END IF;

    SELECT COALESCE((SELECT f.value = 'true'::jsonb FROM public.platform_flags f WHERE f.key = 'org_creation_open'), false)
        INTO v_open;
    IF NOT v_open THEN
        RETURN pg_catalog.jsonb_build_object('status', 'pending', 'request_id', v_res->>'request_id', 'slug', p_slug);
    END IF;

    -- The approve path without the reviewer gate (approve_organisation_request's body).
    SELECT * INTO v_req FROM public.organisation_requests r WHERE r.id = (v_res->>'request_id')::uuid FOR UPDATE;
    v_res := public.create_organisation_internal(
        p_slug => v_req.slug, p_name => v_req.name, p_description => v_req.description,
        p_owner_id => v_req.requester_id, p_domain => v_req.domain, p_website => v_req.website,
        p_visibility => 'private', p_join_policy => 'request_to_join');
    IF COALESCE((v_res->>'success')::boolean, false) IS NOT TRUE THEN
        RAISE EXCEPTION '%', COALESCE(v_res->>'error', 'create_failed') USING ERRCODE = 'P0001';
    END IF;
    v_org := (v_res->>'org_id')::uuid;
    -- reviewed_shape needs a reviewer on an approved row: the requester, under the open policy.
    PERFORM pg_catalog.set_config('agents.org_create_auto', 'on', true);
    UPDATE public.organisation_requests
       SET status = 'approved', reviewer_id = v_actor, reviewed_at = pg_catalog.now(),
           review_notes = 'Created at once: organisation creation is open', created_org_id = v_org,
           updated_at = pg_catalog.now()
     WHERE id = v_req.id;
    PERFORM pg_catalog.set_config('agents.org_create_auto', '', true);
    RETURN pg_catalog.jsonb_build_object('status', 'created', 'request_id', v_req.id, 'slug', v_req.slug, 'org_id', v_org);
END;
$$;

CREATE OR REPLACE FUNCTION public.invite_link_mail_limits_ok(p_target text, p_org uuid, p_actor uuid, p_email extensions.citext)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_hour int;
    v_day int;
BEGIN
    IF p_org IS NOT NULL THEN
        PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext('agent_invite_mail:' || p_org::text));
        IF (SELECT count(*) FROM public.agent_invite_mail_log l WHERE l.org_id = p_org AND l.sent_at > now() - interval '1 hour') >= public.platform_limit_number('inviteMailPerOrgHour', 30) THEN
            RETURN false;
        END IF;
    ELSE
        PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext('invite_link_mail:' || p_actor::text));
        IF (SELECT count(*) FROM public.invite_link_mail_log l
            WHERE l.actor = p_actor AND l.target = p_target AND l.sent_at > now() - interval '1 hour') >= public.platform_limit_number('inviteMailPerActorHour', 30) THEN
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
    RETURN v_hour < public.platform_limit_number('inviteMailPerEmailHour', 3) AND v_day < public.platform_limit_number('inviteMailPerEmailDay', 10);
END;
$$;

CREATE OR REPLACE FUNCTION public.marketplace_bundle_precheck(p_user uuid, p_manifest jsonb, p_skip_review boolean DEFAULT false)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    me uuid := p_user;
    code text;
    v_kind public.marketplace_kind;
    ctype text;
    scripts boolean;
    agents text[];
    ag text;
    pl public.plugins%ROWTYPE;
    l public.marketplace_listings%ROWTYPE;
BEGIN
    IF me IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    IF NOT public.marketplace_flag_on('marketplace_bundles') THEN
        RETURN pg_catalog.jsonb_build_object('error', 'bundles_disabled');
    END IF;
    code := public.marketplace_check_manifest(p_manifest);
    IF code IS NOT NULL THEN
        RETURN pg_catalog.jsonb_build_object('error', code);
    END IF;

    v_kind := (p_manifest->>'kind')::public.marketplace_kind;
    ctype := p_manifest->'connector'->>'type';
    scripts := COALESCE(ctype = 'scripts', false);
    agents := ARRAY(SELECT DISTINCT ON (x.a) x.a FROM pg_catalog.jsonb_array_elements_text(
        COALESCE(p_manifest->'visibility'->'agents', '[]'::jsonb)) WITH ORDINALITY AS x(a, ord) ORDER BY x.a, x.ord);

    IF ctype = 'http' AND NOT public.marketplace_flag_on('marketplace_http_connectors') THEN
        RETURN pg_catalog.jsonb_build_object('error', 'http_connectors_disabled');
    END IF;
    IF scripts AND NOT public.marketplace_flag_on('marketplace_script_connectors') THEN
        RETURN pg_catalog.jsonb_build_object('error', 'script_connectors_disabled');
    END IF;
    IF pg_catalog.cardinality(agents) > 0 THEN
        IF NOT public.marketplace_flag_on('marketplace_restricted_listings') THEN
            RETURN pg_catalog.jsonb_build_object('error', 'restricted_disabled');
        END IF;
        FOREACH ag IN ARRAY agents LOOP
            SELECT * INTO pl FROM public.plugins p WHERE p.plugin_id = ag AND p.published;
            IF NOT FOUND THEN
                RETURN pg_catalog.jsonb_build_object('error', 'unknown_agent');
            END IF;
            IF NOT (public.is_user_admin(me) OR public.user_has_org_permission(me, pl.org_id, 'agents.publish')) THEN
                RETURN pg_catalog.jsonb_build_object('error', 'agent_restriction_not_allowed');
            END IF;
        END LOOP;
    END IF;

    -- A script connector always waits for an admin: no skip, no open publishing.
    IF scripts AND COALESCE(p_skip_review, false) THEN
        RETURN pg_catalog.jsonb_build_object('error', 'review_required');
    END IF;
    IF COALESCE(p_skip_review, false) AND NOT public.marketplace_user_can_skip_review(me)
       AND NOT public.marketplace_open_publishing() THEN
        RETURN pg_catalog.jsonb_build_object('error', 'review_skip_not_allowed');
    END IF;

    -- Platform admins and trusted publishers have no daily cap; everyone else gets 20 a day.
    IF NOT public.marketplace_user_can_skip_review(me) AND (SELECT pg_catalog.count(*) FROM public.marketplace_versions
            WHERE created_by = me AND created_at > now() - interval '1 day') >= public.platform_limit_number('marketplaceSubmissionsPerDay', 20) THEN
        RETURN pg_catalog.jsonb_build_object('error', 'submission_rate_limited');
    END IF;

    -- The listing, when the slug already has one.
    SELECT * INTO l FROM public.marketplace_listings WHERE slug = p_manifest->>'slug';
    IF FOUND THEN
        IF l.publisher_id <> me THEN
            RETURN pg_catalog.jsonb_build_object('error', 'slug_taken');
        END IF;
        IF l.kind <> v_kind THEN
            RETURN pg_catalog.jsonb_build_object('error', 'kind_mismatch');
        END IF;
        IF l.status = 'suspended' THEN
            RETURN pg_catalog.jsonb_build_object('error', 'listing_suspended');
        END IF;
    END IF;
    RETURN '{}'::jsonb;
END;
$$;

CREATE OR REPLACE FUNCTION public.marketplace_submit(p_kind public.marketplace_kind, p_slug text, p_name text, p_summary text,
    p_description text, p_publisher_name text, p_content jsonb, p_skip_review boolean DEFAULT false,
    p_category text DEFAULT NULL, p_tags text[] DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    me uuid := auth.uid();
    skip boolean := COALESCE(p_skip_review, false) OR public.marketplace_open_publishing();
    l public.marketplace_listings%ROWTYPE;
    live_decision text;
    next_version int;
    vid uuid;
    cat text := NULLIF(pg_catalog.btrim(COALESCE(p_category, '')), '');
    new_tags text[] := public.marketplace_norm_tags(p_tags);
BEGIN
    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('marketplace_submit:' || me::text, 0));
    IF me IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    IF COALESCE(p_skip_review, false) AND NOT public.marketplace_can_skip_review() AND NOT public.marketplace_open_publishing() THEN
        RAISE EXCEPTION 'review_skip_not_allowed' USING ERRCODE = '42501';
    END IF;
    PERFORM public.marketplace_validate(p_kind, p_slug, p_name, p_summary, COALESCE(p_description, ''), p_publisher_name, p_content);
    IF cat IS NOT NULL AND NOT EXISTS (SELECT 1 FROM public.marketplace_categories c WHERE c.slug = cat) THEN
        RAISE EXCEPTION 'invalid_category' USING ERRCODE = '22023';
    END IF;
    -- Platform admins and trusted publishers have no daily cap; everyone else gets 20 a day.
    IF NOT public.marketplace_can_skip_review() AND (SELECT pg_catalog.count(*) FROM public.marketplace_versions
            WHERE created_by = me AND created_at > now() - interval '1 day') >= public.platform_limit_number('marketplaceSubmissionsPerDay', 20) THEN
        RAISE EXCEPTION 'submission_rate_limited' USING ERRCODE = 'P0001';
    END IF;
    INSERT INTO public.marketplace_listings (kind, slug, publisher_id)
        VALUES (p_kind, p_slug, me) ON CONFLICT (slug) DO NOTHING;
    SELECT * INTO l FROM public.marketplace_listings WHERE slug = p_slug FOR UPDATE;
    IF l.publisher_id <> me THEN
        RAISE EXCEPTION 'slug_taken' USING ERRCODE = 'P0001';
    END IF;
    IF l.kind <> p_kind THEN
        RAISE EXCEPTION 'kind_mismatch' USING ERRCODE = 'P0001';
    END IF;
    IF l.status = 'suspended' THEN
        RAISE EXCEPTION 'listing_suspended' USING ERRCODE = 'P0001';
    END IF;
    UPDATE public.marketplace_versions SET decision = 'superseded', decided_at = now()
        WHERE id = l.pending_version_id AND decision IS NULL;
    SELECT COALESCE(pg_catalog.max(version), 0) + 1 INTO next_version
        FROM public.marketplace_versions WHERE listing_id = l.id;
    INSERT INTO public.marketplace_versions (listing_id, version, name, summary, description, publisher_name, content, created_by,
                                             decision, decided_by, decided_at, category, tags)
        VALUES (l.id, next_version, p_name, p_summary, COALESCE(p_description, ''), pg_catalog.btrim(p_publisher_name), p_content, me,
                CASE WHEN skip THEN 'unreviewed' END, CASE WHEN skip THEN me END, CASE WHEN skip THEN now() END, cat, new_tags)
        RETURNING id INTO vid;
    IF skip THEN
        UPDATE public.marketplace_listings SET live_version_id = vid, pending_version_id = NULL, review_note = NULL,
            status = 'live', updated_at = now() WHERE id = l.id;
        INSERT INTO public.platform_audit_log (actor, action, detail)
            VALUES (me, 'marketplace.publish_unreviewed',
                    pg_catalog.jsonb_build_object('slug', l.slug, 'kind', l.kind, 'version', next_version));
        RETURN pg_catalog.jsonb_build_object('slug', l.slug, 'version', next_version, 'status', 'live', 'reviewed', false);
    END IF;
    UPDATE public.marketplace_listings SET pending_version_id = vid, review_note = NULL, updated_at = now(),
        -- A live listing keeps serving its live version; anything else (rejected, withdrawn) waits for review unlisted.
        status = (CASE WHEN l.status = 'live' THEN 'live' ELSE 'pending_review' END)::public.marketplace_status
        WHERE id = l.id;
    INSERT INTO public.platform_audit_log (actor, action, detail)
        VALUES (me, 'marketplace.submit', pg_catalog.jsonb_build_object('slug', l.slug, 'kind', l.kind, 'version', next_version));
    SELECT v.decision INTO live_decision FROM public.marketplace_versions v WHERE v.id = l.live_version_id;
    RETURN pg_catalog.jsonb_strip_nulls(pg_catalog.jsonb_build_object('slug', l.slug, 'version', next_version,
        'status', 'pending_review', 'reviewed', CASE WHEN l.live_version_id IS NOT NULL THEN live_decision = 'approved' END));
END;
$$;

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
        WHERE l.org_id = v_agent.org_id AND l.sent_at > now() - interval '1 hour') >= public.platform_limit_number('inviteMailPerOrgHour', 30) THEN
        RAISE EXCEPTION 'rate_limited_org' USING ERRCODE = 'P0001';
    END IF;
    SELECT (SELECT count(*) FROM public.agent_invite_mail_log l WHERE l.email = v_inv.email AND l.sent_at > now() - interval '1 hour')
         + (SELECT count(*) FROM public.org_invite_mail_log l WHERE l.email = v_inv.email AND l.sent_at > now() - interval '1 hour'),
           (SELECT count(*) FROM public.agent_invite_mail_log l WHERE l.email = v_inv.email AND l.sent_at > now() - interval '1 day')
         + (SELECT count(*) FROM public.org_invite_mail_log l WHERE l.email = v_inv.email AND l.sent_at > now() - interval '1 day')
      INTO v_recipient_hour, v_recipient_day;
    IF v_recipient_hour >= public.platform_limit_number('inviteMailPerEmailHour', 3) OR v_recipient_day >= public.platform_limit_number('inviteMailPerEmailDay', 10) THEN
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

CREATE OR REPLACE FUNCTION public.org_invite_mail_prepare(p_actor uuid, p_org_id uuid, p_invite_id uuid, p_token text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_inv public.organisation_invites;
    v_org public.organisations;
    v_role text;
    v_from_email text;
    v_from_name text;
BEGIN
    IF p_actor IS NULL OR p_org_id IS NULL OR NOT public.user_is_org_admin(p_actor, p_org_id) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    SELECT * INTO v_inv FROM public.organisation_invites i
        WHERE i.id = p_invite_id AND i.org_id = p_org_id
          AND i.token_hash = pg_catalog.encode(extensions.digest(pg_catalog.btrim(COALESCE(p_token, '')), 'sha256'), 'hex')
        FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'invite_not_found' USING ERRCODE = 'P0001';
    END IF;
    IF v_inv.email IS NULL THEN
        RAISE EXCEPTION 'invite_not_email' USING ERRCODE = 'P0001';
    END IF;
    IF v_inv.revoked_at IS NOT NULL OR v_inv.expires_at <= now()
       OR (v_inv.max_uses IS NOT NULL AND v_inv.uses >= v_inv.max_uses) THEN
        RAISE EXCEPTION 'invite_not_pending' USING ERRCODE = 'P0001';
    END IF;

    PERFORM pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtext('org_invite_mail:' || p_org_id::text));
    IF (SELECT count(*) FROM public.org_invite_mail_log l
        WHERE l.org_id = p_org_id AND l.sent_at > now() - interval '1 hour') >= public.platform_limit_number('inviteMailPerOrgHour', 30) THEN
        RAISE EXCEPTION 'rate_limited_org' USING ERRCODE = 'P0001';
    END IF;
    IF (SELECT count(*) FROM public.org_invite_mail_log l
        WHERE l.email = v_inv.email AND l.sent_at > now() - interval '1 hour') >= public.platform_limit_number('inviteMailPerEmailHour', 3)
       OR (SELECT count(*) FROM public.org_invite_mail_log l
           WHERE l.email = v_inv.email AND l.sent_at > now() - interval '1 day') >= public.platform_limit_number('inviteMailPerEmailDay', 10) THEN
        RAISE EXCEPTION 'rate_limited_recipient' USING ERRCODE = 'P0001';
    END IF;

    INSERT INTO public.org_invite_mail_log (org_id, invite_id, email, actor)
        VALUES (p_org_id, v_inv.id, v_inv.email, p_actor);
    INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
        VALUES (p_org_id, NULL, p_actor, 'team.invite_mail',
                jsonb_build_object('invite_id', v_inv.id, 'email', v_inv.email::text));

    SELECT * INTO v_org FROM public.organisations o WHERE o.id = p_org_id;
    SELECT r.name INTO v_role FROM public.roles r WHERE r.id = v_inv.role_id;
    SELECT u.email::text,
           COALESCE(NULLIF(pg_catalog.btrim(u.raw_user_meta_data->>'full_name'), ''),
                    NULLIF(pg_catalog.btrim(u.raw_user_meta_data->>'name'), ''))
      INTO v_from_email, v_from_name
      FROM auth.users u WHERE u.id = p_actor;
    RETURN jsonb_build_object('email', v_inv.email::text, 'org_name', v_org.name, 'org_slug', v_org.slug,
                              'role_name', v_role, 'expires_at', v_inv.expires_at,
                              'inviter_email', v_from_email, 'inviter_name', v_from_name);
END;
$$;

CREATE OR REPLACE FUNCTION "public"."submit_organisation_request"(
    "p_name" "text",
    "p_slug" "text",
    "p_description" "text" DEFAULT NULL::"text",
    "p_domain" "text" DEFAULT NULL::"text",
    "p_justification" "text" DEFAULT NULL::"text",
    "p_website" "text" DEFAULT NULL::"text",
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_request_id UUID;
    v_pending_count INTEGER;
    v_domain TEXT;
    v_website TEXT;
    v_website_error TEXT;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    -- A genuinely global permission, so a permission check is the right gate.
    IF NOT public.user_holds_permission(v_actor, 'organisation.create') THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    IF p_name IS NULL OR char_length(btrim(p_name)) < 2 OR char_length(btrim(p_name)) > 100 THEN
        RETURN jsonb_build_object('success', false, 'error', 'Name must be 2-100 characters');
    END IF;

    IF p_slug IS NULL OR NOT (p_slug ~ '^[a-z][a-z0-9_]{1,30}$') THEN
        RETURN jsonb_build_object('success', false, 'error',
            'Slug must be 2-31 characters, lowercase letters, digits and underscores, starting with a letter');
    END IF;

    IF public.is_reserved_organisation_slug(p_slug) THEN
        RETURN jsonb_build_object('success', false, 'error',
            format('Slug "%s" is reserved or already in use', p_slug));
    END IF;

    IF EXISTS (SELECT 1 FROM public.organisations o WHERE o.slug = p_slug) THEN
        RETURN jsonb_build_object('success', false, 'error',
            format('An organisation with slug "%s" already exists', p_slug));
    END IF;

    -- The pending-request ceiling follows the configurable ownership allowance.
    SELECT count(*) INTO v_pending_count
    FROM public.organisation_requests r
    WHERE r.requester_id = v_actor AND r.status = 'pending';
    IF v_pending_count >= public.platform_limit_number('orgsPerOwner', 3) THEN
        RETURN jsonb_build_object('success', false, 'error',
            'Your pending organisation request limit has been reached. Withdraw one before submitting another.');
    END IF;

    IF p_domain IS NOT NULL AND btrim(p_domain) <> '' THEN
        v_domain := lower(btrim(p_domain));
        IF NOT (v_domain ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$') THEN
            RETURN jsonb_build_object('success', false, 'error', 'That is not a valid domain name');
        END IF;
        IF EXISTS (SELECT 1 FROM public.reserved_email_domains red WHERE red.domain = v_domain) THEN
            RETURN jsonb_build_object('success', false, 'error',
                format('"%s" is a reserved email domain and cannot be claimed by an organisation', v_domain));
        END IF;
        IF EXISTS (SELECT 1 FROM public.organisation_domains d WHERE d.domain = v_domain) THEN
            RETURN jsonb_build_object('success', false, 'error',
                format('Domain "%s" is already claimed by another organisation', v_domain));
        END IF;
    END IF;

    -- Validated HERE as well as by the column CHECK, so a typo comes back as a
    -- sentence rather than a constraint violation the caller cannot read.
    --
    -- http and https only. This value is rendered as a link on the organisation
    -- pages, and a javascript: or data: URL there would be script execution from a
    -- field any authenticated user can fill in. The renderer refuses those too;
    -- this is the half that stops one being stored at all.
    v_website := NULLIF(btrim(COALESCE(p_website, '')), '');
    v_website_error := public.validate_website(v_website);
    IF v_website_error IS NOT NULL THEN
        RETURN jsonb_build_object('success', false, 'error', v_website_error);
    END IF;

    BEGIN
        INSERT INTO public.organisation_requests (
            requester_id, name, slug, description, domain, justification, website, status
        ) VALUES (
            v_actor, btrim(p_name), p_slug, p_description, v_domain, p_justification, v_website, 'pending'
        ) RETURNING id INTO v_request_id;
    EXCEPTION WHEN unique_violation THEN
        -- idx_organisation_requests_pending_slug
        RETURN jsonb_build_object('success', false, 'error',
            format('A pending request for slug "%s" already exists', p_slug));
    END;

    RETURN jsonb_build_object('success', true, 'request_id', v_request_id::text, 'status', 'pending');
END;
$$;

COMMIT;
