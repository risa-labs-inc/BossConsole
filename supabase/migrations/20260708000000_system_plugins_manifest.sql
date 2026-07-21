-- System plugins manifest: the set of always-installed plugins (and their
-- minimum-version floors) that BossConsole previously hardcoded in
-- PluginStoreSetup.systemPlugins. Moving it here means adding a system plugin
-- or bumping a min_version is a row edit, not a host release. The app reads
-- this pre-login (system plugins install before auth), hence anon SELECT.

create table if not exists public.system_plugins (
    plugin_id text primary key,
    github_repo text not null,
    artifact_prefix text not null,
    load_priority integer not null default 100,
    download_only boolean not null default false,
    -- Minimum plugin version this row's host builds require; the app
    -- force-updates older jars before load. Null = no floor.
    min_version text,
    -- Row only applies when the app runs in microkernel (KERNEL) mode.
    kernel_only boolean not null default false,
    enabled boolean not null default true,
    updated_at timestamptz not null default now()
);

alter table public.system_plugins enable row level security;

-- Read-only for everyone (metadata only); writes via service role.
-- drop-first so a partial-failure retry of this migration is re-runnable
-- (Postgres has no CREATE POLICY IF NOT EXISTS; convention per 20260625/29).
drop policy if exists "system_plugins_anon_select" on public.system_plugins;
create policy "system_plugins_anon_select"
    on public.system_plugins for select
    using (true);

-- Realtime push for live additions (model: PluginStoreRealtimeService).
alter publication supabase_realtime add table public.system_plugins;

-- Seed with the current hardcoded set.
insert into public.system_plugins (plugin_id, github_repo, artifact_prefix, load_priority, download_only, min_version, kernel_only) values
    ('ai.rever.boss.plugin.api',                     'risa-labs-inc/boss-plugin-api',            'boss-plugin-api',            0,  false, null,    false),
    ('ai.rever.boss.microkernel.runtime',            'risa-labs-inc/boss-microkernel-runtime',   'boss-microkernel-runtime',   1,  true,  null,    true),
    ('ai.rever.boss.plugin.dynamic.pluginmanager',   'risa-labs-inc/boss-plugin-plugin-manager', 'boss-plugin-plugin-manager', 5,  false, null,    false),
    ('ai.rever.boss.plugin.dynamic.terminaltab',     'risa-labs-inc/boss-plugin-terminal-tab',   'boss-plugin-terminal-tab',   10, false, null,    false),
    ('ai.rever.boss.plugin.dynamic.terminal',        'risa-labs-inc/boss-plugin-terminal',       'boss-plugin-terminal',       10, false, null,    false),
    ('ai.rever.boss.plugin.dynamic.fluckbrowser',    'risa-labs-inc/boss-plugin-fluck-browser',  'boss-plugin-fluck-browser',  10, false, null,    false),
    ('ai.rever.boss.plugin.dynamic.editortab',       'risa-labs-inc/boss-plugin-editor-tab',     'boss-plugin-editor-tab',     10, false, '1.4.0', false)
on conflict (plugin_id) do nothing;

-- Store metadata: plugin versions can declare the minimum boss-plugin-api
-- (runtime API layer) version they need — the client updater gates on it
-- (minApiVersion), like min_boss_version for the host app. The server only
-- reports the value; it does not gate. (Pattern: 20260529 min_ipc_version.)
alter table public.plugin_versions
    add column if not exists min_api_version text not null default '';

comment on column public.plugin_versions.min_api_version is
    'Minimum boss-plugin-api (runtime API layer) version required by this plugin version. Blank = no requirement; publishers set it from the JAR manifest minApiVersion.';

-- Recreate get_plugin_versions to surface the new column. The RETURNS TABLE
-- signature changes, so the function must be dropped first.
drop function if exists get_plugin_versions(text);

create or replace function get_plugin_versions(p_plugin_id text)
returns table (
    id uuid,
    version text,
    changelog text,
    min_boss_version text,
    min_ipc_version text,
    min_api_version text,
    jar_path text,
    jar_size bigint,
    sha256 text,
    dependencies jsonb,
    published_at timestamptz,
    download_count bigint
) as $$
begin
    return query
    select
        pv.id,
        pv.version,
        pv.changelog,
        pv.min_boss_version,
        pv.min_ipc_version,
        pv.min_api_version,
        pv.jar_path,
        pv.jar_size,
        pv.sha256,
        pv.dependencies,
        pv.published_at,
        (select count(*)::bigint from plugin_downloads pd where pd.version_id = pv.id) as download_count
    from plugin_versions pv
    join plugins p on p.id = pv.plugin_id
    where p.plugin_id = p_plugin_id
    and p.published = true
    order by pv.published_at desc;
end;
$$ language plpgsql security definer;

comment on function get_plugin_versions is
    'Get all versions of a plugin with download counts, IPC floor, and API-layer floor.';

-- DROP FUNCTION discarded the original EXECUTE grants; restore them.
grant execute on function get_plugin_versions to authenticated;
grant execute on function get_plugin_versions to anon;
