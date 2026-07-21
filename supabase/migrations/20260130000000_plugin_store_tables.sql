-- ============================================================================
-- BOSS Database Schema: Plugin Store Tables
-- ============================================================================
-- File: 20260130000000_plugin_store_tables.sql
-- Description: Plugin store tables for remote plugin distribution.
--              Enables users to browse, download, rate, and update plugins
--              from a central Supabase-backed repository.
-- Dependencies:
--   - File 1: extensions_and_types.sql (uuid-ossp, pgcrypto)
--   - File 8: rbac_tables.sql (users table)
-- Tables: 6 tables
-- ============================================================================


-- ============================================================================
-- Plugin Store Architecture Overview
-- ============================================================================
--
-- This schema implements a plugin store supporting:
--   1. Plugin catalog with metadata, versions, and dependencies
--   2. User ratings and reviews
--   3. Download tracking and statistics
--   4. Plugin categorization via tags
--   5. Screenshot galleries for plugin previews
--
-- Table Relationships:
--   plugins (1) ←→ (N) plugin_versions
--   plugins (1) ←→ (N) plugin_tags
--   plugins (1) ←→ (N) plugin_screenshots
--   plugins (1) ←→ (N) plugin_ratings
--   plugins (1) ←→ (N) plugin_downloads
--   users (1) ←→ (N) plugins (author)
--   users (1) ←→ (N) plugin_ratings
--
-- Storage:
--   Plugin JARs are stored in Supabase Storage bucket 'plugin-jars'
--   jar_path in plugin_versions references the storage path
--
-- ============================================================================


-- ============================================================================
-- SECTION 1: Core Plugin Tables
-- ============================================================================

-- Table 1.1: plugins
-- -----------------------------------------------------------------------------
-- Purpose: Store plugin metadata
-- Why Needed: Central catalog of all available plugins
--
-- Columns:
--   - id: Plugin UUID (PRIMARY KEY)
--   - plugin_id: Unique string identifier (e.g., "com.example.my-plugin")
--   - display_name: Human-readable name
--   - description: Plugin description (markdown supported)
--   - author_id: Foreign key to auth.users (who published this plugin)
--   - author_name: Cached author name for display
--   - homepage_url: Plugin homepage or documentation URL
--   - icon_url: URL to plugin icon
--   - type: Plugin type (panel, tab, hybrid)
--   - api_version: Required BOSS Plugin API version
--   - verified: Whether plugin is verified/signed
--   - published: Whether plugin is visible in store
--   - created_at: First publish timestamp
--   - updated_at: Last update timestamp
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS plugins (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plugin_id TEXT UNIQUE NOT NULL,
    display_name TEXT NOT NULL,
    description TEXT DEFAULT '',
    author_id UUID REFERENCES auth.users(id) ON DELETE SET NULL,
    author_name TEXT NOT NULL,
    homepage_url TEXT DEFAULT '',
    icon_url TEXT DEFAULT '',
    type TEXT NOT NULL DEFAULT 'panel' CHECK (type IN ('panel', 'tab', 'hybrid')),
    api_version TEXT NOT NULL DEFAULT '1.0',
    verified BOOLEAN DEFAULT FALSE,
    published BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

COMMENT ON TABLE plugins IS 'Plugin store: Core plugin metadata catalog';
COMMENT ON COLUMN plugins.plugin_id IS 'Unique string identifier (e.g., com.example.my-plugin)';
COMMENT ON COLUMN plugins.type IS 'Plugin type: panel (sidebar), tab (main area), hybrid (both)';
COMMENT ON COLUMN plugins.verified IS 'Whether plugin is verified/signed by BOSS team';
COMMENT ON COLUMN plugins.published IS 'Whether plugin is visible in the store';


-- Table 1.2: plugin_versions
-- -----------------------------------------------------------------------------
-- Purpose: Store plugin version history and download information
-- Why Needed: Support multiple versions of each plugin for updates and rollback
--
-- Columns:
--   - id: Version UUID (PRIMARY KEY)
--   - plugin_id: Foreign key to plugins table
--   - version: Semantic version string (e.g., "1.2.3")
--   - changelog: Version changelog (markdown supported)
--   - min_boss_version: Minimum BOSS version required
--   - jar_path: Path in Supabase Storage bucket
--   - jar_size: Size in bytes
--   - sha256: SHA-256 hash for verification
--   - dependencies: JSON array of dependencies [{pluginId, versionRange}]
--   - published_at: When this version was published
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS plugin_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plugin_id UUID NOT NULL REFERENCES plugins(id) ON DELETE CASCADE,
    version TEXT NOT NULL,
    changelog TEXT DEFAULT '',
    min_boss_version TEXT DEFAULT '1.0.0',
    jar_path TEXT NOT NULL,
    jar_size BIGINT DEFAULT 0,
    sha256 TEXT NOT NULL,
    dependencies JSONB DEFAULT '[]',
    published_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(plugin_id, version)
);

