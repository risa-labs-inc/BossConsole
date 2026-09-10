package ai.rever.boss.plugin

import ai.rever.boss.plugin.loader.PluginBundledTrust
import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File

/**
 * Removes what an uninstalled plugin leaves behind.
 *
 * Three things, and until now nothing in the host did any of them - only the Toolbox plugin deleted
 * jars, and no code anywhere called [PluginPersistence.removeInstalledPlugin], so every uninstall
 * left a row pointing at a file that may since have been deleted:
 *
 * - **The jar**, or the plugin comes straight back on the next directory scan.
 * - **The `.sig` sidecar** (and the `.bundled-trust` marker, BossConsole#102 - see
 *   [PluginBundledTrust]), because either one left beside a filename that is later reused by a
 *   different download hard-fails, or wrongly exempts, that load.
 * - **The `installed.json` row**, or the persisted-load pass keeps trying to load a missing file.
 */
object PluginArtifactCleanup {
    private val logger = BossLogger.forComponent("PluginArtifactCleanup")

    /**
     * Seams with production defaults, so a test can pin the ordering and the blank-path guard
     * without deleting real files or rewriting the developer's own `installed.json` (which
     * `PluginPersistence` resolves from `PluginStoreSetup.getPluginDir()`).
     */
    class Hooks(
        val deleteJar: (String) -> Boolean = { path ->
            runCatching { File(path).takeIf { it.exists() }?.delete() == true }.getOrDefault(false)
        },
        val deleteSidecar: (String) -> Unit = { path ->
            runCatching { PluginSignatureSidecar.delete(path) }
            runCatching { PluginBundledTrust.delete(path) }
        },
        val forgetRow: (String) -> Unit = { id -> PluginPersistence.removeInstalledPlugin(id) },
    )

    fun remove(
        pluginId: String,
        jarPath: String,
        hooks: Hooks = Hooks(),
    ) {
        // A blank path is not a path: deleting on it would be a no-op at best, and the row still has
        // to go or the plugin comes back at the next launch.
        val jarDeleted = if (jarPath.isBlank()) false else hooks.deleteJar(jarPath)
        if (jarPath.isNotBlank()) {
            hooks.deleteSidecar(jarPath)
        }
        runCatching { hooks.forgetRow(pluginId) }
            .onFailure { error ->
                logger.warn(
                    LogCategory.SYSTEM,
                    "Uninstalled a plugin but could not update installed.json",
                    mapOf("pluginId" to pluginId, "error" to (error.message ?: "unknown")),
                )
            }
        logger.info(
            LogCategory.SYSTEM,
            "Removed plugin artifacts",
            mapOf("pluginId" to pluginId, "jarPath" to jarPath, "jarDeleted" to jarDeleted),
        )
    }
}
