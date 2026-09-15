package ai.rever.boss.components.home

import ai.rever.boss.components.plugin.DynamicPluginInfo
import ai.rever.boss.components.plugin.registries.RegistryAccess
import ai.rever.boss.plugin.api.PluginState

/** Candidate ids only; the catalog still requires a compatible, non-service, user-facing store row. */
internal fun disabledHomePluginIds(
    states: Map<String, DynamicPluginInfo>,
    installedPluginIds: Set<String>,
    access: RegistryAccess,
): Set<String> =
    states
        .filter { (id, info) ->
            id in installedPluginIds &&
                info.state == PluginState.DISABLED &&
                !info.enabled &&
                !info.manifest.systemPlugin &&
                access.permits(info.manifest.requiresAdmin, info.manifest.requiredPermissions.toSet())
        }.keys