COMMENT ON TABLE plugin_versions IS 'Plugin store: Version history for each plugin';
COMMENT ON COLUMN plugin_versions.jar_path IS 'Path to JAR in Supabase Storage bucket plugin-jars';
COMMENT ON COLUMN plugin_versions.sha256 IS 'SHA-256 hash for download verification';
COMMENT ON COLUMN plugin_versions.dependencies IS 'JSON array: [{pluginId: string, versionRange: string}]';


-- Table 1.3: plugin_tags
-- -----------------------------------------------------------------------------
-- Purpose: Categorize plugins with tags for filtering
-- Why Needed: Enable users to find plugins by category
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS plugin_tags (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plugin_id UUID NOT NULL REFERENCES plugins(id) ON DELETE CASCADE,
    tag TEXT NOT NULL,
    UNIQUE(plugin_id, tag)
);

COMMENT ON TABLE plugin_tags IS 'Plugin store: Tags for categorization and filtering';


-- Table 1.4: plugin_screenshots
-- -----------------------------------------------------------------------------
-- Purpose: Store plugin screenshot URLs for preview gallery
-- Why Needed: Show users what the plugin looks like before installing
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS plugin_screenshots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plugin_id UUID NOT NULL REFERENCES plugins(id) ON DELETE CASCADE,
    url TEXT NOT NULL,
    caption TEXT DEFAULT '',
    sort_order INT DEFAULT 0
);

COMMENT ON TABLE plugin_screenshots IS 'Plugin store: Screenshot gallery for plugin previews';


-- ============================================================================
-- SECTION 2: User Interaction Tables
-- ============================================================================

-- Table 2.1: plugin_ratings
-- -----------------------------------------------------------------------------
-- Purpose: Store user ratings and reviews
-- Why Needed: Help users evaluate plugins based on community feedback
--
-- Constraints:
--   - rating: 1-5 stars
--   - One rating per user per plugin
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS plugin_ratings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plugin_id UUID NOT NULL REFERENCES plugins(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    rating INT NOT NULL CHECK (rating >= 1 AND rating <= 5),
    review TEXT DEFAULT '',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(plugin_id, user_id)
);

COMMENT ON TABLE plugin_ratings IS 'Plugin store: User ratings and reviews (1-5 stars)';


-- Table 2.2: plugin_downloads
-- -----------------------------------------------------------------------------
-- Purpose: Track plugin downloads for analytics
-- Why Needed: Show download counts and track popularity
--
-- Privacy:
--   - ip_hash: Hashed IP for analytics without storing raw IP
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS plugin_downloads (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plugin_id UUID NOT NULL REFERENCES plugins(id) ON DELETE CASCADE,
    version_id UUID REFERENCES plugin_versions(id) ON DELETE SET NULL,
    user_id UUID REFERENCES auth.users(id) ON DELETE SET NULL,
    downloaded_at TIMESTAMPTZ DEFAULT NOW(),
    ip_hash TEXT
);

COMMENT ON TABLE plugin_downloads IS 'Plugin store: Download tracking for analytics';
COMMENT ON COLUMN plugin_downloads.ip_hash IS 'SHA-256 hashed IP for privacy-preserving analytics';


-- ============================================================================
-- SECTION 3: Indexes
-- ============================================================================

-- Fast lookup by plugin_id string
CREATE INDEX IF NOT EXISTS idx_plugins_plugin_id ON plugins(plugin_id);

-- Version lookup by plugin
CREATE INDEX IF NOT EXISTS idx_plugin_versions_plugin_id ON plugin_versions(plugin_id);

-- Version ordering by publish date
CREATE INDEX IF NOT EXISTS idx_plugin_versions_published ON plugin_versions(plugin_id, published_at DESC);

