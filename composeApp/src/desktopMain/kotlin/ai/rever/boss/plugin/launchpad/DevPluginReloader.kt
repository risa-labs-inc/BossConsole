package ai.rever.boss.plugin.launchpad

import ai.rever.boss.components.plugin.DefaultPlugin
import ai.rever.boss.components.plugin.DynamicPluginInfo
import ai.rever.boss.components.plugin.DynamicPluginManager
import ai.rever.boss.components.plugin.HotReloadPolicy
import ai.rever.boss.plugin.api.CanUnloadResult
import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.plugin.loader.PluginUnloadException
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Executes dev plugin hot-reload by dispatching unload and install requests directly to DynamicPluginManager.
 */
@Suppress("ReturnCount", "ThrowsCount", "TooGenericExceptionCaught")
object DevPluginReloader {
    private val logger = BossLogger.forComponent("DevPluginReloader")
    private val reloadLocks = ConcurrentHashMap<String, Mutex>()
    private val sessionPreservedPaths = ConcurrentHashMap<String, MutableSet<String>>()

    internal fun clearSessionPreservedPathsForTest() {
        sessionPreservedPaths.clear()
    }

    /**
     * Reloads the active dev build of [pluginId] into all running host [DynamicPluginManager] instances.
     * Preserves native/browser restart policies, runs a dry-run pre-flight check across all managers
     * before unloading, unloads running instances without force, loads fresh bytes, and prunes old
     * staging directories on the host while strictly retaining any JAR path actively referenced by any manager.
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
    ): Result<Unit> {
        val mutex = reloadLocks.computeIfAbsent(pluginId) { Mutex() }
        return mutex.withLock {
            withContext(Dispatchers.Main) {
                runCatching {
                    executeReload(pluginId, devRoot)
                }
            }
        }
    }

    private suspend fun executeReload(
        pluginId: String,
        devRoot: File,
    ) {
        val activeManagers = DynamicPluginManager.activeManagers()
        if (activeManagers.isEmpty()) {
            error("Host plugin manager is not yet initialized")
        }

        if (HotReloadPolicy.requiresRestartInsteadOfHotReload(pluginId)) {
            val message = "Plugin $pluginId owns native resources that require a full application restart"
            logger.warn(LogCategory.SYSTEM, message, mapOf("pluginId" to pluginId))
            error(message)
        }

        val stagedJar =
            DevPluginArtifacts.findActiveDevJar(pluginId, devRoot, deepValidate = true)
                ?: error("No staged dev JAR found for plugin $pluginId in ${devRoot.absolutePath}")

        recordSessionPreservedPaths(pluginId, listOf(stagedJar.absolutePath))

        logger.info(
            LogCategory.SYSTEM,
            "Initiating dev plugin reload",
            mapOf(
                "pluginId" to pluginId,
                "jarPath" to stagedJar.absolutePath,
                "managersCount" to activeManagers.size,
            ),
        )

        val priorStates =
            activeManagers.map { manager ->
                val info = manager.getPluginInfo(pluginId)
                PriorManagerState(
                    manager = manager,
                    priorJarPath = info?.jarPath,
                    wasLoaded = info?.state == PluginState.LOADED,
                    wasEnabled = info?.enabled ?: true,
                )
            }
        val priorPaths = priorStates.mapNotNull { it.priorJarPath }
        recordSessionPreservedPaths(pluginId, priorPaths)

        preflightCheck(pluginId, activeManagers)
        val modifiedManagers = mutableSetOf<DynamicPluginManager>()
        try {
            unloadManagers(pluginId, activeManagers, modifiedManagers)
            installManagers(pluginId, stagedJar, activeManagers, priorStates, modifiedManagers)
        } catch (e: Exception) {
            val rollbackErrors = rollbackManagers(pluginId, priorStates, modifiedManagers)
            if (rollbackErrors.isNotEmpty()) {
                rollbackErrors.values.forEach { rollbackError ->
                    e.addSuppressed(rollbackError)
                }
            }
            throw e
        }

        pruneStaging(pluginId, devRoot, sessionPreservedPaths[pluginId].orEmpty())

        logger.info(
            LogCategory.SYSTEM,
            "Dev plugin reloaded successfully",
            mapOf("pluginId" to pluginId, "jarPath" to stagedJar.absolutePath),
        )
    }

    internal data class PriorManagerState(
        val manager: DynamicPluginManager,
        val priorJarPath: String?,
        val wasLoaded: Boolean,
        val wasEnabled: Boolean,
    )

    internal suspend fun rollbackManagers(
        pluginId: String,
        priorStates: List<PriorManagerState>,
        modifiedManagers: Set<DynamicPluginManager> = priorStates.map { it.manager }.toSet(),
    ): Map<DynamicPluginManager, Throwable> {
        val targets = priorStates.filter { it.manager in modifiedManagers }
        logger.warn(
            LogCategory.SYSTEM,
            "Rolling back dev plugin reload across modified managers",
            mapOf("pluginId" to pluginId, "managersCount" to targets.size),
        )
        val rollbackErrors = mutableMapOf<DynamicPluginManager, Throwable>()
        for (priorState in targets) {
            val outcome = rollbackSingleManager(pluginId, priorState)
            outcome.exceptionOrNull()?.let { error ->
                rollbackErrors[priorState.manager] = error
            }
        }
        return rollbackErrors
    }

    private suspend fun rollbackSingleManager(
        pluginId: String,
        priorState: PriorManagerState,
    ): Result<Unit> =
        runCatching {
            val hasValidPriorJar =
                priorState.priorJarPath != null &&
                    File(priorState.priorJarPath).exists()
            if (hasValidPriorJar) {
                // Hot-reload case: reinstall the prior working JAR with previous enabled state.
                // Always force-uninstall any existing or partially loaded instance first.
                val uninstallResult =
                    priorState.manager.uninstallPlugin(pluginId, force = true, waitForGC = false)
                if (uninstallResult.isFailure) {
                    val error = uninstallResult.exceptionOrNull()
                    val isNotFound =
                        (error is PluginUnloadException && error.message?.contains("Plugin not found") == true) ||
                            error?.message?.contains("Plugin not found") == true
                    if (!isNotFound) {
                        val message = "Failed to force-uninstall plugin $pluginId during rollback: ${error?.message}"
                        logger.error(LogCategory.SYSTEM, message, mapOf("pluginId" to pluginId), error)
                        throw IllegalStateException(message, error)
                    }
                }
                val installResult =
                    priorState.manager.installPlugin(priorState.priorJarPath, enabled = priorState.wasEnabled)
                val restored = installResult.getOrNull()
                if (!isLoadedOrHidden(restored, priorState.wasEnabled, priorState.manager)) {
                    val error = installResult.exceptionOrNull()
                    val message =
                        "Failed to reinstall prior JAR ${priorState.priorJarPath} during rollback: " +
                            (error?.message ?: "restored plugin is not running (state: ${restored?.state})")
                    logger.error(LogCategory.SYSTEM, message, mapOf("pluginId" to pluginId), error)
                    throw IllegalStateException(message, error)
                }
            } else {
                // First link case: was newly installed during this reload cycle;
                // uninstall it to restore clean initial state.
                val uninstallResult =
                    priorState.manager.uninstallPlugin(pluginId, force = true, waitForGC = false)
                if (uninstallResult.isFailure) {
                    val error = uninstallResult.exceptionOrNull()
                    val isNotFound =
                        (error is PluginUnloadException && error.message?.contains("Plugin not found") == true) ||
                            error?.message?.contains("Plugin not found") == true
                    if (!isNotFound) {
                        val message =
                            "Failed to clean up newly installed plugin $pluginId during rollback: ${error?.message}"
                        logger.error(LogCategory.SYSTEM, message, mapOf("pluginId" to pluginId), error)
                        throw IllegalStateException(message, error)
                    }
                }
            }
        }.onFailure { e ->
            logger.error(
                LogCategory.SYSTEM,
                "Failed to rollback plugin $pluginId on manager",
                mapOf("pluginId" to pluginId),
                e,
            )
        }

    private suspend fun preflightCheck(
        pluginId: String,
        managers: List<DynamicPluginManager>,
    ) {
        if (DefaultPlugin.isAuthoritativeSystemPlugin(pluginId)) {
            val message = "Plugin '$pluginId' is a protected system plugin and cannot be hot-reloaded"
            logger.warn(LogCategory.SYSTEM, message, mapOf("pluginId" to pluginId))
            error(message)
        }

        for (manager in managers) {
            val info = manager.getPluginInfo(pluginId) ?: continue
            if (manager.isSystemPlugin(pluginId) || info.manifest.canUnload == false) {
                val message = "Plugin '$pluginId' is a protected system plugin"
                logger.warn(LogCategory.SYSTEM, message, mapOf("pluginId" to pluginId))
                error(message)
            }
            val canUnloadResult = manager.checkCanUnload(pluginId)
            if (canUnloadResult is CanUnloadResult.NotAllowed) {
                val reasons = canUnloadResult.reasons.joinToString(", ")
                val message = "Cannot unload '$pluginId' due to active dependents: $reasons"
                logger.warn(LogCategory.SYSTEM, message, mapOf("pluginId" to pluginId, "reasons" to reasons))
                error(message)
            }
        }
    }

    private suspend fun unloadManagers(
        pluginId: String,
        managers: List<DynamicPluginManager>,
        modifiedManagers: MutableSet<DynamicPluginManager>,
    ) {
        for (manager in managers) {
            if (manager.getPluginInfo(pluginId) != null) {
                val unloadResult = manager.uninstallPlugin(pluginId, force = false, waitForGC = false)
                if (unloadResult.isFailure) {
                    val error = unloadResult.exceptionOrNull()
                    val message = "Failed to unload plugin $pluginId: ${error?.message ?: "unknown"}"
                    logger.error(LogCategory.SYSTEM, message, mapOf("pluginId" to pluginId), error)
                    throw IllegalStateException(message, error)
                }
                modifiedManagers.add(manager)
            }
        }
    }

    private suspend fun installManagers(
        pluginId: String,
        stagedJar: File,
        managers: List<DynamicPluginManager>,
        priorStates: List<PriorManagerState>,
        modifiedManagers: MutableSet<DynamicPluginManager>,
    ) {
        val priorByManager = priorStates.associateBy { it.manager }
        for (manager in managers) {
            modifiedManagers.add(manager)
            val targetEnabled = priorByManager[manager]?.wasEnabled ?: true
            val installResult = manager.installPlugin(stagedJar.absolutePath, enabled = targetEnabled)
            val installed = installResult.getOrNull()
            if (!isLoadedOrHidden(installed, targetEnabled, manager)) {
                val error = installResult.exceptionOrNull()
                val state = installed?.state?.name ?: "unknown"
                val message =
                    "Staged dev JAR for $pluginId is not running (state: $state): " +
                        "${error?.message ?: "installation did not report a loaded plugin"}"
                logger.error(LogCategory.SYSTEM, message, mapOf("pluginId" to pluginId), error)
                throw IllegalStateException(message, error)
            }
        }
    }

    private fun recordSessionPreservedPaths(
        pluginId: String,
        paths: Collection<String>,
    ) {
        if (paths.isEmpty()) return
        sessionPreservedPaths
            .computeIfAbsent(pluginId) { ConcurrentHashMap.newKeySet() }
            .addAll(paths)
    }

    private fun pruneStaging(
        pluginId: String,
        devRoot: File,
        preservedPaths: Set<String> = emptySet(),
    ) {
        val activeJarPaths =
            DynamicPluginManager
                .activeManagers()
                .mapNotNull { it.getPluginInfo(pluginId)?.jarPath }
                .toSet() + preservedPaths
        val pluginDevDir = DevPluginArtifacts.pluginDevDir(pluginId, devRoot)
        DevPluginArtifacts.pruneStagingHistory(
            pluginDevDir = pluginDevDir,
            activeJarPaths = activeJarPaths,
        )
    }
}

private fun isLoadedOrHidden(
    installed: DynamicPluginInfo?,
    enabled: Boolean,
    manager: DynamicPluginManager,
): Boolean =
    installed != null && (
        installed.state == PluginState.LOADED ||
            (installed.state == PluginState.DISABLED && (!enabled || !manager.canAccess(installed.manifest)))
    )
