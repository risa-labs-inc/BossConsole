-- Direct reads of plugin_versions must not surface unfinalized rows (#1630).
-- The store functions were gated by 20260923133000, but a client can also read
-- the table itself -- PostgREST (`GET /rest/v1/plugin_versions?...`) and the
-- realtime subscription PluginStoreRealtimeService holds with the anon key --
-- and those answer through the "Published versions are viewable" SELECT
-- policy. Until 20260929000000 that policy tested only the plugin's
-- visibility, so an interrupted publish's pending row (sha256='pending',
-- jar_size=0, jar_path to an artifact that does not exist) streamed to anyone.
--
-- The per-surface split is deliberate and is what this file pins:
--   * anon / authenticated non-owner: pending rows are INVISIBLE (fail-closed
--     on the sha256 <> 'pending' AND jar_size > 0 pair, NULL size included);
--   * the author keeps their own pending rows (publisher-facing view, where an
--     interrupted publish is repaired);
--   * plugins.admin.view keeps everything, pending included;
--   * service_role bypasses RLS entirely, so the edge function's reads --
--     finalize's getVersionById repair path included -- are untouched.
--
-- Run against a disposable migrated database with: supabase test db
-- All synthetic fixtures are rolled back.

begin;
select plan(20);

-- ---------------------------------------------------------------------------
-- Fixtures: a public published plugin with a finalized 1.0.0 (older) and a
-- pending 2.0.0 (newer, higher semver -- the adversarial case); a second
-- plugin with ONLY a pending row.
-- ---------------------------------------------------------------------------
insert into auth.users (id, email, email_confirmed_at) values
    ('e1630000-0000-0000-0000-000000000001', 'readgate-author@pgtap.test',   now()),
    ('e1630000-0000-0000-0000-000000000002', 'readgate-outsider@pgtap.test', now()),
    ('e1630000-0000-0000-0000-000000000003', 'readgate-admin@pgtap.test',    now());

insert into public.user_roles (user_id, role_id)
select 'e1630000-0000-0000-0000-000000000003', id from public.roles where name='admin'
on conflict do nothing;

insert into public.plugins (id, plugin_id, display_name, author_id, author_name, published, visibility)
values ('e1630000-0000-0000-0000-000000000010', 'test.read.gate', 'Read Gate',
        'e1630000-0000-0000-0000-000000000001', 'readgate-author', true, 'public');

insert into public.plugin_versions (id, plugin_id, version, jar_path, sha256, jar_size, published_at)
values ('e1630000-0000-0000-0000-000000000011', 'e1630000-0000-0000-0000-000000000010', '1.0.0',
        'test/read-gate-1.0.0.jar', repeat('a', 64), 1024, now() - interval '2 days');

insert into public.plugin_versions (id, plugin_id, version, jar_path, sha256, jar_size, published_at)
values ('e1630000-0000-0000-0000-000000000012', 'e1630000-0000-0000-0000-000000000010', '2.0.0',
        'test/read-gate-2.0.0.jar', 'pending', 0, now());

insert into public.plugins (id, plugin_id, display_name, author_id, author_name, published, visibility)
values ('e1630000-0000-0000-0000-000000000020', 'test.read.only', 'Read Only',
        'e1630000-0000-0000-0000-000000000001', 'readgate-author', true, 'public');

insert into public.plugin_versions (id, plugin_id, version, jar_path, sha256, jar_size)
values ('e1630000-0000-0000-0000-000000000021', 'e1630000-0000-0000-0000-000000000020', '1.0.0',
        'test/read-only-1.0.0.jar', 'pending', 0);

-- ---------------------------------------------------------------------------
-- Catalogue pins: the public SELECT policy exists and carries the gate text,
-- so a later edit that drops the predicate while keeping the name fails here.
-- ---------------------------------------------------------------------------
select is(
    (select count(*)::int from pg_policies
      where schemaname = 'public' and tablename = 'plugin_versions'
        and policyname = 'Published versions are viewable' and cmd = 'SELECT'),
    1,
    'the public SELECT policy on plugin_versions still exists');

