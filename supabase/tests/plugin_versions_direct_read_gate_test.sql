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
select plan(31);

-- ---------------------------------------------------------------------------
-- Fixtures: a public published plugin with a finalized 1.0.0 (older) and a
-- pending 2.0.0 (newer, higher semver -- the adversarial case); a second
-- plugin with ONLY a pending row. Plus two NON-PUBLIC plugins (org and
-- unlisted), because this migration rewrites the whole USING clause: without
-- them a regression that dropped the can_view_plugin_row arm would pass every
-- assertion here while exposing org/unlisted version rows to anonymous reads.
-- ---------------------------------------------------------------------------
insert into auth.users (id, email, email_confirmed_at) values
    ('e1630000-0000-0000-0000-000000000001', 'readgate-author@pgtap.test',   now()),
    ('e1630000-0000-0000-0000-000000000002', 'readgate-outsider@pgtap.test', now()),
    ('e1630000-0000-0000-0000-000000000003', 'readgate-admin@pgtap.test',    now()),
    ('e1630000-0000-0000-0000-000000000004', 'readgate-member@pgtap.test',   now()),
    ('e1630000-0000-0000-0000-000000000005', 'readgate-orgowner@pgtap.test', now());

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

-- An organisation the AUTHOR does not own: readgate-orgowner (005) founds it,
-- readgate-member (004) is a plain active member, and readgate-outsider (002)
-- has no relationship. Authoring stays on 001 for every plugin so that the
-- owner/member assertions isolate the visibility arm rather than authorship.
select public.create_organisation_internal(
    p_slug=>'readgateorg', p_name=>'Read Gate Org',
    p_owner_id=>'e1630000-0000-0000-0000-000000000005',
    p_visibility=>'private', p_join_policy=>'invite_only');

create temporary table t_rg_org (v uuid);
insert into t_rg_org select id from public.organisations where slug='readgateorg';

insert into public.organisation_members (org_id, user_id, status, joined_at, join_source)
select v, 'e1630000-0000-0000-0000-000000000004', 'active', now(), 'admin'
from t_rg_org;

-- 'org' is visible to published-org members only; 'unlisted' to the author and
-- org admins only. Both get a finalized row AND a pending row, so the tests can
-- pin that visibility and finalization gate INDEPENDENTLY on each arm.
insert into public.plugins (id, plugin_id, display_name, author_id, author_name, published, visibility, org_id)
values ('e1630000-0000-0000-0000-000000000040', 'test.read.org', 'Read Gate Org',
        'e1630000-0000-0000-0000-000000000001', 'readgate-author', true, 'org',
        (select v from t_rg_org));

insert into public.plugin_versions (id, plugin_id, version, jar_path, sha256, jar_size)
values ('e1630000-0000-0000-0000-000000000041', 'e1630000-0000-0000-0000-000000000040', '1.0.0',
        'test/read-org-1.0.0.jar', repeat('c', 64), 1024);

insert into public.plugin_versions (id, plugin_id, version, jar_path, sha256, jar_size)
values ('e1630000-0000-0000-0000-000000000042', 'e1630000-0000-0000-0000-000000000040', '2.0.0',
        'test/read-org-2.0.0.jar', 'pending', 0);

insert into public.plugins (id, plugin_id, display_name, author_id, author_name, published, visibility, org_id)
values ('e1630000-0000-0000-0000-000000000050', 'test.read.unlisted', 'Read Gate Unlisted',
        'e1630000-0000-0000-0000-000000000001', 'readgate-author', true, 'unlisted',
        (select v from t_rg_org));

insert into public.plugin_versions (id, plugin_id, version, jar_path, sha256, jar_size)
values ('e1630000-0000-0000-0000-000000000051', 'e1630000-0000-0000-0000-000000000050', '1.0.0',
        'test/read-unlisted-1.0.0.jar', repeat('e', 64), 1024);

insert into public.plugin_versions (id, plugin_id, version, jar_path, sha256, jar_size)
values ('e1630000-0000-0000-0000-000000000052', 'e1630000-0000-0000-0000-000000000050', '2.0.0',
        'test/read-unlisted-2.0.0.jar', 'pending', 0);

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

