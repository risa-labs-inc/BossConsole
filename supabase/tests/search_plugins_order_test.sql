-- pgTAP tests for 20260925150000: search_plugins_internal sorts by name when asked, orders ties
-- by plugin_id so pages are stable, and keeps its filters, JSON shape, count and ACL.
--
-- The fixture's plugin_id order deliberately differs from its name order, so a query that ignored
-- the name (the old constant sort key) or ignored the tiebreak cannot pass by coincidence.

begin;
select plan(15);

-- ---------------------------------------------------------------------------
-- Fixtures: five published public plugins, filtered to with p_query => 'test.order'.
--   plugin_id      display_name     name order    plugin_id order
--   test.order.a   Zeta             5             1
--   test.order.b   alpha            1             2
--   test.order.c   Mu               4             3
--   test.order.d   beta             2             4
--   test.order.e   Gamma            3             5
-- None has a download or a rating, so every non-name sort key ties across all five.
-- ---------------------------------------------------------------------------
insert into auth.users (id, email, email_confirmed_at) values
    ('73000000-0000-0000-0000-000000000001', 'order@pgtap.test', now());

select public.create_organisation_internal(
    p_slug=>'pgorder', p_name=>'PGTap Order',
    p_owner_id=>'73000000-0000-0000-0000-000000000001',
    p_visibility=>'private', p_join_policy=>'invite_only');

insert into public.plugins (plugin_id, display_name, author_id, author_name, type, api_version, published, org_id, visibility)
select v.plugin_id, v.display_name, '73000000-0000-0000-0000-000000000001', 'owner', 'panel', '1.0', true,
       (select id from public.organisations where slug='pgorder'), 'public'
from (values
    ('test.order.a', 'Zeta'),
    ('test.order.b', 'alpha'),
    ('test.order.c', 'Mu'),
    ('test.order.d', 'beta'),
    ('test.order.e', 'Gamma')
) as v(plugin_id, display_name);

-- The plugin ids of one page, in the order the function returned them.
create function pg_temp.page_ids(p_sort text, p_page int, p_size int) returns text[]
language sql as $$
    select coalesce(array_agg(e->>'pluginId' order by ord), '{}')
    from public.search_plugins_internal(
        p_viewer_id => null, p_query => 'test.order', p_sort_by => p_sort,
        p_page => p_page, p_page_size => p_size) s,
         jsonb_array_elements(s.plugins) with ordinality as t(e, ord)
$$;

-- ---------------------------------------------------------------------------
-- Sorting by name sorts by name, case-insensitively.
-- ---------------------------------------------------------------------------
select is(pg_temp.page_ids('name', 1, 20),
    array['test.order.b', 'test.order.d', 'test.order.e', 'test.order.c', 'test.order.a'],
    'sortBy name returns alpha, beta, Gamma, Mu, Zeta - it used to be the planner''s order, since its key was the constant 0');
select is(pg_temp.page_ids('name', 2, 2),
    array['test.order.e', 'test.order.c'],
    'a second page of a name sort continues the name order');

-- ---------------------------------------------------------------------------
-- Ties break on plugin_id, so paging through tied rows sees each exactly once.
-- ---------------------------------------------------------------------------
select is(pg_temp.page_ids('downloads', 1, 2) || pg_temp.page_ids('downloads', 2, 2) || pg_temp.page_ids('downloads', 3, 2),
    array['test.order.a', 'test.order.b', 'test.order.c', 'test.order.d', 'test.order.e'],
    'three pages over five tied rows are every row once, in plugin_id order: no repeats, no gaps');
select is(pg_temp.page_ids('rating', 1, 20),
    array['test.order.a', 'test.order.b', 'test.order.c', 'test.order.d', 'test.order.e'],
    'a rating sort with every rating equal falls back to plugin_id too');
select is(pg_temp.page_ids('downloads', 1, 2), (pg_temp.page_ids('downloads', 1, 5))[1:2],
    'the first page is the same rows whatever the page size, which an unordered tie cannot promise');

