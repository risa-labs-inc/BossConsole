package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.ApplicationEventBus
import ai.rever.boss.plugin.api.AuthDataProvider
import ai.rever.boss.plugin.api.BackgroundTaskProvider
import ai.rever.boss.plugin.api.BookmarkDataProvider
import ai.rever.boss.plugin.api.CacheProvider
import ai.rever.boss.plugin.api.ClipboardProvider
import ai.rever.boss.plugin.api.ContextMenuProvider
import ai.rever.boss.plugin.api.DashboardContentProvider
import ai.rever.boss.plugin.api.DiagnosticProvider
import ai.rever.boss.plugin.api.DownloadDataProvider
import ai.rever.boss.plugin.api.EditorContentProvider
import ai.rever.boss.plugin.api.FilePickerProvider
import ai.rever.boss.plugin.api.FileSystemDataProvider
import ai.rever.boss.plugin.api.GenericDialogProvider
import ai.rever.boss.plugin.api.GitDataProvider
import ai.rever.boss.plugin.api.KeyboardShortcutProvider
import ai.rever.boss.plugin.api.LogDataProvider
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolRegistry
import ai.rever.boss.plugin.api.NavigationResolverProvider
import ai.rever.boss.plugin.api.NavigationTargetProvider
import ai.rever.boss.plugin.api.NotificationProvider
import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelEventProvider
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PerformanceDataProvider
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.api.PluginSandboxRef
import ai.rever.boss.plugin.api.PluginStorageFactory
import ai.rever.boss.plugin.api.PluginStoreApiKeyProvider
import ai.rever.boss.plugin.api.RoleManagementProvider
import ai.rever.boss.plugin.api.RunConfigurationDataProvider
import ai.rever.boss.plugin.api.ScreenCaptureProvider
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.SemanticTokenProvider
import ai.rever.boss.plugin.api.SettingsProvider
import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.api.SupabaseDataProvider
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.api.TabUpdateProviderFactory
import ai.rever.boss.plugin.api.UrlHistoryProvider
import ai.rever.boss.plugin.api.UserManagementProvider
import ai.rever.boss.plugin.api.WorkspaceDataProvider
import ai.rever.boss.plugin.api.ZoomSettingsProvider
import ai.rever.boss.plugin.browser.BrowserService
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry of all registrations made by dynamic plugins.
 *
 * This tracks which panels and tabs each plugin has registered,
 * enabling automatic cleanup when plugins are unloaded.
 */
class PluginRegistrationTracker {
    /**
     * Panels registered by each plugin.
     */
    private val panelsByPlugin = ConcurrentHashMap<String, MutableSet<PanelId>>()

    /**
     * Tab types registered by each plugin.
     */
    private val tabTypesByPlugin = ConcurrentHashMap<String, MutableSet<TabTypeId>>()

    /**
     * MCP tool provider ids registered by each plugin.
     */
    private val mcpToolProvidersByPlugin = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * UI extension teardown callbacks by plugin — panel menus, settings
     * pages, deep-link handlers, shortcut providers, status-bar items. Each
     * registration records the exact undo action captured at register time,
     * so [unregisterAll] is one loop and a NEW extension kind needs no edit
     * here (the old per-kind enum + per-kind teardown loop meant a forgotten
     * loop would leak a kind past unload).
     */
    private val uiExtensionUndoByPlugin = ConcurrentHashMap<String, MutableList<() -> Unit>>()

    /**
     * Record a UI extension registration with the action that undoes it.
     */
    fun recordUiExtensionRegistration(
        pluginId: String,
        undo: () -> Unit,
    ) {
        uiExtensionUndoByPlugin
            .getOrPut(pluginId) { java.util.concurrent.CopyOnWriteArrayList() }
            .add(undo)
    }

    /**
     * Run and clear all recorded UI extension teardown callbacks for a plugin.
     */
    fun unregisterUiExtensions(pluginId: String) {
        uiExtensionUndoByPlugin.remove(pluginId)?.forEach { undo ->
            try {
                undo()
            } catch (_: Throwable) {
                // Teardown of one extension must not block the others.
            }
        }
    }

    /**
     * Record a panel registration.
     */
    fun recordPanelRegistration(
        pluginId: String,
        panelId: PanelId,
    ) {
        panelsByPlugin.getOrPut(pluginId) { ConcurrentHashMap.newKeySet() }.add(panelId)
    }