select ok(
    (select qual::text from pg_policies
      where schemaname = 'public' and tablename = 'plugin_versions'
        and policyname = 'Published versions are viewable') ilike '%can_view_plugin_row%',
    'the public SELECT policy keeps the can_view_plugin_row visibility arm');

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
-- NON-PUBLIC plugins: the visibility arm must still gate direct reads. A
-- regression that dropped can_view_plugin_row from the USING clause would pass
-- every assertion above (all their fixtures are public) while streaming org
-- and unlisted jar_paths to anyone.
--
-- These queries filter plugin_versions.plugin_id by the UUID directly -- the
-- same shape as `GET /rest/v1/plugin_versions?plugin_id=eq.<uuid>`. They must
-- NOT join through public.plugins: the join would apply the plugins table's
-- own RLS on top, hiding the parent row and masking a missing visibility arm
-- (the sabotage check this section exists for).
-- ---------------------------------------------------------------------------
reset role;
select set_config('request.jwt.claims', '', true);
set local role anon;

select is(
    (select count(*) from public.plugin_versions
      where plugin_id = 'e1630000-0000-0000-0000-000000000040'),
    0::bigint,
    'ANON: the org plugin exposes no version rows, finalized or not');

select is(
    (select count(*) from public.plugin_versions
      where plugin_id = 'e1630000-0000-0000-0000-000000000050'),
    0::bigint,
    'ANON: the unlisted plugin exposes no version rows either');

reset role;
select set_config('request.jwt.claims',
    '{"sub":"e1630000-0000-0000-0000-000000000002","role":"authenticated"}', true);
set local role authenticated;

select is(
    (select count(*) from public.plugin_versions
      where plugin_id = 'e1630000-0000-0000-0000-000000000040'),
    0::bigint,
    'OUTSIDER: signed in is not membership -- the org plugin stays hidden');

select is(
    (select count(*) from public.plugin_versions
      where plugin_id = 'e1630000-0000-0000-0000-000000000050'),
    0::bigint,
    'OUTSIDER: the unlisted plugin stays hidden too');

reset role;
select set_config('request.jwt.claims',
    '{"sub":"e1630000-0000-0000-0000-000000000004","role":"authenticated"}', true);
set local role authenticated;

select is(
    (select count(*) from public.plugin_versions
      where plugin_id = 'e1630000-0000-0000-0000-000000000040'),
    1::bigint,
    'MEMBER: the org plugin shows its finalized version');

select is(
    (select count(*) from public.plugin_versions
      where plugin_id = 'e1630000-0000-0000-0000-000000000040' and sha256 = 'pending'),
    0::bigint,
    'MEMBER: but NOT its pending row -- the gate applies even when visibility passes');

select is(
    (select count(*) from public.plugin_versions
      where plugin_id = 'e1630000-0000-0000-0000-000000000050'),
    0::bigint,
    'MEMBER: unlisted is install-by-link, not org-wide -- a plain member sees nothing');

reset role;
select set_config('request.jwt.claims',
    '{"sub":"e1630000-0000-0000-0000-000000000005","role":"authenticated"}', true);
set local role authenticated;

select is(
    (select count(*) from public.plugin_versions
      where plugin_id = 'e1630000-0000-0000-0000-000000000040'),
    1::bigint,
    'ORG OWNER: sees the org plugin''s finalized version, pending still hidden');

select is(
    (select count(*) from public.plugin_versions
      where plugin_id = 'e1630000-0000-0000-0000-000000000050'),
    1::bigint,
    'ORG OWNER: the unlisted plugin shows its finalized version to an org admin');

reset role;
select set_config('request.jwt.claims',
    '{"sub":"e1630000-0000-0000-0000-000000000001","role":"authenticated"}', true);
set local role authenticated;

select is(
    (select count(*) from public.plugin_versions
      where plugin_id = 'e1630000-0000-0000-0000-000000000050'),
    2::bigint,
    'AUTHOR: keeps both unlisted rows -- the pending one via the author policy');

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
