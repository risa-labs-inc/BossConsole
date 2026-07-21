-- pgTAP tests for the plugin install-permission gate (migration 20260630000000).
-- Run with: supabase test db
--
-- Covers: the plugins.required_permissions column (exists, type, not-null,
-- default empty) and that get_plugin_with_stats / search_plugins surface it.
--
-- Fixtures are created inside the test transaction and rolled back.

begin;
select plan(6);

-- ---------------------------------------------------------------------------
-- Column shape
-- ---------------------------------------------------------------------------
select has_column('public', 'plugins', 'required_permissions',
    'plugins has a required_permissions column');
select col_type_is('public', 'plugins', 'required_permissions', 'text[]',
    'required_permissions is text[]');
select col_not_null('public', 'plugins', 'required_permissions',
    'required_permissions is NOT NULL');

-- ---------------------------------------------------------------------------
-- Fixture: a published plugin declaring a required permission + one version
-- ---------------------------------------------------------------------------
insert into public.plugins (plugin_id, display_name, author_name, required_permissions, published)
values ('test.install.perm', 'Test Install Perm', 'tester', ARRAY['foo.bar']::text[], true);

insert into public.plugin_versions (plugin_id, version, jar_path, sha256)
select id, '1.0.0', 'plugin-jars/test.jar', repeat('a', 64) from public.plugins where plugin_id = 'test.install.perm';

-- A plugin inserted WITHOUT required_permissions defaults to empty (legacy/open)
insert into public.plugins (plugin_id, display_name, author_name, published)
values ('test.install.open', 'Test Install Open', 'tester', true);

-- ---------------------------------------------------------------------------
-- get_plugin_with_stats surfaces the declared permission and defaults empty
-- ---------------------------------------------------------------------------
select is(
    (select required_permissions from get_plugin_with_stats('test.install.perm')),
    ARRAY['foo.bar']::text[],
    'get_plugin_with_stats returns the declared required_permissions'
);
select is(
    (select required_permissions from get_plugin_with_stats('test.install.open')),
    ARRAY[]::text[],
    'a plugin without declared permissions defaults to empty'
);

-- ---------------------------------------------------------------------------
-- search_plugins includes requiredPermissions in each list item
-- ---------------------------------------------------------------------------
select ok(
    exists (
        select 1
        from search_plugins('test.install.perm') s,
             jsonb_array_elements(s.plugins) elem
        where elem ->> 'pluginId' = 'test.install.perm'
          and elem -> 'requiredPermissions' = '["foo.bar"]'::jsonb
    ),
    'search_plugins surfaces requiredPermissions for each plugin'
);

select * from finish();
rollback;
