-- Unfinalized plugin version rows must never surface as a plugin's latest
-- version (#912). The publish flow inserts the version row BEFORE the JAR
-- exists (sha256='pending', jar_size=0) and only the finalize route replaces
-- those once the uploaded bytes have been re-hashed and manifest-checked.
-- Every store surface that resolves "latest" -- the plugins_with_latest_version
-- view, search_plugins, get_plugin_with_stats and the public version list --
-- must therefore gate on the finalized state and fall back to the newest
-- FINALIZED row; a plugin with only an unfinalized row has no latest version
-- at all. The gate is fail-closed: either half of the finalization alone
-- (sentinel sha with nonzero size, or real sha with zero/NULL size) is still
-- refused.
--
-- Run against a disposable migrated database with: supabase test db
-- All synthetic fixtures are rolled back.

begin;
select plan(23);

-- ---------------------------------------------------------------------------
-- Fixtures: test.latest.gate has a finalized 1.0.0 (older) and a pending
-- 2.0.0 (newer, higher semver -- the adversarial case).
-- ---------------------------------------------------------------------------

insert into public.plugins (id, plugin_id, display_name, author_name, published, visibility)
values ('e9120000-0000-0000-0000-000000000001', 'test.latest.gate', 'Latest Gate', 'tester', true, 'public');

insert into public.plugin_versions (id, plugin_id, version, jar_path, sha256, jar_size, published_at)
values ('e9120000-0000-0000-0000-000000000002', 'e9120000-0000-0000-0000-000000000001', '1.0.0',
        'test/latest-gate-1.0.0.jar', repeat('a', 64), 1024, now() - interval '2 days');

insert into public.plugin_versions (id, plugin_id, version, jar_path, sha256, jar_size, published_at)
values ('e9120000-0000-0000-0000-000000000003', 'e9120000-0000-0000-0000-000000000001', '2.0.0',
        'test/latest-gate-2.0.0.jar', 'pending', 0, now());

-- A newer, higher-semver unfinalized row never resolves as latest: every
-- surface falls back to the newest finalized row instead.
select is((select latest_version from public.plugins_with_latest_version where plugin_id = 'test.latest.gate'),
          '1.0.0'::text,
          'view: pending row is newer and higher-semver, latest falls back to finalized 1.0.0');

select is((select latest_version from public.get_plugin_with_stats('test.latest.gate')),
          '1.0.0'::text,
          'get_plugin_with_stats: latest falls back to finalized 1.0.0 while 2.0.0 is pending');

select is((select latest_version_id from public.get_plugin_with_stats('test.latest.gate')),
          'e9120000-0000-0000-0000-000000000002'::uuid,
          'get_plugin_with_stats: latest_version_id points at the finalized row');

select is((select (elem ->> 'version')
             from public.search_plugins('test.latest.gate') s,
                  jsonb_array_elements(s.plugins) elem
            where elem ->> 'pluginId' = 'test.latest.gate'),
          '1.0.0',
          'search_plugins: browse row version falls back to finalized 1.0.0');

-- The unfinalized row is absent from every consumer surface, including the
-- public version list: it has no verified artifact, so it is not a version.
select is((select count(*) from public.get_plugin_versions('test.latest.gate') where version = '2.0.0'),
          0::bigint,
          'version list: pending 2.0.0 is absent');

select is((select count(*) from public.get_plugin_versions('test.latest.gate')),
          1::bigint,
          'version list: finalized 1.0.0 is still listed');

-- ---------------------------------------------------------------------------
-- Poison shapes: either half of finalization alone must stay refused.
-- ---------------------------------------------------------------------------

update public.plugin_versions set jar_size = 2048
 where id = 'e9120000-0000-0000-0000-000000000003';

select is((select latest_version from public.plugins_with_latest_version where plugin_id = 'test.latest.gate'),
          '1.0.0'::text,
          'poison A: sentinel sha with nonzero size is still not finalized');

update public.plugin_versions set sha256 = repeat('b', 64), jar_size = 0
 where id = 'e9120000-0000-0000-0000-000000000003';

select is((select latest_version from public.plugins_with_latest_version where plugin_id = 'test.latest.gate'),
          '1.0.0'::text,
          'poison B: real sha with zero size is still not finalized');

update public.plugin_versions set jar_size = NULL
 where id = 'e9120000-0000-0000-0000-000000000003';

select is((select latest_version from public.plugins_with_latest_version where plugin_id = 'test.latest.gate'),
          '1.0.0'::text,
          'poison C: NULL jar_size fails closed, row is still not finalized');

-- ---------------------------------------------------------------------------
-- Finalize 2.0.0 completely: it becomes latest on every surface.
-- ---------------------------------------------------------------------------

update public.plugin_versions set jar_size = 2048
 where id = 'e9120000-0000-0000-0000-000000000003';

select is((select latest_version from public.plugins_with_latest_version where plugin_id = 'test.latest.gate'),
          '2.0.0'::text,
          'view: a finalized row takes over as latest');

select is((select latest_version from public.get_plugin_with_stats('test.latest.gate')),
          '2.0.0'::text,
          'get_plugin_with_stats: finalized 2.0.0 is latest');

select is((select latest_version_id from public.get_plugin_with_stats('test.latest.gate')),
          'e9120000-0000-0000-0000-000000000003'::uuid,
          'get_plugin_with_stats: latest_version_id follows the finalized row');

select is((select (elem ->> 'version')
             from public.search_plugins('test.latest.gate') s,
                  jsonb_array_elements(s.plugins) elem
            where elem ->> 'pluginId' = 'test.latest.gate'),
          '2.0.0',
          'search_plugins: finalized 2.0.0 is the browse row version');

select is((select count(*) from public.get_plugin_versions('test.latest.gate')),
          2::bigint,
          'version list: both finalized rows are listed once finalized');

-- ---------------------------------------------------------------------------
-- A plugin with ONLY an unfinalized row has no latest version at all.
-- ---------------------------------------------------------------------------

insert into public.plugins (id, plugin_id, display_name, author_name, published, visibility)
values ('e9120000-0000-0000-0000-000000000005', 'test.latest.only', 'Latest Only', 'tester', true, 'public');

insert into public.plugin_versions (id, plugin_id, version, jar_path, sha256, jar_size)
values ('e9120000-0000-0000-0000-000000000006', 'e9120000-0000-0000-0000-000000000005', '1.0.0',
        'test/latest-only-1.0.0.jar', 'pending', 0);

select ok((select latest_version from public.plugins_with_latest_version where plugin_id = 'test.latest.only') IS NULL,
          'view: a plugin with only a pending row has no latest version');

select ok((select latest_version from public.get_plugin_with_stats('test.latest.only')) IS NULL,
          'get_plugin_with_stats: only-pending plugin has no latest_version');

select ok((select latest_version_id from public.get_plugin_with_stats('test.latest.only')) IS NULL,
          'get_plugin_with_stats: only-pending plugin has no latest_version_id');

select is((select count(*) from public.get_plugin_versions('test.latest.only')),
          0::bigint,
          'version list: only-pending plugin exposes no versions');

-- The plugin itself is still browsable; only its nonexistent publish is hidden.
select is((select count(*) from public.get_plugin_with_stats('test.latest.only')),
          1::bigint,
          'get_plugin_with_stats: the plugin row itself is still served');

-- ---------------------------------------------------------------------------
-- Healthy data is unchanged: one plugin, one finalized version.
-- ---------------------------------------------------------------------------

insert into public.plugins (id, plugin_id, display_name, author_name, published, visibility)
values ('e9120000-0000-0000-0000-000000000007', 'test.latest.healthy', 'Latest Healthy', 'tester', true, 'public');

insert into public.plugin_versions (id, plugin_id, version, jar_path, sha256, jar_size)
values ('e9120000-0000-0000-0000-000000000008', 'e9120000-0000-0000-0000-000000000007', '1.5.0',
        'test/latest-healthy-1.5.0.jar', repeat('d', 64), 2048);

select is((select latest_version from public.plugins_with_latest_version where plugin_id = 'test.latest.healthy'),
          '1.5.0'::text,
          'view: healthy finalized plugin resolves its version unchanged');

select is((select latest_version from public.get_plugin_with_stats('test.latest.healthy')),
          '1.5.0'::text,
          'get_plugin_with_stats: healthy plugin resolves its version unchanged');

select is((select (elem ->> 'version')
             from public.search_plugins('test.latest.healthy') s,
                  jsonb_array_elements(s.plugins) elem
            where elem ->> 'pluginId' = 'test.latest.healthy'),
          '1.5.0',
          'search_plugins: healthy plugin resolves its version unchanged');

select is((select count(*) from public.get_plugin_versions('test.latest.healthy')),
          1::bigint,
          'version list: healthy plugin lists its version unchanged');

select * from finish();
rollback;
