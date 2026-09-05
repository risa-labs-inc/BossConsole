package ai.rever.boss.plugin

import ai.rever.boss.components.plugin.DependentPlugin
import ai.rever.boss.components.plugin.DependentRestartCoordinator
import ai.rever.boss.components.plugin.DependentRestartEventBus
import ai.rever.boss.components.plugin.DynamicPluginInfo
import ai.rever.boss.components.plugin.DynamicPluginManager
import ai.rever.boss.components.plugin.MicrokernelRuntime
import ai.rever.boss.components.plugin.findRelocatedPluginJar
import ai.rever.boss.components.plugin.resolveReloadJarPath
import ai.rever.boss.components.registery.PanelComponentStoreRegistry
import ai.rever.boss.components.window_panel.SplitViewStateRegistry
import ai.rever.boss.components.window_panel.components.main_window_panels.BossTabsComponent
import ai.rever.boss.plugin.api.DependentPluginInfo
import ai.rever.boss.plugin.api.InaccessiblePluginInfo
import ai.rever.boss.plugin.api.LoadedPluginInfo
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PluginLoaderDelegate
import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.plugin.api.PluginUnloadIntent
import ai.rever.boss.plugin.api.PluginUnloadResult
import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import ai.rever.boss.plugin.loader.PluginUnloadException
import ai.rever.boss.plugin.repository.remote.PluginStoreConfig
import ai.rever.boss.plugin.sandbox.TabSandboxRegistry
import ai.rever.boss.plugin.sandbox.ui.PluginCrashRegistry
import ai.rever.boss.plugin.sandbox.ui.PluginUiMountRegistry
import ai.rever.boss.utils.ApplicationRestarter
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.ClosedTabHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.SwingUtilities

/**
 * Implementation of PluginLoaderDelegate that wraps DynamicPluginManager.
 *
 * This delegate is registered via context.registerPluginAPI() and allows
 * dynamic plugins (like plugin-manager) to interact with the plugin system.
 */
