package ai.rever.boss.components.plugin

import ai.rever.boss.components.plugin.providers.publishSystemEvent
import ai.rever.boss.plugin.api.CanUnloadResult
import ai.rever.boss.plugin.api.DynamicPluginListener
import ai.rever.boss.plugin.api.LoadedPlugin
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginLifecycleEvent
import ai.rever.boss.plugin.api.PluginLifecycleState
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.api.PluginSandboxRef
import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.plugin.api.PluginUnloadAware
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.loader.DynamicPluginLoaderImpl
import ai.rever.boss.plugin.loader.PluginBinaryIncompatibilityException
import ai.rever.boss.plugin.loader.PluginUnloadException
import ai.rever.boss.plugin.sandbox.PluginErrorClassifier
import ai.rever.boss.plugin.sandbox.PluginSandboxManager
import ai.rever.boss.plugin.sandbox.SandboxConfig
import ai.rever.boss.plugin.sandbox.ui.PluginCrashRegistry
import ai.rever.boss.services.auth.AuthStateManager
import ai.rever.boss.utils.AppVersion
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Entry for a persisted plugin to be loaded on startup.
 */
data class PersistedPluginEntry(
    val pluginId: String,
    val jarPath: String,
    val enabled: Boolean,
)

/**
 * Information about a dynamically managed plugin.
 */
data class DynamicPluginInfo(
    val manifest: PluginManifest,
    val jarPath: String,
    val state: PluginState,
    val loadedAt: Long,
    val enabled: Boolean,
    val errorMessage: String? = null,
)

/**
 * Callback interface for spawning out-of-process plugin children.
 *
 * Implementors launch the plugin's child JVM or native-image binary,
 * establish the gRPC transport, and return a stable lifecycle handle.
 * Decoupled from any specific process-management library.
 */
interface OutOfProcessPluginSpawner {
    /**
     * Spawn a child process for the given plugin.
     *
     * @param manifest Plugin manifest (contains `nativeImagePath`, capabilities, etc.)
     * @param jarPath  Path to the plugin JAR (used when launching a JVM subprocess)
     * @return Success if the process started, or failure with the cause.
     */
    suspend fun spawn(
        manifest: PluginManifest,
        jarPath: String,
    ): Result<Unit>

    /**
     * Terminate the child process for the given plugin.
     */
    suspend fun terminate(pluginId: String): Result<Unit>
}

/**
 * Manager for dynamic plugin loading and unloading at runtime.
 *
 * This coordinates the full plugin lifecycle:
 * - Loading plugins from JAR files
 * - Creating sandboxed contexts with registration tracking
 * - Notifying listeners of lifecycle events
 * - Validating unload feasibility
 * - Cleaning up registrations on unload
 *
 * When [outOfProcessSpawner] is provided, plugins whose manifest declares
 * `isolationMode = "out-of-process"` are delegated to the spawner instead
 * of being loaded into the kernel JVM classloader.
 *
 * Follows IntelliJ IDEA patterns for dynamic plugin management.
 */
