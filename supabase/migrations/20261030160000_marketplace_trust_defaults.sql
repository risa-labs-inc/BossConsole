-- Marketplace trust and defaults. Trusted publishers (and platform admins) may publish without waiting for review; the
-- version goes live as 'unreviewed' and an admin can mark it reviewed later. A listing is "reviewed" iff its live version
-- is 'approved'. Platform admins can also make skill listings defaults of a managed agent, served to the agent's runtime.

ALTER TABLE public.marketplace_versions DROP CONSTRAINT marketplace_versions_decision_check;
ALTER TABLE public.marketplace_versions ADD CONSTRAINT marketplace_versions_decision_check
    CHECK (decision IN ('approved', 'unreviewed', 'rejected', 'superseded', 'withdrawn'));

CREATE TABLE public.marketplace_trusted_publishers (
    user_id    uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    note       text,
    granted_by uuid REFERENCES auth.users(id) ON DELETE SET NULL,
    granted_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE public.marketplace_trusted_publishers ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.marketplace_trusted_publishers FROM PUBLIC, anon, authenticated, service_role;

CREATE TABLE public.marketplace_agent_defaults (
    agent_id   uuid NOT NULL REFERENCES public.agents(id) ON DELETE CASCADE,
    listing_id uuid NOT NULL REFERENCES public.marketplace_listings(id) ON DELETE CASCADE,
    added_by   uuid REFERENCES auth.users(id) ON DELETE SET NULL,
    added_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (agent_id, listing_id)
);
CREATE INDEX idx_marketplace_agent_defaults_listing ON public.marketplace_agent_defaults (listing_id);
ALTER TABLE public.marketplace_agent_defaults ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.marketplace_agent_defaults FROM PUBLIC, anon, authenticated, service_role;

CREATE FUNCTION public.marketplace_can_skip_review()
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT auth.uid() IS NOT NULL AND (
        public.is_user_admin(auth.uid())
        OR EXISTS (SELECT 1 FROM public.marketplace_trusted_publishers t WHERE t.user_id = auth.uid()));
$$;
REVOKE ALL ON FUNCTION public.marketplace_can_skip_review() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_can_skip_review() TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- Public reads gain `reviewed` (the live version is approved).
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.marketplace_search(p_query text DEFAULT NULL, p_kind public.marketplace_kind DEFAULT NULL,
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
                       'updated_at', v.decided_at, 'reviewed', v.decision = 'approved') AS item,
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
-- CREATE OR REPLACE re-runs the anon-grant guard, so the public reads are granted again.
GRANT EXECUTE ON FUNCTION public.marketplace_search(text, public.marketplace_kind, int, int) TO anon, authenticated, service_role;

CREATE OR REPLACE FUNCTION public.marketplace_get(p_slug text)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    l public.marketplace_listings%ROWTYPE;
    live public.marketplace_versions%ROWTYPE;
    pending public.marketplace_versions%ROWTYPE;
    owner_view boolean;
BEGIN
    SELECT * INTO l FROM public.marketplace_listings WHERE slug = p_slug;
    owner_view := FOUND AND auth.uid() IS NOT NULL AND (l.publisher_id = auth.uid() OR public.is_platform_reader(auth.uid()));
    IF NOT FOUND OR (l.status <> 'live' AND NOT owner_view) THEN
        RETURN NULL;
    END IF;
    SELECT * INTO live FROM public.marketplace_versions WHERE id = l.live_version_id;
    SELECT * INTO pending FROM public.marketplace_versions WHERE id = l.pending_version_id;
    RETURN pg_catalog.jsonb_strip_nulls(pg_catalog.jsonb_build_object(
        'slug', l.slug, 'kind', l.kind, 'install_count', l.install_count, 'created_at', l.created_at,
        'live', CASE WHEN l.status = 'live' OR owner_view THEN
                    CASE WHEN live.id IS NOT NULL THEN public.marketplace_version_json(live, true) END END,
        'reviewed', CASE WHEN live.id IS NOT NULL AND (l.status = 'live' OR owner_view) THEN live.decision = 'approved' END,
        'status', CASE WHEN owner_view THEN l.status END,
        'review_note', CASE WHEN owner_view THEN l.review_note END,
        'pending', CASE WHEN owner_view AND pending.id IS NOT NULL THEN public.marketplace_version_json(pending, true) END,
        'is_publisher', CASE WHEN owner_view THEN l.publisher_id = auth.uid() END));
END;
$$;
GRANT EXECUTE ON FUNCTION public.marketplace_get(text) TO anon, authenticated, service_role;

CREATE OR REPLACE FUNCTION public.marketplace_mine()
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    RETURN COALESCE((
        SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_strip_nulls(pg_catalog.jsonb_build_object(
                   'slug', l.slug, 'kind', l.kind, 'status', l.status, 'review_note', l.review_note,
                   'install_count', l.install_count, 'updated_at', l.updated_at,
                   'reviewed', CASE WHEN lv.id IS NOT NULL THEN lv.decision = 'approved' END,
                   'live', CASE WHEN lv.id IS NOT NULL THEN public.marketplace_version_json(lv, false) END,
                   'pending', CASE WHEN pv.id IS NOT NULL THEN public.marketplace_version_json(pv, false) END))
               ORDER BY l.updated_at DESC)
        FROM public.marketplace_listings l
        LEFT JOIN public.marketplace_versions lv ON lv.id = l.live_version_id
        LEFT JOIN public.marketplace_versions pv ON pv.id = l.pending_version_id
        WHERE l.publisher_id = auth.uid()), '[]'::jsonb);
