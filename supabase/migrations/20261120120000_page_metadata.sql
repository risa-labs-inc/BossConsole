-- Editable page metadata (title, description, preview image, robots, custom meta tags) for pages served through the Optimist core.
-- Agents keep it in brand.meta (validate_agent_brand gains a 'meta' key); Optimist and Max keep theirs in platform_page_meta,
-- read by anyone through site_page_meta and written by platform admins through set_platform_page_meta.
-- Rules live in page_meta_invalid_reason: NULL when valid, else meta_invalid / meta_<key>_invalid.

CREATE OR REPLACE FUNCTION public.page_meta_invalid_reason(p jsonb) RETURNS text
    LANGUAGE plpgsql IMMUTABLE SET search_path = '' AS $$
DECLARE
    -- control characters (newlines included) and angle brackets
    bad constant text := '[\u0001-\u001f\u007f-\u009f<>]';
    k text;
    v jsonb;
    t text;
    item jsonb;
    lim int;
    bad_val boolean;
BEGIN
    IF p IS NULL OR pg_catalog.jsonb_typeof(p) <> 'object' OR pg_catalog.octet_length(p::text) > 20000 THEN
        RETURN 'meta_invalid';
    END IF;
    FOR k, v IN SELECT * FROM pg_catalog.jsonb_each(p) LOOP
        IF k NOT IN ('title', 'description', 'keywords', 'image_url', 'image_alt', 'site_name', 'twitter_site',
                     'theme_color', 'robots', 'locale', 'custom') THEN
            RETURN 'meta_invalid';
        END IF;
        IF v = 'null'::jsonb THEN
            CONTINUE;
        END IF;
        IF k = 'custom' THEN
            IF pg_catalog.jsonb_typeof(v) <> 'array' OR pg_catalog.jsonb_array_length(v) > 20 THEN
                RETURN 'meta_custom_invalid';
            END IF;
            FOR item IN SELECT * FROM pg_catalog.jsonb_array_elements(v) LOOP
                IF pg_catalog.jsonb_typeof(item) <> 'object'
                   OR EXISTS (SELECT 1 FROM pg_catalog.jsonb_object_keys(item) x WHERE x NOT IN ('attr', 'key', 'content'))
                   OR pg_catalog.jsonb_typeof(item->'attr') IS DISTINCT FROM 'string'
                   OR (item->>'attr') NOT IN ('name', 'property')
                   OR pg_catalog.jsonb_typeof(item->'key') IS DISTINCT FROM 'string'
                   OR (item->>'key') !~ '^[A-Za-z][A-Za-z0-9:._-]{0,63}$'
                   OR pg_catalog.lower(item->>'key') IN ('viewport', 'referrer', 'color-scheme', 'robots', 'theme-color',
                                                         'description', 'keywords')
                   OR pg_catalog.lower(item->>'key') ~ '^(optimist-|og:|twitter:)'
                   OR pg_catalog.jsonb_typeof(item->'content') IS DISTINCT FROM 'string'
                   OR pg_catalog.char_length(pg_catalog.btrim(item->>'content')) NOT BETWEEN 1 AND 500
                   OR (item->>'content') ~ bad THEN
                    RETURN 'meta_custom_invalid';
                END IF;
            END LOOP;
            CONTINUE;
        END IF;
        IF pg_catalog.jsonb_typeof(v) <> 'string' THEN
            RETURN 'meta_' || k || '_invalid';
        END IF;
        t := v #>> '{}';
        lim := CASE k WHEN 'title' THEN 70 WHEN 'description' THEN 300 WHEN 'keywords' THEN 300
                      WHEN 'image_alt' THEN 200 ELSE 80 END;
        bad_val := CASE k
            WHEN 'image_url' THEN pg_catalog.char_length(t) > 2048 OR t !~ '^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[^\s"''<>\\]*)?$'
            WHEN 'twitter_site' THEN t !~ '^@[A-Za-z0-9_]{1,15}$'
            WHEN 'theme_color' THEN t !~ '^#[0-9a-fA-F]{6}$'
            WHEN 'robots' THEN t NOT IN ('index', 'noindex')
            WHEN 'locale' THEN t !~ '^[a-z]{2}(_[A-Z]{2})?$'
            ELSE pg_catalog.char_length(pg_catalog.btrim(t)) NOT BETWEEN 1 AND lim OR t ~ bad
            END;
        IF bad_val THEN
            RETURN 'meta_' || k || '_invalid';
        END IF;
    END LOOP;
    RETURN NULL;
END;
$$;

