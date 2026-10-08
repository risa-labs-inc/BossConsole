-- Plugin rating submission integrity: self-rating refusal and a race-free upsert.
--
-- Problem
-- -------
-- upsert_plugin_rating (20260130000000) is a two-statement "UPDATE, else
-- INSERT" pipeline, and that shape fails two ways:
--
--   1. Race on the first submission. Two sessions submitting the same
--      user's FIRST rating for the same plugin both run the UPDATE against
--      zero rows, then both INSERT. The UNIQUE(plugin_id, user_id)
--      constraint keeps the table invariant - no double-counted row - but
--      the loser surfaces it as an unhandled unique_violation (23505)
--      instead of an upsert: the plugin-store edge route maps that to a
--      500 and a valid rating is lost. Double-clicks, retry loops and
--      multi-tab clients hit exactly this window.
--
--   2. Self-rating is never refused. Neither the RPC nor the edge route
--      (functions/plugin-store/routes/rating.ts) compares the caller with
--      plugins.author_id, so an author can rate their own plugin. The
--      aggregate readers (get_plugin_with_stats*) recompute AVG/COUNT from
--      the raw rows, so an author review flows straight into the
--      displayed score with no neutral third party involved.
--
-- Fix
-- ---
-- One atomic INSERT ... ON CONFLICT (plugin_id, user_id) DO UPDATE replaces
-- the read-then-write pair: concurrent first submissions serialize on the
-- constraint and the loser takes the update arm instead of erroring. The
-- `created` flag comes from xmax = 0, which holds only on the insertion
-- arm of the statement. Self-rating is refused inside the SECURITY
-- DEFINER RPC, defense in depth behind the edge route's own check, and
-- pre-existing self-ratings are removed so the invariant starts clean.
--
-- Why this is safe
-- ----------------
-- The observable contract is unchanged: same parameters, same returned
-- columns (id, created), same bounds message, same review-overwrite
-- semantics. Aggregates are never stored, so nothing downstream caches a
-- stale score. The 20260909130000 ACL (service_role only) is re-asserted
-- because CREATE OR REPLACE would otherwise carry forward whatever grants
-- happen to exist when this migration runs. search_path is closed and all
-- table references qualified, per the convention every SECURITY DEFINER
-- function since 20260802000000 follows; this RPC predates it.

-- Restore the invariant where it is already violated: a stored self-rating
-- is exactly the row the invariant below refuses, and the computed
-- aggregates re-derive from the surviving rows immediately.
DELETE FROM public.plugin_ratings pr
    USING public.plugins p
    WHERE pr.plugin_id = p.id
      AND pr.user_id = p.author_id;

CREATE OR REPLACE FUNCTION public.upsert_plugin_rating(
    p_plugin_id UUID,
    p_user_id UUID,
    p_rating INT,
    p_review TEXT DEFAULT ''
)
RETURNS TABLE (
    id UUID,
    created BOOLEAN
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $$
DECLARE
    v_rating_id UUID;
    v_created BOOLEAN;
BEGIN
    -- Check rating bounds (unchanged)
    IF p_rating < 1 OR p_rating > 5 THEN
        RAISE EXCEPTION 'Rating must be between 1 and 5';
    END IF;

    -- An author cannot rate their own plugin. The aggregate readers compute
    -- AVG/COUNT from these rows, so an author review would inflate the
    -- displayed score with no neutral third party involved. Checked before
    -- the upsert so it guards the update arm too.
    IF EXISTS (
        SELECT 1 FROM public.plugins p
        WHERE p.id = p_plugin_id
          AND p.author_id = p_user_id
    ) THEN
        RAISE EXCEPTION 'Plugin authors cannot rate their own plugin';
    END IF;

    -- Atomic upsert. Concurrent first submissions for the same
    -- (plugin, user) serialize here: the loser wakes on the winner's
    -- speculative token and takes the update arm instead of a
    -- unique_violation. xmax = 0 is true only on the insertion arm, which
    -- is the created flag.
    INSERT INTO public.plugin_ratings (plugin_id, user_id, rating, review)
    VALUES (p_plugin_id, p_user_id, p_rating, p_review)
    ON CONFLICT (plugin_id, user_id)
    DO UPDATE SET
        rating = EXCLUDED.rating,
        review = EXCLUDED.review,
        updated_at = NOW()
    RETURNING plugin_ratings.id, (plugin_ratings.xmax = 0)
    INTO v_rating_id, v_created;

    RETURN QUERY SELECT v_rating_id, v_created;
END;
$$;

-- The 20260909130000 ACL, re-asserted so the replacement carries the same
-- execute surface it replaced: service_role only, no client role.
REVOKE ALL ON FUNCTION public.upsert_plugin_rating(UUID, UUID, INT, TEXT)
    FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.upsert_plugin_rating(UUID, UUID, INT, TEXT)
    TO service_role;

COMMENT ON FUNCTION public.upsert_plugin_rating IS
    'Create or update a rating for a plugin (atomic upsert; authors cannot rate their own plugin)';
