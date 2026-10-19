-- ============================================================================
-- Agents platform v3: brand + sign-in page, custom domains, org console (aggregates + audit log)
-- ============================================================================
-- Builds on 20261016130000_agents_platform. Brand content safety (script-free HTML/CSS) is enforced
-- by the chat server at render time; here the brand's SHAPE, sizes and asset URLs are enforced.
-- Domain status moves only through agent_domain_record_check (service role, called by the
-- agent-domains edge function after a DNS check). Org console RPCs return aggregates only.
-- ============================================================================

-- Values the database needs to know about the deployment. Platform admins may change them.
--   agents_base_domain:   agent hosts are <slug>.<this>
--   agent_brand_base_url: the agent-brand bucket's public URL prefix, ending in /agent-brand/.
--                         null = no brand asset URL is accepted until it is set.
INSERT INTO public.platform_flags (key, value) VALUES
    ('agents_base_domain', '"bossconsole.ai"'::jsonb),
    ('agent_brand_base_url', 'null'::jsonb)
    ON CONFLICT (key) DO NOTHING;

-- ----------------------------------------------------------------------------
-- Brand bucket: <orgId>/<agentId>/<file> or <orgId>/org/<file> (org default brand). Raster only.
-- ----------------------------------------------------------------------------
UPDATE storage.buckets SET public = true, file_size_limit = 2 * 1024 * 1024,
    allowed_mime_types = ARRAY['image/png', 'image/jpeg', 'image/webp']
    WHERE id = 'agent-brand';

CREATE OR REPLACE FUNCTION public.is_agent_folder_admin(p_name text) RETURNS boolean
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    f text[] := storage.foldername(p_name);
    uuid_re constant text := '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';
BEGIN
    IF pg_catalog.cardinality(f) <> 2 OR f[1] !~ uuid_re
       OR pg_catalog.split_part(p_name, '/', 3) !~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$' THEN
        RETURN false;
    END IF;
    IF NOT public.is_org_admin(f[1]::uuid) THEN
        RETURN false;
    END IF;
    RETURN f[2] = 'org'
        OR (f[2] ~ uuid_re AND EXISTS (SELECT 1 FROM public.agents a
                                       WHERE a.id = f[2]::uuid AND a.org_id = f[1]::uuid));
