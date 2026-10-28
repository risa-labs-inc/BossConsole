-- The public marketplace for skills and connectors (app.bossconsole.ai/marketplace). Any signed-in person submits;
-- every first version and every later version waits for a platform admin. A live listing keeps serving its approved
-- version while a newer one is in review. Content is validated by the same rules as a managed agent's draft
-- (validate_agent_draft_content), so anything listed here can also be published inside an agent.
-- Tables are reachable only through the RPCs below.

CREATE TYPE public.marketplace_kind AS ENUM ('skill', 'connector');
-- pending_review: never approved yet. live: has an approved version (a newer one may be pending). rejected: the first
-- version was refused (a new submission returns it to review). suspended: taken down by an admin. withdrawn: by its publisher.
CREATE TYPE public.marketplace_status AS ENUM ('pending_review', 'live', 'rejected', 'suspended', 'withdrawn');

CREATE TABLE public.marketplace_listings (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    kind               public.marketplace_kind NOT NULL,
    -- The install name: a skill's file name, a connector's id is 'mcp-' || slug.
    slug               text NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,46}$'),
    publisher_id       uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    status             public.marketplace_status NOT NULL DEFAULT 'pending_review',
    live_version_id    uuid,
    pending_version_id uuid,
    review_note        text,
    install_count      bigint NOT NULL DEFAULT 0,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_marketplace_listings_publisher ON public.marketplace_listings (publisher_id);
CREATE INDEX idx_marketplace_listings_status ON public.marketplace_listings (status, kind);

-- Immutable once written: a change is a new version.
CREATE TABLE public.marketplace_versions (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_id     uuid NOT NULL REFERENCES public.marketplace_listings(id) ON DELETE CASCADE,
    version        int NOT NULL CHECK (version > 0),
    name           text NOT NULL,
    summary        text NOT NULL,
    description    text NOT NULL DEFAULT '',
    publisher_name text NOT NULL,
    -- skill: {"markdown"}; connector: {"url", "auth"}.
    content        jsonb NOT NULL,
    created_by     uuid REFERENCES auth.users(id) ON DELETE SET NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    -- null while in review; approved, rejected, or superseded by a later submission before review.
    decision       text CHECK (decision IN ('approved', 'rejected', 'superseded', 'withdrawn')),
    decided_by     uuid REFERENCES auth.users(id) ON DELETE SET NULL,
    decided_at     timestamptz,
    reason         text,
    UNIQUE (listing_id, version)
);
ALTER TABLE public.marketplace_listings
    ADD CONSTRAINT marketplace_listings_live_fk FOREIGN KEY (live_version_id) REFERENCES public.marketplace_versions(id),
    ADD CONSTRAINT marketplace_listings_pending_fk FOREIGN KEY (pending_version_id) REFERENCES public.marketplace_versions(id);

CREATE TABLE public.marketplace_installs (
    listing_id   uuid NOT NULL REFERENCES public.marketplace_listings(id) ON DELETE CASCADE,
    user_id      uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    version_id   uuid NOT NULL REFERENCES public.marketplace_versions(id) ON DELETE CASCADE,
    installed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (listing_id, user_id)
);

ALTER TABLE public.marketplace_listings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.marketplace_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.marketplace_installs ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.marketplace_listings, public.marketplace_versions, public.marketplace_installs
    FROM PUBLIC, anon, authenticated, service_role;

-- ----------------------------------------------------------------------------
-- Validation: the managed-agent draft rules on a one-item draft, plus the listing's own text fields.
-- ----------------------------------------------------------------------------
CREATE FUNCTION public.marketplace_validate(p_kind public.marketplace_kind, p_slug text, p_name text, p_summary text,
    p_description text, p_publisher_name text, p_content jsonb)