class DynamicPluginManager(
    private val panelRegistry: PanelRegistry,
    private val tabRegistry: TabRegistry,
    private val sandboxManager: PluginSandboxManager,
    private val createSandboxedContext: (pluginId: String, config: SandboxConfig) -> PluginContext,
    private val outOfProcessSpawner: OutOfProcessPluginSpawner? = null,
) {
    private val logger = BossLogger.forComponent("DynamicPluginManager")

    /**
     * Scope for manager operations.
     */
    private val managerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Mutex for plugin operations to prevent race conditions.
     */
    private val mutex = Mutex()

    /**
     * The underlying plugin loader.
     */
    private val pluginLoader =
        DynamicPluginLoaderImpl().apply {
            currentBossVersion = AppVersion.CURRENT.toString()
        }

    /**
     * Resolve the runtime API layer (newest installed boss-plugin-api jar in
     * [pluginDir]) into the shared ApiClassLoader that parents every plugin
     * classloader, and publish its version as the `boss.api.version` system
     * property (read by plugins via BossApiRuntime). Must run after the
     * plugin dir is reconciled and before any plugin loads; idempotent.
     */
    fun initializeApiLayer(pluginDir: java.io.File) {
        val apiLoader = pluginLoader.initializeApiLayer(pluginDir)
        System.setProperty("boss.api.version", apiLoader.apiVersion ?: "")
        // Published for the out-of-process spawner (child-JVM classpaths).
        System.setProperty("boss.api.jar", apiLoader.apiJarPath ?: "")
        // fromPluginDir already logs the resolved jar + version at INFO;
        // keep this at debug to avoid triple-logging one init.
        logger.debug(
            LogCategory.SYSTEM,
            "Runtime API layer initialized",
            mapOf(
                "apiVersion" to (apiLoader.apiVersion ?: "none"),
            ),
        )
    }

    /**
     * Tracks registrations by plugin for cleanup.
     */
    private val registrationTracker = PluginRegistrationTracker()

    /**
     * Tracking contexts by plugin ID.
     */
    private val trackingContexts = ConcurrentHashMap<String, TrackingPluginContext>()

    /**
     * Listeners for plugin lifecycle events.
     */
    private val listeners = CopyOnWriteArrayList<WeakReference<DynamicPluginListener>>()

    /**
     * Components that need to be notified before plugin unload.
     */
    private val unloadAwareComponents = CopyOnWriteArrayList<WeakReference<PluginUnloadAware>>()

    /**
     * Current state of all dynamic plugins.
     */
    private val _pluginStates = MutableStateFlow<Map<String, DynamicPluginInfo>>(emptyMap())
    val pluginStates: StateFlow<Map<String, DynamicPluginInfo>> = _pluginStates.asStateFlow()

    /**
     * Current admin status of the user.
     */
    private val _isAdmin = MutableStateFlow(false)

    /**
     * Current effective permissions of the user (own + inherited via the role
     * hierarchy), from the JWT `user_permissions` claim. Drives permission-based
     * plugin visibility.
     */
    private val _userPermissions = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Plugins that are loaded but NOT registered because the current user does
     * not satisfy their access requirements (admin status and/or required
     * permissions). They are re-registered if the user's access later qualifies.
     */
    private val hiddenPlugins = ConcurrentHashMap<String, DynamicPluginInfo>()

    companion object {
        private val companionLogger = BossLogger.forComponent("DynamicPluginManager")

        /**
         * Live manager instances (one per window), tracked so the api-plugin
         * hot swap can unload/reload plugins EVERYWHERE — the ApiClassLoader
         * is process-wide, so a swap that missed a window would leave its
         * plugins parented to the closed old loader.
         */
        private val liveManagers = CopyOnWriteArrayList<WeakReference<DynamicPluginManager>>()

        /** Re-entry guard: the swap's own reload calls installPlugin. */
        private val apiSwapInProgress =
            java.util.concurrent.atomic
                .AtomicBoolean(false)

        /**
         * Tears down all plugin-hosting UI (open plugin tabs across every
         * window) on the UI thread and waits for disposal — invoked by the
         * hot swap BEFORE any classloader closes, so Compose's onDispose runs
         * against still-open loaders. Set once by the desktop layer
         * (PluginLoaderDelegateSetup); null in headless/test contexts, where
         * the swap simply skips teardown.
         */
        @Volatile
        var pluginUiTeardown: (suspend () -> Unit)? = null

        /**
         * Tears down ONE plugin's open tabs (across all windows) on the UI
         * thread and waits — invoked by [uninstallPlugin] before the loader
         * closes that plugin's classloader, so an update/reload/remove of a
         * tab-hosting plugin (terminal-tab, editor-tab, fluck-browser) from
         * the plugin manager or an update notification disposes its tab UI
         * cleanly instead of crashing Compose against a closed loader. Set
         * once by the desktop layer; null in headless/test contexts.
         */
        @Volatile
        var pluginTabsTeardown: (suspend (pluginId: String) -> Unit)? = null

        /**
         * Resets ONE plugin's open sidebar panel slots (across all windows)
         * after its panel factories were (re)registered — invoked by
         * [installPlugin] and [enablePlugin] AFTER their mutex releases, so a
         * hot reload is live for already-open panels too: the slot's cached
         * component (which pins the pre-reload classloader) is dropped and
         * re-created from the new registration (#856). Receives the plugin's
         * freshly-registered panel ids from the registering manager's own
         * tracker, since plugins rarely set [PanelId.pluginId]. Fire-and-forget
         * on the UI thread. Set once by the desktop layer; null in
         * headless/test contexts, where no panel is open.
         */
        @Volatile
        var pluginPanelsRefresh: ((pluginId: String, panelIds: Set<PanelId>) -> Unit)? = null

        /**
         * Runs swaps decoupled from the caller. The trigger usually fires
         * from a PLUGIN's own coroutine (Toolbox update runs on
         * plugin-manager's scope, evolver hot-reload on terminal-tab's) and
         * the swap unloads that very plugin — cancelling the caller's scope
         * mid-orchestration would otherwise abort the swap with everything
         * unloaded and nothing reloaded.
         */
        private val swapScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        private fun activeManagers(): List<DynamicPluginManager> {
            liveManagers.removeIf { it.get() == null }
            return liveManagers.mapNotNull { it.get() }
        }

        /**
         * HOT-SWAP the runtime API layer: unload every plugin in every
         * window's manager, swap the process-wide ApiClassLoader to the
         * newest api jar in [pluginDir] (closing the old one), refresh the
         * boss.api.* properties, then reload everything. A full plugin
         * reload is the accepted cost of upgrading the api plugin without an
         * app restart.
         *
         * @return the number of plugins successfully reloaded. NOTE: if the
         * swap unloads the CALLER's own plugin, the caller's scope is
         * cancelled and this Result is never observed — the detached
         * orchestration still runs to completion, with the logs as the only
         * record of its outcome.
         */
        suspend fun hotSwapApiLayer(pluginDir: java.io.File): Result<Int> {
            // Detach from the caller (see swapScope): the orchestration is
            // NOT a child of the calling coroutine, so when the swap unloads
            // the caller's own plugin (cancelling its scope), the swap runs
            // to completion anyway — the caller merely gets the cancellation
            // at await.
            val detached = swapScope.async { hotSwapApiLayerOrchestration(pluginDir) }
            return try {
                detached.await()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // Caller cancelled (typically the swap unloaded the caller's
                // own plugin) — the detached job runs on with nobody left to
                // observe it. If it then THROWS (not Result.failure), log it
                // here so the "logs are the only record" promise above holds.
                // Registered only on this path: a live caller sees the throw
                // from await() itself, and logging here too would duplicate it.
                detached.invokeOnCompletion { cause ->
                    if (cause != null && cause !is kotlinx.coroutines.CancellationException) {
                        companionLogger.error(
                            LogCategory.SYSTEM,
                            "API-layer hot swap orchestration threw after caller cancellation",
                            error = cause,
                        )
                    }
                }
                throw ce
            }
        }

        private suspend fun hotSwapApiLayerOrchestration(pluginDir: java.io.File): Result<Int> {
            if (!apiSwapInProgress.compareAndSet(false, true)) {
                return Result.failure(IllegalStateException("API layer hot swap already in progress"))
            }
            try {
                val managers = activeManagers()
                if (managers.isEmpty()) {
                    return Result.failure(IllegalStateException("No live plugin managers to swap"))
                }

                companionLogger.info(
                    LogCategory.SYSTEM,
                    "API layer hot swap: unloading all plugins",
                    mapOf(
                        "managers" to managers.size,
                    ),
                )

                // Tear down plugin-hosting UI FIRST, while every classloader is
                // still open, so Compose disposal (onDispose effects) resolves
                // its lazily-loaded lambdas instead of crashing against a
                // closed loader. Must complete before the unload loop below.
                pluginUiTeardown?.let { teardown ->
                    try {
                        teardown()
                    } catch (t: Throwable) {
                        companionLogger.warn(
                            LogCategory.SYSTEM,
                            "Plugin UI teardown before swap failed (continuing)",
                            mapOf(
                                "error" to (t.message ?: t::class.simpleName),
                            ),
                            t,
                        )
                    }
                }

                // Snapshot then unload everything (force bypasses canUnload —
                // this is a controlled, restorative operation).
                val snapshots =
                    managers.map { manager ->
                        manager to
                            manager
                                .getInstalledPlugins()
                                .sortedBy { it.manifest.loadPriority }
                    }
                for ((manager, snapshot) in snapshots) {
                    for (info in snapshot) {
                        manager.uninstallPlugin(info.manifest.pluginId, force = true).onFailure { e ->
                            companionLogger.warn(
                                LogCategory.SYSTEM,
                                "Hot swap: unload failed (continuing)",
                                mapOf(
                                    "pluginId" to info.manifest.pluginId,
                                    "error" to (e.message ?: "unknown"),
                                ),
                            )
                        }
                    }
                }

                // All plugin classloaders are closed: swap the shared layer.
                // If the swap itself throws, DON'T leave the app pluginless —
                // reload the snapshots against whatever layer is installed and
                // report the failure.
                val fresh =
                    try {
                        managers.first().pluginLoader.swapApiLayer(pluginDir)
                    } catch (t: Throwable) {
                        companionLogger.error(
                            LogCategory.SYSTEM,
                            "API layer swap failed; reloading plugins on the current layer",
                            mapOf(
                                "error" to (t.message ?: t::class.simpleName),
                            ),
                            t,
                        )
                        for ((manager, snapshot) in snapshots) {
                            for (info in snapshot) {
                                if (java.io.File(info.jarPath).isFile) {
                                    manager.installPlugin(info.jarPath, enabled = info.enabled)
                                }
                            }
                        }
                        return Result.failure(t)
                    }
                System.setProperty("boss.api.version", fresh.apiVersion ?: "")
                System.setProperty("boss.api.jar", fresh.apiJarPath ?: "")

                // Reload every plugin against the new layer. The api plugin's
                // own entry reloads from the freshly resolved jar (its old
                // jar may have been superseded/deleted).
                var reloaded = 0
                for ((manager, snapshot) in snapshots) {
                    for (info in snapshot) {
                        val pluginId = info.manifest.pluginId
                        val jarPath =
                            if (pluginId == ai.rever.boss.plugin.loader.ApiClassLoader.API_PLUGIN_ID) {
                                fresh.apiJarPath ?: info.jarPath
                            } else {
                                info.jarPath
                            }
                        if (!java.io.File(jarPath).isFile) {
                            companionLogger.warn(
                                LogCategory.SYSTEM,
                                "Hot swap: jar missing, plugin skipped until next launch",
                                mapOf(
                                    "pluginId" to pluginId,
                                    "jarPath" to jarPath,
                                ),
                            )
                            continue
                        }
                        manager
                            .installPlugin(jarPath, enabled = info.enabled)
                            .onSuccess { reloaded++ }
                            .onFailure { e ->
                                companionLogger.warn(
                                    LogCategory.SYSTEM,
                                    "Hot swap: reload failed",
                                    mapOf(
                                        "pluginId" to pluginId,
                                        "error" to (e.message ?: "unknown"),
                                    ),
                                )
                            }
                    }
                }

                companionLogger.info(
                    LogCategory.SYSTEM,
                    "API layer hot swap complete",
                    mapOf(
                        "apiVersion" to (fresh.apiVersion ?: "unknown"),
                        "reloaded" to reloaded,
                    ),
                )
                return Result.success(reloaded)
            } finally {
                apiSwapInProgress.set(false)
            }
        }
    }

    init {
        liveManagers.add(WeakReference(this))

        // Observe access changes (admin status + effective permissions) and
        // reconcile plugin visibility whenever either changes.
        managerScope.launch(Dispatchers.Main) {
            AuthStateManager.currentUser
                .map { user -> AccessSnapshot(user?.isAdmin == true, user?.permissions?.toSet() ?: emptySet()) }
                .distinctUntilChanged()
                .collect { access ->
                    val changed = _isAdmin.value != access.isAdmin || _userPermissions.value != access.permissions
                    _isAdmin.value = access.isAdmin
                    _userPermissions.value = access.permissions

                    // Keep the MCP tool registry's RBAC view in sync so permission-gated
                    // tools appear/disappear with the user's admin status and permissions.
                    ai.rever.boss.mcp.McpToolRegistryImpl
                        .updateAccess(access.isAdmin, access.permissions)

                    // Same push for every RBAC-gated UI extension registry — a
                    // new one added to ACCESS_GATED is wired here automatically.
                    ai.rever.boss.components.plugin.registries.AccessGatedRegistry.ACCESS_GATED.forEach {
                        it.updateAccess(access.isAdmin, access.permissions)
                    }

                    if (changed) {
                        handleAccessChange()
                    }
                }
        }
    }

    /** Snapshot of the inputs that determine plugin visibility. */
    private data class AccessSnapshot(
        val isAdmin: Boolean,
        val permissions: Set<String>,
    )

    /**
     * Whether the current user may see/run a plugin with the given manifest.
     *
     * - Admins implicitly hold every permission.
     * - `requiresAdmin` (legacy) still requires admin status.
     * - Otherwise the user's effective permissions must contain ALL of the
     *   plugin's [PluginManifest.requiredPermissions]. An empty list (legacy
     *   plugins) means "available to any authenticated user".
     */
    private fun canAccess(manifest: PluginManifest): Boolean =
        pluginAccessAllowed(
            isAdmin = _isAdmin.value,
            userPermissions = _userPermissions.value,
            requiresAdmin = manifest.requiresAdmin,
            requiredPermissions = manifest.requiredPermissions,
        )

    /**
     * The subset of a plugin's [PluginManifest.requiredPermissions] the current
     * (non-admin) user does NOT hold — i.e. exactly what an admin must grant for
     * the plugin to become visible. Empty for admins (they bypass the gate).
     * Surfaced in the "plugin hidden" diagnostics so it's clear *why* a plugin is
     * missing and *which* permissions to ask an admin to grant.
     */
    private fun missingPermissions(manifest: PluginManifest): List<String> =
        if (_isAdmin.value) {
            emptyList()
        } else {
            manifest.requiredPermissions.filter { it !in _userPermissions.value }
        }

    /**
     * Add a listener for plugin lifecycle events.
     */
    fun addListener(listener: DynamicPluginListener) {
        cleanupDeadReferences(listeners)
        listeners.add(WeakReference(listener))
    }

    /**
     * Remove a listener.
     */
    fun removeListener(listener: DynamicPluginListener) {
        listeners.removeIf { it.get() == null || it.get() === listener }
    }

    /**
     * Register a component that needs to be notified before plugin unload.
     */
    fun registerUnloadAware(component: PluginUnloadAware) {
        cleanupDeadReferences(unloadAwareComponents)
        unloadAwareComponents.add(WeakReference(component))
    }

    /**
     * Unregister an unload-aware component.
     */
    fun unregisterUnloadAware(component: PluginUnloadAware) {
        unloadAwareComponents.removeIf { it.get() == null || it.get() === component }
    }

    /**
     * Install a plugin from a JAR file.
     *
     * @param jarPath Path to the plugin JAR
     * @param enabled Whether to enable the plugin after loading
     * @return Result containing the plugin info or an error
     */
    suspend fun installPlugin(
        jarPath: String,
        enabled: Boolean = true,
    ): Result<DynamicPluginInfo> {
        // A NEWER boss-plugin-api jar arriving at runtime (store update,
        // Toolbox install, hot-reload) can't be applied by a normal plugin
        // (re)load — every plugin classloader parents to the CURRENT
        // ApiClassLoader. Route it through the hot swap: unload everything,
        // swap the layer, reload everything. Runs BEFORE this manager's
        // mutex (the swap's reload re-enters installPlugin) and never during
        // a swap (the guard) or at startup (equal version → normal install).
        if (!apiSwapInProgress.get()) {
            val incoming =
                runCatching {
                    ai.rever.boss.plugin.loader.PluginManifestReader
                        .readFromJar(jarPath)
                }.getOrNull()
            if (incoming?.pluginId == ai.rever.boss.plugin.loader.ApiClassLoader.API_PLUGIN_ID) {
                val installed =
                    pluginLoader
                        .getClassLoaderManager()
                        .getApiClassLoader()
                        ?.apiVersion
                        ?.let {
                            ai.rever.boss.plugin.api.Version
                                .parse(it)
                        }
                val candidate =
                    ai.rever.boss.plugin.api.Version
                        .parse(incoming.version)
                if (installed != null && candidate != null && candidate > installed) {
                    logger.info(
                        LogCategory.SYSTEM,
                        "Newer api plugin installed — hot-swapping the API layer",
                        mapOf(
                            "from" to installed.toString(),
                            "to" to candidate.toString(),
                        ),
                    )
                    hotSwapApiLayer(java.io.File(jarPath).parentFile ?: java.io.File(".")).onFailure {
                        return Result.failure(it)
                    }
                    // If the swap's snapshot contained the api plugin, it was
                    // already reloaded — return that entry. The update bridge
                    // however UNINSTALLS the api plugin before handing us the
                    // new jar, so the snapshot may have lacked it: fall through
                    // to a normal install (versions are now equal, so the
                    // trigger won't re-fire) to (re)create the plugin entry.
                    getPluginInfo(ai.rever.boss.plugin.loader.ApiClassLoader.API_PLUGIN_ID)
                        ?.let { return Result.success(it) }
                }
            }
        }
        val result =
            mutex.withLock {
                try {
                    logger.info(
                        LogCategory.SYSTEM,
                        "Installing plugin",
                        mapOf(
                            "jarPath" to jarPath,
                        ),
                    )

                    // Load the plugin
                    val loadResult = pluginLoader.loadPlugin(jarPath)
                    if (loadResult.isFailure) {
                        val error = loadResult.exceptionOrNull()

                        // Binary incompatibility detected at load time — disable gracefully
                        if (error is PluginBinaryIncompatibilityException) {
                            val pluginId = error.pluginId
                            val manifest = error.manifest
                            if (pluginId != null) {
                                PluginCrashRegistry.markIncompatible(pluginId)
                                if (manifest != null) {
                                    val info =
                                        DynamicPluginInfo(
                                            manifest = manifest,
                                            jarPath = jarPath,
                                            state = PluginState.DISABLED,
                                            loadedAt = System.currentTimeMillis(),
                                            enabled = false,
                                            errorMessage = error.message,
                                        )
                                    updatePluginState(pluginId, info)
                                }
                            }
                            logger.warn(
                                LogCategory.SYSTEM,
                                "Plugin disabled due to binary incompatibility",
                                mapOf(
                                    "jarPath" to jarPath,
                                    "error" to (error.message ?: "unknown"),
                                ),
                            )
                            notifyListeners { it.pluginLoadFailed(manifest, error) }
                            return@withLock Result.failure(error)
                        }

                        notifyListeners { it.pluginLoadFailed(null, error ?: Exception("Unknown error")) }
                        return@withLock Result.failure(error ?: Exception("Unknown error"))
                    }

                    val loadedPlugin = loadResult.getOrThrow()
                    val manifest = loadedPlugin.manifest

                    // ---- Out-of-process branch (split-brain) ----
                    if (manifest.isolationMode == "out-of-process") {
                        val spawner = outOfProcessSpawner
                        if (spawner == null) {
                            logger.warn(
                                LogCategory.SYSTEM,
                                "No OutOfProcessPluginSpawner provided; loading in-process as fallback",
                                mapOf(
                                    "pluginId" to manifest.pluginId,
                                ),
                            )
                            // Fall through to in-process path below
                        } else {
                            // Split-brain model:
                            // 1. Register UI immediately in kernel using already-loaded plugin
                            // 2. Spawn child process for state management in background
                            notifyListeners { it.beforePluginLoaded(manifest) }

                            val sandboxConfig =
                                SandboxConfig(
                                    maxThreads = manifest.sandbox.maxThreads,
                                    maxRestartAttempts = manifest.sandbox.maxRestartAttempts,
                                    heartbeatIntervalMs = manifest.sandbox.heartbeatIntervalMs,
                                )

                            val baseContext = createSandboxedContext(manifest.pluginId, sandboxConfig)
                            val trackingContext =
                                TrackingPluginContext(
                                    pluginId = manifest.pluginId,
                                    delegate = baseContext,
                                    tracker = registrationTracker,
                                    pluginManifest = manifest,
                                )
                            trackingContexts[manifest.pluginId] = trackingContext

                            try {
                                loadedPlugin.instance.register(trackingContext)
                            } catch (e: Exception) {
                                logger.error(
                                    LogCategory.SYSTEM,
                                    "Plugin registration failed in split-brain mode",
                                    mapOf(
                                        "pluginId" to manifest.pluginId,
                                    ),
                                    e,
                                )
                            }

                            // Spawn child process in background — don't block plugin loading
                            managerScope.launch {
                                val spawnResult = spawner.spawn(manifest, jarPath)
                                if (spawnResult.isFailure) {
                                    logger.warn(
                                        LogCategory.SYSTEM,
                                        "Background OOP spawn failed",
                                        mapOf(
                                            "pluginId" to manifest.pluginId,
                                            "error" to (spawnResult.exceptionOrNull()?.message ?: "unknown"),
                                        ),
                                    )
                                } else {
                                    logger.info(
                                        LogCategory.SYSTEM,
                                        "Background OOP spawn succeeded",
                                        mapOf(
                                            "pluginId" to manifest.pluginId,
                                        ),
                                    )
                                }
                            }

                            val info =
                                DynamicPluginInfo(
                                    manifest = manifest,
                                    jarPath = jarPath,
                                    state = if (enabled) PluginState.LOADED else PluginState.DISABLED,
                                    loadedAt = System.currentTimeMillis(),
                                    enabled = enabled,
                                )
                            updatePluginState(manifest.pluginId, info)
                            notifyListeners { it.pluginLoaded(manifest) }
                            emitPluginLifecycle(manifest.pluginId, PluginLifecycleState.LOADED)

                            logger.info(
                                LogCategory.SYSTEM,
                                "Split-brain plugin loaded: UI in-process, state out-of-process",
                                mapOf(
                                    "pluginId" to manifest.pluginId,
                                    "jarPath" to jarPath,
                                ),
                            )
                            return@withLock Result.success(info)
                        }
                    }

                    // Notify listeners before registration
                    notifyListeners { it.beforePluginLoaded(manifest) }

                    // Create tracking context
                    val sandboxConfig =
                        SandboxConfig(
                            maxThreads = manifest.sandbox.maxThreads,
                            maxRestartAttempts = manifest.sandbox.maxRestartAttempts,
                            heartbeatIntervalMs = manifest.sandbox.heartbeatIntervalMs,
                        )

                    val baseContext = createSandboxedContext(manifest.pluginId, sandboxConfig)
                    val trackingContext =
                        TrackingPluginContext(
                            pluginId = manifest.pluginId,
                            delegate = baseContext,
                            tracker = registrationTracker,
                            pluginManifest = manifest,
                        )
                    trackingContexts[manifest.pluginId] = trackingContext

                    // Check whether the current user satisfies the plugin's access
                    // requirements (admin status and/or required permissions).
                    val accessible = canAccess(manifest)

                    // Register the plugin (unless the user lacks access to it)
                    var registrationFailed = false
                    if (enabled && accessible) {
                        try {
                            loadedPlugin.instance.register(trackingContext)
                        } catch (e: Throwable) {
                            // Catch Throwable (not just Exception) because binary incompatibility
                            // errors like NoSuchMethodError extend Error, not Exception.
                            logger.error(
                                LogCategory.SYSTEM,
                                "Error registering plugin",
                                mapOf(
                                    "pluginId" to manifest.pluginId,
                                    "errorType" to e.javaClass.simpleName,
                                ),
                                e,
                            )

                            if (PluginErrorClassifier.isBinaryIncompatibility(e)) {
                                // Binary incompatibility is deterministic — track as DISABLED
                                // so the UI shows "Plugin Incompatible" with update prompt.
                                PluginCrashRegistry.markIncompatible(manifest.pluginId)
                                registrationFailed = true

                                trackingContext.unregisterAll()
                                trackingContexts.remove(manifest.pluginId)
                                // force: this is cleanup of a plugin we just loaded and
                                // failed to register — canUnload=false must not strand it
                                // in the classloader unregistered.
                                pluginLoader.unloadPlugin(manifest.pluginId, force = true)
                            } else {
                                // Recoverable error — cleanup and report failure
                                trackingContext.unregisterAll()
                                trackingContexts.remove(manifest.pluginId)
                                pluginLoader.unloadPlugin(manifest.pluginId, force = true)

                                notifyListeners { it.pluginLoadFailed(manifest, e) }
                                return@withLock Result.failure(e)
                            }
                        }
                    }

                    // Create plugin info
                    val pluginState =
                        when {
                            registrationFailed -> PluginState.DISABLED
                            enabled && accessible -> PluginState.LOADED
                            else -> PluginState.DISABLED
                        }
                    val info =
                        DynamicPluginInfo(
                            manifest = manifest,
                            jarPath = jarPath,
                            state = pluginState,
                            loadedAt = System.currentTimeMillis(),
                            enabled = enabled && !registrationFailed,
                        )

                    // Track plugins hidden because the user lacks access (admin and/or
                    // required permissions). They are re-registered if access qualifies.
                    if (!accessible && enabled) {
                        hiddenPlugins[manifest.pluginId] = info
                        val missing = missingPermissions(manifest)
                        logger.info(
                            LogCategory.SYSTEM,
                            "Plugin hidden (insufficient access)",
                            mapOf(
                                "pluginId" to manifest.pluginId,
                                "requiresAdmin" to manifest.requiresAdmin,
                                "requiredPermissions" to manifest.requiredPermissions.joinToString(","),
                                "missingPermissions" to missing.joinToString(","),
                                "hint" to
                                    if (missing.isNotEmpty()) {
                                        "Ask an admin to grant: ${missing.joinToString(", ")}"
                                    } else {
                                        "Requires admin"
                                    },
                            ),
                        )
                    }

                    // Update state
                    updatePluginState(manifest.pluginId, info)

                    // Notify listeners
                    notifyListeners { it.pluginLoaded(manifest) }
                    emitPluginLifecycle(manifest.pluginId, PluginLifecycleState.LOADED)

                    logger.info(
                        LogCategory.SYSTEM,
                        "Plugin installed successfully",
                        mapOf(
                            "pluginId" to manifest.pluginId,
                            "version" to manifest.version,
                        ),
                    )

                    Result.success(info)
                } catch (e: Throwable) {
                    logger.error(
                        LogCategory.SYSTEM,
                        "Failed to install plugin",
                        mapOf(
                            "jarPath" to jarPath,
                            "errorType" to e.javaClass.simpleName,
                        ),
                        e,
                    )
                    Result.failure(e)
                }
            }
        // The registration above swapped this plugin's panel factories, but an
        // open sidebar panel slot keeps its already-created component (old
        // classloader and all) until reset — refresh those slots now that the
        // new factories are in place. Outside the mutex because the refresh
        // hops to the UI thread, which may re-enter manager operations (same
        // reasoning as pluginTabsTeardown in uninstallPlugin).
        result.getOrNull()?.takeIf { it.state == PluginState.LOADED }?.let { info ->
            notifyPanelsRefresh(info.manifest.pluginId)
        }
        return result
    }

    /**
     * Check if a plugin can be unloaded without issues.
     *
     * @param pluginId The plugin ID
     * @return Result indicating whether unloading is allowed
     */
    suspend fun checkCanUnload(pluginId: String): CanUnloadResult {
        val reasons = mutableListOf<String>()

        // Check with all unload-aware components
        for (ref in unloadAwareComponents) {
            val component = ref.get() ?: continue
            val result = component.checkCanUnload(pluginId)
            if (result is CanUnloadResult.NotAllowed) {
                reasons.addAll(result.reasons)
            }
        }

        // Check for dependent plugins
        val loadedPlugins = pluginLoader.getLoadedPlugins()
        for (plugin in loadedPlugins) {
            val hasDependency = plugin.manifest.dependencies.any { it.pluginId == pluginId }
            if (hasDependency) {
                reasons.add("Plugin '${plugin.manifest.displayName}' depends on this plugin")
            }
        }

        return if (reasons.isEmpty()) {
            CanUnloadResult.Ok
        } else {
            CanUnloadResult.NotAllowed(reasons)
        }
    }

    /**
     * Uninstall a plugin.
     *
     * @param pluginId The plugin ID
     * @param force Force unload even if checkCanUnload returns NotAllowed
     * @param waitForGC Whether to wait for classloader garbage collection
     * @return Result indicating success or failure
     */
    suspend fun uninstallPlugin(
        pluginId: String,
        force: Boolean = false,
        waitForGC: Boolean = false,
    ): Result<Unit> =
        uninstallPlugin(
            pluginId = pluginId,
            force = force,
            waitForGC = waitForGC,
            closeTabsAcrossWindows = true,
        )

    private suspend fun uninstallPlugin(
        pluginId: String,
        force: Boolean,
        waitForGC: Boolean,
        closeTabsAcrossWindows: Boolean,
    ): Result<Unit> {
        // Close this plugin's open tabs on the UI thread FIRST, while its
        // classloader is still open, so Compose disposal resolves its
        // lazily-loaded onDispose lambdas instead of crashing against a
        // closed loader (the ProperTerminal NoClassDefFound class of bug).
        // Covers update/reload/remove from the plugin manager and update
        // notifications. Deliberately BEFORE the mutex — the teardown blocks
        // on the EDT (invokeAndWait), and EDT-side disposal may re-enter
        // manager operations that need this mutex; holding it across the
        // round-trip risks deadlock (mirrors the swap orchestration, which
        // tears down all tabs before its unload loop, and resetPluginInstances).
        // Runs during the API swap too — usually a no-op there (teardown-all
        // already emptied every plugin tab at swap start, so the rescan finds
        // nothing and never touches the EDT), but it catches a tab the user
        // opened mid-swap before that plugin's loader closes. The pre-checks
        // are best-effort reads outside the lock — if the uninstall below
        // still refuses, we closed tabs unnecessarily, which is a minor UX
        // cost, not a correctness one. For non-forced uninstalls the
        // pre-check mirrors BOTH refusal paths the locked section applies
        // (manifest.canUnload and checkCanUnload's dependents/unload-aware
        // vetoes), so a depended-upon plugin's tabs survive a remove that
        // will be refused anyway. (checkCanUnload runs again inside the lock;
        // the doubled O(plugins) scan is the price of keeping the locked
        // check authoritative.)
        val candidate = pluginLoader.getPlugin(pluginId)
        if (closeTabsAcrossWindows &&
            candidate != null &&
            (force || (candidate.manifest.canUnload && checkCanUnload(pluginId).isAllowed))
        ) {
            pluginTabsTeardown?.let { teardown ->
                try {
                    teardown(pluginId)
                } catch (t: Throwable) {
                    logger.warn(
                        LogCategory.SYSTEM,
                        "Plugin tab teardown before unload failed (continuing)",
                        mapOf(
                            "pluginId" to pluginId,
                            "error" to (t.message ?: t::class.simpleName),
                        ),
                    )
                }
            }
        }
        return mutex.withLock {
            try {
                logger.info(
                    LogCategory.SYSTEM,
                    "Uninstalling plugin",
                    mapOf(
                        "pluginId" to pluginId,
                        "force" to force,
                    ),
                )

                val loadedPlugin = pluginLoader.getPlugin(pluginId)
                if (loadedPlugin == null) {
                    // Plugin not in classloader — may have been unloaded during
                    // incompatibility handling but still tracked in _pluginStates.
                    // Clean up state so the update flow can proceed to reinstall.
                    val pluginState = _pluginStates.value[pluginId]
                    if (pluginState != null) {
                        logger.info(
                            LogCategory.SYSTEM,
                            "Plugin already unloaded from classloader, cleaning up state",
                            mapOf(
                                "pluginId" to pluginId,
                                "state" to pluginState.state.name,
                            ),
                        )
                        val trackingContext = trackingContexts.remove(pluginId)
                        trackingContext?.unregisterAll()
                        managerScope.launch { sandboxManager.removeSandbox(pluginId) }
                        removePluginState(pluginId)
                        return@withLock Result.success(Unit)
                    }
                    return@withLock Result.failure(
                        PluginUnloadException("Plugin not found: $pluginId", pluginId),
                    )
                }

                val manifest = loadedPlugin.manifest

                // Check if plugin can be unloaded (system plugins may be protected)
                if (!manifest.canUnload && !force) {
                    logger.warn(
                        LogCategory.SYSTEM,
                        "Cannot unload system plugin",
                        mapOf(
                            "pluginId" to pluginId,
                            "systemPlugin" to manifest.systemPlugin,
                        ),
                    )
                    return@withLock Result.failure(
                        PluginUnloadException(
                            "Cannot unload system plugin: $pluginId (canUnload=false)",
                            pluginId,
                            listOf("System plugin is protected from unloading"),
                        ),
                    )
                }

                // Check if unload is allowed
                if (!force) {
                    val canUnload = checkCanUnload(pluginId)
                    if (!canUnload.isAllowed) {
                        val reasons = (canUnload as CanUnloadResult.NotAllowed).reasons
                        return@withLock Result.failure(
                            PluginUnloadException(
                                "Cannot unload plugin: ${reasons.joinToString("; ")}",
                                pluginId,
                                reasons,
                            ),
                        )
                    }
                }

                // Notify listeners before unload
                notifyListeners { it.beforePluginUnload(manifest) }

                // Prepare unload-aware components
                for (ref in unloadAwareComponents) {
                    val component = ref.get() ?: continue
                    try {
                        component.prepareForUnload(pluginId)
                    } catch (e: Exception) {
                        logger.warn(
                            LogCategory.SYSTEM,
                            "Error preparing component for unload",
                            mapOf(
                                "pluginId" to pluginId,
                            ),
                            error = e,
                        )
                    }
                }

                // Unregister all panels and tabs
                val trackingContext = trackingContexts.remove(pluginId)
                trackingContext?.unregisterAll()

                // Remove sandbox
                managerScope.launch {
                    sandboxManager.removeSandbox(pluginId)
                }

                // Terminate out-of-process child if applicable
                if (manifest.isolationMode == "out-of-process") {
                    managerScope.launch {
                        outOfProcessSpawner?.terminate(pluginId)
                    }
                }

                // Unload the plugin
                // force flows through: without it the loader re-blocks
                // canUnload=false system plugins, leaving their classloaders
                // parented to a superseded ApiClassLoader after a hot swap.
                val unloadResult = pluginLoader.unloadPlugin(pluginId, waitForGC, force)
                if (unloadResult.isFailure) {
                    notifyListeners { it.pluginUnloadFailed(manifest, unloadResult.exceptionOrNull()!!) }
                    return@withLock unloadResult
                }

                // Update state
                removePluginState(pluginId)

                // Notify listeners
                notifyListeners { it.pluginUnloaded(manifest) }
                emitPluginLifecycle(manifest.pluginId, PluginLifecycleState.UNLOADED)

                logger.info(
                    LogCategory.SYSTEM,
                    "Plugin uninstalled successfully",
                    mapOf(
                        "pluginId" to pluginId,
                    ),
                )

                Result.success(Unit)
            } catch (e: Exception) {
                logger.error(
                    LogCategory.SYSTEM,
                    "Failed to uninstall plugin",
                    mapOf(
                        "pluginId" to pluginId,
                    ),
                    e,
                )
                Result.failure(e)
            }
        }
    }

    /**
     * Enable a disabled plugin.
     *
     * @param pluginId The plugin ID
     * @return Result indicating success or failure
     */
    suspend fun enablePlugin(pluginId: String): Result<Unit> {
        var wasAlreadyEnabled = false
        val result =
            mutex.withLock {
                try {
                    val loadedPlugin =
                        pluginLoader.getPlugin(pluginId)
                            ?: return@withLock Result.failure(Exception("Plugin not found: $pluginId"))

                    val trackingContext =
                        trackingContexts[pluginId]
                            ?: return@withLock Result.failure(Exception("No context for plugin: $pluginId"))

                    wasAlreadyEnabled = _pluginStates.value[pluginId]?.enabled == true

                    // Register the plugin
                    loadedPlugin.instance.register(trackingContext)

                    // Enable sandbox
                    sandboxManager.enablePlugin(pluginId)

                    // Clear any prior incompatible state on successful re-enable
                    PluginCrashRegistry.clearIncompatible(pluginId)

                    // Update state
                    val currentInfo = _pluginStates.value[pluginId]
                    if (currentInfo != null) {
                        updatePluginState(
                            pluginId,
                            currentInfo.copy(
                                state = PluginState.LOADED,
                                enabled = true,
                            ),
                        )
                    }

                    Result.success(Unit)
                } catch (e: Throwable) {
                    logger.error(
                        LogCategory.SYSTEM,
                        "Failed to enable plugin",
                        mapOf(
                            "pluginId" to pluginId,
                            "errorType" to e.javaClass.simpleName,
                        ),
                        e,
                    )
                    // register() may have partially succeeded (panels/tab types/MCP tool
                    // providers already registered) before throwing — tear those down so a
                    // "disabled" plugin can't leave agent-callable MCP tools live.
                    runCatching { trackingContexts[pluginId]?.unregisterAll() }
                    Result.failure(e)
                }
            }
        // A panel left open across disable→enable holds the pre-disable
        // component — refresh it from the just-re-registered factories.
        // Outside the mutex, same as installPlugin. Skipped when the plugin
        // was already enabled: a redundant enable must not discard an open
        // panel's in-memory state (resetComponent loses it by design).
        if (result.isSuccess && !wasAlreadyEnabled) notifyPanelsRefresh(pluginId)
        return result
    }

    /** Invoke [pluginPanelsRefresh] with [pluginId]'s currently-registered panels; never throws. */
    private fun notifyPanelsRefresh(pluginId: String) {
        val refresh = pluginPanelsRefresh ?: return
        try {
            // Tracker read outside the mutex on purpose — racy against a
            // concurrent uninstall/reload of the same plugin, which is fine
            // for a best-effort fire-and-forget refresh: worst case a slot is
            // missed and shows the old build until the next reload.
            refresh(pluginId, registrationTracker.getPanelsForPlugin(pluginId))
        } catch (t: Throwable) {
            logger.warn(
                LogCategory.SYSTEM,
                "Panel refresh after plugin (re)registration failed",
                mapOf(
                    "pluginId" to pluginId,
                    "error" to (t.message ?: t::class.simpleName),
                ),
            )
        }
    }

    /**
     * Disable an enabled plugin without unloading it.
     *
     * @param pluginId The plugin ID
     * @return Result indicating success or failure
     */
    suspend fun disablePlugin(pluginId: String): Result<Unit> {
        return mutex.withLock {
            try {
                val trackingContext =
                    trackingContexts[pluginId]
                        ?: return@withLock Result.failure(Exception("No context for plugin: $pluginId"))

                // Unregister all panels and tabs
                trackingContext.unregisterAll()

                // Disable sandbox
                sandboxManager.disablePlugin(pluginId)

                // Update state
                val currentInfo = _pluginStates.value[pluginId]
                if (currentInfo != null) {
                    updatePluginState(
                        pluginId,
                        currentInfo.copy(
                            state = PluginState.DISABLED,
                            enabled = false,
                        ),
                    )
                }

                Result.success(Unit)
            } catch (e: Exception) {
                logger.error(
                    LogCategory.SYSTEM,
                    "Failed to disable plugin",
                    mapOf(
                        "pluginId" to pluginId,
                    ),
                    e,
                )
                Result.failure(e)
            }
        }
    }

    /**
     * Reload a plugin by uninstalling and reinstalling from the same JAR path.
     *
     * @param pluginId The plugin ID to reload
     * @return Result containing the reloaded plugin info or an error
     */
    suspend fun reloadPlugin(pluginId: String): Result<DynamicPluginInfo> {
        val info =
            getPluginInfo(pluginId)
                ?: return Result.failure(Exception("Plugin not found: $pluginId"))
        val jarPath = info.jarPath
        val wasEnabled = info.enabled

        logger.info(
            LogCategory.SYSTEM,
            "Reloading plugin",
            mapOf(
                "pluginId" to pluginId,
                "jarPath" to jarPath,
            ),
        )

        val uninstallResult = uninstallPlugin(pluginId, force = true)
        if (uninstallResult.isFailure) {
            logger.error(
                LogCategory.SYSTEM,
                "Failed to uninstall plugin during reload",
                mapOf(
                    "pluginId" to pluginId,
                    "error" to (uninstallResult.exceptionOrNull()?.message ?: "unknown"),
                ),
            )
            return Result.failure(uninstallResult.exceptionOrNull() ?: Exception("Uninstall failed"))
        }

        return installPlugin(jarPath, enabled = wasEnabled)
    }

    /**
     * Reload all installed plugins.
     *
     * @return Result containing the number of successfully reloaded plugins
     */
    suspend fun reloadAllPlugins(): Result<Int> {
        val plugins = getInstalledPlugins().toList()
        logger.info(
            LogCategory.SYSTEM,
            "Reloading all plugins",
            mapOf(
                "count" to plugins.size,
            ),
        )

        var reloaded = 0
        for (info in plugins) {
            val result = reloadPlugin(info.manifest.pluginId)
            if (result.isSuccess) reloaded++
        }

        logger.info(
            LogCategory.SYSTEM,
            "Finished reloading all plugins",
            mapOf(
                "total" to plugins.size,
                "reloaded" to reloaded,
            ),
        )
        return Result.success(reloaded)
    }

    /**
     * Get information about a plugin.
     */
    fun getPluginInfo(pluginId: String): DynamicPluginInfo? = _pluginStates.value[pluginId]

    /**
     * Get all installed plugins.
     */
    fun getInstalledPlugins(): List<DynamicPluginInfo> = _pluginStates.value.values.toList()

    /**
     * Check if a plugin is installed.
     */
    fun isInstalled(pluginId: String): Boolean = _pluginStates.value.containsKey(pluginId)

    /**
     * Get installed plugins visible to the current user.
     * Filters out plugins the user lacks access to (admin and/or permissions).
     */
    fun getVisibleInstalledPlugins(): List<DynamicPluginInfo> =
        _pluginStates.value.values.filter { info ->
            canAccess(info.manifest)
        }

    /**
     * Installed plugins the current (non-admin) user can't see because they lack
     * required permissions, each with the specific missing permissions an admin
     * would need to grant. Empty for admins (they bypass the permission gate) and
     * for plugins hidden only by the legacy `requiresAdmin` flag (no permission to
     * grant). Powers the Plugin Manager's "ask an admin" banner.
     */
    fun getInaccessiblePlugins(): List<ai.rever.boss.plugin.api.InaccessiblePluginInfo> {
        if (_isAdmin.value) return emptyList()
        return hiddenPlugins.values.mapNotNull { info ->
            val missing = missingPermissions(info.manifest)
            if (missing.isEmpty()) {
                null
            } else {
                ai.rever.boss.plugin.api.InaccessiblePluginInfo(
                    pluginId = info.manifest.pluginId,
                    displayName = info.manifest.displayName,
                    missingPermissions = missing,
                )
            }
        }
    }

    /**
     * Get the current admin status.
     */
    fun isCurrentUserAdmin(): Boolean = _isAdmin.value

    /**
     * Get the registration tracker.
     */
    fun getRegistrationTracker(): PluginRegistrationTracker = registrationTracker

    /**
     * Load plugins from persisted state.
     * This should be called during application startup to restore previously installed plugins.
     *
     * @param plugins List of plugin entries with JAR paths and enabled states
     * @return Map of plugin IDs to their load results
     */
    suspend fun loadPersistedPlugins(plugins: List<PersistedPluginEntry>): Map<String, Result<DynamicPluginInfo>> {
        val results = mutableMapOf<String, Result<DynamicPluginInfo>>()

        logger.info(
            LogCategory.SYSTEM,
            "Loading persisted plugins",
            mapOf(
                "count" to plugins.size,
            ),
        )

        for (entry in plugins) {
            try {
                var jarFile = java.io.File(entry.jarPath)
                if (!jarFile.exists()) {
                    // The background system-plugin updater can replace a JAR
                    // (new versioned filename, old file deleted) between the
                    // persisted snapshot being read and this entry's turn —
                    // the path goes stale while the plugin sits right there
                    // under a new name. Re-resolve by pluginId before giving up.
                    val relocated = findRelocatedPluginJar(jarFile.parentFile, entry.pluginId)
                    if (relocated == null) {
                        logger.warn(
                            LogCategory.SYSTEM,
                            "Persisted plugin JAR not found",
                            mapOf(
                                "pluginId" to entry.pluginId,
                                "jarPath" to entry.jarPath,
                            ),
                        )
                        results[entry.pluginId] = Result.failure(Exception("JAR file not found: ${entry.jarPath}"))
                        continue
                    }
                    logger.info(
                        LogCategory.SYSTEM,
                        "Persisted JAR path stale — loading relocated jar",
                        mapOf(
                            "pluginId" to entry.pluginId,
                            "staleJarPath" to entry.jarPath,
                            "jarPath" to relocated.absolutePath,
                        ),
                    )
                    jarFile = relocated
                }

                val result = installPlugin(jarFile.absolutePath, enabled = entry.enabled)
                results[entry.pluginId] = result

                if (result.isSuccess) {
                    logger.info(
                        LogCategory.SYSTEM,
                        "Loaded persisted plugin",
                        mapOf(
                            "pluginId" to entry.pluginId,
                            "enabled" to entry.enabled,
                        ),
                    )
                } else {
                    logger.error(
                        LogCategory.SYSTEM,
                        "Failed to load persisted plugin",
                        mapOf(
                            "pluginId" to entry.pluginId,
                            "error" to (result.exceptionOrNull()?.message ?: "unknown"),
                        ),
                    )
                }
            } catch (e: Throwable) {
                logger.error(
                    LogCategory.SYSTEM,
                    "Exception loading persisted plugin",
                    mapOf(
                        "pluginId" to entry.pluginId,
                        "errorType" to e.javaClass.simpleName,
                    ),
                    e,
                )
                results[entry.pluginId] = Result.failure(e)
            }
        }

        logger.info(
            LogCategory.SYSTEM,
            "Finished loading persisted plugins",
            mapOf(
                "total" to plugins.size,
                "successful" to results.count { it.value.isSuccess },
                "failed" to results.count { it.value.isFailure },
            ),
        )

        return results
    }

    /**
     * Load all bundled plugins from a directory.
     *
     * Bundled plugins are system plugins that ship with BossConsole.
     * They are loaded in priority order (lower loadPriority values load first).
     *
     * @param bundledDir Directory containing bundled plugin JARs
     * @return Map of plugin IDs to their load results
     */
    suspend fun loadBundledPlugins(bundledDir: java.io.File): Map<String, Result<DynamicPluginInfo>> {
        val results = mutableMapOf<String, Result<DynamicPluginInfo>>()

        if (!bundledDir.exists() || !bundledDir.isDirectory) {
            logger.debug(
                LogCategory.SYSTEM,
                "Bundled plugins directory not found",
                mapOf(
                    "path" to bundledDir.absolutePath,
                ),
            )
            return results
        }

        val jarFiles =
            bundledDir
                .listFiles { file ->
                    file.isFile && file.extension == "jar"
                }?.toList() ?: emptyList()

        if (jarFiles.isEmpty()) {
            logger.debug(
                LogCategory.SYSTEM,
                "No bundled plugins found",
                mapOf(
                    "path" to bundledDir.absolutePath,
                ),
            )
            return results
        }

        logger.info(
            LogCategory.SYSTEM,
            "Loading bundled plugins",
            mapOf(
                "count" to jarFiles.size,
                "path" to bundledDir.absolutePath,
            ),
        )

        // Load each bundled plugin
        for (jarFile in jarFiles) {
            try {
                val result = installPlugin(jarFile.absolutePath, enabled = true)
                val pluginId = result.getOrNull()?.manifest?.pluginId ?: jarFile.nameWithoutExtension
                results[pluginId] = result

                if (result.isSuccess) {
                    val info = result.getOrThrow()
                    logger.info(
                        LogCategory.SYSTEM,
                        "Loaded bundled plugin",
                        mapOf(
                            "pluginId" to info.manifest.pluginId,
                            "version" to info.manifest.version,
                            "systemPlugin" to info.manifest.systemPlugin,
                            "loadPriority" to info.manifest.loadPriority,
                        ),
                    )
                } else {
                    logger.error(
                        LogCategory.SYSTEM,
                        "Failed to load bundled plugin",
                        mapOf(
                            "file" to jarFile.name,
                            "error" to (result.exceptionOrNull()?.message ?: "unknown"),
                        ),
                    )
                }
            } catch (e: Throwable) {
                logger.error(
                    LogCategory.SYSTEM,
                    "Exception loading bundled plugin",
                    mapOf(
                        "file" to jarFile.name,
                        "errorType" to e.javaClass.simpleName,
                    ),
                    e,
                )
                results[jarFile.nameWithoutExtension] = Result.failure(e)
            }
        }

        // Sort results by loadPriority for logging
        val sortedPlugins =
            results.values
                .mapNotNull { it.getOrNull() }
                .sortedBy { it.manifest.loadPriority }

        logger.info(
            LogCategory.SYSTEM,
            "Finished loading bundled plugins",
            mapOf(
                "total" to jarFiles.size,
                "successful" to results.count { it.value.isSuccess },
                "failed" to results.count { it.value.isFailure },
                "loadOrder" to sortedPlugins.map { "${it.manifest.pluginId}(${it.manifest.loadPriority})" },
            ),
        )

        return results
    }

    /**
     * Check if a plugin is a system/bundled plugin.
     *
     * @param pluginId The plugin ID to check
     * @return True if the plugin is a system plugin
     */
    fun isSystemPlugin(pluginId: String): Boolean = _pluginStates.value[pluginId]?.manifest?.systemPlugin == true

    /**
     * Get the bundled plugins directory path.
     *
     * @return The path to the bundled plugins directory
     */
    fun getBundledPluginsDirectory(): java.io.File {
        // First check for system property override (useful for development)
        val systemPropDir = System.getProperty("boss.bundled.plugins.dir")
        if (!systemPropDir.isNullOrBlank()) {
            return java.io.File(systemPropDir)
        }

        val userDir = System.getProperty("user.dir")

        // Check multiple locations in order of priority:
        // 1. bundled-plugins in current directory (production)
        val prodDir = java.io.File(userDir, "bundled-plugins")
        if (prodDir.exists() && prodDir.isDirectory) {
            return prodDir
        }

        // 2. composeApp/build/bundled-plugins (development - Gradle run)
        val devDir = java.io.File(userDir, "composeApp/build/bundled-plugins")
        if (devDir.exists() && devDir.isDirectory) {
            return devDir
        }

        // 3. ../build/bundled-plugins (development - running from composeApp)
        val devDir2 = java.io.File(userDir, "../composeApp/build/bundled-plugins")
        if (devDir2.exists() && devDir2.isDirectory) {
            return devDir2
        }

        // Default to production location even if it doesn't exist
        return prodDir
    }

    /**
     * Dispose the manager and all plugins.
     */
    suspend fun dispose() {
        dispose(closeTabsAcrossWindows = true)
    }

    /**
     * Dispose this window's plugin manager without closing plugin tabs in other windows.
     *
     * Each BossApp window owns a manager instance, while the global plugin tab teardown
     * callback intentionally spans every window for explicit update/reload/uninstall.
     * Window composition teardown must bypass that global callback.
     */
    suspend fun disposeWindow() {
        dispose(closeTabsAcrossWindows = false)
    }

    private suspend fun dispose(closeTabsAcrossWindows: Boolean) {
        logger.info(
            LogCategory.SYSTEM,
            "Disposing DynamicPluginManager",
            mapOf(
                "scope" to if (closeTabsAcrossWindows) "global" else "window",
            ),
        )

        // Uninstall all plugins
        for (pluginId in _pluginStates.value.keys.toList()) {
            uninstallPlugin(
                pluginId = pluginId,
                force = true,
                waitForGC = false,
                closeTabsAcrossWindows = closeTabsAcrossWindows,
            )
        }

        // Cancel scope
        managerScope.cancel()
    }

    /**
     * Reconcile plugin visibility after the user's access (admin status and/or
     * effective permissions) changes. Re-registers previously-hidden plugins the
     * user can now access, and unregisters/hides plugins they can no longer access.
     */
    private suspend fun handleAccessChange() {
        mutex.withLock {
            // 1. Re-register previously-hidden plugins the user can now access.
            val nowVisible =
                hiddenPlugins.filterValues { info ->
                    info.enabled && canAccess(info.manifest)
                }
            for ((pluginId, info) in nowVisible) {
                val trackingContext = trackingContexts[pluginId]
                val loadedPlugin = pluginLoader.getPlugin(pluginId)
                if (trackingContext != null && loadedPlugin != null) {
                    try {
                        loadedPlugin.instance.register(trackingContext)
                        updatePluginState(pluginId, info.copy(state = PluginState.LOADED))
                        hiddenPlugins.remove(pluginId)
                        logger.info(
                            LogCategory.SYSTEM,
                            "Re-registered plugin after access gained",
                            mapOf(
                                "pluginId" to pluginId,
                            ),
                        )
                    } catch (e: Throwable) {
                        logger.error(
                            LogCategory.SYSTEM,
                            "Failed to re-register plugin",
                            mapOf(
                                "pluginId" to pluginId,
                                "errorType" to e.javaClass.simpleName,
                            ),
                            e,
                        )
                        // Partial register() must not leave stray registrations (incl.
                        // agent-callable MCP tool providers) for a plugin still hidden.
                        runCatching { trackingContext.unregisterAll() }
                    }
                }
            }

            // 2. Unregister and hide plugins the user can no longer access.
            val nowHidden =
                _pluginStates.value.filter { (pluginId, info) ->
                    info.enabled && !hiddenPlugins.containsKey(pluginId) && !canAccess(info.manifest)
                }
            for ((pluginId, info) in nowHidden) {
                val trackingContext = trackingContexts[pluginId]
                if (trackingContext != null) {
                    trackingContext.unregisterAll()
                    hiddenPlugins[pluginId] = info
                    updatePluginState(pluginId, info.copy(state = PluginState.DISABLED))
                    val missing = missingPermissions(info.manifest)
                    logger.info(
                        LogCategory.SYSTEM,
                        "Hid plugin after access lost",
                        mapOf(
                            "pluginId" to pluginId,
                            "missingPermissions" to missing.joinToString(","),
                            "hint" to
                                if (missing.isNotEmpty()) {
                                    "Ask an admin to grant: ${missing.joinToString(", ")}"
                                } else {
                                    "Requires admin"
                                },
                        ),
                    )
                }
            }
        }
    }

    private fun updatePluginState(
        pluginId: String,
        info: DynamicPluginInfo,
    ) {
        _pluginStates.value = _pluginStates.value + (pluginId to info)
    }

    private fun removePluginState(pluginId: String) {
        _pluginStates.value = _pluginStates.value - pluginId
    }

    private fun <T> cleanupDeadReferences(list: CopyOnWriteArrayList<WeakReference<T>>) {
        list.removeIf { it.get() == null }
    }

    /**
     * Emit a [PluginLifecycleEvent] onto the shared application event bus so analytics and
     * other subscribers observe plugin load/unload. Best-effort; never throws.
     */
    private fun emitPluginLifecycle(
        pluginId: String,
        state: PluginLifecycleState,
    ) {
        publishSystemEvent(PluginLifecycleEvent(pluginId = pluginId, lifecycleState = state))
    }

    private fun notifyListeners(action: (DynamicPluginListener) -> Unit) {
        listeners.removeIf { ref ->
            val listener = ref.get()
            if (listener != null) {
                try {
                    action(listener)
                } catch (e: Exception) {
                    logger.warn(
                        LogCategory.SYSTEM,
                        "Error notifying listener",
                        mapOf(
                            "error" to (e.message ?: "unknown"),
                        ),
                    )
                }
                false // Keep reference
            } else {
                true // Remove dead reference
            }
        }
    }
}

