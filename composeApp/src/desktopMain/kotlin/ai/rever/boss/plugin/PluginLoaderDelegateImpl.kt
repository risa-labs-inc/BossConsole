package ai.rever.boss.plugin

import ai.rever.boss.components.plugin.DynamicPluginInfo
import ai.rever.boss.components.plugin.DynamicPluginManager
import ai.rever.boss.components.plugin.MicrokernelRuntime
import ai.rever.boss.components.plugin.findRelocatedPluginJar
import ai.rever.boss.components.plugin.resolveReloadJarPath
import ai.rever.boss.components.registery.PanelComponentStoreRegistry
import ai.rever.boss.components.window_panel.SplitViewStateRegistry
import ai.rever.boss.components.window_panel.components.main_window_panels.BossTabsComponent
import ai.rever.boss.plugin.api.InaccessiblePluginInfo
import ai.rever.boss.plugin.api.LoadedPluginInfo
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PluginLoaderDelegate
import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import ai.rever.boss.plugin.repository.remote.PluginStoreConfig
import ai.rever.boss.plugin.sandbox.TabSandboxRegistry
import ai.rever.boss.plugin.sandbox.ui.PluginCrashRegistry
import ai.rever.boss.utils.ApplicationRestarter
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
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

    override suspend fun unloadPlugin(pluginId: String): Boolean =
        try {
            logger.info(LogCategory.SYSTEM, "Unloading plugin via delegate", mapOf("pluginId" to pluginId))
            val result = dynamicPluginManager.uninstallPlugin(pluginId, force = false)
            result.isSuccess
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Exception unloading plugin", error = e)
            false
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
        val tabs =
            SplitViewStateRegistry.getAllStates().values.flatMap { state ->
                state.getAllPanels().flatMap { panel ->
                    val component = panel.tabsComponent
                    component.tabsState.value.tabs
                        .filter { tab -> TabSandboxRegistry.getSandbox(tab.typeId) != null }
                        .map { tab -> component to tab.id }
                }
            }
        if (tabs.isEmpty()) return 0
        logger.info(
            LogCategory.SYSTEM,
            "Tearing down plugin tabs before API-layer swap",
            mapOf(
                "tabs" to tabs.size.toString(),
            ),
        )
        closeTabsOnEdt(pluginId = null, tabs = tabs)
        return tabs.size
    }

    /**
     * Close ONE plugin's open tabs across all windows on the EDT and wait.
     * Invoked by the shared uninstall path (DynamicPluginManager) before the
     * classloader closes, so update/reload/remove of a tab-hosting plugin
     * (terminal-tab, editor-tab, fluck-browser) disposes its tab UI cleanly —
     * same NoClassDefFoundError-avoidance as the API swap, one plugin at a time.
     */
    suspend fun teardownPluginTabs(pluginId: String): Int {
        val tabs = findOpenTabs(pluginId)
        if (tabs.isEmpty()) return 0
        logger.info(
            LogCategory.SYSTEM,
            "Tearing down plugin tabs before unload",
            mapOf(
                "pluginId" to pluginId,
                "tabs" to tabs.size.toString(),
            ),
        )
        closeTabsOnEdt(pluginId, tabs)
        return tabs.size
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
     * the EDT, matching the manual ⋮ → Restart Panel path.
     */
    fun refreshPluginPanels(
        pluginId: String,
        panelIds: Set<PanelId>,
    ) {
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

    /** Remove the given tabs on the EDT and block until they detach. [pluginId] is null for the all-plugins (API-swap) teardown. */
    private suspend fun closeTabsOnEdt(
        pluginId: String?,
        tabs: List<Pair<BossTabsComponent, String>>,
    ) {
        if (tabs.isEmpty()) return
        runOnEdtAndWait {
            tabs.forEach { (component, tabId) ->
                try {
                    component.removeTabById(tabId)
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
