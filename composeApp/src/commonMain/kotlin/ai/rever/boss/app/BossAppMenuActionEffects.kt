package ai.rever.boss.app

import ai.rever.boss.components.bars.horizontal.StatusMessageManager
import ai.rever.boss.components.dialogs.TabType
import ai.rever.boss.components.plugin.AvailablePluginUpdate
import ai.rever.boss.components.plugin.InstalledPluginRef
import ai.rever.boss.components.plugin.PluginUpdateBridge
import ai.rever.boss.components.plugin.UpdateCheckOutcome
import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabComponent
import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.components.sidebar.SidebarVisibilitySettings
import ai.rever.boss.components.sidebar.SidebarVisibilitySettingsManager
import ai.rever.boss.components.window_panel.NavigationDirection
import ai.rever.boss.components.window_panel.SplitOrientation
import ai.rever.boss.components.wizard.plugin.PluginWizardIntegration
import ai.rever.boss.components.workspaces.applyWorkspace
import ai.rever.boss.components.workspaces.extractCurrentWorkspace
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.focusmode.FocusModeSettingsManager
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import ai.rever.boss.topofmind.TabTreeState
import ai.rever.boss.window.MenuActionsHandler
import ai.rever.boss.window.WindowOperations
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import kotlin.random.Random
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Listeners translating [MenuActionsHandler] menu-bar events (File/View/Plugin
 * menus, tab switching, zoom, workspace actions) into window-local behavior,
 * plus the derived state MenuActionsHandler needs back (split enabled, panel
 * count) to keep menu items in sync.
 */
