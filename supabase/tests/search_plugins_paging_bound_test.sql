-- pgTAP tests for 20260924180000: search_plugins clamps its paging in SQL (BossConsole#1668).
--
-- The edge route caps pageSize, but search_plugins is anon-callable through PostgREST, so the
-- bound has to hold for a direct call too. The fixture has 150 published public plugins, so every
-- cap below is observable rather than satisfied by a small catalogue.

begin;
select plan(27);

-- ---------------------------------------------------------------------------
-- Fixtures: 150 published public plugins in one organisation. Each is a minute newer than the
-- last, in the future, so sorting by 'newest' puts them first in a fixed order: that sort reads
-- whole seconds, and equal keys would leave which rows form a page up to the planner.
-- ---------------------------------------------------------------------------
insert into auth.users (id, email, email_confirmed_at) values
    ('72000000-0000-0000-0000-000000000001', 'pagecap@pgtap.test', now());

select public.create_organisation_internal(
    p_slug=>'pgpagecap', p_name=>'PGTap Page Cap',
    p_owner_id=>'72000000-0000-0000-0000-000000000001',
    p_visibility=>'private', p_join_policy=>'invite_only');

insert into public.plugins (plugin_id, display_name, author_id, author_name, type, api_version, published, org_id,
                            visibility, created_at)
select 'test.pagecap.' || lpad(g::text, 3, '0'), 'Page Cap ' || g, '72000000-0000-0000-0000-000000000001', 'owner',
       'panel', '1.0', true, (select id from public.organisations where slug='pgpagecap'), 'public',
       now() + g * interval '1 minute'
from generate_series(1, 150) g;

-- The guards that keep the cap assertions from passing vacuously.
select cmp_ok(
    (select total_count::int from public.search_plugins(p_page_size => 100)),
    '>=', 150,
    'FIXTURE: at least 150 plugins are visible, so a cap of 100 is observable');
select is(
    (select jsonb_array_length(plugins) from public.search_plugins(p_page_size => 100)),
    100,
    'FIXTURE: a full page of 100 comes back');

-- ---------------------------------------------------------------------------
-- The ACL survives the replace. The event trigger revokes anon on CREATE FUNCTION, which
-- CREATE OR REPLACE also fires, so the migration re-grants search_plugins. Only the anon
-- assertion is mutation coverage for that grant; the others pin what the file restates.
-- ---------------------------------------------------------------------------
select ok(has_function_privilege('anon',
    'public.search_plugins(text,text,text[],numeric,boolean,integer,integer,text)', 'EXECUTE'),
    'anon can still call search_plugins, so the store browses before sign-in');
select ok(has_function_privilege('authenticated',
    'public.search_plugins(text,text,text[],numeric,boolean,integer,integer,text)', 'EXECUTE'),
    'authenticated can still call search_plugins');
select ok(not has_function_privilege('anon',
    'public.search_plugins_for_viewer(uuid,text,text,text[],numeric,boolean,integer,integer,text)', 'EXECUTE'),
    'search_plugins_for_viewer stays closed to anon');
select ok(not has_function_privilege('authenticated',
    'public.search_plugins_for_viewer(uuid,text,text,text[],numeric,boolean,integer,integer,text)', 'EXECUTE'),
    'search_plugins_for_viewer stays closed to authenticated, who could otherwise browse as anyone');

-- ---------------------------------------------------------------------------
-- The clamp, called the way PostgREST calls it for the anon key.
-- ---------------------------------------------------------------------------
set local role anon;

select is((select jsonb_array_length(plugins) from public.search_plugins(p_page_size => null::int)), 20,
    'a NULL page size takes the default of 20, where it used to be LIMIT NULL, which is LIMIT ALL');
select is((select jsonb_array_length(plugins) from public.search_plugins(p_page_size => 1000000000)), 100,
    'an oversized page size is clamped to 100');
select is((select jsonb_array_length(plugins) from public.search_plugins(p_page_size => 101)), 100,
    'one past the cap is clamped to 100');
-- A deliberate change, not only an error path closed: LIMIT 0 is legal SQL, so a page size of 0
-- used to return an empty page with a correct total_count. It now returns one row, matching
-- get_popular_tags (20260922160000); both routes refuse 0, so only a direct call sees it.
select is((select jsonb_array_length(plugins) from public.search_plugins(p_page_size => 0)), 1,
    'a page size of zero is raised to 1');
select is((select jsonb_array_length(plugins) from public.search_plugins(p_page_size => -1)), 1,
    'a negative page size is raised to 1, where it used to raise "LIMIT must not be negative"');
select is((select jsonb_array_length(plugins) from public.search_plugins(p_page_size => 7)), 7,
    'a page size inside 1..100 is unchanged');

select is((select jsonb_array_length(plugins) from public.search_plugins(p_page => null::int)), 20,
    'a NULL page is page 1');
select is((select jsonb_array_length(plugins) from public.search_plugins(p_page => 0)), 20,
    'page 0 is page 1, where it used to raise "OFFSET must not be negative"');
select is((select jsonb_array_length(plugins) from public.search_plugins(p_page => -5)), 20,
    'a negative page is page 1');
select is((select jsonb_array_length(plugins) from public.search_plugins(p_page => 2147483647)), 0,
    'the largest page is an empty page, where (p_page - 1) * p_page_size used to overflow INT');

-- WHICH rows, not only how many. The offset has to be computed from the clamped size: a clamp
-- applied to LIMIT alone (the easy refactor into search_plugins_internal) would make page 2 at a
-- requested size of 101 start at row 102 instead of row 101. Filtered to the fixture and sorted
-- newest first, page 1 is test.pagecap.150 down to .051, so page 2 starts at .050.
select is(
    (select plugins from public.search_plugins(
        p_query => 'test.pagecap', p_page => 2, p_page_size => 101, p_sort_by => 'newest')),
    (select plugins from public.search_plugins(
        p_query => 'test.pagecap', p_page => 2, p_page_size => 100, p_sort_by => 'newest')),
    'page 2 at a requested size of 101 is exactly page 2 at 100');
select is(
    (select jsonb_array_length(plugins) from public.search_plugins(
        p_query => 'test.pagecap', p_page => 2, p_page_size => 101, p_sort_by => 'newest')),
    50,
    'page 2 of the 150-plugin fixture at the clamped size holds the last 50');
select is(
    (select plugins->0->>'pluginId' from public.search_plugins(
        p_query => 'test.pagecap', p_page => 2, p_page_size => 101, p_sort_by => 'newest')),
    'test.pagecap.050',
    'page 2 starts at the 101st plugin, not the 102nd');

-- The clamp bounds the page, never the count a caller pages against.
select is(
    (select total_count from public.search_plugins(p_page_size => null::int)),
    (select total_count from public.search_plugins(p_page_size => 20)),
    'total_count is unaffected by the clamp');

-- The band edges themselves, stated directly: the out-of-band cases above already fail if either
-- bound moves inward, because they assert exact counts, but 100 and 1 are the values a reader
-- looks for.
select is((select jsonb_array_length(plugins) from public.search_plugins(p_page_size => 100)), 100,
    'the cap itself passes through unchanged');
select is((select jsonb_array_length(plugins) from public.search_plugins(p_page_size => 1)), 1,
    'the floor itself passes through unchanged');

reset role;

-- The viewer-scoped wrapper the edge function calls (service_role only) carries its own copy of
-- the clamp, so each half of that copy is pinned too, not only the NULL case.
select is(
    (select jsonb_array_length(plugins) from public.search_plugins_for_viewer(null::uuid, p_page_size => null::int)),
    20,
    'search_plugins_for_viewer clamps a NULL page size the same way');
select is(
    (select jsonb_array_length(plugins) from public.search_plugins_for_viewer(null::uuid, p_page_size => 101)),
    100,
    'search_plugins_for_viewer clamps an oversized page size to 100');
-- The floor on the viewer copy's page size: without it, 101 and NULL above still give 100 and 20,
-- so only a value below 1 shows whether GREATEST(..., 1) is there.
select is(
    (select jsonb_array_length(plugins) from public.search_plugins_for_viewer(null::uuid, p_page_size => 0)),
    1,
    'search_plugins_for_viewer raises a page size of zero to 1');
select is(
    (select jsonb_array_length(plugins) from public.search_plugins_for_viewer(null::uuid, p_page => 0)),
    20,
    'search_plugins_for_viewer treats page 0 as page 1');
select is(
    (select jsonb_array_length(plugins) from public.search_plugins_for_viewer(null::uuid, p_page => 2147483647)),
    0,
    'search_plugins_for_viewer caps the page, so the largest one is empty rather than an overflow');

select * from finish();
rollback;