-- Tag filtering
CREATE INDEX IF NOT EXISTS idx_plugin_tags_tag ON plugin_tags(tag);
CREATE INDEX IF NOT EXISTS idx_plugin_tags_plugin_id ON plugin_tags(plugin_id);

-- Rating aggregation
CREATE INDEX IF NOT EXISTS idx_plugin_ratings_plugin_id ON plugin_ratings(plugin_id);

-- Download statistics
CREATE INDEX IF NOT EXISTS idx_plugin_downloads_plugin_id ON plugin_downloads(plugin_id);
CREATE INDEX IF NOT EXISTS idx_plugin_downloads_version_id ON plugin_downloads(version_id);

-- Full-text search on plugin name and description
CREATE INDEX IF NOT EXISTS idx_plugins_search ON plugins USING gin(
    to_tsvector('english', display_name || ' ' || COALESCE(description, ''))
);

-- Published plugins filter (most common query)
CREATE INDEX IF NOT EXISTS idx_plugins_published ON plugins(published) WHERE published = true;


-- ============================================================================
-- SECTION 4: Row Level Security (RLS)
-- ============================================================================

-- Enable RLS on all tables
ALTER TABLE plugins ENABLE ROW LEVEL SECURITY;
ALTER TABLE plugin_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE plugin_tags ENABLE ROW LEVEL SECURITY;
ALTER TABLE plugin_screenshots ENABLE ROW LEVEL SECURITY;
ALTER TABLE plugin_ratings ENABLE ROW LEVEL SECURITY;
ALTER TABLE plugin_downloads ENABLE ROW LEVEL SECURITY;

-- Plugins: Anyone can view published plugins
CREATE POLICY "Published plugins are viewable by everyone"
    ON plugins FOR SELECT
    USING (published = true);

-- Plugins: Authors can view their own unpublished plugins
CREATE POLICY "Authors can view own plugins"
    ON plugins FOR SELECT
    USING (auth.uid() = author_id);

-- Plugins: Authors can insert new plugins
CREATE POLICY "Authenticated users can create plugins"
    ON plugins FOR INSERT
    WITH CHECK (auth.uid() = author_id);

-- Plugins: Authors can update their own plugins
CREATE POLICY "Authors can update own plugins"
    ON plugins FOR UPDATE
    USING (auth.uid() = author_id);

-- Plugins: Authors can delete their own plugins
CREATE POLICY "Authors can delete own plugins"
    ON plugins FOR DELETE
    USING (auth.uid() = author_id);

-- Versions: Anyone can view versions of published plugins
CREATE POLICY "Published versions are viewable"
    ON plugin_versions FOR SELECT
    USING (
        EXISTS (
            SELECT 1 FROM plugins 
            WHERE plugins.id = plugin_versions.plugin_id 
            AND plugins.published = true
        )
    );

-- Versions: Authors can view all versions of their plugins
CREATE POLICY "Authors can view own plugin versions"
    ON plugin_versions FOR SELECT
    USING (
        EXISTS (
            SELECT 1 FROM plugins 
            WHERE plugins.id = plugin_versions.plugin_id 
            AND plugins.author_id = auth.uid()
        )
    );

-- Versions: Authors can insert versions for their plugins
CREATE POLICY "Authors can add versions"
    ON plugin_versions FOR INSERT
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM plugins 
            WHERE plugins.id = plugin_versions.plugin_id 
            AND plugins.author_id = auth.uid()
        )
    );

-- Tags: Anyone can view tags of published plugins
CREATE POLICY "Published tags are viewable"
    ON plugin_tags FOR SELECT
    USING (
        EXISTS (
            SELECT 1 FROM plugins 
            WHERE plugins.id = plugin_tags.plugin_id 
            AND plugins.published = true
        )
    );

-- Tags: Authors can manage tags for their plugins
CREATE POLICY "Authors can manage own plugin tags"
    ON plugin_tags FOR ALL
    USING (
        EXISTS (
            SELECT 1 FROM plugins 
            WHERE plugins.id = plugin_tags.plugin_id 
            AND plugins.author_id = auth.uid()
        )
    );

