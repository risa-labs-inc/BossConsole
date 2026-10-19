package ai.rever.boss.plugin

import ai.rever.boss.components.plugin.DependentPlugin
import ai.rever.boss.components.plugin.DependentRestartCoordinator
import ai.rever.boss.components.plugin.DependentRestartDeclinedException
import ai.rever.boss.components.plugin.DependentRestartEventBus
import ai.rever.boss.components.plugin.DynamicPluginManager
import ai.rever.boss.plugin.api.CanUnloadResult
import ai.rever.boss.plugin.api.PluginUnloadIntent
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Removes a plugin: unload it, then delete what it left behind.
 *
 * Detached from the caller for the same reason installs are. The prompt runs on a window's
 * `rememberCoroutineScope`, and a cancellation landing between the unload and the cleanup leaves the
 * jar and its `installed.json` row on disk after the panels have already been torn down, so the
 * plugin comes back at the next launch and the user's "uninstall" reads as a lie. Coalescing per
 * plugin id also stops two windows racing the same removal.
 */
object PluginRemoval {
    private val logger = BossLogger.forComponent("PluginRemoval")

    private val REMOVAL_SCOPE = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val DETACHED_REMOVALS = KeyedDetachedJobs<String, Result<Unit>>(REMOVAL_SCOPE)

    suspend fun remove(
        pluginId: String,
        jarPath: String,
        manager: DynamicPluginManager,
    ): Result<Unit> =
        DETACHED_REMOVALS.run(
            key = pluginId,
            onDetachedFailure = { error ->
                logger.error(LogCategory.SYSTEM, "Detached plugin removal failed", error = error)
            },
        ) {
            // This path does NOT go through PluginLoaderDelegateImpl - it calls the manager
            // directly - so it needs its own copy of the question, or the host's own Uninstall
            // would still hard-refuse while the Toolbox's asked. Only when the manifest allows
            // the unload at all: a `canUnload = false` plugin is refused whatever the answer, so
            // asking would be a dialog with one real outcome. (The menu gates on that too, but
            // this function is reachable from the deep-link handler as well.)
            val targets = DynamicPluginManager.activeManagers().filter { it.hasEntry(pluginId) }
            if (targets.isEmpty()) {
                return@run Result.failure(IllegalStateException("Plugin is not installed: $pluginId"))
            }
            if (targets.any { it.getPluginInfo(pluginId)?.manifest?.canUnload == false }) {
                return@run Result.failure(IllegalStateException("Plugin cannot be unloaded: $pluginId"))
            }

            val additionalJarPaths = targets.mapNotNull { it.getPluginInfo(pluginId)?.jarPath }
            val info = (targets.firstOrNull { it === manager } ?: targets.first()).getPluginInfo(pluginId)
            val dependentsByManager = targets.associateWith { it.dependentsOf(pluginId) }
            val dependents =
                dependentsByManager.values
                    .flatten()
                    .distinctBy { it.pluginId }
                    .sortedBy { it.loadPriority }

            val preflight = preflightUnload(pluginId, dependentsByManager)
            if (preflight.isFailure) {
                return@run preflight
            }

            val confirmed =
                dependents.isEmpty() ||
                    DependentRestartEventBus.ask(
                        DependentRestartCoordinator.promptFor(
                            targetPluginId = pluginId,
                            targetDisplayName = info?.manifest?.displayName ?: pluginId,
                            intent = PluginUnloadIntent.REMOVE,
                            dependents = dependents,
                        ),
                    )
            if (!confirmed) {
                return@run Result.failure(DependentRestartDeclinedException(pluginId))
            }

            // Forced only once the user has agreed to the consequence the veto exists to warn
            // about. With no dependents this is the unchanged non-forced path, so the manifest
            // gate and the unload-aware checks still apply as they always did.
            val unloadFailures = unloadAcrossWindows(pluginId, dependentsByManager)
            val remaining = DynamicPluginManager.activeManagers().filter { it.hasEntry(pluginId) }
            if (remaining.isNotEmpty()) {
                val reasons = unloadFailures.mapNotNull { it.message }.distinct()
                val explanation = if (reasons.isEmpty()) "" else " (${reasons.joinToString("; ")})"
                val failure =
                    IllegalStateException(
                        "Plugin removal failed; ${remaining.size} window(s) still report it installed: " +
                            "$pluginId$explanation",
                    )
                unloadFailures.forEach { failure.addSuppressed(it) }
                return@run Result.failure(failure)
            }
            // Only once the plugin is unloaded: deleting a jar out from under a live classloader is
            // how you get NoClassDefFoundError from code that is still running.
            PluginArtifactCleanup.remove(
                pluginId,
                jarPath,
                additionalJarPaths = additionalJarPaths,
            )
            // Nothing is coming back, so the dependents are restarted now rather than recorded:
            // each is holding a handle into a classloader that has just closed, and a restart
            // makes it re-resolve to null - the truth about what is now installed.
            DependentRestartCoordinator.restartNowAcrossWindows(dependentsByManager)
            Result.success(Unit)
        }

