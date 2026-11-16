-- Marketplace v2: zip bundles (skills, http and script connectors), listings restricted to named agents, and four platform
-- flags that ship OFF. Bundles live in the private storage bucket `marketplace-bundles`; the edge function
-- `marketplace-bundle` verifies the user and calls the service-only RPCs below.
-- The host strips PostgREST error bodies, so the new RPCs that Max calls return refusals as {"error": code} with HTTP 200.
-- Only "not signed in" raises.
BEGIN;

-- ----------------------------------------------------------------------------
-- Version columns. Legacy rows keep `content` and have no manifest; legacy connectors are MCP.
-- ----------------------------------------------------------------------------
ALTER TABLE public.marketplace_versions
    ADD COLUMN manifest jsonb,
    ADD COLUMN connector_type text CHECK (connector_type IN ('mcp', 'http', 'scripts')),
    ADD COLUMN bundle_path text,
    ADD COLUMN bundle_sha256 text CHECK (bundle_sha256 ~ '^[0-9a-f]{64}$'),
    ADD COLUMN bundle_size int CHECK (bundle_size > 0),
    ADD COLUMN allowed_agents text[] NOT NULL DEFAULT '{}' CHECK (pg_catalog.cardinality(allowed_agents) <= 10);

UPDATE public.marketplace_versions v SET connector_type = 'mcp'
    FROM public.marketplace_listings l WHERE l.id = v.listing_id AND l.kind = 'connector' AND v.manifest IS NULL;

INSERT INTO public.platform_flags (key, value) VALUES
    ('marketplace_bundles', 'false'::jsonb),
    ('marketplace_restricted_listings', 'false'::jsonb),
    ('marketplace_http_connectors', 'false'::jsonb),
    ('marketplace_script_connectors', 'false'::jsonb)
ON CONFLICT (key) DO NOTHING;

-- Private bucket: no policy on storage.objects, so only the service role (the edge function) reads or writes it.
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
    VALUES ('marketplace-bundles', 'marketplace-bundles', false, 10 * 1024 * 1024, ARRAY['application/zip'])
ON CONFLICT (id) DO UPDATE SET public = false, file_size_limit = EXCLUDED.file_size_limit,
    allowed_mime_types = EXCLUDED.allowed_mime_types;

-- ----------------------------------------------------------------------------
-- Flags. A missing row is off.
-- ----------------------------------------------------------------------------
CREATE FUNCTION public.marketplace_flag_on(p_key text)
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT COALESCE((SELECT f.value = 'true'::jsonb FROM public.platform_flags f WHERE f.key = p_key), false);
$$;
REVOKE ALL ON FUNCTION public.marketplace_flag_on(text) FROM PUBLIC, anon, authenticated, service_role;

CREATE FUNCTION public.marketplace_flags()
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT pg_catalog.jsonb_build_object(
        'bundles', public.marketplace_flag_on('marketplace_bundles'),
        'restricted', public.marketplace_flag_on('marketplace_restricted_listings'),
        'http', public.marketplace_flag_on('marketplace_http_connectors'),
        'scripts', public.marketplace_flag_on('marketplace_script_connectors'));
$$;
REVOKE ALL ON FUNCTION public.marketplace_flags() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.marketplace_flags() TO anon, authenticated, service_role;

-- The host rules of validate_agent_draft_content: a public, dotted, non-IP name.
CREATE FUNCTION public.marketplace_public_host(p_host text)
RETURNS boolean LANGUAGE sql IMMUTABLE SET search_path = '' AS $$
    SELECT COALESCE(pg_catalog.char_length(p_host) <= 253
        AND p_host ~ '^([a-z0-9]([a-z0-9-]*[a-z0-9])?\.)+[a-z][a-z0-9-]*[a-z0-9]$'
        AND p_host !~ '(^|\.)(localhost|local|internal|lan|home|test|invalid|onion)$', false);
$$;
REVOKE ALL ON FUNCTION public.marketplace_public_host(text) FROM PUBLIC, anon, authenticated, service_role;

-- marketplace_can_skip_review for an explicit user (the service-only submit has no auth.uid()).
CREATE FUNCTION public.marketplace_user_can_skip_review(p_user uuid)
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT p_user IS NOT NULL AND (
        public.is_user_admin(p_user)
        OR EXISTS (SELECT 1 FROM public.marketplace_trusted_publishers t WHERE t.user_id = p_user));
$$;
REVOKE ALL ON FUNCTION public.marketplace_user_can_skip_review(uuid) FROM PUBLIC, anon, authenticated, service_role;

-- ----------------------------------------------------------------------------
-- Manifest validation (contract section 1). Returns NULL when valid, else the error code.
-- ----------------------------------------------------------------------------
CREATE FUNCTION public.marketplace_check_manifest(p jsonb)
RETURNS text LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    kind text;
    slug text;
    nm text;
    cat text;
    c jsonb;
    t jsonb;
    a jsonb;
    s jsonb;
    props jsonb;
    ctype text;
    tool_names text[];
    secret_names text[];
    ph text;
    k text;
    base text;
    host text;
    hdr text;
    n int;
    qk jsonb;
