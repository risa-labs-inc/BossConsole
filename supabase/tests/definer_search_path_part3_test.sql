-- pgTAP tests for the part-3 SECURITY DEFINER search_path hardening
-- (20260919000000, BossConsole#1165).
--
-- Part-1 (20260916130000, #773) and part-2 (20260916140000, #772) pinned
-- the passkey lifecycle and identity/secret sets; the live catalog audit
-- behind #1165 shows exactly five plugin-store SECURITY DEFINER RPCs
-- still resolving through the caller-influenced search_path:
--
--   record_plugin_download, upsert_plugin_rating, update_api_key_last_used,
--   log_api_key_action, get_user_api_key_count
--
-- These assertions pin the closed form (proconfig membership, SECURITY
-- DEFINER retained, grants untouched), prove each RPC still executes
-- against its real tables under the closed path, and then run the
-- adversarial case the migration exists for: a hostile schema placed
-- first on the caller's search_path, holding shadow copies of every
-- table the five RPCs reference plus a canary row (same id as the real
-- key, marked revoked). If any pin is dropped, the unqualified references
-- resolve into the hostile schema instead: the writes land in the shadow
-- tables, the definer UPDATE stamps the hostile canary, the upsert
-- reports its insert branch against an empty shadow, and the count read
-- misses the real key row - exactly what the landing assertions below
-- detect. All fixtures roll back with us.

begin;
select plan(31);

-- 1-5: each hardened RPC pins an empty search_path in pg_proc.proconfig.
-- Array containment (@>) instead of proconfig[1] so a later-added SET
-- clause cannot push the pin out of position without tripping the assertion.
select is(
    (select coalesce(p.proconfig @> ARRAY['search_path=""']::text[], false)
      from pg_proc p
     where p.oid = 'public.record_plugin_download(uuid, uuid, uuid, text)'::regprocedure),
    true,
    'record_plugin_download pins search_path to empty'
);

select is(
    (select coalesce(p.proconfig @> ARRAY['search_path=""']::text[], false)
      from pg_proc p
     where p.oid = 'public.upsert_plugin_rating(uuid, uuid, integer, text)'::regprocedure),
    true,
    'upsert_plugin_rating pins search_path to empty'
);

select is(
    (select coalesce(p.proconfig @> ARRAY['search_path=""']::text[], false)
      from pg_proc p
     where p.oid = 'public.update_api_key_last_used(uuid)'::regprocedure),
    true,
    'update_api_key_last_used pins search_path to empty'
);

select is(
    (select coalesce(p.proconfig @> ARRAY['search_path=""']::text[], false)
      from pg_proc p
     where p.oid = 'public.log_api_key_action(uuid, text, text, text, text, boolean, text)'::regprocedure),
    true,
    'log_api_key_action pins search_path to empty'
);

select is(
    (select coalesce(p.proconfig @> ARRAY['search_path=""']::text[], false)
      from pg_proc p
     where p.oid = 'public.get_user_api_key_count(uuid)'::regprocedure),
    true,
    'get_user_api_key_count pins search_path to empty'
);

-- 6: every RPC this migration hardened reports the empty search_path
-- somewhere in proconfig (membership test, order-independent).
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
    'all five functions carry the empty search_path (membership test, order-independent)'
);

-- 7: the definer attribute is pinned too; a future CREATE OR REPLACE must
-- not downgrade these to invoker while borrowing the pin.
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
    'all five functions remain SECURITY DEFINER'
);

-- Fixtures: a user, a published plugin with a version, and one active
-- API key - mirroring plugin_downloads_access_test.sql. Only (id, email)
-- is needed from auth.users: the five RPCs never read it, the row exists
-- for the user_id foreign keys, and both the current and the migrated
-- auth schemas carry those two columns.
insert into auth.users (id, email)
values ('d3f1e500-0000-4000-8000-000000000001', 'definer-part3@pgtap.test');

insert into public.plugins (id, plugin_id, display_name, author_name, published, visibility)
values ('d3f1e500-0000-4000-8000-000000000002', 'test.definer.part3', 'Definer Part3', 'tester', true, 'public');

insert into public.plugin_versions (id, plugin_id, version, jar_path, sha256)
values ('d3f1e500-0000-4000-8000-000000000003', 'd3f1e500-0000-4000-8000-000000000002', '1.0.0', 'test/definer.jar', repeat('a', 64));

insert into public.plugin_api_keys (id, user_id, name, key_prefix, key_hash)
values ('d3f1e500-0000-4000-8000-000000000004', 'd3f1e500-0000-4000-8000-000000000001', 'part3-key', 'pk_part3', repeat('b', 64));

