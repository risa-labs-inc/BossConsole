-- Marketplace categories and tags: one admin-managed category and up to 8 free tags per version (reviewed with it).
-- Search filters by category or tag and matches tags; marketplace_categories() and marketplace_tags() are public reads.
-- Admins manage categories and can re-file a live listing without a new version (marketplace_set_metadata).
BEGIN;

CREATE TABLE public.marketplace_categories (
    slug        text PRIMARY KEY CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,39}$'),
    name        text NOT NULL CHECK (pg_catalog.char_length(name) BETWEEN 2 AND 40 AND name = pg_catalog.btrim(name)),
    description text NOT NULL DEFAULT '' CHECK (pg_catalog.char_length(description) <= 200),
    sort        int NOT NULL DEFAULT 100,
    created_at  timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE public.marketplace_categories ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.marketplace_categories FROM PUBLIC, anon, authenticated, service_role;

INSERT INTO public.marketplace_categories (slug, name, description, sort) VALUES
    ('development', 'Development', 'Writing, reviewing and shipping code.', 10),
    ('testing', 'Testing & quality', 'Tests, verification and QA.', 20),
    ('design', 'Design', 'Interfaces, themes and visual work.', 30),
    ('writing', 'Writing & communication', 'Updates, docs, messages and announcements.', 40),
    ('research', 'Research & analysis', 'Finding, reading and making sense of information.', 50),
    ('planning', 'Planning & productivity', 'Plans, decisions and getting work organised.', 60),
    ('documents', 'Data & documents', 'PDFs, spreadsheets, office files and data.', 70),
    ('automation', 'Web & automation', 'Browsing, web tasks and connecting services.', 80),
    ('security', 'Security', 'Reviews and checks that keep things safe.', 90),
    ('business', 'Business & marketing', 'Growth, SEO, sales and marketing.', 100),
    ('lifestyle', 'Lifestyle & shopping', 'Food, shopping, travel and everyday tasks.', 110)
ON CONFLICT (slug) DO NOTHING;

ALTER TABLE public.marketplace_versions
    ADD COLUMN category text REFERENCES public.marketplace_categories(slug) ON UPDATE CASCADE ON DELETE RESTRICT,
    ADD COLUMN tags text[] NOT NULL DEFAULT '{}' CHECK (pg_catalog.cardinality(tags) <= 8);
CREATE INDEX idx_marketplace_versions_category ON public.marketplace_versions (category);
CREATE INDEX idx_marketplace_versions_tags ON public.marketplace_versions USING gin (tags);

-- Lowercase, trimmed, spaces to dashes, deduplicated in order; at most 8 of up to 30 characters.
CREATE FUNCTION public.marketplace_norm_tags(p text[])
RETURNS text[] LANGUAGE plpgsql IMMUTABLE SET search_path = '' AS $$
DECLARE
    out text[] := '{}';
    t text;
BEGIN
    FOREACH t IN ARRAY COALESCE(p, '{}'::text[]) LOOP
        t := pg_catalog.regexp_replace(pg_catalog.lower(pg_catalog.btrim(COALESCE(t, ''))), '\s+', '-', 'g');
        CONTINUE WHEN t = '' OR t = ANY (out);
        IF t !~ '^[a-z0-9][a-z0-9.+#-]{0,29}$' THEN
            RAISE EXCEPTION 'invalid_tag' USING ERRCODE = '22023';
        END IF;
        out := out || t;
    END LOOP;
    IF pg_catalog.cardinality(out) > 8 THEN
        RAISE EXCEPTION 'too_many_tags' USING ERRCODE = '22023';
    END IF;
    RETURN out;
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_norm_tags(text[]) FROM PUBLIC, anon, authenticated, service_role;

-- Every version read carries its category and tags.
CREATE OR REPLACE FUNCTION public.marketplace_version_json(v public.marketplace_versions, p_with_content boolean)
RETURNS jsonb LANGUAGE sql IMMUTABLE SET search_path = '' AS $$
    SELECT pg_catalog.jsonb_strip_nulls(pg_catalog.jsonb_build_object(
        'id', v.id, 'version', v.version, 'name', v.name, 'summary', v.summary, 'description', v.description,
        'publisher_name', v.publisher_name, 'created_at', v.created_at, 'decision', v.decision, 'reason', v.reason,
        'category', v.category, 'tags', pg_catalog.to_jsonb(v.tags),
        'content', CASE WHEN p_with_content THEN v.content END))
$$;