    /**
     * Record a tab type registration.
     */
    fun recordTabTypeRegistration(
        pluginId: String,
        tabTypeId: TabTypeId,
    ) {
        tabTypesByPlugin.getOrPut(pluginId) { ConcurrentHashMap.newKeySet() }.add(tabTypeId)
    }

    /**
     * Record an MCP tool provider registration.
     */
    fun recordMcpToolProviderRegistration(
        pluginId: String,
        providerId: String,
    ) {
        mcpToolProvidersByPlugin.getOrPut(pluginId) { ConcurrentHashMap.newKeySet() }.add(providerId)
    }

    /**
     * Get all panels registered by a plugin.
     */
    fun getPanelsForPlugin(pluginId: String): Set<PanelId> = panelsByPlugin[pluginId]?.toSet() ?: emptySet()

    /**
     * Get all MCP tool provider ids registered by a plugin.
     */
    fun getMcpToolProvidersForPlugin(pluginId: String): Set<String> = mcpToolProvidersByPlugin[pluginId]?.toSet() ?: emptySet()

    /**
     * Get all tab types registered by a plugin.
     */
    fun getTabTypesForPlugin(pluginId: String): Set<TabTypeId> = tabTypesByPlugin[pluginId]?.toSet() ?: emptySet()

    /**
     * Remove all registration records for a plugin.
     *
     * Called after cleanup is complete.
     */
    fun clearPlugin(pluginId: String) {
        panelsByPlugin.remove(pluginId)
        tabTypesByPlugin.remove(pluginId)
        mcpToolProvidersByPlugin.remove(pluginId)
        uiExtensionUndoByPlugin.remove(pluginId)
    }

    /**
     * Get all plugins that have registered panels or tab types.
     */
    fun getRegisteredPlugins(): Set<String> = panelsByPlugin.keys + tabTypesByPlugin.keys

    /**
     * Check if a plugin has any registrations.
     */
    fun hasRegistrations(pluginId: String): Boolean =
        (panelsByPlugin[pluginId]?.isNotEmpty() == true) ||
            (tabTypesByPlugin[pluginId]?.isNotEmpty() == true)

    /**
     * Reverse lookup: find which plugin registered a given panel.
     *
     * @param panelId The panel ID to look up
     * @return The plugin ID that registered this panel, or null if not found
     */
    fun getPluginIdForPanel(panelId: PanelId): String? =
        panelsByPlugin.entries
            .firstOrNull { (_, panels) -> panelId in panels }
            ?.key
}

/**
 * A panel registry wrapper that tracks registrations for a specific plugin.
 */
class TrackingPanelRegistry(
    private val pluginId: String,
    private val delegate: PanelRegistry,
    private val tracker: PluginRegistrationTracker,
) : PanelRegistry() {
    override fun registerPanel(
        content: PanelInfo,
        factory: (ComponentContext, PanelInfo) -> PanelComponentWithUI,
    ) {
        tracker.recordPanelRegistration(pluginId, content.id)
        delegate.registerPanel(content, factory)
    }

    override fun unregisterPanel(id: PanelId) {
        delegate.unregisterPanel(id)
    }

    override fun addChangeListener(listener: () -> Unit) {
        delegate.addChangeListener(listener)
    }

    override fun removeChangeListener(listener: () -> Unit) {
        delegate.removeChangeListener(listener)
    }

    override fun createComponent(
        id: PanelId,
        componentContext: ComponentContext,
    ): PanelComponentWithUI? = delegate.createComponent(id, componentContext)

    override fun getPanelContent(id: PanelId): PanelInfo? = delegate.getPanelContent(id)

    override fun getAllPanels(): List<PanelInfo> = delegate.getAllPanels()
}

/**
 * A tab registry wrapper that tracks registrations for a specific plugin.
 */
class TrackingTabRegistry(
    private val pluginId: String,
    private val delegate: TabRegistry,
    private val tracker: PluginRegistrationTracker,
) : TabRegistry() {
    override fun registerTabType(
        content: TabTypeInfo,
        factory: (TabInfo, ComponentContext) -> TabComponentWithUI,
    ) {
        tracker.recordTabTypeRegistration(pluginId, content.typeId)
        delegate.registerTabType(content, factory)
    }

    override fun unregisterTabType(typeId: TabTypeId) {
        delegate.unregisterTabType(typeId)
    }

    override fun addChangeListener(listener: () -> Unit) {
        delegate.addChangeListener(listener)
    }

    override fun removeChangeListener(listener: () -> Unit) {
        delegate.removeChangeListener(listener)
    }

    override fun addUnregisterListener(listener: (TabTypeId) -> Unit) {
        delegate.addUnregisterListener(listener)
    }

    override fun removeUnregisterListener(listener: (TabTypeId) -> Unit) {
        delegate.removeUnregisterListener(listener)
    }

    override fun createTabComponent(
        config: TabInfo,
        componentContext: ComponentContext,
    ): TabComponentWithUI? = delegate.createTabComponent(config, componentContext)

    override fun getTabTypeInfo(typeId: TabTypeId): TabTypeInfo? = delegate.getTabTypeInfo(typeId)

    override fun getAllTabTypes(): List<TabTypeInfo> = delegate.getAllTabTypes()

    override fun isRegistered(typeId: TabTypeId): Boolean = delegate.isRegistered(typeId)
}

