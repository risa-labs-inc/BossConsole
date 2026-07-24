package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.PluginStoreSetup
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File

/**
 * Desktop implementation of the plugin update bridge. Delegates to the PluginUpdateManager created
 * in [PluginStoreSetup] (which is gated by host IPC compatibility, so `availableUpdates` only ever
 * contains versions the running BOSS can load) and to [DynamicPluginManager] for unload/load.
 */
actual object PluginUpdateBridge {
    private val logger = BossLogger.forComponent("PluginUpdateBridge")

    actual suspend fun refreshAll(installed: List<InstalledPluginRef>) {
        if (installed.isEmpty()) return
        val mgr = PluginStoreSetup.updateManager ?: return
        val byId = installed.associateBy { it.pluginId }
        // Use the check's own result rather than reading mgr.availableUpdates afterwards:
        // that shared flow is replaced wholesale by every checkForUpdates() call, so a
        // concurrent single-plugin checkOne() could shrink it to one entry between our
        // check and the read.
        val result =
            try {
                mgr.checkForUpdates(installed.associate { it.pluginId to it.version })
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Plugin update check failed: ${e.message}")
                return
            }
        PluginUpdateRegistry.putAll(
            result.availableUpdates.map { u ->
                AvailablePluginUpdate(
                    pluginId = u.pluginId,
                    displayName = byId[u.pluginId]?.displayName ?: u.displayName,
                    currentVersion = u.currentVersion,
                    newVersion = u.newVersion,
                )
            },
        )
    }

    actual suspend fun checkOne(ref: InstalledPluginRef): UpdateCheckOutcome {
        val mgr =
            PluginStoreSetup.updateManager
                ?: return UpdateCheckOutcome.Error("Plugin store not initialized")
        return try {
            // Read this check's own result (not the shared mgr flows, which a concurrent
            // refreshAll/checkOne may have overwritten since).
            val result = mgr.checkForUpdates(mapOf(ref.pluginId to ref.version))
            val failure = result.failedChecks[ref.pluginId]
            val available = result.availableUpdates.firstOrNull { it.pluginId == ref.pluginId }
            when {
                failure != null -> {
                    // Don't clear a previously-known update on a transient check failure.
                    UpdateCheckOutcome.Error(failure)
                }

                available != null -> {
                    PluginUpdateRegistry.put(
                        AvailablePluginUpdate(ref.pluginId, ref.displayName, available.currentVersion, available.newVersion),
                    )
                    UpdateCheckOutcome.Available(ref.displayName, available.currentVersion, available.newVersion)
                }

                else -> {
                    PluginUpdateRegistry.clear(ref.pluginId)
                    val incompatible = result.incompatibleNotices.firstOrNull { it.pluginId == ref.pluginId }
                    if (incompatible != null) {
                        UpdateCheckOutcome.Incompatible(incompatible.advertisedLatest)
                    } else {
                        UpdateCheckOutcome.UpToDate
                    }
                }
            }
        } catch (e: Exception) {
            UpdateCheckOutcome.Error(e.message ?: "Unknown error")
        }
    }

    actual suspend fun performUpdate(
        pluginId: String,
        manager: DynamicPluginManager,
    ): Result<String> {
        val mgr =
            PluginStoreSetup.updateManager
                ?: return Result.failure(Exception("Plugin store not initialized"))
        val update =
            mgr.availableUpdates.value.firstOrNull { it.pluginId == pluginId }
                ?: return Result.failure(Exception("No update available"))

        // newVersion comes from the (remote) store manifest, and SemanticVersion.parse does NOT
        // reject path separators in prerelease/build metadata — so sanitize the filename and verify
        // the resolved path stays inside the plugin directory (no traversal out of it).
        val pluginDir = PluginStoreSetup.getPluginDir()
        val safeName = "$pluginId-${update.newVersion}".replace(Regex("[^A-Za-z0-9._-]"), "_")
        val targetFile = File(pluginDir, "$safeName.jar")
        if (!targetFile.canonicalPath.startsWith(pluginDir.canonicalPath + File.separator)) {
            return Result.failure(Exception("Refusing to download update outside the plugin directory"))
        }
        val targetPath = targetFile.absolutePath
        val result =
            mgr.updatePlugin(
                pluginId = pluginId,
                downloadPath = targetPath,
                unloadPlugin = { id -> manager.uninstallPlugin(id, force = true).map { } },
                loadPlugin = { path -> manager.installPlugin(path).map { } },
            )
        return if (result.isSuccess) {
            PluginUpdateRegistry.clear(pluginId)
            // Remove the previous version's JAR (and any other stale duplicates).
            // Matching is by manifest pluginId, so it handles every filename
            // convention; the just-installed JAR is the highest version and is kept.
            runCatching {
                ai.rever.boss.plugin.PluginJarReconciler
                    .reconcilePluginDir(pluginDir)
            }.onFailure { e ->
                logger.warn(LogCategory.SYSTEM, "Post-update plugin dir reconcile failed: ${e.message}")
            }
            Result.success(update.newVersion)
        } else {
            Result.failure(result.exceptionOrNull() ?: Exception("Update failed"))
        }
    }
}
