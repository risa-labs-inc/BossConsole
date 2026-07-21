package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.git.GitDataProviderImpl
import ai.rever.boss.components.plugin.providers.PanelEventProviderImpl
import ai.rever.boss.components.plugin.providers.SettingsProviderImpl
import ai.rever.boss.cache.loadFaviconFromCache
import ai.rever.boss.components.events.TerminalEventBus
import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.components.overlays.contextMenu
import ai.rever.boss.components.plugin.panels.left_top.BookmarksDialogProviderImpl
import ai.rever.boss.components.plugin.panels.left_top.createDownloadDataProvider
import ai.rever.boss.plugin.ui.ContextMenuItemData
import ai.rever.boss.components.plugin.providers.DirectoryPickerProviderImpl
import ai.rever.boss.components.plugin.providers.FileSystemDataProviderImpl
import ai.rever.boss.components.plugin.providers.ProjectDataProviderImpl
import ai.rever.boss.plugin.api.ProjectData
import ai.rever.boss.window.Project
import ai.rever.boss.window.selectProjectInWindow
import ai.rever.boss.components.plugin.providers.SplitViewOperationsImpl
import ai.rever.boss.components.plugin.providers.WorkspaceDataProviderImpl
import ai.rever.boss.components.plugin.providers.createLogDataProvider
import ai.rever.boss.components.plugin.providers.createPerformanceDataProvider
import ai.rever.boss.components.plugin.providers.DashboardContentProviderImpl
import ai.rever.boss.components.plugin.panels.right_top.BrowserAccessor
import ai.rever.boss.components.plugin.panels.right_top.storeSplitViewState
import ai.rever.boss.components.plugin.panels.right_top.BrowserIntegration as InternalBrowserIntegration
// DYNAMIC: Tab type registrations moved to dynamic plugins
// import ai.rever.boss.components.plugin.tab_types.registerCodeEditor
// import ai.rever.boss.components.plugin.tab_types.registerTerminalTab
import ai.rever.boss.components.plugin.tab_types.fluck.SecretChangeNotifier
import ai.rever.boss.services.auth.AuthDataProviderImpl
import ai.rever.boss.services.auth.AuthStateManager
import ai.rever.boss.services.auth.PluginStoreApiKeyProviderImpl
import ai.rever.boss.search.SearchRegistryImpl
import ai.rever.boss.plugin.api.SearchProvider
import ai.rever.boss.services.supabase.RoleManagementProviderImpl
import ai.rever.boss.services.supabase.SecretDataProviderImpl
import ai.rever.boss.services.supabase.SupabaseDataProviderImpl
import ai.rever.boss.services.supabase.UserManagementProviderImpl
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.LogSanitizer
import ai.rever.boss.window.WindowProjectState
import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.AuthDataProvider
import ai.rever.boss.plugin.api.FileNodeData
import ai.rever.boss.plugin.api.FileSystemDataProvider
import ai.rever.boss.plugin.api.GitDataProvider
import ai.rever.boss.plugin.api.NodeLoadingStateData
import ai.rever.boss.plugin.api.ContextMenuProvider
import ai.rever.boss.plugin.api.DashboardContentProvider
import ai.rever.boss.plugin.api.LogDataProvider
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginStoreApiKeyProvider
import ai.rever.boss.plugin.api.BrowserIntegration as ApiBrowserIntegration
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginSandboxRef
import ai.rever.boss.plugin.api.RoleManagementProvider
import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.api.SupabaseDataProvider
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabUpdateProviderFactory
import ai.rever.boss.plugin.api.UserManagementProvider
import ai.rever.boss.plugin.api.WorkspaceDataProvider
import ai.rever.boss.plugin.api.ZoomSettingsProvider
import ai.rever.boss.plugin.api.UrlHistoryProvider
import ai.rever.boss.components.plugin.providers.createZoomSettingsProvider
import ai.rever.boss.components.plugin.providers.createUrlHistoryProvider
import ai.rever.boss.components.plugin.providers.createScreenCaptureProvider
import ai.rever.boss.components.plugin.providers.createCoBrowseRtcProvider
import ai.rever.boss.components.plugin.providers.createEditorContentProvider
import ai.rever.boss.plugin.api.EditorContentProvider
import ai.rever.boss.plugin.api.NotificationProvider
import ai.rever.boss.plugin.api.ApplicationEventBus
import ai.rever.boss.plugin.api.PluginStorageFactory
import ai.rever.boss.plugin.api.GenericDialogProvider
import ai.rever.boss.plugin.api.BackgroundTaskHandle
import ai.rever.boss.plugin.api.BackgroundTaskProvider
import ai.rever.boss.plugin.api.CacheProvider
import ai.rever.boss.plugin.api.ClipboardProvider
import ai.rever.boss.plugin.api.DiagnosticEntry
import ai.rever.boss.plugin.api.DiagnosticProvider
import ai.rever.boss.plugin.api.FilePickerProvider
import ai.rever.boss.plugin.api.KeyboardShortcutInfo
import ai.rever.boss.plugin.api.KeyboardShortcutProvider
import ai.rever.boss.plugin.api.NavigationResolverProvider
import ai.rever.boss.plugin.api.NavigationTargetProvider
import ai.rever.boss.plugin.api.ScreenCaptureProvider
import ai.rever.boss.plugin.api.SemanticTokenProvider
import ai.rever.boss.components.plugin.providers.createNotificationProvider
import ai.rever.boss.components.plugin.providers.createClipboardProvider
import ai.rever.boss.components.plugin.providers.createFilePickerProvider
import ai.rever.boss.components.plugin.providers.createNavigationResolverProvider
import ai.rever.boss.components.plugin.providers.createSemanticTokenProvider
import ai.rever.boss.components.plugin.providers.NavigationTargetProviderImpl
import ai.rever.boss.components.plugin.providers.createApplicationEventBus
import ai.rever.boss.components.plugin.providers.createPluginStorageFactory
import ai.rever.boss.components.plugin.providers.createGenericDialogProvider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.Terminal
import ai.rever.boss.plugin.browser.BrowserService

import ai.rever.boss.plugin.sandbox.PluginSandboxManager
import ai.rever.boss.plugin.sandbox.PluginSandboxManagerImpl
import ai.rever.boss.plugin.sandbox.SandboxConfig
import ai.rever.boss.plugin.sandbox.context.SandboxedPanelRegistry
import ai.rever.boss.plugin.sandbox.context.SandboxedPluginContext
import ai.rever.boss.plugin.sandbox.context.SandboxedTabRegistry
import ai.rever.boss.plugin.sandbox.health.PluginHealthSummary
import ai.rever.boss.plugin.sandbox.notification.BossPluginNotificationService
import ai.rever.boss.plugin.sandbox.notification.PluginSandboxNotificationListener
import ai.rever.boss.plugin.sandbox.notification.PluginToastState
import ai.rever.boss.window.WindowGitState
import ai.rever.boss.plugin.loader.PluginLoadException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