-- Submit takes a category and tags; the 8-argument form is replaced.
DROP FUNCTION public.marketplace_submit(public.marketplace_kind, text, text, text, text, text, jsonb, boolean);
CREATE FUNCTION public.marketplace_submit(p_kind public.marketplace_kind, p_slug text, p_name text, p_summary text,
    p_description text, p_publisher_name text, p_content jsonb, p_skip_review boolean DEFAULT false,
    p_category text DEFAULT NULL, p_tags text[] DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    me uuid := auth.uid();
    skip boolean := COALESCE(p_skip_review, false);
    l public.marketplace_listings%ROWTYPE;
    live_decision text;
    next_version int;
    vid uuid;
    cat text := NULLIF(pg_catalog.btrim(COALESCE(p_category, '')), '');
    new_tags text[] := public.marketplace_norm_tags(p_tags);
BEGIN
    IF me IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    IF skip AND NOT public.marketplace_can_skip_review() THEN
        RAISE EXCEPTION 'review_skip_not_allowed' USING ERRCODE = '42501';
    END IF;
    PERFORM public.marketplace_validate(p_kind, p_slug, p_name, p_summary, COALESCE(p_description, ''), p_publisher_name, p_content);
    IF cat IS NOT NULL AND NOT EXISTS (SELECT 1 FROM public.marketplace_categories c WHERE c.slug = cat) THEN
        RAISE EXCEPTION 'invalid_category' USING ERRCODE = '22023';
    END IF;
    -- Platform admins and trusted publishers have no daily cap; everyone else gets 20 a day.
    IF NOT public.marketplace_can_skip_review() AND (SELECT pg_catalog.count(*) FROM public.marketplace_versions
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
REVOKE ALL ON FUNCTION public.marketplace_submit(public.marketplace_kind, text, text, text, text, text, jsonb, boolean, text, text[]) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_submit(public.marketplace_kind, text, text, text, text, text, jsonb, boolean, text, text[]) TO authenticated, service_role;

-- Search filters by category or tag and matches tags; the 4-argument form is replaced.
DROP FUNCTION public.marketplace_search(text, public.marketplace_kind, int, int);
CREATE FUNCTION public.marketplace_search(p_query text DEFAULT NULL, p_kind public.marketplace_kind DEFAULT NULL,
    p_limit int DEFAULT 50, p_offset int DEFAULT 0, p_category text DEFAULT NULL, p_tag text DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    q text := NULLIF(pg_catalog.btrim(COALESCE(p_query, '')), '');
    pat text;
    cat text := NULLIF(pg_catalog.btrim(COALESCE(p_category, '')), '');
    tag text := NULLIF(pg_catalog.lower(pg_catalog.btrim(COALESCE(p_tag, ''))), '');
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
                       'updated_at', v.decided_at, 'reviewed', v.decision = 'approved',
                       'category', v.category, 'tags', pg_catalog.to_jsonb(v.tags)) AS item,
                   pg_catalog.row_number() OVER (ORDER BY l.install_count DESC, v.decided_at DESC, l.slug) AS ord
            FROM public.marketplace_listings l
            JOIN public.marketplace_versions v ON v.id = l.live_version_id
            WHERE l.status = 'live'
              AND (p_kind IS NULL OR l.kind = p_kind)
              AND (q IS NULL OR l.slug ILIKE pat OR v.name ILIKE pat OR v.summary ILIKE pat
                   OR EXISTS (SELECT 1 FROM pg_catalog.unnest(v.tags) t WHERE t ILIKE pat))
              AND (cat IS NULL OR v.category = cat)
              AND (tag IS NULL OR tag = ANY (v.tags))
            ORDER BY ord
            LIMIT LEAST(GREATEST(COALESCE(p_limit, 50), 1), 100) OFFSET GREATEST(COALESCE(p_offset, 0), 0)
        ) s), '[]'::jsonb);
END;
$$;
GRANT EXECUTE ON FUNCTION public.marketplace_search(text, public.marketplace_kind, int, int, text, text) TO anon, authenticated, service_role;

-- Public: every category with how many live listings use it, in display order.
CREATE FUNCTION public.marketplace_categories()
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT COALESCE(pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object('slug', c.slug, 'name', c.name,
               'description', c.description, 'sort', c.sort, 'count', (
                   SELECT pg_catalog.count(*) FROM public.marketplace_listings l
                   JOIN public.marketplace_versions v ON v.id = l.live_version_id
                   WHERE l.status = 'live' AND v.category = c.slug)) ORDER BY c.sort, c.name), '[]'::jsonb)
    FROM public.marketplace_categories c;
$$;
REVOKE ALL ON FUNCTION public.marketplace_categories() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.marketplace_categories() TO anon, authenticated, service_role;

