-- Fix RLS bypass: add security_invoker to both views so RLS policies
-- on underlying tables are enforced for the calling role, not the view owner.
-- Also use explicit columns in plugins_with_latest_version and add
-- deterministic tiebreaker (pv.id DESC) to the version ordering.

-- Fix users_with_roles: add security_invoker, drop unnecessary anon grant
CREATE OR REPLACE VIEW public.users_with_roles
WITH (security_invoker = true) AS
SELECT
    u.id,
    u.email,
    u.created_at,
    COALESCE(
        array_agg(r.name ORDER BY r.name) FILTER (WHERE r.name IS NOT NULL),
        ARRAY[]::text[]
    ) AS roles
FROM public.users u
LEFT JOIN public.user_roles ur ON ur.user_id = u.id
LEFT JOIN public.roles r ON r.id = ur.role_id
GROUP BY u.id, u.email, u.created_at;

REVOKE SELECT ON public.users_with_roles FROM anon;
GRANT SELECT ON public.users_with_roles TO authenticated;

-- Fix plugins_with_latest_version: add security_invoker, explicit columns, deterministic ordering
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
    ORDER BY pv.published_at DESC, pv.id DESC
    LIMIT 1
) lv ON true;

GRANT SELECT ON public.plugins_with_latest_version TO authenticated;
GRANT SELECT ON public.plugins_with_latest_version TO anon;

-- Covering index for the deterministic tiebreaker ordering
CREATE INDEX IF NOT EXISTS idx_plugin_versions_latest
    ON public.plugin_versions(plugin_id, published_at DESC, id DESC);
