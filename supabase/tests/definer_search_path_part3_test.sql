-- pgTAP tests for the part-3 SECURITY DEFINER search_path hardening
-- (20260918010000, BossConsole#772).
--
-- 20260916130000 pins the three passkey lifecycle functions and
-- 20260916140000 the three the part-2 audit found. A catalog-wide audit of
-- every CREATE FUNCTION in supabase/migrations reported five more with no
-- clause at all, every one of them a live plugin-store RPC. These
-- assertions pin the closed form so a future CREATE OR REPLACE cannot
-- silently drop it, and pin that the hardening changed nothing else: the
-- client revokes applied by the 20260908-20260911 sweeps survive, and the
-- bodies still execute and still enforce their own rules.

begin;
select plan(18);

-- 1-5: each hardened function carries an empty search_path. Membership
-- checks, so an additional per-function GUC later does not break them.
select ok(
    (select 'search_path=""' = any(proconfig) from pg_proc
      where oid = 'public.record_plugin_download(uuid, uuid, uuid, text)'::regprocedure),
    'record_plugin_download pins search_path to empty'
);

select ok(
    (select 'search_path=""' = any(proconfig) from pg_proc
      where oid = 'public.upsert_plugin_rating(uuid, uuid, integer, text)'::regprocedure),
    'upsert_plugin_rating pins search_path to empty'
);

select ok(
    (select 'search_path=""' = any(proconfig) from pg_proc
      where oid = 'public.update_api_key_last_used(uuid)'::regprocedure),
    'update_api_key_last_used pins search_path to empty'
);

select ok(
    (select 'search_path=""' = any(proconfig) from pg_proc
      where oid = 'public.log_api_key_action(uuid, text, text, text, text, boolean, text)'::regprocedure),
    'log_api_key_action pins search_path to empty'
);

select ok(
    (select 'search_path=""' = any(proconfig) from pg_proc
      where oid = 'public.get_user_api_key_count(uuid)'::regprocedure),
    'get_user_api_key_count pins search_path to empty'
);

-- 6: all five at once, order-independent.
select is(
    (select count(*)::int from pg_proc
      where oid in (
        'public.record_plugin_download(uuid, uuid, uuid, text)'::regprocedure,
        'public.upsert_plugin_rating(uuid, uuid, integer, text)'::regprocedure,
        'public.update_api_key_last_used(uuid)'::regprocedure,
        'public.log_api_key_action(uuid, text, text, text, text, boolean, text)'::regprocedure,
        'public.get_user_api_key_count(uuid)'::regprocedure
      )
      and 'search_path=""' = any(proconfig)),
    5,
    'all five plugin-store functions carry the empty search_path'
);

-- 7-8: they are still SECURITY DEFINER. A replacement that dropped this
-- would "pass" the clause checks above while changing who the body runs as.
select is(
    (select count(*)::int from pg_proc
      where oid in (
        'public.record_plugin_download(uuid, uuid, uuid, text)'::regprocedure,
        'public.upsert_plugin_rating(uuid, uuid, integer, text)'::regprocedure,
        'public.update_api_key_last_used(uuid)'::regprocedure,
        'public.log_api_key_action(uuid, text, text, text, text, boolean, text)'::regprocedure,
        'public.get_user_api_key_count(uuid)'::regprocedure
      )
      and prosecdef),
    5,
    'all five remain SECURITY DEFINER'
);

select is(
    (select prorettype::regtype::text from pg_proc
      where oid = 'public.get_user_api_key_count(uuid)'::regprocedure),
    'integer',
    'get_user_api_key_count keeps the integer return the edge function compares against'
);

-- 9-13: the table references are resolved without the search_path.
select alike(
    pg_get_functiondef('public.record_plugin_download(uuid, uuid, uuid, text)'::regprocedure),
    '%INSERT INTO public.plugin_downloads%',
    'record_plugin_download resolves its table without the search_path'
);

select alike(
    pg_get_functiondef('public.upsert_plugin_rating(uuid, uuid, integer, text)'::regprocedure),
    '%UPDATE public.plugin_ratings%',
    'upsert_plugin_rating resolves its table without the search_path'
);

select alike(
    pg_get_functiondef('public.update_api_key_last_used(uuid)'::regprocedure),
    '%UPDATE public.plugin_api_keys%',
    'update_api_key_last_used resolves its table without the search_path'
);

select alike(
    pg_get_functiondef('public.log_api_key_action(uuid, text, text, text, text, boolean, text)'::regprocedure),
    '%INSERT INTO public.plugin_api_key_logs%',
    'log_api_key_action resolves its table without the search_path'
);

select alike(
    pg_get_functiondef('public.get_user_api_key_count(uuid)'::regprocedure),
    '%FROM public.plugin_api_keys%',
    'get_user_api_key_count resolves its table without the search_path'
);

-- 14-15: the bodies still run under the closed path, and still enforce
-- their own rules. The bounds check fires before any table access, so it
-- proves execution without needing plugin and version rows.
select is(
    public.get_user_api_key_count('f0000000-0000-4000-8000-0000000009d1'::uuid),
    0,
    'get_user_api_key_count executes with the closed search_path and still counts'
);

select throws_ok(
    $$ select * from public.upsert_plugin_rating(
           'f0000000-0000-4000-8000-0000000009d2'::uuid,
           'f0000000-0000-4000-8000-0000000009d3'::uuid, 9, '') $$,
    'Rating must be between 1 and 5',
    'upsert_plugin_rating executes with the closed search_path and keeps its bounds check'
);

-- 16-18: the hardening did not go too far. CREATE OR REPLACE preserves the
-- ACL, so the client revokes from the 20260908-20260911 sweeps must still
-- hold and the operational role must still reach these.
select ok(
    not has_function_privilege('authenticated', 'public.update_api_key_last_used(uuid)', 'EXECUTE'),
    'authenticated still cannot execute update_api_key_last_used (revoked by 20260910000000; unchanged here)'
);

select ok(
    not has_function_privilege('anon', 'public.log_api_key_action(uuid, text, text, text, text, boolean, text)', 'EXECUTE'),
    'anon still cannot execute log_api_key_action (revoked by 20260911010000; unchanged here)'
);

select ok(
    has_function_privilege('service_role', 'public.record_plugin_download(uuid, uuid, uuid, text)', 'EXECUTE'),
    'service_role keeps the download RPC the plugin-store edge function calls'
);

select * from finish();
rollback;
