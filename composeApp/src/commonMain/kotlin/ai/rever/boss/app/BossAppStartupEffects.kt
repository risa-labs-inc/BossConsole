package ai.rever.boss.app

import ai.rever.boss.components.events.PanelEventBus
import ai.rever.boss.components.plugin.DefaultPlugin
import ai.rever.boss.components.plugin.PanelIds
import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.components.plugin.tab_types.registerPanelHostTab
import ai.rever.boss.components.registery.PanelComponentStoreRegistry
import ai.rever.boss.components.window_panel.SplitNode
import ai.rever.boss.components.window_panel.SplitViewStateRegistry
import ai.rever.boss.components.wizard.plugin.PluginWizardIntegration
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.WorkspaceSettingsManager
import ai.rever.boss.components.workspaces.applyWorkspace
import ai.rever.boss.components.workspaces.extractCurrentWorkspace
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.consumePendingInitialProject
import ai.rever.boss.consumePendingInitialTab
import ai.rever.boss.performance.BrowserTabInfo
import ai.rever.boss.performance.EditorTabResourceInfo
import ai.rever.boss.performance.PerformanceState
import ai.rever.boss.performance.TerminalInfo
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.Panel.Companion.right
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.plugin.tab.codeeditor.EditorTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.services.TerminalHandlerService
import ai.rever.boss.services.auth.CoreAuthService
import ai.rever.boss.services.auth.UserDataStorage
import ai.rever.boss.services.bookmarks.BookmarkAPIAccess
import ai.rever.boss.services.terminal.TerminalAPIAccess
import ai.rever.boss.setupDownloadTabCloseCallback
import ai.rever.boss.startup.StartupSettingsManager
import ai.rever.boss.components.plugin.panels.right_top.LLMSettingsManager
import ai.rever.boss.topofmind.TabTreeState
import ai.rever.boss.updater.UpdateManager
import ai.rever.boss.updater.UpdateSettings
import ai.rever.boss.utils.CLIInstaller
import ai.rever.boss.utils.CLIVersionManager
import ai.rever.boss.utils.WindowFocusManager
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.WindowGitStateRegistry
import ai.rever.boss.window.WindowProjectStateRegistry
import ai.rever.boss.window.WindowRunnerStateRegistry
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Startup and lifecycle effects for one BossApp window: registry registration,
 * pending tab/project consumption, [DefaultPlugin] lifecycle, workspace
 * restoration + handler readiness, the plugin install wizard, updater and CLI
 * bootstraps, performance providers, and workspace auto-save.
 */
