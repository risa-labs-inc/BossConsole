-- ============================================================================
-- Plugin install-permission gate
-- ============================================================================
-- Adds plugins.required_permissions: the effective permissions a user must hold
-- to INSTALL a plugin from the store. This mirrors the manifest
-- `requiredPermissions` the host already uses to gate a plugin's
-- visibility/use after install — reusing it as a single source of truth means a
-- user can't install a plugin they wouldn't be allowed to use.
--
-- Empty array ({}) = the `user.read` baseline every authenticated user holds, so
-- legacy plugins (and all existing rows) stay installable by everyone. A
-- non-empty list is enforced server-side by the /download endpoint (403 unless
-- the caller's JWT carries all of them, or they are an admin) and surfaced in
-- the browse/detail responses so the client can gate the Install button.
-- ============================================================================

ALTER TABLE plugins
    ADD COLUMN IF NOT EXISTS required_permissions TEXT[] NOT NULL DEFAULT '{}';

COMMENT ON COLUMN plugins.required_permissions IS
    'Permissions required to install/use this plugin (granular RBAC). Empty = open to all authenticated users (user.read baseline). Enforced at /download and used to gate the store Install button.';

-- ----------------------------------------------------------------------------
-- Recreate get_plugin_with_stats to surface required_permissions.
-- (Used by the detail endpoint and by /download for enforcement.)
--
-- This adds a column to the function's RETURNS TABLE, i.e. changes its return
-- type, which CREATE OR REPLACE is not allowed to do ("cannot change return type
-- of existing function"). So drop it first, then recreate. DROP also drops the
-- EXECUTE grants, so they are re-granted below — all within this migration's
-- single transaction, so anon/authenticated never observe a missing grant.
-- ----------------------------------------------------------------------------
DROP FUNCTION IF EXISTS get_plugin_with_stats(TEXT);

CREATE OR REPLACE FUNCTION get_plugin_with_stats(p_plugin_id TEXT)
RETURNS TABLE (
    id UUID,
    plugin_id TEXT,
    display_name TEXT,
    description TEXT,
    author_id UUID,
    author_name TEXT,
    homepage_url TEXT,
    icon_url TEXT,
    type TEXT,
    api_version TEXT,
    verified BOOLEAN,
    published BOOLEAN,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    latest_version TEXT,
    latest_version_id UUID,
    avg_rating NUMERIC,
    rating_count BIGINT,
    download_count BIGINT,
    tags TEXT[],
    screenshots JSONB,
    required_permissions TEXT[]
) AS $$
BEGIN
    RETURN QUERY
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
        (
            SELECT pv.version
            FROM plugin_versions pv
            WHERE pv.plugin_id = p.id
            ORDER BY pv.published_at DESC
            LIMIT 1
        ) AS latest_version,
        (
            SELECT pv.id
            FROM plugin_versions pv
            WHERE pv.plugin_id = p.id
            ORDER BY pv.published_at DESC
            LIMIT 1
        ) AS latest_version_id,
        COALESCE(
            (SELECT AVG(pr.rating)::NUMERIC(3,2) FROM plugin_ratings pr WHERE pr.plugin_id = p.id),
            0
        ) AS avg_rating,
        (SELECT COUNT(*)::BIGINT FROM plugin_ratings pr WHERE pr.plugin_id = p.id) AS rating_count,
        (SELECT COUNT(*)::BIGINT FROM plugin_downloads pd WHERE pd.plugin_id = p.id) AS download_count,
        COALESCE(
            (SELECT ARRAY_AGG(pt.tag) FROM plugin_tags pt WHERE pt.plugin_id = p.id),
            ARRAY[]::TEXT[]
        ) AS tags,
        COALESCE(
            (
                SELECT jsonb_agg(
                    jsonb_build_object('url', ps.url, 'caption', ps.caption)
                    ORDER BY ps.sort_order
                )
                FROM plugin_screenshots ps
                WHERE ps.plugin_id = p.id
            ),
            '[]'::JSONB
        ) AS screenshots,
        COALESCE(p.required_permissions, ARRAY[]::TEXT[]) AS required_permissions
    FROM plugins p
    WHERE p.plugin_id = p_plugin_id
    AND p.published = true;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Re-grant EXECUTE (the DROP above removed the original grants).
GRANT EXECUTE ON FUNCTION get_plugin_with_stats(TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION get_plugin_with_stats(TEXT) TO anon;

COMMENT ON FUNCTION get_plugin_with_stats IS 'Get plugin details with aggregated ratings, downloads, tags, screenshots, required permissions';

-- ----------------------------------------------------------------------------
-- Recreate search_plugins to include requiredPermissions in each list item.
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION search_plugins(
    p_query TEXT DEFAULT '',
    p_type TEXT DEFAULT NULL,
    p_tags TEXT[] DEFAULT NULL,
    p_min_rating NUMERIC DEFAULT 0,
    p_verified_only BOOLEAN DEFAULT FALSE,
    p_page INT DEFAULT 1,
    p_page_size INT DEFAULT 20,
    p_sort_by TEXT DEFAULT 'downloads'
)
RETURNS TABLE (
    plugins JSONB,
    total_count BIGINT
) AS $$
DECLARE
    v_offset INT;
    v_plugins JSONB;
    v_total BIGINT;
BEGIN
    v_offset := (p_page - 1) * p_page_size;

    -- Get total count
    SELECT COUNT(*)::BIGINT INTO v_total
    FROM plugins p
    WHERE p.published = true
    AND (
        p_query = ''
        OR to_tsvector('english', p.display_name || ' ' || COALESCE(p.description, '')) @@ plainto_tsquery('english', p_query)
        OR p.plugin_id ILIKE '%' || p_query || '%'
    )
    AND (p_type IS NULL OR p.type = p_type)
    AND (p_verified_only = false OR p.verified = true)
    AND (
        p_tags IS NULL
        OR EXISTS (
            SELECT 1 FROM plugin_tags pt
            WHERE pt.plugin_id = p.id
            AND pt.tag = ANY(p_tags)
        )
    )
    AND (
        p_min_rating = 0
        OR COALESCE(
            (SELECT AVG(pr.rating) FROM plugin_ratings pr WHERE pr.plugin_id = p.id),
            0
        ) >= p_min_rating
    );

    -- Get paginated results
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
                    SELECT pv.version
                    FROM plugin_versions pv
                    WHERE pv.plugin_id = p.id
                    ORDER BY pv.published_at DESC
                    LIMIT 1
                ),
                'rating', COALESCE(
                    (SELECT AVG(pr.rating)::NUMERIC(3,2) FROM plugin_ratings pr WHERE pr.plugin_id = p.id),
                    0
                ),
                'ratingCount', (SELECT COUNT(*)::INT FROM plugin_ratings pr WHERE pr.plugin_id = p.id),
                'downloadCount', (SELECT COUNT(*)::INT FROM plugin_downloads pd WHERE pd.plugin_id = p.id),
                'tags', COALESCE(
                    (SELECT ARRAY_AGG(pt.tag) FROM plugin_tags pt WHERE pt.plugin_id = p.id),
                    ARRAY[]::TEXT[]
                ),
                'requiredPermissions', COALESCE(p.required_permissions, ARRAY[]::TEXT[]),
                'updatedAt', p.updated_at
            ) AS plugin_data,
            CASE p_sort_by
                WHEN 'name' THEN 0
                WHEN 'downloads' THEN (SELECT COUNT(*) FROM plugin_downloads pd WHERE pd.plugin_id = p.id)
                WHEN 'rating' THEN COALESCE(
                    (SELECT AVG(pr.rating) * 100 FROM plugin_ratings pr WHERE pr.plugin_id = p.id)::BIGINT,
                    0
                )
                WHEN 'newest' THEN EXTRACT(EPOCH FROM p.created_at)::BIGINT
                WHEN 'updated' THEN EXTRACT(EPOCH FROM p.updated_at)::BIGINT
                ELSE (SELECT COUNT(*) FROM plugin_downloads pd WHERE pd.plugin_id = p.id)
            END AS sort_key
        FROM plugins p
        WHERE p.published = true
        AND (
            p_query = ''
            OR to_tsvector('english', p.display_name || ' ' || COALESCE(p.description, '')) @@ plainto_tsquery('english', p_query)
            OR p.plugin_id ILIKE '%' || p_query || '%'
        )
        AND (p_type IS NULL OR p.type = p_type)
        AND (p_verified_only = false OR p.verified = true)
        AND (
            p_tags IS NULL
            OR EXISTS (
                SELECT 1 FROM plugin_tags pt
                WHERE pt.plugin_id = p.id
                AND pt.tag = ANY(p_tags)
            )
        )
        AND (
            p_min_rating = 0
            OR COALESCE(
                (SELECT AVG(pr.rating) FROM plugin_ratings pr WHERE pr.plugin_id = p.id),
                0
            ) >= p_min_rating
        )
        ORDER BY sort_key DESC
        LIMIT p_page_size
        OFFSET v_offset
    ) AS subquery;

    RETURN QUERY SELECT COALESCE(v_plugins, '[]'::JSONB), v_total;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

COMMENT ON FUNCTION search_plugins IS 'Search plugins with filtering, sorting, and pagination';
