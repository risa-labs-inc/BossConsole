package ai.rever.boss.app

import ai.rever.boss.components.bars.horizontal.StatusMessageManager
import ai.rever.boss.components.dialogs.TabType
import ai.rever.boss.components.plugin.AvailablePluginUpdate
import ai.rever.boss.components.plugin.DynamicPluginManager
import ai.rever.boss.components.plugin.InstalledPluginRef
import ai.rever.boss.components.plugin.PluginBuildRegistry
import ai.rever.boss.components.plugin.PluginStoreVersionBridge
import ai.rever.boss.components.plugin.PluginUninstallPrompt
import ai.rever.boss.components.plugin.PluginUpdateBridge
import ai.rever.boss.components.plugin.StoreVersionLookup
import ai.rever.boss.components.plugin.StoreVersionPrompt
import ai.rever.boss.components.plugin.UpdateCheckOutcome
import ai.rever.boss.components.sidebar.SidebarVisibilitySettings
import ai.rever.boss.components.sidebar.SidebarVisibilitySettingsManager
import ai.rever.boss.components.window_panel.NavigationDirection
import ai.rever.boss.components.window_panel.SplitOrientation
import ai.rever.boss.components.wizard.plugin.PluginWizardIntegration
import ai.rever.boss.components.workspaces.applyWorkspace
import ai.rever.boss.components.workspaces.extractCurrentWorkspace
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.focusmode.FocusModeSettingsManager
import ai.rever.boss.plugin.browser.ActiveBrowserRegistry
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import ai.rever.boss.project.DefaultWorkingDirectory
import ai.rever.boss.topofmind.TabTreeState
import ai.rever.boss.window.MenuActionsHandler
import ai.rever.boss.window.WindowAppearanceSettings
import ai.rever.boss.window.WindowAppearanceSettingsManager
import ai.rever.boss.window.WindowOperations
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.time.Clock

/**
 * [settings] with the strip holding the customize button switched back on, [onLeft] saying which.
 *
 * "View - Customize Sidebar..." force-reveals that strip so the click has somewhere to land. Once a
 * strip can also be hidden by preference that reveal is only half the job, because the scaffold
 * requires the preference AND the reveal flag to agree - so a strip switched off would silently
 * swallow the menu item again, which is the bug the reveal itself exists to fix.
 *
 * Switching the preference back on is the honest reading of the request: you cannot customise a bar
 * you have hidden, so asking to customise it is asking for it back.
 *
 * Pure and lifted out of the effect so it is testable, which the conjunction it compensates for was
 * not - see `CustomizeSidebarRevealTest`.
 */
internal fun withCustomizeTargetRevealed(
    settings: WindowAppearanceSettings,
    onLeft: Boolean,
): WindowAppearanceSettings =
    if (onLeft) {
        settings.copy(showLeftStrip = true)
    } else {
        settings.copy(showRightStrip = true)
    }

/**
 * Listeners translating [MenuActionsHandler] menu-bar events (File/View/Plugin
 * menus, tab switching, zoom, workspace actions) into window-local behavior,
 * plus the derived state MenuActionsHandler needs back (split enabled, panel
 * count) to keep menu items in sync.
 */
