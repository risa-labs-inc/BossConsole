package ai.rever.boss.plugin.sandbox.context

import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.AuthDataProvider
import ai.rever.boss.plugin.api.BookmarkDataProvider
import ai.rever.boss.plugin.api.ContextMenuProvider
import ai.rever.boss.plugin.api.DashboardContentProvider
import ai.rever.boss.plugin.api.DownloadDataProvider
import ai.rever.boss.plugin.api.FileSystemDataProvider
import ai.rever.boss.plugin.api.GitDataProvider
import ai.rever.boss.plugin.api.LogDataProvider
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolRegistry
import ai.rever.boss.plugin.api.PanelEventProvider
import ai.rever.boss.plugin.api.RoleManagementProvider
import ai.rever.boss.plugin.api.SettingsProvider
import ai.rever.boss.plugin.api.UserManagementProvider
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PerformanceDataProvider
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.api.PluginSandboxRef
import ai.rever.boss.plugin.api.PluginStoreApiKeyProvider
import ai.rever.boss.plugin.api.RunConfigurationDataProvider
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.SplitViewOperations
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabUpdateProviderFactory
import ai.rever.boss.plugin.api.WorkspaceDataProvider
import ai.rever.boss.plugin.api.ZoomSettingsProvider
import ai.rever.boss.plugin.api.UrlHistoryProvider
import ai.rever.boss.plugin.api.EditorContentProvider
import ai.rever.boss.plugin.api.NotificationProvider
import ai.rever.boss.plugin.api.ApplicationEventBus
import ai.rever.boss.plugin.api.PluginStorageFactory
import ai.rever.boss.plugin.api.GenericDialogProvider
import ai.rever.boss.plugin.api.ClipboardProvider
import ai.rever.boss.plugin.api.FilePickerProvider
import ai.rever.boss.plugin.api.BackgroundTaskProvider
import ai.rever.boss.plugin.api.CacheProvider
import ai.rever.boss.plugin.api.DiagnosticProvider
import ai.rever.boss.plugin.api.KeyboardShortcutProvider
import ai.rever.boss.plugin.api.NavigationResolverProvider
import ai.rever.boss.plugin.api.ScreenCaptureProvider
import ai.rever.boss.plugin.api.SemanticTokenProvider
import ai.rever.boss.plugin.api.NavigationTargetProvider
import ai.rever.boss.plugin.browser.BrowserService
import ai.rever.boss.plugin.sandbox.PluginSandbox
import kotlinx.coroutines.CoroutineScope

/**
 * A PluginContext wrapper that provides sandboxed registries.
 *
 * This context wraps the original PanelRegistry and TabRegistry with
 * error boundary wrappers, ensuring that plugin crashes are isolated.
 */
