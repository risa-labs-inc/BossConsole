-- Agent sign-in page: the org's own template (custom_html) may lay out every functional piece as a slot, the
-- email form being <agent-email-form> exactly once; the single <agent-signin> slot (the whole form) still works.
-- An agent can read and change its own brand with its user's session: agent_brand_get / agent_brand_update, the
-- latter recording "via" (which agent did it for the person) on the audit entry. The actor stays the person.

-- Occurrences of an opening tag (case-insensitive), as the validator counts them.
CREATE OR REPLACE FUNCTION public.agent_signin_tag_count(p_html text, p_tag text) RETURNS int
    LANGUAGE sql IMMUTABLE SET search_path = '' AS $$
    SELECT ((pg_catalog.char_length(pg_catalog.lower(p_html))
             - pg_catalog.char_length(pg_catalog.replace(pg_catalog.lower(p_html), pg_catalog.lower(p_tag), '')))
            / pg_catalog.char_length(p_tag))::int;
$$;
REVOKE ALL ON FUNCTION public.agent_signin_tag_count(text, text) FROM PUBLIC, anon, authenticated;

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
        ELSE
            RETURN 'brand_unknown_key:' || pg_catalog.left(k, 40);
        END CASE;
    END LOOP;
    RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION public.agents_audit() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    actor uuid := public.agent_audit_actor();
    changed text[];
    brand_keys text[];
    act text;
    via text := NULLIF(pg_catalog.current_setting('agents.audit_via', true), '');
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
            VALUES (NEW.org_id, NEW.id, actor, 'agent.create', jsonb_build_object('slug', NEW.slug, 'name', NEW.name));
        RETURN NULL;
    ELSIF TG_OP = 'DELETE' THEN
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
            VALUES (OLD.org_id, OLD.id, actor, 'agent.delete', jsonb_build_object('slug', OLD.slug));
        RETURN NULL;
    END IF;
    IF NEW.brand IS DISTINCT FROM OLD.brand THEN
        SELECT array_agg(k ORDER BY k) INTO brand_keys FROM (
            SELECT k FROM pg_catalog.jsonb_object_keys(NEW.brand) k
            UNION SELECT k FROM pg_catalog.jsonb_object_keys(OLD.brand) k) ks
        WHERE NEW.brand->k IS DISTINCT FROM OLD.brand->k;
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
            VALUES (NEW.org_id, NEW.id, actor, 'agent.brand_change', jsonb_build_object('keys', to_jsonb(brand_keys)) || CASE WHEN via IS NULL THEN '{}'::jsonb ELSE jsonb_build_object('via', via) END);
    END IF;
    changed := array_remove(ARRAY[
        CASE WHEN NEW.name IS DISTINCT FROM OLD.name THEN 'name' END,
        CASE WHEN NEW.model IS DISTINCT FROM OLD.model THEN 'model' END,
        CASE WHEN NEW.signup_policy IS DISTINCT FROM OLD.signup_policy THEN 'signup_policy' END,
        CASE WHEN NEW.allowed_connectors IS DISTINCT FROM OLD.allowed_connectors THEN 'allowed_connectors' END,
        CASE WHEN NEW.allowed_tools IS DISTINCT FROM OLD.allowed_tools THEN 'allowed_tools' END,
        CASE WHEN NEW.plugin_id IS DISTINCT FROM OLD.plugin_id THEN 'plugin_id' END], NULL);
    IF cardinality(changed) > 0 THEN
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
            VALUES (NEW.org_id, NEW.id, actor, 'agent.settings_change', jsonb_build_object('fields', to_jsonb(changed)));
    END IF;
    IF NEW.live_version IS DISTINCT FROM OLD.live_version THEN
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
            VALUES (NEW.org_id, NEW.id, actor, 'agent.publish', jsonb_build_object(
                'version', NEW.live_version, 'from_version', OLD.live_version,
                'status', NEW.status, 'from_status', OLD.status));
    ELSIF NEW.status IS DISTINCT FROM OLD.status THEN
        act := CASE WHEN NEW.status = 'suspended' THEN 'agent.suspend'
                    WHEN OLD.status = 'suspended' THEN 'agent.unsuspend'
                    WHEN OLD.status = 'pending_review' AND NEW.status = 'live' THEN 'agent.approve'
                    WHEN OLD.status = 'pending_review' AND NEW.status = 'draft' THEN 'agent.reject'
                    ELSE 'agent.status' END;
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
            VALUES (NEW.org_id, NEW.id, actor, act, jsonb_build_object('status', NEW.status, 'from_status', OLD.status));
    END IF;
    RETURN NULL;
END;
$$;

-- The agent's brand as its signed-in user may see it (agents.view), with the console and site addresses.
CREATE OR REPLACE FUNCTION public.agent_brand_get(p_agent_id uuid) RETURNS jsonb
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    a record;
    base text;
BEGIN
    SELECT id, org_id, slug, name, brand, status INTO a FROM public.agents WHERE id = p_agent_id;
    IF a.id IS NULL OR NOT public.has_org_permission(a.org_id, 'agents.view') THEN
        RAISE EXCEPTION 'agent_not_found' USING ERRCODE = '42501';
    END IF;
    SELECT CASE WHEN pg_catalog.jsonb_typeof(f.value) = 'string' THEN f.value #>> '{}' END INTO base
        FROM public.platform_flags f WHERE f.key = 'agents_base_domain';
    RETURN jsonb_build_object(
        'agent_id', a.id, 'org_id', a.org_id, 'slug', a.slug, 'name', a.name, 'status', a.status, 'brand', a.brand,
        'can_edit', public.has_org_permission(a.org_id, 'agents.edit'),
        'site_url', CASE WHEN base IS NULL THEN NULL ELSE 'https://' || a.slug || '.' || base || '/' END,
        'console_url', CASE WHEN base IS NULL THEN NULL
                            ELSE 'https://console.' || base || '/orgs/' || a.org_id || '/agents/' || a.id END);
END;
$$;
REVOKE ALL ON FUNCTION public.agent_brand_get(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.agent_brand_get(uuid) TO authenticated;

-- Replaces the agent's brand as the caller (RLS, agents.edit and the brand validator all apply, as for the
-- console's own write). p_via names the agent acting for the person, e.g. 'agent:acme'; it is recorded on the
-- audit entry, the actor being the person.
CREATE OR REPLACE FUNCTION public.agent_brand_update(p_agent_id uuid, p_brand jsonb, p_via text DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql SECURITY INVOKER SET search_path = '' AS $$
DECLARE
    saved jsonb;
BEGIN
    IF p_via IS NOT NULL AND p_via !~ '^agent:[a-z][a-z0-9-]{0,62}$' THEN
        RAISE EXCEPTION 'via_invalid' USING ERRCODE = '22023';
    END IF;
    PERFORM pg_catalog.set_config('agents.audit_via', COALESCE(p_via, ''), true);
    UPDATE public.agents SET brand = p_brand WHERE id = p_agent_id RETURNING brand INTO saved;
    PERFORM pg_catalog.set_config('agents.audit_via', '', true);
    IF saved IS NULL THEN
        RAISE EXCEPTION 'agent_not_found' USING ERRCODE = '42501';
    END IF;
    RETURN saved;
END;
$$;
REVOKE ALL ON FUNCTION public.agent_brand_update(uuid, jsonb, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.agent_brand_update(uuid, jsonb, text) TO authenticated;
