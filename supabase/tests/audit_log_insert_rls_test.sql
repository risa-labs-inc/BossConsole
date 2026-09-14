-- pgTAP tests for restricting the two audit-log INSERT policies (20260911010000).
-- Run with: supabase test db
--
-- Before this migration both `secret_access_log` and `plugin_api_key_logs` had an
-- INSERT policy of `WITH CHECK (true)` with no `TO` clause, so it applied to
-- PUBLIC, and both tables were writable by anon: one by explicit grant, the other
-- by the schema-wide ALTER DEFAULT PRIVILEGES in 20251023000014_grants.sql. The
-- interesting assertions are therefore about what must now be REFUSED.
--
-- WHAT THESE ASSERTIONS ACTUALLY COVER, measured by reverting each half of the
-- migration and re-running rather than by reading:
--
--   COVERED. Restoring `WITH CHECK (true)` on secret_access_log fails 3 (the
--   misattribution case, the anon case and the predicate check). Dropping the
--   REVOKE on anon fails 4 of the per-verb privilege assertions. Restoring the
--   PUBLIC policy on plugin_api_key_logs fails 2.
--
--   Privileges are asserted PER VERB rather than with a single ALL check,
--   because `has_table_privilege(..., 'ALL')` is true only when every verb is
--   held. One re-granted verb would pass an ALL-shaped assertion while leaving
--   the hole open.
--
--   NOT COVERED by the policy assertions, and worth stating so nobody assumes
--   otherwise: service_role bypasses RLS entirely, so the plugin_api_key_logs
--   policy scoped `TO service_role` is belt and braces and asserting it proves
--   nothing about the real write path. log_api_key_action() is SECURITY
--   DEFINER and would keep working with no policy at all. It is asserted below
--   only so that a future change which drops the definer attribute does not
--   silently lose the ability to write. The RPC's EXECUTE grants (PUBLIC,
--   anon, authenticated revoked; service_role kept) ARE asserted, because that
--   is what actually stops a client from calling the definer writer.

begin;
select plan(39);

-- ---------------------------------------------------------------------------
-- Row level security is still on. Everything below is meaningless without it.
-- ---------------------------------------------------------------------------

select is(
    (select relrowsecurity from pg_class where oid = 'public.secret_access_log'::regclass),
    true,
    'secret_access_log still has row level security enabled'
);

select is(
    (select relrowsecurity from pg_class where oid = 'public.plugin_api_key_logs'::regclass),
    true,
    'plugin_api_key_logs still has row level security enabled'
);

-- ---------------------------------------------------------------------------
-- secret_access_log: the unconditional INSERT policy is gone, and no
-- client-scoped replacement remains - with no INSERT privilege, no client
-- policy could ever fire, so a leftover one would be misleading dead code.
-- ---------------------------------------------------------------------------

select is_empty(
    $$ select policyname from pg_policies
       where schemaname = 'public' and tablename = 'secret_access_log'
         and policyname = 'secret_access_log_insert' $$,
    'no INSERT policy remains on secret_access_log'
);

-- The read policy is untouched. A fix that quietly removed it would hide the
-- forged rows rather than stop them being written.
select isnt_empty(
    $$ select 1 from pg_policies
        where schemaname = 'public' and tablename = 'secret_access_log'
          and policyname = 'secret_access_log_select' $$,
    'the secret_access_log SELECT policy still exists'
);

-- ---------------------------------------------------------------------------
-- secret_access_log: anon holds nothing, checked one verb at a time.
-- ---------------------------------------------------------------------------

select ok(
    not has_table_privilege('anon', 'public.secret_access_log', 'SELECT'),
    'anon cannot read the secret audit log'
);

select ok(
    not has_table_privilege('anon', 'public.secret_access_log', 'INSERT'),
    'anon cannot append to the secret audit log'
);

select ok(
    not has_table_privilege('anon', 'public.secret_access_log', 'UPDATE'),
    'anon cannot amend the secret audit log'
);

