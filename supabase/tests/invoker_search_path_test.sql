-- pgTAP tests for the SECURITY INVOKER search_path close (20260919010000).
-- Run with: supabase test db
--
-- The migration rewrites five function bodies, so these assertions cover the
-- three ways that goes wrong: the clause not landing, something other than the
-- clause changing, and a body that no longer resolves the names it needs once
-- the path is empty. The last one is the reason the live calls are here rather
-- than a catalog check alone. An empty search_path turns an unqualified table
-- reference into a runtime error, not a migration error, so applying cleanly
-- proves nothing about whether these still work.
--
-- custom_access_token_hook is the one that matters most: GoTrue calls it as
-- supabase_auth_admin on every token issuance, so a body that fails to resolve
-- a name stops every login rather than failing one query.

begin;
select plan(17);

-- ---------------------------------------------------------------------------
-- 1-5: each function carries the empty search_path.
-- ---------------------------------------------------------------------------
select is(
    (select p.proconfig from pg_proc p
     join pg_namespace n on n.oid = p.pronamespace
     where n.nspname = 'public' and p.proname = 'check_api_key_limit'),
    array['search_path=""'],
    'check_api_key_limit pins an empty search_path'
);

select is(
    (select p.proconfig from pg_proc p
     join pg_namespace n on n.oid = p.pronamespace
     where n.nspname = 'public' and p.proname = 'cleanup_expired_completed_authentications'),
    array['search_path=""'],
    'cleanup_expired_completed_authentications pins an empty search_path'
);

select is(
    (select p.proconfig from pg_proc p
     join pg_namespace n on n.oid = p.pronamespace
     where n.nspname = 'public' and p.proname = 'trigger_cleanup_expired_completed_auths'),
    array['search_path=""'],
    'trigger_cleanup_expired_completed_auths pins an empty search_path'
);

select is(
    (select p.proconfig from pg_proc p
     join pg_namespace n on n.oid = p.pronamespace
     where n.nspname = 'public' and p.proname = 'update_plugin_timestamp'),
    array['search_path=""'],
    'update_plugin_timestamp pins an empty search_path'
);

select is(
    (select p.proconfig from pg_proc p
     join pg_namespace n on n.oid = p.pronamespace
     where n.nspname = 'public' and p.proname = 'custom_access_token_hook'),
    array['search_path=""'],
    'custom_access_token_hook pins an empty search_path'
);

-- ---------------------------------------------------------------------------
-- 6: and no OTHER invoker function is left without one.
--
-- Scoped to SECURITY INVOKER on purpose. This is the advisor's lint 0011
-- restated against the catalog, limited to the half this migration owns; the
-- definer half is the #772 sweep's, and asserting the whole schema here would
-- make this suite fail until that lands rather than when this migration breaks.
-- Stated as a property rather than as five names, so it keeps meaning when a
-- later migration adds an invoker function.
-- ---------------------------------------------------------------------------
select is_empty(
    $$ select p.proname
       from pg_proc p
       join pg_namespace n on n.oid = p.pronamespace
       where n.nspname = 'public'
         and p.prokind in ('f', 'p')
         and not p.prosecdef
         and not exists (
           select 1 from pg_catalog.pg_depend d
           where d.classid = 'pg_catalog.pg_proc'::regclass
             and d.objid = p.oid and d.deptype = 'e')
         and (p.proconfig is null
              or not exists (
                select 1 from unnest(p.proconfig) c
                where c like 'search_path=%')) $$,
    'no SECURITY INVOKER function in public is left with a mutable search_path'
);