select ok(
    (select qual::text from pg_policies
      where schemaname = 'public' and tablename = 'plugin_versions'
        and policyname = 'Published versions are viewable') ilike '%pending%',
    'the public SELECT policy names the pending sentinel');

select ok(
    (select qual::text from pg_policies
      where schemaname = 'public' and tablename = 'plugin_versions'
        and policyname = 'Published versions are viewable') ilike '%jar_size%',
    'the public SELECT policy carries the jar_size half of the gate');

-- ---------------------------------------------------------------------------
-- ANON: the direct table read (and the realtime feed, filtered by the same
-- policies) sees only the finalized row.
-- ---------------------------------------------------------------------------
select set_config('request.jwt.claims', '', true);
set local role anon;

select is(
    (select count(*) from public.plugin_versions pv
      join public.plugins p on p.id = pv.plugin_id
      where p.plugin_id = 'test.read.gate'),
    1::bigint,
    'ANON direct read: only the finalized version is returned');

select is(
    (select count(*) from public.plugin_versions pv
      join public.plugins p on p.id = pv.plugin_id
      where p.plugin_id = 'test.read.gate' and pv.sha256 = 'pending'),
    0::bigint,
    'ANON direct read: the pending row and its jar_path are invisible');

select is(
    (select count(*) from public.plugin_versions pv
      join public.plugins p on p.id = pv.plugin_id
      where p.plugin_id = 'test.read.only'),
    0::bigint,
    'ANON direct read: a plugin with only a pending row exposes no versions');

-- ---------------------------------------------------------------------------
-- AUTHENTICATED outsider: same as anon -- the row is not theirs.
-- ---------------------------------------------------------------------------
reset role;
select set_config('request.jwt.claims',
    '{"sub":"e1630000-0000-0000-0000-000000000002","role":"authenticated"}', true);
set local role authenticated;

select is(
    (select count(*) from public.plugin_versions pv
      join public.plugins p on p.id = pv.plugin_id
      where p.plugin_id = 'test.read.gate'),
    1::bigint,
    'AUTHENTICATED outsider: only the finalized version is returned');

select is(
    (select count(*) from public.plugin_versions pv
      join public.plugins p on p.id = pv.plugin_id
      where p.plugin_id = 'test.read.gate' and pv.version = '2.0.0'),
    0::bigint,
    'AUTHENTICATED outsider: the pending version is invisible');

-- ---------------------------------------------------------------------------
-- AUTHOR: the publisher-facing view keeps the pending row -- that is where an
-- interrupted publish is seen and repaired.
-- ---------------------------------------------------------------------------
reset role;
select set_config('request.jwt.claims',
    '{"sub":"e1630000-0000-0000-0000-000000000001","role":"authenticated"}', true);
set local role authenticated;

select is(
    (select count(*) from public.plugin_versions pv
      join public.plugins p on p.id = pv.plugin_id
      where p.plugin_id = 'test.read.gate'),
    2::bigint,
    'AUTHOR: still sees both rows, the pending one included');

select is(
    (select count(*) from public.plugin_versions pv
      join public.plugins p on p.id = pv.plugin_id
      where p.plugin_id = 'test.read.gate' and pv.sha256 = 'pending'),
    1::bigint,
    'AUTHOR: the pending row itself is still readable for repair');

-- ---------------------------------------------------------------------------
-- ADMIN: plugins.admin.view keeps the full list.
-- ---------------------------------------------------------------------------
reset role;
select set_config('request.jwt.claims',
    '{"sub":"e1630000-0000-0000-0000-000000000003","role":"authenticated"}', true);
set local role authenticated;

select is(
    (select count(*) from public.plugin_versions pv
      join public.plugins p on p.id = pv.plugin_id
      where p.plugin_id = 'test.read.gate'),
    2::bigint,
    'ADMIN: sees both rows, the pending one included');

-- ---------------------------------------------------------------------------
-- SERVICE ROLE: bypasses RLS outright -- the edge function (finalize included)
-- is unaffected.
-- ---------------------------------------------------------------------------
reset role;
set local role service_role;

