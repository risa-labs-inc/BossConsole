package ai.rever.boss.plugin.launchpad

import ai.rever.boss.components.plugin.DynamicPluginManager
import ai.rever.boss.components.plugin.HotReloadPolicy
import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Executes dev plugin hot-reload by dispatching unload and install requests directly to DynamicPluginManager.
 */
@Suppress("ReturnCount")
object DevPluginReloader {
    private val logger = BossLogger.forComponent("DevPluginReloader")

    /**
     * Reloads the active dev build of [pluginId] into the running host's [DynamicPluginManager].
     * Preserves native/browser restart policies, unloads running instances, and loads fresh bytes.
     * Executes strictly on [Dispatchers.Main] to prevent UI hierarchy threading deadlocks.
     *
     * The unload deliberately does not wait for classloader garbage collection: the poll loop
     * runs [System.gc] on the caller's thread, and running it here would freeze the UI for up
     * to the GC watcher's timeout. Every host reload path takes the same default; the old
     * loader is reclaimed out of band once nothing references it.
     */
    suspend fun reload(
        pluginId: String,
        devRoot: File = DevPluginArtifacts.stagingRoot(),
    ): Result<Unit> =
        withContext(Dispatchers.Main) {
            runCatching {
                val manager =
                    DynamicPluginManager.anyActiveManager()
                        ?: error("Host plugin manager is not yet initialized")

                if (HotReloadPolicy.requiresRestartInsteadOfHotReload(pluginId)) {
                    val message = "Plugin $pluginId owns native resources that require a full application restart"
                    logger.warn(LogCategory.SYSTEM, message, mapOf("pluginId" to pluginId))
                    error(message)
                }

                val stagedJar =
                    DevPluginArtifacts.findActiveDevJar(pluginId, devRoot)
                        ?: error("No staged dev JAR found for plugin $pluginId in ${devRoot.absolutePath}")

                logger.info(
                    LogCategory.SYSTEM,
                    "Initiating dev plugin reload",
                    mapOf("pluginId" to pluginId, "jarPath" to stagedJar.absolutePath),
                )

                val currentlyLoaded = manager.getPluginInfo(pluginId)
                if (currentlyLoaded != null) {
                    val unloadResult = manager.uninstallPlugin(pluginId, force = true)
                    if (unloadResult.isFailure) {
                        val error = unloadResult.exceptionOrNull()
                        val message = "Failed to unload plugin $pluginId: ${error?.message ?: "unknown"}"
                        logger.error(LogCategory.SYSTEM, message, mapOf("pluginId" to pluginId), error)
                        throw IllegalStateException(message, error)
                    }
                }

                val installResult = manager.installPlugin(stagedJar.absolutePath, enabled = true)
                val installed = installResult.getOrNull()
                // installPlugin returns success with state DISABLED when registration failed as
                // binary-incompatible or the plugin is hidden for lack of access. A "reload
                // confirmed" for a plugin the host then force-unloaded is a lie the CLI would
                // print as success, so success means exactly LOADED, matching the host's own
                // report paths.
                if (installed == null || installed.state != PluginState.LOADED) {
                    val error = installResult.exceptionOrNull()
                    val state = installed?.state?.name ?: "unknown"
                    val message =
                        "Staged dev JAR for $pluginId is not running (state: $state): " +
                            "${error?.message ?: "installation did not report a loaded plugin"}"
                    logger.error(LogCategory.SYSTEM, message, mapOf("pluginId" to pluginId), error)
                    throw IllegalStateException(message, error)
                }
                logger.info(
                    LogCategory.SYSTEM,
                    "Dev plugin reloaded successfully",
                    mapOf("pluginId" to pluginId, "jarPath" to stagedJar.absolutePath),
                )
            }
        }
}
