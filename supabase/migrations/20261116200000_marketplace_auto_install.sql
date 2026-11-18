-- Marketplace auto-install: a skill listing can be installed automatically, locked, into any plugin id (Optimist, Max, a
-- managed agent). One mechanism: marketplace_auto_installs replaces marketplace_agent_defaults. Restricted listings are
-- hidden from everyone but their allowed agents, the publisher, platform readers and reviewers.
-- Auto-install fetches are never counted as installs (install_count and marketplace_installs are untouched).

CREATE TABLE public.marketplace_auto_installs (
    listing_id uuid NOT NULL REFERENCES public.marketplace_listings(id) ON DELETE CASCADE,
    plugin_id  text NOT NULL CHECK (plugin_id ~ '^[a-z0-9][a-z0-9._-]{1,127}$'),
    created_by uuid REFERENCES auth.users(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (listing_id, plugin_id)
);
ALTER TABLE public.marketplace_auto_installs ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.marketplace_auto_installs FROM PUBLIC, anon, authenticated, service_role;

INSERT INTO public.marketplace_auto_installs (listing_id, plugin_id, created_by, created_at)
SELECT d.listing_id, a.plugin_id, d.added_by, d.added_at
FROM public.marketplace_agent_defaults d
JOIN public.agents a ON a.id = d.agent_id
WHERE a.plugin_id ~ '^[a-z0-9][a-z0-9._-]{1,127}$'
ON CONFLICT DO NOTHING;

DROP FUNCTION public.marketplace_listing_agents(text);
DROP FUNCTION public.marketplace_agent_default_set(uuid, text, boolean);
DROP FUNCTION public.marketplace_agent_defaults(uuid);
DROP TABLE public.marketplace_agent_defaults;

-- ----------------------------------------------------------------------------
-- A new live version that no longer allows a plugin id drops that auto-install, in the transaction that sets it live
-- (review approve, skip-review publish, v2 bundle publish all go through this column).
-- ----------------------------------------------------------------------------
CREATE FUNCTION public.marketplace_auto_install_prune() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    allowed text[];
    dropped text[];
BEGIN
    SELECT v.allowed_agents INTO allowed FROM public.marketplace_versions v WHERE v.id = NEW.live_version_id;
    IF pg_catalog.cardinality(COALESCE(allowed, '{}'::text[])) = 0 THEN
        RETURN NEW;
    END IF;
    WITH d AS (
        DELETE FROM public.marketplace_auto_installs a
         WHERE a.listing_id = NEW.id AND NOT (a.plugin_id = ANY (allowed))
        RETURNING a.plugin_id)
    SELECT pg_catalog.array_agg(d.plugin_id ORDER BY d.plugin_id) INTO dropped FROM d;
    IF dropped IS NOT NULL THEN
        INSERT INTO public.platform_audit_log (actor, action, detail)
            VALUES (auth.uid(), 'marketplace.auto_install_dropped',
                    pg_catalog.jsonb_build_object('slug', NEW.slug, 'plugin_ids', pg_catalog.to_jsonb(dropped)));
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_auto_install_prune() FROM PUBLIC, anon, authenticated, service_role;
CREATE TRIGGER marketplace_auto_install_prune AFTER UPDATE OF live_version_id ON public.marketplace_listings
    FOR EACH ROW WHEN (NEW.live_version_id IS DISTINCT FROM OLD.live_version_id)
    EXECUTE FUNCTION public.marketplace_auto_install_prune();

-- ----------------------------------------------------------------------------
-- Visibility. A restricted live version (allowed_agents non-empty) is visible only to: a listed agent whose caller may use
-- that plugin (needs the restricted-listings flag, like install), the publisher, platform readers and reviewers.
-- ----------------------------------------------------------------------------
CREATE FUNCTION public.marketplace_restricted_hidden(p_publisher uuid, p_allowed text[], p_agent text)
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT pg_catalog.cardinality(COALESCE(p_allowed, '{}'::text[])) > 0
       AND NOT (
            auth.uid() IS NOT NULL AND (p_publisher = auth.uid() OR public.is_platform_reader(auth.uid())
                                        OR public.is_marketplace_reviewer(auth.uid()))
         OR (auth.uid() IS NOT NULL AND p_agent IS NOT NULL AND p_agent = ANY (p_allowed)
             AND public.marketplace_flag_on('marketplace_restricted_listings')
             AND EXISTS (SELECT 1 FROM public.plugins pl WHERE pl.plugin_id = p_agent
                                                           AND public.user_can_install_plugin(auth.uid(), pl.id))));
$$;
REVOKE ALL ON FUNCTION public.marketplace_restricted_hidden(uuid, text[], text) FROM PUBLIC, anon, authenticated, service_role;

-- Display names for plugin ids: the plugin's display name, else a managed agent's name, else the id.
CREATE FUNCTION public.marketplace_plugin_names(p_ids text[])
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT COALESCE(pg_catalog.jsonb_agg(COALESCE(
               (SELECT pl.display_name FROM public.plugins pl WHERE pl.plugin_id = u.a LIMIT 1),
               (SELECT ag.name FROM public.agents ag WHERE ag.plugin_id = u.a LIMIT 1), u.a) ORDER BY u.ord), '[]'::jsonb)
    FROM pg_catalog.unnest(COALESCE(p_ids, '{}'::text[])) WITH ORDINALITY AS u(a, ord);
$$;
REVOKE ALL ON FUNCTION public.marketplace_plugin_names(text[]) FROM PUBLIC, anon, authenticated, service_role;

CREATE OR REPLACE FUNCTION public.marketplace_get(p_slug text, p_agent text DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    l public.marketplace_listings%ROWTYPE;
    live public.marketplace_versions%ROWTYPE;
    pending public.marketplace_versions%ROWTYPE;
    owner_view boolean;
    gate text;
    ai text[];
BEGIN
    SELECT * INTO l FROM public.marketplace_listings WHERE slug = p_slug;
    owner_view := FOUND AND auth.uid() IS NOT NULL AND (l.publisher_id = auth.uid() OR public.is_platform_reader(auth.uid())
                                                         OR public.is_marketplace_reviewer(auth.uid()));
    IF NOT FOUND OR (l.status <> 'live' AND NOT owner_view) THEN
        RETURN NULL;
    END IF;
    SELECT * INTO live FROM public.marketplace_versions WHERE id = l.live_version_id;
    SELECT * INTO pending FROM public.marketplace_versions WHERE id = l.pending_version_id;
    -- A hidden restricted listing is indistinguishable from a missing one.
    IF live.id IS NOT NULL AND NOT owner_view AND public.marketplace_restricted_hidden(l.publisher_id, live.allowed_agents, p_agent) THEN
        RETURN NULL;
    END IF;
    IF live.id IS NOT NULL THEN
        gate := public.marketplace_install_check(auth.uid(), live, p_agent);
    END IF;
    IF owner_view THEN
        SELECT COALESCE(pg_catalog.array_agg(a.plugin_id ORDER BY a.plugin_id), '{}'::text[]) INTO ai
            FROM public.marketplace_auto_installs a WHERE a.listing_id = l.id;
    END IF;
    RETURN pg_catalog.jsonb_strip_nulls(pg_catalog.jsonb_build_object(
        'slug', l.slug, 'kind', l.kind, 'install_count', l.install_count, 'created_at', l.created_at,
        'live', CASE WHEN l.status = 'live' OR owner_view THEN
                    CASE WHEN live.id IS NOT NULL THEN public.marketplace_version_json(live, true) END END,
        'reviewed', CASE WHEN live.id IS NOT NULL AND (l.status = 'live' OR owner_view) THEN live.decision = 'approved' END,
        'restricted_to', CASE WHEN live.id IS NOT NULL THEN public.marketplace_restricted_to(live.allowed_agents) END,
        'connector_type', CASE WHEN live.id IS NOT NULL THEN
            COALESCE(live.connector_type, CASE WHEN live.manifest IS NULL AND live.content ? 'url' THEN 'mcp' END) END,
        'installable', CASE WHEN live.id IS NOT NULL THEN gate IS NULL END,
        'status', CASE WHEN owner_view THEN l.status END,
        'review_note', CASE WHEN owner_view THEN l.review_note END,
        'pending', CASE WHEN owner_view AND pending.id IS NOT NULL THEN public.marketplace_version_json(pending, true) END,
        'is_publisher', CASE WHEN owner_view THEN l.publisher_id = auth.uid() END,
        'auto_install', CASE WHEN owner_view THEN pg_catalog.to_jsonb(ai) END,
        'auto_install_names', CASE WHEN owner_view THEN public.marketplace_plugin_names(ai) END));
END;
$$;

CREATE OR REPLACE FUNCTION public.marketplace_search(p_query text DEFAULT NULL, p_kind public.marketplace_kind DEFAULT NULL,
    p_limit int DEFAULT 50, p_offset int DEFAULT 0, p_category text DEFAULT NULL, p_tag text DEFAULT NULL,
    p_agent text DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    q text := NULLIF(pg_catalog.btrim(COALESCE(p_query, '')), '');
    pat text;
    cat text := NULLIF(pg_catalog.btrim(COALESCE(p_category, '')), '');
    tag text := NULLIF(pg_catalog.lower(pg_catalog.btrim(COALESCE(p_tag, ''))), '');
    reader boolean := auth.uid() IS NOT NULL AND public.is_platform_reader(auth.uid());
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
                       'installable', public.marketplace_install_check(auth.uid(), v, p_agent) IS NULL)
                   || CASE WHEN reader THEN pg_catalog.jsonb_build_object(
                          'auto_install', (SELECT COALESCE(pg_catalog.jsonb_agg(a.plugin_id ORDER BY a.plugin_id), '[]'::jsonb)
                                           FROM public.marketplace_auto_installs a WHERE a.listing_id = l.id),
                          'auto_install_names', public.marketplace_plugin_names(
                                           (SELECT pg_catalog.array_agg(a.plugin_id ORDER BY a.plugin_id)
                                            FROM public.marketplace_auto_installs a WHERE a.listing_id = l.id)))
                       ELSE '{}'::jsonb END AS item,
                   pg_catalog.row_number() OVER (ORDER BY l.install_count DESC, v.decided_at DESC, l.slug) AS ord
            FROM public.marketplace_listings l
            JOIN public.marketplace_versions v ON v.id = l.live_version_id
            WHERE l.status = 'live'
              AND NOT public.marketplace_restricted_hidden(l.publisher_id, v.allowed_agents, p_agent)
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

REVOKE ALL ON FUNCTION public.marketplace_get(text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.marketplace_get(text, text) TO anon, authenticated, service_role;
REVOKE ALL ON FUNCTION public.marketplace_search(text, public.marketplace_kind, int, int, text, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.marketplace_search(text, public.marketplace_kind, int, int, text, text, text) TO anon, authenticated, service_role;

-- Category and tag counts skip what the viewer cannot see.
DROP FUNCTION public.marketplace_categories();
CREATE FUNCTION public.marketplace_categories(p_agent text DEFAULT NULL)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT COALESCE(pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object('slug', c.slug, 'name', c.name,
               'description', c.description, 'sort', c.sort, 'count', (
                   SELECT pg_catalog.count(*) FROM public.marketplace_listings l
                   JOIN public.marketplace_versions v ON v.id = l.live_version_id
                   WHERE l.status = 'live' AND v.category = c.slug
                     AND NOT public.marketplace_restricted_hidden(l.publisher_id, v.allowed_agents, p_agent))) ORDER BY c.sort, c.name), '[]'::jsonb)
    FROM public.marketplace_categories c;
$$;
REVOKE ALL ON FUNCTION public.marketplace_categories(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.marketplace_categories(text) TO anon, authenticated, service_role;

DROP FUNCTION public.marketplace_tags(int);
CREATE FUNCTION public.marketplace_tags(p_limit int DEFAULT 30, p_agent text DEFAULT NULL)
RETURNS jsonb LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT COALESCE(pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object('tag', t.tag, 'count', t.n) ORDER BY t.n DESC, t.tag), '[]'::jsonb)
    FROM (
        SELECT x.tag, pg_catalog.count(*) AS n
        FROM public.marketplace_listings l
        JOIN public.marketplace_versions v ON v.id = l.live_version_id
        CROSS JOIN LATERAL pg_catalog.unnest(v.tags) AS x(tag)
        WHERE l.status = 'live'
          AND NOT public.marketplace_restricted_hidden(l.publisher_id, v.allowed_agents, p_agent)
        GROUP BY x.tag
        ORDER BY n DESC, x.tag
        LIMIT LEAST(GREATEST(COALESCE(p_limit, 30), 1), 100)
    ) t;
$$;
REVOKE ALL ON FUNCTION public.marketplace_tags(int, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.marketplace_tags(int, text) TO anon, authenticated, service_role;

-- Bundle read for the version's creator, platform readers and reviewers (body from 20261116195000; reviewer added).
CREATE OR REPLACE FUNCTION public.marketplace_bundle_access(p_user uuid, p_version_id uuid)
RETURNS text LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT v.bundle_path FROM public.marketplace_versions v
    WHERE v.id = p_version_id AND v.bundle_path IS NOT NULL AND p_user IS NOT NULL
      AND (public.is_platform_reader(p_user) OR public.is_marketplace_reviewer(p_user) OR v.created_by = p_user);
$$;
REVOKE ALL ON FUNCTION public.marketplace_bundle_access(uuid, uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.marketplace_bundle_access(uuid, uuid) TO service_role;

-- ----------------------------------------------------------------------------
-- Admin: change the set of plugin ids a skill listing is auto-installed into. Refusals are {"ok":false,"reason":code}.
-- One internal apply serves both the replace (set) and the single-id toggle: it takes the listing row lock, then derives
-- the new set (the given ids, or the current rows +/- one id), validates, writes and audits only a change.
-- A toggle off skips the live/skill/restriction checks: removal must work on a listing that has since broken.
-- ----------------------------------------------------------------------------
CREATE FUNCTION public.marketplace_auto_install_apply(p_slug text, p_plugin_ids text[], p_toggle_id text, p_on boolean)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    l public.marketplace_listings%ROWTYPE;
    live public.marketplace_versions%ROWTYPE;
    ids text[];
    tid text := pg_catalog.btrim(p_toggle_id);
    removing boolean := p_toggle_id IS NOT NULL AND NOT COALESCE(p_on, false);
    before_ids text[];
    after_ids text[];
BEGIN
    SELECT * INTO l FROM public.marketplace_listings WHERE slug = p_slug FOR UPDATE;
    IF NOT FOUND THEN
        RETURN pg_catalog.jsonb_build_object('ok', false, 'reason', 'not_found');
    END IF;
    SELECT COALESCE(pg_catalog.array_agg(a.plugin_id ORDER BY a.plugin_id), '{}'::text[]) INTO before_ids
        FROM public.marketplace_auto_installs a WHERE a.listing_id = l.id;
    IF p_toggle_id IS NULL THEN
        SELECT COALESCE(pg_catalog.array_agg(DISTINCT pg_catalog.btrim(x) ORDER BY pg_catalog.btrim(x)), '{}'::text[]) INTO ids
            FROM pg_catalog.unnest(COALESCE(p_plugin_ids, '{}'::text[])) x WHERE x IS NOT NULL;
    ELSE
        IF tid IS NULL OR tid !~ '^[a-z0-9][a-z0-9._-]{1,127}$' THEN
            RETURN pg_catalog.jsonb_build_object('ok', false, 'reason', 'invalid_plugin_id');
        END IF;
        SELECT COALESCE(pg_catalog.array_agg(x ORDER BY x), '{}'::text[]) INTO ids
            FROM (SELECT x FROM pg_catalog.unnest(before_ids) x WHERE x <> tid
                  UNION SELECT tid WHERE COALESCE(p_on, false)) s;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_catalog.unnest(ids) x WHERE x !~ '^[a-z0-9][a-z0-9._-]{1,127}$') THEN
        RETURN pg_catalog.jsonb_build_object('ok', false, 'reason', 'invalid_plugin_id');
    END IF;
    IF pg_catalog.cardinality(ids) > 10 THEN
        RETURN pg_catalog.jsonb_build_object('ok', false, 'reason', 'too_many');
    END IF;
    IF pg_catalog.cardinality(ids) > 0 AND NOT removing THEN
        IF l.kind <> 'skill' THEN
            RETURN pg_catalog.jsonb_build_object('ok', false, 'reason', 'not_skill');
        END IF;
        SELECT * INTO live FROM public.marketplace_versions WHERE id = l.live_version_id;
        IF l.status <> 'live' OR NOT FOUND THEN
            RETURN pg_catalog.jsonb_build_object('ok', false, 'reason', 'not_live');
        END IF;
        IF pg_catalog.cardinality(live.allowed_agents) > 0
           AND EXISTS (SELECT 1 FROM pg_catalog.unnest(ids) x WHERE NOT (x = ANY (live.allowed_agents))) THEN
            RETURN pg_catalog.jsonb_build_object('ok', false, 'reason', 'not_allowed_agent');
        END IF;
    END IF;
    DELETE FROM public.marketplace_auto_installs a WHERE a.listing_id = l.id AND NOT (a.plugin_id = ANY (ids));
    INSERT INTO public.marketplace_auto_installs (listing_id, plugin_id, created_by)
        SELECT l.id, x, auth.uid() FROM pg_catalog.unnest(ids) x ON CONFLICT DO NOTHING;
    after_ids := ids;
    IF before_ids IS DISTINCT FROM after_ids THEN
        INSERT INTO public.platform_audit_log (actor, action, detail)
            VALUES (auth.uid(), 'marketplace.auto_install_set',
                    pg_catalog.jsonb_build_object('slug', l.slug, 'plugin_ids', pg_catalog.to_jsonb(after_ids),
                                                  'previous', pg_catalog.to_jsonb(before_ids)));
    END IF;
    RETURN pg_catalog.jsonb_build_object('ok', true, 'auto_install', pg_catalog.to_jsonb(after_ids));
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_auto_install_apply(text, text[], text, boolean) FROM PUBLIC, anon, authenticated, service_role;

CREATE FUNCTION public.marketplace_set_auto_install(p_slug text, p_plugin_ids text[])
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_user_admin(auth.uid()) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    RETURN public.marketplace_auto_install_apply(p_slug, p_plugin_ids, NULL, NULL);
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_set_auto_install(text, text[]) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_set_auto_install(text, text[]) TO authenticated, service_role;

-- Admin: add or remove one plugin id (idempotent). Same refusals and audit as the set.
CREATE FUNCTION public.marketplace_toggle_auto_install(p_slug text, p_plugin_id text, p_on boolean)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_user_admin(auth.uid()) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    RETURN public.marketplace_auto_install_apply(p_slug, NULL, COALESCE(p_plugin_id, ''), p_on);
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_toggle_auto_install(text, text, boolean) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_toggle_auto_install(text, text, boolean) TO authenticated, service_role;

-- Reverse view for an agent page: every listing auto-installed into one plugin id, in any status (admins see broken ones).
CREATE FUNCTION public.marketplace_auto_installs_for(p_plugin_id text)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_platform_reader(auth.uid()) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    RETURN pg_catalog.jsonb_build_object('listings', COALESCE((
        SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_strip_nulls(pg_catalog.jsonb_build_object(
                   'slug', l.slug, 'name', COALESCE(v.name, l.slug), 'kind', l.kind, 'version', v.version,
                   'status', l.status, 'reviewed', v.decision = 'approved',
                   'restricted', pg_catalog.cardinality(COALESCE(v.allowed_agents, '{}'::text[])) > 0,
                   'auto_install', (SELECT COALESCE(pg_catalog.jsonb_agg(a2.plugin_id ORDER BY a2.plugin_id), '[]'::jsonb)
                                    FROM public.marketplace_auto_installs a2 WHERE a2.listing_id = l.id),
                   'added_at', a.created_at)) ORDER BY COALESCE(v.name, l.slug), l.slug)
        FROM public.marketplace_auto_installs a
        JOIN public.marketplace_listings l ON l.id = a.listing_id
        LEFT JOIN public.marketplace_versions v ON v.id = l.live_version_id
        WHERE a.plugin_id = p_plugin_id), '[]'::jsonb));
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_auto_installs_for(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_auto_installs_for(text) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- Runtime: may the caller use this plugin id (an installed or editing person of a managed agent, anyone who may install the
-- plugin, a platform admin)? Shared by the list and the fetch.
-- ----------------------------------------------------------------------------
CREATE FUNCTION public.marketplace_can_use_plugin(p_user uuid, p_plugin_id text)
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT p_user IS NOT NULL AND (
        public.is_user_admin(p_user)
        OR EXISTS (SELECT 1 FROM public.agents a
                    WHERE a.plugin_id = p_plugin_id
                      AND (EXISTS (SELECT 1 FROM public.agent_installs i WHERE i.agent_id = a.id AND i.user_id = p_user AND i.state <> 'removed')
                           OR public.has_agent_permission(a.id, 'agents.edit')))
        OR EXISTS (SELECT 1 FROM public.plugins p WHERE p.plugin_id = p_plugin_id AND public.user_can_install_plugin(p_user, p.id)));
$$;
REVOKE ALL ON FUNCTION public.marketplace_can_use_plugin(uuid, text) FROM PUBLIC, anon, authenticated, service_role;

CREATE FUNCTION public.marketplace_auto_install_skills(p_plugin_id text)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    me uuid := auth.uid();
BEGIN
    IF me IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    IF NOT public.marketplace_can_use_plugin(me, p_plugin_id) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    RETURN pg_catalog.jsonb_build_object('skills', COALESCE((
        SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_strip_nulls(pg_catalog.jsonb_build_object(
                   'slug', l.slug, 'name', v.name, 'summary', v.summary, 'version', v.version,
                   'reviewed', v.decision = 'approved', 'bundle', v.bundle_path IS NOT NULL,
                   'markdown', CASE WHEN v.bundle_path IS NULL THEN v.content->>'markdown' END,
                   'bundle_sha256', v.bundle_sha256, 'bundle_size', v.bundle_size)) ORDER BY l.slug)
        FROM public.marketplace_auto_installs a
        JOIN public.marketplace_listings l ON l.id = a.listing_id
        JOIN public.marketplace_versions v ON v.id = l.live_version_id
        WHERE a.plugin_id = p_plugin_id AND l.status = 'live' AND l.kind = 'skill'
          AND public.marketplace_install_check(me, v, p_plugin_id) IS NULL), '[]'::jsonb));
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_auto_install_skills(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_auto_install_skills(text) TO authenticated, service_role;

-- Released Max (<= 1.2.29) still reads marketplace_agent_skills for managed agents. Kept, over the new table, with its old
-- answer: single-file, unrestricted skills only, markdown inline. Drop once every pod runs a Max that reads the new RPC.
CREATE OR REPLACE FUNCTION public.marketplace_agent_skills(p_plugin_id text)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    me uuid := auth.uid();
BEGIN
    IF me IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    IF NOT public.marketplace_can_use_plugin(me, p_plugin_id) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    RETURN pg_catalog.jsonb_build_object('skills', COALESCE((
        SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
                   'slug', l.slug, 'name', v.name, 'summary', v.summary, 'version', v.version,
                   'reviewed', v.decision = 'approved', 'markdown', v.content->>'markdown') ORDER BY l.slug)
        FROM public.marketplace_auto_installs a
        JOIN public.marketplace_listings l ON l.id = a.listing_id
        JOIN public.marketplace_versions v ON v.id = l.live_version_id
        WHERE a.plugin_id = p_plugin_id AND l.status = 'live' AND l.kind = 'skill'
          AND v.bundle_path IS NULL AND pg_catalog.cardinality(v.allowed_agents) = 0), '[]'::jsonb));
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_agent_skills(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_agent_skills(text) TO authenticated, service_role;

-- The fetch behind a bundle entry: same JSON as marketplace_install_v2 but nothing is recorded or counted.
CREATE FUNCTION public.marketplace_auto_install_fetch(p_slug text, p_plugin_id text)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    me uuid := auth.uid();
    l public.marketplace_listings%ROWTYPE;
    v public.marketplace_versions%ROWTYPE;
    code text;
BEGIN
    IF me IS NULL THEN
        RAISE EXCEPTION 'Not signed in' USING ERRCODE = '42501';
    END IF;
    IF NOT public.marketplace_can_use_plugin(me, p_plugin_id) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    SELECT l2.* INTO l FROM public.marketplace_listings l2
        JOIN public.marketplace_auto_installs a ON a.listing_id = l2.id
        WHERE l2.slug = p_slug AND l2.status = 'live' AND l2.kind = 'skill' AND a.plugin_id = p_plugin_id;
    IF NOT FOUND THEN
        RETURN pg_catalog.jsonb_build_object('error', 'not_found');
    END IF;
    SELECT * INTO v FROM public.marketplace_versions WHERE id = l.live_version_id;
    IF NOT FOUND THEN
        RETURN pg_catalog.jsonb_build_object('error', 'not_found');
    END IF;
    code := public.marketplace_install_check(me, v, p_plugin_id);
    IF code IS NOT NULL THEN
        RETURN pg_catalog.jsonb_build_object('error', code);
    END IF;
    RETURN pg_catalog.jsonb_build_object('slug', l.slug, 'kind', l.kind, 'version', v.version, 'name', v.name,
        'summary', v.summary, 'reviewed', v.decision = 'approved',
        'connector_type', COALESCE(v.connector_type, CASE WHEN v.manifest IS NULL AND v.content ? 'url' THEN 'mcp' END),
        'manifest', v.manifest, 'content', v.content, 'bundle_path', v.bundle_path, 'bundle_sha256', v.bundle_sha256,
        'bundle_size', v.bundle_size, 'allowed_agents', pg_catalog.to_jsonb(v.allowed_agents));
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_auto_install_fetch(text, text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_auto_install_fetch(text, text) TO authenticated, service_role;

-- ----------------------------------------------------------------------------
-- Compat for the live console (optimist main): the per-agent defaults RPCs, same signatures, shapes, permissions and
-- refusal codes as before, over marketplace_auto_installs keyed by the agent's plugin id. Drop when console #111 is live.
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
                   'reviewed', COALESCE(lv.decision = 'approved', false), 'status', l.status, 'added_at', ai.created_at)
               ORDER BY l.slug)
        FROM public.agents a
        JOIN public.marketplace_auto_installs ai ON ai.plugin_id = a.plugin_id
        JOIN public.marketplace_listings l ON l.id = ai.listing_id
        LEFT JOIN public.marketplace_versions lv ON lv.id = l.live_version_id
        LEFT JOIN public.marketplace_versions pv ON pv.id = l.pending_version_id
        WHERE a.id = p_agent_id), '[]'::jsonb);
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_agent_defaults(uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_agent_defaults(uuid) TO authenticated, service_role;

CREATE FUNCTION public.marketplace_agent_default_set(p_agent_id uuid, p_slug text, p_on boolean)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    a public.agents%ROWTYPE;
    l public.marketplace_listings%ROWTYPE;
    had boolean;
    r jsonb;
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_user_admin(auth.uid()) THEN
        RAISE EXCEPTION 'Permission denied' USING ERRCODE = '42501';
    END IF;
    SELECT * INTO a FROM public.agents WHERE id = p_agent_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'agent_not_found' USING ERRCODE = 'P0001';
    END IF;
    SELECT * INTO l FROM public.marketplace_listings WHERE slug = p_slug FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'listing_not_found' USING ERRCODE = 'P0001';
    END IF;
    IF l.kind <> 'skill' THEN
        RAISE EXCEPTION 'not_a_skill' USING ERRCODE = 'P0001';
    END IF;
    -- Auto-installs are keyed by plugin id; an agent without one never received defaults.
    IF a.plugin_id IS NULL OR a.plugin_id !~ '^[a-z0-9][a-z0-9._-]{1,127}$' THEN
        RAISE EXCEPTION 'agent_has_no_plugin' USING ERRCODE = 'P0001';
    END IF;
    had := EXISTS (SELECT 1 FROM public.marketplace_auto_installs WHERE listing_id = l.id AND plugin_id = a.plugin_id);
    IF COALESCE(p_on, false) THEN
        IF l.status <> 'live' THEN
            RAISE EXCEPTION 'listing_not_live' USING ERRCODE = 'P0001';
        END IF;
        IF EXISTS (SELECT 1 FROM public.marketplace_versions v WHERE v.id = l.live_version_id
                   AND (v.bundle_path IS NOT NULL OR pg_catalog.cardinality(v.allowed_agents) > 0)) THEN
            RAISE EXCEPTION 'default_requires_legacy_skill' USING ERRCODE = 'P0001';
        END IF;
        IF NOT had AND (SELECT pg_catalog.count(*) FROM public.marketplace_auto_installs WHERE plugin_id = a.plugin_id) >= 50 THEN
            RAISE EXCEPTION 'too_many_defaults' USING ERRCODE = 'P0001';
        END IF;
    END IF;
    r := public.marketplace_auto_install_apply(l.slug, NULL, a.plugin_id, COALESCE(p_on, false));
    IF NOT (r ->> 'ok')::boolean THEN
        RAISE EXCEPTION '%', CASE r ->> 'reason' WHEN 'too_many' THEN 'too_many_defaults' WHEN 'not_live' THEN 'listing_not_live'
                                 WHEN 'not_skill' THEN 'not_a_skill' WHEN 'not_found' THEN 'listing_not_found'
                                 ELSE r ->> 'reason' END USING ERRCODE = 'P0001';
    END IF;
    IF had IS DISTINCT FROM COALESCE(p_on, false) THEN
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
    -- Managed agents only: other plugin ids (Optimist, Max) have no agent row for the old console to show.
    RETURN COALESCE((
        SELECT pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
                   'agent_id', a.id, 'slug', a.slug, 'name', a.name, 'org_id', a.org_id, 'added_at', ai.created_at)
               ORDER BY a.slug)
        FROM public.marketplace_listings l
        JOIN public.marketplace_auto_installs ai ON ai.listing_id = l.id
        JOIN public.agents a ON a.plugin_id = ai.plugin_id
        WHERE l.slug = p_slug), '[]'::jsonb);
END;
$$;
REVOKE ALL ON FUNCTION public.marketplace_listing_agents(text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.marketplace_listing_agents(text) TO authenticated, service_role;
