-- Bound get_popular_tags in the function, where every caller meets it.
--
-- The plugin store's /tags/popular route now caps `limit` (BossConsole#1253, #1508), but that
-- route is not the only way in. get_popular_tags is anon-callable on purpose, because the store
-- is browsable before sign-in (see 20260908030000's keep list), so anyone holding the anon key
-- the desktop client ships can call it through PostgREST directly:
--
--     POST /rest/v1/rpc/get_popular_tags   {"p_limit": null}
--
-- and the body ended in `LIMIT p_limit`. LIMIT NULL is LIMIT ALL, and any large number is
-- nearly the same, so the cap in the edge function was a cap on one door. The rows are public
-- tags only, so this is a cost bound rather than a disclosure fix.
--
-- The clamp mirrors the route: NULL takes the default of 20, anything below 1 becomes 1 (a
-- negative LIMIT is an error, not an empty result), and anything above 100 becomes 100.
-- Values inside 1..100 behave exactly as before. The signature is unchanged, so the audits
-- that name get_popular_tags(integer) still match.

CREATE OR REPLACE FUNCTION "public"."get_popular_tags"("p_limit" integer DEFAULT 20)
RETURNS TABLE("tag" "text", "count" bigint)
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
BEGIN
    RETURN QUERY
    SELECT pt.tag, COUNT(*)::BIGINT
    FROM public.plugin_tags pt
    JOIN public.plugins p ON p.id = pt.plugin_id
    WHERE p.published = true
      AND p.visibility = 'public'
    GROUP BY pt.tag
    ORDER BY COUNT(*) DESC
    LIMIT LEAST(GREATEST(COALESCE(p_limit, 20), 1), 100);
END;
$$;

-- CREATE OR REPLACE keeps the owner and the ACL, but the enforce_explicit_anon_grants event
-- trigger (20260908000000) fires on the CREATE FUNCTION tag, which a replace also produces, and
-- revokes EXECUTE from public and anon. Without this grant the store's tag cloud would stop
-- working for signed-out users. It restates 20260803000000's grant exactly.
GRANT EXECUTE ON FUNCTION "public"."get_popular_tags"(integer) TO "anon", "authenticated", "service_role";