select is(
    (select count(*) from public.plugin_versions pv
      join public.plugins p on p.id = pv.plugin_id
      where p.plugin_id = 'test.read.gate'),
    2::bigint,
    'SERVICE_ROLE: still sees every row, so the edge function is untouched');

-- ---------------------------------------------------------------------------
-- Poison shapes: either half of finalization alone stays refused.
-- ---------------------------------------------------------------------------
reset role;
update public.plugin_versions set jar_size = 2048
 where id = 'e1630000-0000-0000-0000-000000000012';
select set_config('request.jwt.claims', '', true);
set local role anon;

select is(
    (select count(*) from public.plugin_versions pv
      join public.plugins p on p.id = pv.plugin_id
      where p.plugin_id = 'test.read.gate'),
    1::bigint,
    'poison A: sentinel sha with nonzero size is still hidden');

reset role;
update public.plugin_versions set sha256 = repeat('b', 64), jar_size = 0
 where id = 'e1630000-0000-0000-0000-000000000012';
set local role anon;

select is(
    (select count(*) from public.plugin_versions pv
      join public.plugins p on p.id = pv.plugin_id
      where p.plugin_id = 'test.read.gate'),
    1::bigint,
    'poison B: real sha with zero size is still hidden');

reset role;
update public.plugin_versions set jar_size = NULL
 where id = 'e1630000-0000-0000-0000-000000000012';
set local role anon;

select is(
    (select count(*) from public.plugin_versions pv
      join public.plugins p on p.id = pv.plugin_id
      where p.plugin_id = 'test.read.gate'),
    1::bigint,
    'poison C: NULL jar_size fails closed, still hidden');

-- ---------------------------------------------------------------------------
-- Finalize the row completely: it becomes publicly readable.
-- ---------------------------------------------------------------------------
reset role;
update public.plugin_versions set jar_size = 2048
 where id = 'e1630000-0000-0000-0000-000000000012';
set local role anon;

select is(
    (select count(*) from public.plugin_versions pv
      join public.plugins p on p.id = pv.plugin_id
      where p.plugin_id = 'test.read.gate'),
    2::bigint,
    'a finalized row becomes visible to direct reads');

select is(
    (select version from public.plugin_versions pv
      join public.plugins p on p.id = pv.plugin_id
      where p.plugin_id = 'test.read.gate'
      order by pv.published_at desc limit 1),
    '2.0.0'::text,
    'the newly finalized row is the one direct reads now see');

-- ---------------------------------------------------------------------------
-- Healthy data is unchanged: a finalized row on a healthy plugin reads as
-- before, and the plugin row itself is untouched by the version gate.
-- ---------------------------------------------------------------------------
reset role;

insert into public.plugins (id, plugin_id, display_name, author_id, author_name, published, visibility)
values ('e1630000-0000-0000-0000-000000000030', 'test.read.healthy', 'Read Healthy',
        'e1630000-0000-0000-0000-000000000001', 'readgate-author', true, 'public');

insert into public.plugin_versions (id, plugin_id, version, jar_path, sha256, jar_size)
values ('e1630000-0000-0000-0000-000000000031', 'e1630000-0000-0000-0000-000000000030', '1.5.0',
        'test/read-healthy-1.5.0.jar', repeat('d', 64), 2048);

select is(
    (select count(*) from public.plugin_versions pv
      join public.plugins p on p.id = pv.plugin_id
      where p.plugin_id = 'test.read.healthy'),
    1::bigint,
    'a healthy finalized version reads unchanged');

reset role;
set local role anon;

select is(
    (select count(*) from public.plugin_versions pv
      join public.plugins p on p.id = pv.plugin_id
      where p.plugin_id = 'test.read.healthy'),
    1::bigint,
    'ANON direct read: the healthy finalized version still reads');

select is(
    (select count(*) from public.plugins where plugin_id = 'test.read.only'),
    1::bigint,
    'ANON: the plugin row itself stays browsable -- only the nonexistent publish is hidden');

select * from finish();
rollback;