@Composable
internal fun BossAppMenuActionEffects(state: BossAppState, reveal: FocusModeRevealState) {
    val windowId = state.windowId
    val splitViewState = state.splitViewState
    val windowProjectState = state.windowProjectState
    val coroutineScope = state.coroutineScope

    // Force-reveal the sidebar containing the customize button when
    // "View → Customize Sidebar…" fires. Without this, focus mode keeps
    // the sidebar (and the SidebarCustomizeMenu inside it) un-composed and
    // the OS-menu click has nowhere to land. Triggers are keyed by
    // windowId, so once we reveal the sidebar the now-composed
    // SidebarCustomizeMenu still picks up the same request (and is
    // responsible for clearing the entry once handled).
    val customizeTriggers by MenuActionsHandler.customizeSidebarTriggers.collectAsState()
    val sidebarVisibilitySettings by SidebarVisibilitySettingsManager.currentSettings.collectAsState()
    LaunchedEffect(customizeTriggers, windowId) {
        if (customizeTriggers.containsKey(windowId)) {
            if (SidebarVisibilitySettings.isLeftSide(sidebarVisibilitySettings.customizeButtonSlotId)) {
                reveal.showLeftSidebar = true
            } else {
                reveal.showRightSidebar = true
            }
        }
    }

    // Listen for menu actions from MenuBar (File > New Tab, etc.)
    LaunchedEffect(windowId) {
        MenuActionsHandler.newTabEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    // Show new tab dialog when menu item is clicked
                    state.showNewTabDialog = true
                }
            }
            .launchIn(this)
    }

    LaunchedEffect(windowId) {
        MenuActionsHandler.closeTabEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    // First check if there are ANY tabs in the window
                    val allPanels = splitViewState.getAllPanels()
                    val totalTabs = allPanels.sumOf { panel ->
                        panel.tabsComponent.tabsState.value.tabs.size
                    }

                    // If no tabs at all (dashboard showing), close window directly
                    if (totalTabs == 0) {
                        WindowOperations.closeWindow(windowId)
                        return@onEach
                    }

                    // Otherwise, close the active tab
                    val activeTabsComponent = splitViewState.getPanelTabsComponent(splitViewState.activePanelId)
                    if (activeTabsComponent != null) {
                        val tabs = activeTabsComponent.tabsState.value.tabs
                        val activeIndex = activeTabsComponent.tabsState.value.activeIndex
                        if (activeIndex >= 0 && activeIndex < tabs.size) {
                            activeTabsComponent.removeTab(activeIndex)

                            // Re-check total tabs after removal
                            val remainingTabs = allPanels.sumOf { panel ->
                                panel.tabsComponent.tabsState.value.tabs.size
                            }
                            if (remainingTabs == 0) {
                                WindowOperations.closeWindow(windowId)
                            }
                        }
                    }
                }
            }
            .launchIn(this)
    }

    // Tab switching (Ctrl+Tab). Next/previous "steps" and the MRU "commit" share ONE ordered
    // stream so a step (Tab keydown) is always applied before its commit (modifier keyup) —
    // a single collector preserves emission order; separate flows would not guarantee it.
    LaunchedEffect(windowId) {
        MenuActionsHandler.tabSwitchEvents
            .onEach { (eventWindowId, action) ->
                if (eventWindowId != windowId) return@onEach
                val comp = splitViewState.getPanelTabsComponent(splitViewState.activePanelId)
                when (action) {
                    MenuActionsHandler.TabSwitchAction.NEXT -> {
                        comp?.switchToNextTab()
                        // Non-null only during an MRU cycle; drives the switcher overlay.
                        state.tabCycleOverlay = comp?.currentCycleOverlay()
                    }
                    MenuActionsHandler.TabSwitchAction.PREVIOUS -> {
                        comp?.switchToPreviousTab()
                        state.tabCycleOverlay = comp?.currentCycleOverlay()
                    }
                    MenuActionsHandler.TabSwitchAction.COMMIT -> {
                        comp?.commitTabCycle()
                        state.tabCycleOverlay = null
                    }
                }
            }
            .launchIn(this)
    }

    // Listen for zoom menu actions
    LaunchedEffect(windowId) {
        MenuActionsHandler.zoomInEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    val activeTabsComponent = splitViewState.getPanelTabsComponent(splitViewState.activePanelId)
                    val activeTab = activeTabsComponent?.getActiveComponent()
                    if (activeTab is FluckTabComponent) {
                        activeTab.zoomIn()
                    }
                }
            }
            .launchIn(this)
    }

    LaunchedEffect(windowId) {
        MenuActionsHandler.zoomOutEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    val activeTabsComponent = splitViewState.getPanelTabsComponent(splitViewState.activePanelId)
                    val activeTab = activeTabsComponent?.getActiveComponent()
                    if (activeTab is FluckTabComponent) {
                        activeTab.zoomOut()
                    }
                }
            }
            .launchIn(this)
    }

    LaunchedEffect(windowId) {
        MenuActionsHandler.actualSizeEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    val activeTabsComponent = splitViewState.getPanelTabsComponent(splitViewState.activePanelId)
                    val activeTab = activeTabsComponent?.getActiveComponent()
                    if (activeTab is FluckTabComponent) {
                        activeTab.actualSize()
                    }
                }
            }
            .launchIn(this)
    }

    // Handle new File menu events
    LaunchedEffect(windowId) {
        MenuActionsHandler.openProjectEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    state.showProjectDialog = true
                }
            }
            .launchIn(this)
    }

    LaunchedEffect(windowId) {
        MenuActionsHandler.openFileEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    // Open file tab selection - show new tab dialog with File tab pre-selected
                    state.newTabDialogInitialType = TabType.FILE
                    state.showNewTabDialog = true
                }
            }
            .launchIn(this)
    }

    LaunchedEffect(windowId) {
        MenuActionsHandler.newTerminalEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    // Directly create and open terminal tab
                    val activeTabsComponent = splitViewState.getPanelTabsComponent(splitViewState.activePanelId)
                    activeTabsComponent?.let { component ->
                        // Get current project path for terminal working directory (per-window)
                        val projectPath = windowProjectState.selectedProject.value.path
                        val terminalTab = TerminalTabInfo(
                            id = "terminal-${Random.nextLong()}",
                            typeId = TerminalTabType.typeId,
                            title = "Terminal",
                            workingDirectory = projectPath.ifEmpty { null }
                        )
                        component.addTab(terminalTab)
                    }
                }
            }
            .launchIn(this)
    }

    LaunchedEffect(windowId) {
        MenuActionsHandler.selectWorkspaceEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    state.showTopOfMindDialog = true
                }
            }
            .launchIn(this)
    }

    LaunchedEffect(windowId, workspaceManager, splitViewState) {
        MenuActionsHandler.applyWorkspaceEvents
            .onEach { (eventWindowId, workspace) ->
                if (eventWindowId == windowId) {
                    // Load workspace into manager
                    workspaceManager.loadWorkspace(workspace)

                    // Apply workspace to UI
                    applyWorkspace(workspace, splitViewState, windowProjectState)
                }
            }
            .launchIn(this)
    }

    LaunchedEffect(windowId) {
        MenuActionsHandler.openSettingsEvents
            .onEach { (eventWindowId, section) ->
                if (eventWindowId == windowId) {
                    state.settingsInitialSection = section
                    state.showSettingsDialog = true
                }
            }
            .launchIn(this)
    }

    // Handle View menu events
    LaunchedEffect(windowId) {
        MenuActionsHandler.toggleFocusModeEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    coroutineScope.launch {
                        FocusModeSettingsManager.toggleFocusMode()
                    }
                }
            }
            .launchIn(this)
    }

    LaunchedEffect(windowId) {
        MenuActionsHandler.splitVerticallyEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    // Copy the active tab to the new panel to prevent empty panel auto-close
                    val currentTab = splitViewState.getActiveTabsComponent()?.getCurrentTab()
                    splitViewState.splitPanel(
                        panelId = splitViewState.activePanelId,
                        orientation = SplitOrientation.VERTICAL,
                        tabToMove = currentTab
                    )
                }
            }
            .launchIn(this)
    }

    LaunchedEffect(windowId) {
        MenuActionsHandler.splitHorizontallyEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    // Copy the active tab to the new panel to prevent empty panel auto-close
                    val currentTab = splitViewState.getActiveTabsComponent()?.getCurrentTab()
                    splitViewState.splitPanel(
                        panelId = splitViewState.activePanelId,
                        orientation = SplitOrientation.HORIZONTAL,
                        tabToMove = currentTab
                    )
                }
            }
            .launchIn(this)
    }

    // Track whether split is enabled (has tabs in active panel).
    // tabsState is a Decompose Value, not snapshot state — reading .value in
    // composition subscribes to nothing, and this small extracted scope no
    // longer recomposes incidentally the way the old monolithic BossApp body
    // did. subscribeAsState() keeps the menu enablement live when tabs are
    // added/removed with no other recomposition trigger (e.g. first tab
    // created purely via the OS menu).
    val activePanelId by splitViewState.activePanelIdState
    val activeTabsComponent = splitViewState.getActiveTabsComponent()
    val hasActiveTabs = if (activeTabsComponent != null) {
        val activeTabsState by activeTabsComponent.tabsState.subscribeAsState()
        activeTabsState.tabs.isNotEmpty()
    } else {
        false
    }
    LaunchedEffect(windowId, activePanelId, hasActiveTabs) {
        MenuActionsHandler.updateSplitEnabled(windowId, hasActiveTabs)
    }

    // Track panel count for navigation menu items
    val panelCount = splitViewState.getAllPanels().size
    LaunchedEffect(windowId, panelCount) {
        MenuActionsHandler.updatePanelCount(windowId, panelCount)
    }

    // Handle Plugin menu events
    LaunchedEffect(windowId) {
        MenuActionsHandler.revealPluginEvents
            .onEach { (eventWindowId, pluginId) ->
                if (eventWindowId == windowId) {
                    // Activate the plugin (same as clicking its sidebar icon)
                    state.draggablePanelComponent.activatePlugin(pluginId)
                }
            }
            .launchIn(this)
    }

    // Handle Browser Reload menu events
    LaunchedEffect(windowId) {
        MenuActionsHandler.reloadBrowserEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    val activeTabsComp = splitViewState.getPanelTabsComponent(splitViewState.activePanelId)
                    val activeTab = activeTabsComp?.tabsState?.value?.activeTab
                    if (activeTab is FluckTabInfo) {
                        val activeTabComponent = activeTabsComp.getActiveComponent()
                        if (activeTabComponent is FluckTabComponent) {
                            activeTabComponent.reload()
                        }
                    }
                }
            }
            .launchIn(this)
    }

    // Handle Save Workspace menu events
    LaunchedEffect(windowId) {
        MenuActionsHandler.saveWorkspaceEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    val currentConfig = workspaceManager.currentWorkspace.value
                    if (currentConfig != null) {
                        val currentLayout = extractCurrentWorkspace(splitViewState, windowProjectState.selectedProject.value.path)
                        val updatedConfig = currentConfig.copy(
                            layout = currentLayout.layout,
                            timestamp = Clock.System.now().toEpochMilliseconds()
                        )
                        workspaceManager.updateCurrentWorkspace(updatedConfig)
                        workspaceManager.saveCurrentWorkspace()
                        TabTreeState.markWorkspaceAsSaved(currentConfig.id)
                        StatusMessageManager.showMessage("Workspace Saved")
                    } else {
                        val currentLayout = extractCurrentWorkspace(splitViewState, windowProjectState.selectedProject.value.path)
                        val newConfig = currentLayout.copy(
                            name = "Workspace ${Clock.System.now().toEpochMilliseconds() / 1000}",
                            description = "Saved workspace"
                        )
                        workspaceManager.updateCurrentWorkspace(newConfig)
                        workspaceManager.saveCurrentWorkspace()
                        StatusMessageManager.showMessage("Workspace Saved")
                    }
                }
            }
            .launchIn(this)
    }

    // Handle Open Codebase menu events
    LaunchedEffect(windowId) {
        MenuActionsHandler.openCodebaseEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    state.draggablePanelComponent.activatePlugin("codebase")
                }
            }
            .launchIn(this)
    }

    // Handle Open Global Search menu events (Issue #92)
    LaunchedEffect(windowId) {
        MenuActionsHandler.openGlobalSearchEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    state.showGlobalSearchDialog = true
                }
            }
            .launchIn(this)
    }

    // Handle Panel Navigation menu events (consolidated)
    LaunchedEffect(windowId) {
        val navigationFlows = mapOf(
            NavigationDirection.LEFT to MenuActionsHandler.navigatePanelLeftEvents,
            NavigationDirection.RIGHT to MenuActionsHandler.navigatePanelRightEvents,
            NavigationDirection.UP to MenuActionsHandler.navigatePanelUpEvents,
            NavigationDirection.DOWN to MenuActionsHandler.navigatePanelDownEvents
        )

        navigationFlows.forEach { (direction, flow) ->
            flow.onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    splitViewState.findPanelInDirection(direction)?.let { panel ->
                        splitViewState.setActivePanel(panel.id)
                    }
                }
            }.launchIn(this)
        }
    }

    // Handle Show Shortcut Help menu events
    LaunchedEffect(windowId) {
        MenuActionsHandler.showShortcutHelpEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    state.showShortcutHelpDialog = true
                }
            }
            .launchIn(this)
    }

    // Handle Show Plugin Wizard menu events
    LaunchedEffect(windowId) {
        MenuActionsHandler.showPluginWizardEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    // Load plugins if not already loaded (on IO thread)
                    if (state.availablePluginsForWizard.isEmpty()) {
                        val plugins = withContext(Dispatchers.IO) {
                            PluginWizardIntegration.getAvailablePlugins()
                        }
                        state.availablePluginsForWizard = plugins
                    }
                    if (state.availablePluginsForWizard.isNotEmpty()) {
                        state.showPluginInstallWizard = true
                    }
                }
            }
            .launchIn(this)
    }

    // Handle Reload All Plugins menu events
    LaunchedEffect(windowId) {
        MenuActionsHandler.reloadAllPluginsEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    val manager = state.currentDefaultPlugin?.dynamicPluginManager ?: return@onEach
                    val result = manager.reloadAllPlugins()
                    val count = result.getOrElse { 0 }
                    StatusMessageManager.showMessage("Reloaded $count plugin(s)")
                }
            }
            .launchIn(this)
    }

    // Handle Reload Plugin (by panel ID) menu events
    LaunchedEffect(windowId) {
        MenuActionsHandler.reloadPluginEvents
            .onEach { (eventWindowId, panelId) ->
                if (eventWindowId == windowId) {
                    val manager = state.currentDefaultPlugin?.dynamicPluginManager ?: return@onEach
                    val tracker = manager.getRegistrationTracker()
                    val pluginId = tracker.getPluginIdForPanel(panelId)
                    if (pluginId != null) {
                        val result = manager.reloadPlugin(pluginId)
                        if (result.isSuccess) {
                            StatusMessageManager.showMessage("Reloaded: ${result.getOrNull()?.manifest?.displayName}")
                        } else {
                            StatusMessageManager.showMessage("Failed to reload plugin: ${result.exceptionOrNull()?.message}")
                        }
                    }
                }
            }
            .launchIn(this)
    }

    // Handle "Check for Updates" (by panel ID) menu / header-badge events
    LaunchedEffect(windowId) {
        MenuActionsHandler.checkPluginUpdatesEvents
            .onEach { (eventWindowId, panelId) ->
                if (eventWindowId == windowId) {
                    val manager = state.currentDefaultPlugin?.dynamicPluginManager ?: return@onEach
                    val pluginId = manager.getRegistrationTracker().getPluginIdForPanel(panelId) ?: return@onEach
                    val info = manager.getPluginInfo(pluginId) ?: return@onEach
                    val ref = InstalledPluginRef(
                        pluginId, info.manifest.displayName, info.manifest.version
                    )
                    when (val outcome = PluginUpdateBridge.checkOne(ref)) {
                        is UpdateCheckOutcome.Available ->
                            state.pluginUpdatePrompt = AvailablePluginUpdate(
                                pluginId, outcome.displayName, outcome.currentVersion, outcome.newVersion
                            )
                        UpdateCheckOutcome.UpToDate ->
                            StatusMessageManager.showMessage("${info.manifest.displayName} is up to date")
                        is UpdateCheckOutcome.Incompatible ->
                            StatusMessageManager.showMessage("Update v${outcome.advertisedLatest} needs a newer BOSS")
                        is UpdateCheckOutcome.Error ->
                            StatusMessageManager.showMessage("Couldn't check for updates: ${outcome.message}")
                    }
                }
            }
            .launchIn(this)
    }
}
