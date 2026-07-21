-- Create users_with_roles view for efficient user+role listing
-- Replaces N+1 RPC calls (one per user) with a single SELECT query

CREATE OR REPLACE VIEW public.users_with_roles AS
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

GRANT SELECT ON public.users_with_roles TO authenticated;
GRANT SELECT ON public.users_with_roles TO anon;

COMMENT ON VIEW public.users_with_roles IS 'Denormalized view of users with their role names aggregated as an array. Used by admin-role-management plugin.';