class SandboxedPluginContext(
    private val _sandbox: PluginSandbox,
    private val delegate: PluginContext,
    private val sandboxedPanelRegistry: SandboxedPanelRegistry,
    private val sandboxedTabRegistry: SandboxedTabRegistry
) : PluginContext {

    override val panelRegistry: PanelRegistry
        get() = sandboxedPanelRegistry

    override val tabRegistry: TabRegistry
        get() = sandboxedTabRegistry

    /**
     * The pluginScope is provided by the sandbox, ensuring all plugin
     * coroutines run within the sandboxed scope with SupervisorJob.
     */
    override val pluginScope: CoroutineScope
        get() = _sandbox.sandboxScope

    /**
     * The sandbox reference for health reporting.
     */
    override val sandbox: PluginSandboxRef
        get() = _sandbox

    /**
     * Browser service for plugins needing embedded browser capabilities.
     * Delegates to the underlying context's browserService.
     */
    override val browserService: BrowserService?
        get() = delegate.browserService

    /**
     * The plugin's manifest, providing access to configuration declared in plugin.json.
     * Delegates to the underlying context's manifest.
     */
    override val manifest: PluginManifest?
        get() = delegate.manifest

    // Service providers - delegate to underlying context
    override val performanceDataProvider: PerformanceDataProvider?
        get() = delegate.performanceDataProvider

    override val downloadDataProvider: DownloadDataProvider?
        get() = delegate.downloadDataProvider

    override val bookmarkDataProvider: BookmarkDataProvider?
        get() = delegate.bookmarkDataProvider

    override val workspaceDataProvider: WorkspaceDataProvider?
        get() = delegate.workspaceDataProvider

    override val splitViewOperations: SplitViewOperations?
        get() = delegate.splitViewOperations

    override val gitDataProvider: GitDataProvider?
        get() = delegate.gitDataProvider

    override val fileSystemDataProvider: FileSystemDataProvider?
        get() = delegate.fileSystemDataProvider

    override val secretDataProvider: SecretDataProvider?
        get() = delegate.secretDataProvider

    override val runConfigurationDataProvider: RunConfigurationDataProvider?
        get() = delegate.runConfigurationDataProvider

    override val activeTabsProvider: ActiveTabsProvider?
        get() = delegate.activeTabsProvider

    override val windowId: String?
        get() = delegate.windowId

    override val projectPath: String?
        get() = delegate.projectPath

    override val authDataProvider: AuthDataProvider?
        get() = delegate.authDataProvider

    override val userManagementProvider: UserManagementProvider?
        get() = delegate.userManagementProvider

    override val roleManagementProvider: RoleManagementProvider?
        get() = delegate.roleManagementProvider

    override val supabaseDataProvider: ai.rever.boss.plugin.api.SupabaseDataProvider?
        get() = delegate.supabaseDataProvider

    override val panelEventProvider: PanelEventProvider?
        get() = delegate.panelEventProvider

    override val settingsProvider: SettingsProvider?
        get() = delegate.settingsProvider

    // Context menu provider - delegate to underlying context
    override val contextMenuProvider: ContextMenuProvider?
        get() = delegate.contextMenuProvider

    // Log data provider - delegate to underlying context
    override val logDataProvider: LogDataProvider?
        get() = delegate.logDataProvider

    // Plugin Store API key provider - delegate to underlying context
    override val pluginStoreApiKeyProvider: PluginStoreApiKeyProvider?
        get() = delegate.pluginStoreApiKeyProvider

    // Tab update provider factory - delegate to underlying context
    override val tabUpdateProviderFactory: TabUpdateProviderFactory?
        get() = delegate.tabUpdateProviderFactory

    // Dashboard content provider - delegate to underlying context
    override val dashboardContentProvider: DashboardContentProvider?
        get() = delegate.dashboardContentProvider

    // Zoom settings provider - delegate to underlying context
    override val zoomSettingsProvider: ZoomSettingsProvider?
        get() = delegate.zoomSettingsProvider

    // URL history provider - delegate to underlying context
    override val urlHistoryProvider: UrlHistoryProvider?
        get() = delegate.urlHistoryProvider

    // Screen capture provider - delegate to underlying context
    override val screenCaptureProvider: ScreenCaptureProvider?
        get() = delegate.screenCaptureProvider

    // WebRTC peer provider for co-browse - delegate to underlying context
    override val coBrowseRtcProvider: ai.rever.boss.plugin.api.CoBrowseRtcProvider?
        get() = delegate.coBrowseRtcProvider

    // Editor content provider - delegate to underlying context
    override val editorContentProvider: EditorContentProvider?
        get() = delegate.editorContentProvider

    // Phase 4: Notification provider - delegate to underlying context
    override val notificationProvider: NotificationProvider?
        get() = delegate.notificationProvider

    // Phase 4: Application event bus - delegate to underlying context
    override val applicationEventBus: ApplicationEventBus?
        get() = delegate.applicationEventBus

    // Phase 4: Plugin storage factory - delegate to underlying context
    override val pluginStorageFactory: PluginStorageFactory?
        get() = delegate.pluginStorageFactory

    // Phase 4: Generic dialog provider - delegate to underlying context
    override val genericDialogProvider: GenericDialogProvider?
        get() = delegate.genericDialogProvider

    // Navigation resolver provider - delegate to underlying context
    override val navigationResolverProvider: NavigationResolverProvider?
        get() = delegate.navigationResolverProvider

    // Semantic token provider - delegate to underlying context
    override val semanticTokenProvider: SemanticTokenProvider?
        get() = delegate.semanticTokenProvider

    // Navigation target provider - delegate to underlying context
    override val navigationTargetProvider: NavigationTargetProvider?
        get() {
            val result = delegate.navigationTargetProvider
            println("[HOST-DEBUG] SandboxedPluginContext.navigationTargetProvider: delegate=${delegate::class.simpleName}, result=$result")
            return result
        }

    // Clipboard provider - delegate to underlying context
    override val clipboardProvider: ClipboardProvider?
        get() = delegate.clipboardProvider

    // File picker provider - delegate to underlying context
    override val filePickerProvider: FilePickerProvider?
        get() = delegate.filePickerProvider

    // Directory picker + project data providers - delegate to underlying context.
    // (Sandboxed plugins wrap TrackingPluginContext; both wrappers must forward
    // these or a sandboxed plugin's directory "Browse" gets the interface-default
    // null even though the host provides one.)
    override val directoryPickerProvider: ai.rever.boss.plugin.api.DirectoryPickerProvider?
        get() = delegate.directoryPickerProvider
    override val projectDataProvider: ai.rever.boss.plugin.api.ProjectDataProvider?
        get() = delegate.projectDataProvider

    // Keyboard shortcut provider - delegate to underlying context
    override val keyboardShortcutProvider: KeyboardShortcutProvider?
        get() = delegate.keyboardShortcutProvider

    // Cache provider - delegate to underlying context
    override val cacheProvider: CacheProvider?
        get() = delegate.cacheProvider

    // Background task provider - delegate to underlying context
    override val backgroundTaskProvider: BackgroundTaskProvider?
        get() = delegate.backgroundTaskProvider

    // Diagnostic provider - delegate to underlying context
    override val diagnosticProvider: DiagnosticProvider?
        get() = delegate.diagnosticProvider

    // MCP tool provider registration - delegate to underlying context so
    // plugin-contributed MCP tools reach the host McpToolRegistry.
    override fun registerMcpToolProvider(provider: McpToolProvider) = delegate.registerMcpToolProvider(provider)
    override fun unregisterMcpToolProvider(providerId: String) = delegate.unregisterMcpToolProvider(providerId)
    override val mcpToolRegistry: McpToolRegistry? get() = delegate.mcpToolRegistry

    // UI extension registries - delegate to underlying context. Without these
    // overrides the PluginContext interface defaults (no-ops) would swallow
    // plugin registrations silently.
    override fun registerPanelMenuContribution(contribution: ai.rever.boss.plugin.api.PanelMenuContribution) =
        delegate.registerPanelMenuContribution(contribution)
    override fun unregisterPanelMenuContribution(contributionId: String) =
        delegate.unregisterPanelMenuContribution(contributionId)
    override fun registerSettingsPage(provider: ai.rever.boss.plugin.api.SettingsPageProvider) =
        delegate.registerSettingsPage(provider)
    override fun unregisterSettingsPage(pageId: String) =
        delegate.unregisterSettingsPage(pageId)
    override fun registerDeepLinkActionHandler(handler: ai.rever.boss.plugin.api.DeepLinkActionHandler) =
        delegate.registerDeepLinkActionHandler(handler)
    override fun unregisterDeepLinkActionHandler(handlerId: String) =
        delegate.unregisterDeepLinkActionHandler(handlerId)
    override fun registerShortcutActionProvider(provider: ai.rever.boss.plugin.api.ShortcutActionProvider) =
        delegate.registerShortcutActionProvider(provider)
    override fun unregisterShortcutActionProvider(providerId: String) =
        delegate.unregisterShortcutActionProvider(providerId)
    override fun registerStatusBarItem(provider: ai.rever.boss.plugin.api.StatusBarItemProvider) =
        delegate.registerStatusBarItem(provider)
    override fun unregisterStatusBarItem(itemId: String) =
        delegate.unregisterStatusBarItem(itemId)

    // Plugin-to-plugin API access - delegate to underlying context
    override fun <T : Any> getPluginAPI(apiClass: Class<T>): T? = delegate.getPluginAPI(apiClass)
    override fun registerPluginAPI(api: Any) = delegate.registerPluginAPI(api)

    /**
     * Get the underlying sandbox for this context.
     */
    fun getSandbox(): PluginSandbox = _sandbox
}