-- Screenshots: Anyone can view screenshots of published plugins
CREATE POLICY "Published screenshots are viewable"
    ON plugin_screenshots FOR SELECT
    USING (
        EXISTS (
            SELECT 1 FROM plugins 
            WHERE plugins.id = plugin_screenshots.plugin_id 
            AND plugins.published = true
        )
    );

-- Screenshots: Authors can manage screenshots for their plugins
CREATE POLICY "Authors can manage own plugin screenshots"
    ON plugin_screenshots FOR ALL
    USING (
        EXISTS (
            SELECT 1 FROM plugins 
            WHERE plugins.id = plugin_screenshots.plugin_id 
            AND plugins.author_id = auth.uid()
        )
    );

-- Ratings: Anyone can view ratings
CREATE POLICY "Ratings are viewable by everyone"
    ON plugin_ratings FOR SELECT
    USING (true);

-- Ratings: Authenticated users can create ratings
CREATE POLICY "Authenticated users can rate plugins"
    ON plugin_ratings FOR INSERT
    WITH CHECK (auth.uid() = user_id);

-- Ratings: Users can update their own ratings
CREATE POLICY "Users can update own ratings"
    ON plugin_ratings FOR UPDATE
    USING (auth.uid() = user_id);

-- Ratings: Users can delete their own ratings
CREATE POLICY "Users can delete own ratings"
    ON plugin_ratings FOR DELETE
    USING (auth.uid() = user_id);

-- Downloads: Anyone can view download counts (aggregated only via functions)
CREATE POLICY "Downloads viewable via service role"
    ON plugin_downloads FOR SELECT
    USING (true);

-- Downloads: Service role can insert downloads (via Edge Function)
CREATE POLICY "Service role can track downloads"
    ON plugin_downloads FOR INSERT
    WITH CHECK (true);


-- ============================================================================
-- SECTION 5: Database Functions
-- ============================================================================

-- Function 5.1: Get plugin with aggregated stats
-- -----------------------------------------------------------------------------
-- Purpose: Retrieve plugin details with ratings, downloads, tags, screenshots
-- Used by: GET /plugin-store/:pluginId endpoint
-- -----------------------------------------------------------------------------
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
    screenshots JSONB
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
        ) AS screenshots
    FROM plugins p
    WHERE p.plugin_id = p_plugin_id
    AND p.published = true;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

COMMENT ON FUNCTION get_plugin_with_stats IS 'Get plugin details with aggregated ratings, downloads, tags, screenshots';


-- Function 5.2: Search plugins with pagination
-- -----------------------------------------------------------------------------
-- Purpose: Full-text search with filters, sorting, and pagination
-- Used by: GET /plugin-store/list and POST /plugin-store/search endpoints
-- -----------------------------------------------------------------------------
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


-- Function 5.3: Get plugin versions
-- -----------------------------------------------------------------------------
-- Purpose: Retrieve all versions of a plugin with download info
-- Used by: GET /plugin-store/:pluginId endpoint
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION get_plugin_versions(p_plugin_id TEXT)
RETURNS TABLE (
    id UUID,
    version TEXT,
    changelog TEXT,
    min_boss_version TEXT,
    jar_path TEXT,
    jar_size BIGINT,
    sha256 TEXT,
    dependencies JSONB,
    published_at TIMESTAMPTZ,
    download_count BIGINT
) AS $$
BEGIN
    RETURN QUERY
    SELECT
        pv.id,
        pv.version,
        pv.changelog,
        pv.min_boss_version,
        pv.jar_path,
        pv.jar_size,
        pv.sha256,
        pv.dependencies,
        pv.published_at,
        (SELECT COUNT(*)::BIGINT FROM plugin_downloads pd WHERE pd.version_id = pv.id) AS download_count
    FROM plugin_versions pv
    JOIN plugins p ON p.id = pv.plugin_id
    WHERE p.plugin_id = p_plugin_id
    AND p.published = true
    ORDER BY pv.published_at DESC;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

COMMENT ON FUNCTION get_plugin_versions IS 'Get all versions of a plugin with download counts';


-- Function 5.4: Record download
-- -----------------------------------------------------------------------------
-- Purpose: Track plugin download for statistics
-- Used by: GET /plugin-store/:pluginId/download endpoint
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION record_plugin_download(
    p_plugin_id UUID,
    p_version_id UUID,
    p_user_id UUID DEFAULT NULL,
    p_ip_hash TEXT DEFAULT NULL
)
RETURNS UUID AS $$
DECLARE
    v_download_id UUID;
