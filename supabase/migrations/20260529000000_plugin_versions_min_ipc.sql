-- Plugin store: per-version IPC contract floor.
--
-- Each plugin version may require a minimum host IPC contract version
-- (IpcVersion.CURRENT) to load. The client filters store results against its
-- own IpcVersion.CURRENT so it never offers / installs a version the host
-- can't speak. The server only reports the value; it does not gate.
--
-- See boss-ipc/src/main/kotlin/ai/rever/boss/ipc/IpcVersion.kt and issue #740.

ALTER TABLE plugin_versions
    ADD COLUMN IF NOT EXISTS min_ipc_version TEXT NOT NULL DEFAULT '1.0.0';

COMMENT ON COLUMN plugin_versions.min_ipc_version IS
    'Minimum host IpcVersion.CURRENT required to load this plugin version. '
    'Existing rows backfill to 1.0.0; publishers set it from the JAR manifest.';

-- Recreate get_plugin_versions to surface the new column. The RETURNS TABLE
-- signature changes, so the function must be dropped first.
DROP FUNCTION IF EXISTS get_plugin_versions(TEXT);

CREATE OR REPLACE FUNCTION get_plugin_versions(p_plugin_id TEXT)
RETURNS TABLE (
    id UUID,
    version TEXT,
    changelog TEXT,
    min_boss_version TEXT,
    min_ipc_version TEXT,
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
        pv.min_ipc_version,
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

COMMENT ON FUNCTION get_plugin_versions IS
    'Get all versions of a plugin with download counts and IPC compatibility floor.';

-- DROP FUNCTION discarded the original EXECUTE grants; restore them.
GRANT EXECUTE ON FUNCTION get_plugin_versions TO authenticated;
GRANT EXECUTE ON FUNCTION get_plugin_versions TO anon;