RETURNS void LANGUAGE plpgsql IMMUTABLE SET search_path = '' AS $$
BEGIN
    IF p_slug IS NULL OR p_slug !~ '^[a-z0-9][a-z0-9-]{1,46}$' THEN
        RAISE EXCEPTION 'invalid_slug' USING ERRCODE = '22023';
    END IF;
    IF p_name IS NULL OR pg_catalog.btrim(p_name) <> p_name OR pg_catalog.char_length(p_name) NOT BETWEEN 2 AND 40 THEN
        RAISE EXCEPTION 'invalid_name' USING ERRCODE = '22023';
    END IF;
    IF p_summary IS NULL OR pg_catalog.btrim(p_summary) = '' OR pg_catalog.char_length(p_summary) > 200 OR p_summary ~ '[\r\n]' THEN
        RAISE EXCEPTION 'invalid_summary' USING ERRCODE = '22023';
    END IF;
    IF pg_catalog.char_length(COALESCE(p_description, '')) > 4000 THEN
        RAISE EXCEPTION 'invalid_description' USING ERRCODE = '22023';
    END IF;
    IF p_publisher_name IS NULL OR pg_catalog.btrim(p_publisher_name) = '' OR pg_catalog.char_length(p_publisher_name) > 60 THEN
        RAISE EXCEPTION 'invalid_publisher_name' USING ERRCODE = '22023';
    END IF;
    IF pg_catalog.jsonb_typeof(p_content) IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION 'invalid_content' USING ERRCODE = '22023';
    END IF;
    IF p_kind = 'skill' THEN
        IF EXISTS (SELECT 1 FROM pg_catalog.jsonb_object_keys(p_content) k WHERE k <> 'markdown')
           OR pg_catalog.jsonb_typeof(p_content->'markdown') IS DISTINCT FROM 'string'
           OR pg_catalog.btrim(p_content->>'markdown') = '' THEN
            RAISE EXCEPTION 'invalid_content' USING ERRCODE = '22023';
        END IF;
        PERFORM public.validate_agent_draft_content(pg_catalog.jsonb_build_object('soul', '', 'identity', '',
            'skills', pg_catalog.jsonb_build_object(p_slug, p_content->'markdown'), 'custom_connectors', '[]'::jsonb));
    ELSE
        IF EXISTS (SELECT 1 FROM pg_catalog.jsonb_object_keys(p_content) k WHERE k NOT IN ('url', 'auth')) THEN
            RAISE EXCEPTION 'invalid_content' USING ERRCODE = '22023';
        END IF;
        PERFORM public.validate_agent_draft_content(pg_catalog.jsonb_build_object('soul', '', 'identity', '',
            'skills', '{}'::jsonb, 'custom_connectors', pg_catalog.jsonb_build_array(pg_catalog.jsonb_build_object(
                'id', 'mcp-' || p_slug, 'name', p_name, 'url', p_content->'url', 'auth', p_content->'auth'))));
    END IF;
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_validate(public.marketplace_kind, text, text, text, text, text, jsonb)
    FROM PUBLIC, anon, authenticated, service_role;

-- One version as JSON; content only when asked.
CREATE FUNCTION public.marketplace_version_json(v public.marketplace_versions, p_with_content boolean)
RETURNS jsonb LANGUAGE sql IMMUTABLE SET search_path = '' AS $$
    SELECT pg_catalog.jsonb_strip_nulls(pg_catalog.jsonb_build_object(
        'id', v.id, 'version', v.version, 'name', v.name, 'summary', v.summary, 'description', v.description,
        'publisher_name', v.publisher_name, 'created_at', v.created_at, 'decision', v.decision, 'reason', v.reason,
        'content', CASE WHEN p_with_content THEN v.content END))
$$;
REVOKE ALL ON FUNCTION public.marketplace_version_json(public.marketplace_versions, boolean)
    FROM PUBLIC, anon, authenticated, service_role;

-- ----------------------------------------------------------------------------
-- Public reads: live listings only, the approved version only.
-- ----------------------------------------------------------------------------
CREATE FUNCTION public.marketplace_search(p_query text DEFAULT NULL, p_kind public.marketplace_kind DEFAULT NULL,
    p_limit int DEFAULT 50, p_offset int DEFAULT 0)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    q text := NULLIF(pg_catalog.btrim(COALESCE(p_query, '')), '');
    pat text;