-- ---------------------------------------------------------------------------
-- 7-11: nothing but the clause and the qualification changed.
--
-- Security mode in particular: these are SECURITY INVOKER, and flipping one to
-- DEFINER while "hardening" it would hand every caller the owner's rights.
-- ---------------------------------------------------------------------------
select is_empty(
    $$ select p.proname
       from pg_proc p
       join pg_namespace n on n.oid = p.pronamespace
       where n.nspname = 'public'
         and p.proname in ('check_api_key_limit',
                           'cleanup_expired_completed_authentications',
                           'trigger_cleanup_expired_completed_auths',
                           'update_plugin_timestamp',
                           'custom_access_token_hook')
         and p.prosecdef $$,
    'all five are still SECURITY INVOKER'
);

select is(
    (select p.provolatile from pg_proc p
     join pg_namespace n on n.oid = p.pronamespace
     where n.nspname = 'public' and p.proname = 'custom_access_token_hook'),
    's'::"char",
    'the auth hook is still STABLE'
);

select is(
    (select pg_catalog.pg_get_function_result(p.oid) from pg_proc p
     join pg_namespace n on n.oid = p.pronamespace
     where n.nspname = 'public' and p.proname = 'custom_access_token_hook'),
    'jsonb',
    'the auth hook still returns jsonb, which GoTrue requires'
);

select is(
    (select pg_catalog.count(*)::int from pg_proc p
     join pg_namespace n on n.oid = p.pronamespace
     where n.nspname = 'public'
       and p.proname in ('check_api_key_limit',
                         'trigger_cleanup_expired_completed_auths',
                         'update_plugin_timestamp')
       and pg_catalog.pg_get_function_result(p.oid) = 'trigger'),
    3,
    'the three trigger functions still return trigger'
);

select is_empty(
    $$ select p.proname
       from pg_proc p
       join pg_namespace n on n.oid = p.pronamespace
       where n.nspname = 'public'
         and p.proname in ('cleanup_expired_completed_authentications',
                           'trigger_cleanup_expired_completed_auths',
                           'update_plugin_timestamp',
                           'check_api_key_limit')
         and pg_catalog.pg_get_functiondef(p.oid) !~ 'public\.' $$,
    'every rewritten body now names its table with a schema'
);

-- ---------------------------------------------------------------------------
-- 12-14: the bodies still resolve under the empty path.
--
-- A catalog assertion cannot answer this. An unqualified name that survived the
-- rewrite raises at call time, so these have to actually run.
-- ---------------------------------------------------------------------------
select lives_ok(
    $$ select public.cleanup_expired_completed_authentications() $$,
    'the cleanup function still runs with an empty search_path'
);

select is(
    (select public.custom_access_token_hook(
        '{"user_id":"00000000-0000-0000-0000-000000000000","claims":{}}'::jsonb)
        -> 'claims' ->> 'is_admin'),
    'false',
    'the auth hook still resolves its helpers and decides is_admin for an unknown user'
);

select isnt(
    (select public.custom_access_token_hook(
        '{"user_id":"00000000-0000-0000-0000-000000000000","claims":{}}'::jsonb)
        -> 'claims' ->> 'user_permissions'),
    null,
    'the auth hook still populates user_permissions'
);

-- ---------------------------------------------------------------------------
-- 15-17: the grants the earlier sweeps settled on are still in place.
--
-- CREATE OR REPLACE keeps the ACL, and this pins that rather than trusting it:
-- 20260909130000 took the hook away from the client roles and gave it to
-- supabase_auth_admin, and a rewrite that reset the ACL would undo that
-- silently.
-- ---------------------------------------------------------------------------
select ok(
    pg_catalog.has_function_privilege('supabase_auth_admin',
        'public.custom_access_token_hook(jsonb)', 'EXECUTE'),
    'supabase_auth_admin can still execute the hook, so login still works'
);

select ok(
    not pg_catalog.has_function_privilege('anon',
        'public.custom_access_token_hook(jsonb)', 'EXECUTE'),
    'anon still cannot execute the hook'
);

select ok(
    not pg_catalog.has_function_privilege('authenticated',
        'public.custom_access_token_hook(jsonb)', 'EXECUTE'),
    'authenticated still cannot execute the hook'
);

select * from finish();
rollback;
