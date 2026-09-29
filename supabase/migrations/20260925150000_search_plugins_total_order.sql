-- search_plugins_internal: sort by name actually sorts by name, pages are stable, and a row
-- skipped by the offset no longer builds its JSON.
--
-- Three defects in the one ORDER BY, all visible to a store client:
--
-- 1. p_sort_by = 'name' sorted nothing. Its sort_key was the constant 0, so every row tied and the
--    order was the planner's. The host sends 'name' for PluginSortOrder.NAME
--    (PluginStoreClient.toApiString), and the local repository does sort by displayName, so the
--    same choice meant two different things depending on where the plugin came from.
-- 2. Ties were unordered for every sort. Plugins with equal downloads, ratings or timestamps came
--    back in whatever order the planner produced, and LIMIT/OFFSET over an unstable order lets two
--    pages of one listing repeat a plugin or skip one. plugin_id, which is unique, now breaks ties.
-- 3. The JSON for every row was built before the offset was applied: the page query projected
--    jsonb_build_object, with its version, rating, rating-count, download-count, tag and
--    organisation subqueries, for each row, and LIMIT/OFFSET then discarded all but one page. A deep
--    page cost as much as returning the whole catalogue. Rows are now ranked first and the JSON is
--    built only for the page (#1669 item 6, and #1671's review). A skipped row still computes its
--    sort key; bounding that too would need precomputed counts.
--
-- The filters, the JSON keys and values, total_count, the signature and the ACL are unchanged; this
-- restates 20260923133000's body with only the ordering and the order of work changed.
--
-- Do not fold the count into `ranked` as count(*) OVER (): the window counts the rows `ranked`
-- keeps, and on a page past the end it keeps none, so total_count would read 0 instead of the
-- number of matches. The count stays its own query over the same filters, which is why the filter
-- block appears twice; search_plugins_order_test.sql checks each filter against both copies.

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

    -- Page first, then build the JSON: `ranked` carries only what ordering needs, so a row skipped
    -- by the offset never runs the per-row version, rating, download, tag and organisation lookups
    -- below. It still computes its sort key, which the ordering cannot do without.
    --
    -- The order is total: name_key (only for 'name'; NULL, so equal, for every other sort), then
    -- sort_key, then plugin_id, which is unique. With sort_key alone every tie was ordered however
    -- the planner liked, so two pages of one listing could repeat a plugin or skip one - and 'name'
    -- was ALL ties, because its sort_key is the constant 0: sorting by name did not sort by name.
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
        )
        ORDER BY r.name_key ASC, r.sort_key DESC, r.tie_key ASC
    )
    INTO v_plugins
    FROM ranked r
    JOIN public.plugins p ON p.id = r.id;

    RETURN QUERY SELECT COALESCE(v_plugins, '[]'::JSONB), v_total;
END;
$$;

ALTER FUNCTION "public"."search_plugins_internal"("uuid","text","text","text"[],numeric,boolean,integer,integer,"text") OWNER TO "postgres";

REVOKE EXECUTE ON FUNCTION "public"."search_plugins_internal"("uuid","text","text","text"[],numeric,boolean,integer,integer,"text") FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."search_plugins_internal"("uuid","text","text","text"[],numeric,boolean,integer,integer,"text") TO "service_role";