BEGIN
    INSERT INTO plugin_downloads (plugin_id, version_id, user_id, ip_hash)
    VALUES (p_plugin_id, p_version_id, p_user_id, p_ip_hash)
    RETURNING id INTO v_download_id;
    
    RETURN v_download_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

COMMENT ON FUNCTION record_plugin_download IS 'Record a plugin download for analytics';


-- Function 5.5: Upsert rating
-- -----------------------------------------------------------------------------
-- Purpose: Create or update a user's rating for a plugin
-- Used by: POST /plugin-store/:pluginId/rate endpoint
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION upsert_plugin_rating(
    p_plugin_id UUID,
    p_user_id UUID,
    p_rating INT,
    p_review TEXT DEFAULT ''
)
RETURNS TABLE (
    id UUID,
    created BOOLEAN
) AS $$
DECLARE
    v_rating_id UUID;
    v_created BOOLEAN;
BEGIN
    -- Check rating bounds
    IF p_rating < 1 OR p_rating > 5 THEN
        RAISE EXCEPTION 'Rating must be between 1 and 5';
    END IF;
    
    -- Try to update existing rating
    UPDATE plugin_ratings
    SET rating = p_rating, review = p_review, updated_at = NOW()
    WHERE plugin_id = p_plugin_id AND user_id = p_user_id
    RETURNING plugin_ratings.id INTO v_rating_id;
    
    IF v_rating_id IS NOT NULL THEN
        v_created := false;
    ELSE
        -- Insert new rating
        INSERT INTO plugin_ratings (plugin_id, user_id, rating, review)
        VALUES (p_plugin_id, p_user_id, p_rating, p_review)
        RETURNING plugin_ratings.id INTO v_rating_id;
        v_created := true;
    END IF;
    
    RETURN QUERY SELECT v_rating_id, v_created;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

COMMENT ON FUNCTION upsert_plugin_rating IS 'Create or update a rating for a plugin';


-- Function 5.6: Get popular tags
-- -----------------------------------------------------------------------------
-- Purpose: Get most used tags for filtering UI
-- Used by: Filter UI in Plugin Manager
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION get_popular_tags(p_limit INT DEFAULT 20)
RETURNS TABLE (
    tag TEXT,
    count BIGINT
) AS $$
BEGIN
    RETURN QUERY
    SELECT pt.tag, COUNT(*)::BIGINT
    FROM plugin_tags pt
    JOIN plugins p ON p.id = pt.plugin_id
    WHERE p.published = true
    GROUP BY pt.tag
    ORDER BY COUNT(*) DESC
    LIMIT p_limit;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

COMMENT ON FUNCTION get_popular_tags IS 'Get most popular tags for filtering';


-- ============================================================================
-- SECTION 6: Triggers
-- ============================================================================

-- Trigger: Update plugins.updated_at when a new version is added
CREATE OR REPLACE FUNCTION update_plugin_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    UPDATE plugins SET updated_at = NOW() WHERE id = NEW.plugin_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_update_plugin_on_version
    AFTER INSERT ON plugin_versions
    FOR EACH ROW
    EXECUTE FUNCTION update_plugin_timestamp();


-- ============================================================================
-- SECTION 7: Grants
-- ============================================================================

-- Grant execute permissions on functions to authenticated users
GRANT EXECUTE ON FUNCTION get_plugin_with_stats TO authenticated;
GRANT EXECUTE ON FUNCTION search_plugins TO authenticated;
GRANT EXECUTE ON FUNCTION get_plugin_versions TO authenticated;
GRANT EXECUTE ON FUNCTION get_popular_tags TO authenticated;

-- Grant execute to anon for public browsing
GRANT EXECUTE ON FUNCTION get_plugin_with_stats TO anon;
GRANT EXECUTE ON FUNCTION search_plugins TO anon;
GRANT EXECUTE ON FUNCTION get_plugin_versions TO anon;
GRANT EXECUTE ON FUNCTION get_popular_tags TO anon;

-- Service role can record downloads and upsert ratings
GRANT EXECUTE ON FUNCTION record_plugin_download TO service_role;
GRANT EXECUTE ON FUNCTION upsert_plugin_rating TO service_role;