BEGIN
    IF pg_catalog.char_length(COALESCE(q, '')) > 100 THEN
        RAISE EXCEPTION 'query_too_long' USING ERRCODE = '22023';
    END IF;
    pat := '%' || pg_catalog.replace(pg_catalog.replace(pg_catalog.replace(COALESCE(q, ''), '\', '\\'), '%', '\%'), '_', '\_') || '%';
    RETURN COALESCE((
        SELECT pg_catalog.jsonb_agg(item ORDER BY ord)
        FROM (
            SELECT pg_catalog.jsonb_build_object('slug', l.slug, 'kind', l.kind, 'name', v.name, 'summary', v.summary,
                       'publisher_name', v.publisher_name, 'version', v.version, 'install_count', l.install_count,
                       'updated_at', v.decided_at) AS item,
                   pg_catalog.row_number() OVER (ORDER BY l.install_count DESC, v.decided_at DESC, l.slug) AS ord
            FROM public.marketplace_listings l
            JOIN public.marketplace_versions v ON v.id = l.live_version_id
            WHERE l.status = 'live'
              AND (p_kind IS NULL OR l.kind = p_kind)
              AND (q IS NULL OR l.slug ILIKE pat OR v.name ILIKE pat OR v.summary ILIKE pat)
            ORDER BY ord
            LIMIT LEAST(GREATEST(COALESCE(p_limit, 50), 1), 100) OFFSET GREATEST(COALESCE(p_offset, 0), 0)
        ) s), '[]'::jsonb);
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_search(text, public.marketplace_kind, int, int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.marketplace_search(text, public.marketplace_kind, int, int) TO anon, authenticated, service_role;

-- A live listing for anyone; its publisher and platform admins also see status, the pending version and the review note.
CREATE FUNCTION public.marketplace_get(p_slug text)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    l public.marketplace_listings%ROWTYPE;
    live public.marketplace_versions%ROWTYPE;
    pending public.marketplace_versions%ROWTYPE;
    owner_view boolean;
BEGIN
    SELECT * INTO l FROM public.marketplace_listings WHERE slug = p_slug;
    owner_view := FOUND AND auth.uid() IS NOT NULL AND (l.publisher_id = auth.uid() OR public.is_user_admin(auth.uid()));
    IF NOT FOUND OR (l.status <> 'live' AND NOT owner_view) THEN
        RETURN NULL;
    END IF;
    SELECT * INTO live FROM public.marketplace_versions WHERE id = l.live_version_id;
    SELECT * INTO pending FROM public.marketplace_versions WHERE id = l.pending_version_id;
    RETURN pg_catalog.jsonb_strip_nulls(pg_catalog.jsonb_build_object(
        'slug', l.slug, 'kind', l.kind, 'install_count', l.install_count, 'created_at', l.created_at,
        'live', CASE WHEN l.status = 'live' OR owner_view THEN
                    CASE WHEN live.id IS NOT NULL THEN public.marketplace_version_json(live, true) END END,
        'status', CASE WHEN owner_view THEN l.status END,
        'review_note', CASE WHEN owner_view THEN l.review_note END,
        'pending', CASE WHEN owner_view AND pending.id IS NOT NULL THEN public.marketplace_version_json(pending, true) END,
        'is_publisher', CASE WHEN owner_view THEN l.publisher_id = auth.uid() END));
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_get(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.marketplace_get(text) TO anon, authenticated, service_role;

-- ----------------------------------------------------------------------------
-- Publishing: a new listing, or a new version of the caller's own. Always to review.
-- ----------------------------------------------------------------------------
CREATE FUNCTION public.marketplace_submit(p_kind public.marketplace_kind, p_slug text, p_name text, p_summary text,
    p_description text, p_publisher_name text, p_content jsonb)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    me uuid := auth.uid();
    l public.marketplace_listings%ROWTYPE;
    next_version int;
    vid uuid;
BEGIN
    IF me IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    PERFORM public.marketplace_validate(p_kind, p_slug, p_name, p_summary, COALESCE(p_description, ''), p_publisher_name, p_content);
    IF (SELECT pg_catalog.count(*) FROM public.marketplace_versions
            WHERE created_by = me AND created_at > now() - interval '1 day') >= 20 THEN
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
    INSERT INTO public.marketplace_versions (listing_id, version, name, summary, description, publisher_name, content, created_by)
        VALUES (l.id, next_version, p_name, p_summary, COALESCE(p_description, ''), pg_catalog.btrim(p_publisher_name), p_content, me)
        RETURNING id INTO vid;
    UPDATE public.marketplace_listings SET pending_version_id = vid, review_note = NULL, updated_at = now(),
        -- A live listing keeps serving its approved version; anything else (rejected, withdrawn) waits for review unlisted.
        status = (CASE WHEN l.status = 'live' THEN 'live' ELSE 'pending_review' END)::public.marketplace_status
        WHERE id = l.id;
    INSERT INTO public.platform_audit_log (actor, action, detail)
        VALUES (me, 'marketplace.submit', pg_catalog.jsonb_build_object('slug', l.slug, 'kind', l.kind, 'version', next_version));
    RETURN pg_catalog.jsonb_build_object('slug', l.slug, 'version', next_version, 'status', 'pending_review');
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_submit(public.marketplace_kind, text, text, text, text, text, jsonb) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_submit(public.marketplace_kind, text, text, text, text, text, jsonb) TO authenticated, service_role;

-- The publisher's own: drop a pending version, or unlist the whole listing.
CREATE FUNCTION public.marketplace_withdraw(p_slug text, p_unlist boolean DEFAULT false)
RETURNS public.marketplace_status LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    l public.marketplace_listings%ROWTYPE;
    new_status public.marketplace_status;
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    SELECT * INTO l FROM public.marketplace_listings WHERE slug = p_slug FOR UPDATE;
    IF NOT FOUND OR l.publisher_id <> auth.uid() THEN
        RAISE EXCEPTION 'listing_not_found' USING ERRCODE = 'P0001';
    END IF;
    UPDATE public.marketplace_versions SET decision = 'withdrawn', decided_at = now()
        WHERE id = l.pending_version_id AND decision IS NULL;
    new_status := CASE
        WHEN l.status = 'suspended' THEN 'suspended'
        WHEN p_unlist OR l.live_version_id IS NULL THEN 'withdrawn'
        ELSE l.status END;
    UPDATE public.marketplace_listings SET pending_version_id = NULL, status = new_status, updated_at = now() WHERE id = l.id;
    INSERT INTO public.platform_audit_log (actor, action, detail)
        VALUES (auth.uid(), 'marketplace.withdraw', pg_catalog.jsonb_build_object('slug', l.slug, 'unlist', p_unlist));
    RETURN new_status;
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_withdraw(text, boolean) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_withdraw(text, boolean) TO authenticated, service_role;

CREATE FUNCTION public.marketplace_mine()
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    RETURN COALESCE((
        SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_strip_nulls(pg_catalog.jsonb_build_object(
                   'slug', l.slug, 'kind', l.kind, 'status', l.status, 'review_note', l.review_note,
                   'install_count', l.install_count, 'updated_at', l.updated_at,
                   'live', CASE WHEN lv.id IS NOT NULL THEN public.marketplace_version_json(lv, false) END,
                   'pending', CASE WHEN pv.id IS NOT NULL THEN public.marketplace_version_json(pv, false) END))
               ORDER BY l.updated_at DESC)
        FROM public.marketplace_listings l
        LEFT JOIN public.marketplace_versions lv ON lv.id = l.live_version_id
        LEFT JOIN public.marketplace_versions pv ON pv.id = l.pending_version_id
        WHERE l.publisher_id = auth.uid()), '[]'::jsonb);
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_mine() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_mine() TO authenticated, service_role;

-- An install by a signed-in person (counted once per person; a reinstall records the newer version).
CREATE FUNCTION public.marketplace_record_install(p_slug text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    l public.marketplace_listings%ROWTYPE;
    v public.marketplace_versions%ROWTYPE;
    inserted boolean;
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    SELECT * INTO l FROM public.marketplace_listings WHERE slug = p_slug AND status = 'live';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'listing_not_found' USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO v FROM public.marketplace_versions WHERE id = l.live_version_id;
    INSERT INTO public.marketplace_installs (listing_id, user_id, version_id)
        VALUES (l.id, auth.uid(), v.id)
        ON CONFLICT (listing_id, user_id) DO UPDATE SET version_id = excluded.version_id, installed_at = now()
        RETURNING (xmax = 0) INTO inserted;
    IF inserted THEN
        UPDATE public.marketplace_listings SET install_count = install_count + 1 WHERE id = l.id;
    END IF;
    RETURN pg_catalog.jsonb_build_object('slug', l.slug, 'kind', l.kind, 'version', public.marketplace_version_json(v, true));
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_record_install(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_record_install(text) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- Platform review (is_user_admin only).
-- ----------------------------------------------------------------------------
CREATE FUNCTION public.marketplace_pending()
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_user_admin(auth.uid()) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    RETURN COALESCE((
        SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_strip_nulls(pg_catalog.jsonb_build_object(
                   'slug', l.slug, 'kind', l.kind, 'status', l.status, 'publisher_email', u.email,
                   'pending', public.marketplace_version_json(pv, true),
                   'live', CASE WHEN lv.id IS NOT NULL THEN public.marketplace_version_json(lv, true) END))
               ORDER BY pv.created_at)
        FROM public.marketplace_listings l
        JOIN public.marketplace_versions pv ON pv.id = l.pending_version_id
        LEFT JOIN public.marketplace_versions lv ON lv.id = l.live_version_id
        LEFT JOIN auth.users u ON u.id = l.publisher_id
        WHERE l.status <> 'suspended'), '[]'::jsonb);
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_pending() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_pending() TO authenticated, service_role;

-- Approve or reject one pending version (by id, so a newer submission is never approved unseen).
CREATE FUNCTION public.marketplace_review(p_version_id uuid, p_approve boolean, p_reason text DEFAULT NULL)
RETURNS public.marketplace_status LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    note text := NULLIF(pg_catalog.btrim(COALESCE(p_reason, '')), '');
    v public.marketplace_versions%ROWTYPE;
    l public.marketplace_listings%ROWTYPE;
    new_status public.marketplace_status;
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_user_admin(auth.uid()) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    IF NOT p_approve AND note IS NULL THEN
        RAISE EXCEPTION 'reason_required' USING ERRCODE = 'P0001';
    END IF;
    IF pg_catalog.char_length(COALESCE(note, '')) > 500 THEN
        RAISE EXCEPTION 'reason_too_long' USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO v FROM public.marketplace_versions WHERE id = p_version_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'version_not_found' USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO l FROM public.marketplace_listings WHERE id = v.listing_id FOR UPDATE;
    IF l.pending_version_id IS DISTINCT FROM v.id OR l.status = 'suspended' THEN
        RAISE EXCEPTION 'version_not_pending_review' USING ERRCODE = 'P0001';
    END IF;
    UPDATE public.marketplace_versions
        SET decision = CASE WHEN p_approve THEN 'approved' ELSE 'rejected' END,
            decided_by = auth.uid(), decided_at = now(), reason = note
        WHERE id = v.id;
    IF p_approve THEN
        new_status := 'live';
        UPDATE public.marketplace_listings SET live_version_id = v.id, pending_version_id = NULL, status = 'live',
            review_note = NULL, updated_at = now() WHERE id = l.id;
    ELSE
        new_status := CASE WHEN l.live_version_id IS NOT NULL AND l.status = 'live' THEN 'live' ELSE 'rejected' END;
        UPDATE public.marketplace_listings SET pending_version_id = NULL, status = new_status, review_note = note,
            updated_at = now() WHERE id = l.id;
    END IF;
    INSERT INTO public.platform_audit_log (actor, action, target_user, detail)
        VALUES (auth.uid(), CASE WHEN p_approve THEN 'marketplace.approve' ELSE 'marketplace.reject' END, l.publisher_id,
                pg_catalog.jsonb_strip_nulls(pg_catalog.jsonb_build_object('slug', l.slug, 'version', v.version, 'reason', note)));
    RETURN new_status;
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_review(uuid, boolean, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_review(uuid, boolean, text) TO authenticated, service_role;

-- Take a listing down (or restore it). A reason is required to suspend.
CREATE FUNCTION public.marketplace_suspend(p_slug text, p_suspend boolean, p_reason text DEFAULT NULL)
RETURNS public.marketplace_status LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    reason text := NULLIF(pg_catalog.btrim(COALESCE(p_reason, '')), '');
    l public.marketplace_listings%ROWTYPE;
    new_status public.marketplace_status;
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_user_admin(auth.uid()) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    IF p_suspend AND reason IS NULL THEN
        RAISE EXCEPTION 'reason_required' USING ERRCODE = 'P0001';
    END IF;
    IF pg_catalog.char_length(COALESCE(reason, '')) > 500 THEN
        RAISE EXCEPTION 'reason_too_long' USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO l FROM public.marketplace_listings WHERE slug = p_slug FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'listing_not_found' USING ERRCODE = 'P0001';
    END IF;
    IF p_suspend THEN
        new_status := 'suspended';
    ELSIF l.status <> 'suspended' THEN
        RAISE EXCEPTION 'listing_not_suspended' USING ERRCODE = 'P0001';
    ELSE
        new_status := CASE WHEN l.live_version_id IS NOT NULL THEN 'live'
                           WHEN l.pending_version_id IS NOT NULL THEN 'pending_review' ELSE 'withdrawn' END;
    END IF;
    UPDATE public.marketplace_listings SET status = new_status,
        review_note = CASE WHEN p_suspend THEN reason ELSE NULL END, updated_at = now() WHERE id = l.id;
    INSERT INTO public.platform_audit_log (actor, action, target_user, detail)
        VALUES (auth.uid(), CASE WHEN p_suspend THEN 'marketplace.suspend' ELSE 'marketplace.unsuspend' END, l.publisher_id,
                pg_catalog.jsonb_strip_nulls(pg_catalog.jsonb_build_object('slug', l.slug, 'reason', reason)));
    RETURN new_status;
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_suspend(text, boolean, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_suspend(text, boolean, text) TO authenticated, service_role;
