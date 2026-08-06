-- Resource modes (Lite / Ultra Lite): which system plugins still load when the
-- host runs in ULTRA_LITE.
--
-- Why the tiers exist: JxBrowser's libtoolkit registers Chromium's PartitionAlloc
-- as the host JVM's default malloc zone, so every native allocation in the app is
-- served by an allocator that aborts the process on failure instead of throwing.
-- BOSS 9.4.0 died that way on 2026-08-05 after 35 h with 36 plugins loaded. Loading
-- fewer plugins on a constrained machine is the defence.
--
-- Default false, so a row nobody has curated is SKIPPED on a constrained machine
-- rather than loaded. That is the safe direction: an uncurated plugin costing
-- memory on a machine that has none is how the app dies, whereas an uncurated
-- plugin missing from a reduced tier is listed in Settings and one click from
-- coming back.
--
-- Note the host also ORs this against its built-in fallback
-- (SystemPluginManifestService.mergeWithFallback), so a row this build ships as
-- eligible stays eligible even if this column says otherwise. That is what keeps
-- installs whose cached manifest predates this column from coming up with no
-- terminal, browser or editor.

alter table public.system_plugins
    add column if not exists lite_eligible boolean not null default false;

comment on column public.system_plugins.lite_eligible is
    'Whether this plugin still loads under the host''s ULTRA_LITE resource mode. '
    'Default false = skipped on constrained machines. The core product set is true.';

-- The shipped core set: without these a reduced tier is not a smaller BOSS, it is
-- a broken one. api and pluginmanager are additionally bootstrap ids the host
-- never gates (no api layer means no plugin can link; no plugin manager means no
-- UI to leave the tier from). The microkernel runtime is download_only, so it is
-- never loaded into the host JVM and costs it no memory either way.
update public.system_plugins
   set lite_eligible = true,
       updated_at = now()
 where plugin_id in (
    'ai.rever.boss.plugin.api',
    'ai.rever.boss.microkernel.runtime',
    'ai.rever.boss.plugin.dynamic.pluginmanager',
    'ai.rever.boss.plugin.dynamic.terminaltab',
    'ai.rever.boss.plugin.dynamic.terminal',
    'ai.rever.boss.plugin.dynamic.fluckbrowser',
    'ai.rever.boss.plugin.dynamic.editortab'
 );