@Composable
internal fun BossAppStartupEffects(state: BossAppState) {
    val windowId = state.windowId
    val isFirstWindow = state.isFirstWindow
    val logger = state.logger
    val splitViewState = state.splitViewState
    val windowProjectState = state.windowProjectState

    // Log once when BossApp first composes
    LaunchedEffect(Unit) {
        logger.info(LogCategory.SYSTEM, "BossApp initialized", mapOf(
            "windowId" to windowId,
            "isFirstWindow" to isFirstWindow.toString()
        ))
    }

    // Register the host-internal "panel host" tab type so a sidebar plugin can be
    // opened as a main tab (header "Open as Tab" / drag-out). Idempotent. Also let the
    // header drag-out resolve the same per-panel split zones the tab drag uses.
    LaunchedEffect(state.tabRegistry) {
        state.tabRegistry.registerPanelHostTab(state.panelComponentStore, state.draggablePanelComponent)
        state.draggablePanelComponent.panelDropZonesProvider = { state.tabDragComponent.panelDropZones }
    }

    // Register this window's state in the global registry for multi-window features
    LaunchedEffect(splitViewState, windowId) {
        SplitViewStateRegistry.register(windowId, splitViewState)
    }

    // Register this window's panel component store so the plugin reload path can
    // reset open sidebar panel slots across all windows (see PanelComponentStoreRegistry).
    // DisposableEffect (not LaunchedEffect like the registries below): a store
    // leaked past its window would keep pinning unloaded plugin classloaders —
    // the very leak #856 is about — so unregistration is tied to composition
    // teardown as well as the explicit window-close cleanup.
    DisposableEffect(state.panelComponentStore, windowId) {
        PanelComponentStoreRegistry.register(windowId, state.panelComponentStore)
        onDispose {
            PanelComponentStoreRegistry.unregister(windowId)
        }
    }

    // Register callback for FluckEngine to auto-close download redirect tabs (desktop only)
    LaunchedEffect(splitViewState) {
        setupDownloadTabCloseCallback(splitViewState)
    }

    // Cancel any active drag when window loses focus (prevents stuck ghost)
    LaunchedEffect(state.tabDragComponent, windowId) {
        WindowFocusManager.focusedWindowFlow.collect { focusedWindowId ->
            // If this window lost focus and there's an active drag, cancel it
            if (focusedWindowId != windowId && state.tabDragComponent.isDragging) {
                state.tabDragComponent.cancelDrag()
            }
        }
    }

    // Consume any pending initial tab for this window (from "Open in New Window" context menu)
    LaunchedEffect(windowId, splitViewState) {
        val pendingTab = consumePendingInitialTab(windowId)
        if (pendingTab != null) {
            // Add the tab to the active panel (first panel by default)
            val activePanel = splitViewState.getAllPanels().firstOrNull()
            if (activePanel != null) {
                val index = activePanel.tabsComponent.addTab(pendingTab)
                if (index >= 0) {
                    activePanel.tabsComponent.selectTab(index)
                }
            }
        }
    }

    // Consume any pending initial project for this window (from "Open in New Window" context menu)
    LaunchedEffect(windowId, windowProjectState) {
        val pendingProject = consumePendingInitialProject(windowId)
        if (pendingProject != null) {
            windowProjectState.selectProject(pendingProject)
            PanelEventBus.openPanel(PanelIds.CODEBASE, sourceWindowId = windowId)
            PanelEventBus.openPanel(PanelIds.RUN_CONFIGURATIONS, sourceWindowId = windowId)
        }
    }

    // Collect window-specific project state reactively (used by multiple effects below)
    val selectedProject by windowProjectState.selectedProject.collectAsState()

    // Open CodeBase and RunConfigurations panels if a project is selected at startup
    // Note: Pending project is handled in the LaunchedEffect above, this handles
    // existing window project state (e.g., when restored from workspace)
    LaunchedEffect(windowProjectState) {
        val initialProject = windowProjectState.selectedProject.value
        if (initialProject.path.isNotEmpty()) {
            PanelEventBus.openPanel(PanelIds.CODEBASE, sourceWindowId = windowId)
            PanelEventBus.openPanel(PanelIds.RUN_CONFIGURATIONS, sourceWindowId = windowId)
        }
    }

    // Register resource count providers for performance monitoring
    // Use DisposableEffect to clean up on disposal and prevent memory leaks
    DisposableEffect(splitViewState, state.draggablePanelComponent) {
        // Cache for getAllPanels() to avoid repeated tree traversals
        // All 6 providers are called within milliseconds of each other every 5 seconds
        // Using synchronized block for thread-safe access from provider lambdas
        val cacheLock = Any()
        var cachedPanels: List<SplitNode.Panel>? = null
        var cacheTimestamp = 0L
        val cacheTtlMs = 500L // Cache valid for 500ms (well within 5s collection interval)

        fun getCachedPanels(): List<SplitNode.Panel> {
            synchronized(cacheLock) {
                val now = System.currentTimeMillis()
                val cached = cachedPanels
                if (cached == null || now - cacheTimestamp > cacheTtlMs) {
                    val newPanels = splitViewState.getAllPanels()
                    cachedPanels = newPanels
                    cacheTimestamp = now
                    return newPanels
                }
                return cached
            }
        }

        PerformanceState.registerResourceProviders(
            browserTabs = {
                getCachedPanels().sumOf { panel ->
                    panel.tabsComponent.tabsState.value.tabs.count { it is FluckTabInfo }
                }
            },
            terminals = {
                getCachedPanels().sumOf { panel ->
                    panel.tabsComponent.tabsState.value.tabs.count { it is TerminalTabInfo }
                }
            },
            editorTabs = {
                getCachedPanels().sumOf { panel ->
                    panel.tabsComponent.tabsState.value.tabs.count { it is EditorTabInfo }
                }
            },
            panels = {
                // Count visible panels from the draggable panel component
                listOf(
                    bottom,
                    left.top,
                    left.bottom,
                    right.top,
                    right.bottom
                ).count { panel -> state.draggablePanelComponent.isVisible(panel) }
            },
            windows = {
                SplitViewStateRegistry.states.value.size
            }
        )

        // Register detailed resource providers for the Resources tab
        PerformanceState.registerDetailedResourceProviders(
            browserTabs = {
                getCachedPanels().flatMap { panel ->
                    val tabsState = panel.tabsComponent.tabsState.value
                    val activeTabId = tabsState.activeTab?.id
                    tabsState.tabs.filterIsInstance<FluckTabInfo>().map { tab ->
                        BrowserTabInfo(
                            id = tab.id,
                            title = tab.title,
                            url = tab.currentUrl,
                            isActive = tab.id == activeTabId
                        )
                    }
                }
            },
            terminals = {
                getCachedPanels().flatMap { panel ->
                    val tabsState = panel.tabsComponent.tabsState.value
                    val activeTabId = tabsState.activeTab?.id
                    tabsState.tabs.filterIsInstance<TerminalTabInfo>().map { tab ->
                        TerminalInfo(
                            id = tab.id,
                            title = tab.title,
                            isActive = tab.id == activeTabId
                        )
                    }
                }
            },
            editorTabs = {
                getCachedPanels().flatMap { panel ->
                    val tabsState = panel.tabsComponent.tabsState.value
                    val activeTabId = tabsState.activeTab?.id
                    tabsState.tabs.filterIsInstance<EditorTabInfo>().map { tab ->
                        EditorTabResourceInfo(
                            id = tab.id,
                            fileName = tab.title,
                            filePath = tab.filePath,
                            isActive = tab.id == activeTabId
                        )
                    }
                }
            }
        )

        onDispose {
            PerformanceState.clearResourceProviders()
        }
    }

    // Request focus when auth session resolves (event-driven, no delays)
    val isSessionResolved by CoreAuthService.isSessionResolved.collectAsState()

    LaunchedEffect(isSessionResolved) {
        if (isSessionResolved) {
            state.focusRequester.requestFocus()
        }
    }

    // Set up workspace deletion callback to cleanup tabs
    LaunchedEffect(workspaceManager, splitViewState) {
        workspaceManager.setOnWorkspaceDeleted { deletedWorkspaceId ->
            // Clean up preserved states for the deleted workspace
            splitViewState.cleanupDeletedWorkspace(deletedWorkspaceId)
        }
    }

    // Apply default workspace when project is selected
    LaunchedEffect(selectedProject.path) {
        if (selectedProject.path.isNotEmpty()) {
            val defaultWorkspace = WorkspaceSettingsManager.getDefaultWorkspace()
            if (defaultWorkspace != null) {
                // Apply the workspace
                applyWorkspace(defaultWorkspace, splitViewState, windowProjectState)
                workspaceManager.loadWorkspace(defaultWorkspace)
            }
        }
    }

    // Open CodeBase and RunConfigurations panels when project is selected (reactive architecture)
    LaunchedEffect(selectedProject.path, windowId) {
        if (selectedProject.path.isNotEmpty()) {
            PanelEventBus.openPanel(PanelIds.CODEBASE, sourceWindowId = windowId)
            PanelEventBus.openPanel(PanelIds.RUN_CONFIGURATIONS, sourceWindowId = windowId)
        }
    }

    // DefaultPlugin lifecycle: created per window, exposes the plugin system to the
    // host (bookmarks/terminal/editor API access), disposed with the composition.
    DisposableEffect(state.panelRegistry, state.tabRegistry, windowProjectState, state.windowGitState, windowId, workspaceManager, splitViewState) {
        val plugin = DefaultPlugin(
            panelRegistry = state.panelRegistry,
            tabRegistry = state.tabRegistry,
            windowProjectState = windowProjectState,
            windowGitState = state.windowGitState,
            _windowId = windowId,
            workspaceManager = workspaceManager,
            splitViewState = splitViewState
        )
        state.currentDefaultPlugin = plugin
        state.draggablePanelComponent.update()

        // Initialize BookmarkAPIAccess so UI code can access bookmarks via the plugin system
        BookmarkAPIAccess.initialize(plugin)

        // Initialize TerminalAPIAccess so host code can access terminal via the plugin system
        TerminalAPIAccess.initialize(plugin)

        // Initialize EditorAPIAccess so host code can access editor settings via the plugin system
        ai.rever.boss.services.editor.EditorAPIAccess.initialize(plugin)

        onDispose {
            // NOTE: Browser disposal moved to main.kt onCloseRequest handler
            // Browsers must be disposed BEFORE Compose disposal begins, not during it
            // See main.kt onCloseRequest for the disposeAllBrowsersBlocking() call

            // Save current workspace as "Last Session" when app closes
            try {
                // Use runBlocking to ensure save completes before app closes
                kotlinx.coroutines.runBlocking {
                    val currentLayout = extractCurrentWorkspace(splitViewState, selectedProject.path)
                    val lastSessionConfig = currentLayout.copy(
                        id = "last-session",
                        name = "Last Session",
                        description = "Automatically saved session"
                    )
                    workspaceManager.updateCurrentWorkspace(lastSessionConfig)
                    workspaceManager.saveCurrentWorkspace("Last Session")
                }
            } catch (e: Exception) {
                // Shutdown path: never block window teardown, but leave a breadcrumb —
                // a silently lost "Last Session" is exactly what users report as
                // "my layout disappeared".
                logger.warn(LogCategory.WORKSPACE, "Last Session save on window close failed", error = e)
            }

            // Cleanup plugin coroutines
            plugin.dispose()

            // Cleanup update manager
            UpdateManager.instance.cleanup()

            // Unregister this window's state from the global registries
            SplitViewStateRegistry.unregister(windowId)
            PanelComponentStoreRegistry.unregister(windowId)
            WindowProjectStateRegistry.unregister(windowId)
            WindowRunnerStateRegistry.unregister(windowId)
            WindowGitStateRegistry.unregister(windowId)
        }
    }

    // Load LLM settings on startup
    LaunchedEffect(Unit) {
        try {
            LLMSettingsManager.loadSettings()
        } catch (e: Exception) {
            // Non-fatal: app runs with default LLM settings
            logger.warn(LogCategory.SYSTEM, "LLM settings load failed - using defaults", error = e)
        }
    }

    // Check if plugin install wizard should be shown (only for first window)
    // Depend on pluginWizardRetryCount to prevent race conditions - retry logic is explicit
    LaunchedEffect(isFirstWindow, state.currentDefaultPlugin, state.pluginWizardRetryCount) {
        val defaultPlugin = state.currentDefaultPlugin

        // Early exit: Check retry limit FIRST to prevent race conditions
        if (state.pluginWizardRetryCount >= 3) {
            logger.error(LogCategory.SYSTEM, "Plugin wizard fetch failed after 3 attempts, giving up")
            return@LaunchedEffect
        }

        // Early exit: Already checked successfully
        if (state.pluginWizardChecked) {
            return@LaunchedEffect
        }

        if (isFirstWindow && defaultPlugin != null) {
            // Get plugin manager (already initialized by this point)
            val pluginManager = defaultPlugin.dynamicPluginManager

            // Check if wizard was already completed
            val wizardCompleted = withContext(Dispatchers.IO) {
                UserDataStorage.isPluginWizardCompleted()
            }

            // Startup plugin loading (persisted pass + external directory scan) is
            // asynchronous, so "no plugins installed" is only meaningful once it
            // finishes — checking mid-load read an empty registry and re-showed the
            // wizard on every restart. First run (!wizardCompleted) shows the wizard
            // regardless, so only the completed case needs to wait.
            if (wizardCompleted) {
                val loadFinished = withTimeoutOrNull(30_000) {
                    defaultPlugin.awaitInitialPluginLoad()
                }
                if (loadFinished == null) {
                    logger.warn(LogCategory.SYSTEM, "Startup plugin load still running after 30s; skipping wizard check")
                    state.pluginWizardChecked = true
                    return@LaunchedEffect
                }
            }

            // Check if any plugins are installed (in-memory operation, no IO needed)
            val installedPlugins = pluginManager.getInstalledPlugins()
            val hasNoPlugins = installedPlugins.isEmpty()

            // Show wizard if: (1) first time (wizard not completed) OR (2) no plugins installed
            val shouldShowWizard = !wizardCompleted || hasNoPlugins

            if (shouldShowWizard) {
                // Exponential backoff: 0ms, 500ms, 1000ms, 1500ms (on retries)
                if (state.pluginWizardRetryCount > 0) {
                    val delayMs = state.pluginWizardRetryCount * 500L
                    logger.info(LogCategory.SYSTEM, "Retrying plugin fetch after ${delayMs}ms delay", mapOf(
                        "attempt" to (state.pluginWizardRetryCount + 1).toString()
                    ))
                    delay(delayMs)
                }

                try {
                    val plugins = withContext(Dispatchers.IO) {
                        PluginWizardIntegration.getAvailablePlugins()
                    }

                    if (plugins.isNotEmpty()) {
                        state.availablePluginsForWizard = plugins
                        state.showPluginInstallWizard = true
                        state.pluginWizardChecked = true  // Set AFTER successfully showing wizard

                        val reason = if (!wizardCompleted) "first_time" else "no_plugins_installed"
                        logger.info(LogCategory.SYSTEM, "Plugin wizard shown", mapOf(
                            "reason" to reason,
                            "availablePlugins" to plugins.size.toString()
                        ))
                    } else {
                        // No plugins available to install
                        logger.info(LogCategory.SYSTEM, "No plugins available to install")
                        state.pluginWizardChecked = true  // Set to prevent further attempts
                        if (!wizardCompleted) {
                            withContext(Dispatchers.IO) {
                                UserDataStorage.setPluginWizardCompleted(true)
                            }
                        }
                    }
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e // Don't retry on scope cancellation
                } catch (e: Exception) {
                    logger.error(LogCategory.SYSTEM, "Failed to fetch plugins", mapOf(
                        "attempt" to (state.pluginWizardRetryCount + 1).toString(),
                        "maxAttempts" to "3"
                    ), error = e)
                    // Increment counter to trigger retry via LaunchedEffect dependency
                    state.pluginWizardRetryCount++
                }
            } else {
                logger.info(LogCategory.SYSTEM, "Plugin wizard not needed", mapOf(
                    "wizardCompleted" to wizardCompleted.toString(),
                    "pluginsInstalled" to installedPlugins.size.toString()
                ))
                state.pluginWizardChecked = true  // Set to prevent further checks
            }
        }
    }

    // Initialize update manager and conditionally start periodic checks
    LaunchedEffect(Unit) {
        try {
            // Only start periodic checks if enabled in settings
            if (UpdateSettings.autoCheckEnabled) {
                UpdateManager.instance.startPeriodicChecks()

                // Check for updates on startup if enough time has passed
                if (UpdateManager.instance.shouldCheckForUpdates()) {
                    UpdateManager.instance.checkForUpdates()
                }
            }
        } catch (e: Exception) {
            // Non-fatal: user can still check for updates manually
            logger.warn(LogCategory.SYSTEM, "Update manager startup check failed", error = e)
        }
    }

    // Check and auto-update CLI version on startup
    LaunchedEffect(Unit) {
        launch {
            try {
                if (CLIVersionManager.needsCLIUpdate()) {
                    CLIInstaller.installCLI()
                }
            } catch (e: Exception) {
                // Non-fatal: the bundled CLI keeps working at its current version
                logger.warn(LogCategory.SYSTEM, "CLI auto-update failed", error = e)
            }
        }
    }

    // Load last used workspace on startup
    LaunchedEffect(workspaceManager, splitViewState) {
        // Wait for workspaces to be loaded
        workspaceManager.workspaces
            .onEach { configs ->
                // Clean up orphaned workspace states
                val existingWorkspaceIds = configs.map { it.id }.toSet()
                splitViewState.cleanupDeletedWorkspaces(existingWorkspaceIds)

                // Handle workspace restoration - only process when configs are loaded (non-empty)
                // Empty configs might mean either "loading" or "fresh install" - we use timeout for fresh install
                if (configs.isNotEmpty() && workspaceManager.currentWorkspace.value == null) {
                    // Only load "Last Session" for the first window (app startup)
                    // New windows should start fresh (Issue #129)
                    if (isFirstWindow) {
                        // Check if there's a saved "last-session" workspace
                        val lastSessionConfig = configs.find { it.name == "Last Session" }

                        if (lastSessionConfig != null) {
                            // Ensure it has the correct ID
                            val configWithId = if (lastSessionConfig.id != "last-session") {
                                lastSessionConfig.copy(id = "last-session")
                            } else {
                                lastSessionConfig
                            }
                            // Apply the last session workspace FIRST
                            workspaceManager.loadWorkspace(configWithId)
                            // A failed restore must not abort this collector: the
                            // handler-marking below is the only path left once
                            // loadWorkspace has set currentWorkspace — the fresh-install
                            // fallback timeout deliberately stands down at that point.
                            try {
                                applyWorkspace(configWithId, splitViewState, windowProjectState)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logger.error(LogCategory.WORKSPACE, "Last Session restore failed - continuing startup", error = e)
                            }
                        }

                        // Mark workspace restoration as complete (for auto-show dialog logic)
                        state.workspaceRestorationComplete = true

                        // CRITICAL: Mark handlers as ready AFTER Last Session loads (or after determining no session exists)
                        // This ensures URLs/terminals/files/workspaces create tabs AFTER workspace is loaded,
                        // not before (which would cause tabs to be destroyed by clearAllPanels)
                        state.markHandlersReady(isSessionResolved)
                    }
                    // Else: New window - don't load Last Session, start with empty workspace, but still mark ready
                    else {
                        state.markHandlersReady(isSessionResolved)
                    }
                }
            }
            .launchIn(this)
    }

    // Fallback timeout for fresh install (no workspaces on disk at all)
    // This handles the case where workspace manager never emits non-empty configs
    LaunchedEffect(isFirstWindow, isSessionResolved) {
        if (isFirstWindow && !state.workspaceRestorationComplete) {
            // Read timeout from settings (use current value, don't make it a key to avoid restart)
            val timeoutMs = StartupSettingsManager.currentSettings.value.workspaceLoadTimeoutMs
            delay(timeoutMs) // Wait for workspace manager to load from disk
            // currentWorkspace != null means Last Session restore is already in
            // flight (it can outlast this timeout while applyWorkspace waits for
            // plugin tab types) — let it mark handlers ready itself, otherwise
            // handler-created tabs get destroyed by the restore's clearAllPanels.
            if (!state.workspaceRestorationComplete && workspaceManager.currentWorkspace.value == null) {
                // Still not complete after timeout - assume fresh install
                state.workspaceRestorationComplete = true
                state.markHandlersReady(isSessionResolved)
            }
        }
    }

    // Separate effect to handle session resolution AFTER Last Session may have loaded
    // This ensures terminal handler is marked ready even if session resolves late
    LaunchedEffect(isSessionResolved, workspaceManager.currentWorkspace.value) {
        if (isSessionResolved && workspaceManager.currentWorkspace.value != null) {
            // Session is now resolved and workspace has been loaded
            // Mark terminal handler ready if it hasn't been already
            TerminalHandlerService.markReady()
        }
    }

    // One-time plugin update check after plugins load (populates header update badges).
    LaunchedEffect(isFirstWindow, state.currentDefaultPlugin) {
        if (!isFirstWindow) return@LaunchedEffect
        val manager = state.currentDefaultPlugin?.dynamicPluginManager ?: return@LaunchedEffect
        val refs = manager.getInstalledPlugins().map {
            ai.rever.boss.components.plugin.InstalledPluginRef(it.manifest.pluginId, it.manifest.displayName, it.manifest.version)
        }
        if (refs.isNotEmpty()) {
            ai.rever.boss.components.plugin.PluginUpdateBridge.refreshAll(refs)
        }
    }

    // Monitor for layout changes to mark workspace as dirty and auto-save
    LaunchedEffect(splitViewState, workspaceManager) {
        var lastWorkspaceSnapshot: LayoutWorkspace? = null
        var saveJob: Job? = null

        // Monitor the entire layout structure for changes
        snapshotFlow {
            // Extract current layout workspace
            extractCurrentWorkspace(splitViewState, selectedProject.path)
        }
        .onEach { currentLayout ->
            // Check if we have a loaded workspace
            val loadedConfig = workspaceManager.currentWorkspace.value

            if (loadedConfig != null) {
                // Compare with the last known workspace state
                if (lastWorkspaceSnapshot == null) {
                    // First snapshot after loading
                    lastWorkspaceSnapshot = currentLayout
                } else if (currentLayout != lastWorkspaceSnapshot) {
                    // Layout has changed (splits, tabs added/removed, etc.)
                    lastWorkspaceSnapshot = currentLayout

                    // Mark the current workspace as modified (if it's not "Last Session")
                    if (loadedConfig.name != "Last Session") {
                        TabTreeState.markWorkspaceAsModified(loadedConfig.id)
                    }

                    // Cancel previous save job if any
                    saveJob?.cancel()

                    // Auto-save to current workspace or "Last Session" after a short delay
                    saveJob = launch {
                        delay(2000) // Wait 2 seconds before saving

                        if (loadedConfig.name == "Last Session") {
                            // If we're already in "Last Session", update it
                            val lastSessionConfig = currentLayout.copy(
                                id = "last-session",
                                name = "Last Session",
                                description = "Automatically saved session"
                            )
                            workspaceManager.updateCurrentWorkspace(lastSessionConfig)
                            workspaceManager.saveCurrentWorkspace("Last Session")
                        } else {
                            // Update the current loaded workspace with changes
                            val updatedConfig = loadedConfig.copy(
                                layout = currentLayout.layout,
                                timestamp = Clock.System.now().toEpochMilliseconds()
                            )
                            workspaceManager.updateCurrentWorkspace(updatedConfig)
                            workspaceManager.saveCurrentWorkspace()

                            // Clear the modified state since we just auto-saved
                            TabTreeState.markWorkspaceAsSaved(loadedConfig.id)
                        }
                    }
                }
            } else {
                // No workspace loaded, but still save as "Last Session"
                if (currentLayout != lastWorkspaceSnapshot) {
                    lastWorkspaceSnapshot = currentLayout

                    // Cancel previous save job if any
                    saveJob?.cancel()

                    // Auto-save as "Last Session" after a short delay
                    saveJob = launch {
                        delay(2000) // Wait 2 seconds before saving
                        val lastSessionConfig = currentLayout.copy(
                            id = "last-session",
                            name = "Last Session",
                            description = "Automatically saved session"
                        )
                        workspaceManager.updateCurrentWorkspace(lastSessionConfig)
                        workspaceManager.saveCurrentWorkspace("Last Session")
                    }
                }
            }
        }
        .launchIn(this)

        // Reset snapshot when workspace changes
        workspaceManager.currentWorkspace
            .onEach { config ->
                if (config != null && config.name != "Last Session") {
                    // Workspace loaded (but not Last Session), reset tracking
                    lastWorkspaceSnapshot = null
                    // Clear modified status when loading a workspace
                    TabTreeState.markWorkspaceAsSaved(config.id)
                }
            }
            .launchIn(this)
    }
}
