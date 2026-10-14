-- Restrict plugin_ratings to the server-side rating pipeline only.
--
-- Problem
-- -------
-- plugin_ratings stores one row per (plugin, user) rating with `user_id`
-- referencing auth.users and free-text `review` (see
-- 20260130000000_plugin_store_tables.sql). Its original RLS policies were:
--
--   SELECT USING (true)      -- "Ratings are viewable by everyone"
--   INSERT WITH CHECK (auth.uid() = user_id)
--   UPDATE USING (auth.uid() = user_id)
--   DELETE USING (auth.uid() = user_id)
--
-- None carries a TO clause, so all four apply to `anon` and `authenticated`,
-- the roles RLS actually gates (service_role bypasses RLS entirely). Two live
-- defects follow from the SELECT policy alone, reachable with nothing more
-- than the anon key the desktop client already ships:
--
--   1. Cross-user identity leak. `select * from plugin_ratings` returns
--      every row to any unauthenticated client: which auth.users UUID rated
--      which plugin, when, and their review text - across all users and all
--      plugins, including ratings on private/unlisted org plugins (the rows
--      are not filtered by plugin visibility; the policy is literally
--      `USING (true)`). This is the same defect class that
--      20260910120000_restrict_plugin_downloads_rls.sql closed for
--      plugin_downloads, which also leaked per-user rows; #487 documented it.
--      The exposure is worse here: plugin_downloads leaked user_id +
--      ip_hash, while plugin_ratings additionally exposes free-text review
--      content attributable to a user UUID.
--
--   2. Plugin existence oracle. The table is not filtered by plugin
--      visibility, so `select ... where plugin_id = <uuid>` distinguishes
--      "private/unlisted org plugin exists" from "no such plugin" to anyone
--      holding the anon key, including ratings left on plugins that were
--      later unpublished.
--
-- The three write policies look self-scoped (`auth.uid() = user_id`) but
-- only bind the row's user_id to the caller; a self-registered user can
-- still INSERT a rating for an arbitrary plugin UUID - permitted or not,
-- visible to them or not - because nothing checks the plugin's visibility.
-- The FK violation (vs. successful insert) becomes an existence oracle for
-- org-scoped plugin UUIDs, the same shape #487 called out for downloads.
--
-- Why this is safe
-- ----------------
-- The desktop client never touches this table directly; it goes through the
-- plugin-store Edge Function (supabase/functions/plugin-store), which builds
-- its client with SUPABASE_SERVICE_ROLE_KEY (index.ts) and therefore
-- bypasses RLS:
--   - reads:  routes/rating.ts -> services/ratings.ts (getUserRating,
--             getPluginRatings) - SELECT via the service-role client
--   - writes: routes/rating.ts -> upsert_plugin_rating RPC - already
--             restricted to service_role by 20260909130000, which also
--             documents that the route authenticates the caller and supplies
--             p_user_id itself
--   - aggregates (avg/count) live in the SECURITY DEFINER stats functions
--             (get_plugin_with_stats, search_plugins), which run as their
--             owner and ignore RLS
-- So dropping the permissive client policies changes nothing for the shipped
-- app and closes both defects. Direct table reads and writes by
-- anon/authenticated are denied by RLS with no permissive policy in force.
--
-- Belt and braces: drop the inherited base-table privileges too, so the gate
-- does not rest on RLS alone. A future permissive policy could not re-open
-- access without a matching GRANT being added deliberately. ALL also removes
-- TRUNCATE, which is not subject to row-level security.
REVOKE ALL ON TABLE public.plugin_ratings FROM PUBLIC, anon, authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.plugin_ratings TO service_role;

-- Remove the over-broad policies. IF EXISTS keeps this re-runnable.
DROP POLICY IF EXISTS "Ratings are viewable by everyone" ON public.plugin_ratings;
DROP POLICY IF EXISTS "Authenticated users can rate plugins" ON public.plugin_ratings;
DROP POLICY IF EXISTS "Users can update own ratings" ON public.plugin_ratings;
DROP POLICY IF EXISTS "Users can delete own ratings" ON public.plugin_ratings;

-- Re-state the intent explicitly for service_role. These are no-ops at
-- runtime (service_role bypasses RLS) but document the intended access and
-- retain it if that role's BYPASSRLS attribute is removed in a future
-- deployment.
DROP POLICY IF EXISTS "plugin_ratings service role read" ON public.plugin_ratings;
DROP POLICY IF EXISTS "plugin_ratings service role write" ON public.plugin_ratings;
DROP POLICY IF EXISTS "plugin_ratings service role update" ON public.plugin_ratings;
DROP POLICY IF EXISTS "plugin_ratings service role delete" ON public.plugin_ratings;

CREATE POLICY "plugin_ratings service role read"
    ON public.plugin_ratings FOR SELECT TO service_role
    USING (true);

CREATE POLICY "plugin_ratings service role write"
    ON public.plugin_ratings FOR INSERT TO service_role
    WITH CHECK (true);

CREATE POLICY "plugin_ratings service role update"
    ON public.plugin_ratings FOR UPDATE TO service_role
    USING (true);

CREATE POLICY "plugin_ratings service role delete"
    ON public.plugin_ratings FOR DELETE TO service_role
    USING (true);

COMMENT ON POLICY "plugin_ratings service role read" ON public.plugin_ratings IS
    'Raw rows are service-role only; clients read ratings through the plugin-store Edge Function and counts through the SECURITY DEFINER stats functions.';
COMMENT ON POLICY "plugin_ratings service role write" ON public.plugin_ratings IS
    'Ratings are written only by the plugin-store Edge Function via upsert_plugin_rating (SECURITY DEFINER, service_role).';
