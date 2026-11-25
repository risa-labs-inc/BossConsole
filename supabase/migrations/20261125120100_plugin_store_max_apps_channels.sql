-- ============================================================================
-- Plugin store: Max app kind, a review gate, release channels; marketplace theme kind
-- ============================================================================
-- Everything here defaults to today's behaviour. Every existing plugin is kind 'plugin', every existing version is
-- review_status 'approved' on channel 'stable', so every surface that resolves "latest" or lists versions answers
-- exactly what it did before. Only rows written by the new publish paths can be anything else.
--
-- 1. plugins.kind ('plugin' | 'max-app'). A Max app is a content-only jar: an HTML page and an app.json under
--    META-INF/max-app/, plus one tiny stub class. The plugin-store edge function enforces the content rules at
--    finalize and puts every Max app version through human review before it is signed or served.
--    Clients may set kind on INSERT (a client-inserted row only ever makes its versions stricter) but never change
--    it afterwards: flipping a reviewed app to 'plugin' would take its next versions out of review.
--
-- 2. plugin_versions.channel ('stable' | 'beta' | 'test'), review_status ('approved' | 'pending_review' |
--    'rejected'), reviewed_by / reviewed_at / review_note, and review_meta (what the reviewer is shown: plugin.json,
--    app.json and the entry list, recorded by finalize). No client role may write review_status, reviewed_*,
--    review_note or review_meta (the 20260927120000 technique: the table-level grant is replaced by a column-level
--    one without them), and a version a client inserts into a Max app is forced to pending_review by trigger, since
--    the column default would otherwise approve it.
--
-- 3. The "latest" gate. A version is released to ordinary callers only when it is finalized (20260923133000,
--    20260929000000) AND approved AND on the stable channel. Every surface that carried the finalization gate
--    carries the new predicate too: the plugins_with_latest_version view, search_plugins_internal,
--    get_plugin_with_stats_internal, get_plugin_versions_internal and the "Published versions are viewable"
--    policy. Bodies are restated from their latest definitions (20260925150000 for search_plugins_internal,
--    20260923133000 for the rest) with only the predicate added, and for search_plugins_internal the new 'kind'
--    key; signatures, RETURNS TABLE lists, attributes and ACLs are unchanged.
--
-- 4. Channels. Non-stable channels are visible only to the plugin's author, members of its organisation and
--    holders of plugins.channel.test (platform admins hold every permission). A channel includes the ones below it:
--    beta sees stable + beta, test sees all three. Two new service-role-only functions serve the edge function's
--    ?kind= and ?channel= filters: search_plugins_channel and get_plugin_versions_channel. Callers that pass
--    neither keep using the old functions.
--
-- 5. Permissions plugins.review (approve or reject Max app versions) and plugins.channel.test, registered like
--    plugins.create (20260731000000). plugins.review goes to the marketplace_reviewer role, which the admin role
--    already inherits (20261116190000).
--
-- 6. Marketplace: kind 'theme' (the enum value is added in 20261125120000). marketplace_check_manifest accepts a
--    theme bundle (key `theme` with only `entry`); marketplace_validate refuses kind theme on the legacy, non-bundle
--    path. Theme bundles install exactly like skill bundles: marketplace_install_check needs no change.
--
-- 7. plugin_version_review: the approve/reject decision for the edge function's review routes, audited to
--    platform_audit_log in the same statement (plugin_store.approve / plugin_store.reject).
--
-- PRE-DEPLOY: nothing to backfill; the defaults are the backfill. The function deploy must follow this migration
-- (the new edge code reads the new columns), never precede it.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. plugins.kind
-- ----------------------------------------------------------------------------
ALTER TABLE public.plugins
    ADD COLUMN kind text NOT NULL DEFAULT 'plugin' CONSTRAINT plugins_kind_check CHECK (kind IN ('plugin', 'max-app'));

COMMENT ON COLUMN public.plugins.kind IS
    'plugin (a normal plugin jar) or max-app (content-only jar, every version reviewed before it is signed or served). '
    'Clients may set it on INSERT, never change it: only the plugin-store edge function (service role) writes it after.';

-- After 20260927120000 every client grant on public.plugins is column-level, so a new column is closed to clients
-- for both verbs. Open INSERT (kind) to whoever may INSERT the ordinary columns; UPDATE (kind) stays closed.
DO $plugins_kind_grant$
DECLARE
    client_role text;
BEGIN
    FOREACH client_role IN ARRAY ARRAY['anon', 'authenticated'] LOOP
        CONTINUE WHEN pg_catalog.to_regrole(client_role) IS NULL;
        IF pg_catalog.has_column_privilege(client_role, 'public.plugins'::regclass, 'display_name', 'INSERT') THEN
            EXECUTE pg_catalog.format('GRANT INSERT (kind) ON TABLE public.plugins TO %I', client_role);
        END IF;
        IF pg_catalog.has_column_privilege(client_role, 'public.plugins'::regclass, 'kind', 'UPDATE') THEN
            RAISE EXCEPTION USING
                errcode = '42501',
                message = pg_catalog.format('client %s holds UPDATE on public.plugins.kind', client_role),
                hint = 'Revoke the privilege from PUBLIC or the inherited role.';
        END IF;
    END LOOP;
END;
$plugins_kind_grant$;

-- ----------------------------------------------------------------------------
-- 2. plugin_versions: channel and review columns
-- ----------------------------------------------------------------------------
ALTER TABLE public.plugin_versions
    ADD COLUMN channel text NOT NULL DEFAULT 'stable'
        CONSTRAINT plugin_versions_channel_check CHECK (channel IN ('stable', 'beta', 'test')),
    ADD COLUMN review_status text NOT NULL DEFAULT 'approved'
        CONSTRAINT plugin_versions_review_status_check CHECK (review_status IN ('approved', 'pending_review', 'rejected')),
    ADD COLUMN reviewed_by uuid REFERENCES auth.users (id) ON DELETE SET NULL,
    ADD COLUMN reviewed_at timestamptz,
    ADD COLUMN review_note text
        CONSTRAINT plugin_versions_review_note_length CHECK (review_note IS NULL OR pg_catalog.char_length(review_note) <= 1000),
    ADD COLUMN review_meta jsonb
        CONSTRAINT plugin_versions_review_meta_size CHECK (review_meta IS NULL OR pg_catalog.octet_length(review_meta::text) <= 65536);

