package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.DaemonServiceProvider

internal expect fun daemonServiceProviderFor(
    pluginId: String,
    jarPath: String,
): DaemonServiceProvider

internal expect suspend fun stopPluginDaemonServices(pluginId: String)