/**
 * Pure access predicate for permission-based plugin gating. Extracted from
 * [DynamicPluginManager.canAccess] so it can be unit-tested in isolation.
 *
 * - Admins implicitly hold every permission.
 * - `requiresAdmin` (legacy gate) still requires admin status.
 * - Otherwise the user's effective permissions must contain ALL of
 *   [requiredPermissions]. An empty list (legacy plugins) means any authenticated user.
 */
internal fun pluginAccessAllowed(
    isAdmin: Boolean,
    userPermissions: Set<String>,
    requiresAdmin: Boolean,
    requiredPermissions: List<String>,
): Boolean {
    if (requiresAdmin && !isAdmin) return false
    if (isAdmin) return true
    return userPermissions.containsAll(requiredPermissions)
}

/**
 * The best jar in [dir] whose manifest pluginId matches [pluginId], or null.
 * Fallback for a persisted jar path gone stale because a background update
 * replaced the file under a new versioned name: highest parseable manifest
 * version wins, newest file breaks ties. Top-level so it can be unit-tested
 * (see [pluginAccessAllowed] for the same pattern).
 *
 * Highest-version assumption: this path only triggers when the persisted
 * file is GONE — i.e. after a delete-and-replace, where highest == intended.
 * A pinned/downgraded version keeps its file and never gets here; and the
 * startup reconciler dedupes multi-version leftovers before the persisted
 * pass, so ambiguity is transient. Cost (opens every jar's manifest) is
 * fine because this is the exceptional path — hoist a single dir-wide
 * manifest map if it ever runs per-entry at scale.
 */
internal fun findRelocatedPluginJar(
    dir: java.io.File?,
    pluginId: String,
): java.io.File? =
    dir
        ?.listFiles { f -> f.isFile && f.extension == "jar" }
        ?.mapNotNull { jar ->
            val manifest =
                runCatching {
                    ai.rever.boss.plugin.loader.PluginManifestReader
                        .readFromJar(jar.absolutePath)
                }.getOrNull() ?: return@mapNotNull null
            if (manifest.pluginId != pluginId) return@mapNotNull null
            jar to
                ai.rever.boss.plugin.api.Version
                    .parse(manifest.version)
        }?.maxWithOrNull(
            compareBy<Pair<java.io.File, ai.rever.boss.plugin.api.Version?>, ai.rever.boss.plugin.api.Version?>(
                nullsFirst(),
            ) { it.second }.thenBy { it.first.lastModified() },
        )?.first