COMMENT ON COLUMN public.plugin_versions.channel IS
    'Release channel. stable is what every caller gets; beta and test are served only to the plugin''s author, its '
    'organisation''s members and holders of plugins.channel.test, and only when they ask for that channel.';
COMMENT ON COLUMN public.plugin_versions.review_status IS
    'approved, pending_review or rejected. Only approved versions resolve as latest or appear in public lists. Max app '
    'versions start pending_review and are signed only when a plugins.review holder approves them. Not client-writable.';
COMMENT ON COLUMN public.plugin_versions.review_meta IS
    'What a reviewer is shown for a Max app version (plugin.json, app.json, entry names and sizes), recorded by the '
    'plugin-store finalize route from the bytes it hashed. Not client-writable.';

-- The review queue: small, and only ever read for pending rows.
CREATE INDEX IF NOT EXISTS idx_plugin_versions_pending_review
    ON public.plugin_versions (published_at)
    WHERE review_status = 'pending_review';

-- No client role may write the review columns. Same technique as 20260927120000: where a client role holds a
-- table-level INSERT or UPDATE, replace it with a column-level grant of every other current column. The column list
-- is read from the catalog. Fails closed if the privilege survives through PUBLIC or an inherited role.
DO $restrict_plugin_version_review_columns$
DECLARE
    versions_oid oid := pg_catalog.to_regclass('public.plugin_versions');
    protected name[] := ARRAY['review_status', 'reviewed_by', 'reviewed_at', 'review_note', 'review_meta']::name[];
    client_role text;
    privilege text;
    writable text;
    col name;
BEGIN
    IF versions_oid IS NULL THEN
        RAISE EXCEPTION 'public.plugin_versions is missing; this migration expects the plugin store schema';
    END IF;

    FOREACH client_role IN ARRAY ARRAY['anon', 'authenticated'] LOOP
        CONTINUE WHEN pg_catalog.to_regrole(client_role) IS NULL;

        FOREACH privilege IN ARRAY ARRAY['INSERT', 'UPDATE'] LOOP
            IF pg_catalog.has_table_privilege(client_role, versions_oid, privilege) THEN
                SELECT pg_catalog.string_agg(pg_catalog.quote_ident(a.attname), ', ' ORDER BY a.attnum)
                INTO writable
                FROM pg_catalog.pg_attribute a
                WHERE a.attrelid = versions_oid
                  AND a.attnum > 0
                  AND NOT a.attisdropped
                  AND a.attname <> ALL (protected);

                EXECUTE pg_catalog.format(
                    'REVOKE %s ON TABLE public.plugin_versions FROM %I', privilege, client_role);
                EXECUTE pg_catalog.format(
                    'GRANT %s (%s) ON TABLE public.plugin_versions TO %I', privilege, writable, client_role);
            END IF;

            FOREACH col IN ARRAY protected LOOP
                IF pg_catalog.has_column_privilege(client_role, versions_oid, col, privilege) THEN
                    RAISE EXCEPTION USING
                        errcode = '42501',
                        message = pg_catalog.format(
                            'client %s retains %s on public.plugin_versions.%s', client_role, privilege, col),
                        hint = 'Revoke the privilege from PUBLIC or the inherited role.';
                END IF;
            END LOOP;
        END LOOP;
    END LOOP;
END;
$restrict_plugin_version_review_columns$;

-- A client may still INSERT a version of its own plugin directly ("Authors can add versions"). It cannot name
-- review_status, so the row takes the column default, 'approved' - which for a Max app would skip review. This
-- trigger puts every client-inserted Max app version into the queue. The edge function (service role) sets
-- review_status itself and is not touched.
--
-- SECURITY INVOKER on purpose: inside a SECURITY DEFINER function current_user is the function's owner, so a
-- `current_user IN ('anon', 'authenticated')` test there is never true and the guard would never fire. As the
-- invoker it sees the client role. The kind lookup then runs under the client's RLS; a legitimate insert is by the
-- plugin's author, who always sees their own plugin row, and anything it cannot read is treated as a Max app
-- (fail closed: the row goes to review rather than being approved).
CREATE OR REPLACE FUNCTION public.plugin_versions_client_review_guard()
RETURNS trigger
    LANGUAGE plpgsql
    SET search_path TO ''
    AS $$
DECLARE
    v_kind text;
BEGIN
    IF current_user IN ('anon', 'authenticated') THEN
        SELECT p.kind INTO v_kind FROM public.plugins p WHERE p.id = NEW.plugin_id;
        IF v_kind IS DISTINCT FROM 'plugin' THEN
            NEW.review_status := 'pending_review';
            NEW.reviewed_by := NULL;
            NEW.reviewed_at := NULL;
            NEW.review_note := NULL;
            NEW.review_meta := NULL;
            NEW.signature := NULL;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

ALTER FUNCTION public.plugin_versions_client_review_guard() OWNER TO postgres;
REVOKE ALL ON FUNCTION public.plugin_versions_client_review_guard() FROM PUBLIC, anon, authenticated;

DROP TRIGGER IF EXISTS plugin_versions_client_review_guard ON public.plugin_versions;
CREATE TRIGGER plugin_versions_client_review_guard
    BEFORE INSERT ON public.plugin_versions
    FOR EACH ROW EXECUTE FUNCTION public.plugin_versions_client_review_guard();

-- ----------------------------------------------------------------------------
-- Helpers
-- ----------------------------------------------------------------------------

