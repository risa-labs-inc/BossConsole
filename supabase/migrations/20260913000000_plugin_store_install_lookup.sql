-- ============================================================================
-- BOSS Database Schema: viewer-scoped plugin install metadata
-- ============================================================================
-- The plugin-store edge function uses the service-role client, so its normal
-- auth.uid()-scoped RPCs cannot identify the HTTP caller. Downloadability also
-- differs from catalogue visibility: an organisation member may install an
-- unlisted published plugin from a direct link without seeing it in listings.
--
-- Keep the lookup and the installability check in one SECURITY DEFINER function
-- so the route cannot accidentally fetch private metadata before it authorises
-- the caller, and so the two answers cannot race between separate queries.
-- ============================================================================

CREATE OR REPLACE FUNCTION "public"."get_plugin_install_info_for_viewer"(
    "p_plugin_id" "text",
    "p_viewer_id" "uuid"
) RETURNS TABLE("id" "uuid", "required_permissions" "text"[])
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    SELECT p.id, COALESCE(p.required_permissions, ARRAY[]::TEXT[])
    FROM public.plugins p
    WHERE p.plugin_id = p_plugin_id
      AND public.user_can_install_plugin(p_viewer_id, p.id);
$$;

ALTER FUNCTION "public"."get_plugin_install_info_for_viewer"("text", "uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."get_plugin_install_info_for_viewer"("text", "uuid") IS
'Returns only the internal id and install permissions when the viewer may download a plugin. Uses user_can_install_plugin rather than user_can_view_plugin so an ordinary organisation member may install an unlisted published plugin from a direct link. service_role only: the plugin-store edge function supplies the verified viewer id.';

REVOKE EXECUTE ON FUNCTION "public"."get_plugin_install_info_for_viewer"("text", "uuid")
    FROM PUBLIC, "anon", "authenticated";
GRANT EXECUTE ON FUNCTION "public"."get_plugin_install_info_for_viewer"("text", "uuid") TO "service_role";
