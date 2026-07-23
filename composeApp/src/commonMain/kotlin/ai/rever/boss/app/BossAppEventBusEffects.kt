package ai.rever.boss.app

import ai.rever.boss.components.events.DashboardEventBus
import ai.rever.boss.components.events.FileEventBus
import ai.rever.boss.components.events.GitTerminalEventBus
import ai.rever.boss.components.events.NavigationTargetBus
import ai.rever.boss.components.events.PanelEventBus
import ai.rever.boss.components.events.RunEventBus
import ai.rever.boss.components.events.RunnerTerminalEventBus
import ai.rever.boss.components.events.TerminalEventBus
import ai.rever.boss.components.events.TerminalLinkEventBus
import ai.rever.boss.components.events.URLEventBus
import ai.rever.boss.components.events.WorkspaceEventBus
import ai.rever.boss.components.plugin.PanelIds
import ai.rever.boss.components.window_panel.SplitOrientation
import ai.rever.boss.components.workspaces.WorkspaceSerializer
import ai.rever.boss.components.workspaces.applyWorkspace
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.dashboard.DashboardStatsManager
import ai.rever.boss.git.GitTerminalService
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.Panel.Companion.right
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import ai.rever.boss.run.RunConfigurationManager
import ai.rever.boss.run.RunExecutionService
import ai.rever.boss.run.RunnerSettingsManager
import ai.rever.boss.run.RunnerTerminalService
import ai.rever.boss.run.RunnerTerminalTarget
import ai.rever.boss.services.FileHandlerService
import ai.rever.boss.services.TerminalHandlerService
import ai.rever.boss.services.URLHandlerService
import ai.rever.boss.terminal.TerminalLinkOpenMode
import ai.rever.boss.terminal.TerminalLinkSettingsManager
import ai.rever.boss.utils.awaitRegistryCondition
import ai.rever.boss.utils.extractFileName
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take

/**
 * Event-bus listeners for one BossApp window. Every bus is window-filtered by
 * sourceWindowId (Issues #498/#506) so events only affect the window they came
 * from. Handlers translate bus events into split-view / panel / dialog actions.
 */
