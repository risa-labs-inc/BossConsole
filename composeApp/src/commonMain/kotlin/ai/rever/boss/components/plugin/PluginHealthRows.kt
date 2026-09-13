package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PluginState

private data class HealthRowInputs(
    val pluginId: String,
    val info: DynamicPluginInfo?,
    val gate: PluginLoadGate?,
    val crashed: Boolean,
    val inaccessible: Boolean,
    val incompatible: Boolean,
)

/** Maps existing manager, crash and load-gate facts into a compact operational view. */
internal fun pluginHealthRows(
    pluginStates: Map<String, DynamicPluginInfo>,
    loadGates: Map<String, PluginLoadGate>,
    crashedPluginIds: Set<String>,
    inaccessiblePluginIds: Set<String>,
    incompatiblePluginIds: Set<String>,
): List<PluginHealthRow> {
    val pluginIds =
        (pluginStates.keys + loadGates.keys + crashedPluginIds + inaccessiblePluginIds).toSet()
    return pluginIds
        .map { pluginId ->
            healthRowFor(
                HealthRowInputs(
                    pluginId = pluginId,
                    info = pluginStates[pluginId],
                    gate = loadGates[pluginId],
                    crashed = pluginId in crashedPluginIds,
                    inaccessible = pluginId in inaccessiblePluginIds,
                    incompatible = pluginId in incompatiblePluginIds,
                ),
            )
        }.sortedWith(
            compareBy<PluginHealthRow> { it.status != PluginHealthStatus.NEEDS_ATTENTION }
                .thenBy { it.displayName.lowercase() },
        )
}

private fun healthRowFor(input: HealthRowInputs): PluginHealthRow =
    input.gate?.let { gateRow(input, it) }
        ?: input.info?.let { infoRow(input, it).copy(pluginId = input.pluginId) }
        ?: unknownRow(input)

private fun gateRow(
    input: HealthRowInputs,
    gate: PluginLoadGate,
) = PluginHealthRow(
    pluginId = input.pluginId,
    displayName = input.info?.manifest?.displayName ?: gate.displayName,
    status = PluginHealthStatus.NEEDS_ATTENTION,
    detail =
        when (gate) {
            is PluginLoadGate.NeedsNewerHost -> "This plugin requires a newer BOSS version."
            is PluginLoadGate.NeedsNewerApi -> "This plugin requires a newer plugin API version."
            is PluginLoadGate.SignatureRejected -> "The plugin signature could not be verified."
        },
)

private fun infoRow(
    input: HealthRowInputs,
    info: DynamicPluginInfo,
): PluginHealthRow =
    when {
        input.inaccessible -> {
            unavailableRow(info, "Access is required for this plugin.")
        }

        input.incompatible -> {
            attentionRow(info, "This plugin is incompatible with the current runtime.")
        }

        input.crashed -> {
            attentionRow(info, "The plugin needs recovery after an unexpected failure.").copy(
                action =
                    info
                        .takeIf {
                            it.state == PluginState.LOADED &&
                                !HotReloadPolicy.requiresRestartInsteadOfHotReload(input.pluginId)
                        }?.let { PluginHealthAction.RELOAD },
            )
        }

        info.state == PluginState.DISABLED -> {
            unavailableRow(info, "The plugin is disabled.").copy(
                action = if (!info.enabled) PluginHealthAction.ENABLE else null,
            )
        }

        info.errorMessage != null || info.state == PluginState.ERROR -> {
            attentionRow(info, "The plugin reported a manager error.").copy(
                action = reloadActionFor(input.pluginId, info),
            )
        }

        info.state != PluginState.LOADED -> {
            unavailableRow(info, "The plugin is not currently running.")
        }

        else -> {
            PluginHealthRow(
                info.manifest.pluginId,
                info.manifest.displayName,
                PluginHealthStatus.HEALTHY,
                "Running normally.",
            )
        }
    }

private fun unknownRow(input: HealthRowInputs): PluginHealthRow =
    when {
        input.inaccessible -> {
            PluginHealthRow(
                input.pluginId,
                input.pluginId,
                PluginHealthStatus.UNAVAILABLE,
                "Access is required for this plugin.",
            )
        }

        input.incompatible -> {
            PluginHealthRow(
                input.pluginId,
                input.pluginId,
                PluginHealthStatus.NEEDS_ATTENTION,
                "This plugin is incompatible with the current runtime.",
            )
        }

        input.crashed -> {
            PluginHealthRow(
                input.pluginId,
                input.pluginId,
                PluginHealthStatus.NEEDS_ATTENTION,
                "The plugin needs recovery after an unexpected failure.",
            )
        }

        else -> {
            PluginHealthRow(
                input.pluginId,
                input.pluginId,
                PluginHealthStatus.UNAVAILABLE,
                "Plugin information is unavailable.",
            )
        }
    }

private fun unavailableRow(
    info: DynamicPluginInfo,
    detail: String,
) = PluginHealthRow(
    info.manifest.pluginId,
    info.manifest.displayName,
    PluginHealthStatus.UNAVAILABLE,
    detail,
)

private fun attentionRow(
    info: DynamicPluginInfo,
    detail: String,
) = PluginHealthRow(
    info.manifest.pluginId,
    info.manifest.displayName,
    PluginHealthStatus.NEEDS_ATTENTION,
    detail,
)

private fun reloadActionFor(
    pluginId: String,
    info: DynamicPluginInfo,
): PluginHealthAction? =
    if (
        info.state == PluginState.LOADED &&
        !HotReloadPolicy.requiresRestartInsteadOfHotReload(pluginId)
    ) {
        PluginHealthAction.RELOAD
    } else {
        null
    }

/** Watchdog stops retain registrations: recover through full reload, never bare enable. */
internal fun healthRowsWithSandboxDisables(
    rows: List<PluginHealthRow>,
    disabledPluginIds: Set<String>,
): List<PluginHealthRow> =
    rows.map { row ->
        if (row.pluginId in disabledPluginIds && row.status == PluginHealthStatus.HEALTHY) {
            val requiresRestart = HotReloadPolicy.requiresRestartInsteadOfHotReload(row.pluginId)
            row.copy(
                status = PluginHealthStatus.NEEDS_ATTENTION,
                detail =
                    if (requiresRestart) {
                        "Restart BOSS to recover this plugin."
                    } else {
                        "The plugin stopped and needs recovery."
                    },
                action = if (requiresRestart) null else PluginHealthAction.RELOAD,
            )
        } else {
            row
        }
    }

/** Evaluate access directly, including the legacy admin-only gate omitted by permission banners. */
internal fun healthInaccessiblePluginIds(
    states: Map<String, DynamicPluginInfo>,
    isAdmin: Boolean,
    permissions: Set<String>,
): Set<String> =
    states
        .filterValues { info ->
            !pluginAccessAllowed(isAdmin, permissions, info.manifest.requiresAdmin, info.manifest.requiredPermissions)
        }.keys
