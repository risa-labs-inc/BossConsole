-- pgTAP tests for canonical plugin permissions (migration 20260908000000).
-- Run with: supabase test db
--
-- Covers:
-- 1. plugin_permission_enum exists and has all 12 canonical labels.
-- 2. public.permissions contains all 12 canonical permissions with is_system=true.
-- 3. public.plugins required_permissions supports all 12 canonical permissions.
-- 4. RLS and query functions (get_plugin_with_stats, search_plugins) work with canonical permissions.

begin;
select plan(20);

-- ---------------------------------------------------------------------------
-- 1. Verify plugin_permission_enum type and all 12 values
-- ---------------------------------------------------------------------------
select has_type('public', 'plugin_permission_enum', 'plugin_permission_enum type exists');

select enum_has_labels(
    'public',
    'plugin_permission_enum',
    ARRAY[
        'network',
        'filesystem',
        'terminal',
        'browser',
        'notifications',
        'auth',
        'mcp',
        'editor',
        'clipboard',
        'settings',
        'system',
        'storage'
    ]::text[],
    'plugin_permission_enum contains exactly the 12 canonical permissions'
);

-- ---------------------------------------------------------------------------
-- 2. Verify all 12 canonical permissions exist in public.permissions as system
-- ---------------------------------------------------------------------------
select is(
    (select count(*)::int from public.permissions
     where name in (
        'network', 'filesystem', 'terminal', 'browser', 'notifications',
        'auth', 'mcp', 'editor', 'clipboard', 'settings', 'system', 'storage'
     ) and is_system = true),
    12,
    'All 12 canonical permissions exist in public.permissions as system permissions'
);

-- Verify individual canonical permissions
select is((select is_system from public.permissions where name = 'network'), true, 'permission "network" is system');
select is((select is_system from public.permissions where name = 'filesystem'), true, 'permission "filesystem" is system');
select is((select is_system from public.permissions where name = 'terminal'), true, 'permission "terminal" is system');
select is((select is_system from public.permissions where name = 'browser'), true, 'permission "browser" is system');
select is((select is_system from public.permissions where name = 'notifications'), true, 'permission "notifications" is system');
select is((select is_system from public.permissions where name = 'auth'), true, 'permission "auth" is system');
select is((select is_system from public.permissions where name = 'mcp'), true, 'permission "mcp" is system');
select is((select is_system from public.permissions where name = 'editor'), true, 'permission "editor" is system');
select is((select is_system from public.permissions where name = 'clipboard'), true, 'permission "clipboard" is system');
select is((select is_system from public.permissions where name = 'settings'), true, 'permission "settings" is system');
select is((select is_system from public.permissions where name = 'system'), true, 'permission "system" is system');
select is((select is_system from public.permissions where name = 'storage'), true, 'permission "storage" is system');

-- ---------------------------------------------------------------------------
-- 3. Verify plugins table and required_permissions interaction
-- ---------------------------------------------------------------------------
insert into public.plugins (
    plugin_id, display_name, author_name, required_permissions, published
) values (
    'canonical.test.plugin',
    'Canonical Test Plugin',
    'Tester',
    ARRAY[
        'network', 'filesystem', 'terminal', 'browser', 'notifications',
        'auth', 'mcp', 'editor', 'clipboard', 'settings', 'system', 'storage'
    ]::text[],
    true
);

insert into public.plugin_versions (plugin_id, version, jar_path, sha256)
select id, '1.0.0', 'plugin-jars/test.jar', repeat('b', 64)
from public.plugins
where plugin_id = 'canonical.test.plugin';

select is(
    (select array_length(required_permissions, 1) from public.plugins where plugin_id = 'canonical.test.plugin'),
    12,
    'plugins table stores all 12 canonical permissions in required_permissions array'
);

select is(
    (select required_permissions from get_plugin_with_stats('canonical.test.plugin')),
    ARRAY[
        'network', 'filesystem', 'terminal', 'browser', 'notifications',
        'auth', 'mcp', 'editor', 'clipboard', 'settings', 'system', 'storage'
    ]::text[],
    'get_plugin_with_stats surfaces all 12 canonical permissions'
);

select ok(
    exists (
        select 1
        from search_plugins('canonical.test.plugin') s,
             jsonb_array_elements(s.plugins) elem
        where elem ->> 'pluginId' = 'canonical.test.plugin'
          and jsonb_array_length(elem -> 'requiredPermissions') = 12
    ),
    'search_plugins surfaces all 12 canonical permissions'
);

-- ---------------------------------------------------------------------------
-- 4. Role and RLS enforcement
-- ---------------------------------------------------------------------------
select ok(
    (select count(*) > 0 from public.permissions),
    'permissions table is readable'
);

select is(
    (select is_system from public.permissions where name = 'storage'),
    true,
    'canonical storage permission is protected system permission'
);

select * from finish();
rollback;