/**
 * A plugin context that tracks all registrations made by a dynamic plugin.
 *
 * This enables automatic cleanup when the plugin is unloaded.
 *
 * @param pluginId The ID of the plugin using this context
 * @param delegate The underlying plugin context
 * @param tracker The registration tracker
 * @param pluginManifest The plugin's manifest (optional, for external plugins)
 */
class TrackingPluginContext(
    val pluginId: String,
    private val delegate: PluginContext,
    private val tracker: PluginRegistrationTracker,
    private val pluginManifest: PluginManifest? = null,
) : PluginContext {
    private val _panelRegistry = TrackingPanelRegistry(pluginId, delegate.panelRegistry, tracker)
    private val _tabRegistry = TrackingTabRegistry(pluginId, delegate.tabRegistry, tracker)

    override val panelRegistry: PanelRegistry get() = _panelRegistry
    override val tabRegistry: TabRegistry get() = _tabRegistry
    override val pluginScope: CoroutineScope get() = delegate.pluginScope
    override val sandbox: PluginSandboxRef? get() = delegate.sandbox
    override val browserService: BrowserService? get() = delegate.browserService
    override val manifest: PluginManifest? get() = pluginManifest ?: delegate.manifest

    // Service providers - delegate to underlying context
    override val performanceDataProvider: PerformanceDataProvider? get() = delegate.performanceDataProvider
    override val downloadDataProvider: DownloadDataProvider? get() = delegate.downloadDataProvider
    override val bookmarkDataProvider: BookmarkDataProvider? get() = delegate.bookmarkDataProvider
    override val workspaceDataProvider: WorkspaceDataProvider? get() = delegate.workspaceDataProvider
    override val splitViewOperations: SplitViewOperations? get() = delegate.splitViewOperations
    override val gitDataProvider: GitDataProvider? get() = delegate.gitDataProvider
    override val fileSystemDataProvider: FileSystemDataProvider? get() = delegate.fileSystemDataProvider
    override val secretDataProvider: SecretDataProvider? get() = delegate.secretDataProvider
    override val runConfigurationDataProvider: RunConfigurationDataProvider? get() = delegate.runConfigurationDataProvider
    override val activeTabsProvider: ActiveTabsProvider? get() = delegate.activeTabsProvider
    override val windowId: String? get() = delegate.windowId
    override val projectPath: String? get() = delegate.projectPath
    override val authDataProvider: AuthDataProvider? get() = delegate.authDataProvider
    override val userManagementProvider: UserManagementProvider? get() = delegate.userManagementProvider
    override val roleManagementProvider: RoleManagementProvider? get() = delegate.roleManagementProvider
    override val supabaseDataProvider: SupabaseDataProvider? get() = delegate.supabaseDataProvider

    override val panelEventProvider: PanelEventProvider? get() = delegate.panelEventProvider
    override val settingsProvider: SettingsProvider? get() = delegate.settingsProvider

    // Context menu provider - delegate to underlying context
    override val contextMenuProvider: ContextMenuProvider? get() = delegate.contextMenuProvider

    // Log data provider - delegate to underlying context
    override val logDataProvider: LogDataProvider? get() = delegate.logDataProvider

    // Plugin Store API key provider - delegate to underlying context
    override val pluginStoreApiKeyProvider: PluginStoreApiKeyProvider? get() = delegate.pluginStoreApiKeyProvider

    // Tab update provider factory - delegate to underlying context
    override val tabUpdateProviderFactory: TabUpdateProviderFactory? get() = delegate.tabUpdateProviderFactory

    // Dashboard content provider - delegate to underlying context
    override val dashboardContentProvider: DashboardContentProvider? get() = delegate.dashboardContentProvider

    // Zoom settings provider - delegate to underlying context
    override val zoomSettingsProvider: ZoomSettingsProvider? get() = delegate.zoomSettingsProvider

    // URL history provider - delegate to underlying context
    override val urlHistoryProvider: UrlHistoryProvider? get() = delegate.urlHistoryProvider

    // Screen capture provider - delegate to underlying context
    override val screenCaptureProvider: ScreenCaptureProvider? get() = delegate.screenCaptureProvider

    // WebRTC peer provider for co-browse - delegate to underlying context
    override val coBrowseRtcProvider: ai.rever.boss.plugin.api.CoBrowseRtcProvider? get() = delegate.coBrowseRtcProvider

    // Editor content provider - delegate to underlying context
    override val editorContentProvider: EditorContentProvider? get() = delegate.editorContentProvider

    // Phase 4: Notification provider - delegate to underlying context
    override val notificationProvider: NotificationProvider? get() = delegate.notificationProvider

    // Phase 4: Application event bus - delegate to underlying context
    override val applicationEventBus: ApplicationEventBus? get() = delegate.applicationEventBus

    // Phase 4: Plugin storage factory - delegate to underlying context
    override val pluginStorageFactory: PluginStorageFactory? get() = delegate.pluginStorageFactory

    // Phase 4: Generic dialog provider - delegate to underlying context
    override val genericDialogProvider: GenericDialogProvider? get() = delegate.genericDialogProvider

    // Navigation resolver provider - delegate to underlying context
    override val navigationResolverProvider: NavigationResolverProvider? get() = delegate.navigationResolverProvider

    // Semantic token provider - delegate to underlying context
    override val semanticTokenProvider: SemanticTokenProvider? get() = delegate.semanticTokenProvider

    // Navigation target provider - delegate to underlying context
    override val navigationTargetProvider: NavigationTargetProvider? get() = delegate.navigationTargetProvider

    // Clipboard provider - delegate to underlying context
    override val clipboardProvider: ClipboardProvider? get() = delegate.clipboardProvider

    // File picker provider - delegate to underlying context
    override val filePickerProvider: FilePickerProvider? get() = delegate.filePickerProvider

    // Directory picker + project data providers — previously omitted here, so
    // every plugin saw the interface default null (the codebase panel's "Open
    // Project" and the Tool Evolver's Evolve "Browse" silently did nothing).
    override val directoryPickerProvider: ai.rever.boss.plugin.api.DirectoryPickerProvider?
        get() = delegate.directoryPickerProvider
    override val projectDataProvider: ai.rever.boss.plugin.api.ProjectDataProvider?
        get() = delegate.projectDataProvider

    // Keyboard shortcut provider - delegate to underlying context
    override val keyboardShortcutProvider: KeyboardShortcutProvider? get() = delegate.keyboardShortcutProvider

    // Cache provider - delegate to underlying context
    override val cacheProvider: CacheProvider? get() = delegate.cacheProvider

    // Background task provider - delegate to underlying context
    override val backgroundTaskProvider: BackgroundTaskProvider? get() = delegate.backgroundTaskProvider

    // Diagnostic provider - delegate to underlying context
    override val diagnosticProvider: DiagnosticProvider? get() = delegate.diagnosticProvider

    // MCP tool provider registration - track per plugin so tools are removed
    // automatically in unregisterAll() when the plugin is disabled/unloaded.
    override fun registerMcpToolProvider(provider: McpToolProvider) {
        tracker.recordMcpToolProviderRegistration(pluginId, provider.providerId)
        delegate.registerMcpToolProvider(provider)
    }

    // Deliberately does NOT remove providerId from the tracker (unlike a plugin
    // explicitly unregistering a panel/tab type, which also isn't untracked
    // individually — same convention). unregisterAll() reads the tracker to know
    // which ids to unregister and clears everything at once at plugin teardown,
    // so a stale tracker entry here just means unregisterAll() calls the
    // (idempotent) registry unregister a second time for that id — harmless.
    override fun unregisterMcpToolProvider(providerId: String) {
        delegate.unregisterMcpToolProvider(providerId)
    }

    override val mcpToolRegistry: McpToolRegistry? get() = delegate.mcpToolRegistry

    // UI extension registrations — each records the exact undo action, so
    // unregisterAll tears them all down in one loop (see the tracker). Same
    // convention as MCP providers: an explicit unregister call doesn't remove
    // the tracked undo, so unregisterAll may call the (idempotent) registry
    // unregister a second time — harmless.
    override fun registerPanelMenuContribution(contribution: ai.rever.boss.plugin.api.PanelMenuContribution) {
        val id = contribution.contributionId
        tracker.recordUiExtensionRegistration(pluginId) { delegate.unregisterPanelMenuContribution(id) }
        delegate.registerPanelMenuContribution(contribution)
    }

    override fun unregisterPanelMenuContribution(contributionId: String) {
        delegate.unregisterPanelMenuContribution(contributionId)
    }

    override fun registerSettingsPage(provider: ai.rever.boss.plugin.api.SettingsPageProvider) {
        val id = provider.pageId
        tracker.recordUiExtensionRegistration(pluginId) { delegate.unregisterSettingsPage(id) }
        delegate.registerSettingsPage(provider)
    }

    override fun unregisterSettingsPage(pageId: String) {
        delegate.unregisterSettingsPage(pageId)
    }

    override fun registerDeepLinkActionHandler(handler: ai.rever.boss.plugin.api.DeepLinkActionHandler) {
        val id = handler.handlerId
        tracker.recordUiExtensionRegistration(pluginId) { delegate.unregisterDeepLinkActionHandler(id) }
        delegate.registerDeepLinkActionHandler(handler)
    }

    override fun unregisterDeepLinkActionHandler(handlerId: String) {
        delegate.unregisterDeepLinkActionHandler(handlerId)
    }

    override fun registerShortcutActionProvider(provider: ai.rever.boss.plugin.api.ShortcutActionProvider) {
        val id = provider.providerId
        tracker.recordUiExtensionRegistration(pluginId) { delegate.unregisterShortcutActionProvider(id) }
        delegate.registerShortcutActionProvider(provider)
    }

    override fun unregisterShortcutActionProvider(providerId: String) {
        delegate.unregisterShortcutActionProvider(providerId)
    }

    override fun registerStatusBarItem(provider: ai.rever.boss.plugin.api.StatusBarItemProvider) {
        val id = provider.itemId
        tracker.recordUiExtensionRegistration(pluginId) { delegate.unregisterStatusBarItem(id) }
        delegate.registerStatusBarItem(provider)
    }

    override fun unregisterStatusBarItem(itemId: String) {
        delegate.unregisterStatusBarItem(itemId)
    }

    // Plugin-to-plugin API access - delegate to underlying context
    override fun <T : Any> getPluginAPI(apiClass: Class<T>): T? = delegate.getPluginAPI(apiClass)

    override fun registerPluginAPI(api: Any) = delegate.registerPluginAPI(api)

    /**
     * Get the panels registered by this plugin.
     */
    fun getRegisteredPanels(): Set<PanelId> = tracker.getPanelsForPlugin(pluginId)

    /**
     * Get the tab types registered by this plugin.
     */
    fun getRegisteredTabTypes(): Set<TabTypeId> = tracker.getTabTypesForPlugin(pluginId)

    /**
     * Unregister all panels and tab types registered by this plugin.
     */
    fun unregisterAll() {
        println("[TrackingPluginContext] unregisterAll called for plugin: $pluginId")

        // Unregister all panels
        val panels = tracker.getPanelsForPlugin(pluginId)
        println("[TrackingPluginContext] Panels to unregister: ${panels.size}")
        for (panelId in panels) {
            delegate.panelRegistry.unregisterPanel(panelId)
        }

        // Unregister all tab types
        val tabTypes = tracker.getTabTypesForPlugin(pluginId)
        println("[TrackingPluginContext] Tab types to unregister: ${tabTypes.size} - $tabTypes")
        for (tabTypeId in tabTypes) {
            println("[TrackingPluginContext] Unregistering tab type: ${tabTypeId.typeId}")
            delegate.tabRegistry.unregisterTabType(tabTypeId)
        }

        // Unregister all MCP tool providers — this is what makes a plugin's
        // `mcp__boss__*` tools disappear when the plugin is disabled/unloaded.
        val toolProviders = tracker.getMcpToolProvidersForPlugin(pluginId)
        for (providerId in toolProviders) {
            delegate.unregisterMcpToolProvider(providerId)
        }

        // Unregister all UI extensions (panel menu items, settings pages,
        // deep-link handlers, shortcuts, status-bar widgets) — same lifecycle
        // guarantee as MCP tools: gone the moment the plugin is disabled. One
        // loop over the recorded undo callbacks; new kinds need no edit here.
        tracker.unregisterUiExtensions(pluginId)

        // Clear tracking records
        tracker.clearPlugin(pluginId)
    }
}