select ok(
    not has_table_privilege('anon', 'public.secret_access_log', 'DELETE'),
    'anon cannot erase from the secret audit log'
);

-- ---------------------------------------------------------------------------
-- secret_access_log: authenticated keeps exactly what the logging functions
-- need, and nothing that would let it rewrite history.
-- ---------------------------------------------------------------------------

select ok(
    has_table_privilege('authenticated', 'public.secret_access_log', 'SELECT'),
    'authenticated can still read its own audit rows'
);

select ok(
    not has_table_privilege('authenticated', 'public.secret_access_log', 'INSERT'),
    'authenticated cannot insert into the secret audit log; every writer is SECURITY DEFINER'
);

select ok(
    not has_table_privilege('authenticated', 'public.secret_access_log', 'UPDATE'),
    'authenticated cannot amend an audit row'
);

select ok(
    not has_table_privilege('authenticated', 'public.secret_access_log', 'DELETE'),
    'authenticated cannot erase an audit row'
);

-- ---------------------------------------------------------------------------
-- plugin_api_key_logs
-- ---------------------------------------------------------------------------

select is(
    (select roles::text from pg_policies
      where schemaname = 'public' and tablename = 'plugin_api_key_logs'
        and policyname = 'Service role can insert API key logs'),
    '{service_role}',
    'the API key log INSERT policy finally names the role its own name claims'
);

select ok(
    not has_table_privilege('anon', 'public.plugin_api_key_logs', 'INSERT'),
    'anon cannot forge API key usage history'
);

select ok(
    not has_table_privilege('authenticated', 'public.plugin_api_key_logs', 'INSERT'),
    'authenticated cannot forge API key usage history either'
);

-- The definer writer must not be callable by clients: EXECUTE is granted to
-- PUBLIC by default and to anon/authenticated via the schema-wide default
-- privileges, so all three revokes are asserted. Without this, the RPC forges
-- rows for any api_key_id even with the table locked.
select ok(
    not has_function_privilege('anon', 'public.log_api_key_action(uuid, text, text, text, text, boolean, text)', 'EXECUTE'),
    'anon cannot execute the API key log RPC'
);

select ok(
    not has_function_privilege('authenticated', 'public.log_api_key_action(uuid, text, text, text, text, boolean, text)', 'EXECUTE'),
    'authenticated cannot execute the API key log RPC either'
);

select ok(
    has_function_privilege('service_role', 'public.log_api_key_action(uuid, text, text, text, text, boolean, text)', 'EXECUTE'),
    'service_role can still execute the API key log RPC'
);

select ok(
    has_table_privilege('authenticated', 'public.plugin_api_key_logs', 'SELECT'),
    'authenticated keeps SELECT, so "Users can view own API key logs" still resolves'
);

select isnt_empty(
    $$ select 1 from pg_policies
        where schemaname = 'public' and tablename = 'plugin_api_key_logs'
          and policyname = 'Users can view own API key logs' $$,
    'the API key log SELECT policy still exists'
);

-- ---------------------------------------------------------------------------
-- The second definer RPC. Same shape as log_api_key_action, same door.
-- ---------------------------------------------------------------------------

select ok(
    not has_function_privilege('anon', 'public.update_api_key_last_used(uuid)', 'EXECUTE'),
    'anon cannot stamp last_used_at on an arbitrary key'
);

select ok(
    not has_function_privilege('authenticated', 'public.update_api_key_last_used(uuid)', 'EXECUTE'),
    'authenticated cannot stamp last_used_at on an arbitrary key'
);

select ok(
    has_function_privilege('service_role', 'public.update_api_key_last_used(uuid)', 'EXECUTE'),
    'the Edge Function can still stamp last_used_at'
);

-- ---------------------------------------------------------------------------
-- TRUNCATE, which no policy could ever have stopped.
-- ---------------------------------------------------------------------------

