package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PluginState

/** A successful reload can recover an automatic disable without using the Enable operation. */
internal fun recordRestartRecoveryIfRunning(
    info: DynamicPluginInfo,
    sandboxDisabled: Boolean,
    record: (String) -> Unit,
) {
    if (info.enabled && info.state == PluginState.LOADED && !sandboxDisabled) {
        record(info.manifest.pluginId)
    }
}
