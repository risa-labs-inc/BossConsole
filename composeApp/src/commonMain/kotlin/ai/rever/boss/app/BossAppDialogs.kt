package ai.rever.boss.app

import ai.rever.boss.components.bars.horizontal.StatusMessageManager
import ai.rever.boss.components.dialogs.CloneProjectDialog
import ai.rever.boss.components.dialogs.ConfirmationDialog
import ai.rever.boss.components.dialogs.GlobalSearchDialog
import ai.rever.boss.components.dialogs.LogoutConfirmationDialog
import ai.rever.boss.components.dialogs.NewProjectWizardDialog
import ai.rever.boss.components.dialogs.NewTabDialog
import ai.rever.boss.components.dialogs.ProjectOpenModeDialog
import ai.rever.boss.components.dialogs.ProjectSelectionDialog
import ai.rever.boss.components.dialogs.ShortcutHelpDialog
import ai.rever.boss.components.dialogs.TabType
import ai.rever.boss.components.dialogs.TerminalLinkOpenDialog
import ai.rever.boss.components.dialogs.ToolLauncherDialog
import ai.rever.boss.components.events.DashboardEventBus
import ai.rever.boss.components.events.FileEventBus
import ai.rever.boss.components.events.PanelEventBus
import ai.rever.boss.components.plugin.DependentRestartDeclinedException
import ai.rever.boss.components.plugin.DependentRestartDialog
import ai.rever.boss.components.plugin.DynamicPluginManager
import ai.rever.boss.components.plugin.MissingDependencyDialog
import ai.rever.boss.components.plugin.MissingHandlerPluginDialog
import ai.rever.boss.components.plugin.MissingHandlerPluginEventBus
import ai.rever.boss.components.plugin.PluginDependencyEventBus
import ai.rever.boss.components.plugin.PluginLoadGateHost
import ai.rever.boss.components.plugin.PluginLoadRemedyAccess
import ai.rever.boss.components.plugin.PluginStoreVersionBridge
import ai.rever.boss.components.plugin.PluginUpdateBridge
import ai.rever.boss.components.plugin.openTopOfMindQuickSwitcher
import ai.rever.boss.components.plugin.providers.GenericDialogHostContent
import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.components.registery.PanelComponentStoreRegistry
import ai.rever.boss.components.registery.TabTypeId
import ai.rever.boss.components.windows.SettingsWindow
import ai.rever.boss.components.wizard.plugin.PluginWizardIntegration
import ai.rever.boss.components.wizard.plugin.PluginWizardWindow
import ai.rever.boss.components.wizard.plugin.rememberPluginInstallWizardState
import ai.rever.boss.components.workspaces.SelectWorkspaceDialog
import ai.rever.boss.components.workspaces.applyWorkspace
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.dashboard.DashboardStatsManager
import ai.rever.boss.icons.FileIcons
import ai.rever.boss.keymap.KeymapSettingsManager
import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.platform.rememberDirectoryPicker
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.sandbox.notification.ToastMessage
import ai.rever.boss.plugin.sandbox.notification.ToastType
import ai.rever.boss.plugin.tab.codeeditor.EditorTabInfo
import ai.rever.boss.plugin.tab.jupyter.JupyterTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import ai.rever.boss.plugin.ui.BossAlertDialog
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.project.DefaultWorkingDirectory
import ai.rever.boss.run.RunConfigurationManager
import ai.rever.boss.run.RunExecutionService
import ai.rever.boss.search.SearchSources
import ai.rever.boss.search.ToolSearchRecord
import ai.rever.boss.services.auth.UserDataStorage
import ai.rever.boss.services.bookmarks.BookmarkAPIAccess
import ai.rever.boss.terminal.TerminalLinkSettingsManager
import ai.rever.boss.utils.extractFileName
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.MenuActionsHandler
import ai.rever.boss.window.Project
import ai.rever.boss.window.WindowOperations
import ai.rever.boss.window.selectProjectInWindow
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Every dialog and auxiliary window BossApp can show, driven by the visibility
 * flags on [BossAppState]: new tab, quick switcher, global search, settings,
 * shortcut help, terminal link prompt, project open/new/clone flows, the plugin
 * install wizard, plugin update prompts, and the generic plugin dialog host.
 */
