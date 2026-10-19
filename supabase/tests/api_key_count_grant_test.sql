-- pgTAP tests for 20260924100000: get_user_api_key_count is service-role only.
-- Run with: supabase test db
--
-- The function is SECURITY DEFINER and answers for any user id it is handed, so
-- the grant IS the access control. Assertions 1-3 are the change; 4-5 are why it
-- mattered; 6-7 are the path that must keep working; 8 is the call a client makes
-- in practice, refused the way PostgREST would see it; 9 restates the owner grant.

begin;
select plan(9);

-- ---------------------------------------------------------------------------
-- 1-3: no client role can execute it.
--
-- Asked through has_function_privilege, not by reading proacl: a NULL proacl means
-- the default ACL, in which PUBLIC can execute, and aclexplode(NULL) returns no
-- rows, so reading the entries would pass in exactly the case that matters.
-- ---------------------------------------------------------------------------
select ok(
    not pg_catalog.has_function_privilege('authenticated',
        'public.get_user_api_key_count(uuid)', 'EXECUTE'),
    'authenticated cannot execute get_user_api_key_count'
);

select ok(
    not pg_catalog.has_function_privilege('anon',
        'public.get_user_api_key_count(uuid)', 'EXECUTE'),
    'anon cannot execute get_user_api_key_count'
);

select ok(
    not pg_catalog.has_function_privilege('public',
        'public.get_user_api_key_count(uuid)', 'EXECUTE'),
    'and PUBLIC cannot execute it either'
);

-- ---------------------------------------------------------------------------
-- 4-5: why it mattered. The table already refuses another user's rows; the
-- definer function was the way around that. If either stops being true, this
-- suite is measuring the wrong thing and should fail loudly.
-- ---------------------------------------------------------------------------
select ok(
    (select c.relrowsecurity from pg_catalog.pg_class c
     join pg_catalog.pg_namespace n on n.oid = c.relnamespace
     where n.nspname = 'public' and c.relname = 'plugin_api_keys'),
    'plugin_api_keys still has row level security enabled'
);

select isnt_empty(
    $$ select p.polname
       from pg_catalog.pg_policy p
       join pg_catalog.pg_class c on c.oid = p.polrelid
       join pg_catalog.pg_namespace n on n.oid = c.relnamespace
       where n.nspname = 'public' and c.relname = 'plugin_api_keys'
         and p.polcmd = 'r'
         and pg_catalog.pg_get_expr(p.polqual, p.polrelid) ~ 'auth\.uid\(\)' $$,
    'plugin_api_keys still restricts a reader to rows matching their own id'
);

-- ---------------------------------------------------------------------------
-- 6-7: the Edge Function's key-limit check is untouched. It runs as
-- service_role; losing that grant would fail every API key creation.
-- ---------------------------------------------------------------------------
select ok(
    pg_catalog.has_function_privilege('service_role',
        'public.get_user_api_key_count(uuid)', 'EXECUTE'),
    'service_role can still execute it (the plugin-store key-limit check)'
);

select ok(
    (select p.prosecdef from pg_catalog.pg_proc p
     where p.oid = 'public.get_user_api_key_count(uuid)'::regprocedure),
    'get_user_api_key_count is still SECURITY DEFINER; this is a grant change only'
);

-- ---------------------------------------------------------------------------
-- 8: the call itself, as a signed-in client would make it through PostgREST,
-- for somebody else's id. Refused before the body runs, with insufficient
-- privilege rather than an answer of 0 that would read as "no keys".
-- ---------------------------------------------------------------------------
set local role authenticated;

select throws_ok(
    $$ select public.get_user_api_key_count('00000000-0000-4000-8000-00000000c0de'::uuid) $$,
    '42501',
    null,
    'a signed-in client asking for another user''s key count is refused'
);

reset role;

-- ---------------------------------------------------------------------------
-- 9: the owner grant the migration restates.
-- ---------------------------------------------------------------------------
select ok(
    pg_catalog.has_function_privilege('postgres',
        'public.get_user_api_key_count(uuid)', 'EXECUTE'),
    'postgres keeps EXECUTE, restated in the migration'
);

select * from finish();
rollback;