-- 8-12: each RPC still executes against its real tables under the closed
-- search_path (this session runs as the owner role, which holds EXECUTE).
select lives_ok(
    $$ select public.record_plugin_download(
           'd3f1e500-0000-4000-8000-000000000002',
           'd3f1e500-0000-4000-8000-000000000003',
           'd3f1e500-0000-4000-8000-000000000001',
           'part3-ip-hash') $$,
    'record_plugin_download executes with the closed search_path'
);

select lives_ok(
    $$ select public.upsert_plugin_rating(
           'd3f1e500-0000-4000-8000-000000000002',
           'd3f1e500-0000-4000-8000-000000000001',
           5, 'part3 review') $$,
    'upsert_plugin_rating executes with the closed search_path'
);

select lives_ok(
    $$ select public.update_api_key_last_used('d3f1e500-0000-4000-8000-000000000004') $$,
    'update_api_key_last_used executes with the closed search_path'
);

select lives_ok(
    $$ select public.log_api_key_action(
           'd3f1e500-0000-4000-8000-000000000004', 'test',
           'test.definer.part3', '127.0.0.1', 'pgtap', true, NULL) $$,
    'log_api_key_action executes with the closed search_path'
);

select is(
    (select public.get_user_api_key_count('d3f1e500-0000-4000-8000-000000000001')),
    1,
    'get_user_api_key_count reads the real key row under the closed search_path'
);

-- 13: the second rating call takes the documented update branch.
select is(
    (select created from public.upsert_plugin_rating(
        'd3f1e500-0000-4000-8000-000000000002',
        'd3f1e500-0000-4000-8000-000000000001',
        4, 'part3 updated review')),
    false,
    'upsert_plugin_rating reports created=false on the update branch'
);