END;
$$;

CREATE OR REPLACE FUNCTION public.marketplace_record_install(p_slug text)
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
    RETURN pg_catalog.jsonb_build_object('slug', l.slug, 'kind', l.kind, 'version', public.marketplace_version_json(v, true),
        'reviewed', v.decision = 'approved');
END;
$$;

CREATE OR REPLACE FUNCTION public.marketplace_pending()
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_platform_reader(auth.uid()) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    RETURN COALESCE((
        SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_strip_nulls(pg_catalog.jsonb_build_object(
                   'slug', l.slug, 'kind', l.kind, 'status', l.status, 'publisher_email', u.email,
                   'pending', public.marketplace_version_json(pv, true),
                   'reviewed', CASE WHEN lv.id IS NOT NULL THEN lv.decision = 'approved' END,
                   'live', CASE WHEN lv.id IS NOT NULL THEN public.marketplace_version_json(lv, true) END))
               ORDER BY pv.created_at)
        FROM public.marketplace_listings l
        JOIN public.marketplace_versions pv ON pv.id = l.pending_version_id
        LEFT JOIN public.marketplace_versions lv ON lv.id = l.live_version_id
        LEFT JOIN auth.users u ON u.id = l.publisher_id
        WHERE l.status <> 'suspended'), '[]'::jsonb);
END;
$$;

-- ----------------------------------------------------------------------------
-- Publishing, with an optional skip of review for trusted publishers and admins.
-- ----------------------------------------------------------------------------
DROP FUNCTION public.marketplace_submit(public.marketplace_kind, text, text, text, text, text, jsonb);
CREATE FUNCTION public.marketplace_submit(p_kind public.marketplace_kind, p_slug text, p_name text, p_summary text,
    p_description text, p_publisher_name text, p_content jsonb, p_skip_review boolean DEFAULT false)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    me uuid := auth.uid();
    skip boolean := COALESCE(p_skip_review, false);
    l public.marketplace_listings%ROWTYPE;
    live_decision text;
    next_version int;
    vid uuid;
BEGIN
    IF me IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    IF skip AND NOT public.marketplace_can_skip_review() THEN
        RAISE EXCEPTION 'review_skip_not_allowed' USING ERRCODE = '42501';
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
    INSERT INTO public.marketplace_versions (listing_id, version, name, summary, description, publisher_name, content, created_by,
                                             decision, decided_by, decided_at)
        VALUES (l.id, next_version, p_name, p_summary, COALESCE(p_description, ''), pg_catalog.btrim(p_publisher_name), p_content, me,
                CASE WHEN skip THEN 'unreviewed' END, CASE WHEN skip THEN me END, CASE WHEN skip THEN now() END)
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
REVOKE ALL ON FUNCTION public.marketplace_submit(public.marketplace_kind, text, text, text, text, text, jsonb, boolean) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_submit(public.marketplace_kind, text, text, text, text, text, jsonb, boolean) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- Admin: unreviewed live versions, trusted publishers.
-- ----------------------------------------------------------------------------
CREATE FUNCTION public.marketplace_unreviewed()
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_platform_reader(auth.uid()) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    RETURN COALESCE((
        SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_strip_nulls(pg_catalog.jsonb_build_object(
                   'slug', l.slug, 'kind', l.kind, 'publisher_email', u.email,
                   'live', public.marketplace_version_json(lv, true), 'published_at', lv.decided_at))
               ORDER BY lv.decided_at, l.slug)
        FROM public.marketplace_listings l
        JOIN public.marketplace_versions lv ON lv.id = l.live_version_id
        LEFT JOIN auth.users u ON u.id = l.publisher_id
        WHERE l.status = 'live' AND lv.decision = 'unreviewed'), '[]'::jsonb);
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_unreviewed() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_unreviewed() TO authenticated, service_role;