class PluginLoaderDelegateImpl(
    private val dynamicPluginManager: DynamicPluginManager,
) : PluginLoaderDelegate {
    private val logger = BossLogger.forComponent("PluginLoaderDelegate")

    /**
     * Owner of detached reload jobs (see [reloadPlugin]) — deliberately never
     * cancelled: an in-flight reload must run to completion even if the
     * initiating plugin (or this delegate's window) is torn down mid-reload.
     * No leak either way: idle, the scope holds no threads and becomes
     * unreachable together with this delegate if its window closes; a running
     * job is held by the dispatcher only until it completes.
     */
    private val reloadScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Detaches reloads from their callers and coalesces them per pluginId.
     * Per-delegate (= per-window) on purpose, matching the manager it guards —
     * same-plugin reloads in different windows target different managers.
     */
    private val detachedReloads = KeyedDetachedJobs<String, LoadedPluginInfo?>(reloadScope)

    /**
     * Raises the install-time dependency prompt. See [MissingDependencyReporter] for which
     * paths report and, more importantly, which must not.
     */
    private val dependencyReporter = MissingDependencyReporter.forManager(dynamicPluginManager)

    /**
     * Report the plugin's unmet dependencies (unless this is a reload) and describe it for the
     * caller.
     *
     * A function rather than an inline block only because the two together sit four levels deep
     * inside [loadPlugin]'s try / isSuccess / let.
     */
    private fun describe(
        info: DynamicPluginInfo,
        reportDependencies: Boolean,
    ): LoadedPluginInfo {
        // Only for a plugin that actually registered. `installPlugin` returns success with
        // `state = DISABLED` when registration failed as binary-incompatible or the plugin is
        // hidden for lack of access - reporting there would say "Flow needs the AI Gateway" for
        // something that is not running and will not run, and taking Install would download a
        // second plugin to support a dead one.
        if (reportDependencies && info.state == PluginState.LOADED) {
            dependencyReporter.report(info.manifest)
        }
        return LoadedPluginInfo(
            pluginId = info.manifest.pluginId,
            displayName = info.manifest.displayName,
            version = info.manifest.version,
            description = info.manifest.description,
            author = info.manifest.author,
            url = info.manifest.url,
            // Blank on a FIRST install, because the caller persists the row after this returns.
            // That is the right answer anyway - blank means "ask the store", which is where a
            // plugin being installed for the first time by any store path came from. On an update
            // the row is already there and still carries the original provenance.
            sourceUrl = PluginPersistence.getSourceUrl(info.manifest.pluginId).orEmpty(),
            type =
                info.manifest.type.name
                    .lowercase(),
            apiVersion = info.manifest.apiVersion,
            minBossVersion = info.manifest.minBossVersion,
            isSystemPlugin = info.manifest.systemPlugin,
            canUnload = info.manifest.canUnload,
            loadPriority = info.manifest.loadPriority,
            isEnabled = info.enabled,
            healthy = info.state == PluginState.LOADED,
            jarPath = info.jarPath,
            installedAt = System.currentTimeMillis(),
            requiresAdmin = info.manifest.requiresAdmin,
        )
    }

    override suspend fun loadPlugin(jarPath: String): LoadedPluginInfo? = loadPlugin(jarPath, reportDependencies = true)

    /**
     * @param reportDependencies whether an unmet dependency should prompt.
     *
     * False for reloads. `doReloadPlugin` finishes by calling this, and reload is reached by
     * `resetPluginInstances`, the Toolbox's update flow and the evolver's hot reload - none of
     * which is a user asking to install anything. Without the distinction, an optional
     * dependency someone declined with "Not now" would be re-offered on every reload.
     */
    private suspend fun loadPlugin(
        jarPath: String,
        reportDependencies: Boolean,
    ): LoadedPluginInfo? {
        // Never try to load the microkernel runtime via the plugin-install
        // path — it's a classpath dependency for OOP child JVMs, not a
        // loadable plugin. DefaultPlugin.loadExternalPlugins already skips
        // it on directory scan, but plugin-manager install/update flows
        // reach us directly with a JAR path and would otherwise trip the
        // binary-compatibility validator on core JDK classes.
        //
        // We check by pluginId (from the manifest) rather than filename
        // because the plugin store downloads with a pluginId-based name
        // (`ai_rever_boss_microkernel_runtime_1.0.10.jar`) while the
        // Gradle build output uses the artifact prefix
        // (`boss-microkernel-runtime-1.0.10-all.jar`). Either name needs
        // to be rejected.
        if (isMicrokernelRuntimeJar(jarPath)) {
            // Clean up the JAR that the installer just downloaded so it doesn't
            // linger in the plugins directory and confuse a future scan. The
            // sidecar goes with it: an uninstall→reinstall of the same version
            // reuses the filename, so a surviving `.sig` would meet fresh bytes
            // and hard-fail at load — worse than being unsigned.
            runCatching { File(jarPath).delete() }
            runCatching { PluginSignatureSidecar.delete(jarPath) }
            logger.info(
                LogCategory.SYSTEM,
                "Refusing to install microkernel runtime as a plugin",
                mapOf(
                    "jarPath" to jarPath,
                ),
            )
            throw IllegalArgumentException(
                "The Microkernel Runtime is a system component, not a user-installable plugin. " +
                    "It is managed automatically when Microkernel Mode is enabled - no manual install needed.",
            )
        }
        return try {
            logger.info(LogCategory.SYSTEM, "Loading plugin via delegate", mapOf("jarPath" to jarPath))
            val result = dynamicPluginManager.installPlugin(jarPath, enabled = true)
            if (result.isSuccess) {
                val loadedPlugin = result.getOrNull()
                loadedPlugin?.let { info -> describe(info, reportDependencies) }
            } else {
                logger.error(LogCategory.SYSTEM, "Failed to load plugin", error = result.exceptionOrNull())
                null
            }
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Exception loading plugin", error = e)
            null
        }
    }

    /**
     * The pre-1.0.79 verb, kept because an installed Toolbox still calls it.
     *
     * Routed through [unloadPluginForIntent] with [PluginUnloadIntent.UNSPECIFIED] rather than
     * left on the old path, so the dependent-restart prompt reaches a Toolbox that has not been
     * updated yet. The intent verb buys wording and restart timing, not the feature itself.
     */
    override suspend fun unloadPlugin(pluginId: String): Boolean {
        val result = unloadPluginForIntent(pluginId, PluginUnloadIntent.UNSPECIFIED)
        return result.unloaded
    }

    override fun getDependentPlugins(pluginId: String): List<DependentPluginInfo> =
        dynamicPluginManager.dependentsOf(pluginId).map { dependent ->
            DependentPluginInfo(
                pluginId = dependent.pluginId,
                displayName = dependent.displayName,
                optional = dependent.optional,
                runningInstances = getRunningInstanceCount(dependent.pluginId),
            )
        }

    /**
     * Unload, asking first when other plugins depend on this one.
     *
     * Before this, a loaded hard dependent made the unload fail outright, and because the
     * Toolbox updates a plugin by uninstalling and reinstalling it, that refusal landed on the
     * Update button - the AI Gateway could not be updated while any consumer was running. The
     * refusal was also unexplained: [unloadPlugin] hands back a `Boolean`, so the reasons naming
     * the blocking plugins never left the host.
     *
     * Three things are deliberate here:
     *
     * - **The prompt is raised outside the manager mutex**, like the tab teardown in
     *   `uninstallPlugin`. It waits on a person; holding the lock across that would freeze every
     *   other plugin operation in the window for as long as the dialog is open.
     * - **Confirming forces the unload.** Having asked the question the veto exists to ask,
     *   re-applying it would just refuse what the user agreed to. `canUnload = false` system
     *   plugins are still refused, because that gate answers a different question and no dialog
     *   was shown for it - so the pre-check keeps its own `canUnload` term.
     * - **Nothing is asked when there are no dependents.** The overwhelmingly common unload must
     *   look exactly as it did.
     */
    override suspend fun unloadPluginForIntent(
        pluginId: String,
        intent: PluginUnloadIntent,
    ): PluginUnloadResult {
        val target = dynamicPluginManager.getPluginInfo(pluginId)
        val dependents = dependentsFor(pluginId)
        // Nothing to ask about, or a plugin the manifest gate protects - which is refused
        // whatever the user would have said, so asking would be a dialog with one real answer.
        // Mirrors uninstallPlugin's own ordering, which checks canUnload before the dependents.
        if (dependents.isEmpty() || target?.manifest?.canUnload == false) {
            return unloadPluginUnasked(pluginId, force = false)
        }

        val confirmed =
            DependentRestartEventBus.ask(
                DependentRestartCoordinator.promptFor(
                    targetPluginId = pluginId,
                    targetDisplayName = target?.manifest?.displayName ?: pluginId,
                    intent = intent,
                    dependents = dependents,
                ),
            )
        return if (confirmed) {
            // Logged as well as the decline, so the log says a question was asked and answered.
            // Without this a confirmed prompt was invisible: the only trace was a force=true
            // unload, which is indistinguishable from the paths that always forced.
            logger.info(
                LogCategory.SYSTEM,
                "Dependent-restart prompt confirmed; forcing the unload",
                mapOf(
                    "pluginId" to pluginId,
                    "intent" to intent.name,
                    "dependents" to dependents.joinToString(", ") { it.pluginId },
                ),
            )
            unloadConfirmed(pluginId, intent, dependents.map { it.pluginId })
        } else {
            logger.info(
                LogCategory.SYSTEM,
                "Dependent-restart prompt declined; leaving the plugin loaded",
                mapOf("pluginId" to pluginId, "intent" to intent.name),
            )
            PluginUnloadResult(unloaded = false, cancelledByUser = true)
        }
    }

    /**
     * The unload the user just agreed to, plus the arrangement to restart what depended on it.
     *
     * Forced, because the veto exists to ask exactly the question that was already asked -
     * re-applying it here would refuse what the user agreed to.
     */
    private suspend fun unloadConfirmed(
        pluginId: String,
        intent: PluginUnloadIntent,
        dependentIds: List<String>,
    ): PluginUnloadResult {
        // Recorded BEFORE the unload, not after: for an update, whoever reinstalls the plugin
        // can be quicker than this function's own continuation, and a record written after the
        // load had already happened would never be claimed.
        if (intent != PluginUnloadIntent.REMOVE) {
            DependentRestartCoordinator.record(pluginId, dependentIds)
        }
        val result = unloadPluginUnasked(pluginId, force = true)
        when {
            // The forced unload failed after all, so nothing should be restarted on its behalf.
            !result.unloaded -> DependentRestartCoordinator.record(pluginId, emptyList())

            // A removal has no load to wait for, so restart now. The dependents re-resolve their
            // handle to null, which is the truth about what is installed.
            intent == PluginUnloadIntent.REMOVE -> DependentRestartCoordinator.restartNow(dependentIds)
        }
        return result
    }

    /**
     * Best-effort, outside the manager mutex: failing to enumerate dependents must not fail the
     * unload, because the authoritative veto still runs inside `uninstallPlugin`.
     */
    private fun dependentsFor(pluginId: String): List<DependentPlugin> =
        runCatching { dynamicPluginManager.dependentsOf(pluginId) }
            .onFailure { cause ->
                logger.warn(
                    LogCategory.SYSTEM,
                    "Could not enumerate dependents before unload (continuing unasked)",
                    mapOf(
                        "pluginId" to pluginId,
                        "error" to (cause.message ?: cause::class.simpleName ?: "unknown"),
                    ),
                )
            }.getOrDefault(emptyList())

    private suspend fun unloadPluginUnasked(
        pluginId: String,
        force: Boolean,
    ): PluginUnloadResult =
        try {
            logger.info(
                LogCategory.SYSTEM,
                "Unloading plugin via delegate",
                mapOf("pluginId" to pluginId, "force" to force.toString()),
            )
            val result = dynamicPluginManager.uninstallPlugin(pluginId, force = force)
            // Logged here as well as returned, because [unloadPlugin] still reduces this to a
            // Boolean for callers that have not moved to the intent verb: uninstallPlugin logs
            // "Uninstalling plugin" *before* deciding, so without this the log stopped
            // mid-sequence and the reasons the manager assembled (which name the plugins
            // standing in the way) were dropped here. That is what made the Toolbox's Update
            // button look like it did nothing at all.
            //
            // Refusal and failure are logged apart because uninstallPlugin returns
            // Result.failure for both, and they send a reader to opposite places: a refusal
            // means some other plugin is in the way, while "Plugin not found" or a
            // pluginLoader.unloadPlugin error is a fault worth a stack trace. Reasons come off
            // PluginUnloadException.reasons rather than the joined message, so this keeps
            // working if that message is ever reworded.
            val cause = result.exceptionOrNull()
            val refusalReasons = (cause as? PluginUnloadException)?.reasons.orEmpty()
            if (refusalReasons.isNotEmpty()) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Plugin unload refused",
                    mapOf(
                        "pluginId" to pluginId,
                        "reasons" to refusalReasons.joinToString("; "),
                    ),
                )
            } else if (cause != null) {
                logger.error(
                    LogCategory.SYSTEM,
                    "Plugin unload failed",
                    mapOf("pluginId" to pluginId),
                    cause,
                )
            }
            PluginUnloadResult(unloaded = result.isSuccess, reasons = refusalReasons)
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Exception unloading plugin", error = e)
            PluginUnloadResult(unloaded = false)
        }

    override suspend fun reloadPlugin(pluginId: String): LoadedPluginInfo? {
        // Detached from the caller (mirroring DynamicPluginManager.swapScope):
        // reloads are driven from a PLUGIN's own coroutine (Toolbox update
        // flow, evolver hot-reload), and reloading the caller's OWN plugin
        // force-unloads it — cancelling its scope between unload and load
        // would otherwise leave the plugin unloaded and never reloaded.
        //
        // Coalescing trade-off: a call that joins an in-flight reload gets
        // THAT job's result — a jar replaced on disk mid-reload is not picked
        // up; call again after completion to load it.
        return detachedReloads.run(pluginId, onDetachedFailure = { cause ->
            // Only non-Exception Throwables can land here (doReloadPlugin
            // swallows Exceptions into null) — e.g. a NoClassDefFoundError
            // from a closed classloader, exactly the failure that must not
            // vanish when the caller was torn down by its own reload.
            logger.error(LogCategory.SYSTEM, "Detached plugin reload threw after caller cancellation", error = cause)
        }) {
            doReloadPlugin(pluginId)
        }
    }

    private suspend fun doReloadPlugin(pluginId: String): LoadedPluginInfo? {
        return try {
            logger.info(LogCategory.SYSTEM, "Reloading plugin via delegate", mapOf("pluginId" to pluginId))

            // Resolve the JAR before unloading, and resolve it against the DISK rather than
            // trusting the loaded record. Reloads are most often triggered BY an update that
            // just replaced the jar: the updater writes a version-named file and deletes the
            // old one, so the path this plugin was loaded from is exactly the path that no
            // longer exists. Taking it on trust unloaded the plugin and then failed to load
            // it, leaving it gone until the next restart.
            //
            // Checking existence up front also means a reload that cannot succeed no longer
            // tears the running plugin down first.
            val loadedJarPath = dynamicPluginManager.getPluginInfo(pluginId)?.jarPath
            // Disk IO, and this runs on reloadScope (Dispatchers.Default): reading the record
            // parses installed.json and, on a cold cache, opens every plugin jar's manifest.
            val jarPath =
                withContext(Dispatchers.IO) {
                    val persistedJarPath =
                        PluginPersistence.getInstalledPlugins().firstOrNull { it.pluginId == pluginId }?.jarPath
                    resolveReloadJarPath(
                        loadedJarPath = loadedJarPath,
                        persistedJarPath = persistedJarPath,
                        exists = { File(it).isFile },
                        relocated = {
                            val dir = (loadedJarPath ?: persistedJarPath)?.let { File(it).parentFile }
                            findRelocatedPluginJar(dir, pluginId)?.absolutePath
                        },
                    )
                }

            if (jarPath == null) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Cannot reload - no existing JAR for plugin",
                    mapOf("pluginId" to pluginId, "loadedJarPath" to (loadedJarPath ?: "none")),
                )
                return null
            }
            if (jarPath != loadedJarPath) {
                logger.info(
                    LogCategory.SYSTEM,
                    "Reloading from the installed record - the loaded JAR is gone, most likely replaced by an update",
                    mapOf("pluginId" to pluginId, "loadedJarPath" to (loadedJarPath ?: "none"), "jarPath" to jarPath),
                )
            }

            // Unload
            val unloadResult = dynamicPluginManager.uninstallPlugin(pluginId, force = true)
            if (unloadResult.isFailure) {
                logger.warn(LogCategory.SYSTEM, "Failed to unload for reload", mapOf("pluginId" to pluginId))
                return null
            }

            // Reload
            loadPlugin(jarPath, reportDependencies = false)
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Exception reloading plugin", error = e)
            null
        }
    }

    override fun getLoadedPlugins(): List<LoadedPluginInfo> =
        try {
            // getVisibleInstalledPlugins() already filters by full access
            // (admin status AND required permissions), so no extra filter here.
            dynamicPluginManager.getVisibleInstalledPlugins().map { info ->
                // Use manifest.canUnload instead of calling suspend checkCanUnload
                LoadedPluginInfo(
                    pluginId = info.manifest.pluginId,
                    displayName = info.manifest.displayName,
                    version = info.manifest.version,
                    description = info.manifest.description,
                    author = info.manifest.author,
                    url = info.manifest.url,
                    // The list the Toolbox's installed view is built from, so this is the site that
                    // decides where its Update goes. Read per plugin rather than hoisted: the
                    // persisted config is held in memory behind a lock, so each call is a find on a
                    // list, not a file read.
                    sourceUrl = PluginPersistence.getSourceUrl(info.manifest.pluginId).orEmpty(),
                    type =
                        info.manifest.type.name
                            .lowercase(),
                    apiVersion = info.manifest.apiVersion,
                    minBossVersion = info.manifest.minBossVersion,
                    isSystemPlugin = info.manifest.systemPlugin,
                    canUnload = info.manifest.canUnload,
                    loadPriority = info.manifest.loadPriority,
                    isEnabled = info.enabled,
                    healthy = info.state == PluginState.LOADED,
                    jarPath = info.jarPath,
                    installedAt = 0L,
                    requiresAdmin = info.manifest.requiresAdmin,
                    isIncompatible = PluginCrashRegistry.isIncompatible(info.manifest.pluginId),
                )
            }
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Exception getting loaded plugins", error = e)
            emptyList()
        }

    override fun isPluginLoaded(pluginId: String): Boolean = dynamicPluginManager.getPluginInfo(pluginId) != null

    override fun getPluginsDirectory(): String = PluginStoreSetup.getPluginDir().absolutePath

    override fun getBundledPluginsDirectory(): String = File(System.getProperty("user.dir"), "bundled-plugins").absolutePath

    override fun isCurrentUserAdmin(): Boolean = PluginStoreConfig.isAdmin

    override suspend fun enablePlugin(pluginId: String): Boolean =
        try {
            logger.info(LogCategory.SYSTEM, "Enabling plugin via delegate", mapOf("pluginId" to pluginId))
            val result = dynamicPluginManager.enablePlugin(pluginId)
            if (result.isSuccess) {
                PluginPersistence.setPluginEnabled(pluginId, true)
            }
            result.isSuccess
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Exception enabling plugin", error = e)
            false
        }

    override suspend fun disablePlugin(pluginId: String): Boolean =
        try {
            logger.info(LogCategory.SYSTEM, "Disabling plugin via delegate", mapOf("pluginId" to pluginId))
            val result = dynamicPluginManager.disablePlugin(pluginId)
            if (result.isSuccess) {
                PluginPersistence.setPluginEnabled(pluginId, false)
            }
            result.isSuccess
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Exception disabling plugin", error = e)
            false
        }

    override fun getAccessToken(): String? = PluginStoreConfig.accessToken

    override fun getRunningInstanceCount(pluginId: String): Int = findOpenTabs(pluginId).size

    override suspend fun resetPluginInstances(pluginId: String): Int =
        try {
            // Enumerate every open tab of this plugin across all panels/windows BEFORE
            // touching the loaded plugin, so the typeId → sandbox mapping is still intact.
            val tabs = findOpenTabs(pluginId)
            logger.info(
                LogCategory.SYSTEM,
                "Resetting plugin instances",
                mapOf(
                    "pluginId" to pluginId,
                    "instances" to tabs.size.toString(),
                ),
            )
            // Close the stale tab UIs on the EDT and wait for them to detach, then reload
            // so the freshly-installed version is what's loaded when the user reopens.
            closeTabsOnEdt(pluginId, tabs)
            reloadPlugin(pluginId)
            tabs.size
        } catch (ce: CancellationException) {
            // Self-reset: the detached reload just unloaded the CALLER's own
            // plugin and cancelled its scope. The reload completes on the
            // detached scope — propagate instead of logging a spurious error.
            throw ce
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Exception resetting plugin instances", error = e)
            0
        }

    override fun restartApplication() {
        logger.info(LogCategory.SYSTEM, "Restarting application to apply plugin update")
        ApplicationRestarter.scheduleRestart()
    }

    /**
     * Close EVERY open plugin tab across all windows on the EDT and wait for
     * them to detach. Used by the API-layer hot swap BEFORE any plugin
     * classloader is closed: Compose runs each tab's `onDispose` cleanup
     * synchronously here while its classloader is still open, so lazily-loaded
     * disposal lambdas (e.g. BossTerm's ProperTerminal onDispose) resolve
     * instead of throwing NoClassDefFoundError against a closed loader.
     * Sidebar panels re-register on reload; open tabs do not reopen.
     */
    suspend fun teardownAllPluginTabs(): Int {
        val sandboxed =
            SplitViewStateRegistry.getAllStates().values.flatMap { state ->
                state.getAllPanels().flatMap { panel ->
                    val component = panel.tabsComponent
                    component.tabsState.value.tabs
                        .mapNotNull { tab ->
                            TabSandboxRegistry.getSandbox(tab.typeId)?.let { sandbox ->
                                Triple(component, tab.id, sandbox.pluginId)
                            }
                        }
                }
            }
        val tabs = sandboxed.map { (component, tabId, _) -> component to tabId }
        // The plugins whose loaders the swap is about to close - the only ones worth waiting on.
        // NOT every mounted plugin: sidebar panels stay up across a swap by design, so an "is
        // anything mounted" wait could never come true while one was open.
        val owners = sandboxed.map { (_, _, pluginId) -> pluginId }.toSet()
        if (tabs.isEmpty()) {
            // Nothing to await, and the call that used to be here could never say otherwise:
            // `owners` comes out of the same scan as `tabs`, so no tabs means no owners, and
            // awaitDisposed returns immediately on an empty set. A panel-only plugin is NOT
            // covered here - its panel is not in `owners` in either branch, and it stays mounted
            // across a swap by design. Covering it would mean sourcing owners from
            // PanelSandboxRegistry too, which is a change to what this waits for, not a comment.
            return 0
        }
        logger.info(
            LogCategory.SYSTEM,
            "Tearing down plugin tabs before API-layer swap",
            mapOf(
                "tabs" to tabs.size.toString(),
            ),
        )
        closeTabsOnEdt(pluginId = null, tabs = tabs)
        awaitPluginUiDisposal(owners)
        return tabs.size
    }

    /**
     * Close ONE plugin's open tabs across all windows on the EDT, take its sidebar panels out of
     * the composition, and wait for all of it to dispose.
     *
     * Invoked by the shared uninstall path (DynamicPluginManager) before the classloader closes,
     * so update/reload/remove of a tab-hosting plugin (terminal-tab, editor-tab, fluck-browser)
     * disposes its tab UI cleanly — same NoClassDefFoundError-avoidance as the API swap, one
     * plugin at a time.
     *
     * Panels need the detach because nothing else removes them in time: the registration drops in
     * TrackingPluginContext.unregisterAll(), which runs INSIDE the unload, after this has
     * returned. Without it a plugin with a panel open could only ever time out here, and its
     * panel disposed against a closed loader anyway - the exact fault this path exists to
     * prevent. [PanelComponentStoreRegistry.detachPanels] has the mechanics.
     *
     * [detachPanels] is false for callers that are NOT about to close the classloader. Crash
     * recovery is the one that matters: it closes tabs before disabling, `disable` leaves the
     * loader open, and its panel is on screen showing the crash fallback with the Restart button
     * the user needs. Detaching there would replace that with a blank slot to no purpose.
     */
    suspend fun teardownPluginTabs(
        pluginId: String,
        detachPanels: Boolean = true,
    ): Int {
        val tabs = findOpenTabs(pluginId)
        if (detachPanels) detachPluginPanels(pluginId)
        if (tabs.isEmpty()) {
            // No tabs is not "nothing to wait for": a sidebar panel is a boundary too, and with
            // the detach above it now has a disposal to await rather than a timeout to burn.
            awaitPluginUiDisposal(setOf(pluginId))
            return 0
        }
        logger.info(
            LogCategory.SYSTEM,
            "Tearing down plugin tabs before unload",
            mapOf(
                "pluginId" to pluginId,
                "tabs" to tabs.size.toString(),
            ),
        )
        closeTabsOnEdt(pluginId, tabs)
        awaitPluginUiDisposal(setOf(pluginId))
        return tabs.size
    }

    /**
     * Wait for [pluginIds]' UI to actually leave the composition, before their loaders close.
     *
     * Closing a tab is not disposing it: `removeTabById` mutates the tab model on the EDT and
     * returns, while Compose disposes the subtree on a LATER render frame - and that frame is what
     * runs the plugin's own onDispose lambdas, which cannot resolve once its classloader has gone.
     *
     * Hoisted out of [closeTabsOnEdt] because it is not about tabs: a plugin whose only surface is
     * a sidebar panel has no tabs to close and the same race to lose. Panels reach this wait through
     * [teardownPluginTabs]'s detach rather than through a tab close, which is why the wait is
     * phrased in plugins and not in tabs.
     */
    private suspend fun awaitPluginUiDisposal(pluginIds: Set<String>) {
        if (PluginUiMountRegistry.awaitDisposed(pluginIds, UI_DISPOSAL_TIMEOUT_MS)) return
        logger.warn(
            LogCategory.SYSTEM,
            "Plugin UI still mounted after teardown timeout - unloading anyway",
            mapOf(
                // The awaited plugins only. Listing every mounted plugin named ones that had
                // nothing to do with this unload, which is what made the line unactionable.
                "stillMounted" to PluginUiMountRegistry.stillMounted(pluginIds).toString(),
                "timeoutMs" to UI_DISPOSAL_TIMEOUT_MS.toString(),
            ),
        )
    }

    /**
     * Reset any OPEN sidebar panel slots showing one of [panelIds] across all
     * windows, so they re-create from the plugin's just-registered factories.
     * The panel counterpart of [teardownPluginTabs], on the other side of the
     * swap: tabs must close BEFORE the old classloader does, while panels stay
     * open and swap to the new build once it's registered — a hot reload is
     * then truly live for panels too, and the stale component stops pinning
     * the pre-reload classloader (#856). Invoked by the shared (re)install
     * path via [DynamicPluginManager.pluginPanelsRefresh]. Fire-and-forget on
     * the EDT, using the same `resetComponent` the ⋮ → Reload Panel action ends in.
     */
    fun refreshPluginPanels(
        pluginId: String,
        panelIds: Set<PanelId>,
    ) {
        // Resume FIRST, and before the empty check: this is the far side of every unload, and a
        // plugin that did not come back (removed, or a failed reload) has no panels to reset but
        // must still stop being suspended. Leaving it suspended would blank the slot for the rest
        // of the session.
        SwingUtilities.invokeLater { PanelComponentStoreRegistry.resumePanels(pluginId) }
        if (panelIds.isEmpty()) return
        SwingUtilities.invokeLater {
            val reset = PanelComponentStoreRegistry.resetPanels(panelIds)
            if (reset > 0) {
                logger.info(
                    LogCategory.SYSTEM,
                    "Refreshed open sidebar panels after plugin (re)registration",
                    mapOf(
                        "pluginId" to pluginId,
                        "panels" to reset.toString(),
                    ),
                )
            }
        }
    }

    /**
     * Take [pluginId]'s sidebar panels out of every window's composition, on the EDT.
     *
     * Blocking rather than fire-and-forget, unlike [refreshPluginPanels]: the caller awaits the
     * disposal that this triggers, so returning before the state change had landed would just
     * start the wait against a panel that was still composed.
     */
    @Suppress("TooGenericExceptionCaught") // A detach touches plugin code; a closed loader throws Error, not Exception.
    private fun detachPluginPanels(pluginId: String) {
        var detached = 0
        runOnEdtAndWait {
            detached =
                try {
                    PanelComponentStoreRegistry.detachPanels(pluginId)
                } catch (t: Throwable) {
                    logger.warn(
                        LogCategory.SYSTEM,
                        "Detaching sidebar panels before unload failed (continuing)",
                        mapOf("pluginId" to pluginId),
                        t,
                    )
                    0
                }
        }
        if (detached > 0) {
            logger.info(
                LogCategory.SYSTEM,
                "Detached sidebar panels before unload",
                mapOf(
                    "pluginId" to pluginId,
                    "panels" to detached.toString(),
                ),
            )
        }
    }

    /** Remove the given tabs on the EDT and block until they detach. [pluginId] is null for the all-plugins (API-swap) teardown. */
    private suspend fun closeTabsOnEdt(
        pluginId: String?,
        tabs: List<Pair<BossTabsComponent, String>>,
    ) {
        // Entries the USER closed before this unload are still on the reopen stack, and a
        // plugin's TabInfo is one of its own classes: leaving them pins the classloader, and an
        // update would hand the new factory an instance of the old class. Dropped before the
        // teardown loop so it happens even if a removeTabById throws below.
        pluginId?.let { ClosedTabHistory.dropEntriesFor(it) }

        if (tabs.isEmpty()) return
        runOnEdtAndWait {
            tabs.forEach { (component, tabId) ->
                try {
                    // NOT recorded for reopen: the classloader is about to close, so no factory
                    // is left to rebuild these. Recording them would bury the user's own closures
                    // (the stack holds 25, and a plugin can easily own that many tabs), and an
                    // update - uninstall then reinstall - would register the factory again in
                    // time for Cmd+Shift+T to resurrect tabs nobody closed.
                    component.removeTabById(tabId, recordForReopen = false)
                } catch (e: Throwable) {
                    logger.warn(
                        LogCategory.SYSTEM,
                        "removeTabById threw during tab teardown",
                        mapOf(
                            "pluginId" to (pluginId ?: "all"),
                            "tabId" to tabId,
                        ),
                        e,
                    )
                }
            }
        }
    }

    override fun getInaccessiblePlugins(): List<InaccessiblePluginInfo> =
        try {
            dynamicPluginManager.getInaccessiblePlugins()
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Exception getting inaccessible plugins", error = e)
            emptyList()
        }

    /**
     * All currently-open tabs belonging to [pluginId], across every panel and window,
     * as (owning tabs component, tabId) pairs. A tab belongs to the plugin when its
     * type is sandboxed by that plugin (see [TabSandboxRegistry]). This counts inactive
     * (background) tabs too — not just the visible one in each panel.
     */
    private fun findOpenTabs(pluginId: String): List<Pair<BossTabsComponent, String>> =
        SplitViewStateRegistry.getAllStates().values.flatMap { state ->
            state.getAllPanels().flatMap { panel ->
                val component = panel.tabsComponent
                component.tabsState.value.tabs
                    .filter { tab -> TabSandboxRegistry.getSandbox(tab.typeId)?.pluginId == pluginId }
                    .map { tab -> component to tab.id }
            }
        }

    /** Run [block] on the Swing EDT and block until it completes. Safe to call off-EDT. */
    private fun runOnEdtAndWait(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            SwingUtilities.invokeAndWait(block)
        }
    }

    /**
     * True if the JAR at [jarPath] is the microkernel runtime. Checks the
     * filename against both naming conventions (Gradle `{prefix}-…` and
     * plugin-store `{pluginId-with-underscores}_…`) and falls back to a
     * manifest read for anything else that manages to slip through — this
     * is cheap (just reads one file inside the JAR) and it's the last line
     * of defense before the binary-compatibility validator.
     */
    private fun isMicrokernelRuntimeJar(jarPath: String): Boolean {
        val fileName = File(jarPath).name
        if (fileName.startsWith(MicrokernelRuntime.ARTIFACT_PREFIX)) return true
        val pluginIdPrefix = MicrokernelRuntime.PLUGIN_ID.replace('.', '_')
        if (fileName.startsWith(pluginIdPrefix)) return true
        return try {
            val manifest =
                ai.rever.boss.plugin.loader.PluginManifestReader
                    .readFromJar(jarPath)
            manifest.pluginId == MicrokernelRuntime.PLUGIN_ID
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * How long an unload waits for Compose to finish disposing a plugin's UI.
 *
 * Generous for what it waits on - a render frame is ~16ms - and short enough that a window which
 * is never going to draw does not hold up an unload. See `PluginUiMountRegistry.awaitDisposed`.
 */
private const val UI_DISPOSAL_TIMEOUT_MS = 2_000L