REVOKE ALL ON FUNCTION public.page_meta_invalid_reason(jsonb) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.page_meta_invalid_reason(jsonb) TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.validate_agent_brand(p_brand jsonb, p_org_id uuid DEFAULT NULL,
                                                      p_agent_id uuid DEFAULT NULL)
RETURNS text LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    hex constant text := '^#[0-9a-fA-F]{6}$';
    k text;
    v jsonb;
    s jsonb;
    bg jsonb;
    link jsonb;
BEGIN
    IF p_brand IS NULL OR pg_catalog.jsonb_typeof(p_brand) <> 'object' THEN
        RETURN 'brand_not_object';
    END IF;
    IF pg_catalog.octet_length(p_brand::text) > 100000 THEN
        RETURN 'brand_too_large';
    END IF;
    FOR k, v IN SELECT * FROM pg_catalog.jsonb_each(p_brand) LOOP
        CASE k
        WHEN 'name', 'short_name' THEN
            IF pg_catalog.jsonb_typeof(v) <> 'string'
               OR pg_catalog.char_length(v #>> '{}') > (ARRAY[80, 30])[pg_catalog.array_position(ARRAY['name', 'short_name'], k)] THEN
                RETURN k || '_invalid';
            END IF;
        WHEN 'accent', 'accent_dark' THEN
            -- "" and null both mean "unset"; the console writes "" for no accent.
            IF v <> 'null'::jsonb AND NOT (pg_catalog.jsonb_typeof(v) = 'string'
                                           AND ((v #>> '{}') = '' OR (v #>> '{}') ~ hex)) THEN
                RETURN k || '_invalid';
            END IF;
        WHEN 'favicon_url', 'apple_touch_url' THEN
            IF v <> 'null'::jsonb AND NOT (pg_catalog.jsonb_typeof(v) = 'string'
                                           AND public.agent_brand_url_ok(v #>> '{}', p_org_id, p_agent_id)) THEN
                RETURN k || '_invalid';
            END IF;
        WHEN 'logo' THEN
            IF v = 'null'::jsonb THEN
                CONTINUE;
            END IF;
            IF pg_catalog.jsonb_typeof(v) <> 'object'
               OR EXISTS (SELECT 1 FROM pg_catalog.jsonb_object_keys(v) x
                          WHERE x NOT IN ('url', 'shape', 'background', 'source', 'crop')) THEN
                RETURN 'logo_invalid';
            END IF;
            -- The uploaded original (re-cropped later without uploading again), in the agent's own folder.
            IF v ? 'source' AND v->'source' <> 'null'::jsonb
               AND NOT (pg_catalog.jsonb_typeof(v->'source') = 'string'
                        AND public.agent_brand_url_ok(v->>'source', p_org_id, p_agent_id)) THEN
                RETURN 'logo_source_invalid';
            END IF;
            -- Where the original sits in the cropper's frame: numbers and one flag, nothing else.
            IF v ? 'crop' AND v->'crop' <> 'null'::jsonb AND (
                   pg_catalog.jsonb_typeof(v->'crop') <> 'object'
                   OR EXISTS (SELECT 1 FROM pg_catalog.jsonb_object_keys(v->'crop') x
                              WHERE x NOT IN ('x', 'y', 'scale', 'padding', 'trim', 'frame'))
                   OR EXISTS (SELECT 1 FROM pg_catalog.jsonb_each(v->'crop') e
                              WHERE (e.key = 'trim' AND pg_catalog.jsonb_typeof(e.value) <> 'boolean')
                                 OR (e.key <> 'trim' AND (pg_catalog.jsonb_typeof(e.value) <> 'number'
                                                          OR pg_catalog.abs((e.value #>> '{}')::numeric) > 100000)))
                   OR COALESCE((v->'crop'->>'scale')::numeric, 1) <= 0
                   OR COALESCE((v->'crop'->>'padding')::numeric, 0) NOT BETWEEN 0 AND 0.4
                   OR COALESCE((v->'crop'->>'frame')::numeric, 280) <= 0) THEN
                RETURN 'logo_crop_invalid';
            END IF;
            IF pg_catalog.jsonb_typeof(v->'url') IS DISTINCT FROM 'string'
               OR NOT public.agent_brand_url_ok(v->>'url', p_org_id, p_agent_id) THEN
                RETURN 'logo_url_invalid';
            END IF;
            IF (v->>'shape') IS NULL OR pg_catalog.jsonb_typeof(v->'shape') <> 'string'
               OR (v->>'shape') NOT IN ('square', 'rounded', 'circle') THEN
                RETURN 'logo_shape_invalid';
            END IF;
            IF v ? 'background' AND v->'background' <> 'null'::jsonb
               AND NOT (pg_catalog.jsonb_typeof(v->'background') = 'string' AND (v->>'background') ~ hex) THEN
                RETURN 'logo_background_invalid';
            END IF;
        WHEN 'signin' THEN
            s := v;
            IF pg_catalog.jsonb_typeof(s) <> 'object'
               OR EXISTS (SELECT 1 FROM pg_catalog.jsonb_object_keys(s) x
                          WHERE x NOT IN ('mode', 'layout', 'headline', 'subtext', 'notice', 'background',
                                          'show_powered_by', 'footer_links', 'custom_css', 'custom_html')) THEN
                RETURN 'signin_invalid';
            END IF;
            IF pg_catalog.jsonb_typeof(s->'mode') IS DISTINCT FROM 'string'
               OR (s->>'mode') NOT IN ('standard', 'custom_html') THEN
                RETURN 'signin_mode_invalid';
            END IF;
            IF s ? 'layout' AND (pg_catalog.jsonb_typeof(s->'layout') <> 'string'
                                 OR (s->>'layout') NOT IN ('centered', 'split', 'minimal')) THEN
                RETURN 'signin_layout_invalid';
            END IF;
            IF s ? 'headline' AND (pg_catalog.jsonb_typeof(s->'headline') <> 'string'
                                   OR pg_catalog.char_length(s->>'headline') > 80) THEN
                RETURN 'signin_headline_invalid';
            END IF;
            IF s ? 'subtext' AND (pg_catalog.jsonb_typeof(s->'subtext') <> 'string'
                                  OR pg_catalog.char_length(s->>'subtext') > 240) THEN
                RETURN 'signin_subtext_invalid';
            END IF;
            -- Plain text under the form (a compliance line, say); the page renders it as text, never markup.
            IF s ? 'notice' AND (pg_catalog.jsonb_typeof(s->'notice') <> 'string'
                                 OR pg_catalog.char_length(s->>'notice') > 300) THEN
                RETURN 'signin_notice_invalid';
            END IF;
            IF s ? 'show_powered_by' AND pg_catalog.jsonb_typeof(s->'show_powered_by') <> 'boolean' THEN
                RETURN 'signin_show_powered_by_invalid';
            END IF;
            IF s ? 'background' THEN
                bg := s->'background';
                IF pg_catalog.jsonb_typeof(bg) <> 'object'
                   OR EXISTS (SELECT 1 FROM pg_catalog.jsonb_object_keys(bg) x WHERE x NOT IN ('type', 'image_url'))
                   OR pg_catalog.jsonb_typeof(bg->'type') IS DISTINCT FROM 'string'
                   OR (bg->>'type') NOT IN ('plain', 'gradient', 'image') THEN
                    RETURN 'signin_background_invalid';
                END IF;
                IF (bg->>'type') = 'image' AND (pg_catalog.jsonb_typeof(bg->'image_url') IS DISTINCT FROM 'string'
                                                OR NOT public.agent_brand_url_ok(bg->>'image_url', p_org_id, p_agent_id)) THEN
                    RETURN 'signin_background_image_invalid';
                END IF;
                IF (bg->>'type') <> 'image' AND bg ? 'image_url' AND bg->'image_url' <> 'null'::jsonb THEN
                    RETURN 'signin_background_image_invalid';
                END IF;
            END IF;
            IF s ? 'footer_links' THEN
                IF pg_catalog.jsonb_typeof(s->'footer_links') <> 'array'
                   OR pg_catalog.jsonb_array_length(s->'footer_links') > 5 THEN
                    RETURN 'signin_footer_links_invalid';
                END IF;
                FOR link IN SELECT * FROM pg_catalog.jsonb_array_elements(s->'footer_links') LOOP
                    IF pg_catalog.jsonb_typeof(link) <> 'object'
                       OR EXISTS (SELECT 1 FROM pg_catalog.jsonb_object_keys(link) x WHERE x NOT IN ('label', 'url'))
                       OR pg_catalog.jsonb_typeof(link->'label') IS DISTINCT FROM 'string'
                       OR pg_catalog.btrim(link->>'label') = '' OR pg_catalog.char_length(link->>'label') > 40
                       OR pg_catalog.jsonb_typeof(link->'url') IS DISTINCT FROM 'string'
                       OR pg_catalog.char_length(link->>'url') > 2048
                       OR (link->>'url') !~ '^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[^\s"''<>\\]*)?$' THEN
                        RETURN 'signin_footer_link_invalid';
                    END IF;
                END LOOP;
            END IF;
            IF s ? 'custom_css' AND (pg_catalog.jsonb_typeof(s->'custom_css') <> 'string'
                                     OR pg_catalog.char_length(s->>'custom_css') > 20000) THEN
                RETURN 'signin_custom_css_invalid';
            END IF;
            IF s ? 'custom_html' AND (pg_catalog.jsonb_typeof(s->'custom_html') <> 'string'
                                      OR pg_catalog.char_length(s->>'custom_html') > 50000) THEN
                RETURN 'signin_custom_html_invalid';
            END IF;
            -- The real form mounts at exactly one <agent-signin></agent-signin> (the whole form), or the page is the
            -- org's own template whose email form is exactly one <agent-email-form>; never both.
            IF (s->>'mode') = 'custom_html' AND (
                   (s->>'custom_html') IS NULL
                   OR NOT (
                       (public.agent_signin_tag_count(s->>'custom_html', '<agent-signin') = 1
                        AND pg_catalog.strpos(pg_catalog.lower(s->>'custom_html'), '<agent-signin></agent-signin>') > 0
                        AND public.agent_signin_tag_count(s->>'custom_html', '<agent-email-form') = 0)
                       OR (public.agent_signin_tag_count(s->>'custom_html', '<agent-signin') = 0
                           AND public.agent_signin_tag_count(s->>'custom_html', '<agent-email-form') = 1))) THEN
                RETURN 'signin_custom_html_mount_invalid';
            END IF;
        WHEN 'meta' THEN
            IF v <> 'null'::jsonb AND public.page_meta_invalid_reason(v) IS NOT NULL THEN
                RETURN public.page_meta_invalid_reason(v);
            END IF;
        ELSE
            RETURN 'brand_unknown_key:' || pg_catalog.left(k, 40);
        END CASE;
    END LOOP;
    RETURN NULL;
END;
$$;

CREATE TABLE IF NOT EXISTS public.platform_page_meta (
    site       text PRIMARY KEY CHECK (site IN ('optimist', 'max')),
    meta       jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (public.page_meta_invalid_reason(meta) IS NULL),
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid
);
INSERT INTO public.platform_page_meta (site) VALUES ('optimist'), ('max') ON CONFLICT DO NOTHING;
ALTER TABLE public.platform_page_meta ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.platform_page_meta FROM PUBLIC, anon, authenticated;
GRANT SELECT ON public.platform_page_meta TO service_role;

CREATE OR REPLACE FUNCTION public.platform_page_meta_audit() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF NEW.meta IS DISTINCT FROM OLD.meta THEN
        INSERT INTO public.platform_audit_log (actor, action, detail)
            VALUES (auth.uid(), 'page_meta.change',
                    pg_catalog.jsonb_build_object('site', NEW.site, 'from', OLD.meta, 'to', NEW.meta));
    END IF;
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION public.platform_page_meta_audit() FROM PUBLIC, anon, authenticated;
DROP TRIGGER IF EXISTS platform_page_meta_audit_on_change ON public.platform_page_meta;
CREATE TRIGGER platform_page_meta_audit_on_change AFTER UPDATE ON public.platform_page_meta
    FOR EACH ROW EXECUTE FUNCTION public.platform_page_meta_audit();

-- Public on purpose: it is what the pages publish anyway.
CREATE OR REPLACE FUNCTION public.site_page_meta(p_site text) RETURNS jsonb
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT m.meta FROM public.platform_page_meta m WHERE m.site = p_site;
$$;
REVOKE ALL ON FUNCTION public.site_page_meta(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.site_page_meta(text) TO anon, authenticated, service_role;

CREATE OR REPLACE FUNCTION public.set_platform_page_meta(p_site text, p_meta jsonb) RETURNS jsonb
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_meta jsonb;
    v_code text;
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_user_admin(auth.uid()) THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'not_allowed');
    END IF;
    IF p_site IS NULL OR p_site NOT IN ('optimist', 'max') THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', 'bad_request');
    END IF;
    v_code := public.page_meta_invalid_reason(p_meta);
    IF v_code IS NOT NULL THEN
        RETURN pg_catalog.jsonb_build_object('success', false, 'error', v_code);
    END IF;
    v_meta := pg_catalog.jsonb_strip_nulls(p_meta);
    INSERT INTO public.platform_page_meta (site, meta, updated_by) VALUES (p_site, v_meta, auth.uid())
        ON CONFLICT (site) DO UPDATE SET meta = EXCLUDED.meta, updated_at = now(), updated_by = EXCLUDED.updated_by;
    RETURN pg_catalog.jsonb_build_object('success', true, 'meta', v_meta);
END;
$$;
REVOKE ALL ON FUNCTION public.set_platform_page_meta(text, jsonb) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.set_platform_page_meta(text, jsonb) TO authenticated, service_role;