-- Public: the most used tags on live listings.
CREATE FUNCTION public.marketplace_tags(p_limit int DEFAULT 30)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT COALESCE(pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object('tag', t.tag, 'count', t.n) ORDER BY t.n DESC, t.tag), '[]'::jsonb)
    FROM (
        SELECT x.tag, pg_catalog.count(*) AS n
        FROM public.marketplace_listings l
        JOIN public.marketplace_versions v ON v.id = l.live_version_id
        CROSS JOIN LATERAL pg_catalog.unnest(v.tags) AS x(tag)
        WHERE l.status = 'live'
        GROUP BY x.tag
        ORDER BY n DESC, x.tag
        LIMIT LEAST(GREATEST(COALESCE(p_limit, 30), 1), 100)
    ) t;
$$;
REVOKE ALL ON FUNCTION public.marketplace_tags(int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.marketplace_tags(int) TO anon, authenticated, service_role;

-- Admin: create or update a category.
CREATE FUNCTION public.marketplace_category_set(p_slug text, p_name text, p_description text DEFAULT '', p_sort int DEFAULT 100)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    s text := pg_catalog.lower(pg_catalog.btrim(COALESCE(p_slug, '')));
    n text := pg_catalog.btrim(COALESCE(p_name, ''));
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_user_admin(auth.uid()) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    IF s !~ '^[a-z0-9][a-z0-9-]{1,39}$' THEN RAISE EXCEPTION 'invalid_category' USING ERRCODE = '22023'; END IF;
    IF pg_catalog.char_length(n) NOT BETWEEN 2 AND 40 THEN RAISE EXCEPTION 'invalid_name' USING ERRCODE = '22023'; END IF;
    IF pg_catalog.char_length(COALESCE(p_description, '')) > 200 THEN RAISE EXCEPTION 'invalid_description' USING ERRCODE = '22023'; END IF;
    INSERT INTO public.marketplace_categories (slug, name, description, sort)
        VALUES (s, n, pg_catalog.btrim(COALESCE(p_description, '')), COALESCE(p_sort, 100))
        ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, sort = EXCLUDED.sort;
    INSERT INTO public.platform_audit_log (actor, action, detail)
        VALUES (auth.uid(), 'marketplace.category_set', pg_catalog.jsonb_build_object('slug', s, 'name', n));
    RETURN public.marketplace_categories();
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_category_set(text, text, text, int) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_category_set(text, text, text, int) TO authenticated, service_role;

-- Admin: delete an unused category.
CREATE FUNCTION public.marketplace_category_delete(p_slug text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_user_admin(auth.uid()) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    IF EXISTS (SELECT 1 FROM public.marketplace_versions v WHERE v.category = p_slug) THEN
        RAISE EXCEPTION 'category_in_use' USING ERRCODE = 'P0001';
    END IF;
    DELETE FROM public.marketplace_categories WHERE slug = p_slug;
    IF NOT FOUND THEN RAISE EXCEPTION 'invalid_category' USING ERRCODE = '22023'; END IF;
    INSERT INTO public.platform_audit_log (actor, action, detail)
        VALUES (auth.uid(), 'marketplace.category_delete', pg_catalog.jsonb_build_object('slug', p_slug));
    RETURN public.marketplace_categories();
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_category_delete(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_category_delete(text) TO authenticated, service_role;

-- Admin: re-file a listing's live version (and any pending one) under a category and tags, without a new version.
CREATE FUNCTION public.marketplace_set_metadata(p_slug text, p_category text, p_tags text[])
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    l public.marketplace_listings%ROWTYPE;
    cat text := NULLIF(pg_catalog.btrim(COALESCE(p_category, '')), '');
    new_tags text[] := public.marketplace_norm_tags(p_tags);
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_user_admin(auth.uid()) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    SELECT * INTO l FROM public.marketplace_listings WHERE slug = p_slug FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'listing_not_found' USING ERRCODE = 'P0001'; END IF;
    IF cat IS NOT NULL AND NOT EXISTS (SELECT 1 FROM public.marketplace_categories c WHERE c.slug = cat) THEN
        RAISE EXCEPTION 'invalid_category' USING ERRCODE = '22023';
    END IF;
    UPDATE public.marketplace_versions SET category = cat, tags = new_tags
        WHERE id IN (l.live_version_id, l.pending_version_id);
    INSERT INTO public.platform_audit_log (actor, action, detail)
        VALUES (auth.uid(), 'marketplace.set_metadata',
                pg_catalog.jsonb_build_object('slug', l.slug, 'category', cat, 'tags', pg_catalog.to_jsonb(new_tags)));
    RETURN public.marketplace_get(l.slug);
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_set_metadata(text, text, text[]) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_set_metadata(text, text, text[]) TO authenticated, service_role;

COMMIT;