@Composable
internal fun BossAppMenuActionEffects(
    state: BossAppState,
    reveal: FocusModeRevealState,
) {
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
            val onLeft = SidebarVisibilitySettings.isLeftSide(sidebarVisibilitySettings.customizeButtonSlotId)
            if (onLeft) {
                reveal.showLeftSidebar = true
            } else {
                reveal.showRightSidebar = true
            }
            // Force-revealing the focus-mode flag is not enough once a strip can also be switched
            // off for good: the scaffold requires BOTH. Read at click time rather than composed in,
            // matching the other writers of this store.
            val current = WindowAppearanceSettingsManager.currentSettings.value
            val restored = withCustomizeTargetRevealed(current, onLeft)
            if (restored != current) {
                WindowAppearanceSettingsManager.updateSettings(restored)
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
            }.launchIn(this)
    }

    LaunchedEffect(windowId) {
        MenuActionsHandler.closeTabEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    // First check if there are ANY tabs in the window
                    val allPanels = splitViewState.getAllPanels()
                    val totalTabs =
                        allPanels.sumOf { panel ->
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
                            val remainingTabs =
                                allPanels.sumOf { panel ->
                                    panel.tabsComponent.tabsState.value.tabs.size
                                }
                            if (remainingTabs == 0) {
                                WindowOperations.closeWindow(windowId)
                            }
                        }
                    }
                }
            }.launchIn(this)
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

                    // Discrete chords (Cmd+Opt+Arrow, Cmd+Shift+Bracket): step in tab-bar order
                    // and leave the overlay alone - there is no cycle for it to describe.
                    MenuActionsHandler.TabSwitchAction.NEXT_POSITIONAL -> {
                        comp?.switchToNextTabPositional()
                    }

                    MenuActionsHandler.TabSwitchAction.PREVIOUS_POSITIONAL -> {
                        comp?.switchToPreviousTabPositional()
                    }
                }
            }.launchIn(this)
    }

    // Listen for zoom menu actions.
    //
    // These act on the browser ActiveBrowserRegistry names for this window, not on a tab component
    // the host can type-test. The live fluck tab is the dynamic plugin's FluckBrowserTabComponent,
    // which implements ai.rever.boss.plugin.api.TabComponentWithUI directly and is not the built-in
    // FluckTabComponent (dead since registerFluck() was disabled, and its zoom methods are no-op
    // stubs anyway) - so `activeTab is FluckTabComponent` was a branch that could never be taken,
    // which is why Zoom In / Zoom Out / Actual Size / Reload did nothing on every platform.
    //
    // splitViewState.activePanelId is deliberately no longer consulted: the registry already
    // encodes it, because BrowserHandleImpl.Content reads LocalIsPanelActive, which
    // BossMainWindowPanel provides as `panelId == activePanelId`. The registry additionally ranks a
    // sidebar-slot browser below one in the main content area, which activePanelId alone cannot
    // express. Asking both would be two answers to one question.
    LaunchedEffect(windowId) {
        MenuActionsHandler.zoomInEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    ActiveBrowserRegistry.activeIn(windowId)?.zoomIn()
                }
            }.launchIn(this)
    }

    LaunchedEffect(windowId) {
        MenuActionsHandler.zoomOutEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    ActiveBrowserRegistry.activeIn(windowId)?.zoomOut()
                }
            }.launchIn(this)
    }

    LaunchedEffect(windowId) {
        MenuActionsHandler.actualSizeEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    ActiveBrowserRegistry.activeIn(windowId)?.resetZoom()
                }
            }.launchIn(this)
    }

    // Handle new File menu events
    LaunchedEffect(windowId) {
        MenuActionsHandler.openProjectEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    state.showProjectDialog = true
                }
            }.launchIn(this)
    }

    LaunchedEffect(windowId) {
        MenuActionsHandler.openFileEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    // Open file tab selection - show new tab dialog with File tab pre-selected
                    state.newTabDialogInitialType = TabType.FILE
                    state.showNewTabDialog = true
                }
            }.launchIn(this)
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
                        val terminalTab =
                            TerminalTabInfo(
                                id = "terminal-${Random.nextLong()}",
                                typeId = TerminalTabType.typeId,
                                title = "Terminal",
                                workingDirectory = DefaultWorkingDirectory.resolve(projectPath),
                            )
                        component.addTab(terminalTab)
                    }
                }
            }.launchIn(this)
    }

    LaunchedEffect(windowId) {
        MenuActionsHandler.selectWorkspaceEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    state.showTopOfMindDialog = true
                }
            }.launchIn(this)
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
            }.launchIn(this)
    }

    LaunchedEffect(windowId) {
        MenuActionsHandler.openSettingsEvents
            .onEach { (eventWindowId, section) ->
                if (eventWindowId == windowId) {
                    state.settingsWindow.open(section)
                }
            }.launchIn(this)
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
            }.launchIn(this)
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
                        tabToMove = currentTab,
                    )
                }
            }.launchIn(this)
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
                        tabToMove = currentTab,
                    )
                }
            }.launchIn(this)
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
    // Read once and derive both flags from it: subscribeAsState has to be called from
    // composition, not from inside the LaunchedEffects below.
    val activePanelTabCount =
        if (activeTabsComponent != null) {
            val activeTabsState by activeTabsComponent.tabsState.subscribeAsState()
            activeTabsState.tabs.size
        } else {
            0
        }
    val hasActiveTabs = activePanelTabCount > 0
    LaunchedEffect(windowId, activePanelId, hasActiveTabs) {
        MenuActionsHandler.updateSplitEnabled(windowId, hasActiveTabs)
    }

    // Drives the enabled flag on View > Next/Previous Tab, and the interceptor's gate for the
    // same chords. Keyed on activePanelId too: moving focus between splits changes the answer
    // without the tab list itself changing.
    LaunchedEffect(windowId, activePanelId, activePanelTabCount) {
        MenuActionsHandler.updateActivePanelTabCount(windowId, activePanelTabCount)
    }

    // Track panel count for navigation menu items
    val panelCount = splitViewState.getAllPanels().size
    LaunchedEffect(windowId, panelCount) {
        MenuActionsHandler.updatePanelCount(windowId, panelCount)
    }

    // Handle Plugin menu events
    LaunchedEffect(windowId) {
        MenuActionsHandler.revealPluginEvents
            .onEach { (eventWindowId, panelId) ->
                if (eventWindowId == windowId) {
                    // Activate the plugin (same as clicking its sidebar icon).
                    //
                    // A PANEL id, whatever the event's shape suggests: activatePlugin matches on
                    // `pluginContentId.panelId`, and passing a plugin id here is the exact miss
                    // its KDoc documents - it finds nothing and the menu item does nothing.
                    state.draggablePanelComponent.activatePlugin(panelId)
                }
            }.launchIn(this)
    }

    // Handle Browser Reload menu events. Same registry lookup, and for the same reason, as the
    // zoom handlers above; the old double gate on FluckTabInfo *and* FluckTabComponent was two
    // tests where the second could never pass.
    LaunchedEffect(windowId) {
        MenuActionsHandler.reloadBrowserEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    ActiveBrowserRegistry.activeIn(windowId)?.reload()
                }
            }.launchIn(this)
    }

    // Cmd+1..Cmd+9. The active panel owns the positions, the same way tab switching does.
    LaunchedEffect(windowId) {
        MenuActionsHandler.selectTabIndexEvents
            .onEach { (eventWindowId, index) ->
                if (eventWindowId != windowId) return@onEach
                splitViewState
                    .getPanelTabsComponent(splitViewState.activePanelId)
                    ?.selectTabByPosition(index)
            }.launchIn(this)
    }

    // Cmd+Shift+T. The history is window-scoped (see ClosedTabHistory) but the tab has to land
    // in a panel, so it reopens into the active one. Works for every tab type - the entry is a
    // TabInfo, rebuilt through its own tab-type factory.
    //
    // The liveness check is passed in because tab ids are unique across the WINDOW while a
    // tabs component only knows its own panel: without the wider view, reopening an id that a
    // sibling panel already holds would put the same id in two panels.
    LaunchedEffect(windowId) {
        MenuActionsHandler.reopenClosedTabEvents
            .onEach { eventWindowId ->
                if (eventWindowId != windowId) return@onEach
                splitViewState
                    .getPanelTabsComponent(splitViewState.activePanelId)
                    ?.reopenLastClosedTab { tabId ->
                        splitViewState.getAllPanels().any { panel ->
                            panel.tabsComponent.tabsState.value.tabs
                                .any { it.id == tabId }
                        }
                    }
            }.launchIn(this)
    }

    // Browser history and DevTools. Same ActiveBrowserRegistry lookup, and for the same reason,
    // as the zoom and reload handlers above.
    LaunchedEffect(windowId) {
        MenuActionsHandler.browserBackEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    // No canGoBack() gate: goBack() already returns early when it cannot, and
                    // canGoBack() is an untimed syncCall into the browser process. This collector
                    // runs on the EDT, so the gate only doubled the blocking cross-process calls
                    // per keypress.
                    ActiveBrowserRegistry.activeIn(windowId)?.goBack()
                }
            }.launchIn(this)
    }

    LaunchedEffect(windowId) {
        MenuActionsHandler.browserForwardEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    ActiveBrowserRegistry.activeIn(windowId)?.goForward()
                }
            }.launchIn(this)
    }

    LaunchedEffect(windowId) {
        MenuActionsHandler.browserDevToolsEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    ActiveBrowserRegistry.activeIn(windowId)?.showDevTools()
                }
            }.launchIn(this)
    }

    // Handle Save Workspace menu events
    LaunchedEffect(windowId) {
        MenuActionsHandler.saveWorkspaceEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    val currentConfig = workspaceManager.currentWorkspace.value
                    if (currentConfig != null) {
                        val currentLayout = extractCurrentWorkspace(splitViewState, windowProjectState.selectedProject.value.path)
                        val updatedConfig =
                            currentConfig.copy(
                                layout = currentLayout.layout,
                                timestamp = Clock.System.now().toEpochMilliseconds(),
                            )
                        workspaceManager.updateCurrentWorkspace(updatedConfig)
                        workspaceManager.saveCurrentWorkspace()
                        TabTreeState.markWorkspaceAsSaved(currentConfig.id)
                        StatusMessageManager.showMessage("Workspace Saved")
                    } else {
                        val currentLayout = extractCurrentWorkspace(splitViewState, windowProjectState.selectedProject.value.path)
                        val newConfig =
                            currentLayout.copy(
                                name = "Workspace ${Clock.System.now().toEpochMilliseconds() / 1000}",
                                description = "Saved workspace",
                            )
                        workspaceManager.updateCurrentWorkspace(newConfig)
                        workspaceManager.saveCurrentWorkspace()
                        StatusMessageManager.showMessage("Workspace Saved")
                    }
                }
            }.launchIn(this)
    }

    // Handle Open Codebase menu events
    LaunchedEffect(windowId) {
        MenuActionsHandler.openCodebaseEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    state.draggablePanelComponent.activatePlugin("codebase")
                }
            }.launchIn(this)
    }

    // Handle Open Global Search menu events (Issue #92)
    LaunchedEffect(windowId) {
        MenuActionsHandler.openGlobalSearchEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    state.showGlobalSearchDialog = true
                }
            }.launchIn(this)
    }

    // Handle Panel Navigation menu events (consolidated)
    LaunchedEffect(windowId) {
        val navigationFlows =
            mapOf(
                NavigationDirection.LEFT to MenuActionsHandler.navigatePanelLeftEvents,
                NavigationDirection.RIGHT to MenuActionsHandler.navigatePanelRightEvents,
                NavigationDirection.UP to MenuActionsHandler.navigatePanelUpEvents,
                NavigationDirection.DOWN to MenuActionsHandler.navigatePanelDownEvents,
            )

        navigationFlows.forEach { (direction, flow) ->
            flow
                .onEach { eventWindowId ->
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
            }.launchIn(this)
    }

    // Handle Show Plugin Wizard menu events
    LaunchedEffect(windowId) {
        MenuActionsHandler.showPluginWizardEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    // Load plugins if not already loaded (on IO thread)
                    if (state.availablePluginsForWizard.isEmpty()) {
                        val plugins =
                            withContext(Dispatchers.IO) {
                                PluginWizardIntegration.getAvailablePlugins()
                            }
                        state.availablePluginsForWizard = plugins
                    }
                    if (state.availablePluginsForWizard.isNotEmpty()) {
                        state.showPluginInstallWizard = true
                    }
                }
            }.launchIn(this)
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
            }.launchIn(this)
    }

    // Handle "Reload Panel" (by panel ID) menu events, which reload the owning plugin
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
            }.launchIn(this)
    }

    // Handle "Check for Updates" (by panel ID) menu / header-badge events
    LaunchedEffect(windowId) {
        MenuActionsHandler.checkPluginUpdatesEvents
            .onEach { (eventWindowId, panelId) ->
                if (eventWindowId == windowId) {
                    val manager = state.currentDefaultPlugin?.dynamicPluginManager ?: return@onEach
                    val pluginId = manager.getRegistrationTracker().getPluginIdForPanel(panelId) ?: return@onEach
                    val info = manager.getPluginInfo(pluginId) ?: return@onEach
                    val ref =
                        InstalledPluginRef(
                            pluginId,
                            info.manifest.displayName,
                            info.manifest.version,
                        )
                    when (val outcome = PluginUpdateBridge.checkOne(ref)) {
                        is UpdateCheckOutcome.Available -> {
                            state.pluginUpdatePrompt =
                                AvailablePluginUpdate(
                                    pluginId,
                                    outcome.displayName,
                                    outcome.currentVersion,
                                    outcome.newVersion,
                                )
                        }

                        UpdateCheckOutcome.UpToDate -> {
                            StatusMessageManager.showMessage("${info.manifest.displayName} is up to date")
                        }

                        is UpdateCheckOutcome.Incompatible -> {
                            StatusMessageManager.showMessage("Update v${outcome.advertisedLatest} needs a newer BOSS")
                        }

                        is UpdateCheckOutcome.Error -> {
                            StatusMessageManager.showMessage("Couldn't check for updates: ${outcome.message}")
                        }
                    }
                }
            }.launchIn(this)
    }

    // Handle "install the store version" events, from a panel's build tag or its version menu row.
    LaunchedEffect(windowId) {
        MenuActionsHandler.installStoreVersionEvents
            .onEach { (eventWindowId, panelId) ->
                if (eventWindowId == windowId) {
                    val manager = state.currentDefaultPlugin?.dynamicPluginManager ?: return@onEach
                    val pluginId = manager.getRegistrationTracker().getPluginIdForPanel(panelId) ?: return@onEach
                    val info = manager.getPluginInfo(pluginId) ?: return@onEach
                    val running =
                        PluginBuildRegistry.get(pluginId)?.displayVersion ?: info.manifest.version
                    // The lookup is a network call, so say something before making one: without this a
                    // click on the tag looks ignored until the store answers.
                    StatusMessageManager.showMessage("Checking the store for ${info.manifest.displayName}…")
                    state.storeVersionPrompt =
                        when (val lookup = PluginStoreVersionBridge.lookup(pluginId)) {
                            is StoreVersionLookup.Available -> {
                                StoreVersionPrompt(
                                    pluginId = pluginId,
                                    displayName = info.manifest.displayName,
                                    runningVersion = running,
                                    storeVersion = lookup.version,
                                    storeSourceUrl = lookup.sourceUrl,
                                )
                            }

                            StoreVersionLookup.NotPublished -> {
                                StoreVersionPrompt(
                                    pluginId = pluginId,
                                    displayName = info.manifest.displayName,
                                    runningVersion = running,
                                    storeVersion = null,
                                    note = "This plugin has no published version in the plugin store yet.",
                                )
                            }

                            is StoreVersionLookup.Unavailable -> {
                                StoreVersionPrompt(
                                    pluginId = pluginId,
                                    displayName = info.manifest.displayName,
                                    runningVersion = running,
                                    storeVersion = null,
                                    note = lookup.message,
                                )
                            }
                        }
                }
            }.launchIn(this)
    }

    // Handle "Uninstall Plugin" events. This only raises the confirmation; the removal itself lives
    // with the dialog in BossAppDialogs.
    LaunchedEffect(windowId) {
        MenuActionsHandler.uninstallPluginEvents
            .onEach { (eventWindowId, panelId) ->
                if (eventWindowId == windowId) {
                    val manager = state.currentDefaultPlugin?.dynamicPluginManager ?: return@onEach
                    val tracker = manager.getRegistrationTracker()
                    val pluginId = tracker.getPluginIdForPanel(panelId) ?: return@onEach
                    val info = manager.getPluginInfo(pluginId) ?: return@onEach
                    // The menu item is disabled for these, so this is the belt-and-braces half: the
                    // manager would refuse the unload anyway and leave the plugin half-removed.
                    if (info.manifest.systemPlugin || !info.manifest.canUnload) {
                        StatusMessageManager.showMessage(
                            "${info.manifest.displayName} is a system plugin and cannot be uninstalled",
                        )
                        return@onEach
                    }
                    // A bundled plugin passes the manifest gate but is copied back into the plugins
                    // directory at the next launch, so removing it would quietly undo itself.
                    val veto = DynamicPluginManager.pluginRemovalVeto?.invoke(pluginId)
                    if (veto != null) {
                        StatusMessageManager.showMessage("${info.manifest.displayName} $veto")
                        return@onEach
                    }
                    // Panel ids and jar path captured NOW: the uninstall clears the tracker and the
                    // plugin's state, so neither is available afterwards.
                    state.pluginUninstallPrompt =
                        PluginUninstallPrompt(
                            pluginId = pluginId,
                            displayName = info.manifest.displayName,
                            version = info.manifest.version,
                            jarPath = info.jarPath,
                            panelIds = tracker.getPanelsForPlugin(pluginId),
                        )
                }
            }.launchIn(this)
    }
}