@Composable
internal fun BossAppDialogs(state: BossAppState) {
    val windowId = state.windowId
    val logger = state.logger
    val coroutineScope = state.coroutineScope
    val splitViewState = state.splitViewState
    val windowProjectState = state.windowProjectState
    val selectedProject by windowProjectState.selectedProject.collectAsState()

    // Keymap settings (used by ShortcutHelpDialog)
    val keymapSettings by KeymapSettingsManager.currentSettings.collectAsState()

    // Plugin update confirmation prompt (from "Check for Updates" or the header badge).
    state.pluginUpdatePrompt?.let { prompt ->
        ConfirmationDialog(
            title = "Update Available",
            message = "Update \"${prompt.displayName}\" from v${prompt.currentVersion} to v${prompt.newVersion}?",
            confirmText = "Update",
            onDismiss = { state.pluginUpdatePrompt = null },
            onConfirm = {
                val mgr = state.currentDefaultPlugin?.dynamicPluginManager
                if (mgr != null) {
                    coroutineScope.launch {
                        StatusMessageManager.showMessage("Updating ${prompt.displayName}…")
                        val r = PluginUpdateBridge.performUpdate(prompt.pluginId, mgr)
                        val cause = r.exceptionOrNull()
                        when {
                            r.isSuccess -> {
                                StatusMessageManager.showMessage(
                                    "Updated ${prompt.displayName} to v${r.getOrNull()}",
                                )
                            }

                            // Declining the dependent-restart prompt is an answer, not a fault:
                            // nothing downloaded, nothing unloaded. "Update failed" would read
                            // as something having gone wrong with a choice the user just made.
                            cause is DependentRestartDeclinedException -> {
                                StatusMessageManager.showMessage(
                                    "${prompt.displayName} was left on v${prompt.currentVersion}",
                                )
                            }

                            else -> {
                                StatusMessageManager.showMessage("Update failed: ${cause?.message}")
                            }
                        }
                    }
                }
            },
        )
    }

    // "You are not running the released build" - from a panel's build tag or its version menu row.
    state.storeVersionPrompt?.let { prompt ->
        val storeVersion = prompt.storeVersion
        if (storeVersion == null) {
            // Nothing to install, so this is a notice, not a choice. BossAlertDialog rather than
            // ConfirmationDialog: that one always renders its own Cancel, which would put "Cancel"
            // and "Close" side by side, both doing the same thing.
            BossAlertDialog(
                onDismissRequest = { state.storeVersionPrompt = null },
                title = { Text("No Store Version", color = BossTheme.colors.textPrimary) },
                text = {
                    Text(
                        "\"${prompt.displayName}\" is running ${prompt.runningVersion}. " +
                            (prompt.note ?: "There is no store version to install."),
                        color = BossTheme.colors.textSecondary,
                    )
                },
                confirmButton = {
                    TextButton(onClick = { state.storeVersionPrompt = null }) {
                        Text("Close", color = BossTheme.colors.signalText)
                    }
                },
            )
        } else {
            ConfirmationDialog(
                title = "Install Store Version",
                message =
                    "\"${prompt.displayName}\" is running ${prompt.runningVersion}, which the plugin store " +
                        "did not publish. Replace it with the store version v$storeVersion?",
                confirmText = "Install",
                // Not the default alert red: this installs a released build, it does not destroy
                // anything. The local jar is left on disk.
                confirmColor = BossTheme.colors.signal,
                onDismiss = { state.storeVersionPrompt = null },
                onConfirm = {
                    val mgr = state.currentDefaultPlugin?.dynamicPluginManager
                    if (mgr != null) {
                        coroutineScope.launch {
                            StatusMessageManager.showMessage("Installing ${prompt.displayName} v$storeVersion…")
                            // The swap itself is detached inside the bridge, so closing this window
                            // only stops us reporting the result, never the swap mid-flight.
                            val r =
                                PluginStoreVersionBridge.installStoreVersion(
                                    prompt.pluginId,
                                    storeVersion,
                                    prompt.storeSourceUrl,
                                    mgr,
                                )
                            val cause = r.exceptionOrNull()
                            when {
                                r.isSuccess -> {
                                    StatusMessageManager.showMessage(
                                        "${prompt.displayName} is now on the store version v${r.getOrNull()}",
                                    )
                                }

                                cause is DependentRestartDeclinedException -> {
                                    StatusMessageManager.showMessage(
                                        "${prompt.displayName} was left on ${prompt.runningVersion}",
                                    )
                                }

                                else -> {
                                    StatusMessageManager.showMessage(
                                        "Could not install the store version: ${cause?.message}",
                                    )
                                }
                            }
                        }
                    }
                },
            )
        }
    }

    // "Remove this plugin?" - from a panel's overflow menu.
    state.pluginUninstallPrompt?.let { prompt ->
        ConfirmationDialog(
            title = "Uninstall Plugin",
            message =
                "Uninstall \"${prompt.displayName}\" v${prompt.version}? Its panels close and its jar is " +
                    "deleted. This cannot be undone.",
            confirmText = "Uninstall",
            onDismiss = { state.pluginUninstallPrompt = null },
            onConfirm = {
                val mgr = state.currentDefaultPlugin?.dynamicPluginManager
                if (mgr != null) {
                    coroutineScope.launch {
                        // Unload plus jar, sidecar and installed.json row, all detached inside the
                        // hook: a window closing mid-removal must not leave the plugin unloaded with
                        // its files still on disk, which would bring it back at the next launch.
                        val result =
                            DynamicPluginManager.pluginRemoval?.invoke(prompt.pluginId, prompt.jarPath, mgr)
                                ?: mgr.uninstallPlugin(prompt.pluginId, force = false)
                        if (result.isSuccess) {
                            // Close this window's slots properly (hides the slot AND drops the
                            // component), then evict any component the other windows still cache -
                            // its factory is gone, so left alone it would keep rendering against a
                            // closed classloader.
                            prompt.panelIds.forEach { panelId ->
                                PanelEventBus.closePanel(panelId, windowId)
                            }
                            PanelComponentStoreRegistry.resetPanels(prompt.panelIds)
                            StatusMessageManager.showMessage("Uninstalled ${prompt.displayName}")
                        } else if (result.exceptionOrNull() is DependentRestartDeclinedException) {
                            // Cancel on the dependent-restart prompt. The plugin is still
                            // installed and still running, which is what was asked for.
                            StatusMessageManager.showMessage("${prompt.displayName} was left installed")
                        } else {
                            StatusMessageManager.showMessage(
                                "Could not uninstall ${prompt.displayName}: ${result.exceptionOrNull()?.message}",
                            )
                        }
                    }
                }
            },
        )
    }

    // Show new tab dialog
    if (state.showNewTabDialog) {
        // Where a tab this dialog creates goes: into a NEW pane when it was opened by a pane's
        // "Split Right" / "Split Down", otherwise into the active one. Declared once, above the
        // dialog, so the plugin tab types below take the same route as the built-in ones - they
        // used to resolve their own target and would have ignored a pending split.
        //
        // Splitting first and filling afterwards is not an option. checkAndCloseEmptyPanels closes
        // a panel with no tabs about 50ms later, so a split that made an empty pane would appear
        // to do nothing at all - which is exactly what the pane menu's split did until this
        // existed.
        val place: (TabInfo) -> Unit = { tab ->
            val split = splitViewState.consumePendingSplit()
            if (split == null) {
                val target =
                    splitViewState.getActiveTabsComponent()
                        ?: splitViewState.getLastInteractedTabComponent()
                        ?: state.tabsComponent
                target.addTab(tab)
            } else {
                splitViewState.splitPanel(
                    split.panelId,
                    split.direction.orientation,
                    tabToMove = tab,
                    placeBefore = split.direction.placeBefore,
                )
            }
        }

        NewTabDialog(
            onDismiss = {
                state.showNewTabDialog = false
                state.newTabDialogInitialType = null
                // A split asked for and then abandoned must not fire on the next ordinary New Tab.
                splitViewState.cancelPendingSplit()
                state.focusRequester.requestFocus()
            },
            tabRegistry = state.tabRegistry,
            // The dialog is a VIEW over the pending split rather than an owner of the choice:
            // the split map sets one before opening this, and the picker has to come up showing
            // the direction that was clicked rather than resetting it to "this pane".
            splitDirection = splitViewState.pendingSplit?.direction,
            onSplitDirectionChange = { direction ->
                if (direction == null) {
                    splitViewState.cancelPendingSplit()
                } else {
                    // The pane the tab would otherwise have landed in - see `place` above, which
                    // adds to the active component. Splitting anything else would put the new
                    // pane somewhere the user was not looking.
                    splitViewState.requestSplitWithNewTab(splitViewState.activePanelId, direction)
                }
            },
            onCreateTab = { type, path ->

                when (type) {
                    TabType.URL -> {
                        val tab =
                            FluckTabInfo(
                                id = "browser-${Random.nextLong()}",
                                typeId = TabTypeId("fluck"),
                                _title = "Loading...",
                                url = path,
                            )
                        place(tab)
                    }

                    TabType.FILE -> {
                        val fileName = path.extractFileName()
                        val fileIconInfo = FileIcons.forFile(fileName)
                        val tab =
                            EditorTabInfo(
                                id = "editor-${Random.nextLong()}",
                                typeId = TabTypeId("editor"),
                                title = fileName,
                                icon = fileIconInfo.icon,
                                tabIcon =
                                    ai.rever.boss.plugin.api.TabIcon
                                        .Vector(fileIconInfo.icon, fileIconInfo.color),
                                filePath = path,
                            )
                        place(tab)
                    }

                    TabType.TERMINAL -> {
                        // Get current project path for terminal working directory (per-window)
                        val projectPath = windowProjectState.selectedProject.value.path
                        val tab =
                            TerminalTabInfo(
                                id = "terminal-${Random.nextLong()}",
                                typeId = TerminalTabType.typeId,
                                title = "Terminal",
                                workingDirectory = DefaultWorkingDirectory.resolve(projectPath),
                            )
                        place(tab)
                    }

                    TabType.JUPYTER -> {
                        val tab = JupyterTabInfo.createUntitled(path)
                        place(tab)
                    }
                }
                // Reset the initial type after tab creation
                state.newTabDialogInitialType = null
            },
            initialTabType = state.newTabDialogInitialType,
            // Plugin tab types build their own TabInfo; open it in the
            // same target component as the built-in types.
            onCreateTabInfo = { tabInfo ->
                place(tabInfo)
                state.newTabDialogInitialType = null
            },
            projectPath =
                windowProjectState.selectedProject.value.path
                    .ifEmpty { null },
        )
    }

    // "Which workspace do you want?" - raised by the project-selection effect when the
    // default workspace setting is `ask`, the default on a fresh install.
    state.pendingWorkspacePrompt?.let { projectName ->
        val workspaces by workspaceManager.workspaces.collectAsState()
        SelectWorkspaceDialog(
            projectName = projectName,
            // The same list the top bar's workspace button and the app menu show, saved
            // workspaces included. Reading PredefinedWorkspaces here instead would offer a
            // different set than the rest of the app does.
            workspaces = workspaces,
            onDismiss = {
                state.pendingWorkspacePrompt = null
                state.focusRequester.requestFocus()
            },
            onSelect = { workspace ->
                state.pendingWorkspacePrompt = null
                coroutineScope.launch {
                    // Preserve, load, apply: the same three steps the top bar's workspace
                    // switch takes, so a workspace opened from here can be switched away
                    // from and back with its tabs intact.
                    val currentWorkspace = workspaceManager.currentWorkspace.value
                    if (currentWorkspace != null && currentWorkspace.id.isNotEmpty()) {
                        splitViewState.preserveCurrentState(currentWorkspace.id, currentWorkspace.name)
                    }
                    workspaceManager.loadWorkspace(workspace)
                    applyWorkspace(workspace, splitViewState, windowProjectState)
                }
                state.focusRequester.requestFocus()
            },
        )
    }

    // The tools launcher's dialog.
    if (state.showToolLauncherDialog) {
        // In the MAIN composition, not inside whichever chrome raised it - see BossAppState.
        state.draggablePanelComponent.ToolLauncherDialog(
            onDismiss = { state.showToolLauncherDialog = false },
        )
    }

    if (state.showGlobalSearchDialog) {
        // Offer THIS window's tools to the search, for exactly as long as its dialog is open.
        //
        // Registered here rather than per window, because the window that matters is the one whose
        // dialog is up - which is what this block already is. Registering while the WINDOW was
        // mounted had a worse failure than "last one wins": closing any window ran its onDispose
        // and cleared the slot unconditionally, including when the supplier in it belonged to a
        // window still open, whose effect would never re-run. That window's Tools results then
        // stayed empty for the rest of the session.
        //
        // A supplier rather than a snapshot, so a plugin loading while the dialog is open is
        // findable without reopening it.
        DisposableEffect(state.draggablePanelComponent, windowId) {
            val component = state.draggablePanelComponent
            // Registered under THIS window's id, and searched under it too, so two windows with a
            // dialog open at once neither overwrite each other's tools nor empty the other's slot
            // on close - and the tools offered always belong to the component that will be asked
            // to open them.
            SearchSources.registerTools(windowId) {
                // distinctBy panelId, because allSidebarTools dedupes by SidebarItem.id and this
                // record keys on the PANEL id - a different key, as slotForItem and activatePlugin
                // between them show. Two items sharing a panel id across slots would otherwise
                // give two identical Tool rows, and picking either reaches the same panel.
                component
                    .allSidebarTools()
                    .map { ToolSearchRecord(panelId = it.pluginContentId.panelId, label = it.label) }
                    .distinctBy { it.panelId }
            }
            onDispose { SearchSources.unregisterTools(windowId) }
        }

        GlobalSearchDialog(
            projectPath = selectedProject.path,
            workspaceManager = workspaceManager,
            windowId = windowId,
            onDismiss = {
                state.showGlobalSearchDialog = false
                state.focusRequester.requestFocus()
            },
            onFileSelect = { filePath ->
                state.showGlobalSearchDialog = false
                coroutineScope.launch {
                    FileEventBus.openFile(filePath, sourceWindowId = windowId, projectPath = selectedProject.path)
                }
                state.focusRequester.requestFocus()
            },
            onTabSelect = { targetWindowId, panelId, tabId ->
                state.showGlobalSearchDialog = false
                // Only handle tabs in this window
                if (targetWindowId == windowId) {
                    coroutineScope.launch {
                        delay(100)
                        splitViewState.selectTabInPanel(tabId, panelId)
                    }
                }
                state.focusRequester.requestFocus()
            },
            onBookmarkSelect = { bookmarkId, collectionId ->
                state.showGlobalSearchDialog = false
                // Find the bookmark and open it (gracefully handles missing plugin)
                val collection = BookmarkAPIAccess.getCollections().find { it.id == collectionId }
                val bookmark = collection?.bookmarks?.find { it.id == bookmarkId }
                if (bookmark != null) {
                    coroutineScope.launch {
                        // Open the bookmark as a new tab using the tab config
                        when (bookmark.tabConfig.type) {
                            "browser" -> {
                                bookmark.tabConfig.url?.let { url ->
                                    splitViewState.openUrlInActivePanel(url, bookmark.tabConfig.title)
                                }
                            }

                            "editor" -> {
                                bookmark.tabConfig.filePath?.let { filePath ->
                                    FileEventBus.openFile(filePath, sourceWindowId = windowId, projectPath = selectedProject.path)
                                }
                            }

                            // Route .ipynb through the same file bus as editor; the router opens
                            // it in the notebook tab when the plugin is present, else the editor.
                            "jupyter" -> {
                                bookmark.tabConfig.filePath?.takeIf { it.isNotBlank() }?.let { filePath ->
                                    FileEventBus.openFile(filePath, sourceWindowId = windowId, projectPath = selectedProject.path)
                                }
                            }

                            else -> {} // Other tab types can be added later
                        }
                    }
                }
                state.focusRequester.requestFocus()
            },
            onRunConfigSelect = { configId ->
                state.showGlobalSearchDialog = false
                // Find and run the configuration
                coroutineScope.launch {
                    val config =
                        RunConfigurationManager.currentSettings.value.configurations
                            .find { it.id == configId }
                            ?: RunConfigurationManager.detectedConfigurations.value
                                .find { it.id == configId }
                    if (config != null) {
                        // Execute the configuration
                        RunExecutionService.execute(config, debug = false, windowId)
                    }
                }
                state.focusRequester.requestFocus()
            },
            onCommandSelect = { actionId ->
                state.showGlobalSearchDialog = false
                // Execute the command via MenuActionsHandler
                when (actionId) {
                    KeymapActions.WINDOW_NEW -> {
                        WindowOperations.createNewWindow()
                    }

                    KeymapActions.WINDOW_CLOSE -> {
                        WindowOperations.closeWindow(windowId)
                    }

                    KeymapActions.TAB_NEW -> {
                        MenuActionsHandler.triggerNewTab(windowId)
                    }

                    KeymapActions.TAB_CLOSE -> {
                        MenuActionsHandler.triggerCloseTab(windowId)
                    }

                    KeymapActions.BROWSER_RELOAD -> {
                        MenuActionsHandler.triggerReloadBrowser(windowId)
                    }

                    KeymapActions.BROWSER_ZOOM_RESET -> {
                        MenuActionsHandler.triggerActualSize(windowId)
                    }

                    KeymapActions.BROWSER_ZOOM_IN -> {
                        MenuActionsHandler.triggerZoomIn(windowId)
                    }

                    KeymapActions.BROWSER_ZOOM_OUT -> {
                        MenuActionsHandler.triggerZoomOut(windowId)
                    }

                    KeymapActions.PANEL_NAVIGATE_LEFT -> {
                        MenuActionsHandler.triggerNavigatePanelLeft(windowId)
                    }

                    KeymapActions.PANEL_NAVIGATE_RIGHT -> {
                        MenuActionsHandler.triggerNavigatePanelRight(windowId)
                    }

                    KeymapActions.PANEL_NAVIGATE_UP -> {
                        MenuActionsHandler.triggerNavigatePanelUp(windowId)
                    }

                    KeymapActions.PANEL_NAVIGATE_DOWN -> {
                        MenuActionsHandler.triggerNavigatePanelDown(windowId)
                    }

                    KeymapActions.PANEL_SPLIT_VERTICAL -> {
                        MenuActionsHandler.triggerSplitVertically(windowId)
                    }

                    KeymapActions.PANEL_SPLIT_HORIZONTAL -> {
                        MenuActionsHandler.triggerSplitHorizontally(windowId)
                    }

                    KeymapActions.QUICK_SWITCHER_OPEN -> {
                        openTopOfMindQuickSwitcher(windowId, coroutineScope)
                    }

                    KeymapActions.WORKSPACE_SAVE -> {
                        MenuActionsHandler.triggerSaveWorkspace(windowId)
                    }

                    KeymapActions.CODEBASE_OPEN -> {
                        MenuActionsHandler.triggerOpenCodebase(windowId)
                    }

                    KeymapActions.GLOBAL_SEARCH_OPEN -> {
                        state.showGlobalSearchDialog = true
                    }

                    KeymapActions.FOCUS_MODE_TOGGLE -> {
                        MenuActionsHandler.triggerToggleFocusMode(windowId)
                    }

                    KeymapActions.SETTINGS_OPEN -> {
                        MenuActionsHandler.triggerOpenSettings(windowId)
                    }

                    KeymapActions.HELP_SHORTCUTS -> {
                        MenuActionsHandler.triggerShowShortcutHelp(windowId)
                    }

                    else -> {} // Unknown command
                }
                state.focusRequester.requestFocus()
            },
            onToolSelect = { panelId ->
                state.showGlobalSearchDialog = false
                // revealPlugin, not activatePlugin: a search asks for a thing, so it must not
                // toggle the panel shut, and it must focus the tab a tool is already hosted in
                // rather than re-open it in the sidebar. Plugin-supplied onClick still wins.
                state.draggablePanelComponent.revealPlugin(panelId)
                state.focusRequester.requestFocus()
            },
            onSettingSelect = { setting ->
                state.showGlobalSearchDialog = false
                // A signpost is not in the Settings window at all, so it does not open it: it
                // activates the panel, the same entry point a ToolResult takes. Handled before the
                // reveal because such an entry names neither a section nor a page, and reveal(null)
                // would raise Settings on whatever page it was last on and highlight a label that
                // is not there - the wrong-page highlight its own KDoc exists to prevent.
                val panelId = setting.panelId
                if (panelId != null) {
                    // Same verb as onToolSelect, for the same reasons - a signpost is a request to
                    // be taken somewhere, not a switch.
                    //
                    // Note this is NOT the path the Settings window's own search box takes for the
                    // same row: that one goes through `revealPanel`, which resolves the id against
                    // a PanelRegistry and then raises a main window over PanelEventBus. It has to,
                    // because it is reaching out of the Settings window into another one. Here the
                    // dialog is already inside the window that owns the sidebar, so the component
                    // is right there and the resolve-then-raise dance has nothing to do - and
                    // `activatePlugin`'s own matching is what `searchSettings` filters signposts
                    // on, so the row is offered exactly when this path can serve it.
                    state.draggablePanelComponent.revealPlugin(panelId)
                    // The same pair onToolSelect does, because this branch does the same work.
                    // The reveal branch below deliberately does not: it is handing focus to the
                    // Settings window, so pulling it back here would fight that.
                    state.focusRequester.requestFocus()
                } else {
                    // A plugin page navigates by page id; everything else by section. Both go
                    // through the same open(), which raises the window if it is already up and
                    // bumps its sectionRequest so asking twice for one section still navigates.
                    state.settingsWindow.reveal(
                        section = setting.pluginPageId ?: setting.section,
                        group = setting.group,
                        label = setting.label,
                        highlightable = setting.highlightable,
                    )
                }
            },
            onPageSelect = { url ->
                state.showGlobalSearchDialog = false
                coroutineScope.launch { DashboardEventBus.openUrlInNewTab(url, windowId) }
                state.focusRequester.requestFocus()
            },
        )
    }

    // Settings Window - always available, even in focus mode
    if (state.settingsWindow.visible) {
        SettingsWindow(
            onClose = {
                state.settingsWindow.close()
            },
            initialSection = state.settingsWindow.section,
            // Every Settings affordance routes through SettingsWindowState.open, which bumps these
            // instead of re-setting values that are already set. Without focusRequest the second
            // click is silent: the window stays wherever it was, usually behind the main one.
            // Without sectionRequest it raises itself but stays on the page the user last picked,
            // which reads as a different bug rather than as none.
            focusRequest = state.settingsWindow.focusRequest,
            sectionRequest = state.settingsWindow.sectionRequest,
            requestedHighlight = state.settingsWindow.highlight,
            highlightRequest = state.settingsWindow.highlightRequest,
        )
    }

    // Keyboard Shortcut Help Dialog
    // Sign-out confirmation, raised by the top bar's Sign Out and by the focus-mode quick actions.
    // Hosted here with every other state.show*Dialog flag rather than in the scaffold: the cluster's
    // buttons live in a separate, content-sized overlay window with no room for a dialog, and one
    // owner is what stops the two entry points each opening their own. Tree position does not affect
    // z-order - LogoutConfirmationDialog is a BossDialog, a real platform window on both paths.
    if (state.showLogoutDialog) {
        LogoutConfirmationDialog(
            onDismiss = { state.showLogoutDialog = false },
        )
    }

    if (state.showShortcutHelpDialog) {
        ShortcutHelpDialog(
            keymapSettings = keymapSettings,
            onDismiss = {
                state.showShortcutHelpDialog = false
                state.focusRequester.requestFocus()
            },
            onOpenSettings = {
                state.settingsWindow.open("KEYMAP")
            },
        )
    }

    // A terminal command that reached BOSS from outside the operator's own
    // `boss` invocation. `boss://` is registered with the OS, so this request
    // carries no evidence of who made it — the operator says whether it runs,
    // and sees the exact text first.
    state.pendingTerminalCommand?.let { pending ->
        ConfirmationDialog(
            title = "Run this command?",
            message =
                "BOSS was asked from outside the app to run a command in a new terminal tab. " +
                    "It has not run. Confirm only if you recognise it:\n\n${pending.command}",
            confirmText = "Run command",
            onDismiss = { state.pendingTerminalCommand = null },
            onConfirm = {
                logger.info(
                    LogCategory.TERMINAL,
                    "Operator confirmed an externally requested terminal command",
                    mapOf("windowId" to windowId),
                )
                splitViewState.openTerminalInActivePanel(pending.command, pending.workingDirectory)
                DashboardStatsManager.recordTerminalSession()
            },
        )
    }

    // Terminal link open dialog (Issue #346)
    if (state.showTerminalLinkDialog) {
        TerminalLinkOpenDialog(
            url = state.pendingTerminalLinkUrl,
            hasTabs = splitViewState.hasTabs(),
            hasSplits = splitViewState.hasSplits(),
            onDismiss = {
                state.showTerminalLinkDialog = false
                state.pendingTerminalLinkUrl = ""
                state.pendingTerminalSourceId = null
            },
            onOpenLink = { mode, rememberChoice ->
                state.showTerminalLinkDialog = false

                // Save preference if user wants to remember
                if (rememberChoice) {
                    coroutineScope.launch {
                        TerminalLinkSettingsManager.setOpenMode(mode)
                    }
                }

                // Open the link using helper function
                // Issue #506: Pass windowId for multi-window navigation filtering
                openTerminalLink(
                    state.pendingTerminalLinkUrl,
                    mode,
                    splitViewState,
                    state.pendingTerminalSourceId,
                    coroutineScope,
                    windowId = windowId,
                )
                state.pendingTerminalLinkUrl = ""
                state.pendingTerminalSourceId = null
            },
        )
    }

    // An unload is waiting on this answer: other plugins depend on the one being updated or
    // removed. Both handlers complete the prompt's `answer` before clearing the field - the
    // collector's finally block is only a backstop for a window closing mid-dialog.
    state.pendingDependentRestart?.let { prompt ->
        DependentRestartDialog(
            prompt = prompt,
            onDismiss = {
                prompt.answer.complete(false)
                state.pendingDependentRestart = null
            },
            onConfirm = {
                prompt.answer.complete(true)
                state.pendingDependentRestart = null
            },
        )
    }

    // A plugin the user just installed needs another plugin that is not there. Offer to
    // install it rather than leaving the feature to fail silently later.
    state.pendingMissingPluginDependency?.let { prompt ->
        MissingDependencyDialog(
            prompt = prompt,
            installing = state.installingMissingDependency,
            error = state.missingDependencyError,
            onDismiss = {
                // "Not now" is an answer for the session: three plugins declare the gateway
                // optional, so without this, declining once means being asked again for the
                // next one that needs it.
                PluginDependencyEventBus.decline(prompt.missing)
                state.pendingMissingPluginDependency = null
                state.missingDependencyError = null
            },
            onInstall = {
                state.installingMissingDependency = true
                state.missingDependencyError = null
                coroutineScope.launch {
                    try {
                        // runCatching rather than a catch block: a throw instead of a failed
                        // Result would otherwise leave the dialog open with no message and an
                        // "Install" button, looking like the click did nothing. Cancellation is
                        // rethrown rather than reported, and means one of two things, neither of
                        // them a fault: the window closed while the detached install carried on,
                        // or the user cancelled the download from the bottom bar - in which case
                        // the dependency really is still missing and this prompt is still true.
                        runCatching { prompt.installer.install(prompt.missing.missingPluginId) }
                            .getOrElse { error ->
                                if (error is CancellationException) throw error
                                Result.failure(error)
                            }.onSuccess {
                                state.pendingMissingPluginDependency = null
                                state.missingDependencyError = null
                                // The dialog closing is otherwise the only signal, and since the
                                // dependent is deliberately not reloaded, the user needs telling
                                // that a feature may not light up until they relaunch.
                                state.currentDefaultPlugin?.pluginToastState?.show(
                                    ToastMessage(
                                        type = ToastType.SUCCESS,
                                        title = "Plugin installed",
                                        message =
                                            "${prompt.missing.dependentDisplayName} can use it now. " +
                                                "Relaunch BOSS if a feature still reports it missing.",
                                    ),
                                )
                            }.onFailure { error ->
                                // Keep the dialog up with the reason and a Retry: dismissing on
                                // failure would look like it worked.
                                state.missingDependencyError =
                                    error.message ?: "Could not install the plugin."
                            }
                    } finally {
                        // Always, because "installing" disables every button and blocks
                        // dismissal: a throw here rather than a failed Result would leave a
                        // modal that can only be escaped by closing the window.
                        state.installingMissingDependency = false
                    }
                }
            },
        )
    }

    // A plugin the host refused - a version floor, or bytes that do not match their recorded
    // signature. Before this the refusal reached only the log
    // and the plugin simply stopped existing, which for a systemPlugin reads as a feature
    // disappearing - fluck-browser IS the browser tab.
    PluginLoadGateHost(
        manager = state.currentDefaultPlugin?.dynamicPluginManager,
        remedyResolver = PluginLoadRemedyAccess.current(),
    )

    // BOSS was asked to open something and the plugin that renders it is not
    // running. Offer to install or enable it, rather than dropping the tab with
    // only a log line - which is what "BOSS is my default browser and clicking a
    // link does nothing" actually was.
    state.pendingMissingHandlerPlugin?.let { prompt ->
        MissingHandlerPluginDialog(
            prompt = prompt,
            working = state.resolvingMissingHandlerPlugin,
            error = state.missingHandlerPluginError,
            onDismiss = {
                // An answer for the session, keyed by plugin: twelve files
                // selected in Finder with no editor plugin is one question, and
                // asking again for each would be the same question twelve times.
                MissingHandlerPluginEventBus.decline(prompt.missing.pluginId)
                state.pendingMissingHandlerPlugin = null
                state.missingHandlerPluginError = null
            },
            onResolve = {
                state.resolvingMissingHandlerPlugin = true
                state.missingHandlerPluginError = null
                coroutineScope.launch {
                    try {
                        // runCatching rather than a catch block, as the dependency
                        // dialog does: a throw instead of a failed Result would
                        // leave the dialog open with no message and a live button,
                        // looking like the click did nothing. Cancellation is
                        // rethrown - the work is detached and continues, so
                        // nothing went wrong and there is nowhere to report it.
                        runCatching { prompt.resolve() }
                            .getOrElse { error ->
                                if (error is CancellationException) throw error
                                Result.failure(error)
                            }.onSuccess {
                                // Nothing else to do here: registering the tab
                                // type fires the registry's change listeners,
                                // which completes the wait in
                                // TabTypeAvailability, which performs the open
                                // that was deferred. The file the user
                                // double-clicked appears by itself.
                                state.pendingMissingHandlerPlugin = null
                                state.missingHandlerPluginError = null
                            }.onFailure { error ->
                                // Keep the dialog up with the reason and a Retry:
                                // dismissing on failure would look like it worked.
                                state.missingHandlerPluginError =
                                    error.message ?: "Could not start the plugin."
                            }
                    } finally {
                        // Always, because `working` disables every button and
                        // blocks dismissal: a throw here rather than a failed
                        // Result would leave a modal that can only be escaped by
                        // closing the window.
                        state.resolvingMissingHandlerPlugin = false
                    }
                }
            },
        )
    }

    // Directory picker for project selection (must be outside conditional for Compose)
    val directoryPicker =
        rememberDirectoryPicker { path ->
            path?.let {
                val projectName = it.extractFileName().ifEmpty { "Unknown" }
                selectProjectInWindow(
                    windowProjectState,
                    Project(
                        name = projectName,
                        path = it,
                    ),
                )
                // Show CodeBase panel when project is selected
                state.draggablePanelComponent.setPanelVisible(
                    left.top,
                    true,
                )
                // Close the dialog after selection
                state.showProjectDialog = false
            }
        }

    // Project selection dialog (triggered from File > Open Project menu)
    // Note: Dialog handles empty recentProjects case internally by opening directory picker directly
    if (state.showProjectDialog) {
        ProjectSelectionDialog(
            onDismiss = { state.showProjectDialog = false },
            onOpenDirectoryPicker = {
                state.showProjectDialog = false
                directoryPicker.pickDirectory()
            },
        )
    }

    // New project wizard dialog (Issue #436)
    if (state.showNewProjectDialog) {
        NewProjectWizardDialog(
            onDismiss = {
                state.showNewProjectDialog = false
                state.focusRequester.requestFocus()
            },
            onProjectCreated = { project ->
                selectProjectInWindow(windowProjectState, project)
                state.showNewProjectDialog = false
                state.focusRequester.requestFocus()
            },
        )
    }

    // Clone project dialog (Issue #550)
    if (state.showCloneProjectDialog) {
        CloneProjectDialog(
            onDismiss = {
                state.showCloneProjectDialog = false
                state.focusRequester.requestFocus()
            },
            onProjectCloned = { projectPath ->
                val projectName = projectPath.substringAfterLast(java.io.File.separator)
                val project =
                    Project(
                        name = projectName,
                        path = projectPath,
                    )
                state.showCloneProjectDialog = false
                // Check if a project is already open
                if (selectedProject.path.isNotEmpty()) {
                    // Show dialog to choose between current window or new window
                    state.projectToOpen = project
                } else {
                    // No project open, directly open in current window
                    selectProjectInWindow(windowProjectState, project)
                    state.focusRequester.requestFocus()
                }
            },
        )
    }

    // Project open mode dialog (for cloned projects and other project opening flows)
    state.projectToOpen?.let { project ->
        ProjectOpenModeDialog(
            project = project,
            onDismiss = {
                state.projectToOpen = null
                state.focusRequester.requestFocus()
            },
            onOpenInCurrentWindow = { selectedProj ->
                selectProjectInWindow(windowProjectState, selectedProj)
                state.projectToOpen = null
                state.focusRequester.requestFocus()
            },
            onOpenInNewWindow = { selectedProj ->
                // Create new window with the project - each window has independent project state
                WindowOperations.createNewWindowWithProject(selectedProj)
                state.projectToOpen = null
                state.focusRequester.requestFocus()
            },
        )
    }

    // Plugin install wizard (shown on first login)
    if (state.showPluginInstallWizard && state.availablePluginsForWizard.isNotEmpty()) {
        val wizardState = rememberPluginInstallWizardState(state.availablePluginsForWizard)
        val dynamicPluginManager = state.currentDefaultPlugin?.dynamicPluginManager

        PluginWizardWindow(
            state = wizardState,
            onDismiss = {
                // User dismissed without completing - still mark as completed
                // so they're not prompted again
                coroutineScope.launch(Dispatchers.IO) {
                    UserDataStorage.setPluginWizardCompleted(true)
                }
                state.showPluginInstallWizard = false
                state.focusRequester.requestFocus()
                logger.info(LogCategory.SYSTEM, "Plugin wizard dismissed by user")
            },
            onComplete = {
                coroutineScope.launch(Dispatchers.IO) {
                    UserDataStorage.setPluginWizardCompleted(true)
                }
                state.showPluginInstallWizard = false
                state.focusRequester.requestFocus()
                logger.info(LogCategory.SYSTEM, "Plugin wizard completed")
            },
            onInstallPlugins = { plugins, onProgress ->
                when {
                    dynamicPluginManager != null -> {
                        try {
                            logger.info(
                                LogCategory.SYSTEM,
                                "Installing plugins from wizard",
                                mapOf(
                                    "pluginCount" to plugins.size.toString(),
                                ),
                            )
                            PluginWizardIntegration.installPlugins(dynamicPluginManager, plugins, onProgress)
                        } catch (e: Exception) {
                            logger.error(LogCategory.SYSTEM, "Plugin installation failed", error = e)
                            Result.failure(e)
                        }
                    }

                    else -> {
                        logger.error(LogCategory.SYSTEM, "Plugin manager not available during installation")
                        Result.failure(Exception("Toolbox not available"))
                    }
                }
            },
        )
    }

    // Generic dialog host for plugin dialogs
    GenericDialogHostContent()
}
