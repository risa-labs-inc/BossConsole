package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.sandbox.ui.PluginCrashRegistry
import ai.rever.boss.services.auth.AuthStateManager

/**
 * One window's plugin health, read from current values rather than composition state.
 *
 * [rows] are exactly what the Plugin Health & Recovery center shows for that window.
 */
internal data class PluginHealthSnapshot(
    val rows: List<PluginHealthRow>,
    /** Plugins this window's sandbox watchdog stopped after repeated failures. */
    val sandboxDisabledPluginIds: Set<String>,
)

/**
 * Snapshot [manager]'s plugin health with one read of each value and no composition, so it is safe
 * to call off the UI thread. The Plugin Health & Recovery center uses it to re-check an action
 * before running it; the headless workspace health report uses it for every open window.
 */
internal fun currentPluginHealth(manager: DynamicPluginManager): PluginHealthSnapshot {
    val states = manager.pluginStates.value
    val user = AuthStateManager.currentUser.value
    val rows =
        pluginHealthRows(
            pluginStates = states,
            loadGates = PluginLoadGateRegistry.gates.value,
            crashedPluginIds = states.keys.filterTo(mutableSetOf()) { PluginCrashRegistry.hasCrashed(it) },
            inaccessiblePluginIds =
                healthInaccessiblePluginIds(states, user?.isAdmin == true, user?.permissions?.toSet() ?: emptySet()),
            incompatiblePluginIds = PluginCrashRegistry.incompatiblePlugins.value,
        )
    val sandboxDisabled = states.keys.filterTo(mutableSetOf()) { manager.sandboxManager.isPluginDisabled(it) }
    return PluginHealthSnapshot(healthRowsWithSandboxDisables(rows, sandboxDisabled), sandboxDisabled)
}
