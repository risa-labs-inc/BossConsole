-- pgTAP tests for plugin rating submission integrity
-- (migration 20260920100000_plugin_rating_upsert_integrity).
-- Run with: supabase test db
--
-- Covers the one-row-per-user-per-plugin invariant under duplicate submit,
-- the self-rating refusal, FK fallout for a missing plugin, the
-- service-role-only execute surface (20260909130000), and the computed
-- aggregate property: the stored readers recompute AVG/COUNT from the row
-- set, so a retract is reflected immediately and never drifts.
-- The concurrent-submit interleavings live in
-- scripts/test/test-plugin-rating-concurrency.py (two live sessions).

BEGIN;
SELECT plan(18);

INSERT INTO auth.users(id, email) VALUES
 ('aa100000-0000-4000-8000-000000000001', 'rating-author@pgtap.test'),
 ('aa100000-0000-4000-8000-000000000002', 'rating-alice@pgtap.test'),
 ('aa100000-0000-4000-8000-000000000003', 'rating-bob@pgtap.test');

INSERT INTO public.plugins(id, plugin_id, display_name, author_id, author_name, published)
VALUES ('aa200000-0000-4000-8000-000000000001', 'com.pgtap.rating-fixture',
        'Rating Fixture', 'aa100000-0000-4000-8000-000000000001',
        'Rating Author', true);

-- Duplicate submit: a resubmit updates the same row, never doubles it.
SELECT is((SELECT created FROM public.upsert_plugin_rating(
            'aa200000-0000-4000-8000-000000000001',
            'aa100000-0000-4000-8000-000000000002', 5, 'great')),
          true,
          'the first submission reports created');
SELECT is((SELECT created FROM public.upsert_plugin_rating(
            'aa200000-0000-4000-8000-000000000001',
            'aa100000-0000-4000-8000-000000000002', 4, 'updated')),
          false,
          'a resubmit takes the update arm');
SELECT is((SELECT id FROM public.upsert_plugin_rating(
            'aa200000-0000-4000-8000-000000000001',
            'aa100000-0000-4000-8000-000000000002', 4, 'updated')),
          (SELECT id FROM public.plugin_ratings
            WHERE plugin_id = 'aa200000-0000-4000-8000-000000000001'
              AND user_id = 'aa100000-0000-4000-8000-000000000002'),
          'a resubmit returns the same row id');
SELECT is((SELECT count(*) FROM public.plugin_ratings
            WHERE plugin_id = 'aa200000-0000-4000-8000-000000000001'
              AND user_id = 'aa100000-0000-4000-8000-000000000002'),
          1::bigint,
          'duplicate submits leave exactly one row');
SELECT is((SELECT rating FROM public.plugin_ratings
            WHERE plugin_id = 'aa200000-0000-4000-8000-000000000001'
              AND user_id = 'aa100000-0000-4000-8000-000000000002'),
          4,
          'the resubmit overwrote the rating in place');

-- The bounds check is unchanged.
SELECT throws_ok($$SELECT public.upsert_plugin_rating(
            'aa200000-0000-4000-8000-000000000001',
            'aa100000-0000-4000-8000-000000000002', 0, '')$$,
          'P0001', 'Rating must be between 1 and 5',
          'rating 0 is refused');
SELECT throws_ok($$SELECT public.upsert_plugin_rating(
            'aa200000-0000-4000-8000-000000000001',
            'aa100000-0000-4000-8000-000000000002', 6, '')$$,
          'P0001', 'Rating must be between 1 and 5',
          'rating 6 is refused');

-- Self-rating is refused server-side, on the insert arm and the update arm
-- alike, because the guard runs before the upsert.
SELECT throws_ok($$SELECT public.upsert_plugin_rating(
            'aa200000-0000-4000-8000-000000000001',
            'aa100000-0000-4000-8000-000000000001', 5, 'buy my plugin')$$,
          'P0001', 'Plugin authors cannot rate their own plugin',
          'an author cannot rate their own plugin');
SELECT is((SELECT count(*) FROM public.plugin_ratings
            WHERE user_id = 'aa100000-0000-4000-8000-000000000001'),
          0::bigint,
          'the refused self-rating leaves no row');

-- A rating for a plugin that does not exist dies on the FK.
SELECT throws_ok($$SELECT public.upsert_plugin_rating(
            '00000000-0000-4000-8000-000000000009',
            'aa100000-0000-4000-8000-000000000003', 3, '')$$,
          '23503', NULL,
          'a rating for a missing plugin is refused');

-- The execute surface: 20260909130000 keeps the mutator service-role only.
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'aa100000-0000-4000-8000-000000000002', true);
SELECT throws_ok($$SELECT public.upsert_plugin_rating(
            'aa200000-0000-4000-8000-000000000001',
            'aa100000-0000-4000-8000-000000000002', 5, '')$$,
          '42501', NULL,
          'authenticated callers cannot execute the rating RPC');
SET LOCAL ROLE anon;
SELECT throws_ok($$SELECT public.upsert_plugin_rating(
            'aa200000-0000-4000-8000-000000000001',
            'aa100000-0000-4000-8000-000000000002', 5, '')$$,
          '42501', NULL,
          'anonymous callers cannot execute the rating RPC');
SET LOCAL ROLE service_role;
SELECT lives_ok($$SELECT public.upsert_plugin_rating(
            'aa200000-0000-4000-8000-000000000001',
            'aa100000-0000-4000-8000-000000000003', 1, 'meh')$$,
          'service_role can submit a rating');
RESET ROLE;

-- Aggregate integrity: the stored readers recompute from the row set, so a
-- retract (services/ratings.ts deleteRating) is reflected immediately.
SELECT is((SELECT avg_rating FROM public.get_plugin_with_stats('com.pgtap.rating-fixture')),
          2.50::numeric,
          'the store view reports the average of both stored rows');
SELECT is((SELECT rating_count FROM public.get_plugin_with_stats('com.pgtap.rating-fixture')),
          2::bigint,
          'the store view counts both stored rows');
DELETE FROM public.plugin_ratings
 WHERE user_id = 'aa100000-0000-4000-8000-000000000002';
SELECT is((SELECT rating_count FROM public.get_plugin_with_stats('com.pgtap.rating-fixture')),
          1::bigint,
          'after a retract the count recomputes from the rows');
SELECT is((SELECT avg_rating FROM public.get_plugin_with_stats('com.pgtap.rating-fixture')),
          1.00::numeric,
          'after a retract the average recomputes from the rows');
DELETE FROM public.plugin_ratings
 WHERE user_id = 'aa100000-0000-4000-8000-000000000003';
SELECT is((SELECT rating_count FROM public.get_plugin_with_stats('com.pgtap.rating-fixture')),
          0::bigint,
          'retracting the last rating empties the count');

SELECT * FROM finish();
ROLLBACK;
