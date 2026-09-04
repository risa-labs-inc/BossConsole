package ai.rever.boss.app

import ai.rever.boss.components.bars.horizontal.StatusMessageManager
import ai.rever.boss.components.dialogs.TabType
import ai.rever.boss.components.events.DashboardEventBus
import ai.rever.boss.components.events.DashboardOpenTabTypeEvent
import ai.rever.boss.components.events.FileEventBus
import ai.rever.boss.components.events.GitTerminalEventBus
import ai.rever.boss.components.events.HtmlFileEventBus
import ai.rever.boss.components.events.NavigationTargetBus
import ai.rever.boss.components.events.PanelEventBus
import ai.rever.boss.components.events.RunEventBus
import ai.rever.boss.components.events.RunnerTerminalEventBus
import ai.rever.boss.components.events.TerminalEventBus
import ai.rever.boss.components.events.TerminalLinkEventBus
import ai.rever.boss.components.events.URLEventBus
import ai.rever.boss.components.events.WorkspaceEventBus
import ai.rever.boss.components.plugin.DependentRestartEventBus
import ai.rever.boss.components.plugin.MissingHandlerPluginEventBus
import ai.rever.boss.components.plugin.PanelIds
import ai.rever.boss.components.plugin.PluginDependencyEventBus
import ai.rever.boss.components.plugin.resolveRegisteredPanelId
import ai.rever.boss.components.plugin.shouldShowMissingDependency
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.workspaces.WorkspaceSerializer
import ai.rever.boss.components.workspaces.applyWorkspace
import ai.rever.boss.components.workspaces.requiresProject
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.dashboard.DashboardStatsManager
import ai.rever.boss.git.GitTerminalService
import ai.rever.boss.plugin.api.NewTabContext
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.Panel.Companion.right
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import ai.rever.boss.project.DefaultWorkingDirectory
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
import ai.rever.boss.utils.logging.ComponentLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.WindowProjectState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            }.launchIn(this)
    }

    // Listen for diff open events (git data provider's openDiff) - Issue #506 window filter
    LaunchedEffect(splitViewState, windowId) {
        FileEventBus.diffOpenEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach { event ->
                splitViewState.openDiffTabInActivePanel(event)
            }.launchIn(this)
    }

    // Listen for terminal open events - now handled by split state
    // Issue #506: Filter by window to prevent terminal opening in all windows
    LaunchedEffect(splitViewState, windowId) {
        TerminalEventBus.terminalOpenEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach { event ->
                val command = event.command
                if (event.requiresConfirmation && command != null) {
                    // Show the operator the command and let them decide; the
                    // prompt in BossAppDialogs opens the terminal on confirm.
                    logger.info(
                        LogCategory.TERMINAL,
                        "Holding an externally requested terminal command for confirmation",
                        mapOf("windowId" to windowId),
                    )
                    state.pendingTerminalCommand = PendingTerminalCommand(command, event.workingDirectory)
                } else {
                    splitViewState.openTerminalInActivePanel(command, event.workingDirectory)
                    DashboardStatsManager.recordTerminalSession()
                }
            }.launchIn(this)

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
                    val success =
                        RunnerTerminalService.openInSidebarTerminal(
                            windowId = windowId,
                            configId = event.configId,
                            command = event.command,
                            // Same reason and same shape as openRunnerInMainPanel: an unset
                            // run-configuration working directory arrives null and would start
                            // the shell in the home directory, and the selected project comes
                            // before the no-project default.
                            workingDirectory =
                                DefaultWorkingDirectory.selectedOrNull(event.workingDirectory)
                                    ?: DefaultWorkingDirectory.resolve(
                                        windowProjectState.selectedProject.value.path,
                                    ),
                            tabTitle = "Run: ${event.configName}",
                            isRerun = event.isRerun,
                        )

                    if (!success) {
                        // Fallback to main panel if sidebar terminal not available
                        openRunnerInMainPanel(event, splitViewState, windowProjectState.selectedProject.value.path)
                    }
                } else {
                    // Open in main panel (original behavior)
                    openRunnerInMainPanel(event, splitViewState, windowProjectState.selectedProject.value.path)
                }
            }.launchIn(this)

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
            }.launchIn(this)

        // Stop runner terminal events
        // Note: Ctrl+C is sent by RunnerTerminalService.stopRunner() via TerminalAPIAccess
        // Issue #506: Filter by window to prevent stopping in all windows
        RunnerTerminalEventBus.stopEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach {
                // Ctrl+C is already sent by the service - this event is for any additional UI handling
            }.launchIn(this)
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
                    operationName = event.operationName,
                )
            }.launchIn(this)
    }

    // A plugin the user just installed declares a dependency that is not present. Only
    // user-initiated installs report here (see PluginLoaderDelegateImpl), so this cannot
    // fire during startup restore or the api hot-swap's reload-all.
    LaunchedEffect(windowId) {
        PluginDependencyEventBus.missingDependencies
            .collect { prompt ->
                // Re-check rather than trusting the report: two dependents of one missing
                // plugin each raise a prompt, so installing for the first satisfies the
                // second, whose dialog would otherwise claim something untrue and reinstall
                // what is already loaded. Off the UI thread because the check stats the jar.
                val present =
                    withContext(Dispatchers.IO) {
                        prompt.installer.isInstalled(prompt.missing.missingPluginId)
                    }
                // The rule itself lives in `shouldShowMissingDependency`, so it can be tested
                // against rather than restated here. Notably it exempts a prompt a person asked
                // for by pressing something: without that, dismissing the offer once left the
                // control silent for the rest of the session.
                val show =
                    shouldShowMissingDependency(
                        prompt = prompt,
                        present = present,
                        declined = PluginDependencyEventBus.wasDeclined(prompt.missing),
                    )
                if (!show) {
                    return@collect
                }
                // Reset here rather than relying on the previous dialog's exit path having
                // cleared them: the three fields are reused for every prompt, and ordering
                // between that clear and this assignment should not be load-bearing.
                state.installingMissingDependency = false
                state.missingDependencyError = null
                state.pendingMissingPluginDependency = prompt
                // Back-pressure instead of a queue: the next prompt stays in the channel until
                // this one is answered, so a second missing dependency is asked about after the
                // first rather than replacing it or being dropped. Cancelling this effect (the
                // window closing) leaves whatever is still in the channel for another window -
                // though a prompt already received here and not yet shown does go with it.
                snapshotFlow { state.pendingMissingPluginDependency }.first { it == null }
            }
    }

    // BOSS was asked to open something - a link the OS handed over, a file
    // double-clicked in Finder, a path dropped on a panel - and the plugin that
    // renders it is not running. Without this the tab was silently dropped
    // ("Dropped tab - no factory registered for its type") and the user saw
    // nothing happen at all.
    LaunchedEffect(windowId) {
        MissingHandlerPluginEventBus.missingHandlers
            .collect { prompt ->
                // Declined since it was raised: two files can each raise a prompt
                // for the same plugin before either is shown, and the second must
                // not re-ask a question already answered. The bus filters at
                // report time too; this covers the gap between the two.
                if (MissingHandlerPluginEventBus.wasDeclined(prompt.missing.pluginId)) {
                    return@collect
                }
                // Resolved on its own since it was raised: the plugin may have been
                // installed from the Toolbox, or registration may simply have run
                // past the first bounded wait. TabTypeAvailability's second wait
                // then completes and the deferred open succeeds - and without this
                // the queued dialog still appeared, offering to install a plugin
                // that is now running. The dependency-bus collector guards the
                // analogous case by re-checking isInstalled.
                //
                // Compared on the type STRING, not by looking up a TabTypeId:
                // TabTypeId is a data class whose equality includes pluginId and
                // defaultOrder, so a constructed one misses a type that is plainly
                // registered - the trap panelid-defaultorder-silent-miss records.
                val alreadyRegistered =
                    state.tabRegistry.getAllTabTypes().any { candidate ->
                        candidate.typeId.typeId == prompt.missing.tabTypeId
                    }
                if (alreadyRegistered) {
                    logger.debug(
                        LogCategory.UI,
                        "Not asking about a tab-type plugin that has since registered",
                        mapOf("plugin" to prompt.missing.pluginId, "tabType" to prompt.missing.tabTypeId),
                    )
                    return@collect
                }
                state.resolvingMissingHandlerPlugin = false
                state.missingHandlerPluginError = null
                state.pendingMissingHandlerPlugin = prompt
                // Back-pressure rather than a queue, exactly as above: a second
                // missing plugin waits in the channel instead of replacing a
                // dialog someone is reading.
                snapshotFlow { state.pendingMissingHandlerPlugin }.first { it == null }
            }
    }

    // An unload is waiting on a person: other plugins depend on the one being updated or
    // removed, and confirming restarts them. Unlike the prompt above, this one is BLOCKING an
    // operation - the unload suspends on `prompt.answer` - so every exit from here has to
    // complete that deferred, including the one where this effect is cancelled with a dialog
    // still up. Without the try/finally, closing the window mid-dialog would leave the caller
    // waiting out the bus's five-minute timeout.
    LaunchedEffect(windowId) {
        try {
            DependentRestartEventBus.restartPrompts
                .collect { prompt ->
                    state.pendingDependentRestart = prompt
                    // Back-pressure exactly as above: a second unload's question waits in the
                    // channel rather than replacing a dialog someone is reading.
                    snapshotFlow { state.pendingDependentRestart }.first { it == null }
                    // The dialog's own handlers answer; this is the backstop for a prompt
                    // cleared without one, which would otherwise hang the unload.
                    prompt.answer.complete(false)
                }
        } finally {
            state.pendingDependentRestart?.answer?.complete(false)
            state.pendingDependentRestart = null
        }
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
            }.launchIn(this)
    }

    // Listen for HTML file open prompt events
    LaunchedEffect(splitViewState, windowId) {
        HtmlFileEventBus.openPromptEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach { event ->
                state.pendingHtmlFilePath = event.filePath
                state.pendingHtmlFileName = event.fileName
                state.showHtmlFileOpenDialog = true
            }.launchIn(this)
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
            }.launchIn(this)

        RunEventBus.stopEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach { event ->
                val configIdToStop = event.configId
                if (configIdToStop != null) {
                    RunExecutionService.stop(configIdToStop)
                } else {
                    RunExecutionService.stopAll()
                }
            }.launchIn(this)

        // Scan events are still handled for explicit scan requests (e.g., from Run Configurations plugin)
        // Issue #506: Filter by sourceWindowId for multi-window support
        RunEventBus.scanEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach { event ->
                RunConfigurationManager.scanProject(event.projectPath)
            }.launchIn(this)
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
                    logger.warn(
                        LogCategory.WORKSPACE,
                        "Workspace load from CLI failed",
                        mapOf(
                            "path" to event.workspacePath,
                        ),
                        error = e,
                    )
                }
            }.launchIn(this)
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
                    fun findPanelInfo() =
                        state.panelRegistry.getAllPanels().find {
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
                            state.panelRegistry::removeChangeListener,
                        ) { findPanelInfo() != null }
                        panelInfo = findPanelInfo()
                        if (panelInfo == null) {
                            logger.warn(
                                LogCategory.UI,
                                "Dropping panel open event - panel never registered",
                                mapOf(
                                    "panelId" to event.panelId.panelId,
                                    "pluginId" to event.panelId.pluginId,
                                ),
                            )
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
                            val targetPanel =
                                when (panelSlot) {
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
                    logger.warn(
                        LogCategory.UI,
                        "Panel open event handling failed",
                        mapOf(
                            "panelId" to event.panelId.panelId,
                        ),
                        error = e,
                    )
                }
            }.launchIn(this)
    }

    // Listen for "open this panel as a main-area tab" requests — SplitViewOperations.openPanelAsTab.
    // Deliberately routed to requestOpenAsTab rather than doing the work here: that is the same
    // entry point the header drag-out uses, so a plugin inherits the move semantics the host's own
    // promote path has (the cached component and its state carry into the tab, the sidebar copy is
    // collapsed without being destroyed) and the single-instance rule (already open => focus that
    // tab, never a second copy). ProcessPendingPromoteToTab, which has SplitView access, performs it.
    LaunchedEffect(state.draggablePanelComponent, state.panelRegistry, windowId) {
        PanelEventBus.panelPromoteToTabEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach { event ->
                // Normalise the id against the registry first. A plugin knows the panel's id
                // string but not its defaultOrder, and everything downstream — the component
                // store, the hosted-as-tab counts — keys on the whole data class, so an id
                // carrying a guessed order would promote nothing and say nothing. The panel-open
                // handler above matches the same way; this shares the rule rather than copying it.
                val resolved = state.panelRegistry.resolveRegisteredPanelId(event.panelId)
                if (resolved == null) {
                    logger.warn(
                        LogCategory.UI,
                        "Dropping panel promote-to-tab event - panel is not registered",
                        mapOf(
                            "panelId" to event.panelId.panelId,
                            "pluginId" to event.panelId.pluginId,
                        ),
                    )
                    return@onEach
                }
                state.draggablePanelComponent.requestOpenAsTab(resolved)
            }.launchIn(this)
    }

    // Listen for panel close events
    // Issue #506: Filter by window to prevent panel closing in all windows
    LaunchedEffect(state.draggablePanelComponent, windowId) {
        PanelEventBus.panelCloseEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach { event ->
                // Find which panel contains this component
                val panels =
                    listOf(
                        bottom,
                        left.top,
                        left.bottom,
                        right.top,
                        right.bottom,
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
            }.launchIn(this)
    }

    // Listen for panel toggle events (open if closed, close if open)
    // Issue #506: Filter by window to prevent panel toggling in all windows
    LaunchedEffect(state.draggablePanelComponent, state.panelRegistry, windowId) {
        // Captured so a deferred registry wait below can run as a child of this effect rather
        // than on the collector itself. Cancelled with the effect, so nothing outlives the window.
        val effectScope = this
        PanelEventBus.panelToggleEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach { event ->
                try {
                    val panels =
                        listOf(
                            bottom,
                            left.top,
                            left.bottom,
                            right.top,
                            right.bottom,
                        )

                    // Check if the panel is currently visible with this content
                    var foundVisible = false
                    for (panel in panels) {
                        val panelContentId = state.draggablePanelComponent.getPanelContentId(panel)
                        if (panelContentId?.panelId == event.panelId.panelId &&
                            state.draggablePanelComponent.isVisible(panel)
                        ) {
                            // Panel is visible - close it
                            state.draggablePanelComponent.setPanelVisible(panel, false)
                            state.panelComponentStore.removeComponent(event.panelId)
                            foundVisible = true
                            break
                        }
                    }

                    if (!foundVisible) {
                        // Panel is not visible - open it using the same logic as panelOpenEvents
                        fun findPanelInfo() =
                            state.panelRegistry.getAllPanels().find {
                                it.id.panelId == event.panelId.panelId &&
                                    it.id.pluginId == event.panelId.pluginId
                            }

                        fun activate(panelInfo: PanelInfo) {
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

                        val immediate = findPanelInfo()
                        if (immediate != null) {
                            activate(immediate)
                        } else {
                            // Wait for late plugin registration rather than dropping the toggle,
                            // as panelOpenEvents does - but in a child coroutine, because
                            // `onEach` runs sequentially on ONE collector and this bus is not
                            // home-only. `BossBottomBar` (console) and `PerformanceState` emit on
                            // it too, so awaiting inline let a toggle for a panel that never
                            // registers hold every later toggle for the full 15s timeout.
                            //
                            // The fast path stays inline, so ordinary toggles keep their existing
                            // sequential behaviour and only the already-degenerate case defers.
                            effectScope.launch {
                                // Its own runCatching: the enclosing try/catch is lexical, and a
                                // child coroutine's body runs outside it. Without this, a throw
                                // from `activate` (which ends in plugin-supplied `onClick`) would
                                // reach the LaunchedEffect job, cancel the collector, and stop
                                // panel toggling in this window for the rest of its life -
                                // silently. Every path through this handler was caught before the
                                // wait was deferred; this keeps that true.
                                runCatching {
                                    awaitRegistryCondition(
                                        state.panelRegistry::addChangeListener,
                                        state.panelRegistry::removeChangeListener,
                                    ) { findPanelInfo() != null }
                                    val late = findPanelInfo()
                                    if (late == null) {
                                        logger.warn(
                                            LogCategory.UI,
                                            "Dropping panel toggle event - panel never registered",
                                            mapOf(
                                                "panelId" to event.panelId.panelId,
                                                "pluginId" to event.panelId.pluginId,
                                            ),
                                        )
                                    } else {
                                        activate(late)
                                    }
                                }.onFailure { error ->
                                    logger.warn(
                                        LogCategory.UI,
                                        "Deferred panel toggle failed",
                                        mapOf("panelId" to event.panelId.panelId),
                                        error = error,
                                    )
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.warn(
                        LogCategory.UI,
                        "Panel toggle event handling failed",
                        mapOf(
                            "panelId" to event.panelId.panelId,
                        ),
                        error = e,
                    )
                }
            }.launchIn(this)
    }

    // Listen for Dashboard events from Fluck tabs (when Dashboard is shown in empty browser tabs)
    // Issue #506: Filter by window to prevent events affecting all windows
    LaunchedEffect(splitViewState, windowId) {
        // Captured so a deferred registry wait can run as a child of this effect rather than on
        // the collector; see the openTabTypeEvents handler below.
        val tabEffectScope = this
        // Handle file open events.
        //
        // Delegated to FileEventBus rather than calling openFileInActivePanel directly,
        // because FileEventBus is the only path that records the open: its callback drives
        // RecentFilesManager.recordFileOpen and DashboardStatsManager.recordFileOpen. Opening
        // a file straight into the split view is what made a file opened FROM the home
        // screen never bump its own recency - it kept sliding down the very list it was
        // clicked in - and never increment the counter the old header displayed.
        DashboardEventBus.openFileEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach { event ->
                FileEventBus.openFile(
                    filePath = event.path,
                    sourceWindowId = windowId,
                    projectPath = windowProjectState.selectedProject.value.path,
                )
            }.launchIn(this)

        // Handle URL open in new tab events
        DashboardEventBus.openUrlInNewTabEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach { event ->
                splitViewState.openUrlInActivePanel(event.url, "Loading...")
            }.launchIn(this)

        // Handle new tab events
        DashboardEventBus.newTabEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach {
                state.showNewTabDialog = true
            }.launchIn(this)

        // Handle new terminal events
        DashboardEventBus.newTerminalEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach {
                val timestamp = System.currentTimeMillis()
                val projectPath = windowProjectState.selectedProject.value.path
                val terminalTab =
                    TerminalTabInfo(
                        id = "terminal-$timestamp",
                        typeId = TerminalTabType.typeId,
                        title = "Terminal",
                        icon = TerminalTabType.icon,
                        workingDirectory = DefaultWorkingDirectory.resolve(projectPath),
                    )
                splitViewState.getActiveTabsComponent()?.addTab(terminalTab)
            }.launchIn(this)

        // Handle project dialog events
        DashboardEventBus.showProjectDialogEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach {
                state.showProjectDialog = true
            }.launchIn(this)

        // Handle file dialog events
        DashboardEventBus.showFileDialogEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach {
                // File dialog is typically handled by a system file chooser
                // For now, show new tab dialog with file option
                //
                // The initial type is what makes it "with file option": the dialog reads
                // `newTabDialogInitialType` and the app menu's Open File sets it the same way.
                // Without it this opened the dialog on its default tab, which is a regression the
                // home screen would have introduced by routing here - the call site it replaced
                // set `selectedTabType = TabType.FILE` itself. Unreachable before now, since this
                // handler had no emitters.
                state.newTabDialogInitialType = TabType.FILE
                state.showNewTabDialog = true
            }.launchIn(this)

        // Handle new project events
        DashboardEventBus.showNewProjectEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach {
                state.showNewProjectDialog = true
            }.launchIn(this)

        // Handle workspace layout events from the home screen's cards.
        DashboardEventBus.applyWorkspaceEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach { event ->
                // Resolved against the live list rather than carried in the event, so the
                // home screen, the top bar menu and the app menu all apply the same
                // workspace for the same name - including workspaces saved to disk, which
                // the split-template list this replaced could not see at all.
                val workspace = workspaceManager.workspaces.value.find { it.id == event.workspaceId }
                if (workspace == null) {
                    logger.warn(
                        LogCategory.WORKSPACE,
                        "Home screen asked for a workspace that is no longer in the list",
                        mapOf("workspaceId" to event.workspaceId),
                    )
                    return@onEach
                }

                // A project-shaped workspace with no project is the hazard
                // `shouldApplyOnFreshStart` exists to avoid: `{projectPath}` falls back to
                // ~/BossProjects, so Claude Code here would start an agent in a directory
                // nobody chose. Not a new risk - the split-template card had it too - but
                // the home screen is now what a fresh launch opens on, so it is the click
                // most likely to be made first. Say so instead of doing it.
                if (workspace.requiresProject() &&
                    windowProjectState.selectedProject.value.path
                        .isEmpty()
                ) {
                    StatusMessageManager.showMessage(
                        "Open a project first - \"${workspace.name}\" builds its tabs from the project you are in",
                    )
                    return@onEach
                }

                // Preserve, load, apply: the same three steps the top bar's switch takes,
                // so switching away and back keeps the tabs that were open.
                val currentWorkspace = workspaceManager.currentWorkspace.value
                if (currentWorkspace != null && currentWorkspace.id.isNotEmpty()) {
                    splitViewState.preserveCurrentState(currentWorkspace.id, currentWorkspace.name)
                }
                workspaceManager.loadWorkspace(workspace)
                applyWorkspace(workspace, splitViewState, windowProjectState)
            }.launchIn(this)

        // Handle settings window events from the home screen.
        DashboardEventBus.showSettingsEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach {
                state.settingsWindow.open()
            }.launchIn(this)

        // Handle global search events from the home screen's search affordance. The same dialog
        // the search.open shortcut raises, rather than a second search of its own.
        DashboardEventBus.openSearchEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach {
                state.showGlobalSearchDialog = true
            }.launchIn(this)

        // Handle "open this registered tab type" events from the home screen's tool grid.
        //
        // The same two steps NewTabDialog takes (resolve the type, let the plugin build its
        // TabInfo), so a tool tile and the dialog tile for one plugin cannot diverge. Waits
        // for late registration like the panel handlers, since the grid can be clicked while
        // plugins are still loading.
        DashboardEventBus.openTabTypeEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach { event ->
                // Matched over getAllTabTypes() by field, NOT by rebuilding a TabTypeId and
                // calling getTabTypeInfo. TabTypeId is a data class over (typeId, pluginId), so
                // `TabTypeId("arcade")` leaves pluginId "" and does not equal the
                // `TabTypeId("arcade", "ai.rever.boss.plugin.dynamic.arcade")` the plugin
                // registered - the map lookup missed and every Arcade click was dropped as "tab
                // type never registered". The panel handlers above compare panelId/pluginId field
                // by field for the same reason.
                fun typeInfo() =
                    state.tabRegistry.getAllTabTypes().firstOrNull { candidate ->
                        candidate.typeId.typeId == event.typeId &&
                            // Blank means the caller did not know it, so match on typeId alone.
                            (event.typePluginId.isBlank() || candidate.typeId.pluginId == event.typePluginId)
                    }

                // Deferred when the type is not registered yet, for the reason the panel handler
                // documents: `onEach` is one sequential collector, so awaiting inline lets one
                // event for a type that never registers hold every later tool click for the full
                // 15s timeout, and then start suspending `emit` once the buffer fills. The two
                // handlers deliberately share this shape rather than teaching opposite lessons.
                val immediate = typeInfo()
                if (immediate == null) {
                    tabEffectScope.launch {
                        // runCatching for the same reason as the panel handler's child: this body
                        // runs outside the enclosing scope's error handling, and an escape would
                        // cancel the collector and stop every later tool click in this window.
                        runCatching {
                            awaitRegistryCondition(
                                state.tabRegistry::addChangeListener,
                                state.tabRegistry::removeChangeListener,
                            ) { typeInfo() != null }
                            val late = typeInfo()
                            if (late == null) {
                                logger.warn(
                                    LogCategory.UI,
                                    "Dropping open tab type event - tab type never registered",
                                    mapOf("typeId" to event.typeId),
                                )
                            } else {
                                openRegisteredTabType(late, event, splitViewState, windowProjectState, logger)
                            }
                        }.onFailure { error ->
                            logger.warn(
                                LogCategory.UI,
                                "Deferred tab type open failed",
                                mapOf("typeId" to event.typeId),
                                error = error,
                            )
                        }
                    }
                    return@onEach
                }
                openRegisteredTabType(immediate, event, splitViewState, windowProjectState, logger)
            }.launchIn(this)
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
            }.launchIn(this)

        // Observe tab count AND processing state (URLs + Terminals + Files + Workspace Restoration) reactively
        // This eliminates all timing assumptions by waiting for actual completion
        snapshotFlow {
            val allPanels = splitViewState.getAllPanels()
            val totalTabs =
                allPanels.sumOf { panel ->
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
                val isRestorationComplete: Boolean,
            )
            ProcessingState(totalTabs, isProcessingURLs, isProcessingTerminals, isProcessingFiles, state.workspaceRestorationComplete)
        }.debounce(200) // Wait for 200ms of stability
            .take(1) // Only take first stabilized value
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

/**
 * Build a plugin's `TabInfo` and add it to the active panel.
 *
 * Extracted so the immediate and the deferred path through `openTabTypeEvents` cannot drift: they
 * differ only in whether the type was already registered.
 */
private fun openRegisteredTabType(
    info: TabTypeInfo,
    event: DashboardOpenTabTypeEvent,
    splitViewState: SplitViewState,
    windowProjectState: WindowProjectState,
    logger: ComponentLogger,
) {
    // Plugin code, so crash-isolated: a throwing createTabInfo must not take down the window that
    // asked. Null means the plugin rejected the input.
    val tabInfo =
        try {
            info.createTabInfo(
                event.input.trim(),
                NewTabContext(
                    projectPath = windowProjectState.selectedProject.value.path,
                    windowId = event.sourceWindowId,
                ),
            )
        } catch (e: Exception) {
            logger.warn(
                LogCategory.UI,
                "Plugin createTabInfo failed for a home screen tool",
                mapOf("typeId" to event.typeId),
                error = e,
            )
            null
        }
    if (tabInfo == null) {
        StatusMessageManager.showMessage("Could not open ${info.displayName}")
        return
    }
    // Reported, not dropped: everything else in this handler says why it failed, and a click that
    // opens no tab and logs nothing is the class of defect this whole change exists to remove.
    val tabs = splitViewState.getActiveTabsComponent()
    if (tabs == null) {
        logger.warn(
            LogCategory.UI,
            "Could not open a home screen tool - no active tabs component",
            mapOf("typeId" to event.typeId),
        )
        StatusMessageManager.showMessage("Could not open ${info.displayName} here")
    } else {
        tabs.addTab(tabInfo)
    }
}
