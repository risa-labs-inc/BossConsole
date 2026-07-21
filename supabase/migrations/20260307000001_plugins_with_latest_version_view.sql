-- Create plugins_with_latest_version view for efficient store listing
-- Replaces N+1 queries (one per plugin for latest version) with a single SELECT

CREATE OR REPLACE VIEW public.plugins_with_latest_version AS
SELECT
    p.*,
    lv.version AS latest_version,
    lv.min_boss_version AS latest_min_boss_version,
    lv.published_at AS latest_published_at
FROM public.plugins p
LEFT JOIN LATERAL (
    SELECT pv.version, pv.min_boss_version, pv.published_at
    FROM public.plugin_versions pv
    WHERE pv.plugin_id = p.id
    ORDER BY pv.published_at DESC
    LIMIT 1
) lv ON true;

GRANT SELECT ON public.plugins_with_latest_version TO authenticated;
GRANT SELECT ON public.plugins_with_latest_version TO anon;

COMMENT ON VIEW public.plugins_with_latest_version IS 'Denormalized view of plugins with their latest version info. Eliminates N+1 queries in plugin store listing.';
