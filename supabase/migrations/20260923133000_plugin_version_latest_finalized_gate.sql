-- ============================================================================
-- Finalization gate on "latest" version resolution (#912)
-- ============================================================================
-- A plugin version row exists BEFORE its JAR does. publish.ts inserts it with
-- sha256='pending' (PENDING_SHA256 in services/versions.ts) and jar_size=0,
-- and only the finalize route replaces those, once the uploaded bytes have
-- been re-hashed server-side and manifest-checked. published_at, meanwhile, is
-- set by the column DEFAULT at insert time.
--
-- So every "latest version" resolution that orders by published_at picked the
-- unfinalized row the moment it was inserted, and a publish that died mid-way
-- (client crash, network drop, failed upload) left that state permanently:
-- every consumer of the plugin was served sha256='pending' and a jar key that
-- 404'd -- or worse, held bytes a prior failed attempt left behind, anchored
-- and signed as the NEW version. A bad actor could poison "latest" for every
-- user of a plugin simply by publishing a row and never finalizing it.
--
-- THE FIX, FAIL-CLOSED: a row is a published version only when BOTH halves of
-- the finalization have happened --
--     pv.sha256 <> 'pending'  AND  pv.jar_size > 0
-- Either half alone is refused, so a half-finalized row (a state that cannot
-- arise from today's single UPDATE but must not become resolvable by a future
-- one) still fails closed, and a NULL jar_size fails closed too. Every
-- store-side latest-resolution surface now carries this gate:
--
--   * plugins_with_latest_version     (view, granted to anon/authenticated)
--   * search_plugins_internal        (the 'version' key on browse rows)
--   * get_plugin_with_stats_internal (latest_version / latest_version_id)
--   * get_plugin_versions_internal   (the public version list: an
--                                     unfinalized row is absent from every
--                                     consumer surface, not merely unordered)
--
-- The edge function's own latest resolution (getLatestVersion / getVersion in
-- functions/plugin-store/services/versions.ts) is gated in the same commit.
--
-- WHAT THIS DOES NOT CHANGE: healthy data. A finalized row passes the gate on
-- every surface, in the same order as before; the signatures, RETURNS TABLE
-- column lists, SECURITY DEFINER / SET search_path attributes, owners and
-- grants are reproduced verbatim from 20260803000000 (and the view from
-- 20260307000002). CREATE OR REPLACE, NOT AN EDIT TO THOSE FILES: they are
-- applied on production, and editing them would drift the recorded checksum
-- while having no effect anywhere they had already run. Replacing a body
-- requires the WHOLE definition, hence the repetition.
--
-- No new index: the gate filters a handful of rows per plugin that the
-- existing idx_plugin_versions_latest (plugin_id, published_at DESC, id DESC)
-- already locates.
--
-- PRE-DEPLOY AUDIT: jar_size is BIGINT DEFAULT 0 and nullable, so historical
-- rows may carry a real sha with jar_size 0 or NULL. Under this gate those
-- rows disappear from every consumer surface at once (and the reaper in
-- functions/plugin-store/services/versions.ts now reaps their exact shape,
-- so a republish of the same version string recovers the slot). Count them
-- before pushing:
--     select count(*) from public.plugin_versions
--      where sha256 = 'pending' or jar_size is null or jar_size <= 0;
-- If any count against rows whose artifact really exists is nonzero, gate
-- the deploy on a backfill or a listed set of affected plugins.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. plugins_with_latest_version: gate the lateral latest-version subquery
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
    ORDER BY pv.published_at DESC, pv.id DESC
    LIMIT 1
) lv ON true;

GRANT SELECT ON public.plugins_with_latest_version TO authenticated;
GRANT SELECT ON public.plugins_with_latest_version TO anon;

COMMENT ON VIEW public.plugins_with_latest_version IS 'Denormalized view of plugins with their latest version info. Eliminates N+1 queries in plugin store listing. The latest-version lateral is gated on finalization (sha256 <> ''pending'' AND jar_size > 0, #912): an unfinalized row must never surface as latest.';