    /** Attempt every captured manager before deciding whether shared artifacts can be removed. */
    private suspend fun unloadAcrossWindows(
        pluginId: String,
        dependentsByManager: Map<DynamicPluginManager, List<DependentPlugin>>,
    ): List<Throwable> {
        val failures = mutableListOf<Throwable>()
        for ((target, dependents) in dependentsByManager) {
            // A window may have unloaded the plugin while the confirmation dialog was open.
            if (!target.hasEntry(pluginId)) continue

            val result = target.uninstallPlugin(pluginId, force = dependents.isNotEmpty())
            if (result.isFailure) {
                val failure = result.exceptionOrNull() ?: IllegalStateException("Unload failed: $pluginId")
                failures += failure
                logger.warn(
                    LogCategory.SYSTEM,
                    "A window failed to unload the plugin during removal",
                    mapOf("pluginId" to pluginId),
                    error = failure,
                )
            }
        }
        return failures
    }

    /** Check every window before changing any of them. */
    private suspend fun preflightUnload(
        pluginId: String,
        dependentsByManager: Map<DynamicPluginManager, List<DependentPlugin>>,
    ): Result<Unit> {
        for ((target, dependents) in dependentsByManager) {
            val verdict =
                if (dependents.isEmpty()) {
                    target.checkCanUnload(pluginId)
                } else {
                    target.checkUnloadAware(pluginId)
                }
            if (verdict is CanUnloadResult.NotAllowed) {
                return Result.failure(
                    IllegalStateException("Cannot remove $pluginId: ${verdict.reasons.joinToString("; ")}"),
                )
            }
        }
        return Result.success(Unit)
    }

    /**
     * Why [pluginId] cannot usefully be removed, or null when it can.
     *
     * The manifest gate (`systemPlugin || !canUnload`) covers the plugins the manager refuses to
     * unload. This covers a different case: a plugin whose jar also sits in the bundled directory is
     * re-copied by `copyBundledPluginsToPluginDir` on the next launch whenever no jar for its id is
     * in the plugins directory. Uninstalling one of those succeeds and then quietly undoes itself, so
     * it is better to say so than to let the plugin reappear.
     */
    fun removalVeto(
        pluginId: String,
        bundledDir: java.io.File,
        readManifestId: (String) -> String? = { path ->
            runCatching {
                ai.rever.boss.plugin.loader.PluginManifestReader
                    .readFromJar(path)
                    .pluginId
            }.getOrNull()
        },
    ): String? {
        val jars =
            runCatching {
                bundledDir.takeIf { it.isDirectory }?.listFiles { f -> f.isFile && f.extension == "jar" }
            }.getOrNull() ?: return null
        val bundled = jars.any { readManifestId(it.absolutePath) == pluginId }
        return if (bundled) "ships with BOSS and would be restored at the next launch" else null
    }
}