-- ---------------------------------------------------------------------------
-- What must not change: the count, the JSON shape, the page bounds.
-- ---------------------------------------------------------------------------
select is(
    (select total_count from public.search_plugins_internal(p_viewer_id => null, p_query => 'test.order', p_page_size => 2)),
    5::bigint,
    'total_count still counts every match, not the page');
select is(
    (select array_agg(k order by k) from public.search_plugins_internal(
        p_viewer_id => null, p_query => 'test.order', p_sort_by => 'name', p_page_size => 1) s,
        jsonb_object_keys(s.plugins->0) k),
    array['apiVersion', 'author', 'description', 'displayName', 'downloadCount', 'iconUrl', 'id', 'orgId', 'orgSlug',
          'pluginId', 'rating', 'ratingCount', 'requiredPermissions', 'tags', 'type', 'updatedAt', 'url', 'verified',
          'version', 'visibility'],
    'each entry carries exactly the keys it did before');
select is(pg_temp.page_ids('name', 3, 2), array['test.order.a'],
    'the last page holds what is left');
select is(pg_temp.page_ids('name', 4, 2), '{}'::text[],
    'a page past the end is empty');

-- ---------------------------------------------------------------------------
-- The filter block is written twice, once for total_count and once for the page, so each filter
-- is checked on both: a copy that dropped a filter from one of them would make the count and the
-- page disagree while every other assertion here stayed green.
-- ---------------------------------------------------------------------------
insert into public.plugin_tags (plugin_id, tag)
select p.id, 'pgorder-tagged' from public.plugins p where p.plugin_id in ('test.order.b', 'test.order.d');
update public.plugins set verified = true where plugin_id = 'test.order.c';

select is(
    (select array_agg(e->>'pluginId' order by ord) from public.search_plugins_internal(
        p_viewer_id => null, p_query => 'test.order', p_tags => array['pgorder-tagged'], p_sort_by => 'name') s,
        jsonb_array_elements(s.plugins) with ordinality as t(e, ord)),
    array['test.order.b', 'test.order.d'],
    'a tag filter returns only the tagged plugins, in name order');
select is(
    (select total_count from public.search_plugins_internal(
        p_viewer_id => null, p_query => 'test.order', p_tags => array['pgorder-tagged'])),
    2::bigint,
    'and total_count counts the same two');
select is(
    (select array_agg(e->>'pluginId' order by ord) from public.search_plugins_internal(
        p_viewer_id => null, p_query => 'test.order', p_verified_only => true) s,
        jsonb_array_elements(s.plugins) with ordinality as t(e, ord)),
    array['test.order.c'],
    'verified-only returns only the verified plugin');
select is(
    (select total_count from public.search_plugins_internal(
        p_viewer_id => null, p_query => 'test.order', p_verified_only => true)),
    1::bigint,
    'and total_count counts only it');

-- ---------------------------------------------------------------------------
-- The ACL is restated, not widened: only service_role calls the internal function.
-- ---------------------------------------------------------------------------
select ok(
    not has_function_privilege('anon', 'public.search_plugins_internal(uuid,text,text,text[],numeric,boolean,integer,integer,text)', 'EXECUTE')
    and not has_function_privilege('authenticated', 'public.search_plugins_internal(uuid,text,text,text[],numeric,boolean,integer,integer,text)', 'EXECUTE'),
    'search_plugins_internal stays closed to anon and authenticated');

-- ---------------------------------------------------------------------------
-- And through the anon-callable wrapper the store's list route uses.
-- ---------------------------------------------------------------------------
set local role anon;
select is(
    (select plugins->0->>'pluginId' from public.search_plugins(p_query => 'test.order', p_sort_by => 'name')),
    'test.order.b',
    'search_plugins, called as anon, puts alpha first when sorting by name');
reset role;

select * from finish();
rollback;
