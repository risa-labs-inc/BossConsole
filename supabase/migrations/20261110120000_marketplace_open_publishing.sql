-- Marketplace open publishing: platform flag 'marketplace_open_publishing' (off by default). While on, every signed-in
-- publisher's submission goes live at once as 'unreviewed', exactly like a trusted publisher's skip. Admins can still mark it
-- reviewed or suspend it. The 20-a-day cap still applies to everyone who is not trusted. Fails closed: no row means off.
-- marketplace_submit as in 20261030180000, with only the skip decision changed.
BEGIN;
INSERT INTO public.platform_flags (key, value) VALUES ('marketplace_open_publishing', 'false'::jsonb)
    ON CONFLICT (key) DO NOTHING;

CREATE FUNCTION public.marketplace_open_publishing()
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT COALESCE((SELECT f.value = 'true'::jsonb FROM public.platform_flags f WHERE f.key = 'marketplace_open_publishing'), false);
$$;
REVOKE ALL ON FUNCTION public.marketplace_open_publishing() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_open_publishing() TO authenticated, service_role;

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

COMMIT;