-- The adversarial case: a hostile schema placed FIRST on the session
-- search_path, holding shadow copies of every table the five RPCs
-- reference. Under a dropped pin the unqualified references resolve
-- here; under the pin they cannot. The canary key carries the SAME id as
-- the real fixture key (so a shadowed UPDATE finds and stamps it) and is
-- revoked (so a shadowed count read misses it and returns 0 instead of
-- the real row's 1).
create schema hostile_part3;

create table hostile_part3.plugin_downloads (
    id uuid primary key default gen_random_uuid(),
    plugin_id uuid,
    version_id uuid,
    user_id uuid,
    ip_hash text
);

create table hostile_part3.plugin_ratings (
    id uuid primary key default gen_random_uuid(),
    plugin_id uuid,
    user_id uuid,
    rating integer,
    review text,
    created_at timestamptz,
    updated_at timestamptz
);

create table hostile_part3.plugin_api_keys (
    id uuid primary key,
    user_id uuid,
    name text,
    key_prefix text,
    key_hash text,
    created_at timestamptz,
    last_used_at timestamptz,
    expires_at timestamptz,
    revoked_at timestamptz,
    org_id uuid
);

create table hostile_part3.plugin_api_key_logs (
    id uuid primary key default gen_random_uuid(),
    api_key_id uuid,
    action text,
    plugin_id text,
    ip_address text,
    user_agent text,
    success boolean,
    error_message text,
    created_at timestamptz
);

insert into hostile_part3.plugin_api_keys (id, user_id, name, key_prefix, key_hash, created_at, revoked_at)
values ('d3f1e500-0000-4000-8000-000000000004', 'd3f1e500-0000-4000-8000-000000000001', 'hostile-canary', 'hk', repeat('c', 64), now(), now());

set search_path to hostile_part3, public;

-- 14-18: all five RPCs still execute under the hostile-first path - the
-- pin, not the caller, decides where their references resolve.
select lives_ok(
    $$ select public.record_plugin_download(
           'd3f1e500-0000-4000-8000-000000000002',
           'd3f1e500-0000-4000-8000-000000000003',
           'd3f1e500-0000-4000-8000-000000000001',
           'hostile-path-hash') $$,
    'record_plugin_download executes under a hostile-first search_path'
);

select is(
    (select created from public.upsert_plugin_rating(
        'd3f1e500-0000-4000-8000-000000000002',
        'd3f1e500-0000-4000-8000-000000000001',
        3, 'hostile-path review')),
    false,
    'the upsert still takes the update branch under the hostile path (a dropped pin would insert into the empty shadow and report created=true)'
);

select lives_ok(
    $$ select public.update_api_key_last_used('d3f1e500-0000-4000-8000-000000000004') $$,
    'update_api_key_last_used executes under the hostile-first search_path'
);

select lives_ok(
    $$ select public.log_api_key_action(
           'd3f1e500-0000-4000-8000-000000000004', 'test',
           'test.definer.part3', '127.0.0.1', 'pgtap-hostile', false, NULL) $$,
    'log_api_key_action executes under the hostile-first search_path'
);

select is(
    (select public.get_user_api_key_count('d3f1e500-0000-4000-8000-000000000001')),
    1,
    'get_user_api_key_count ignores the revoked hostile canary and keeps reading public.plugin_api_keys'
);

-- 19-23: the hostile schema captured nothing.
select is(
    (select count(*)::int from hostile_part3.plugin_downloads),
    0,
    'the hostile shadow never captured a download row'
);

select is(
    (select count(*)::int from hostile_part3.plugin_ratings),
    0,
    'the hostile shadow never captured a rating row'
);

select is(
    (select count(*)::int from hostile_part3.plugin_api_key_logs),
    0,
    'the hostile shadow never captured an audit-log row'
);

select is(
    (select count(*)::int from hostile_part3.plugin_api_keys),
    1,
    'the hostile shadow holds only the planted canary'
);

select is(
    (select last_used_at is null from hostile_part3.plugin_api_keys where id = 'd3f1e500-0000-4000-8000-000000000004'),
    true,
    'the definer UPDATE never stamped the hostile canary'
);

-- 24-27: the hostile-path writes all landed in the real tables.
select is(
    (select count(*)::int from public.plugin_downloads where plugin_id = 'd3f1e500-0000-4000-8000-000000000002'),
    2,
    'both downloads landed in the real table'
);

select is(
    (select count(*)::int from public.plugin_ratings where plugin_id = 'd3f1e500-0000-4000-8000-000000000002'),
    1,
    'the upsert under the hostile path still updated the real rating row'
);

select is(
    (select count(*)::int from public.plugin_api_key_logs where api_key_id = 'd3f1e500-0000-4000-8000-000000000004'),
    2,
    'both audit rows landed in the real table'
);

select is(
    (select last_used_at is not null from public.plugin_api_keys where id = 'd3f1e500-0000-4000-8000-000000000004'),
    true,
    'the definer UPDATE still stamps the real key row'
);

-- 28-31: grants are untouched - the mutators stay client-revoked,
-- the service role keeps the edge-function path, and the read-only
-- counter keeps its authenticated grant.
select is(
    (select count(*)::int from pg_proc p
      where p.oid in (
        'public.record_plugin_download(uuid, uuid, uuid, text)'::regprocedure,
        'public.upsert_plugin_rating(uuid, uuid, integer, text)'::regprocedure,
        'public.update_api_key_last_used(uuid)'::regprocedure,
        'public.log_api_key_action(uuid, text, text, text, text, boolean, text)'::regprocedure,
        'public.get_user_api_key_count(uuid)'::regprocedure
      )
      and has_function_privilege('service_role', p.oid, 'EXECUTE')),
    5,
    'service_role keeps EXECUTE on all five RPCs (the plugin-store edge-function path)'
);

select is(
    (select count(*)::int from pg_proc p
      where p.oid in (
        'public.record_plugin_download(uuid, uuid, uuid, text)'::regprocedure,
        'public.upsert_plugin_rating(uuid, uuid, integer, text)'::regprocedure,
        'public.update_api_key_last_used(uuid)'::regprocedure,
        'public.log_api_key_action(uuid, text, text, text, text, boolean, text)'::regprocedure
      )
      and has_function_privilege('anon', p.oid, 'EXECUTE')),
    0,
    'anon still cannot execute any of the four mutators (revoked by 20260909130000 / 20260910000000; unchanged here)'
);

select is(
    (select count(*)::int from pg_proc p
      where p.oid in (
        'public.record_plugin_download(uuid, uuid, uuid, text)'::regprocedure,
        'public.upsert_plugin_rating(uuid, uuid, integer, text)'::regprocedure,
        'public.update_api_key_last_used(uuid)'::regprocedure,
        'public.log_api_key_action(uuid, text, text, text, text, boolean, text)'::regprocedure
      )
      and has_function_privilege('authenticated', p.oid, 'EXECUTE')),
    0,
    'authenticated still cannot execute any of the four mutators'
);

select ok(
    has_function_privilege('authenticated', 'public.get_user_api_key_count(uuid)', 'EXECUTE'),
    'authenticated keeps the read-only key-count RPC (its grant is untouched by the pinning)'
);

select * from finish();
rollback;