-- stable 0, beta 1, test 2; anything else (NULL included) ranks above every channel, so it is never served.
CREATE OR REPLACE FUNCTION public.plugin_channel_rank(p_channel text)
RETURNS integer
    LANGUAGE sql IMMUTABLE
    SET search_path TO ''
    AS $$
    SELECT CASE p_channel WHEN 'stable' THEN 0 WHEN 'beta' THEN 1 WHEN 'test' THEN 2 ELSE 99 END;
$$;
REVOKE ALL ON FUNCTION public.plugin_channel_rank(text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.plugin_channel_rank(text) TO service_role;

-- May this user see this plugin's beta and test channels (and its versions that are not approved yet)?
-- Its author, members of its organisation, and holders of plugins.channel.test (admins hold every permission).
-- A system organisation does not count: every user is a member of @boss (20260801070000) and plugins_default_org
-- files every plugin published without an organisation under it, so membership there would open every channel of
-- every such plugin to every signed-in user.
CREATE OR REPLACE FUNCTION public.plugin_channel_access(p_user_id uuid, p_plugin_uuid uuid)
RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO ''
    AS $$
    SELECT p_user_id IS NOT NULL AND EXISTS (
        SELECT 1 FROM public.plugins p
        WHERE p.id = p_plugin_uuid
          AND (p.author_id = p_user_id
               OR (p.org_id IS NOT NULL AND public.user_is_org_member(p_user_id, p.org_id)
                   AND NOT EXISTS (SELECT 1 FROM public.organisations o WHERE o.id = p.org_id AND o.is_system))
               OR public.user_has_permission(p_user_id, 'plugins.channel.test')));
$$;
ALTER FUNCTION public.plugin_channel_access(uuid, uuid) OWNER TO postgres;
REVOKE ALL ON FUNCTION public.plugin_channel_access(uuid, uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.plugin_channel_access(uuid, uuid) TO service_role;

-- May this user download a version of this plugin that is not approved (pending or rejected)?
-- Its author, its organisation's members (not a system organisation's: see above), and reviewers (plugins.review;
-- admins hold every permission).
CREATE OR REPLACE FUNCTION public.plugin_unreviewed_access(p_user_id uuid, p_plugin_uuid uuid)
RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path TO ''
    AS $$
    SELECT p_user_id IS NOT NULL AND EXISTS (
        SELECT 1 FROM public.plugins p
        WHERE p.id = p_plugin_uuid
          AND (p.author_id = p_user_id
               OR (p.org_id IS NOT NULL AND public.user_is_org_member(p_user_id, p.org_id)
                   AND NOT EXISTS (SELECT 1 FROM public.organisations o WHERE o.id = p.org_id AND o.is_system))
               OR public.user_has_permission(p_user_id, 'plugins.review')));
$$;
ALTER FUNCTION public.plugin_unreviewed_access(uuid, uuid) OWNER TO postgres;
REVOKE ALL ON FUNCTION public.plugin_unreviewed_access(uuid, uuid) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.plugin_unreviewed_access(uuid, uuid) TO service_role;

-- ----------------------------------------------------------------------------
-- 3a. plugins_with_latest_version (20260923133000 + the release predicate)
-- ----------------------------------------------------------------------------
CREATE OR REPLACE VIEW public.plugins_with_latest_version
WITH (security_invoker = true) AS
SELECT
    p.id,
    p.plugin_id,
    p.display_name,
    p.description,
    p.author_id,
    p.author_name,
    p.homepage_url,
    p.icon_url,
    p.type,
    p.api_version,
    p.verified,
    p.published,
    p.created_at,
    p.updated_at,
    lv.version AS latest_version,
    lv.min_boss_version AS latest_min_boss_version,
    lv.published_at AS latest_published_at
FROM public.plugins p
LEFT JOIN LATERAL (
    SELECT pv.version, pv.min_boss_version, pv.published_at
    FROM public.plugin_versions pv
    WHERE pv.plugin_id = p.id
      AND pv.sha256 <> 'pending' AND pv.jar_size > 0
      AND pv.review_status = 'approved' AND pv.channel = 'stable'
    ORDER BY pv.published_at DESC, pv.id DESC
    LIMIT 1
) lv ON true;

GRANT SELECT ON public.plugins_with_latest_version TO authenticated;
GRANT SELECT ON public.plugins_with_latest_version TO anon;

COMMENT ON VIEW public.plugins_with_latest_version IS 'Denormalized view of plugins with their latest version info. Eliminates N+1 queries in plugin store listing. The latest-version lateral is gated on finalization (sha256 <> ''pending'' AND jar_size > 0, #912) and on release (review_status = ''approved'' AND channel = ''stable''): an unfinalized, unreviewed or pre-release row must never surface as latest.';

-- ----------------------------------------------------------------------------
-- 3b. search_plugins_channel: search_plugins_internal's body (20260925150000) with a kind filter, a channel-aware
--     latest version and two additive keys ('kind'; 'versionChannel' only when a non-stable channel was asked for).
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.search_plugins_channel(
    p_viewer_id uuid,
    p_query text DEFAULT '',
    p_type text DEFAULT NULL,
    p_tags text[] DEFAULT NULL,
    p_min_rating numeric DEFAULT 0,
    p_verified_only boolean DEFAULT false,
    p_page integer DEFAULT 1,
    p_page_size integer DEFAULT 20,
    p_sort_by text DEFAULT 'downloads',
    p_kind text DEFAULT NULL,
    p_channel text DEFAULT 'stable'
) RETURNS TABLE(plugins jsonb, total_count bigint)
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO ''
    AS $$
DECLARE
    v_offset INT;
    v_plugins JSONB;
    v_total BIGINT;
    v_rank INT := public.plugin_channel_rank(COALESCE(p_channel, 'stable'));
BEGIN
    IF v_rank > 2 THEN
        RAISE EXCEPTION 'invalid_channel' USING ERRCODE = '22023';
    END IF;
    IF p_kind IS NOT NULL AND p_kind NOT IN ('plugin', 'max-app') THEN
        RAISE EXCEPTION 'invalid_kind' USING ERRCODE = '22023';
    END IF;
    -- The same clamps the public wrappers apply (20260924180000); the identity on values they already clamped.
    p_page := LEAST(GREATEST(COALESCE(p_page, 1), 1), 1000000);
    p_page_size := LEAST(GREATEST(COALESCE(p_page_size, 20), 1), 100);
    v_offset := (p_page - 1) * p_page_size;

    SELECT COUNT(*)::BIGINT INTO v_total
    FROM public.plugins p
    WHERE public.user_can_view_plugin_row(p_viewer_id, p.visibility, p.org_id, p.author_id, p.published)
    AND (p_kind IS NULL OR p.kind = p_kind)
    AND (
        p_query = ''
        OR to_tsvector('english'::regconfig, p.display_name || ' ' || COALESCE(p.description, '')) @@ plainto_tsquery('english'::regconfig, p_query)
        OR p.plugin_id ILIKE '%' || p_query || '%'
    )
    AND (p_type IS NULL OR p.type = p_type)
    AND (p_verified_only = false OR p.verified = true)
    AND (
        p_tags IS NULL
        OR EXISTS (
            SELECT 1 FROM public.plugin_tags pt
            WHERE pt.plugin_id = p.id AND pt.tag = ANY(p_tags)
        )
    )
    AND (
        p_min_rating = 0
        OR COALESCE((SELECT AVG(pr.rating) FROM public.plugin_ratings pr WHERE pr.plugin_id = p.id), 0) >= p_min_rating
    );

    WITH ranked AS (
        SELECT
            p.id,
            CASE WHEN p_sort_by = 'name' THEN lower(p.display_name) END AS name_key,
            CASE p_sort_by
                WHEN 'name' THEN 0
                WHEN 'downloads' THEN (SELECT COUNT(*) FROM public.plugin_downloads pd WHERE pd.plugin_id = p.id)
                WHEN 'rating' THEN COALESCE(
                    (SELECT AVG(pr.rating) * 100 FROM public.plugin_ratings pr WHERE pr.plugin_id = p.id)::BIGINT, 0)
                WHEN 'newest' THEN EXTRACT(EPOCH FROM p.created_at)::BIGINT
                WHEN 'updated' THEN EXTRACT(EPOCH FROM p.updated_at)::BIGINT
                ELSE (SELECT COUNT(*) FROM public.plugin_downloads pd WHERE pd.plugin_id = p.id)
            END AS sort_key,
            p.plugin_id AS tie_key
        FROM public.plugins p
        WHERE public.user_can_view_plugin_row(p_viewer_id, p.visibility, p.org_id, p.author_id, p.published)
        AND (p_kind IS NULL OR p.kind = p_kind)
        AND (
            p_query = ''
            OR to_tsvector('english'::regconfig, p.display_name || ' ' || COALESCE(p.description, '')) @@ plainto_tsquery('english'::regconfig, p_query)
            OR p.plugin_id ILIKE '%' || p_query || '%'
        )
        AND (p_type IS NULL OR p.type = p_type)
        AND (p_verified_only = false OR p.verified = true)
        AND (
            p_tags IS NULL
            OR EXISTS (
                SELECT 1 FROM public.plugin_tags pt
                WHERE pt.plugin_id = p.id AND pt.tag = ANY(p_tags)
            )
        )
        AND (
            p_min_rating = 0
            OR COALESCE((SELECT AVG(pr.rating) FROM public.plugin_ratings pr WHERE pr.plugin_id = p.id), 0) >= p_min_rating
        )
        ORDER BY name_key ASC, sort_key DESC, tie_key ASC
        LIMIT p_page_size
        OFFSET v_offset
    )
    SELECT jsonb_agg(
        jsonb_build_object(
            'id', p.id,
            'pluginId', p.plugin_id,
            'displayName', p.display_name,
            'description', p.description,
            'author', p.author_name,
            'type', p.type,
            'apiVersion', p.api_version,
            'verified', p.verified,
            'iconUrl', p.icon_url,
            'url', p.homepage_url,
            'version', lv.version,
            'rating', COALESCE(
                (SELECT AVG(pr.rating)::NUMERIC(3,2) FROM public.plugin_ratings pr WHERE pr.plugin_id = p.id), 0),
            'ratingCount', (SELECT COUNT(*)::INT FROM public.plugin_ratings pr WHERE pr.plugin_id = p.id),
            'downloadCount', (SELECT COUNT(*)::INT FROM public.plugin_downloads pd WHERE pd.plugin_id = p.id),
            'tags', COALESCE(
                (SELECT ARRAY_AGG(pt.tag) FROM public.plugin_tags pt WHERE pt.plugin_id = p.id), ARRAY[]::TEXT[]),
            'requiredPermissions', COALESCE(p.required_permissions, ARRAY[]::TEXT[]),
            'orgId', p.org_id,
            'orgSlug', (SELECT o.slug FROM public.organisations o WHERE o.id = p.org_id),
            'visibility', p.visibility,
            'updatedAt', p.updated_at,
            -- Additive; types/schemas.ts marks it optional, so an older client ignores it.
            'kind', p.kind
        ) || CASE WHEN v_rank > 0 THEN jsonb_build_object('versionChannel', lv.channel) ELSE '{}'::jsonb END
        ORDER BY r.name_key ASC, r.sort_key DESC, r.tie_key ASC
    )
    INTO v_plugins
    FROM ranked r
    JOIN public.plugins p ON p.id = r.id
    LEFT JOIN LATERAL (
        -- The channel asked for applies only where this viewer may see it; everywhere else it is stable.
        -- Approved only, whatever the channel: a pending or rejected version is never anybody's latest.
        SELECT pv.version, pv.channel
        FROM public.plugin_versions pv
        WHERE pv.plugin_id = p.id
          AND pv.sha256 <> 'pending' AND pv.jar_size > 0
          AND pv.review_status = 'approved'
          AND public.plugin_channel_rank(pv.channel) <= CASE
                WHEN v_rank = 0 THEN 0
                WHEN public.plugin_channel_access(p_viewer_id, p.id) THEN v_rank
                ELSE 0 END
        ORDER BY pv.published_at DESC
        LIMIT 1
    ) lv ON true;

    RETURN QUERY SELECT COALESCE(v_plugins, '[]'::JSONB), v_total;
END;
$$;

ALTER FUNCTION public.search_plugins_channel(uuid, text, text, text[], numeric, boolean, integer, integer, text, text, text) OWNER TO postgres;
REVOKE EXECUTE ON FUNCTION public.search_plugins_channel(uuid, text, text, text[], numeric, boolean, integer, integer, text, text, text) FROM PUBLIC, anon, authenticated;
GRANT  EXECUTE ON FUNCTION public.search_plugins_channel(uuid, text, text, text[], numeric, boolean, integer, integer, text, text, text) TO service_role;

-- search_plugins_internal now delegates: the same filters, order, paging and keys (plus 'kind'), the stable channel,
-- every kind. Signature, attributes and ACL as in 20260925150000.
CREATE OR REPLACE FUNCTION "public"."search_plugins_internal"(
    "p_viewer_id" "uuid",
    "p_query" "text" DEFAULT ''::"text",
    "p_type" "text" DEFAULT NULL::"text",
    "p_tags" "text"[] DEFAULT NULL::"text"[],
    "p_min_rating" numeric DEFAULT 0,
    "p_verified_only" boolean DEFAULT false,
    "p_page" integer DEFAULT 1,
    "p_page_size" integer DEFAULT 20,
    "p_sort_by" "text" DEFAULT 'downloads'::"text"
) RETURNS TABLE("plugins" "jsonb", "total_count" bigint)
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
BEGIN
    RETURN QUERY SELECT * FROM public.search_plugins_channel(
        p_viewer_id, p_query, p_type, p_tags, p_min_rating, p_verified_only, p_page, p_page_size, p_sort_by,
        NULL, 'stable');
END;
$$;

ALTER FUNCTION "public"."search_plugins_internal"("uuid","text","text","text"[],numeric,boolean,integer,integer,"text") OWNER TO "postgres";
REVOKE EXECUTE ON FUNCTION "public"."search_plugins_internal"("uuid","text","text","text"[],numeric,boolean,integer,integer,"text") FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."search_plugins_internal"("uuid","text","text","text"[],numeric,boolean,integer,integer,"text") TO "service_role";

-- ----------------------------------------------------------------------------
-- 3c. get_plugin_with_stats_internal (20260923133000 + the release predicate)
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION "public"."get_plugin_with_stats_internal"(
    "p_plugin_id" "text",
    "p_viewer_id" "uuid"
) RETURNS TABLE(
    "id" "uuid", "plugin_id" "text", "display_name" "text", "description" "text",
    "author_id" "uuid", "author_name" "text", "homepage_url" "text", "icon_url" "text",
    "type" "text", "api_version" "text", "verified" boolean, "published" boolean,
    "created_at" timestamp with time zone, "updated_at" timestamp with time zone,
    "latest_version" "text", "latest_version_id" "uuid",
    "avg_rating" numeric, "rating_count" bigint, "download_count" bigint,
    "tags" "text"[], "screenshots" "jsonb", "required_permissions" "text"[],
    "org_id" "uuid", "org_slug" "text", "visibility" "text"
)
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
BEGIN
    RETURN QUERY
    SELECT
        p.id, p.plugin_id, p.display_name, p.description,
        p.author_id, p.author_name, p.homepage_url, p.icon_url,
        p.type, p.api_version, p.verified, p.published,
        p.created_at, p.updated_at,
        (SELECT pv.version FROM public.plugin_versions pv
          WHERE pv.plugin_id = p.id AND pv.sha256 <> 'pending' AND pv.jar_size > 0
            AND pv.review_status = 'approved' AND pv.channel = 'stable'
          ORDER BY pv.published_at DESC LIMIT 1) AS latest_version,
        (SELECT pv.id FROM public.plugin_versions pv
          WHERE pv.plugin_id = p.id AND pv.sha256 <> 'pending' AND pv.jar_size > 0
            AND pv.review_status = 'approved' AND pv.channel = 'stable'
          ORDER BY pv.published_at DESC LIMIT 1) AS latest_version_id,
        COALESCE((SELECT AVG(pr.rating)::NUMERIC(3,2) FROM public.plugin_ratings pr
                   WHERE pr.plugin_id = p.id), 0) AS avg_rating,
        (SELECT COUNT(*)::BIGINT FROM public.plugin_ratings pr WHERE pr.plugin_id = p.id) AS rating_count,
        (SELECT COUNT(*)::BIGINT FROM public.plugin_downloads pd WHERE pd.plugin_id = p.id) AS download_count,
        COALESCE((SELECT ARRAY_AGG(pt.tag) FROM public.plugin_tags pt
                   WHERE pt.plugin_id = p.id), ARRAY[]::TEXT[]) AS tags,
        COALESCE((
            SELECT jsonb_agg(jsonb_build_object('url', ps.url, 'caption', ps.caption)
                             ORDER BY ps.sort_order)
            FROM public.plugin_screenshots ps WHERE ps.plugin_id = p.id
        ), '[]'::JSONB) AS screenshots,
        COALESCE(p.required_permissions, ARRAY[]::TEXT[]) AS required_permissions,
        p.org_id,
        (SELECT o.slug FROM public.organisations o WHERE o.id = p.org_id) AS org_slug,
        p.visibility
    FROM public.plugins p
    WHERE p.plugin_id = p_plugin_id
      AND public.user_can_view_plugin_row(p_viewer_id, p.visibility, p.org_id, p.author_id, p.published);
END;
$$;

ALTER FUNCTION "public"."get_plugin_with_stats_internal"("text","uuid") OWNER TO "postgres";
REVOKE EXECUTE ON FUNCTION "public"."get_plugin_with_stats_internal"("text","uuid") FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."get_plugin_with_stats_internal"("text","uuid") TO "service_role";

-- ----------------------------------------------------------------------------
-- 3d. get_plugin_versions_internal (20260923133000 + the release predicate)
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION "public"."get_plugin_versions_internal"(
    "p_plugin_id" "text",
    "p_viewer_id" "uuid"
) RETURNS TABLE(
    "id" "uuid", "version" "text", "changelog" "text",
    "min_boss_version" "text", "min_ipc_version" "text", "min_api_version" "text",
    "jar_path" "text", "jar_size" bigint, "sha256" "text",
    "dependencies" "jsonb", "published_at" timestamp with time zone, "download_count" bigint
)
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
BEGIN
    RETURN QUERY
    SELECT
        pv.id, pv.version, pv.changelog,
        pv.min_boss_version, pv.min_ipc_version, pv.min_api_version,
        pv.jar_path, pv.jar_size, pv.sha256,
        pv.dependencies, pv.published_at,
        (SELECT COUNT(*)::bigint FROM public.plugin_downloads pd WHERE pd.version_id = pv.id) AS download_count
    FROM public.plugin_versions pv
    JOIN public.plugins p ON p.id = pv.plugin_id
    WHERE p.plugin_id = p_plugin_id
      AND public.user_can_view_plugin_row(p_viewer_id, p.visibility, p.org_id, p.author_id, p.published)
      AND pv.sha256 <> 'pending' AND pv.jar_size > 0
      -- Released only: approved by review, on the stable channel.
      AND pv.review_status = 'approved' AND pv.channel = 'stable'
    ORDER BY pv.published_at DESC;
END;
$$;

ALTER FUNCTION "public"."get_plugin_versions_internal"("text","uuid") OWNER TO "postgres";
REVOKE EXECUTE ON FUNCTION "public"."get_plugin_versions_internal"("text","uuid") FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."get_plugin_versions_internal"("text","uuid") TO "service_role";

-- The detail page's version list for an explicit ?channel=. A viewer with channel access gets the finalized versions
-- on the channels up to p_channel, including those not approved yet (with review_status and the reviewer's note), so a
-- publisher can follow a version through review. Everyone else gets exactly get_plugin_versions_internal's rows.
CREATE OR REPLACE FUNCTION public.get_plugin_versions_channel(
    p_plugin_id text,
    p_viewer_id uuid,
    p_channel text DEFAULT 'stable'
) RETURNS TABLE(
    id uuid, version text, changelog text,
    min_boss_version text, min_ipc_version text, min_api_version text,
    jar_path text, jar_size bigint, sha256 text,
    dependencies jsonb, published_at timestamp with time zone, download_count bigint,
    channel text, review_status text, review_note text
)
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO ''
    AS $$
DECLARE
    v_rank INT := public.plugin_channel_rank(COALESCE(p_channel, 'stable'));
BEGIN
    IF v_rank > 2 THEN
        RAISE EXCEPTION 'invalid_channel' USING ERRCODE = '22023';
    END IF;
    RETURN QUERY
    SELECT
        pv.id, pv.version, pv.changelog,
        pv.min_boss_version, pv.min_ipc_version, pv.min_api_version,
        pv.jar_path, pv.jar_size, pv.sha256,
        pv.dependencies, pv.published_at,
        (SELECT COUNT(*)::bigint FROM public.plugin_downloads pd WHERE pd.version_id = pv.id) AS download_count,
        pv.channel, pv.review_status,
        CASE WHEN acc.ok THEN pv.review_note END
    FROM public.plugin_versions pv
    JOIN public.plugins p ON p.id = pv.plugin_id
    CROSS JOIN LATERAL (SELECT public.plugin_channel_access(p_viewer_id, p.id) AS ok) acc
    WHERE p.plugin_id = p_plugin_id
      AND public.user_can_view_plugin_row(p_viewer_id, p.visibility, p.org_id, p.author_id, p.published)
      AND pv.sha256 <> 'pending' AND pv.jar_size > 0
      AND (CASE WHEN acc.ok
                THEN public.plugin_channel_rank(pv.channel) <= v_rank
                ELSE pv.channel = 'stable' AND pv.review_status = 'approved' END)
    ORDER BY pv.published_at DESC;
END;
$$;

ALTER FUNCTION public.get_plugin_versions_channel(text, uuid, text) OWNER TO postgres;
REVOKE EXECUTE ON FUNCTION public.get_plugin_versions_channel(text, uuid, text) FROM PUBLIC, anon, authenticated;
GRANT  EXECUTE ON FUNCTION public.get_plugin_versions_channel(text, uuid, text) TO service_role;

-- ----------------------------------------------------------------------------
-- 3e. Direct reads and realtime (20260929000000 + the release predicate). Authors and plugins.admin.view holders
--     keep every row through their own policies.
-- ----------------------------------------------------------------------------
ALTER POLICY "Published versions are viewable" ON "public"."plugin_versions"
    USING (
        "plugin_versions"."sha256" <> 'pending'
        AND "plugin_versions"."jar_size" > 0
        AND "plugin_versions"."review_status" = 'approved'
        AND "plugin_versions"."channel" = 'stable'
        AND EXISTS (
            SELECT 1 FROM "public"."plugins" p
            WHERE p."id" = "plugin_versions"."plugin_id"
              AND "public"."can_view_plugin_row"(
                  p."visibility", p."org_id", p."author_id", p."published")
        )
    );

COMMENT ON POLICY "Published versions are viewable" ON "public"."plugin_versions" IS 'Non-owner, non-admin reads of a visible plugin''s versions, gated on finalization (#1630) and release: a pending row (sha256=''pending'' or jar_size <= 0), a version not approved by review, or one on the beta or test channel is invisible here, to direct PostgREST reads and realtime subscriptions alike. Authors keep their own rows via the author policy; admins via the admin policy.';

-- ----------------------------------------------------------------------------
-- 5. Permissions
-- ----------------------------------------------------------------------------
INSERT INTO "public"."permissions" ("name", "description", "is_system")
VALUES
    ('plugins.review', 'Approve or reject Max app versions in the plugin store review queue.', true),
    ('plugins.channel.test', 'See and install the beta and test channels of every plugin in the plugin store.', true)
ON CONFLICT ("name") DO NOTHING;

UPDATE "public"."permissions" SET "is_system" = true WHERE "name" IN ('plugins.review', 'plugins.channel.test');

-- The marketplace reviewer reviews Max apps too; admin inherits marketplace_reviewer (20261116190000).
INSERT INTO "public"."role_permissions" ("role_id", "permission_id")
SELECT r."id", p."id"
FROM "public"."roles" r
JOIN "public"."permissions" p ON p."name" = 'plugins.review'
WHERE r."name" = 'marketplace_reviewer'
ON CONFLICT ("role_id", "permission_id") DO NOTHING;

-- ----------------------------------------------------------------------------
-- 6. Marketplace theme kind. marketplace_check_manifest: body from 20261116195000 with the `theme` key and branch
--    added (and `theme` refused in skill and connector manifests). marketplace_validate: body from 20261116195000
--    with the legacy path refusing kind theme; without that the connector ELSE branch would accept {url, auth} as a
--    "theme". Attributes and ACLs are unchanged.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.marketplace_check_manifest(p jsonb)
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
    egress_hosts text[];
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
                               'tags', 'visibility', 'skill', 'connector', 'theme')) THEN
        RETURN 'invalid_manifest';
    END IF;
    IF p->'schema' IS DISTINCT FROM '1'::jsonb THEN RETURN 'invalid_manifest'; END IF;
    IF pg_catalog.jsonb_typeof(p->'kind') IS DISTINCT FROM 'string' OR (p->>'kind') NOT IN ('skill', 'connector', 'theme') THEN
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
       OR pg_catalog.char_length(nm) NOT BETWEEN 2 AND 40 OR nm ~ '[[:cntrl:]]'
       OR public.marketplace_has_invisible(nm) THEN
        RETURN 'invalid_name';
    END IF;
    IF pg_catalog.jsonb_typeof(p->'summary') IS DISTINCT FROM 'string' OR pg_catalog.btrim(p->>'summary') = ''
       OR pg_catalog.char_length(p->>'summary') > 200 OR (p->>'summary') ~ '[[:cntrl:]]'
       OR public.marketplace_has_invisible(p->>'summary') THEN
        RETURN 'invalid_summary';
    END IF;
    IF p ? 'description' AND (pg_catalog.jsonb_typeof(p->'description') IS DISTINCT FROM 'string'
                              OR pg_catalog.char_length(p->>'description') > 4000
                              OR public.marketplace_has_invisible(p->>'description')) THEN
        RETURN 'invalid_description';
    END IF;
    IF pg_catalog.jsonb_typeof(p->'publisher_name') IS DISTINCT FROM 'string' OR pg_catalog.btrim(p->>'publisher_name') = ''
       OR pg_catalog.char_length(p->>'publisher_name') > 60 OR (p->>'publisher_name') ~ '[[:cntrl:]]'
       OR public.marketplace_has_invisible(p->>'publisher_name') THEN
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
        IF EXISTS (SELECT 1 FROM pg_catalog.jsonb_array_elements_text(p->'tags') e WHERE public.marketplace_has_invisible(e)) THEN
            RETURN 'invalid_tag';
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

    -- Themes (20261125120100): only `theme: {entry: "theme.json"}`. The theme's own id must equal the slug (Max installs
    -- it as skin x-<slug> and checks the id), so the slug takes the theme id's shape. theme.json itself is validated by
    -- the marketplace-bundle edge function (a port of Max's ThemePackage) and again by Max at install.
    IF kind = 'theme' THEN
        IF p ? 'skill' OR p ? 'connector' OR pg_catalog.jsonb_typeof(p->'theme') IS DISTINCT FROM 'object'
           OR EXISTS (SELECT 1 FROM pg_catalog.jsonb_object_keys(p->'theme') x WHERE x <> 'entry')
           OR (p->'theme'->'entry') IS DISTINCT FROM '"theme.json"'::jsonb THEN
            RETURN 'invalid_manifest';
        END IF;
        IF slug !~ '^[a-z][a-z0-9-]{1,30}$' THEN RETURN 'invalid_slug'; END IF;
        RETURN NULL;
    END IF;

    IF kind = 'skill' THEN
        IF p ? 'connector' OR p ? 'theme' OR pg_catalog.jsonb_typeof(p->'skill') IS DISTINCT FROM 'object'
           OR EXISTS (SELECT 1 FROM pg_catalog.jsonb_object_keys(p->'skill') x WHERE x <> 'entry')
           OR pg_catalog.jsonb_typeof(p->'skill'->'entry') IS DISTINCT FROM 'string'
           OR pg_catalog.char_length(p->'skill'->>'entry') NOT BETWEEN 1 AND 200
           OR (p->'skill'->>'entry') ~ '(^[/~])|(\.\.)|\\|[[:cntrl:]]' THEN
            RETURN 'invalid_manifest';
        END IF;
        RETURN NULL;
    END IF;

    -- Connectors.
    IF p ? 'skill' OR p ? 'theme' OR pg_catalog.jsonb_typeof(p->'connector') IS DISTINCT FROM 'object'
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
            egress_hosts := '{}';
            FOR s IN SELECT e FROM pg_catalog.jsonb_array_elements(c->'egress') e LOOP
                IF pg_catalog.jsonb_typeof(s) <> 'string' OR (s #>> '{}') <> pg_catalog.lower(s #>> '{}')
                   OR NOT public.marketplace_public_host(s #>> '{}') OR (s #>> '{}') = ANY (egress_hosts) THEN
                    RETURN 'invalid_manifest';
                END IF;
                egress_hosts := egress_hosts || (s #>> '{}');
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
           OR pg_catalog.char_length(t->>'description') > 1000 OR public.marketplace_has_invisible(t->>'description')
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

CREATE OR REPLACE FUNCTION public.marketplace_validate(p_kind public.marketplace_kind, p_slug text, p_name text, p_summary text,
    p_description text, p_publisher_name text, p_content jsonb)
RETURNS void LANGUAGE plpgsql IMMUTABLE SET search_path = '' AS $$
BEGIN
    -- A theme is files (theme.json, art, fonts): it can only arrive as a bundle (marketplace-bundle).
    IF p_kind = 'theme' THEN
        RAISE EXCEPTION 'bundle_required' USING ERRCODE = '22023';
    END IF;
    IF p_slug IS NULL OR p_slug !~ '^[a-z0-9][a-z0-9-]{1,46}$' THEN
        RAISE EXCEPTION 'invalid_slug' USING ERRCODE = '22023';
    END IF;
    -- The console's own pages under /marketplace/.
    IF p_slug IN ('publish', 'mine') THEN
        RAISE EXCEPTION 'reserved_slug' USING ERRCODE = '22023';
    END IF;
    IF p_name IS NULL OR pg_catalog.btrim(p_name) <> p_name OR pg_catalog.char_length(p_name) NOT BETWEEN 2 AND 40 OR p_name ~ '[[:cntrl:]]'
       OR public.marketplace_has_invisible(p_name) THEN
        RAISE EXCEPTION 'invalid_name' USING ERRCODE = '22023';
    END IF;
    IF p_summary IS NULL OR pg_catalog.btrim(p_summary) = '' OR pg_catalog.char_length(p_summary) > 200 OR p_summary ~ '[[:cntrl:]]'
       OR public.marketplace_has_invisible(p_summary) THEN
        RAISE EXCEPTION 'invalid_summary' USING ERRCODE = '22023';
    END IF;
    IF pg_catalog.char_length(COALESCE(p_description, '')) > 4000 OR public.marketplace_has_invisible(p_description) THEN
        RAISE EXCEPTION 'invalid_description' USING ERRCODE = '22023';
    END IF;
    IF p_publisher_name IS NULL OR pg_catalog.btrim(p_publisher_name) = '' OR pg_catalog.char_length(p_publisher_name) > 60
       OR p_publisher_name ~ '[[:cntrl:]]' OR public.marketplace_has_invisible(p_publisher_name) THEN
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


-- ----------------------------------------------------------------------------
-- 7. The review decision, for the plugin-store edge function (service role only; the route checks plugins.review).
--    One statement so the decision and its audit row land together, and conditional on the row still being the
--    pending, finalized Max app version with the bytes the caller looked at (p_sha256): two reviewers racing, or a
--    re-finalize between the reviewer's read and the decision, decide nothing. Approve stores the signature the edge
--    function made over pluginId|version|sha256 (NULL when no signing key is configured, as finalize does for a normal
--    plugin). Reject stores the note the publisher sees. Returns {ok: true, ...} or {error: code}.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.plugin_version_review(
    p_version_id uuid,
    p_reviewer uuid,
    p_approve boolean,
    p_sha256 text,
    p_signature text DEFAULT NULL,
    p_note text DEFAULT NULL
) RETURNS jsonb
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path TO ''
    AS $$
DECLARE
    v_note text := NULLIF(pg_catalog.btrim(COALESCE(p_note, '')), '');
    v public.plugin_versions%ROWTYPE;
    pl public.plugins%ROWTYPE;
BEGIN
    IF p_version_id IS NULL OR p_reviewer IS NULL OR p_approve IS NULL OR p_sha256 IS NULL THEN
        RETURN pg_catalog.jsonb_build_object('error', 'bad_input');
    END IF;
    IF NOT p_approve AND v_note IS NULL THEN
        RETURN pg_catalog.jsonb_build_object('error', 'note_required');
    END IF;
    IF pg_catalog.char_length(COALESCE(v_note, '')) > 1000 THEN
        RETURN pg_catalog.jsonb_build_object('error', 'note_too_long');
    END IF;
    SELECT * INTO v FROM public.plugin_versions WHERE id = p_version_id FOR UPDATE;
    IF NOT FOUND THEN
        RETURN pg_catalog.jsonb_build_object('error', 'not_found');
    END IF;
    SELECT * INTO pl FROM public.plugins WHERE id = v.plugin_id;
    IF NOT FOUND OR pl.kind <> 'max-app' THEN
        RETURN pg_catalog.jsonb_build_object('error', 'not_found');
    END IF;
    IF v.review_status <> 'pending_review' OR v.sha256 = 'pending' OR COALESCE(v.jar_size, 0) <= 0
       OR v.sha256 IS DISTINCT FROM pg_catalog.lower(p_sha256) THEN
        RETURN pg_catalog.jsonb_build_object('error', 'not_pending_review');
    END IF;

    UPDATE public.plugin_versions
       SET review_status = CASE WHEN p_approve THEN 'approved' ELSE 'rejected' END,
           reviewed_by = p_reviewer,
           reviewed_at = pg_catalog.now(),
           review_note = CASE WHEN p_approve THEN NULL ELSE v_note END,
           signature = CASE WHEN p_approve THEN p_signature ELSE NULL END
     WHERE id = v.id;

    INSERT INTO public.platform_audit_log (actor, action, target_user, detail)
        VALUES (p_reviewer, CASE WHEN p_approve THEN 'plugin_store.approve' ELSE 'plugin_store.reject' END, pl.author_id,
                pg_catalog.jsonb_strip_nulls(pg_catalog.jsonb_build_object(
                    'plugin_id', pl.plugin_id, 'version', v.version, 'version_id', v.id, 'channel', v.channel,
                    'sha256', v.sha256, 'signed', CASE WHEN p_approve THEN p_signature IS NOT NULL END,
                    'note', CASE WHEN p_approve THEN NULL ELSE v_note END)));

    RETURN pg_catalog.jsonb_build_object('ok', true, 'review_status',
        CASE WHEN p_approve THEN 'approved' ELSE 'rejected' END);
END;
$$;

ALTER FUNCTION public.plugin_version_review(uuid, uuid, boolean, text, text, text) OWNER TO postgres;
REVOKE ALL ON FUNCTION public.plugin_version_review(uuid, uuid, boolean, text, text, text) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.plugin_version_review(uuid, uuid, boolean, text, text, text) TO service_role;
