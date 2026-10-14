-- Bound search_plugins' paging in SQL, where every caller meets it (BossConsole#1668).
--
-- The plugin store's /list route caps pageSize (#1508), but that route is not the only way in.
-- search_plugins is anon-callable on purpose, because the store is browsable before sign-in, so
-- anyone holding the anon key the desktop client ships can call it through PostgREST directly:
--
--     POST /rest/v1/rpc/search_plugins   {"p_page_size": null}
--
-- It passes p_page and p_page_size straight to search_plugins_internal, whose body (as of
-- 20260923133000) computes `(p_page - 1) * p_page_size` and ends in `LIMIT p_page_size OFFSET
-- v_offset`. LIMIT NULL is LIMIT ALL, so that call returned every visible plugin, each with its
-- own version, rating, download and tag subqueries. The rows are what the caller may see anyway,
-- so this is a cost bound rather than a disclosure fix, the same as 20260922160000 for
-- get_popular_tags.
--
-- The clamp lives in the two wrappers rather than in search_plugins_internal. That keeps this
-- migration from restating the internal function's hundred-line body, which 20260923133000
-- changed this week, and the wrappers are the only way to reach it: search_plugins_internal is
-- service_role only. It mirrors the route and get_popular_tags:
--
-- - p_page_size: NULL takes the default of 20, below 1 becomes 1 (a negative LIMIT is an error,
--   not an empty result), above 100 becomes 100. Both routes already refuse anything outside
--   1..100 (ListPluginsQuerySchema since #1508, SearchPluginsRequestSchema before it), so no
--   request a route accepts is changed.
-- - p_page: NULL and anything below 1 become 1 (a negative OFFSET is an error too), and anything
--   above 1,000,000 becomes 1,000,000. That ceiling only exists so `(p_page - 1) * p_page_size`
--   cannot overflow INT: at 100 per page it is an offset of about 10^8, far past any real
--   catalogue, so it still returns an empty page rather than "integer out of range".
--
-- Values inside those ranges behave exactly as before. Both signatures are unchanged, so
-- PostgREST resolution and the audits naming these functions still match.
--
-- What this does not bound: it caps what one call returns, not what a caller can spend. A direct
-- PostgREST call never meets the route's rate limit, and a deep page still makes
-- search_plugins_internal build and discard every row before its offset, since the per-row
-- subqueries sit below the LIMIT. That cost is bounded by the catalogue's size rather than by the
-- caller, and bounding it needs the internal function to page by id before projecting (#1669).

CREATE OR REPLACE FUNCTION "public"."search_plugins"(
    "p_query" "text" DEFAULT ''::"text",
    "p_type" "text" DEFAULT NULL::"text",
    "p_tags" "text"[] DEFAULT NULL::"text"[],
    "p_min_rating" numeric DEFAULT 0,
    "p_verified_only" boolean DEFAULT false,
    "p_page" integer DEFAULT 1,
    "p_page_size" integer DEFAULT 20,
    "p_sort_by" "text" DEFAULT 'downloads'::"text"
) RETURNS TABLE("plugins" "jsonb", "total_count" bigint)
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    SELECT * FROM public.search_plugins_internal(
        auth.uid(), p_query, p_type, p_tags, p_min_rating, p_verified_only,
        LEAST(GREATEST(COALESCE(p_page, 1), 1), 1000000),
        LEAST(GREATEST(COALESCE(p_page_size, 20), 1), 100),
        p_sort_by);
$$;

CREATE OR REPLACE FUNCTION "public"."search_plugins_for_viewer"(
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
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    SELECT * FROM public.search_plugins_internal(
        p_viewer_id, p_query, p_type, p_tags, p_min_rating, p_verified_only,
        LEAST(GREATEST(COALESCE(p_page, 1), 1), 1000000),
        LEAST(GREATEST(COALESCE(p_page_size, 20), 1), 100),
        p_sort_by);
$$;

-- CREATE OR REPLACE keeps the owner and the ACL, but the enforce_explicit_anon_grants event
-- trigger (20260908000000) fires on the CREATE FUNCTION tag, which a replace also produces, and
-- revokes EXECUTE from public and anon. Without this grant the store's plugin list would stop
-- working for signed-out users. It restates 20260803000000's grant exactly.
GRANT EXECUTE ON FUNCTION "public"."search_plugins"("text","text","text"[],numeric,boolean,integer,integer,"text") TO "anon", "authenticated", "service_role";

-- service_role only, as before: exposing this to authenticated would let any user browse as
-- anyone. The replace keeps that ACL; it is restated so this file says what it leaves behind.
REVOKE EXECUTE ON FUNCTION "public"."search_plugins_for_viewer"("uuid","text","text","text"[],numeric,boolean,integer,integer,"text") FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."search_plugins_for_viewer"("uuid","text","text","text"[],numeric,boolean,integer,integer,"text") TO "service_role";