END;
$$;
REVOKE ALL ON FUNCTION public.is_agent_folder_admin(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.is_agent_folder_admin(text) TO authenticated, service_role;

-- Upsert and listing in storage need a SELECT policy; public URLs are served without one.
DROP POLICY IF EXISTS "agent-brand admin select" ON storage.objects;
CREATE POLICY "agent-brand admin select" ON storage.objects FOR SELECT TO authenticated
    USING (bucket_id = 'agent-brand' AND public.is_agent_folder_admin(name));

-- ----------------------------------------------------------------------------
-- Brand validation
-- ----------------------------------------------------------------------------
-- A brand asset URL: <agent_brand_base_url><orgId>/<agentId|org>/<file>.(png|jpg|jpeg|webp).
-- p_org_id / p_agent_id, when given, pin the folder to that org and to that agent (or org).
CREATE OR REPLACE FUNCTION public.agent_brand_url_ok(p_url text, p_org_id uuid, p_agent_id uuid)
RETURNS boolean LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    base text;
    rest text;
    m text[];
BEGIN
    SELECT CASE WHEN pg_catalog.jsonb_typeof(f.value) = 'string' THEN f.value #>> '{}' END INTO base
        FROM public.platform_flags f WHERE f.key = 'agent_brand_base_url';
    IF base IS NULL OR p_url IS NULL OR pg_catalog.char_length(p_url) > 1024
       OR pg_catalog.left(p_url, pg_catalog.char_length(base)) <> base THEN
        RETURN false;
    END IF;
    rest := pg_catalog.substr(p_url, pg_catalog.char_length(base) + 1);
    m := pg_catalog.regexp_match(rest,
        '^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/'
        '(org|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/'
        '[A-Za-z0-9][A-Za-z0-9._-]{0,127}\.(png|jpg|jpeg|webp)$');
    IF m IS NULL THEN
        RETURN false;
    END IF;
    IF p_org_id IS NOT NULL AND m[1] <> p_org_id::text THEN
        RETURN false;
    END IF;
    IF p_agent_id IS NOT NULL AND m[2] NOT IN ('org', p_agent_id::text) THEN
        RETURN false;
    END IF;
    RETURN true;
END;
$$;

-- NULL when the brand is valid, else the first problem as a stable code (the console maps codes to
-- messages). p_org_id / p_agent_id bind asset URLs to the agent's own folder; the console may call
-- it with the brand alone for feedback.
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
               OR EXISTS (SELECT 1 FROM pg_catalog.jsonb_object_keys(v) x WHERE x NOT IN ('url', 'shape', 'background')) THEN
                RETURN 'logo_invalid';
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
                          WHERE x NOT IN ('mode', 'layout', 'headline', 'subtext', 'background',
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
            -- The real form mounts at exactly one <agent-signin></agent-signin>.
            IF (s->>'mode') = 'custom_html' AND (
                   (s->>'custom_html') IS NULL
                   OR (pg_catalog.char_length(pg_catalog.lower(s->>'custom_html'))
                       - pg_catalog.char_length(pg_catalog.replace(pg_catalog.lower(s->>'custom_html'), '<agent-signin', '')))
                      / pg_catalog.char_length('<agent-signin') <> 1
                   OR pg_catalog.strpos(pg_catalog.lower(s->>'custom_html'), '<agent-signin></agent-signin>') = 0) THEN
                RETURN 'signin_custom_html_mount_invalid';
            END IF;
        ELSE
            RETURN 'brand_unknown_key:' || pg_catalog.left(k, 40);
        END CASE;
    END LOOP;
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION public.agent_brand_url_ok(text, uuid, uuid) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.validate_agent_brand(jsonb, uuid, uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.validate_agent_brand(jsonb, uuid, uuid) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.agent_brand_url_ok(text, uuid, uuid) TO service_role;

CREATE OR REPLACE FUNCTION public.agents_brand_check() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    problem text := public.validate_agent_brand(NEW.brand, NEW.org_id, NEW.id);
BEGIN
    IF problem IS NOT NULL THEN
        RAISE EXCEPTION 'invalid_brand: %', problem USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS agents_brand_check_on_write ON public.agents;
CREATE TRIGGER agents_brand_check_on_write BEFORE INSERT OR UPDATE OF brand, org_id ON public.agents
    FOR EACH ROW EXECUTE FUNCTION public.agents_brand_check();

COMMENT ON COLUMN public.agents.brand IS
    'Validated by validate_agent_brand: {name, short_name, accent, accent_dark, logo{url,shape,background}, favicon_url, apple_touch_url, signin{...}}. Asset URLs are agent-brand bucket URLs under <orgId>/<agentId|org>/.';

-- Org-wide default brand (console Settings). Same validator, folder pinned to the org.
CREATE TABLE IF NOT EXISTS public.agent_org_defaults (
    org_id        uuid PRIMARY KEY REFERENCES public.organisations(id) ON DELETE CASCADE,
    default_brand jsonb NOT NULL DEFAULT '{}'::jsonb,
    updated_by    uuid REFERENCES auth.users(id) ON DELETE SET NULL DEFAULT auth.uid(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE OR REPLACE FUNCTION public.agent_org_defaults_check() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    problem text := public.validate_agent_brand(NEW.default_brand, NEW.org_id, NULL);
BEGIN
    IF problem IS NOT NULL THEN
        RAISE EXCEPTION 'invalid_brand: %', problem USING ERRCODE = '23514';
    END IF;
    NEW.updated_by := auth.uid();
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS agent_org_defaults_check_on_write ON public.agent_org_defaults;
CREATE TRIGGER agent_org_defaults_check_on_write BEFORE INSERT OR UPDATE ON public.agent_org_defaults
    FOR EACH ROW EXECUTE FUNCTION public.agent_org_defaults_check();

REVOKE ALL ON public.agent_org_defaults FROM PUBLIC, anon, authenticated;
GRANT ALL ON public.agent_org_defaults TO service_role;
GRANT SELECT ON public.agent_org_defaults TO authenticated;
GRANT INSERT (org_id, default_brand), UPDATE (default_brand) ON public.agent_org_defaults TO authenticated;
ALTER TABLE public.agent_org_defaults ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "agent_org_defaults admin select" ON public.agent_org_defaults;
CREATE POLICY "agent_org_defaults admin select" ON public.agent_org_defaults
    FOR SELECT TO authenticated USING (public.is_org_admin(org_id));
DROP POLICY IF EXISTS "agent_org_defaults admin insert" ON public.agent_org_defaults;
CREATE POLICY "agent_org_defaults admin insert" ON public.agent_org_defaults
    FOR INSERT TO authenticated WITH CHECK (public.is_org_admin(org_id));
DROP POLICY IF EXISTS "agent_org_defaults admin update" ON public.agent_org_defaults;
CREATE POLICY "agent_org_defaults admin update" ON public.agent_org_defaults
    FOR UPDATE TO authenticated USING (public.is_org_admin(org_id)) WITH CHECK (public.is_org_admin(org_id));

-- ----------------------------------------------------------------------------
-- Custom domains
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.agent_domains (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id        uuid NOT NULL REFERENCES public.agents(id) ON DELETE CASCADE,
    hostname        extensions.citext NOT NULL UNIQUE,
    status          text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'verified', 'active', 'failed')),
    verify_token    text NOT NULL DEFAULT pg_catalog.replace(gen_random_uuid()::text, '-', '')
        CHECK (verify_token ~ '^[0-9a-f]{32}$'),
    last_checked_at timestamptz,
    verified_at     timestamptz,
    created_by      uuid REFERENCES auth.users(id) ON DELETE SET NULL DEFAULT auth.uid(),
    created_at      timestamptz NOT NULL DEFAULT now(),
    CHECK ((status IN ('verified', 'active')) = (verified_at IS NOT NULL) OR status = 'failed')
);
CREATE INDEX IF NOT EXISTS idx_agent_domains_agent ON public.agent_domains (agent_id);

-- NULL when p_host is an acceptable custom hostname, else a stable code.
CREATE OR REPLACE FUNCTION public.agent_hostname_error(p_host text) RETURNS text
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    base text;
    d text;
BEGIN
    IF p_host IS NULL OR p_host = '' THEN
        RETURN 'hostname_required';
    END IF;
    IF pg_catalog.char_length(p_host) > 253 THEN
        RETURN 'hostname_too_long';
    END IF;
    IF p_host <> pg_catalog.lower(p_host) THEN
        RETURN 'hostname_not_lowercase';
    END IF;
    IF pg_catalog.strpos(p_host, '*') > 0 THEN
        RETURN 'hostname_wildcard';
    END IF;
    IF p_host ~ '^[0-9.]+$' OR pg_catalog.strpos(p_host, ':') > 0 OR p_host ~ '^\[' THEN
        RETURN 'hostname_ip';
    END IF;
    IF p_host !~ '^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+([a-z]{2,63}|xn--[a-z0-9-]{1,59})$' THEN
        RETURN 'hostname_invalid';
    END IF;
    SELECT f.value #>> '{}' INTO base FROM public.platform_flags f
        WHERE f.key = 'agents_base_domain' AND pg_catalog.jsonb_typeof(f.value) = 'string';
    FOREACH d IN ARRAY ARRAY['bossconsole.ai', 'risaboss.com', 'risalabs.ai', pg_catalog.lower(base)] LOOP
        IF d IS NOT NULL AND (p_host = d OR pg_catalog.right(p_host, pg_catalog.char_length(d) + 1) = '.' || d) THEN
            RETURN 'hostname_reserved';
        END IF;
    END LOOP;
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION public.agent_hostname_error(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.agent_hostname_error(text) TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.agent_domains_guard() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    problem text;
BEGIN
    IF TG_OP = 'INSERT' THEN
        -- Before any check that could reveal another org's agent (RLS WITH CHECK runs after this).
        IF NOT public.is_agent_org_admin(NEW.agent_id) THEN
            RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
        END IF;
        problem := public.agent_hostname_error(NEW.hostname::text);
        IF problem IS NOT NULL THEN
            RAISE EXCEPTION '%', problem USING ERRCODE = '23514';
        END IF;
        -- Serialise concurrent adds for one agent before counting.
        PERFORM 1 FROM public.agents a WHERE a.id = NEW.agent_id FOR UPDATE;
        IF (SELECT count(*) FROM public.agent_domains d WHERE d.agent_id = NEW.agent_id) >= 5 THEN
            RAISE EXCEPTION 'domain_limit_reached' USING ERRCODE = 'P0001';
        END IF;
        NEW.status := 'pending';
        NEW.last_checked_at := NULL;
        NEW.verified_at := NULL;
        NEW.verify_token := pg_catalog.replace(gen_random_uuid()::text, '-', '');
        NEW.created_at := now();
    ELSIF NEW.hostname IS DISTINCT FROM OLD.hostname OR NEW.agent_id IS DISTINCT FROM OLD.agent_id
          OR NEW.verify_token IS DISTINCT FROM OLD.verify_token THEN
        RAISE EXCEPTION 'domain_immutable' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS agent_domains_guard_on_write ON public.agent_domains;
CREATE TRIGGER agent_domains_guard_on_write BEFORE INSERT OR UPDATE ON public.agent_domains
    FOR EACH ROW EXECUTE FUNCTION public.agent_domains_guard();

REVOKE ALL ON public.agent_domains FROM PUBLIC, anon, authenticated;
GRANT ALL ON public.agent_domains TO service_role;
GRANT SELECT, DELETE ON public.agent_domains TO authenticated;
GRANT INSERT (agent_id, hostname) ON public.agent_domains TO authenticated;
ALTER TABLE public.agent_domains ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "agent_domains admin select" ON public.agent_domains;
CREATE POLICY "agent_domains admin select" ON public.agent_domains
    FOR SELECT TO authenticated USING (public.is_agent_org_admin(agent_id));
DROP POLICY IF EXISTS "agent_domains admin insert" ON public.agent_domains;
CREATE POLICY "agent_domains admin insert" ON public.agent_domains
    FOR INSERT TO authenticated
    WITH CHECK (public.is_agent_org_admin(agent_id) AND created_by = (SELECT auth.uid()));
DROP POLICY IF EXISTS "agent_domains admin delete" ON public.agent_domains;
CREATE POLICY "agent_domains admin delete" ON public.agent_domains
    FOR DELETE TO authenticated USING (public.is_agent_org_admin(agent_id));

-- The result of a DNS check by the agent-domains edge function (service role only; the function has
-- already confirmed the caller can see the row with their own JWT). TXT proves ownership
-- (verified); TXT plus the CNAME to the edge makes it active. Anything else fails, including a
-- re-check of an active domain whose records were removed.
CREATE OR REPLACE FUNCTION public.agent_domain_record_check(p_domain_id uuid, p_txt_ok boolean,
                                                           p_cname_ok boolean, p_actor uuid)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    new_status text := CASE WHEN p_txt_ok AND p_cname_ok THEN 'active'
                            WHEN p_txt_ok THEN 'verified' ELSE 'failed' END;
BEGIN
    PERFORM pg_catalog.set_config('agents.audit_actor', COALESCE(p_actor::text, ''), true);
    UPDATE public.agent_domains
       SET status = new_status, last_checked_at = now(),
           verified_at = CASE WHEN new_status = 'failed' THEN verified_at
                              ELSE COALESCE(verified_at, now()) END
     WHERE id = p_domain_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'domain_not_found' USING ERRCODE = 'P0001';
    END IF;
    PERFORM pg_catalog.set_config('agents.audit_actor', '', true);
    RETURN new_status;
END;
$$;
REVOKE ALL ON FUNCTION public.agent_domain_record_check(uuid, boolean, boolean, uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.agent_domain_record_check(uuid, boolean, boolean, uuid) TO service_role;

-- Host -> agent. <slug>.<agents_base_domain> or an ACTIVE custom domain. Callers (chat uses the anon
-- key) see live agents only; the provisioner bot sees every status.
CREATE OR REPLACE FUNCTION public.agent_for_host(p_host text)
RETURNS TABLE (slug text, agent_id uuid, status public.agent_status)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
DECLARE
    host text := pg_catalog.regexp_replace(pg_catalog.lower(pg_catalog.btrim(COALESCE(p_host, ''))), ':[0-9]{1,5}$', '');
    base text;
    privileged boolean := public.is_agents_provisioner();
BEGIN
    host := pg_catalog.rtrim(host, '.');
    IF host = '' OR pg_catalog.char_length(host) > 253 THEN
        RETURN;
    END IF;
    SELECT f.value #>> '{}' INTO base FROM public.platform_flags f
        WHERE f.key = 'agents_base_domain' AND pg_catalog.jsonb_typeof(f.value) = 'string';
    base := pg_catalog.lower(base);
    IF base IS NOT NULL AND pg_catalog.right(host, pg_catalog.char_length(base) + 1) = '.' || base THEN
        RETURN QUERY
            SELECT a.slug, a.id, a.status FROM public.agents a
            WHERE a.slug = pg_catalog.left(host, pg_catalog.char_length(host) - pg_catalog.char_length(base) - 1)
              AND (privileged OR a.status = 'live');
        RETURN;
    END IF;
    RETURN QUERY
        SELECT a.slug, a.id, a.status
        FROM public.agent_domains d JOIN public.agents a ON a.id = d.agent_id
        WHERE d.hostname = host::extensions.citext AND d.status = 'active'
          AND (privileged OR a.status = 'live');
END;
$$;

-- Caddy on-demand TLS "ask" (via the chat server): only hosts that route to a live agent.
CREATE OR REPLACE FUNCTION public.domain_tls_allowed(p_host text) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT EXISTS (SELECT 1 FROM public.agent_for_host(p_host) h WHERE h.status = 'live');
$$;
REVOKE ALL ON FUNCTION public.agent_for_host(text), public.domain_tls_allowed(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.agent_for_host(text), public.domain_tls_allowed(text)
    TO anon, authenticated, service_role;

-- ----------------------------------------------------------------------------
-- Activity: last_seen_at per install plus one row per (agent, person, UTC day) for daily actives.
-- Written only through record_agent_seen, which only the provisioner bot may call.
-- ----------------------------------------------------------------------------
ALTER TABLE public.agent_installs ADD COLUMN IF NOT EXISTS last_seen_at timestamptz;

CREATE TABLE IF NOT EXISTS public.agent_activity_days (
    agent_id uuid NOT NULL REFERENCES public.agents(id) ON DELETE CASCADE,
    user_id  uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    day      date NOT NULL,
    PRIMARY KEY (agent_id, day, user_id)
);
ALTER TABLE public.agent_activity_days ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.agent_activity_days FROM PUBLIC, anon, authenticated;
GRANT ALL ON public.agent_activity_days TO service_role;

-- p_seen: [{"agent_id": uuid, "user_id": uuid, "seen_at": timestamptz?}] (<= 1000). seen_at defaults
-- to now() and is clamped to now(); entries older than 90 days or without a non-removed install are
-- ignored. last_seen_at only moves forward. Returns the number of installs touched.
CREATE OR REPLACE FUNCTION public.record_agent_seen(p_seen jsonb) RETURNS int
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    touched int;
BEGIN
    IF NOT public.is_agents_provisioner() THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    IF p_seen IS NULL OR pg_catalog.jsonb_typeof(p_seen) <> 'array' OR pg_catalog.jsonb_array_length(p_seen) > 1000 THEN
        RAISE EXCEPTION 'invalid_seen' USING ERRCODE = 'P0001';
    END IF;
    WITH raw AS (
        SELECT (e->>'agent_id')::uuid AS agent_id, (e->>'user_id')::uuid AS user_id,
               LEAST(COALESCE((e->>'seen_at')::timestamptz, now()), now()) AS seen
        FROM pg_catalog.jsonb_array_elements(p_seen) e
    ), ok AS (
        SELECT r.* FROM raw r
        WHERE r.seen >= now() - interval '90 days'
          AND EXISTS (SELECT 1 FROM public.agent_installs i
                      WHERE i.agent_id = r.agent_id AND i.user_id = r.user_id AND i.state <> 'removed')
    ), upd AS (
        UPDATE public.agent_installs i
           SET last_seen_at = m.seen
          FROM (SELECT o.agent_id, o.user_id, max(o.seen) AS seen FROM ok o GROUP BY 1, 2) m
         WHERE i.agent_id = m.agent_id AND i.user_id = m.user_id
           AND (i.last_seen_at IS NULL OR i.last_seen_at < m.seen)
        RETURNING 1
    ), ins AS (
        INSERT INTO public.agent_activity_days (agent_id, user_id, day)
            SELECT DISTINCT o.agent_id, o.user_id, (o.seen AT TIME ZONE 'UTC')::date FROM ok o
            ON CONFLICT DO NOTHING
        RETURNING 1
    )
    SELECT count(*)::int INTO touched FROM upd;
    RETURN touched;
END;
$$;
REVOKE ALL ON FUNCTION public.record_agent_seen(jsonb) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.record_agent_seen(jsonb) TO authenticated, service_role;

-- The touch trigger must not count a last-seen bump as a row change (updated_at = provisioner writes).
CREATE OR REPLACE FUNCTION public.agent_installs_touch() RETURNS trigger
    LANGUAGE plpgsql SET search_path = '' AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (to_jsonb(NEW) - 'last_seen_at' - 'updated_at')
                          = (to_jsonb(OLD) - 'last_seen_at' - 'updated_at') THEN
        RETURN NEW;
    END IF;
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

-- ----------------------------------------------------------------------------
-- Audit log: append-only. Written by triggers (definer), read through org_audit_log. No FK to
-- organisations/agents so the trail outlives the rows; no client grants; UPDATE/DELETE/TRUNCATE
-- raise for everyone.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.agent_audit_log (
    id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_id   uuid NOT NULL,
    agent_id uuid,
    actor    uuid,
    action   text NOT NULL,
    detail   jsonb NOT NULL DEFAULT '{}'::jsonb,
    at       timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS idx_agent_audit_log_org_at ON public.agent_audit_log (org_id, at DESC, id DESC);
ALTER TABLE public.agent_audit_log ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.agent_audit_log FROM PUBLIC, anon, authenticated, service_role;
GRANT SELECT ON public.agent_audit_log TO service_role;

CREATE OR REPLACE FUNCTION public.agent_audit_log_immutable() RETURNS trigger
    LANGUAGE plpgsql SET search_path = '' AS $$
BEGIN
    RAISE EXCEPTION 'agent_audit_log is append-only' USING ERRCODE = '42501';
END;
$$;
DROP TRIGGER IF EXISTS agent_audit_log_no_change ON public.agent_audit_log;
CREATE TRIGGER agent_audit_log_no_change BEFORE UPDATE OR DELETE ON public.agent_audit_log
    FOR EACH ROW EXECUTE FUNCTION public.agent_audit_log_immutable();
DROP TRIGGER IF EXISTS agent_audit_log_no_truncate ON public.agent_audit_log;
CREATE TRIGGER agent_audit_log_no_truncate BEFORE TRUNCATE ON public.agent_audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION public.agent_audit_log_immutable();

-- auth.uid() for client writes; agents.audit_actor for service-role writes made on a user's behalf.
CREATE OR REPLACE FUNCTION public.agent_audit_actor() RETURNS uuid
    LANGUAGE sql STABLE SET search_path = '' AS $$
    SELECT COALESCE(auth.uid(), NULLIF(pg_catalog.current_setting('agents.audit_actor', true), '')::uuid);
$$;
REVOKE ALL ON FUNCTION public.agent_audit_actor() FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.agents_audit() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    actor uuid := public.agent_audit_actor();
    changed text[];
    brand_keys text[];
    act text;
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
            VALUES (NEW.org_id, NEW.id, actor, 'agent.brand_change', jsonb_build_object('keys', to_jsonb(brand_keys)));
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
                    WHEN OLD.status = 'pending_review' AND NEW.status = 'live' THEN 'agent.approve'
                    WHEN OLD.status = 'pending_review' AND NEW.status = 'draft' THEN 'agent.reject'
                    ELSE 'agent.status' END;
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
            VALUES (NEW.org_id, NEW.id, actor, act, jsonb_build_object('status', NEW.status, 'from_status', OLD.status));
    END IF;
    RETURN NULL;
END;
$$;
DROP TRIGGER IF EXISTS agents_audit_on_change ON public.agents;
CREATE TRIGGER agents_audit_on_change AFTER INSERT OR UPDATE OR DELETE ON public.agents
    FOR EACH ROW EXECUTE FUNCTION public.agents_audit();

CREATE OR REPLACE FUNCTION public.agent_children_audit() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    agent uuid;
    org uuid;
BEGIN
    IF TG_OP = 'DELETE' THEN agent := OLD.agent_id; ELSE agent := NEW.agent_id; END IF;
    SELECT a.org_id INTO org FROM public.agents a WHERE a.id = agent;
    IF org IS NULL THEN
        RETURN NULL; -- the agent itself is being deleted; agent.delete covers it
    END IF;
    IF TG_TABLE_NAME = 'agent_domains' THEN
        IF TG_OP = 'INSERT' THEN
            INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
                VALUES (org, NEW.agent_id, public.agent_audit_actor(), 'domain.add', jsonb_build_object('hostname', NEW.hostname));
        ELSIF TG_OP = 'DELETE' THEN
            INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
                VALUES (org, OLD.agent_id, public.agent_audit_actor(), 'domain.remove', jsonb_build_object('hostname', OLD.hostname));
        ELSIF NEW.status IS DISTINCT FROM OLD.status THEN
            INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
                VALUES (org, NEW.agent_id, public.agent_audit_actor(), 'domain.status',
                        jsonb_build_object('hostname', NEW.hostname, 'status', NEW.status, 'from_status', OLD.status));
        END IF;
    ELSIF TG_TABLE_NAME = 'agent_invites' AND TG_OP = 'INSERT' THEN
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
            VALUES (org, agent, public.agent_audit_actor(), 'invite.add', jsonb_build_object('email', NEW.email));
    ELSIF TG_TABLE_NAME = 'agent_invites' THEN
        INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
            VALUES (org, agent, public.agent_audit_actor(), 'invite.remove', jsonb_build_object('email', OLD.email));
    END IF;
    RETURN NULL;
END;
$$;
DROP TRIGGER IF EXISTS agent_domains_audit_on_change ON public.agent_domains;
CREATE TRIGGER agent_domains_audit_on_change AFTER INSERT OR UPDATE OR DELETE ON public.agent_domains
    FOR EACH ROW EXECUTE FUNCTION public.agent_children_audit();
DROP TRIGGER IF EXISTS agent_invites_audit_on_change ON public.agent_invites;
CREATE TRIGGER agent_invites_audit_on_change AFTER INSERT OR DELETE ON public.agent_invites
    FOR EACH ROW EXECUTE FUNCTION public.agent_children_audit();

CREATE OR REPLACE FUNCTION public.agent_org_defaults_audit() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    INSERT INTO public.agent_audit_log (org_id, agent_id, actor, action, detail)
        VALUES (NEW.org_id, NULL, public.agent_audit_actor(), 'org.default_brand_change', '{}'::jsonb);
    RETURN NULL;
END;
$$;
DROP TRIGGER IF EXISTS agent_org_defaults_audit_on_change ON public.agent_org_defaults;
CREATE TRIGGER agent_org_defaults_audit_on_change AFTER INSERT OR UPDATE ON public.agent_org_defaults
    FOR EACH ROW EXECUTE FUNCTION public.agent_org_defaults_audit();

REVOKE ALL ON FUNCTION public.agents_brand_check(), public.agent_org_defaults_check(), public.agent_domains_guard(),
    public.agent_audit_log_immutable(), public.agents_audit(), public.agent_children_audit(),
    public.agent_org_defaults_audit() FROM PUBLIC, anon, authenticated;

-- ----------------------------------------------------------------------------
-- Org console RPCs: org admin only, aggregates and identities only, never chat content
-- (install `detail` and `data_dir` are not returned either).
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.org_overview(p_org_id uuid) RETURNS jsonb
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    today date := (now() AT TIME ZONE 'UTC')::date;
BEGIN
    IF auth.uid() IS NULL OR p_org_id IS NULL OR NOT public.is_org_admin(p_org_id) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    RETURN jsonb_build_object(
        'agents_by_status', (
            SELECT jsonb_build_object(
                'draft', count(*) FILTER (WHERE a.status = 'draft'),
                'pending_review', count(*) FILTER (WHERE a.status = 'pending_review'),
                'live', count(*) FILTER (WHERE a.status = 'live'),
                'suspended', count(*) FILTER (WHERE a.status = 'suspended'))
            FROM public.agents a WHERE a.org_id = p_org_id),
        'installs_total', (
            SELECT count(*) FROM public.agent_installs i JOIN public.agents a ON a.id = i.agent_id
            WHERE a.org_id = p_org_id AND i.state <> 'removed'),
        'people_total', (
            SELECT count(DISTINCT i.user_id) FROM public.agent_installs i JOIN public.agents a ON a.id = i.agent_id
            WHERE a.org_id = p_org_id AND i.state <> 'removed'),
        'active_people_7d', (
            SELECT count(DISTINCT d.user_id) FROM public.agent_activity_days d JOIN public.agents a ON a.id = d.agent_id
            WHERE a.org_id = p_org_id AND d.day > today - 7),
        'active_people_30d', (
            SELECT count(DISTINCT d.user_id) FROM public.agent_activity_days d JOIN public.agents a ON a.id = d.agent_id
            WHERE a.org_id = p_org_id AND d.day > today - 30),
        'pods', (
            SELECT jsonb_build_object(
                'running', count(*) FILTER (WHERE i.state = 'running'),
                'failed', count(*) FILTER (WHERE i.state = 'failed'),
                'pending', count(*) FILTER (WHERE i.state = 'pending'))
            FROM public.agent_installs i JOIN public.agents a ON a.id = i.agent_id WHERE a.org_id = p_org_id));
END;
$$;

CREATE OR REPLACE FUNCTION public.org_people(p_org_id uuid, p_limit int DEFAULT 500, p_offset int DEFAULT 0)
RETURNS TABLE (user_id uuid, email text, first_seen_at timestamptz, last_seen_at timestamptz, agents jsonb)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
BEGIN
    IF auth.uid() IS NULL OR p_org_id IS NULL OR NOT public.is_org_admin(p_org_id) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    RETURN QUERY
        SELECT i.user_id, u.email::text, min(i.created_at), max(i.last_seen_at),
               jsonb_agg(jsonb_build_object(
                   'agent_id', a.id, 'slug', a.slug, 'name', a.name, 'state', i.state,
                   'joined_at', i.created_at, 'last_seen_at', i.last_seen_at,
                   'running_version', i.running_version) ORDER BY i.created_at)
        FROM public.agent_installs i
        JOIN public.agents a ON a.id = i.agent_id
        JOIN auth.users u ON u.id = i.user_id
        WHERE a.org_id = p_org_id
        GROUP BY i.user_id, u.email
        ORDER BY max(i.last_seen_at) DESC NULLS LAST, min(i.created_at) DESC, i.user_id
        LIMIT LEAST(GREATEST(COALESCE(p_limit, 500), 1), 1000) OFFSET GREATEST(COALESCE(p_offset, 0), 0);
END;
$$;

CREATE OR REPLACE FUNCTION public.org_usage(p_org_id uuid, p_days int DEFAULT 30)
RETURNS TABLE (day date, agent_id uuid, slug text, joins int, active_people int)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
DECLARE
    today date := (now() AT TIME ZONE 'UTC')::date;
    n int := LEAST(GREATEST(COALESCE(p_days, 30), 1), 90);
BEGIN
    IF auth.uid() IS NULL OR p_org_id IS NULL OR NOT public.is_org_admin(p_org_id) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    RETURN QUERY
        SELECT g.day, a.id, a.slug,
               (SELECT count(*)::int FROM public.agent_installs i
                WHERE i.agent_id = a.id AND (i.created_at AT TIME ZONE 'UTC')::date = g.day),
               (SELECT count(*)::int FROM public.agent_activity_days d
                WHERE d.agent_id = a.id AND d.day = g.day)
        FROM public.agents a
        CROSS JOIN (SELECT (today - s)::date AS day FROM pg_catalog.generate_series(0, n - 1) s) g
        WHERE a.org_id = p_org_id
        ORDER BY g.day, a.slug;
END;
$$;

CREATE OR REPLACE FUNCTION public.org_audit_log(p_org_id uuid, p_limit int DEFAULT 50, p_before timestamptz DEFAULT NULL)
RETURNS TABLE (id bigint, agent_id uuid, agent_slug text, actor uuid, actor_email text, action text,
               detail jsonb, at timestamptz)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
BEGIN
    IF auth.uid() IS NULL OR p_org_id IS NULL OR NOT public.is_org_admin(p_org_id) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    RETURN QUERY
        SELECT l.id, l.agent_id, a.slug, l.actor, u.email::text, l.action, l.detail, l.at
        FROM public.agent_audit_log l
        LEFT JOIN public.agents a ON a.id = l.agent_id
        LEFT JOIN auth.users u ON u.id = l.actor
        WHERE l.org_id = p_org_id AND (p_before IS NULL OR l.at < p_before)
        ORDER BY l.at DESC, l.id DESC
        LIMIT LEAST(GREATEST(COALESCE(p_limit, 50), 1), 200);
END;
$$;

REVOKE ALL ON FUNCTION public.org_overview(uuid), public.org_people(uuid, int, int), public.org_usage(uuid, int),
    public.org_audit_log(uuid, int, timestamptz) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.org_overview(uuid), public.org_people(uuid, int, int), public.org_usage(uuid, int),
    public.org_audit_log(uuid, int, timestamptz) TO authenticated, service_role;