@Composable
internal fun BossAppEventBusEffects(state: BossAppState) {
    val windowId = state.windowId
    val logger = state.logger
    val splitViewState = state.splitViewState
    val windowProjectState = state.windowProjectState

    // Listen for file open events - now handled by split state
    // Issue #506: Filter by window to prevent file opening in all windows
    LaunchedEffect(splitViewState, windowId) {
        FileEventBus.fileOpenEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach { event ->
                splitViewState.openFileInActivePanel(event.filePath, event.fileName)
                // Emit navigation target for cursor positioning (PSI navigation)
                // Issue #506: Pass windowId for multi-window filtering
                if (event.line > 0) {
                    NavigationTargetBus.navigateTo(event.filePath, event.line, event.column, sourceWindowId = windowId)
                }
            }
            .launchIn(this)
    }

    // Listen for terminal open events - now handled by split state
    // Issue #506: Filter by window to prevent terminal opening in all windows
    LaunchedEffect(splitViewState, windowId) {
        TerminalEventBus.terminalOpenEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach { event ->
                splitViewState.openTerminalInActivePanel(event.command, event.workingDirectory)
                DashboardStatsManager.recordTerminalSession()
            }
            .launchIn(this)

        // Note: We DON'T call markReady() here - that happens AFTER Last Session loads
        // just like URL handler, to prevent terminals from being destroyed by clearAllPanels()
    }

    // Listen for runner terminal events (Issue #347 - Runner in terminal sidebar)
    // Issue #498: Filter events by window to prevent duplicate tabs in all windows
    LaunchedEffect(splitViewState, windowId) {
        // Open runner terminal events
        RunnerTerminalEventBus.openEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach { event ->
                // Check settings for terminal target
                val settings = RunnerSettingsManager.currentSettings.value
                val usesSidebar = settings.terminalTarget == RunnerTerminalTarget.SIDEBAR_PANEL

                if (usesSidebar) {
                    // Open in sidebar terminal panel
                    // First, ensure the sidebar terminal panel is open
                    PanelEventBus.openPanel(PanelIds.TERMINAL, sourceWindowId = windowId)

                    // Create a new tab in the sidebar terminal with the command (window-scoped)
                    val success = RunnerTerminalService.openInSidebarTerminal(
                        windowId = windowId,
                        configId = event.configId,
                        command = event.command,
                        workingDirectory = event.workingDirectory,
                        tabTitle = "Run: ${event.configName}",
                        isRerun = event.isRerun
                    )

                    if (!success) {
                        // Fallback to main panel if sidebar terminal not available
                        openRunnerInMainPanel(event, splitViewState)
                    }
                } else {
                    // Open in main panel (original behavior)
                    openRunnerInMainPanel(event, splitViewState)
                }
            }
            .launchIn(this)

        // Close runner terminal events
        // Issue #506: Filter by window to prevent closing in all windows
        RunnerTerminalEventBus.closeEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach { event ->
                // Find and close the terminal tab
                val panel = splitViewState.findPanelWithTab(event.terminalId)
                panel?.tabsComponent?.removeTabById(event.terminalId)

                // Notify service that terminal was removed (window-scoped)
                RunnerTerminalService.removeTerminal(windowId, event.terminalId)
            }
            .launchIn(this)

        // Stop runner terminal events
        // Note: Ctrl+C is sent by RunnerTerminalService.stopRunner() via TerminalAPIAccess
        // Issue #506: Filter by window to prevent stopping in all windows
        RunnerTerminalEventBus.stopEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach {
                // Ctrl+C is already sent by the service - this event is for any additional UI handling
            }
            .launchIn(this)
    }

    // Listen for Git terminal events (opens git commands in sidebar terminal)
    // Issue #498: Filter events by window to prevent duplicate tabs in all windows
    LaunchedEffect(splitViewState, windowId) {
        GitTerminalEventBus.openEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach { event ->
                // Open the terminal panel if not already open
                PanelEventBus.openPanel(PanelIds.TERMINAL, sourceWindowId = windowId)

                // Create a new tab in the sidebar terminal with the git command (window-scoped)
                GitTerminalService.openInSidebarTerminal(
                    windowId = windowId,
                    command = event.command,
                    workingDirectory = event.workingDirectory,
                    operationName = event.operationName
                )
            }
            .launchIn(this)
    }

    // Listen for terminal link click events (Issue #346)
    // Shows dialog or auto-opens based on user preference
    // Note: We collect linkClickEvents directly (not with combine()) to avoid
    // re-processing the same event when settings change (e.g., when user clicks "Remember")
    // Issue #498: Filter events by window to prevent dialog appearing in all windows
    LaunchedEffect(splitViewState, windowId) {
        TerminalLinkEventBus.linkClickEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach { event ->
                val settings = TerminalLinkSettingsManager.currentSettings.value

                when (settings.openMode) {
                    TerminalLinkOpenMode.ALWAYS_ASK -> {
                        state.pendingTerminalLinkUrl = event.url
                        state.pendingTerminalSourceId = event.sourceTerminalId
                        state.showTerminalLinkDialog = true
                    }
                    else -> {
                        openTerminalLink(event.url, settings.openMode, splitViewState, event.sourceTerminalId, this, windowId = windowId)
                    }
                }
            }
            .launchIn(this)
    }

    // Listen for run execute events (Issue #321 - Run functionality)
    // IntelliJ-style: Adds config to run history when executed
    // Issue #506: Filter by sourceWindowId for multi-window support
    LaunchedEffect(splitViewState, windowId) {
        RunEventBus.executeEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach { event ->
                // Add to run history (IntelliJ-style)
                // Note: addConfiguration() already handles deduplication by filePath,
                // so we don't need an external check (avoids TOCTOU race condition)
                val historyConfig = event.configuration.copy(isAutoDetected = false)
                RunConfigurationManager.addConfiguration(historyConfig)

                // Select the config in top bar dropdown (window-scoped)
                // Use filePath lookup since addConfiguration may deduplicate (existing config has different ID)
                val savedConfigs = RunConfigurationManager.currentSettings.value.configurations
                val configToSelect = savedConfigs.find { it.filePath == historyConfig.filePath }
                if (configToSelect != null) {
                    state.windowRunnerState.selectConfiguration(configToSelect)
                }

                RunExecutionService.execute(event.configuration, event.debug, windowId)
            }
            .launchIn(this)

        RunEventBus.stopEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach { event ->
                val configIdToStop = event.configId
                if (configIdToStop != null) {
                    RunExecutionService.stop(configIdToStop)
                } else {
                    RunExecutionService.stopAll()
                }
            }
            .launchIn(this)

        // Scan events are still handled for explicit scan requests (e.g., from Run Configurations plugin)
        // Issue #506: Filter by sourceWindowId for multi-window support
        RunEventBus.scanEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach { event ->
                RunConfigurationManager.scanProject(event.projectPath)
            }
            .launchIn(this)
    }

    // Listen for workspace load events from CLI
    // Issue #506: Filter by sourceWindowId for multi-window support
    LaunchedEffect(splitViewState, workspaceManager, windowId) {
        WorkspaceEventBus.workspaceLoadEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach { event ->
                try {
                    val file = java.io.File(event.workspacePath)
                    if (file.exists() && file.canRead()) {
                        val json = file.readText()
                        val workspace = WorkspaceSerializer.deserialize(json)

                        // Use the same loading pattern as the UI
                        workspaceManager.loadWorkspace(workspace)
                        applyWorkspace(workspace, splitViewState, windowProjectState)
                    }
                } catch (e: Exception) {
                }
            }
            .launchIn(this)
    }

    // Listen for panel open events (e.g., from CLI folder command)
    // Issue #506: Filter by window to prevent panel opening in all windows
    LaunchedEffect(state.draggablePanelComponent, state.panelRegistry, windowId) {
        PanelEventBus.panelOpenEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach { event ->
                try {
                    // Find the panel info from registry
                    // Compare only panelId and pluginId, ignore defaultOrder (UI metadata)
                    fun findPanelInfo() = state.panelRegistry.getAllPanels().find {
                        it.id.panelId == event.panelId.panelId &&
                        it.id.pluginId == event.panelId.pluginId
                    }

                    var panelInfo = findPanelInfo()
                    if (panelInfo == null) {
                        // The plugin providing this panel may still be loading
                        // (panels are opened reactively on project selection,
                        // which can beat async plugin registration at startup).
                        // Wait bounded for it instead of silently dropping the event.
                        awaitRegistryCondition(
                            state.panelRegistry::addChangeListener,
                            state.panelRegistry::removeChangeListener
                        ) { findPanelInfo() != null }
                        panelInfo = findPanelInfo()
                        if (panelInfo == null) {
                            logger.warn(LogCategory.UI, "Dropping panel open event - panel never registered", mapOf(
                                "panelId" to event.panelId.panelId,
                                "pluginId" to event.panelId.pluginId
                            ))
                        }
                    }

                    if (panelInfo != null) {
                        val panelSlot = panelInfo.defaultSlotPosition
                        // Use the unfiltered listing — this path activates a
                        // panel by id from an event, so we should still find
                        // it even if the user has hidden its sidebar icon.
                        val panelItems = state.draggablePanelComponent.getItemsForSlotUnfiltered(panelSlot)
                        val targetItem = panelItems.find { it.pluginContentId.panelId == event.panelId.panelId }

                        if (targetItem != null) {
                            // Check if panel is already open before toggling
                            // If already visible and showing this panel, don't toggle (keep it open)
                            val targetPanel = when (panelSlot) {
                                left.bottom -> bottom
                                left.top.top -> left.top
                                right.top.top -> right.top
                                left.top.bottom -> left.bottom
                                right.top.bottom -> right.bottom
                                else -> null
                            }

                            if (targetPanel != null) {
                                val isAlreadyVisible = state.draggablePanelComponent.isVisible(targetPanel)
                                val currentPanelId = state.draggablePanelComponent.getPanelContentId(targetPanel)
                                val isSamePanel = currentPanelId?.panelId == event.panelId.panelId

                                // Only invoke onClick if panel is not already visible showing this content
                                if (!isAlreadyVisible || !isSamePanel) {
                                    state.draggablePanelComponent.onClick.invoke(targetItem)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                }
            }
            .launchIn(this)
    }

    // Listen for panel close events
    // Issue #506: Filter by window to prevent panel closing in all windows
    LaunchedEffect(state.draggablePanelComponent, windowId) {
        PanelEventBus.panelCloseEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach { event ->
                // Find which panel contains this component
                val panels = listOf(
                    bottom,
                    left.top,
                    left.bottom,
                    right.top,
                    right.bottom
                )

                for (panel in panels) {
                    val panelContentId = state.draggablePanelComponent.getPanelContentId(panel)
                    if (panelContentId == event.panelId) {
                        state.draggablePanelComponent.setPanelVisible(panel, false)
                        // Remove the component from store to ensure fresh instance next time
                        state.panelComponentStore.removeComponent(event.panelId)
                        break
                    }
                }
            }
            .launchIn(this)
    }

    // Listen for panel toggle events (open if closed, close if open)
    // Issue #506: Filter by window to prevent panel toggling in all windows
    LaunchedEffect(state.draggablePanelComponent, state.panelRegistry, windowId) {
        PanelEventBus.panelToggleEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach { event ->
                try {
                    val panels = listOf(
                        bottom,
                        left.top,
                        left.bottom,
                        right.top,
                        right.bottom
                    )

                    // Check if the panel is currently visible with this content
                    var foundVisible = false
                    for (panel in panels) {
                        val panelContentId = state.draggablePanelComponent.getPanelContentId(panel)
                        if (panelContentId?.panelId == event.panelId.panelId &&
                            state.draggablePanelComponent.isVisible(panel)) {
                            // Panel is visible - close it
                            state.draggablePanelComponent.setPanelVisible(panel, false)
                            state.panelComponentStore.removeComponent(event.panelId)
                            foundVisible = true
                            break
                        }
                    }

                    if (!foundVisible) {
                        // Panel is not visible - open it using the same logic as panelOpenEvents
                        val panelInfo = state.panelRegistry.getAllPanels().find {
                            it.id.panelId == event.panelId.panelId &&
                            it.id.pluginId == event.panelId.pluginId
                        }

                        if (panelInfo != null) {
                            val panelSlot = panelInfo.defaultSlotPosition
                            // Use the unfiltered listing — programmatic
                            // activation should work even if the user has
                            // hidden the panel's icon.
                            val panelItems = state.draggablePanelComponent.getItemsForSlotUnfiltered(panelSlot)
                            val targetItem = panelItems.find { it.pluginContentId.panelId == event.panelId.panelId }

                            if (targetItem != null) {
                                state.draggablePanelComponent.onClick.invoke(targetItem)
                            }
                        }
                    }
                } catch (e: Exception) {
                }
            }
            .launchIn(this)
    }

    // Listen for Dashboard events from Fluck tabs (when Dashboard is shown in empty browser tabs)
    // Issue #506: Filter by window to prevent events affecting all windows
    LaunchedEffect(splitViewState, windowId) {
        // Handle file open events
        DashboardEventBus.openFileEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach { event ->
                splitViewState.openFileInActivePanel(
                    event.path,
                    event.path.extractFileName().ifEmpty { "untitled" }
                )
            }
            .launchIn(this)

        // Handle URL open in new tab events
        DashboardEventBus.openUrlInNewTabEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach { event ->
                splitViewState.openUrlInActivePanel(event.url, "Loading...")
            }
            .launchIn(this)

        // Handle new tab events
        DashboardEventBus.newTabEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach {
                state.showNewTabDialog = true
            }
            .launchIn(this)

        // Handle new terminal events
        DashboardEventBus.newTerminalEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach {
                val timestamp = System.currentTimeMillis()
                val projectPath = windowProjectState.selectedProject.value.path
                val terminalTab = TerminalTabInfo(
                    id = "terminal-$timestamp",
                    typeId = TerminalTabType.typeId,
                    title = "Terminal",
                    icon = TerminalTabType.icon,
                    workingDirectory = projectPath.ifEmpty { null }
                )
                splitViewState.getActiveTabsComponent()?.addTab(terminalTab)
            }
            .launchIn(this)

        // Handle project dialog events
        DashboardEventBus.showProjectDialogEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach {
                state.showProjectDialog = true
            }
            .launchIn(this)

        // Handle file dialog events
        DashboardEventBus.showFileDialogEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach {
                // File dialog is typically handled by a system file chooser
                // For now, show new tab dialog with file option
                state.showNewTabDialog = true
            }
            .launchIn(this)

        // Handle new project events
        DashboardEventBus.showNewProjectEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach {
                state.showNewProjectDialog = true
            }
            .launchIn(this)

        // Handle split template events
        DashboardEventBus.applySplitTemplateEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach { event ->
                // Split templates from Fluck Dashboard - apply using active panel
                val activeComponent = splitViewState.getActiveTabsComponent()
                if (activeComponent != null) {
                    val activePanelId = splitViewState.activePanelId
                    val projectPath = windowProjectState.selectedProject.value.path.ifEmpty {
                        System.getProperty("user.home")
                    }
                    // Create tabs from template panels
                    val leftPanelConfig = event.template.panels.find { it.position == "left" }
                    val rightPanelConfig = event.template.panels.find { it.position == "right" }

                    leftPanelConfig?.let { config ->
                        createTabFromTemplateConfig(config, projectPath)?.let { tab ->
                            activeComponent.addTab(tab)
                            if (rightPanelConfig != null) {
                                createTabFromTemplateConfig(rightPanelConfig, projectPath)?.let { rightTab ->
                                    splitViewState.splitPanel(
                                        panelId = activePanelId,
                                        orientation = SplitOrientation.VERTICAL,
                                        tabToMove = rightTab
                                    )
                                }
                            }
                        }
                    } ?: rightPanelConfig?.let { config ->
                        createTabFromTemplateConfig(config, projectPath)?.let { tab ->
                            activeComponent.addTab(tab)
                        }
                    }
                }
            }
            .launchIn(this)

        // Handle plugin activation events
        DashboardEventBus.activatePluginEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach { event ->
                state.draggablePanelComponent.activatePlugin(event.pluginId)
            }
            .launchIn(this)
    }

    // Combined LaunchedEffect for URL handling and auto-show dialog (Issue #168)
    // Uses reactive state observation with processing state tracking to eliminate race conditions
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    LaunchedEffect(splitViewState, windowId) {
        // Set up URL listener for incoming URLs
        // Note: We DON'T call markAppReady() here - that happens AFTER Last Session loads
        // Issue #506: Filter by window to prevent URL opening in all windows
        URLEventBus.urlOpenEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach { event ->
                // sourceWindowId is required, so we already filtered to the correct window
                splitViewState.openUrlInActivePanel(event.url, event.title)
            }
            .launchIn(this)

        // Observe tab count AND processing state (URLs + Terminals + Files + Workspace Restoration) reactively
        // This eliminates all timing assumptions by waiting for actual completion
        snapshotFlow {
            val allPanels = splitViewState.getAllPanels()
            val totalTabs = allPanels.sumOf { panel ->
                panel.tabsComponent.tabsState.value.tabs.size
            }
            val isProcessingURLs = URLHandlerService.isProcessingURLs()
            val isProcessingTerminals = TerminalHandlerService.isProcessingTerminals()
            val isProcessingFiles = FileHandlerService.isProcessingFiles()

            data class ProcessingState(
                val totalTabs: Int,
                val isProcessingURLs: Boolean,
                val isProcessingTerminals: Boolean,
                val isProcessingFiles: Boolean,
                val isRestorationComplete: Boolean
            )
            ProcessingState(totalTabs, isProcessingURLs, isProcessingTerminals, isProcessingFiles, state.workspaceRestorationComplete)
        }
            .debounce(200) // Wait for 200ms of stability
            .take(1)       // Only take first stabilized value
            .collect { processingState ->
                // Only show dialog if no tabs AND nothing being processed AND workspace restoration is complete
                if (processingState.totalTabs == 0 &&
                    !processingState.isProcessingURLs &&
                    !processingState.isProcessingTerminals &&
                    !processingState.isProcessingFiles &&
                    processingState.isRestorationComplete
                ) {
                    state.showNewTabDialog = true
                }
            }
    }
}