class DefaultPlugin(
    override val panelRegistry: PanelRegistry,
    override val tabRegistry: TabRegistry,
    val windowProjectState: WindowProjectState?,
    val windowGitState: WindowGitState? = null,
    private val _windowId: String? = null,
    private val workspaceManager: ai.rever.boss.components.workspaces.WorkspaceManager? = null,
    private val splitViewState: ai.rever.boss.components.window_panel.SplitViewState? = null
) : PluginContext {

    companion object {
        // Plugin IDs for sandboxing - using consistent naming
        private const val PLUGIN_ID_BOOKMARKS = "panel-bookmarks"
        private const val PLUGIN_ID_DOWNLOADS = "panel-downloads"
        private const val PLUGIN_ID_CODEBASE = "panel-codebase"
        private const val PLUGIN_ID_TERMINAL = "panel-terminal"
        private const val PLUGIN_ID_CONSOLE = "panel-console"
        private const val PLUGIN_ID_PERFORMANCE = "panel-performance"
        private const val PLUGIN_ID_GIT_STATUS = "panel-git-status"
        private const val PLUGIN_ID_GIT_LOG = "panel-git-log"
        private const val PLUGIN_ID_TOP_OF_MIND = "panel-top-of-mind"
        private const val PLUGIN_ID_RUN_CONFIGS = "panel-run-configurations"
        private const val PLUGIN_ID_FLUCK = "panel-fluck"
        private const val PLUGIN_ID_LLM_RPA = "panel-llm-rpa"
        private const val PLUGIN_ID_RPA_RECORDER = "panel-rpa-recorder"
        private const val PLUGIN_ID_RPA_ENGINE = "panel-rpa-engine"
        private const val PLUGIN_ID_ADMIN_ROLE_MGMT = "panel-admin-role-management"
        private const val PLUGIN_ID_ROLE_CREATION = "panel-role-creation"
        private const val PLUGIN_ID_SECRET_MANAGER = "panel-secret-manager"
        private const val PLUGIN_ID_USER_SECRET_LIST = "panel-user-secret-list"
        private const val PLUGIN_ID_PLUGIN_MANAGER = "panel-plugin-manager"

        // Tab plugin IDs
        private const val PLUGIN_ID_TAB_FLUCK = "tab-fluck"
        private const val PLUGIN_ID_TAB_CODE_EDITOR = "tab-code-editor"
        private const val PLUGIN_ID_TAB_TERMINAL = "tab-terminal"

        // Persisted plugins loading state
        @Volatile
        private var persistedPluginsLoaded = false

        /**
         * The in-flight persisted-plugin load, joined by [loadExternalPlugins]
         * so the directory scan runs strictly after it. Global (companion) to
         * match [persistedPluginsLoaded]: the persisted load runs ONCE, into
         * the first window's manager — a later window's scan still joins it
         * (its own manager starts empty; populating secondary-window managers
         * from persistence is a pre-existing open question, not changed here).
         */
        @Volatile
        private var persistedPluginsLoadJob: Job? = null

        /**
         * Load persisted plugins. This is called automatically when DynamicPluginManager is first accessed.
         * Platform-specific implementation should set this callback.
         */
        var loadPersistedPluginsInternal: suspend (DynamicPluginManager) -> Unit = { _ ->
            // Default no-op - platform-specific code should set this
        }
    }

    private val logger = BossLogger.forComponent("DefaultPlugin")
    // Lifecycle-aware scope for long-running operations like dynamic panel registration
    // This scope should be cancelled when the plugin is disposed
    override val pluginScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ============================================================
    // PLUGIN-TO-PLUGIN API REGISTRY
    // Enables plugins to expose and consume APIs from other plugins
    // ============================================================

    /**
     * Registry mapping API interface classes to their implementations.
     * Thread-safe using ConcurrentHashMap for concurrent access from plugins.
     */
    private val apiRegistry = java.util.concurrent.ConcurrentHashMap<Class<*>, Any>()

    /**
     * Get a plugin API by its interface type.
     */
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getPluginAPI(apiClass: Class<T>): T? {
        return apiRegistry[apiClass] as? T
    }

    /**
     * Register a plugin API for other plugins to consume.
     * The API is registered under all interfaces it implements.
     */
    /**
     * Bumped whenever a plugin API registers. Compose UI that gates on
     * [getPluginAPI] availability observes this to self-heal once the
     * (asynchronously loading) plugin registers its API.
     */
    private val _apiRegistryVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    val apiRegistryVersion: StateFlow<Int> get() = _apiRegistryVersion

    override fun registerPluginAPI(api: Any) {
        // Register under all interfaces implemented by the API
        api::class.java.interfaces.forEach { iface ->
            apiRegistry[iface] = api
            logger.debug(LogCategory.SYSTEM, "Registered plugin API", mapOf(
                "interface" to iface.name,
                "implementation" to api::class.java.name
            ))
        }
        // Registration happens asynchronously during plugin startup; bump the
        // observable version so Compose readers (EditorAPIAccess.rememberProvider)
        // re-check availability instead of staying on their "not loaded" branch.
        _apiRegistryVersion.value += 1

        // Also register under the concrete class for direct lookups
        apiRegistry[api::class.java] = api
    }

    // Sandbox manager for plugin crash isolation
    private val sandboxManager: PluginSandboxManager = PluginSandboxManagerImpl()

    /**
     * Health summary across all sandboxed plugins.
     * Use this to monitor plugin health from the UI.
     */
    val pluginHealthSummary: StateFlow<PluginHealthSummary> = sandboxManager.healthSummary

    /**
     * Manager for dynamic plugin loading and unloading at runtime.
     * Use this to install, uninstall, enable, or disable plugins dynamically.
     */
    val dynamicPluginManager: DynamicPluginManager by lazy {
        // Wire out-of-process plugin spawner when running in KERNEL mode.
        // Uses reflection to avoid hard dependency on boss-process-manager
        // (which is excluded on Windows ARM64).
        val oopSpawner: OutOfProcessPluginSpawner? = try {
            val bootstrapCls = Class.forName("ai.rever.boss.kernel.KernelBootstrap")
            // KernelBootstrap is a singleton-like — check if processSpawner is available
            val spawnerCls = Class.forName("ai.rever.boss.components.plugin.OutOfProcessPluginSpawnerImpl")
            val processSpawnerCls = Class.forName("ai.rever.boss.process.ProcessSpawner")

            // Only wire if BOSS_MODE=KERNEL
            val bossMode = System.getenv("BOSS_MODE")
                ?: try {
                    val cfgCls = Class.forName("ai.rever.boss.config.ConfigLoader")
                    val cfgInstance = cfgCls.getDeclaredField("INSTANCE").get(null)
                    cfgCls.getMethod("getConfig", String::class.java, String::class.java).invoke(cfgInstance, "BOSS_MODE", null) as? String
                } catch (e: Exception) {
                    logger.warn(LogCategory.SYSTEM, "OOP spawner: ConfigLoader failed", mapOf("error" to e.toString()))
                    null
                }
            logger.info(LogCategory.SYSTEM, "OOP spawner: BOSS_MODE resolved", mapOf("bossMode" to (bossMode ?: "null")))
            if (bossMode == "KERNEL") {
                // Get kernel IPC address from KernelBootstrap.instance (not env var,
                // since BOSS_KERNEL_IPC_ADDR is only set for child processes)
                val kernelAddr = System.getenv("BOSS_KERNEL_IPC_ADDR")
                    ?: try {
                        val companionCls = Class.forName("ai.rever.boss.kernel.KernelBootstrap\$Companion")
                        val companion = bootstrapCls.getDeclaredField("Companion").get(null)
                        val getInstance = companionCls.getMethod("getInstance")
                        val kernelInstance = getInstance.invoke(companion)
                        logger.info(LogCategory.SYSTEM, "OOP spawner: KernelBootstrap.instance", mapOf("isNull" to (kernelInstance == null)))
                        if (kernelInstance != null) {
                            bootstrapCls.getMethod("getKernelAddress").invoke(kernelInstance) as? String
                        } else null
                    } catch (e: Exception) {
                        logger.warn(LogCategory.SYSTEM, "OOP spawner: kernel addr failed", mapOf("error" to e.toString()))
                        null
                    }
                    ?: ""
                logger.info(LogCategory.SYSTEM, "OOP spawner: kernelAddr resolved", mapOf("addr" to kernelAddr))
                if (kernelAddr.isNotEmpty()) {
                    val processSpawner = processSpawnerCls.getConstructor(String::class.java, java.io.File::class.java)
                        .newInstance(kernelAddr, java.io.File(
                            try {
                                val dirsCls2 = Class.forName("ai.rever.boss.plugin.pathutils.BossDirectories")
                                val dirsInst2 = dirsCls2.getDeclaredField("INSTANCE").get(null)
                                (dirsCls2.getMethod("getRootDir").invoke(dirsInst2) as java.io.File).absolutePath
                            } catch (_: Exception) { "${System.getProperty("user.home")}/.boss" },
                            "logs"
                        ))
                    spawnerCls.getConstructor(
                        processSpawnerCls,
                        String::class.java,
                        String::class.java
                    ).newInstance(
                        processSpawner,
                        windowId ?: "",
                        windowProjectState?.selectedProject?.value?.path ?: ""
                    ) as OutOfProcessPluginSpawner
                } else null
            } else null
        } catch (e: ClassNotFoundException) {
            logger.warn(LogCategory.SYSTEM, "OOP spawner: class not found", mapOf("error" to e.message))
            null
        } catch (e: NoClassDefFoundError) {
            logger.warn(LogCategory.SYSTEM, "OOP spawner: class def not found", mapOf("error" to e.message))
            null
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "OOP spawner: unexpected error", mapOf("error" to e.toString()), e)
            null
        }

        if (oopSpawner != null) {
            logger.info(LogCategory.SYSTEM, "OutOfProcessPluginSpawner created successfully")
        } else {
            logger.warn(LogCategory.SYSTEM, "OutOfProcessPluginSpawner is null — OOP plugins will run in-process")
        }

        val manager = DynamicPluginManager(
            panelRegistry = panelRegistry,
            tabRegistry = tabRegistry,
            sandboxManager = sandboxManager,
            createSandboxedContext = { pluginId, config ->
                createSandboxedContext(pluginId, config)
            },
            outOfProcessSpawner = oopSpawner,
        )

        // Load persisted plugins on first access (only once globally).
        // The Job is kept so loadExternalPlugins can sequence after it —
        // the two passes cover overlapping jar sets and must not race.
        if (!persistedPluginsLoaded) {
            persistedPluginsLoaded = true
            persistedPluginsLoadJob = pluginScope.launch {
                loadPersistedPluginsInternal(manager)
            }
        }

        manager
    }

    /**
     * Toast state for plugin notifications.
     * Use this with PluginToastHost in your UI to display plugin status notifications.
     */
    val pluginToastState: PluginToastState = PluginToastState(pluginScope)

    // Notification service and listener for plugin events
    private val notificationService = BossPluginNotificationService(
        toastController = pluginToastState,
        onDisablePlugin = { pluginId ->
            pluginScope.launch {
                sandboxManager.disablePlugin(pluginId)
            }
        },
        onEnablePlugin = { pluginId ->
            pluginScope.launch {
                sandboxManager.enablePlugin(pluginId)
            }
        }
    )

    private val notificationListener = PluginSandboxNotificationListener(notificationService).also {
        (sandboxManager as? PluginSandboxManagerImpl)?.addListener(it)
    }

    // No sandbox for the default context (backward compatibility)
    override val sandbox: PluginSandboxRef? = null

    // Browser service for plugins needing embedded browser capabilities.
    // Automation (isolated/headless sessions, auth seeding, named profiles) is
    // folded into this same service — see BrowserConfig.profileName/ephemeralProfile/auth.
    override val browserService: BrowserService? = getBrowserServiceInstance()

    // Git data provider for plugins that display git information
    override val gitDataProvider: GitDataProvider? by lazy {
        if (windowGitState != null) {
            GitDataProviderImpl(windowGitState) { _windowId }
        } else {
            null
        }
    }

    // Window ID for window-scoped operations
    override val windowId: String?
        get() = _windowId

    // Project path for project-specific operations
    override val projectPath: String?
        get() = windowProjectState?.selectedProject?.value?.path

    // Auth data provider for plugins that need authentication state
    override val authDataProvider: AuthDataProvider by lazy {
        AuthDataProviderImpl()
    }

    // User management provider for admin plugins
    override val userManagementProvider: UserManagementProvider by lazy {
        UserManagementProviderImpl()
    }

    // Role management provider for admin plugins
    override val roleManagementProvider: RoleManagementProvider by lazy {
        RoleManagementProviderImpl()
    }

    // Generic Supabase data provider for plugins
    override val supabaseDataProvider: SupabaseDataProvider by lazy {
        SupabaseDataProviderImpl()
    }

    // File system data provider for codebase plugin
    override val fileSystemDataProvider: FileSystemDataProvider by lazy {
        FileSystemDataProviderImpl()
    }

    // Workspace data provider for plugins that manage workspaces
    override val workspaceDataProvider: WorkspaceDataProvider? by lazy {
        if (workspaceManager != null) {
            WorkspaceDataProviderImpl(workspaceManager)
        } else {
            null
        }
    }

    // Bookmark data provider is now null - bookmarks plugin is self-contained
    // The bookmarks plugin creates its own internal BookmarkManager
    override val bookmarkDataProvider: ai.rever.boss.plugin.api.BookmarkDataProvider?
        get() = null

    // ============================================================
    // SEARCH PROVIDER REGISTRATION
    // Enables plugins to contribute to global search results
    // ============================================================

    /**
     * Register a search provider for global search.
     * Plugins can implement SearchProvider to contribute results to Spotlight.
     */
    override fun registerSearchProvider(provider: SearchProvider) {
        SearchRegistryImpl.registerProvider(provider)
        logger.debug(LogCategory.SYSTEM, "Search provider registered", mapOf(
            "providerId" to provider.providerId
        ))
    }

    /**
     * Unregister a search provider.
     */
    override fun unregisterSearchProvider(providerId: String) {
        SearchRegistryImpl.unregisterProvider(providerId)
        logger.debug(LogCategory.SYSTEM, "Search provider unregistered", mapOf(
            "providerId" to providerId
        ))
    }

    // ============================================================
    // MCP TOOL PROVIDER REGISTRATION
    // Plugins contribute tools to the `boss` MCP server; the terminal-tab
    // plugin bridges McpToolRegistryImpl onto the live MCP server.
    // ============================================================

    override fun registerMcpToolProvider(provider: ai.rever.boss.plugin.api.McpToolProvider) {
        ai.rever.boss.mcp.McpToolRegistryImpl.registerProvider(provider)
        logger.debug(LogCategory.SYSTEM, "MCP tool provider registered", mapOf(
            "providerId" to provider.providerId
        ))
    }

    override fun unregisterMcpToolProvider(providerId: String) {
        ai.rever.boss.mcp.McpToolRegistryImpl.unregisterProvider(providerId)
        logger.debug(LogCategory.SYSTEM, "MCP tool provider unregistered", mapOf(
            "providerId" to providerId
        ))
    }

    override val mcpToolRegistry: ai.rever.boss.plugin.api.McpToolRegistry
        get() = ai.rever.boss.mcp.McpToolRegistryImpl

    // ============================================================
    // UI EXTENSION REGISTRIES
    // Panel top-bar menus, settings pages, deep-link actions, global
    // shortcuts, status-bar widgets. Backed by the process-wide
    // registries in ai.rever.boss.components.plugin.registries;
    // TrackingPluginContext auto-unregisters on disable/unload.
    // ============================================================

    override fun registerPanelMenuContribution(contribution: ai.rever.boss.plugin.api.PanelMenuContribution) {
        ai.rever.boss.components.plugin.registries.PanelMenuRegistryImpl.register(contribution)
    }

    override fun unregisterPanelMenuContribution(contributionId: String) {
        ai.rever.boss.components.plugin.registries.PanelMenuRegistryImpl.unregister(contributionId)
    }

    override fun registerSettingsPage(provider: ai.rever.boss.plugin.api.SettingsPageProvider) {
        ai.rever.boss.components.plugin.registries.SettingsPageRegistryImpl.register(provider)
    }

    override fun unregisterSettingsPage(pageId: String) {
        ai.rever.boss.components.plugin.registries.SettingsPageRegistryImpl.unregister(pageId)
    }

    override fun registerDeepLinkActionHandler(handler: ai.rever.boss.plugin.api.DeepLinkActionHandler) {
        ai.rever.boss.components.plugin.registries.DeepLinkActionRegistryImpl.register(handler)
    }

    override fun unregisterDeepLinkActionHandler(handlerId: String) {
        ai.rever.boss.components.plugin.registries.DeepLinkActionRegistryImpl.unregister(handlerId)
    }

    override fun registerShortcutActionProvider(provider: ai.rever.boss.plugin.api.ShortcutActionProvider) {
        ai.rever.boss.components.plugin.registries.PluginShortcutRegistryImpl.register(provider)
    }

    override fun unregisterShortcutActionProvider(providerId: String) {
        ai.rever.boss.components.plugin.registries.PluginShortcutRegistryImpl.unregister(providerId)
    }

    override fun registerStatusBarItem(provider: ai.rever.boss.plugin.api.StatusBarItemProvider) {
        ai.rever.boss.components.plugin.registries.StatusBarRegistryImpl.register(provider)
    }

    override fun unregisterStatusBarItem(itemId: String) {
        ai.rever.boss.components.plugin.registries.StatusBarRegistryImpl.unregister(itemId)
    }

    // Split view operations for plugins that need tab/panel operations
    override val splitViewOperations: SplitViewOperations? by lazy {
        if (splitViewState != null && _windowId != null) {
            SplitViewOperationsImpl(splitViewState, _windowId)
        } else {
            null
        }
    }

    // Active tabs provider for topofmind plugin
    override val activeTabsProvider: ActiveTabsProvider? by lazy {
        if (splitViewState != null && workspaceManager != null) {
            ApiActiveTabsProviderAdapter(splitViewState, workspaceManager, _windowId ?: "unknown", pluginScope)
        } else {
            null
        }
    }

    // Run configuration data provider for run-configurations plugin
    override val runConfigurationDataProvider: ai.rever.boss.plugin.api.RunConfigurationDataProvider by lazy {
        ai.rever.boss.run.RunConfigurationDataProviderImpl()
    }

    // Performance data provider for performance plugin
    override val performanceDataProvider: ai.rever.boss.plugin.api.PerformanceDataProvider by lazy {
        createPerformanceDataProvider()
    }

    // Download data provider for downloads plugin
    override val downloadDataProvider: ai.rever.boss.plugin.api.DownloadDataProvider by lazy {
        createDownloadDataProvider()
    }

    // Secret data provider for secret manager and user secret list plugins
    override val secretDataProvider: ai.rever.boss.plugin.api.SecretDataProvider by lazy {
        ai.rever.boss.services.supabase.SecretDataProviderImpl()
    }

    // Panel event provider for plugins that need to trigger panel events
    override val panelEventProvider: ai.rever.boss.plugin.api.PanelEventProvider by lazy {
        ai.rever.boss.components.plugin.providers.PanelEventProviderImpl()
    }

    // Settings provider for plugins that need to open settings
    override val settingsProvider: ai.rever.boss.plugin.api.SettingsProvider by lazy {
        ai.rever.boss.components.plugin.providers.SettingsProviderImpl()
    }

    // Context menu provider for plugins that need context menu functionality
    override val contextMenuProvider: ContextMenuProvider by lazy {
        DefaultContextMenuProvider()
    }

    // Log data provider for console plugin
    override val logDataProvider: LogDataProvider by lazy {
        createLogDataProvider()
    }

    // Plugin Store API key provider for secret manager and other plugins
    override val pluginStoreApiKeyProvider: PluginStoreApiKeyProvider by lazy {
        PluginStoreApiKeyProviderImpl()
    }

    // Tab update provider factory - delegates to global TabUpdateRegistry
    // This allows dynamic plugins to update tab titles, favicons, etc.
    // Individual BossTabsComponent instances register with TabUpdateRegistry
    override val tabUpdateProviderFactory: TabUpdateProviderFactory
        get() = TabUpdateRegistry

    // Dashboard content provider for browser plugins showing about:blank
    override val dashboardContentProvider: DashboardContentProvider by lazy {
        DashboardContentProviderImpl()
    }

    // Zoom settings provider for per-domain zoom persistence
    override val zoomSettingsProvider: ZoomSettingsProvider by lazy {
        createZoomSettingsProvider()
    }

    // URL history provider for browser autocomplete
    override val urlHistoryProvider: UrlHistoryProvider by lazy {
        createUrlHistoryProvider()
    }

    // Screen capture provider for browser plugins
    override val screenCaptureProvider: ScreenCaptureProvider by lazy {
        createScreenCaptureProvider()
    }

    // WebRTC peer provider for low-latency co-browse transport (null if unsupported)
    override val coBrowseRtcProvider: ai.rever.boss.plugin.api.CoBrowseRtcProvider? by lazy {
        createCoBrowseRtcProvider()
    }

    // Editor content provider for code editor tab plugins
    override val editorContentProvider: EditorContentProvider? by lazy {
        createEditorContentProvider()
    }

    // Phase 4: Notification provider for plugin toasts/notifications
    override val notificationProvider: NotificationProvider by lazy {
        createNotificationProvider(pluginToastState)
    }

    // Phase 4: Application event bus for state change events
    override val applicationEventBus: ApplicationEventBus by lazy {
        createApplicationEventBus(pluginScope)
    }

    // Phase 4: Plugin storage factory for persistent data
    override val pluginStorageFactory: PluginStorageFactory by lazy {
        createPluginStorageFactory()
    }

    // Phase 4: Generic dialog provider for common dialogs
    override val genericDialogProvider: GenericDialogProvider? by lazy {
        createGenericDialogProvider()
    }

    // Navigation resolver provider for PSI-based code navigation in dynamic plugins
    override val navigationResolverProvider: NavigationResolverProvider? by lazy {
        createNavigationResolverProvider()
    }

    // Semantic token provider for PSI-based semantic highlighting in dynamic plugins
    override val semanticTokenProvider: SemanticTokenProvider? by lazy {
        createSemanticTokenProvider()
    }

    // Navigation target provider for cursor positioning after file navigation in dynamic plugins
    override val navigationTargetProvider: NavigationTargetProvider
        get() = NavigationTargetProviderImpl

    // Clipboard provider for plugins that need clipboard access
    override val clipboardProvider: ClipboardProvider? by lazy {
        createClipboardProvider()
    }

    // File picker provider for plugins that need file open/save dialogs
    override val filePickerProvider: FilePickerProvider? by lazy {
        createFilePickerProvider()
    }

    // Keyboard shortcut provider for plugins that need shortcut information
    override val keyboardShortcutProvider: KeyboardShortcutProvider by lazy {
        DefaultKeyboardShortcutProvider()
    }

    // Cache provider for plugin cache management
    override val cacheProvider: CacheProvider by lazy {
        DefaultCacheProvider()
    }

    // Background task provider for structured task management
    override val backgroundTaskProvider: BackgroundTaskProvider by lazy {
        DefaultBackgroundTaskProvider(pluginScope)
    }

    // Diagnostic provider for plugin diagnostic reporting
    override val diagnosticProvider: DiagnosticProvider by lazy {
        DefaultDiagnosticProvider()
    }

    // Directory picker provider for codebase plugin's "Open Project" feature
    override val directoryPickerProvider: ai.rever.boss.plugin.api.DirectoryPickerProvider by lazy {
        DirectoryPickerProviderImpl()
    }

    // Project data provider for managing recent projects
    override val projectDataProvider: ai.rever.boss.plugin.api.ProjectDataProvider by lazy {
        ProjectDataProviderImpl(windowProjectState)
    }

    /**
     * Create a sandboxed plugin context for a specific plugin.
     *
     * The sandboxed context provides:
     * - Isolated coroutine scope (errors don't propagate)
     * - Health monitoring and automatic restart
     * - Error boundary integration for UI components
     *
     * @param pluginId Unique identifier for the plugin
     * @param config Optional sandbox configuration
     * @return A sandboxed PluginContext for the plugin
     */
    fun createSandboxedContext(
        pluginId: String,
        config: SandboxConfig = SandboxConfig()
    ): PluginContext {
        val sandbox = sandboxManager.createSandbox(pluginId, config)

        // Start the sandbox asynchronously to avoid blocking UI thread
        pluginScope.launch {
            sandbox.start().onFailure { error ->
                logger.error(LogCategory.SYSTEM, "Failed to start sandbox", mapOf(
                    "pluginId" to pluginId
                ), error)
            }
        }

        // Create wrapped registries that record errors to the sandbox
        val sandboxedPanelRegistry = SandboxedPanelRegistry(sandbox, panelRegistry)
        val sandboxedTabRegistry = SandboxedTabRegistry(sandbox, tabRegistry)

        return SandboxedPluginContext(
            _sandbox = sandbox,
            delegate = this,
            sandboxedPanelRegistry = sandboxedPanelRegistry,
            sandboxedTabRegistry = sandboxedTabRegistry
        )
    }

    /**
     * Get the sandbox for a specific plugin.
     *
     * @param pluginId Plugin identifier
     * @return The sandbox, or null if not found
     */
    fun getPluginSandbox(pluginId: String) = sandboxManager.getSandbox(pluginId)

    /**
     * Restart a sandboxed plugin.
     *
     * @param pluginId Plugin identifier
     * @return Result indicating success or failure
     */
    suspend fun restartPlugin(pluginId: String) = sandboxManager.restartPlugin(pluginId)

    /**
     * Disable a sandboxed plugin.
     * Disabled plugins will not auto-restart and show a disabled fallback UI.
     *
     * @param pluginId Plugin identifier
     * @return Result indicating success or failure
     */
    suspend fun disablePlugin(pluginId: String) = sandboxManager.disablePlugin(pluginId)

    /**
     * Enable a previously disabled plugin.
     *
     * @param pluginId Plugin identifier
     * @return Result indicating success or failure
     */
    suspend fun enablePlugin(pluginId: String) = sandboxManager.enablePlugin(pluginId)

    /**
     * Check if a plugin is disabled.
     *
     * @param pluginId Plugin identifier
     * @return True if the plugin is disabled
     */
    fun isPluginDisabled(pluginId: String) = sandboxManager.isPluginDisabled(pluginId)

    init {
        logger.info(LogCategory.SYSTEM, "Initializing DefaultPlugin with sandboxed contexts")

        // ============================================================
        // REGISTER PLUGIN LOADER DELEGATE
        // This allows dynamic plugins (like plugin-manager) to interact with the plugin system
        // ============================================================
        PluginLoaderDelegateSetup.register(this, dynamicPluginManager)

        // ============================================================
        // SANDBOXED PANEL PLUGINS
        // Each plugin gets its own sandbox for crash isolation
        // NOTE: Most panel plugins are now loaded dynamically from JARs.
        // Only Plugin Manager remains bundled.
        // ============================================================

        // DYNAMIC: Bookmarks panel - loaded from boss-plugin-bookmarks JAR
        // val bookmarksContext = createSandboxedContext(PLUGIN_ID_BOOKMARKS)
        // BookmarksPanelPlugin.registerWithProviders(...)

        // DYNAMIC: Downloads panel - loaded from boss-plugin-downloads JAR
        // val downloadsContext = createSandboxedContext(PLUGIN_ID_DOWNLOADS)
        // DownloadsPanelPlugin.register(...)

        // DYNAMIC: CodeBase panel - loaded from boss-plugin-codebase JAR
        // val codebaseContext = createSandboxedContext(PLUGIN_ID_CODEBASE)
        // CodeBasePanelPlugin.registerWithProviders(...)

        // DYNAMIC: Terminal panel - loaded from boss-plugin-terminal JAR
        // val terminalPanelContext = createSandboxedContext(PLUGIN_ID_TERMINAL)
        // TerminalPanelPlugin.registerWithProviders(...)

        // DYNAMIC: Console panel - loaded from boss-plugin-console JAR
        // val consoleContext = createSandboxedContext(PLUGIN_ID_CONSOLE)
        // ConsolePanelPlugin.register(consoleContext)

        // DYNAMIC: Performance panel - loaded from boss-plugin-performance JAR
        // val performanceContext = createSandboxedContext(PLUGIN_ID_PERFORMANCE)
        // PerformancePanelPlugin.register(performanceContext)

        // DYNAMIC: Git panels - loaded from boss-plugin-git-log and boss-plugin-git-status JARs
        // registerGitPanels()

        // DYNAMIC: Top of Mind panel - loaded from boss-plugin-topofmind JAR
        // val topOfMindContext = createSandboxedContext(PLUGIN_ID_TOP_OF_MIND)
        // TopOfMindPanelPlugin.registerWithProviders(...)

        // DYNAMIC: Run Configurations plugin - loaded from boss-plugin-run-configurations JAR
        // val runConfigsContext = createSandboxedContext(PLUGIN_ID_RUN_CONFIGS)
        // RunConfigurationsPanelPlugin.register(...)

        // DYNAMIC: Fluck (ChatGPT) panel - loaded from boss-plugin-fluck JAR
        // val fluckPanelContext = createSandboxedContext(PLUGIN_ID_FLUCK)
        // FluckPanelPlugin.registerWithProviders(...)

        // DYNAMIC: LLM RPA panel - loaded from boss-plugin-llmrpa JAR
        // val llmRpaContext = createSandboxedContext(PLUGIN_ID_LLM_RPA)
        // LLMRpaPanelPlugin.register(...)

        // DYNAMIC: RPA Recorder panel - loaded from boss-plugin-rparecorder JAR
        // val rpaRecorderContext = createSandboxedContext(PLUGIN_ID_RPA_RECORDER)
        // RpaRecorderPanelPlugin.register(...)

        // DYNAMIC: RPA Engine panel - loaded from boss-plugin-rpaengine JAR
        // val rpaEngineContext = createSandboxedContext(PLUGIN_ID_RPA_ENGINE)
        // RpaEnginePanelPlugin.register(...)

        // ============================================================
        // BUNDLED PLUGIN: Plugin Manager (DISABLED - using dynamic plugin instead)
        // This is the ONLY bundled panel plugin - used for managing dynamic plugins
        // ============================================================
        // val pluginManagerContext = createSandboxedContext(PLUGIN_ID_PLUGIN_MANAGER)
        // PluginManagerSetup.registerPluginManagerPanel(
        //     pluginManagerContext,
        //     dynamicPluginManager,
        //     activeTabsProvider
        // )

        // ============================================================
        // DYNAMIC PANEL PLUGINS (loaded from JARs)
        // Previously registered based on auth/state, now loaded dynamically
        // ============================================================

        // DYNAMIC: Admin Role Management panel - loaded from boss-plugin-admin-role-management JAR
        // registerAdminRoleManagementPlugin()

        // DYNAMIC: Role Creation panel - loaded from boss-plugin-role-creation JAR
        // registerRoleCreationPlugin()

        // DYNAMIC: Secret Manager panel - loaded from boss-plugin-secret-manager JAR
        // registerSecretManagerPlugin()

        // DYNAMIC: User Secret List panel - loaded from boss-plugin-user-secret-list JAR
        // registerUserSecretListPlugin()

        // ============================================================
        // TAB TYPE PLUGINS
        // ============================================================

        // DYNAMIC: Tab types moved to dynamic plugins loaded from ~/.boss/plugins/
        // registerFluck() // DYNAMIC: fluck-browser plugin
        // registerCodeEditor() // DYNAMIC: editor-tab plugin
        // registerTerminalTab() // DYNAMIC: terminal-tab plugin

        // ============================================================
        // EXTERNAL PLUGINS (loaded from ~/.boss/plugins/)
        // ============================================================
        loadExternalPlugins()

        // ============================================================
        // KERNEL MODE: Register gRPC services for out-of-process plugins
        // ============================================================
        registerKernelPluginServices()

        logger.info(LogCategory.SYSTEM, "DefaultPlugin initialization complete", mapOf(
            "sandboxedPlugins" to sandboxManager.getAllSandboxes().size
        ))
    }

    /**
     * Dispose the plugin and cancel all coroutines
     * Should be called when the plugin is no longer needed
     */
    fun dispose() {
        // Dispose dynamic plugin manager and sandbox manager
        runBlocking {
            dynamicPluginManager.dispose()
            sandboxManager.dispose()
        }
        pluginScope.cancel()
    }

    /**
     * The in-flight external-plugins directory scan launched from init via
     * [loadExternalPlugins]; null when the scan was skipped (no plugin dir).
     * Joined by [awaitInitialPluginLoad].
     */
    @Volatile
    private var externalPluginsScanJob: Job? = null

    /**
     * Suspends until startup plugin loading has finished: the persisted-plugins
     * pass and the external directory scan (which itself sequences after the
     * persisted pass). Both are launched asynchronously from init, so an early
     * read of [DynamicPluginManager.getInstalledPlugins] sees an empty registry
     * on every startup — callers that treat "no plugins installed" as meaningful
     * (e.g. the Toolbox setup wizard) must await this first.
     */
    suspend fun awaitInitialPluginLoad() {
        // Force the lazy manager so the persisted load is guaranteed to have been
        // kicked off before we join — keeps this method correct even if init's own
        // eager touch of dynamicPluginManager is ever refactored away.
        dynamicPluginManager
        persistedPluginsLoadJob?.join()
        externalPluginsScanJob?.join()
    }

    /**
     * Load external plugins from the local plugins directory (~/.boss/plugins/).
     *
     * This scans for JAR files and installs them via DynamicPluginManager.
     */
    private fun loadExternalPlugins() {
        val pluginDir = BossDirectories.resolve("plugins")

        if (!pluginDir.exists() || !pluginDir.isDirectory) {
            logger.debug(LogCategory.SYSTEM, "External plugins directory not found", mapOf(
                "path" to pluginDir.absolutePath
            ))
            return
        }

        // Scan asynchronously, but strictly AFTER the persisted pass: the two
        // passes cover overlapping jar sets, and racing them made whichever
        // pass lost log "Plugin already loaded" as a failure for most plugins
        // every startup — and let this scan force-load (enabled=true) plugins
        // whose persisted entry said enabled=false.
        externalPluginsScanJob = pluginScope.launch {
            val manager = dynamicPluginManager // first access starts the persisted load
            persistedPluginsLoadJob?.join()

            // List the directory only NOW: the background system-plugin
            // updater can replace jars while startup is in flight — a listing
            // captured at init would try already-deleted files and never see
            // freshly downloaded ones.
            val jarFiles = pluginDir.listFiles { file ->
                file.isFile && file.extension == "jar"
                        // Skip microkernel runtime — it's a classpath dependency for OOP plugins, not a loadable plugin
                        && !file.name.startsWith(MicrokernelRuntime.ARTIFACT_PREFIX)
            } ?: emptyArray()

            if (jarFiles.isEmpty()) {
                logger.debug(LogCategory.SYSTEM, "No external plugins found", mapOf(
                    "path" to pluginDir.absolutePath
                ))
                return@launch
            }

            logger.info(LogCategory.SYSTEM, "Loading external plugins", mapOf(
                "count" to jarFiles.size,
                "path" to pluginDir.absolutePath
            ))

            // The persisted pass is authoritative for every jar it got into
            // pluginStates — loaded ones and binary-incompatibility rejections
            // (tracked as DISABLED); don't retry either. Other load failures
            // don't land in pluginStates, so the scan may retry those and
            // re-log the real error. This scan otherwise only picks up jars
            // dropped into the directory manually.
            val trackedJarPaths = manager.pluginStates.value.values.map { it.jarPath }.toSet()

            for (jarFile in jarFiles) {
                if (jarFile.absolutePath in trackedJarPaths) continue
                try {
                    logger.info(LogCategory.SYSTEM, "Installing external plugin", mapOf(
                        "file" to jarFile.name
                    ))

                    val result = manager.installPlugin(jarFile.absolutePath)

                    if (result.isSuccess) {
                        val info = result.getOrThrow()
                        logger.info(LogCategory.SYSTEM, "External plugin loaded successfully", mapOf(
                            "pluginId" to info.manifest.pluginId,
                            "version" to info.manifest.version,
                            "displayName" to info.manifest.displayName
                        ))
                    } else if (result.exceptionOrNull()?.message?.startsWith(PluginLoadException.ALREADY_LOADED_PREFIX) == true) {
                        // A second jar for a plugin that's already running — a
                        // stale old version left in the directory, not a failure.
                        logger.info(LogCategory.SYSTEM, "Skipping duplicate jar for already-loaded plugin", mapOf(
                            "file" to jarFile.name
                        ))
                    } else {
                        logger.error(LogCategory.SYSTEM, "Failed to load external plugin", mapOf(
                            "file" to jarFile.name,
                            "error" to (result.exceptionOrNull()?.message ?: "unknown")
                        ))
                    }
                } catch (e: Exception) {
                    logger.error(LogCategory.SYSTEM, "Exception loading external plugin", mapOf(
                        "file" to jarFile.name
                    ), e)
                }
            }
        }
    }

    // ============================================================
    // REMOVED BUNDLED PLUGINS
    // The following registration methods have been removed as these
    // panels are now loaded dynamically from JARs:
    // - registerSecretManagerPlugin()
    // - registerUserSecretListPlugin()
    // - registerAdminRoleManagementPlugin()
    // - registerRoleCreationPlugin()
    // - registerGitPanels()
    // ============================================================

    /**
     * Register kernel-side gRPC services when running in KERNEL mode.
     * Uses reflection to avoid hard dependency on boss-process-manager
     * (excluded on Windows ARM64).
     */
    private fun registerKernelPluginServices() {
        try {
            val bossMode = System.getenv("BOSS_MODE")
                ?: try {
                    val configCls = Class.forName("ai.rever.boss.config.ConfigLoader")
                    val cfgInst = configCls.getDeclaredField("INSTANCE").get(null)
                    configCls.getMethod("getConfig", String::class.java, String::class.java).invoke(cfgInst, "BOSS_MODE", null) as? String
                } catch (_: Exception) { null }
            if (bossMode != "KERNEL") return

            val bootstrapCls = Class.forName("ai.rever.boss.kernel.KernelBootstrap")
            val companionCls = Class.forName("ai.rever.boss.kernel.KernelBootstrap\$Companion")
            val companion = bootstrapCls.getDeclaredField("Companion").get(null)
            val getInstance = companionCls.getMethod("getInstance")
            val kernelBootstrap = getInstance.invoke(companion) ?: run {
                logger.info(LogCategory.SYSTEM, "KernelBootstrap not yet initialized — skipping service registration")
                return
            }

            val registerMethod = bootstrapCls.getMethod(
                "registerPluginServices",
                ai.rever.boss.plugin.api.PerformanceDataProvider::class.java,
                ai.rever.boss.plugin.api.DownloadDataProvider::class.java,
                ai.rever.boss.plugin.api.GitDataProvider::class.java,
                ai.rever.boss.plugin.api.LogDataProvider::class.java,
                ai.rever.boss.plugin.api.ActiveTabsProvider::class.java,
                ai.rever.boss.plugin.api.SecretDataProvider::class.java,
                ai.rever.boss.plugin.api.SupabaseDataProvider::class.java,
                ai.rever.boss.plugin.api.SplitViewOperations::class.java,
                ai.rever.boss.plugin.api.ContextMenuProvider::class.java,
                ai.rever.boss.plugin.api.RunConfigurationDataProvider::class.java,
                ai.rever.boss.plugin.api.PanelEventProvider::class.java,
                ai.rever.boss.plugin.api.RoleManagementProvider::class.java,
                ai.rever.boss.plugin.api.DirectoryPickerProvider::class.java,
                ai.rever.boss.plugin.api.ProjectDataProvider::class.java,
                ai.rever.boss.plugin.api.NotificationProvider::class.java,
            )

            registerMethod.invoke(
                kernelBootstrap,
                performanceDataProvider,
                downloadDataProvider,
                gitDataProvider,
                logDataProvider,
                activeTabsProvider,
                secretDataProvider,
                supabaseDataProvider,
                splitViewOperations,
                contextMenuProvider,
                runConfigurationDataProvider,
                panelEventProvider,
                roleManagementProvider,
                directoryPickerProvider,
                projectDataProvider,
                notificationProvider,
            )

            logger.info(LogCategory.SYSTEM, "Kernel plugin gRPC services registered")
        } catch (_: ClassNotFoundException) {
            // Not in KERNEL mode or boss-process-manager not on classpath
        } catch (_: NoClassDefFoundError) {
            // Missing dependency
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Failed to register kernel plugin services", error = e)
        }
    }
}