-- ----------------------------------------------------------------------------
-- 2. search_plugins_internal: gate the 'version' key on browse rows
-- ----------------------------------------------------------------------------
-- Signature, RETURNS TABLE, attributes, owner and grants identical to
-- 20260803000000; the only edited lines are the WHERE of the latest-version
-- subquery. The search_plugins / search_plugins_for_viewer wrappers delegate
-- here, so both are covered without touching their signatures (rule (1) in
-- 20260803000000's header).

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
DECLARE
    v_offset INT;
    v_plugins JSONB;
    v_total BIGINT;
BEGIN
    v_offset := (p_page - 1) * p_page_size;

    SELECT COUNT(*)::BIGINT INTO v_total
    FROM public.plugins p
    -- Was: p.published = true.
    WHERE public.user_can_view_plugin_row(p_viewer_id, p.visibility, p.org_id, p.author_id, p.published)
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

    SELECT jsonb_agg(plugin_data ORDER BY sort_key DESC)
    INTO v_plugins
    FROM (
        SELECT
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
                'version', (
                    SELECT pv.version FROM public.plugin_versions pv
                    WHERE pv.plugin_id = p.id AND pv.sha256 <> 'pending' AND pv.jar_size > 0
                    ORDER BY pv.published_at DESC LIMIT 1
                ),
                'rating', COALESCE(
                    (SELECT AVG(pr.rating)::NUMERIC(3,2) FROM public.plugin_ratings pr WHERE pr.plugin_id = p.id), 0),
                'ratingCount', (SELECT COUNT(*)::INT FROM public.plugin_ratings pr WHERE pr.plugin_id = p.id),
                'downloadCount', (SELECT COUNT(*)::INT FROM public.plugin_downloads pd WHERE pd.plugin_id = p.id),
                'tags', COALESCE(
                    (SELECT ARRAY_AGG(pt.tag) FROM public.plugin_tags pt WHERE pt.plugin_id = p.id), ARRAY[]::TEXT[]),
                'requiredPermissions', COALESCE(p.required_permissions, ARRAY[]::TEXT[]),
                -- New, additive keys. types/schemas.ts marks them optional so an
                -- older desktop client ignores them.
                'orgId', p.org_id,
                'orgSlug', (SELECT o.slug FROM public.organisations o WHERE o.id = p.org_id),
                'visibility', p.visibility,
                'updatedAt', p.updated_at
            ) AS plugin_data,
            CASE p_sort_by
                WHEN 'name' THEN 0
                WHEN 'downloads' THEN (SELECT COUNT(*) FROM public.plugin_downloads pd WHERE pd.plugin_id = p.id)
                WHEN 'rating' THEN COALESCE(
                    (SELECT AVG(pr.rating) * 100 FROM public.plugin_ratings pr WHERE pr.plugin_id = p.id)::BIGINT, 0)
                WHEN 'newest' THEN EXTRACT(EPOCH FROM p.created_at)::BIGINT
                WHEN 'updated' THEN EXTRACT(EPOCH FROM p.updated_at)::BIGINT
                ELSE (SELECT COUNT(*) FROM public.plugin_downloads pd WHERE pd.plugin_id = p.id)
            END AS sort_key
        FROM public.plugins p
        WHERE public.user_can_view_plugin_row(p_viewer_id, p.visibility, p.org_id, p.author_id, p.published)
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
        ORDER BY sort_key DESC
        LIMIT p_page_size
        OFFSET v_offset
    ) AS subquery;

    RETURN QUERY SELECT COALESCE(v_plugins, '[]'::JSONB), v_total;
END;
$$;

ALTER FUNCTION "public"."search_plugins_internal"("uuid","text","text","text"[],numeric,boolean,integer,integer,"text") OWNER TO "postgres";

REVOKE EXECUTE ON FUNCTION "public"."search_plugins_internal"("uuid","text","text","text"[],numeric,boolean,integer,integer,"text") FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."search_plugins_internal"("uuid","text","text","text"[],numeric,boolean,integer,integer,"text") TO "service_role";

-- ----------------------------------------------------------------------------
-- 3. get_plugin_with_stats_internal: gate latest_version / latest_version_id
-- ----------------------------------------------------------------------------
-- Signature, RETURNS TABLE, attributes, owner and grants identical to
-- 20260803000000; the only edited lines are the WHEREs of the two
-- latest-version subqueries. get_plugin_with_stats and
-- get_plugin_with_stats_for_viewer delegate here unchanged.

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
          ORDER BY pv.published_at DESC LIMIT 1) AS latest_version,
        (SELECT pv.id FROM public.plugin_versions pv
          WHERE pv.plugin_id = p.id AND pv.sha256 <> 'pending' AND pv.jar_size > 0
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
      -- Was: p.published = true.
      AND public.user_can_view_plugin_row(p_viewer_id, p.visibility, p.org_id, p.author_id, p.published);
END;
$$;

ALTER FUNCTION "public"."get_plugin_with_stats_internal"("text","uuid") OWNER TO "postgres";

REVOKE EXECUTE ON FUNCTION "public"."get_plugin_with_stats_internal"("text","uuid") FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."get_plugin_with_stats_internal"("text","uuid") TO "service_role";

-- ----------------------------------------------------------------------------
-- 4. get_plugin_versions_internal: keep unfinalized rows out of the public
--    version list. They carry a sentinel sha and a jar key with no (verified)
--    artifact, so they are not installable versions yet -- the finalize flow
--    addresses rows by id and does not read this list.
-- ----------------------------------------------------------------------------
-- Signature, RETURNS TABLE, attributes, owner and grants identical to
-- 20260803000000; the only edited line is the added finalization predicate in
-- the WHERE. get_plugin_versions and get_plugin_versions_for_viewer delegate
-- here unchanged.

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
      -- Was: p.published = true.
      AND public.user_can_view_plugin_row(p_viewer_id, p.visibility, p.org_id, p.author_id, p.published)
      -- #912: an unfinalized row is not a published version; it must be
      -- absent from every consumer surface, including this list.
      AND pv.sha256 <> 'pending' AND pv.jar_size > 0
    ORDER BY pv.published_at DESC;
END;
$$;

ALTER FUNCTION "public"."get_plugin_versions_internal"("text","uuid") OWNER TO "postgres";

REVOKE EXECUTE ON FUNCTION "public"."get_plugin_versions_internal"("text","uuid") FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."get_plugin_versions_internal"("text","uuid") TO "service_role";