CREATE FUNCTION public.marketplace_mark_reviewed(p_slug text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    l public.marketplace_listings%ROWTYPE;
    v public.marketplace_versions%ROWTYPE;
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_user_admin(auth.uid()) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    SELECT * INTO l FROM public.marketplace_listings WHERE slug = p_slug FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'listing_not_found' USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO v FROM public.marketplace_versions WHERE id = l.live_version_id;
    IF NOT FOUND OR v.decision IS DISTINCT FROM 'unreviewed' THEN
        RAISE EXCEPTION 'not_unreviewed' USING ERRCODE = 'P0001';
    END IF;
    UPDATE public.marketplace_versions SET decision = 'approved', decided_by = auth.uid(), decided_at = now() WHERE id = v.id;
    INSERT INTO public.platform_audit_log (actor, action, target_user, detail)
        VALUES (auth.uid(), 'marketplace.mark_reviewed', l.publisher_id,
                pg_catalog.jsonb_build_object('slug', l.slug, 'version', v.version));
    RETURN pg_catalog.jsonb_build_object('slug', l.slug, 'version', v.version, 'reviewed', true);
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_mark_reviewed(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_mark_reviewed(text) TO authenticated, service_role;

-- Revoking trust does not unpublish anything.
CREATE FUNCTION public.marketplace_trust_set(p_email text, p_trusted boolean, p_note text DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    note text := NULLIF(pg_catalog.btrim(COALESCE(p_note, '')), '');
    target auth.users%ROWTYPE;
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_user_admin(auth.uid()) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    IF pg_catalog.char_length(COALESCE(note, '')) > 200 THEN
        RAISE EXCEPTION 'note_too_long' USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO target FROM auth.users WHERE pg_catalog.lower(email) = pg_catalog.lower(pg_catalog.btrim(COALESCE(p_email, '')));
    IF NOT FOUND THEN
        RAISE EXCEPTION 'user_not_found' USING ERRCODE = 'P0001';
    END IF;
    IF COALESCE(p_trusted, false) THEN
        INSERT INTO public.marketplace_trusted_publishers (user_id, note, granted_by) VALUES (target.id, note, auth.uid())
            ON CONFLICT (user_id) DO UPDATE SET note = excluded.note, granted_by = excluded.granted_by, granted_at = now();
    ELSE
        DELETE FROM public.marketplace_trusted_publishers WHERE user_id = target.id;
    END IF;
    INSERT INTO public.platform_audit_log (actor, action, target_user, detail)
        VALUES (auth.uid(), CASE WHEN COALESCE(p_trusted, false) THEN 'marketplace.trust_grant' ELSE 'marketplace.trust_revoke' END,
                target.id, pg_catalog.jsonb_strip_nulls(pg_catalog.jsonb_build_object('note', note)));
    RETURN pg_catalog.jsonb_build_object('user_id', target.id, 'email', target.email, 'trusted', COALESCE(p_trusted, false));
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_trust_set(text, boolean, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_trust_set(text, boolean, text) TO authenticated, service_role;

CREATE FUNCTION public.marketplace_trusted_list()
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_platform_reader(auth.uid()) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    RETURN COALESCE((
        SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
                   'user_id', t.user_id, 'email', u.email, 'note', t.note, 'granted_at', t.granted_at,
                   'granted_by_email', g.email) ORDER BY t.granted_at DESC, t.user_id)
        FROM public.marketplace_trusted_publishers t
        LEFT JOIN auth.users u ON u.id = t.user_id
        LEFT JOIN auth.users g ON g.id = t.granted_by), '[]'::jsonb);
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_trusted_list() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_trusted_list() TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- Agent defaults: skills every install of a managed agent gets.
-- ----------------------------------------------------------------------------
CREATE FUNCTION public.marketplace_agent_defaults(p_agent_id uuid)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_platform_reader(auth.uid()) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    RETURN COALESCE((
        SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
                   'slug', l.slug, 'name', COALESCE(lv.name, pv.name), 'version', COALESCE(lv.version, pv.version),
                   'reviewed', COALESCE(lv.decision = 'approved', false), 'status', l.status, 'added_at', d.added_at)
               ORDER BY l.slug)
        FROM public.marketplace_agent_defaults d
        JOIN public.marketplace_listings l ON l.id = d.listing_id
        LEFT JOIN public.marketplace_versions lv ON lv.id = l.live_version_id
        LEFT JOIN public.marketplace_versions pv ON pv.id = l.pending_version_id
        WHERE d.agent_id = p_agent_id), '[]'::jsonb);
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_agent_defaults(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_agent_defaults(uuid) TO authenticated, service_role;

CREATE FUNCTION public.marketplace_agent_default_set(p_agent_id uuid, p_slug text, p_on boolean)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    a public.agents%ROWTYPE;
    l public.marketplace_listings%ROWTYPE;
    changed boolean;
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_user_admin(auth.uid()) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    SELECT * INTO a FROM public.agents WHERE id = p_agent_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'agent_not_found' USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO l FROM public.marketplace_listings WHERE slug = p_slug;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'listing_not_found' USING ERRCODE = 'P0001';
    END IF;
    IF l.kind <> 'skill' THEN
        RAISE EXCEPTION 'not_a_skill' USING ERRCODE = 'P0001';
    END IF;
    IF COALESCE(p_on, false) THEN
        IF l.status <> 'live' THEN
            RAISE EXCEPTION 'listing_not_live' USING ERRCODE = 'P0001';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM public.marketplace_agent_defaults WHERE agent_id = a.id AND listing_id = l.id)
           AND (SELECT pg_catalog.count(*) FROM public.marketplace_agent_defaults WHERE agent_id = a.id) >= 50 THEN
            RAISE EXCEPTION 'too_many_defaults' USING ERRCODE = 'P0001';
        END IF;
        INSERT INTO public.marketplace_agent_defaults (agent_id, listing_id, added_by) VALUES (a.id, l.id, auth.uid())
            ON CONFLICT DO NOTHING;
    ELSE
        DELETE FROM public.marketplace_agent_defaults WHERE agent_id = a.id AND listing_id = l.id;
    END IF;
    GET DIAGNOSTICS changed = ROW_COUNT;
    IF changed THEN
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
            VALUES (a.org_id, a.id, auth.uid(),
                    CASE WHEN COALESCE(p_on, false) THEN 'agent.marketplace_default_added' ELSE 'agent.marketplace_default_removed' END,
                    pg_catalog.jsonb_build_object('slug', l.slug));
    END IF;
    RETURN public.marketplace_agent_defaults(a.id);
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_agent_default_set(uuid, text, boolean) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_agent_default_set(uuid, text, boolean) TO authenticated, service_role;

