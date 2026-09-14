-- pgTAP tests for completed_authentications access (20260911000000).
-- Run with: supabase test db
--
-- completed_authentications holds live access and refresh tokens for the
-- cross-device login handoff. Before BossConsole#528 its anon policies were
-- guarded by `session_id IS NOT NULL`, which is TRUE on every real row, so any
-- holder of the public anon key could read every in-flight login's tokens,
-- delete them all, or pre-seed a victim's session with attacker-controlled ones.
--
-- These assertions are about the absence of access, which is exactly the kind of
-- thing that is easy to reintroduce: a later migration adding a convenience
-- policy "so the client can poll directly" would restore the hole without
-- failing anything else in the suite. The policy-set assertion below fails on
-- that - including a policy created without a TO clause, which applies to
-- everyone and shows up in pg_policies as roles = {public}, where a per-role
-- check would see neither 'anon' nor 'authenticated' and pass. The four
-- service_role table-privilege assertions carry over unchanged from the
-- companion submission BossConsole#530 by @Rushikeshiitb.

begin;
select plan(24);

-- 1-3: exactly one policy remains - the service_role one - and RLS is still on.
select is_empty(
    $$ select policyname from pg_policies
       where schemaname = 'public' and tablename = 'completed_authentications'
         and roles <> '{service_role}'::name[] $$,
    'no policy remains for any role but service_role, unscoped ones included'
);

-- The service-role policy is the live path and must survive. If this fails, the
-- migration went too far and cross-device login is broken.
select isnt_empty(
    $$ select policyname from pg_policies
       where schemaname = 'public' and tablename = 'completed_authentications'
         and 'service_role' = any(roles) $$,
    'service_role keeps its policy, so the Edge Function flow still works'
);

select ok(
    (select relrowsecurity from pg_class
      where oid = 'public.completed_authentications'::regclass),
    'row level security is still enabled on the table'
);

-- 4-17: no table privilege either, so the gate does not rest on RLS alone.
-- has_table_privilege is checked per verb for both client roles rather than via
-- ALL, so a partial regrant of a single verb cannot pass. TRUNCATE is asserted
-- for both: it is not subject to row level security at all, so the REVOKE ALL
-- above is its only gate.
select ok(
    not has_table_privilege('anon', 'public.completed_authentications', 'SELECT'),
    'anon cannot SELECT the token table'
);
select ok(
    not has_table_privilege('anon', 'public.completed_authentications', 'INSERT'),
    'anon cannot INSERT into the token table'
);
select ok(
    not has_table_privilege('anon', 'public.completed_authentications', 'UPDATE'),
    'anon cannot UPDATE the token table'
);
select ok(
    not has_table_privilege('anon', 'public.completed_authentications', 'DELETE'),
    'anon cannot DELETE from the token table'
);
select ok(
    not has_table_privilege('anon', 'public.completed_authentications', 'TRUNCATE'),
    'anon cannot TRUNCATE the token table, which no policy could stop'
);
select ok(
    not has_table_privilege('anon', 'public.completed_authentications', 'REFERENCES'),
    'anon has no REFERENCES privilege on the token table'
);
select ok(
    not has_table_privilege('anon', 'public.completed_authentications', 'TRIGGER'),
    'anon has no TRIGGER privilege on the token table'
);

select ok(
    not has_table_privilege('authenticated', 'public.completed_authentications', 'SELECT'),
    'authenticated cannot SELECT the token table'
);
select ok(
    not has_table_privilege('authenticated', 'public.completed_authentications', 'INSERT'),
    'authenticated cannot INSERT into the token table'
);
select ok(
    not has_table_privilege('authenticated', 'public.completed_authentications', 'UPDATE'),
    'authenticated cannot UPDATE the token table'
);
select ok(
    not has_table_privilege('authenticated', 'public.completed_authentications', 'DELETE'),
    'authenticated cannot DELETE from the token table'
);
select ok(
    not has_table_privilege('authenticated', 'public.completed_authentications', 'TRUNCATE'),
    'authenticated cannot TRUNCATE the token table, which no policy could stop'
);
select ok(
    not has_table_privilege('authenticated', 'public.completed_authentications', 'REFERENCES'),
    'authenticated has no REFERENCES privilege on the token table'
);
select ok(
    not has_table_privilege('authenticated', 'public.completed_authentications', 'TRIGGER'),
    'authenticated has no TRIGGER privilege on the token table'
);

-- 18-21: the live path must keep working. If any of these fails, the migration
-- went too far and the Edge Function can no longer store or read the handoff.
-- (Carried over from BossConsole#530, @Rushikeshiitb.)
select ok(
    has_table_privilege('service_role', 'public.completed_authentications', 'SELECT'),
    'service_role can still SELECT the token table'
);
select ok(
    has_table_privilege('service_role', 'public.completed_authentications', 'INSERT'),
    'service_role can still INSERT into the token table'
);
select ok(
    has_table_privilege('service_role', 'public.completed_authentications', 'UPDATE'),
    'service_role can still UPDATE the token table'
);
select ok(
    has_table_privilege('service_role', 'public.completed_authentications', 'DELETE'),
    'service_role can still DELETE from the token table'
);

-- 22-24: the expired-row cleanup RPC keeps no client handle either. It returns
-- void, so this is not a data leak, but after the table revoke it could do
-- nothing as a client anyway; the grants are dead weight on a token table.
select ok(
    not has_function_privilege('anon', 'public.cleanup_expired_completed_authentications()', 'EXECUTE'),
    'anon cannot execute the expired-row cleanup RPC'
);
select ok(
    not has_function_privilege('authenticated', 'public.cleanup_expired_completed_authentications()', 'EXECUTE'),
    'authenticated cannot execute the expired-row cleanup RPC'
);
select ok(
    has_function_privilege('service_role', 'public.cleanup_expired_completed_authentications()', 'EXECUTE'),
    'service_role can still execute the cleanup RPC'
);

select * from finish();
rollback;
