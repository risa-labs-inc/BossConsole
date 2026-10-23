-- ============================================================================
-- Agents console: "Create organisation".
--
-- The console files a normal organisation request (submit_organisation_request: same validation,
-- same row, same reviewer queue). When the platform flag org_creation_open is true the request is
-- approved in the same transaction and the requester owns the new org at once; otherwise it waits
-- for a platform admin, who uses the existing approve/reject/list RPCs directly.
--
-- Only org_create auto-approves. The desktop app's submit_organisation_request is unchanged and
-- always queues, so the per-requester limits below cannot be bypassed into an org.
--
-- The org's built-in agent roles come from the organisation_roles seed trigger (20261019140000);
-- every approval (automatic or reviewed) writes an org.create entry to the org's audit log.
-- ============================================================================

INSERT INTO public.platform_flags (key, value) VALUES ('org_creation_open', 'false'::jsonb)
    ON CONFLICT (key) DO NOTHING;

-- ----------------------------------------------------------------------------
-- What is wrong with a slug for a new organisation, as a code, or NULL when it can be requested.
-- Reserved = the organisation list, derived role names already taken, or the agents platform's
-- reserved host names (console, max, ...). Signed-in only: submit already answers "taken".
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.organisation_slug_problem(p_slug text)
RETURNS text LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT CASE
        WHEN p_slug IS NULL OR p_slug !~ '^[a-z][a-z0-9_]{1,30}$' THEN 'slug_invalid'
        WHEN EXISTS (SELECT 1 FROM public.organisations o WHERE o.slug = p_slug) THEN 'slug_taken'
        WHEN public.is_reserved_organisation_slug(p_slug)
          OR EXISTS (SELECT 1 FROM public.agent_reserved_slugs r WHERE r.slug = p_slug) THEN 'slug_reserved'
        WHEN EXISTS (SELECT 1 FROM public.organisation_requests r
                     WHERE r.slug = p_slug AND r.status = 'pending') THEN 'slug_requested'
    END;
$$;
REVOKE ALL ON FUNCTION public.organisation_slug_problem(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.organisation_slug_problem(text) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- org_create: file a request, and create the org at once when org_creation_open.
-- Raises 42501 'Permission denied' / 'Not authenticated', or P0001 '<code>':
--   name_invalid, description_too_long, slug_invalid, slug_reserved, slug_taken, slug_requested,
--   domain_invalid, domain_reserved, domain_taken, org_limit_reached, request_limit_reached,
--   or submit/create's own sentence for anything else.
-- Limits (global admins exempt): owned orgs plus pending requests at most 3; at most 5 requests
-- (any outcome) in 24 hours. Serialised per requester so two tabs cannot both pass the count.
-- Returns {status: 'created'|'pending', request_id, slug, org_id (created only)}.
-- ----------------------------------------------------------------------------
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
           >= 3 THEN
            RAISE EXCEPTION 'org_limit_reached' USING ERRCODE = 'P0001';
        END IF;
        IF (SELECT pg_catalog.count(*) FROM public.organisation_requests r
            WHERE r.requester_id = v_actor AND r.created_at > pg_catalog.now() - interval '24 hours') >= 5 THEN
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
REVOKE ALL ON FUNCTION public.org_create(text, text, text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.org_create(text, text, text, text) TO authenticated;

-- ----------------------------------------------------------------------------
-- The caller's own requests, newest first (the console's "Waiting for approval" card and history).
-- The created org's slug is included only when the caller is still a member of it.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.my_organisation_requests()
RETURNS TABLE (request_id uuid, name text, slug text, description text, domain text, status text,
               review_notes text, reviewed_at timestamptz, created_org_id uuid, created_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT r.id, r.name, r.slug, r.description, r.domain, r.status, r.review_notes, r.reviewed_at,
           CASE WHEN EXISTS (SELECT 1 FROM public.organisation_members m
                             WHERE m.org_id = r.created_org_id AND m.user_id = r.requester_id AND m.status = 'active')
                THEN r.created_org_id END,
           r.created_at
      FROM public.organisation_requests r
     WHERE r.requester_id = auth.uid()
     ORDER BY r.created_at DESC
     LIMIT 50;
$$;
REVOKE ALL ON FUNCTION public.my_organisation_requests() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.my_organisation_requests() TO authenticated;

-- ----------------------------------------------------------------------------
-- Audit: org.create on the new org's log when a request is approved (org_create or a reviewer).
-- detail.mode = 'automatic' (org_creation_open) or 'reviewed'. The actor is the reviewer (the
-- requester themselves when automatic). Approval by the service role names its p_actor_id.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.organisation_requests_audit_approve()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF NEW.status = 'approved' AND OLD.status IS DISTINCT FROM 'approved' AND NEW.created_org_id IS NOT NULL THEN
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
        VALUES (NEW.created_org_id, NULL, NEW.reviewer_id, 'org.create',
                pg_catalog.jsonb_build_object(
                    'request_id', NEW.id, 'name', NEW.name, 'slug', NEW.slug, 'owner_id', NEW.requester_id,
                    'mode', CASE WHEN COALESCE(pg_catalog.current_setting('agents.org_create_auto', true), '') = 'on'
                                 THEN 'automatic' ELSE 'reviewed' END));
    END IF;
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION public.organisation_requests_audit_approve() FROM PUBLIC, anon, authenticated;
DROP TRIGGER IF EXISTS organisation_requests_audit_approve ON public.organisation_requests;
CREATE TRIGGER organisation_requests_audit_approve AFTER UPDATE OF status ON public.organisation_requests
    FOR EACH ROW EXECUTE FUNCTION public.organisation_requests_audit_approve();