CREATE FUNCTION public.marketplace_listing_agents(p_slug text)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_platform_reader(auth.uid()) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    RETURN COALESCE((
        SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
                   'agent_id', a.id, 'slug', a.slug, 'name', a.name, 'org_id', a.org_id, 'added_at', d.added_at)
               ORDER BY a.slug)
        FROM public.marketplace_listings l
        JOIN public.marketplace_agent_defaults d ON d.listing_id = l.id
        JOIN public.agents a ON a.id = d.agent_id
        WHERE l.slug = p_slug), '[]'::jsonb);
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_listing_agents(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_listing_agents(text) TO authenticated, service_role;

-- The runtime read: the default skills a person's agent instance loads.
CREATE FUNCTION public.marketplace_agent_skills(p_plugin_id text)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    me uuid := auth.uid();
    a public.agents%ROWTYPE;
BEGIN
    IF me IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    SELECT * INTO a FROM public.agents WHERE plugin_id = p_plugin_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'agent_not_found' USING ERRCODE = 'P0001';
    END IF;
    IF NOT (
        EXISTS (SELECT 1 FROM public.agent_installs i WHERE i.agent_id = a.id AND i.user_id = me AND i.state <> 'removed')
        OR public.has_agent_permission(a.id, 'agents.edit')
        OR EXISTS (SELECT 1 FROM public.plugins p WHERE p.plugin_id = a.plugin_id AND public.user_can_install_plugin(me, p.id))
        OR public.is_user_admin(me)
    ) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    RETURN pg_catalog.jsonb_build_object('skills', COALESCE((
        SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
                   'slug', l.slug, 'name', v.name, 'summary', v.summary, 'version', v.version,
                   'reviewed', v.decision = 'approved', 'markdown', v.content->>'markdown') ORDER BY l.slug)
        FROM public.marketplace_agent_defaults d
        JOIN public.marketplace_listings l ON l.id = d.listing_id
        JOIN public.marketplace_versions v ON v.id = l.live_version_id
        WHERE d.agent_id = a.id AND l.status = 'live' AND l.kind = 'skill'), '[]'::jsonb));
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_agent_skills(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_agent_skills(text) TO authenticated, service_role;