/**
 * Adapter that implements the plugin-api ActiveTabsProvider interface
 * by wrapping the SplitViewState for tab collection.
 */
private class ApiActiveTabsProviderAdapter(
    private val splitViewState: ai.rever.boss.components.window_panel.SplitViewState,
    private val workspaceManager: ai.rever.boss.components.workspaces.WorkspaceManager,
    private val windowId: String,
    private val scope: CoroutineScope
) : ActiveTabsProvider {

    private val tabsLogger = BossLogger.forComponent("ActiveTabsProvider")
    private val _activeTabs = kotlinx.coroutines.flow.MutableStateFlow<List<ActiveTabData>>(emptyList())
    override val activeTabs: kotlinx.coroutines.flow.StateFlow<List<ActiveTabData>> = _activeTabs

    init {
        // Start polling loop (like bundled LLMRpaIntegration.kt does)
        // This ensures dynamic plugins receive tab updates
        scope.launch {
            var consecutiveFailures = 0
            while (isActive) {
                try {
                    refreshTabs()
                    consecutiveFailures = 0
                } catch (e: Exception) {
                    consecutiveFailures++
                    tabsLogger.warn(LogCategory.GENERAL, "Failed to refresh tabs", mapOf(
                        "consecutiveFailures" to consecutiveFailures
                    ), error = e)
                }
                // Base interval 2s, +1s per failure, max 10s
                delay(minOf(2000L + (consecutiveFailures * 1000L), 10000L))
            }
        }
    }

    override suspend fun refreshTabs() {
        val tabs = splitViewState.collectAllActiveTabs(workspaceManager, windowId)
        _activeTabs.value = tabs.map { convertToActiveTabData(it) }
    }

    override fun selectTab(tabId: String, panelId: String) {
        splitViewState.selectTabInPanel(tabId, panelId)
    }

    override fun getTabUrl(tabId: String): String? {
        val tabs = splitViewState.collectAllActiveTabs(workspaceManager, windowId)
        val tab = tabs.find { it.tabInfo.id == tabId } ?: return null
        val tabInfo = tab.tabInfo
        val fluckTab = tabInfo as? ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
        if (fluckTab != null) return fluckTab.currentUrl
        if (tabInfo.typeId.typeId == "fluck") {
            return getPropertyByReflection(tabInfo, "currentUrl")
                ?: getPropertyByReflection(tabInfo, "initialUrl")
        }
        return null
    }

    override fun getFaviconCacheKey(tabId: String): String? {
        val tabs = splitViewState.collectAllActiveTabs(workspaceManager, windowId)
        val tab = tabs.find { it.tabInfo.id == tabId } ?: return null
        val tabInfo = tab.tabInfo
        val fluckTab = tabInfo as? ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
        if (fluckTab != null) return fluckTab.faviconCacheKey
        if (tabInfo.typeId.typeId == "fluck") {
            return getPropertyByReflection(tabInfo, "faviconCacheKey")
        }
        return null
    }

    @androidx.compose.runtime.Composable
    override fun loadFavicon(cacheKey: String?): androidx.compose.ui.graphics.painter.Painter? {
        return loadFaviconFromCache(cacheKey)?.painter
    }

    override fun getFallbackIcon(typeId: String): androidx.compose.ui.graphics.vector.ImageVector? {
        // Return a generic tab icon based on type
        return when {
            typeId.contains("fluck", ignoreCase = true) -> Icons.Outlined.Language
            typeId.contains("terminal", ignoreCase = true) -> Icons.Outlined.Terminal
            typeId.contains("editor", ignoreCase = true) -> Icons.Outlined.Code
            else -> Icons.Outlined.Tab
        }
    }

    override fun getBrowserIntegration(tabId: String): ApiBrowserIntegration? {
        // Set the selected tab ID for the accessor
        BrowserAccessor.selectedTabId = tabId

        // Store the split view state so BrowserAccessor can find the browser
        // This is critical for dynamic plugins that don't have access to LocalSplitViewState
        storeSplitViewState(splitViewState)

        // Get the internal browser integration
        val internalIntegration = BrowserAccessor().getActiveBrowserIntegration()
            ?: return null

        // Wrap it in an adapter that implements the plugin-api interface
        return BrowserIntegrationAdapter(internalIntegration)
    }

    override fun createBrowserTab(url: String, title: String): String? {
        return try {
            val component = splitViewState.getActiveTabsComponent() ?: return null
            openFluckTabIn(component, url, title)
        } catch (e: Exception) {
            null
        }
    }

    override fun createBrowserTabInRightSplit(url: String, title: String): String? {
        return try {
            // Split the active panel left/right; the new (right) panel hosts the browser.
            val activePanelId = splitViewState.activePanelId
            val newPanelId = splitViewState.splitPanel(
                activePanelId,
                ai.rever.boss.components.window_panel.SplitOrientation.VERTICAL
            )
            val newPanel = splitViewState.getAllPanels().firstOrNull { it.id == newPanelId } ?: return null
            val tabId = openFluckTabIn(newPanel.tabsComponent, url, title)
            if (tabId == null && newPanelId != activePanelId) {
                // Open failed — collapse the empty split we just created so a failed
                // open doesn't leave an orphan pane in the layout.
                splitViewState.closePanel(newPanelId)
            }
            tabId
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Build a Fluck browser tab, add it to [component], select it, and return its
     * id. The single place that knows how to open a browser tab — shared by
     * [createBrowserTab] (active panel) and [createBrowserTabInRightSplit] (a new
     * split pane).
     */
    private fun openFluckTabIn(
        component: ai.rever.boss.components.window_panel.components.main_window_panels.BossTabsComponent,
        url: String,
        title: String,
    ): String? {
        val tabId = "plugin-tab-${kotlin.time.Clock.System.now().toEpochMilliseconds()}"
        val fluckTab = ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo(
            id = tabId,
            typeId = ai.rever.boss.plugin.tab.fluck.FluckTabType.typeId,
            _title = title,
            _icon = androidx.compose.material.icons.Icons.Outlined.Language,
            url = url
        )
        val tabIndex = component.addTab(fluckTab)
        return if (tabIndex >= 0) {
            component.selectTab(tabIndex)
            tabId
        } else {
            null
        }
    }

    override fun closeTab(tabId: String): Boolean {
        return try {
            val allPanels = splitViewState.getAllPanels()
            for (panel in allPanels) {
                val tabsComponent = panel.tabsComponent
                // Check if the tab exists by trying to get its component
                val component = tabsComponent.getComponentById(tabId)
                if (component != null) {
                    tabsComponent.removeTabById(tabId)
                    return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun convertToActiveTabData(tab: ai.rever.boss.topofmind.ActiveTab): ActiveTabData {
        val tabInfo = tab.tabInfo
        val fluckTab = tabInfo as? ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo

        // For dynamic plugin browser tabs (typeId "fluck" but not FluckTabInfo),
        // extract URL and favicon via reflection from FluckBrowserTabData
        val url: String?
        val faviconCacheKey: String?
        if (fluckTab != null) {
            url = fluckTab.currentUrl
            faviconCacheKey = fluckTab.faviconCacheKey
        } else if (tabInfo.typeId.typeId == "fluck") {
            url = getPropertyByReflection(tabInfo, "currentUrl")
                ?: getPropertyByReflection(tabInfo, "initialUrl")
            faviconCacheKey = getPropertyByReflection(tabInfo, "faviconCacheKey")
        } else {
            url = null
            faviconCacheKey = null
        }

        return ActiveTabData(
            tabId = tabInfo.id,
            typeId = tabInfo.typeId.typeId,
            title = tabInfo.title,
            workspaceId = tab.workspaceId,
            workspaceName = tab.workspaceName,
            panelId = tab.panelId,
            windowId = tab.windowId,
            splitPosition = tab.splitPosition,
            url = url,
            faviconCacheKey = faviconCacheKey
        )
    }

    /** Helper to get a String property from an object via reflection. */
    private fun getPropertyByReflection(obj: Any, propertyName: String): String? {
        return try {
            obj::class.java.methods
                .firstOrNull { it.name == "get${propertyName.replaceFirstChar { c -> c.uppercase() }}" && it.parameterCount == 0 }
                ?.invoke(obj) as? String
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Adapter that bridges the internal BrowserIntegration to the plugin-api BrowserIntegration.
 * This allows dynamic plugins to use browser capabilities through the PluginContext API.
 */
private class BrowserIntegrationAdapter(
    private val internal: InternalBrowserIntegration
) : ApiBrowserIntegration {

    override suspend fun executeJavaScript(script: String): Any? {
        return internal.executeJavaScript(script)
    }

    override suspend fun navigate(url: String) {
        internal.navigate(url)
    }

    override fun isBrowserAvailable(): Boolean {
        return internal.isBrowserAvailable()
    }

    override suspend fun getCurrentUrl(): String? {
        return internal.getCurrentUrl()
    }
}

/**
 * Default implementation of ContextMenuProvider that bridges the plugin API
 * to the app's native context menu implementation.
 *
 * Converts ContextMenuItemData (from plugin-ui-core) to ContextMenuItem (from app)
 * and applies the contextMenu modifier.
 */
/**
 * Default implementation of KeyboardShortcutProvider that bridges
 * the existing getKeyboardShortcuts() data to the plugin API.
 */
/**
 * Default implementation of CacheProvider.
 * Uses ~/.boss/plugin-cache/{pluginId}/ for cache storage.
 */
private class DefaultCacheProvider : CacheProvider {
    private val cacheBaseDir = BossDirectories.resolve("plugin-cache")

    override fun clearPluginCache(pluginId: String): Boolean {
        return try {
            val cacheDir = File(cacheBaseDir, pluginId)
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun getPluginCacheSize(pluginId: String): Long {
        return try {
            val cacheDir = File(cacheBaseDir, pluginId)
            if (cacheDir.exists()) {
                cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            } else {
                0L
            }
        } catch (e: Exception) {
            -1L
        }
    }

    override fun getPluginCacheDirectory(pluginId: String): String {
        val cacheDir = File(cacheBaseDir, pluginId)
        cacheDir.mkdirs()
        return cacheDir.absolutePath
    }
}

/**
 * Default implementation of BackgroundTaskProvider.
 * Launches tasks on the plugin scope with tracking.
 */
private class DefaultBackgroundTaskProvider(
    private val scope: kotlinx.coroutines.CoroutineScope
) : BackgroundTaskProvider {
    private val activeTasks = java.util.concurrent.ConcurrentHashMap<String, DefaultBackgroundTaskHandle>()

    override fun launchTask(name: String, task: suspend () -> Unit): BackgroundTaskHandle? {
        return try {
            val taskId = "${name}-${System.currentTimeMillis()}"
            val job = scope.launch {
                try {
                    task()
                } finally {
                    activeTasks.remove(taskId)
                }
            }
            val handle = DefaultBackgroundTaskHandle(name, job)
            activeTasks[taskId] = handle
            handle
        } catch (e: Exception) {
            null
        }
    }

    override fun getRunningTasks(): List<BackgroundTaskHandle> {
        return activeTasks.values.filter { it.isActive }.toList()
    }

    override fun cancelAll(): Int {
        var count = 0
        activeTasks.values.forEach {
            if (it.isActive) {
                it.cancel()
                count++
            }
        }
        activeTasks.clear()
        return count
    }
}

private class DefaultBackgroundTaskHandle(
    override val name: String,
    override val job: kotlinx.coroutines.Job
) : BackgroundTaskHandle {
    override val isActive: Boolean get() = job.isActive
    override fun cancel() = job.cancel()
}

/**
 * Default implementation of DiagnosticProvider.
 * Stores diagnostics in memory with a ring buffer.
 */
private class DefaultDiagnosticProvider : DiagnosticProvider {
    private val maxEntries = 200
    private val entries = java.util.concurrent.ConcurrentLinkedDeque<DiagnosticEntry>()

    override fun reportDiagnostic(category: String, message: String, metadata: Map<String, String>) {
        entries.addFirst(DiagnosticEntry(
            timestamp = System.currentTimeMillis(),
            category = category,
            message = message,
            metadata = metadata
        ))
        // Trim to max size
        while (entries.size > maxEntries) {
            entries.removeLast()
        }
    }

    override fun getRecentDiagnostics(limit: Int): List<DiagnosticEntry> {
        return entries.take(limit)
    }

    override fun clearDiagnostics() {
        entries.clear()
    }
}

private class DefaultKeyboardShortcutProvider : KeyboardShortcutProvider {
    override fun getShortcuts(): List<KeyboardShortcutInfo> {
        return ai.rever.boss.components.settings.sections.getKeyboardShortcuts().map {
            KeyboardShortcutInfo(
                action = it.action,
                key = it.key,
                modifiers = it.modifiers,
                category = it.category.name.replace("_", " ").lowercase()
                    .replaceFirstChar { c -> c.uppercase() },
                description = it.description
            )
        }
    }

    override fun getShortcutsByCategory(category: String): List<KeyboardShortcutInfo> {
        return getShortcuts().filter { it.category == category }
    }

    override fun isMacOS(): Boolean {
        return ai.rever.boss.components.settings.sections.isMacOS()
    }
}

private class DefaultContextMenuProvider : ContextMenuProvider {

    @androidx.compose.runtime.Composable
    override fun applyContextMenu(
        modifier: androidx.compose.ui.Modifier,
        items: List<ContextMenuItemData>
    ): androidx.compose.ui.Modifier {
        // Convert plugin API items to app's ContextMenuItem format
        val appItems = items.map { it.toContextMenuItem() }
        return modifier.contextMenu(items = appItems)
    }

    private fun ContextMenuItemData.toContextMenuItem(): ContextMenuItem =
        if (isDivider) ContextMenuItem(isDivider = true)
        else ContextMenuItem(
            text = label,
            icon = icon,
            subMenu = subMenu?.map { it.toContextMenuItem() },
            onClick = onClick
        )
}

