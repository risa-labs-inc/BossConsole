-- ============================================================================
-- Finalization gate on direct reads of plugin_versions (#1630)
-- ============================================================================
-- A plugin version row exists BEFORE its JAR does: the publish flow inserts it
-- with sha256='pending' and jar_size=0, and only the finalize route replaces
-- those once the uploaded bytes have been re-hashed and manifest-checked.
--
-- 20260923133000 gated every latest-resolution and version-list surface a
-- caller can reach through the store's own functions (the
-- plugins_with_latest_version view, search_plugins, get_plugin_with_stats,
-- get_plugin_versions). What it could not reach is the path that never asks a
-- function: a client going straight to PostgREST --
--
--     GET /rest/v1/plugin_versions?plugin_id=eq.<id>
--
-- or holding a realtime subscription on the table (plugin_versions sits in the
-- supabase_realtime publication, and the desktop client's
-- PluginStoreRealtimeService subscribes to it with the anon key). Both answer
-- through the row-level "Published versions are viewable" SELECT policy, which
-- until now tested only the parent plugin's visibility -- so an interrupted
-- publish's pending row (sentinel sha256, jar_path pointing at an artifact
-- that does not exist yet) streamed or listed to anyone who looked, even
-- though no download can be driven from it. That is #1630.
--
-- THE FIX, per surface:
--   * "Published versions are viewable" -- the anonymous / signed-in
--     non-owner read -- now applies the same fail-closed predicate every other
--     consumer surface carries:
--         sha256 <> 'pending'  AND  jar_size > 0
--     Either half alone is refused, so a half-finalized row (impossible from
--     today's single UPDATE, but must not become resolvable via a future one)
--     still fails closed, and a NULL jar_size fails closed too. Realtime needs
--     no separate change HERE: a subscriber's feed is filtered by the same
--     SELECT policies, so pending inserts stop reaching the anon socket the
--     moment this lands. It does need one on the client: the pending INSERT was
--     never visible to non-owners, so the finalize UPDATE is the only realtime
--     signal a subscriber gets that a version published -- paired with this
--     migration, PluginStoreRealtimeService.onVersionAction now answers
--     PostgresAction.Update, not just Insert.
--   * "Authors can view own plugin versions" is deliberately NOT gated: the
--     publisher-facing view keeps its pending rows, which is where an
--     interrupted publish is repaired -- and the issue leaves those views
--     free to show the row with a publishing state.
--   * "Users with plugins.admin.view can view all versions" likewise keeps
--     everything, including pending.
--   * The service role bypasses RLS entirely, so the edge function's own reads
--     (finalize's getVersionById repair path included) are untouched.
--
-- ALTER POLICY, not DROP + CREATE: the name, command and (implicit) PUBLIC
-- scope are unchanged, only the USING expression gains the predicate.
-- Reproduces the EXISTS arm verbatim from 20260803000000 -- the visibility
-- rule still answers through public.can_view_plugin_row, whose auth.uid()
-- call the SECURITY DEFINER function resolves as the row reader.
--
-- PRE-DEPLOY AUDIT (unchanged from 20260923133000, and the reason this gates
-- on the pair rather than the sentinel alone): jar_size is BIGINT DEFAULT 0
-- and nullable, so historical rows may carry a real sha with jar_size 0 or
-- NULL. Under this gate those rows disappear from public reads at once (their
-- authors and admins still see them). Count them before pushing:
--     select count(*) from public.plugin_versions
--      where sha256 = 'pending' or jar_size is null or jar_size <= 0;
-- If any count against rows whose artifact really exists is nonzero, gate
-- the deploy on a backfill or a listed set of affected plugins.
-- ============================================================================

ALTER POLICY "Published versions are viewable" ON "public"."plugin_versions"
    USING (
        -- #1630: a row is a published version only when BOTH halves of the
        -- finalization have happened -- the same predicate the functions and
        -- the view already carry.
        "plugin_versions"."sha256" <> 'pending'
        AND "plugin_versions"."jar_size" > 0
        AND EXISTS (
            SELECT 1 FROM "public"."plugins" p
            WHERE p."id" = "plugin_versions"."plugin_id"
              AND "public"."can_view_plugin_row"(
                  p."visibility", p."org_id", p."author_id", p."published")
        )
    );

COMMENT ON POLICY "Published versions are viewable" ON "public"."plugin_versions" IS 'Non-owner, non-admin reads of a visible plugin''s versions, gated on finalization (#1630): a pending row (sha256=''pending'' or jar_size <= 0) is invisible here -- direct PostgREST reads and realtime subscriptions can no longer surface an interrupted publish. Authors keep their own pending rows via the author policy; admins via the admin policy.';
