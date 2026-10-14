-- pgTAP tests for plugin_ratings access (20260916120000).
-- Run with: supabase test db
--
-- plugin_ratings holds one row per (plugin, user) rating: an auth.users UUID
-- plus free-text review content. Before BossConsole#<issue> its only SELECT
-- policy was `USING (true)` with no TO clause, so any holder of the public
-- anon key could read every rating row across all users - the same
-- cross-user leak class closed for plugin_downloads by
-- 20260910120000 (issue #487) - and any authenticated user could insert a
-- rating for an arbitrary plugin UUID, using the FK violation as an
-- existence oracle for org-scoped plugins.
--
-- These assertions are about the absence of access, which is exactly the kind
-- of thing that is easy to reintroduce: a later migration adding a
-- convenience policy "so the client can read reviews directly" would restore
-- the hole without failing anything else in the suite. The policy-set
-- assertion below fails on that - including a policy created without a TO
-- clause, which applies to everyone and shows up in pg_policies as
-- roles = {public}, where a per-role check would see neither 'anon' nor
-- 'authenticated' and pass.

begin;
select plan(17);

-- 1-3: only service_role policies remain, and RLS is still on.
select is_empty(
    $$ select policyname from pg_policies
       where schemaname = 'public' and tablename = 'plugin_ratings'
         and roles <> '{service_role}'::name[] $$,
    'no policy remains for any role but service_role, unscoped ones included'
);

select isnt_empty(
    $$ select policyname from pg_policies
       where schemaname = 'public' and tablename = 'plugin_ratings'
         and 'service_role' = any(roles) $$,
    'service_role keeps its policies, so the Edge Function flow still works'
);

select ok(
    (select relrowsecurity from pg_class
      where oid = 'public.plugin_ratings'::regclass),
    'row level security is still enabled on the table'
);

-- 4-17: no table privilege either, so the gate does not rest on RLS alone.
-- has_table_privilege is checked per verb for both client roles rather than
-- via ALL, so a partial regrant of a single verb cannot pass. TRUNCATE is
-- asserted for both: it is not subject to row level security at all, so the
-- REVOKE ALL above is its only gate.
select ok(
    not has_table_privilege('anon', 'public.plugin_ratings', 'SELECT'),
    'anon cannot SELECT the ratings table'
);
select ok(
    not has_table_privilege('anon', 'public.plugin_ratings', 'INSERT'),
    'anon cannot INSERT into the ratings table'
);
select ok(
    not has_table_privilege('anon', 'public.plugin_ratings', 'UPDATE'),
    'anon cannot UPDATE the ratings table'
);
select ok(
    not has_table_privilege('anon', 'public.plugin_ratings', 'DELETE'),
    'anon cannot DELETE from the ratings table'
);
select ok(
    not has_table_privilege('anon', 'public.plugin_ratings', 'TRUNCATE'),
    'anon cannot TRUNCATE the ratings table, which no policy could stop'
);

select ok(
    not has_table_privilege('authenticated', 'public.plugin_ratings', 'SELECT'),
    'authenticated cannot SELECT the ratings table'
);
select ok(
    not has_table_privilege('authenticated', 'public.plugin_ratings', 'INSERT'),
    'authenticated cannot INSERT into the ratings table - no self-serve ratings bypassing the Edge Function'
);
select ok(
    not has_table_privilege('authenticated', 'public.plugin_ratings', 'UPDATE'),
    'authenticated cannot UPDATE the ratings table'
);
select ok(
    not has_table_privilege('authenticated', 'public.plugin_ratings', 'DELETE'),
    'authenticated cannot DELETE from the ratings table'
);
select ok(
    not has_table_privilege('authenticated', 'public.plugin_ratings', 'TRUNCATE'),
    'authenticated cannot TRUNCATE the ratings table, which no policy could stop'
);

-- The live path must keep working. If any of these fails, the migration went
-- too far and the plugin-store Edge Function can no longer serve or record
-- ratings.
select ok(
    has_table_privilege('service_role', 'public.plugin_ratings', 'SELECT'),
    'service_role can still SELECT the ratings table'
);
select ok(
    has_table_privilege('service_role', 'public.plugin_ratings', 'INSERT'),
    'service_role can still INSERT into the ratings table'
);
select ok(
    has_table_privilege('service_role', 'public.plugin_ratings', 'UPDATE'),
    'service_role can still UPDATE the ratings table'
);
select ok(
    has_table_privilege('service_role', 'public.plugin_ratings', 'DELETE'),
    'service_role can still DELETE from the ratings table'
);

select * from finish();
rollback;
