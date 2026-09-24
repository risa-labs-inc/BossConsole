-- Plugin store: a serve RPC for the download path, gated by user_can_install_plugin.
--
-- Problem
-- -------
-- routes/download.ts resolves the plugin to serve with get_plugin_with_stats,
-- which scopes rows to auth.uid(). The edge function calls every RPC over the
-- SERVICE-ROLE client, so auth.uid() is NULL and that lookup resolves every row
-- as an anonymous stranger: public+published only. The route then runs
-- user_can_install_plugin (20260805000000) as a second gate -- but that gate can
-- only ever fire on rows the first lookup already returned, where it passes
-- trivially. Net effect on the artifact serve path:
--
--   - the installability rule the gate exists to enforce is unreachable: an
--     organisation member gets 404 for their own organisation's org plugin,
--     and a link-holder gets 404 for an unlisted published plugin the gate
--     explicitly exists to allow;
--   - the gate is dead weight that guards nothing if the anonymous lookup is
--     ever relaxed, because the two checks are two statements the route must
--     remember to keep in the right order.
--
-- Fix: one serve RPC whose WHERE clause IS the install predicate, so
-- publication state and organisation entitlements are checked inside the same
-- statement that hands back the row. The route cannot forget, reorder or bypass
-- them, and no row means the same indistinguishable 404 as "no such plugin".
--
-- get_plugin_with_stats_for_viewer is deliberately NOT reused: it filters by
-- user_can_view_plugin_row, which denies the one case user_can_install_plugin
-- exists to allow (an unlisted published plugin is installable by an ordinary
-- organisation member). Wiring the view form into the download route would
-- still 404 its documented users.
--
-- Deliberate exceptions inherited from the shared install predicate, pinned by
-- the tests: an author reaches their own unpublished draft (the author branch
-- of user_can_view_plugin_row), and a global admin reaches anything. Every
-- other branch requires published, so an unpublished row is refused for
-- members, link-holders and strangers alike.
--
-- Grants: service_role only, mirroring user_can_install_plugin. The caller is
-- the plugin-store edge function, which resolves the viewer (JWT user or
-- API-key owner) and passes it in; a client-reachable form would let
-- anon/authenticated probe which plugin ids exist for which viewers.

CREATE OR REPLACE FUNCTION public.get_plugin_for_download(
    p_plugin_id TEXT,
    p_viewer_id UUID DEFAULT NULL
)
RETURNS TABLE (
    id UUID,
    plugin_id TEXT,
    display_name TEXT,
    published BOOLEAN,
    required_permissions TEXT[],
    visibility TEXT,
    org_id UUID
)
LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path TO ''
AS $$
BEGIN
    RETURN QUERY
    SELECT
        p.id,
        p.plugin_id,
        p.display_name,
        p.published,
        COALESCE(p.required_permissions, ARRAY[]::TEXT[]) AS required_permissions,
        p.visibility,
        p.org_id
    FROM public.plugins p
    WHERE p.plugin_id = p_plugin_id
      AND public.user_can_install_plugin(p_viewer_id, p.id);
END;
$$;

ALTER FUNCTION public.get_plugin_for_download(TEXT, UUID) OWNER TO postgres;

-- 20251023000014_grants.sql sets ALTER DEFAULT PRIVILEGES ... GRANT ALL ON
-- FUNCTIONS TO anon, so REVOKE from PUBLIC is required despite never granting.
REVOKE EXECUTE ON FUNCTION public.get_plugin_for_download(TEXT, UUID)
    FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.get_plugin_for_download(TEXT, UUID)
    TO service_role;

COMMENT ON FUNCTION public.get_plugin_for_download(TEXT, UUID) IS
    'Serve-side plugin lookup for the download path: returns the row only when user_can_install_plugin(p_viewer_id, id) admits the caller, so publication state and organisation entitlements are enforced inside the RPC. Called by the plugin-store edge function with the resolved viewer; service_role only.';