select ok(not has_table_privilege('anon', 'public.secret_access_log', 'TRUNCATE'),
          'anon cannot truncate the secret audit log');
select ok(not has_table_privilege('authenticated', 'public.secret_access_log', 'TRUNCATE'),
          'authenticated cannot truncate the secret audit log');
select ok(not has_table_privilege('anon', 'public.plugin_api_key_logs', 'TRUNCATE'),
          'anon cannot truncate the API key log');
select ok(not has_table_privilege('authenticated', 'public.plugin_api_key_logs', 'TRUNCATE'),
          'authenticated cannot truncate the API key log');

-- ---------------------------------------------------------------------------
-- plugin_api_key_logs, to the same per-verb standard as its sibling. Without
-- these, a re-grant of any verb but INSERT fails nothing here.
-- ---------------------------------------------------------------------------

select ok(not has_table_privilege('anon', 'public.plugin_api_key_logs', 'SELECT'),
          'anon cannot read API key usage history');
select ok(not has_table_privilege('anon', 'public.plugin_api_key_logs', 'UPDATE'),
          'anon cannot amend API key usage history');
select ok(not has_table_privilege('anon', 'public.plugin_api_key_logs', 'DELETE'),
          'anon cannot erase API key usage history');
select ok(not has_table_privilege('authenticated', 'public.plugin_api_key_logs', 'UPDATE'),
          'authenticated cannot amend API key usage history');
select ok(not has_table_privilege('authenticated', 'public.plugin_api_key_logs', 'DELETE'),
          'authenticated cannot erase API key usage history');

-- A policy recreated as FOR ALL under the same name and role would pass every
-- other assertion here while granting far more than an INSERT policy.
select is(
    (select cmd from pg_policies
      where schemaname = 'public' and tablename = 'plugin_api_key_logs'
        and policyname = 'Service role can insert API key logs'),
    'INSERT',
    'the API key log policy still covers INSERT alone, not ALL'
);

-- ---------------------------------------------------------------------------
-- Behaviour rather than catalogue: set the role and assert the refusal. Each of
-- these is one of the three defects in the migration header, tested directly
-- instead of inferred from a privilege bit.
-- ---------------------------------------------------------------------------

reset role;
set local role anon;

select throws_ok(
    $sql$insert into public.secret_access_log (secret_id, user_id, operation)
         values ('a0d17000-0000-4000-8000-000000000001',
                 'a0d17000-0000-4000-8000-000000000002', 'view')$sql$,
    '42501',
    'permission denied for table secret_access_log',
    'anon cannot attribute a secret access to somebody else'
);

select throws_ok(
    $sql$select * from public.secret_access_log$sql$,
    '42501',
    'permission denied for table secret_access_log',
    'anon cannot read the secret audit trail'
);

select throws_ok(
    $sql$select public.log_api_key_action('a0d17000-0000-4000-8000-000000000003', 'publish')$sql$,
    '42501',
    'permission denied for function log_api_key_action',
    'anon cannot forge API key history through the RPC, the door RLS never saw'
);

select throws_ok(
    $sql$select public.update_api_key_last_used('a0d17000-0000-4000-8000-000000000003')$sql$,
    '42501',
    'permission denied for function update_api_key_last_used',
    'anon cannot stamp last_used_at through the RPC'
);

reset role;
set local role authenticated;

select throws_ok(
    $sql$insert into public.secret_access_log (secret_id, user_id, operation)
         values ('a0d17000-0000-4000-8000-000000000001',
                 'a0d17000-0000-4000-8000-000000000002', 'view')$sql$,
    '42501',
    'permission denied for table secret_access_log',
    'a signed-in user cannot append an audit row, for themselves or anyone else'
);

select throws_ok(
    $sql$select public.log_api_key_action('a0d17000-0000-4000-8000-000000000003', 'publish')$sql$,
    '42501',
    'permission denied for function log_api_key_action',
    'a signed-in user cannot forge API key history either'
);

reset role;


select * from finish();
rollback;
