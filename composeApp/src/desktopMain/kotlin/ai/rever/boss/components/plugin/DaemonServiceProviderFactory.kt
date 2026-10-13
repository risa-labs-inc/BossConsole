package ai.rever.boss.components.plugin

import ai.rever.boss.daemon.BossDaemonClient
import ai.rever.boss.plugin.api.DaemonServiceProvider
import java.io.File

internal actual fun daemonServiceProviderFor(
    pluginId: String,
    jarPath: String,
): DaemonServiceProvider = BossDaemonClient(pluginId, File(jarPath))

internal actual suspend fun stopPluginDaemonServices(pluginId: String) {
    BossDaemonClient.stopPlugin(pluginId)
}