BEGIN
    IF pg_catalog.jsonb_typeof(p) IS DISTINCT FROM 'object' OR pg_catalog.octet_length(p::text) > 65536 THEN
        RETURN 'invalid_manifest';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_catalog.jsonb_object_keys(p) x
               WHERE x NOT IN ('schema', 'kind', 'slug', 'name', 'summary', 'description', 'publisher_name', 'category',
                               'tags', 'visibility', 'skill', 'connector')) THEN
        RETURN 'invalid_manifest';
    END IF;
    IF p->'schema' IS DISTINCT FROM '1'::jsonb THEN RETURN 'invalid_manifest'; END IF;
    IF pg_catalog.jsonb_typeof(p->'kind') IS DISTINCT FROM 'string' OR (p->>'kind') NOT IN ('skill', 'connector') THEN
        RETURN 'invalid_manifest';
    END IF;
    kind := p->>'kind';

    -- Listing text, same limits as marketplace_validate.
    IF pg_catalog.jsonb_typeof(p->'slug') IS DISTINCT FROM 'string' OR (p->>'slug') !~ '^[a-z0-9][a-z0-9-]{1,46}$' THEN
        RETURN 'invalid_slug';
    END IF;
    slug := p->>'slug';
    IF slug IN ('publish', 'mine') THEN RETURN 'reserved_slug'; END IF;
    nm := p->>'name';
    IF pg_catalog.jsonb_typeof(p->'name') IS DISTINCT FROM 'string' OR pg_catalog.btrim(nm) <> nm
       OR pg_catalog.char_length(nm) NOT BETWEEN 2 AND 40 OR nm ~ '[[:cntrl:]]' THEN
        RETURN 'invalid_name';
    END IF;
    IF pg_catalog.jsonb_typeof(p->'summary') IS DISTINCT FROM 'string' OR pg_catalog.btrim(p->>'summary') = ''
       OR pg_catalog.char_length(p->>'summary') > 200 OR (p->>'summary') ~ '[[:cntrl:]]' THEN
        RETURN 'invalid_summary';
    END IF;
    IF p ? 'description' AND (pg_catalog.jsonb_typeof(p->'description') IS DISTINCT FROM 'string'
                              OR pg_catalog.char_length(p->>'description') > 4000) THEN
        RETURN 'invalid_description';
    END IF;
    IF pg_catalog.jsonb_typeof(p->'publisher_name') IS DISTINCT FROM 'string' OR pg_catalog.btrim(p->>'publisher_name') = ''
       OR pg_catalog.char_length(p->>'publisher_name') > 60 OR (p->>'publisher_name') ~ '[[:cntrl:]]' THEN
        RETURN 'invalid_publisher_name';
    END IF;
    IF p ? 'category' AND pg_catalog.jsonb_typeof(p->'category') <> 'null' THEN
        IF pg_catalog.jsonb_typeof(p->'category') <> 'string' THEN RETURN 'invalid_manifest'; END IF;
        cat := NULLIF(pg_catalog.btrim(p->>'category'), '');
        IF cat IS NOT NULL AND NOT EXISTS (SELECT 1 FROM public.marketplace_categories mc WHERE mc.slug = cat) THEN
            RETURN 'invalid_category';
        END IF;
    END IF;
    IF p ? 'tags' AND pg_catalog.jsonb_typeof(p->'tags') <> 'null' THEN
        IF pg_catalog.jsonb_typeof(p->'tags') <> 'array'
           OR EXISTS (SELECT 1 FROM pg_catalog.jsonb_array_elements(p->'tags') e WHERE pg_catalog.jsonb_typeof(e) <> 'string') THEN
            RETURN 'invalid_manifest';
        END IF;
        BEGIN
            PERFORM public.marketplace_norm_tags(ARRAY(SELECT pg_catalog.jsonb_array_elements_text(p->'tags')));
        EXCEPTION WHEN OTHERS THEN
            RETURN SQLERRM;
        END;
    END IF;

    -- Visibility: public unless it names agents.
    IF p ? 'visibility' THEN
        IF pg_catalog.jsonb_typeof(p->'visibility') <> 'object'
           OR EXISTS (SELECT 1 FROM pg_catalog.jsonb_object_keys(p->'visibility') x WHERE x <> 'agents') THEN
            RETURN 'invalid_manifest';
        END IF;
        IF p->'visibility' ? 'agents' AND (
               pg_catalog.jsonb_typeof(p->'visibility'->'agents') <> 'array'
               OR pg_catalog.jsonb_array_length(p->'visibility'->'agents') > 10
               OR EXISTS (SELECT 1 FROM pg_catalog.jsonb_array_elements(p->'visibility'->'agents') e
                          WHERE pg_catalog.jsonb_typeof(e) <> 'string' OR (e #>> '{}') !~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$')) THEN
            RETURN 'invalid_manifest';
        END IF;
    END IF;

    IF kind = 'skill' THEN
        IF p ? 'connector' OR pg_catalog.jsonb_typeof(p->'skill') IS DISTINCT FROM 'object'
           OR EXISTS (SELECT 1 FROM pg_catalog.jsonb_object_keys(p->'skill') x WHERE x <> 'entry')
           OR pg_catalog.jsonb_typeof(p->'skill'->'entry') IS DISTINCT FROM 'string'
           OR pg_catalog.char_length(p->'skill'->>'entry') NOT BETWEEN 1 AND 200
           OR (p->'skill'->>'entry') ~ '(^[/~])|(\.\.)|\\|[[:cntrl:]]' THEN
            RETURN 'invalid_manifest';
        END IF;
        RETURN NULL;
    END IF;

    -- Connectors.
    IF p ? 'skill' OR pg_catalog.jsonb_typeof(p->'connector') IS DISTINCT FROM 'object'
       OR pg_catalog.jsonb_typeof(p->'connector'->'type') IS DISTINCT FROM 'string' THEN
        RETURN 'invalid_manifest';
    END IF;
    c := p->'connector';
    ctype := c->>'type';

    IF ctype = 'mcp' THEN
        IF EXISTS (SELECT 1 FROM pg_catalog.jsonb_object_keys(c) x WHERE x NOT IN ('type', 'url', 'auth'))
           OR NOT (c ?& ARRAY['url', 'auth']) THEN
            RETURN 'invalid_manifest';
        END IF;
        BEGIN
            PERFORM public.validate_agent_draft_content(pg_catalog.jsonb_build_object('soul', '', 'identity', '',
                'skills', '{}'::jsonb, 'custom_connectors', pg_catalog.jsonb_build_array(pg_catalog.jsonb_build_object(
                    'id', 'mcp-' || slug, 'name', nm, 'url', c->'url', 'auth', c->'auth'))));
        EXCEPTION WHEN OTHERS THEN
            RETURN SQLERRM;
        END;
        RETURN NULL;
    ELSIF ctype NOT IN ('http', 'scripts') THEN
        RETURN 'invalid_manifest';
    END IF;

    -- Tools, shared by http and scripts.
    IF pg_catalog.jsonb_typeof(c->'tools') IS DISTINCT FROM 'array'
       OR pg_catalog.jsonb_array_length(c->'tools') NOT BETWEEN 1 AND 50 THEN
        RETURN 'invalid_manifest';
    END IF;
    tool_names := '{}';
    -- Tools are exposed as <slug>__<tool>, which must stay within 64 characters and parse unambiguously.
    IF pg_catalog.strpos(slug, '--') > 0 THEN RETURN 'invalid_manifest'; END IF;

    IF ctype = 'http' THEN
        IF EXISTS (SELECT 1 FROM pg_catalog.jsonb_object_keys(c) x WHERE x NOT IN ('type', 'base_url', 'auth', 'tools'))
           OR pg_catalog.jsonb_typeof(c->'base_url') IS DISTINCT FROM 'string' OR pg_catalog.jsonb_typeof(c->'auth') IS DISTINCT FROM 'object' THEN
            RETURN 'invalid_manifest';
        END IF;
        base := c->>'base_url';
        IF pg_catalog.char_length(base) > 2048
           OR base !~ '^https://[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:443)?(/[^?#[:space:]]*)?$'
           OR base ~ '[\\?#@]' OR base ~ '(^|/)\.\.(/|$)' THEN
            RETURN 'invalid_manifest';
        END IF;
        host := pg_catalog.lower(pg_catalog.split_part(pg_catalog.split_part(pg_catalog.substr(base, 9), '/', 1), ':', 1));
        IF NOT public.marketplace_public_host(host) THEN RETURN 'connector_requires_public_https'; END IF;
        a := c->'auth';
        IF pg_catalog.jsonb_typeof(a->'type') IS DISTINCT FROM 'string' THEN RETURN 'invalid_manifest'; END IF;
        IF a->>'type' = 'none' THEN
            IF EXISTS (SELECT 1 FROM pg_catalog.jsonb_object_keys(a) x WHERE x <> 'type') THEN RETURN 'invalid_manifest'; END IF;
        ELSIF a->>'type' = 'api_key' THEN
            IF EXISTS (SELECT 1 FROM pg_catalog.jsonb_object_keys(a) x WHERE x NOT IN ('type', 'header', 'secret'))
               OR pg_catalog.jsonb_typeof(a->'header') IS DISTINCT FROM 'string' OR pg_catalog.jsonb_typeof(a->'secret') IS DISTINCT FROM 'string' THEN
                RETURN 'invalid_manifest';
            END IF;
            hdr := a->>'header';
            IF hdr !~ '^[A-Za-z0-9-]{1,64}$' OR pg_catalog.lower(hdr) IN ('host', 'cookie', 'content-length', 'transfer-encoding', 'connection')
               OR (a->>'secret') !~ '^[A-Z][A-Z0-9_]{0,63}$' THEN
                RETURN 'invalid_manifest';
            END IF;
        ELSIF a->>'type' = 'bearer' THEN
            IF EXISTS (SELECT 1 FROM pg_catalog.jsonb_object_keys(a) x WHERE x NOT IN ('type', 'secret'))
               OR pg_catalog.jsonb_typeof(a->'secret') IS DISTINCT FROM 'string' OR (a->>'secret') !~ '^[A-Z][A-Z0-9_]{0,63}$' THEN
                RETURN 'invalid_manifest';
            END IF;
        ELSE
            RETURN 'invalid_manifest';
        END IF;
    ELSE
        IF EXISTS (SELECT 1 FROM pg_catalog.jsonb_object_keys(c) x WHERE x NOT IN ('type', 'runtime', 'tools', 'egress', 'secrets'))
           OR pg_catalog.jsonb_typeof(c->'runtime') IS DISTINCT FROM 'string' OR (c->>'runtime') NOT IN ('python3', 'node') THEN
            RETURN 'invalid_manifest';
        END IF;
        IF c ? 'egress' THEN
            IF pg_catalog.jsonb_typeof(c->'egress') <> 'array' OR pg_catalog.jsonb_array_length(c->'egress') > 20 THEN
                RETURN 'invalid_manifest';
            END IF;
            FOR s IN SELECT e FROM pg_catalog.jsonb_array_elements(c->'egress') e LOOP
                IF pg_catalog.jsonb_typeof(s) <> 'string' OR (s #>> '{}') <> pg_catalog.lower(s #>> '{}')
                   OR NOT public.marketplace_public_host(s #>> '{}') THEN
                    RETURN 'invalid_manifest';
                END IF;
            END LOOP;
        END IF;
        IF c ? 'secrets' THEN
            IF pg_catalog.jsonb_typeof(c->'secrets') <> 'array' OR pg_catalog.jsonb_array_length(c->'secrets') > 10 THEN
                RETURN 'invalid_manifest';
            END IF;
            secret_names := '{}';
            FOR s IN SELECT e FROM pg_catalog.jsonb_array_elements(c->'secrets') e LOOP
                IF pg_catalog.jsonb_typeof(s) <> 'object'
                   OR EXISTS (SELECT 1 FROM pg_catalog.jsonb_object_keys(s) x WHERE x NOT IN ('name', 'description'))
                   OR pg_catalog.jsonb_typeof(s->'name') IS DISTINCT FROM 'string' OR (s->>'name') !~ '^[A-Z][A-Z0-9_]{0,63}$'
                   OR (s ? 'description' AND (pg_catalog.jsonb_typeof(s->'description') <> 'string'
                                              OR pg_catalog.char_length(s->>'description') > 500))
                   OR (s->>'name') = ANY (secret_names) THEN
                    RETURN 'invalid_manifest';
                END IF;
                secret_names := secret_names || (s->>'name');
            END LOOP;
        END IF;
    END IF;

    FOR t IN SELECT e FROM pg_catalog.jsonb_array_elements(c->'tools') e LOOP
        IF pg_catalog.jsonb_typeof(t) <> 'object'
           OR EXISTS (SELECT 1 FROM pg_catalog.jsonb_object_keys(t) x WHERE x NOT IN
                  ('name', 'description', 'input_schema', 'method', 'path', 'query', 'body', 'file', 'effect', 'timeout_s'))
           OR pg_catalog.jsonb_typeof(t->'name') IS DISTINCT FROM 'string' OR (t->>'name') !~ '^[a-z][a-z0-9_]{0,47}$'
           OR (t->>'name') = ANY (tool_names)
           OR pg_catalog.jsonb_typeof(t->'description') IS DISTINCT FROM 'string' OR pg_catalog.btrim(t->>'description') = ''
           OR pg_catalog.char_length(t->>'description') > 1000
           OR pg_catalog.jsonb_typeof(t->'input_schema') IS DISTINCT FROM 'object'
           OR pg_catalog.octet_length((t->'input_schema')::text) > 16384 THEN
            RETURN 'invalid_manifest';
        END IF;
        tool_names := tool_names || (t->>'name');
        IF pg_catalog.char_length(slug) + 2 + pg_catalog.char_length(t->>'name') > 64 THEN RETURN 'invalid_manifest'; END IF;
        IF t->'input_schema' ? 'properties' AND pg_catalog.jsonb_typeof(t->'input_schema'->'properties') <> 'object' THEN
            RETURN 'invalid_manifest';
        END IF;
        props := COALESCE(t->'input_schema'->'properties', '{}'::jsonb);

        IF ctype = 'http' THEN
            IF EXISTS (SELECT 1 FROM pg_catalog.jsonb_object_keys(t) x WHERE x IN ('file', 'effect', 'timeout_s'))
               OR pg_catalog.jsonb_typeof(t->'method') IS DISTINCT FROM 'string'
               OR (t->>'method') NOT IN ('GET', 'POST', 'PUT', 'PATCH', 'DELETE')
               OR pg_catalog.jsonb_typeof(t->'path') IS DISTINCT FROM 'string'
               OR pg_catalog.char_length(t->>'path') > 512
               OR (t->>'path') !~ '^/[A-Za-z0-9._~!$&''()*+,;=:@%/{}-]*$'
               OR (t->>'path') ~ '(^|/)\.\.(/|$)' THEN
                RETURN 'invalid_manifest';
            END IF;
            -- Every {p} is a declared property and nothing else uses braces.
            IF pg_catalog.regexp_replace(t->>'path', '\{[A-Za-z_][A-Za-z0-9_]*\}', '', 'g') ~ '[{}]' THEN
                RETURN 'invalid_manifest';
            END IF;
            FOR ph IN SELECT m[1] FROM pg_catalog.regexp_matches(t->>'path', '\{([A-Za-z_][A-Za-z0-9_]*)\}', 'g') m LOOP
                IF NOT props ? ph THEN RETURN 'invalid_manifest'; END IF;
            END LOOP;
            FOREACH k IN ARRAY ARRAY['query', 'body'] LOOP
                IF t ? k THEN
                    IF pg_catalog.jsonb_typeof(t->k) <> 'array' OR pg_catalog.jsonb_array_length(t->k) > 50 THEN
                        RETURN 'invalid_manifest';
                    END IF;
                    FOR qk IN SELECT e FROM pg_catalog.jsonb_array_elements(t->k) e LOOP
                        IF pg_catalog.jsonb_typeof(qk) <> 'string' OR NOT props ? (qk #>> '{}') THEN
                            RETURN 'invalid_manifest';
                        END IF;
                    END LOOP;
                END IF;
            END LOOP;
        ELSE
            IF EXISTS (SELECT 1 FROM pg_catalog.jsonb_object_keys(t) x WHERE x IN ('method', 'path', 'query', 'body'))
               OR pg_catalog.jsonb_typeof(t->'file') IS DISTINCT FROM 'string'
               OR (t->>'file') !~ '^tools/[A-Za-z0-9_-]+\.(py|js)$'
               OR (t->>'file') !~ (CASE WHEN c->>'runtime' = 'python3' THEN '\.py$' ELSE '\.js$' END)
               OR (t ? 'effect' AND (t->>'effect') IS DISTINCT FROM 'read' AND (t->>'effect') IS DISTINCT FROM 'write')
               OR (t ? 'effect' AND pg_catalog.jsonb_typeof(t->'effect') <> 'string') THEN
                RETURN 'invalid_manifest';
            END IF;
            IF t ? 'timeout_s' THEN
                IF pg_catalog.jsonb_typeof(t->'timeout_s') <> 'number' THEN RETURN 'invalid_manifest'; END IF;
                n := CASE WHEN (t->>'timeout_s')::numeric = pg_catalog.trunc((t->>'timeout_s')::numeric)
                               AND (t->>'timeout_s')::numeric BETWEEN 1 AND 120 THEN (t->>'timeout_s')::int END;
                IF n IS NULL THEN RETURN 'invalid_manifest'; END IF;
            END IF;
        END IF;
    END LOOP;
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_check_manifest(jsonb) FROM PUBLIC, anon, authenticated, service_role;

-- ----------------------------------------------------------------------------
-- Install gate shared by install_v2, get and search: NULL when the caller may install this version for p_agent.
-- ----------------------------------------------------------------------------
CREATE FUNCTION public.marketplace_install_check(p_user uuid, v public.marketplace_versions, p_agent text)
RETURNS text LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    ctype text := COALESCE(v.connector_type, CASE WHEN v.manifest IS NULL AND v.content ? 'url' THEN 'mcp' END);
    pl public.plugins%ROWTYPE;
BEGIN
    IF v.manifest IS NOT NULL AND NOT public.marketplace_flag_on('marketplace_bundles') THEN
        RETURN 'bundles_disabled';
    END IF;
    IF ctype = 'http' AND NOT public.marketplace_flag_on('marketplace_http_connectors') THEN
        RETURN 'http_connectors_disabled';
    END IF;
    IF ctype = 'scripts' AND NOT public.marketplace_flag_on('marketplace_script_connectors') THEN
        RETURN 'script_connectors_disabled';
    END IF;
    IF pg_catalog.cardinality(v.allowed_agents) > 0 THEN
        IF p_user IS NULL OR NOT public.marketplace_flag_on('marketplace_restricted_listings')
           OR p_agent IS NULL OR NOT (p_agent = ANY (v.allowed_agents)) THEN
            RETURN 'agent_not_allowed';
        END IF;
        SELECT * INTO pl FROM public.plugins p WHERE p.plugin_id = p_agent;
        IF NOT FOUND OR NOT public.user_can_install_plugin(p_user, pl.id) THEN
            RETURN 'agent_not_allowed';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_install_check(uuid, public.marketplace_versions, text) FROM PUBLIC, anon, authenticated, service_role;

CREATE FUNCTION public.marketplace_restricted_to(p_agents text[])
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT COALESCE(pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object('plugin_id', u.a, 'name', COALESCE(pl.display_name, u.a))
                                         ORDER BY u.ord), '[]'::jsonb)
    FROM pg_catalog.unnest(COALESCE(p_agents, '{}'::text[])) WITH ORDINALITY AS u(a, ord)
    LEFT JOIN public.plugins pl ON pl.plugin_id = u.a;
$$;
REVOKE ALL ON FUNCTION public.marketplace_restricted_to(text[]) FROM PUBLIC, anon, authenticated, service_role;

-- ----------------------------------------------------------------------------
-- Version JSON gains the bundle fields (manifest only with content).
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.marketplace_version_json(v public.marketplace_versions, p_with_content boolean)
RETURNS jsonb LANGUAGE sql STABLE SET search_path = '' AS $$
    SELECT pg_catalog.jsonb_strip_nulls(pg_catalog.jsonb_build_object(
        'id', v.id, 'version', v.version, 'name', v.name, 'summary', v.summary, 'description', v.description,
        'publisher_name', v.publisher_name, 'created_at', v.created_at, 'decision', v.decision, 'reason', v.reason,
        'category', v.category, 'tags', pg_catalog.to_jsonb(v.tags),
        'connector_type', COALESCE(v.connector_type, CASE WHEN v.manifest IS NULL AND v.content ? 'url' THEN 'mcp' END),
        'bundle_sha256', v.bundle_sha256, 'bundle_size', v.bundle_size,
        'allowed_agents', pg_catalog.to_jsonb(v.allowed_agents),
        'restricted_to', CASE WHEN pg_catalog.cardinality(v.allowed_agents) > 0 THEN public.marketplace_restricted_to(v.allowed_agents) END,
        'content', CASE WHEN p_with_content THEN v.content END,
        'manifest', CASE WHEN p_with_content THEN v.manifest END))
$$;

-- ----------------------------------------------------------------------------
-- Public reads. Restricted listings stay visible; their content is for agents that may install them.
-- ----------------------------------------------------------------------------
DROP FUNCTION public.marketplace_get(text);
CREATE FUNCTION public.marketplace_get(p_slug text, p_agent text DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    l public.marketplace_listings%ROWTYPE;
    live public.marketplace_versions%ROWTYPE;
    pending public.marketplace_versions%ROWTYPE;
    owner_view boolean;
    gate text;
    hide boolean;
BEGIN
    SELECT * INTO l FROM public.marketplace_listings WHERE slug = p_slug;
    owner_view := FOUND AND auth.uid() IS NOT NULL AND (l.publisher_id = auth.uid() OR public.is_platform_reader(auth.uid()));
    IF NOT FOUND OR (l.status <> 'live' AND NOT owner_view) THEN
        RETURN NULL;
    END IF;
    SELECT * INTO live FROM public.marketplace_versions WHERE id = l.live_version_id;
    SELECT * INTO pending FROM public.marketplace_versions WHERE id = l.pending_version_id;
    IF live.id IS NOT NULL THEN
        gate := public.marketplace_install_check(auth.uid(), live, p_agent);
    END IF;
    -- The publisher and platform readers always see their own content; everyone else needs the install check to pass.
    hide := live.id IS NOT NULL AND pg_catalog.cardinality(live.allowed_agents) > 0 AND NOT owner_view AND gate IS NOT NULL;
    RETURN pg_catalog.jsonb_strip_nulls(pg_catalog.jsonb_build_object(
        'slug', l.slug, 'kind', l.kind, 'install_count', l.install_count, 'created_at', l.created_at,
        'live', CASE WHEN l.status = 'live' OR owner_view THEN
                    CASE WHEN live.id IS NOT NULL THEN public.marketplace_version_json(live, NOT hide) END END,
        'reviewed', CASE WHEN live.id IS NOT NULL AND (l.status = 'live' OR owner_view) THEN live.decision = 'approved' END,
        'restricted_to', CASE WHEN live.id IS NOT NULL THEN public.marketplace_restricted_to(live.allowed_agents) END,
        'connector_type', CASE WHEN live.id IS NOT NULL THEN
            COALESCE(live.connector_type, CASE WHEN live.manifest IS NULL AND live.content ? 'url' THEN 'mcp' END) END,
        'installable', CASE WHEN live.id IS NOT NULL THEN gate IS NULL END,
        'status', CASE WHEN owner_view THEN l.status END,
        'review_note', CASE WHEN owner_view THEN l.review_note END,
        'pending', CASE WHEN owner_view AND pending.id IS NOT NULL THEN public.marketplace_version_json(pending, true) END,
        'is_publisher', CASE WHEN owner_view THEN l.publisher_id = auth.uid() END));
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_get(text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.marketplace_get(text, text) TO anon, authenticated, service_role;

DROP FUNCTION public.marketplace_search(text, public.marketplace_kind, int, int, text, text);
CREATE FUNCTION public.marketplace_search(p_query text DEFAULT NULL, p_kind public.marketplace_kind DEFAULT NULL,
    p_limit int DEFAULT 50, p_offset int DEFAULT 0, p_category text DEFAULT NULL, p_tag text DEFAULT NULL,
    p_agent text DEFAULT NULL)
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
                       'category', v.category, 'tags', pg_catalog.to_jsonb(v.tags),
                       'restricted_to', public.marketplace_restricted_to(v.allowed_agents),
                       'connector_type', COALESCE(v.connector_type, CASE WHEN v.manifest IS NULL AND v.content ? 'url' THEN 'mcp' END),
                       'installable', public.marketplace_install_check(auth.uid(), v, p_agent) IS NULL) AS item,
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
REVOKE ALL ON FUNCTION public.marketplace_search(text, public.marketplace_kind, int, int, text, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.marketplace_search(text, public.marketplace_kind, int, int, text, text, text) TO anon, authenticated, service_role;

-- ----------------------------------------------------------------------------
-- The legacy install RPC serves legacy versions only: a bundle or restricted version goes through marketplace_install_v2.
-- ----------------------------------------------------------------------------
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
    IF v.manifest IS NOT NULL OR pg_catalog.cardinality(v.allowed_agents) > 0 THEN
        RAISE EXCEPTION 'use_marketplace_install_v2' USING ERRCODE = 'P0001';
    END IF;
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
REVOKE ALL ON FUNCTION public.marketplace_record_install(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_record_install(text) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- Install: the one RPC Max calls. Refusals are {"error": code}.
-- ----------------------------------------------------------------------------
CREATE FUNCTION public.marketplace_install_v2(p_slug text, p_agent text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    me uuid := auth.uid();
    l public.marketplace_listings%ROWTYPE;
    v public.marketplace_versions%ROWTYPE;
    code text;
    inserted boolean;
BEGIN
    IF me IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    SELECT * INTO l FROM public.marketplace_listings WHERE slug = p_slug AND status = 'live';
    IF NOT FOUND THEN
        RETURN pg_catalog.jsonb_build_object('error', 'not_found');
    END IF;
    SELECT * INTO v FROM public.marketplace_versions WHERE id = l.live_version_id;
    IF NOT FOUND THEN
        RETURN pg_catalog.jsonb_build_object('error', 'not_found');
    END IF;
    code := public.marketplace_install_check(me, v, p_agent);
    IF code IS NOT NULL THEN
        RETURN pg_catalog.jsonb_build_object('error', code);
    END IF;
    INSERT INTO public.marketplace_installs (listing_id, user_id, version_id)
        VALUES (l.id, me, v.id)
        ON CONFLICT (listing_id, user_id) DO UPDATE SET version_id = excluded.version_id, installed_at = now()
        RETURNING (xmax = 0) INTO inserted;
    IF inserted THEN
        UPDATE public.marketplace_listings SET install_count = install_count + 1 WHERE id = l.id;
    END IF;
    RETURN pg_catalog.jsonb_build_object('slug', l.slug, 'kind', l.kind, 'version', v.version, 'name', v.name,
        'summary', v.summary, 'reviewed', v.decision = 'approved',
        'connector_type', COALESCE(v.connector_type, CASE WHEN v.manifest IS NULL AND v.content ? 'url' THEN 'mcp' END),
        'manifest', v.manifest, 'content', v.content, 'bundle_path', v.bundle_path, 'bundle_sha256', v.bundle_sha256,
        'bundle_size', v.bundle_size, 'allowed_agents', pg_catalog.to_jsonb(v.allowed_agents));
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_install_v2(text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_install_v2(text, text) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- Service-only: a bundle submission, called by the marketplace-bundle edge function after it verified the user's JWT,
-- validated the zip and stored it. Same rules as marketplace_submit, for p_user.
-- ----------------------------------------------------------------------------
CREATE FUNCTION public.marketplace_submit_bundle(p_user uuid, p_manifest jsonb, p_bundle_path text, p_sha256 text, p_size int,
    p_skip_review boolean DEFAULT false)
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
    skip boolean;
    l public.marketplace_listings%ROWTYPE;
    live_decision text;
    next_version int;
    vid uuid;
    v_slug text;
    cat text;
    new_tags text[];
BEGIN
    IF me IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    IF NOT public.marketplace_flag_on('marketplace_bundles') THEN
        RETURN pg_catalog.jsonb_build_object('error', 'bundles_disabled');
    END IF;
    IF p_size IS NOT NULL AND p_size > 10 * 1024 * 1024 THEN
        RETURN pg_catalog.jsonb_build_object('error', 'bundle_too_large');
    END IF;
    IF p_bundle_path IS NULL OR p_bundle_path !~ '^bundles/[0-9a-f]{64}\.zip$' OR p_sha256 IS NULL OR p_sha256 !~ '^[0-9a-f]{64}$'
       OR p_bundle_path <> 'bundles/' || p_sha256 || '.zip' OR p_size IS NULL OR p_size < 1 THEN
        RETURN pg_catalog.jsonb_build_object('error', 'invalid_bundle');
    END IF;
    code := public.marketplace_check_manifest(p_manifest);
    IF code IS NOT NULL THEN
        RETURN pg_catalog.jsonb_build_object('error', code);
    END IF;

    v_kind := (p_manifest->>'kind')::public.marketplace_kind;
    v_slug := p_manifest->>'slug';
    ctype := p_manifest->'connector'->>'type';
    scripts := COALESCE(ctype = 'scripts', false);
    cat := NULLIF(pg_catalog.btrim(COALESCE(p_manifest->>'category', '')), '');
    new_tags := public.marketplace_norm_tags(ARRAY(SELECT pg_catalog.jsonb_array_elements_text(
        COALESCE(NULLIF(p_manifest->'tags', 'null'::jsonb), '[]'::jsonb))));
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
    skip := NOT scripts AND (COALESCE(p_skip_review, false) OR public.marketplace_open_publishing());

    -- Platform admins and trusted publishers have no daily cap; everyone else gets 20 a day.
    IF NOT public.marketplace_user_can_skip_review(me) AND (SELECT pg_catalog.count(*) FROM public.marketplace_versions
            WHERE created_by = me AND created_at > now() - interval '1 day') >= 20 THEN
        RETURN pg_catalog.jsonb_build_object('error', 'submission_rate_limited');
    END IF;

    -- Every refusal below concerns a listing that already existed, so nothing is left behind by returning it.
    INSERT INTO public.marketplace_listings (kind, slug, publisher_id)
        VALUES (v_kind, v_slug, me) ON CONFLICT (slug) DO NOTHING;
    SELECT * INTO l FROM public.marketplace_listings WHERE slug = v_slug FOR UPDATE;
    IF l.publisher_id <> me THEN
        RETURN pg_catalog.jsonb_build_object('error', 'slug_taken');
    END IF;
    IF l.kind <> v_kind THEN
        RETURN pg_catalog.jsonb_build_object('error', 'kind_mismatch');
    END IF;
    IF l.status = 'suspended' THEN
        RETURN pg_catalog.jsonb_build_object('error', 'listing_suspended');
    END IF;
    UPDATE public.marketplace_versions SET decision = 'superseded', decided_at = now()
        WHERE id = l.pending_version_id AND decision IS NULL;
    SELECT COALESCE(pg_catalog.max(version), 0) + 1 INTO next_version
        FROM public.marketplace_versions WHERE listing_id = l.id;
    INSERT INTO public.marketplace_versions (listing_id, version, name, summary, description, publisher_name, content, created_by,
                                             decision, decided_by, decided_at, category, tags,
                                             manifest, connector_type, bundle_path, bundle_sha256, bundle_size, allowed_agents)
        VALUES (l.id, next_version, p_manifest->>'name', p_manifest->>'summary', COALESCE(p_manifest->>'description', ''),
                pg_catalog.btrim(p_manifest->>'publisher_name'), '{}'::jsonb, me,
                CASE WHEN skip THEN 'unreviewed' END, CASE WHEN skip THEN me END, CASE WHEN skip THEN now() END, cat, new_tags,
                p_manifest, ctype, p_bundle_path, p_sha256, p_size, agents)
        RETURNING id INTO vid;
    IF skip THEN
        UPDATE public.marketplace_listings SET live_version_id = vid, pending_version_id = NULL, review_note = NULL,
            status = 'live', updated_at = now() WHERE id = l.id;
        INSERT INTO public.platform_audit_log (actor, action, detail)
            VALUES (me, 'marketplace.publish_unreviewed',
                    pg_catalog.jsonb_strip_nulls(pg_catalog.jsonb_build_object('slug', l.slug, 'kind', l.kind,
                        'version', next_version, 'bundle', true, 'connector_type', ctype)));
        RETURN pg_catalog.jsonb_build_object('slug', l.slug, 'version', next_version, 'status', 'live', 'reviewed', false);
    END IF;
    UPDATE public.marketplace_listings SET pending_version_id = vid, review_note = NULL, updated_at = now(),
        -- A live listing keeps serving its live version; anything else (rejected, withdrawn) waits for review unlisted.
        status = (CASE WHEN l.status = 'live' THEN 'live' ELSE 'pending_review' END)::public.marketplace_status
        WHERE id = l.id;
    INSERT INTO public.platform_audit_log (actor, action, detail)
        VALUES (me, 'marketplace.submit', pg_catalog.jsonb_strip_nulls(pg_catalog.jsonb_build_object('slug', l.slug,
            'kind', l.kind, 'version', next_version, 'bundle', true, 'connector_type', ctype)));
    SELECT v.decision INTO live_decision FROM public.marketplace_versions v WHERE v.id = l.live_version_id;
    RETURN pg_catalog.jsonb_strip_nulls(pg_catalog.jsonb_build_object('slug', l.slug, 'version', next_version,
        'status', 'pending_review', 'reviewed', CASE WHEN l.live_version_id IS NOT NULL THEN live_decision = 'approved' END));
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_submit_bundle(uuid, jsonb, text, text, int, boolean) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.marketplace_submit_bundle(uuid, jsonb, text, text, int, boolean) TO service_role;

-- Service-only: the stored bundle's path for the version's creator or a platform reader, else NULL.
CREATE FUNCTION public.marketplace_bundle_access(p_user uuid, p_version_id uuid)
RETURNS text LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT v.bundle_path FROM public.marketplace_versions v
    WHERE v.id = p_version_id AND v.bundle_path IS NOT NULL AND p_user IS NOT NULL
      AND (public.is_platform_reader(p_user) OR v.created_by = p_user);
$$;
REVOKE ALL ON FUNCTION public.marketplace_bundle_access(uuid, uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.marketplace_bundle_access(uuid, uuid) TO service_role;

-- ----------------------------------------------------------------------------
-- Agent defaults serve markdown only: bundle and restricted skills are refused and skipped.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.marketplace_agent_default_set(p_agent_id uuid, p_slug text, p_on boolean)
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
        -- Defaults are served as markdown: a bundle or agent-restricted live version cannot be one.
        IF EXISTS (SELECT 1 FROM public.marketplace_versions v WHERE v.id = l.live_version_id
                   AND (v.bundle_path IS NOT NULL OR pg_catalog.cardinality(v.allowed_agents) > 0)) THEN
            RAISE EXCEPTION 'default_requires_legacy_skill' USING ERRCODE = 'P0001';
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

CREATE OR REPLACE FUNCTION public.marketplace_agent_skills(p_plugin_id text)
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
        WHERE d.agent_id = a.id AND l.status = 'live' AND l.kind = 'skill'
          AND v.bundle_path IS NULL AND pg_catalog.cardinality(v.allowed_agents) = 0), '[]'::jsonb));
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_agent_skills(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_agent_skills(text) TO authenticated, service_role;

COMMIT;
