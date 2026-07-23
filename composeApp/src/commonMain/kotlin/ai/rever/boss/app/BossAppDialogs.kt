package ai.rever.boss.app

import ai.rever.boss.components.bars.horizontal.StatusMessageManager
import ai.rever.boss.components.dialogs.CloneProjectDialog
import ai.rever.boss.components.dialogs.ConfirmationDialog
import ai.rever.boss.components.dialogs.GlobalSearchDialog
import ai.rever.boss.components.dialogs.NewProjectWizardDialog
import ai.rever.boss.components.dialogs.NewTabDialog
import ai.rever.boss.components.dialogs.ProjectOpenModeDialog
import ai.rever.boss.components.dialogs.ProjectSelectionDialog
import ai.rever.boss.components.dialogs.ShortcutHelpDialog
import ai.rever.boss.components.dialogs.TabType
import ai.rever.boss.components.dialogs.TerminalLinkOpenDialog
import ai.rever.boss.components.dialogs.TopOfMindDialog
import ai.rever.boss.components.events.FileEventBus
import ai.rever.boss.components.plugin.PluginUpdateBridge
import ai.rever.boss.components.plugin.providers.GenericDialogHostContent
import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.components.registery.TabTypeId
import ai.rever.boss.components.windows.SettingsWindow
import ai.rever.boss.components.wizard.plugin.PluginWizardIntegration
import ai.rever.boss.components.wizard.plugin.PluginWizardWindow
import ai.rever.boss.components.wizard.plugin.rememberPluginInstallWizardState
import ai.rever.boss.components.workspaces.applyWorkspace
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.icons.FileIcons
import ai.rever.boss.keymap.KeymapSettingsManager
import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.platform.rememberDirectoryPicker
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.plugin.tab.codeeditor.EditorTabInfo
import ai.rever.boss.plugin.tab.jupyter.JupyterTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import ai.rever.boss.run.RunConfigurationManager
import ai.rever.boss.run.RunExecutionService
import ai.rever.boss.services.auth.UserDataStorage
import ai.rever.boss.services.bookmarks.BookmarkAPIAccess
import ai.rever.boss.terminal.TerminalLinkSettingsManager
import ai.rever.boss.utils.extractFileName
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.MenuActionsHandler
import ai.rever.boss.window.Project
import ai.rever.boss.window.WindowOperations
import ai.rever.boss.window.selectProjectInWindow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
                        if (r.isSuccess) {
                            StatusMessageManager.showMessage("Updated ${prompt.displayName} to v${r.getOrNull()}")
                        } else {
                            StatusMessageManager.showMessage("Update failed: ${r.exceptionOrNull()?.message}")
                        }
                    }
                }
            }
        )
    }

    // Show new tab dialog
    if (state.showNewTabDialog) {
        NewTabDialog(
            onDismiss = {
                state.showNewTabDialog = false
                state.newTabDialogInitialType = null
                state.focusRequester.requestFocus()
            },
            tabRegistry = state.tabRegistry,
            onCreateTab = { type, path ->
                // Get the active panel component first, fallback to last interacted, then original
                val targetComponent = splitViewState.getActiveTabsComponent()
                    ?: splitViewState.getLastInteractedTabComponent()
                    ?: state.tabsComponent

                when (type) {
                    TabType.URL -> {
                        val tab = FluckTabInfo(
                            id = "browser-${Random.nextLong()}",
                            typeId = TabTypeId("fluck"),
                            _title = "Loading...",
                            url = path
                        )
                        targetComponent.addTab(tab)
                    }
                    TabType.FILE -> {
                        val fileName = path.extractFileName()
                        val fileIconInfo = FileIcons.forFile(fileName)
                        val tab = EditorTabInfo(
                            id = "editor-${Random.nextLong()}",
                            typeId = TabTypeId("editor"),
                            title = fileName,
                            icon = fileIconInfo.icon,
                            tabIcon = ai.rever.boss.plugin.api.TabIcon.Vector(fileIconInfo.icon, fileIconInfo.color),
                            filePath = path
                        )
                        targetComponent.addTab(tab)
                    }
                    TabType.TERMINAL -> {
                        // Get current project path for terminal working directory (per-window)
                        val projectPath = windowProjectState.selectedProject.value.path
                        val tab = TerminalTabInfo(
                            id = "terminal-${Random.nextLong()}",
                            typeId = TerminalTabType.typeId,
                            title = "Terminal",
                            workingDirectory = projectPath.ifEmpty { null }
                        )
                        targetComponent.addTab(tab)
                    }
                    TabType.JUPYTER -> {
                        val tab = JupyterTabInfo.createUntitled(path)
                        targetComponent.addTab(tab)
                    }
                }
                // Reset the initial type after tab creation
                state.newTabDialogInitialType = null
            },
            initialTabType = state.newTabDialogInitialType,
            // Plugin tab types build their own TabInfo; open it in the
            // same target component as the built-in types.
            onCreateTabInfo = { tabInfo ->
                val targetComponent = splitViewState.getActiveTabsComponent()
                    ?: splitViewState.getLastInteractedTabComponent()
                    ?: state.tabsComponent
                targetComponent.addTab(tabInfo)
                state.newTabDialogInitialType = null
            },
            projectPath = windowProjectState.selectedProject.value.path.ifEmpty { null }
        )
    }

    // Top of mind quick switcher dialog
    if (state.showTopOfMindDialog) {
        TopOfMindDialog(
            splitViewState = splitViewState,
            workspaceManager = workspaceManager,
            onDismiss = {
                state.showTopOfMindDialog = false
                state.focusRequester.requestFocus()
            },
            onTabSelect = { activeTab ->
                state.showTopOfMindDialog = false
                coroutineScope.launch {
                    // Preserve current state before switching
                    val currentWorkspace = workspaceManager.currentWorkspace.value
                    if (currentWorkspace != null && currentWorkspace.id.isNotEmpty()) {
                        splitViewState.preserveCurrentState(currentWorkspace.id, currentWorkspace.name)
                    }

                    // Find the workspace containing this tab
                    val targetWorkspace = workspaceManager.workspaces.value.find {
                        it.id == activeTab.workspaceId
                    }

                    if (targetWorkspace != null) {
                        // Load and apply the target workspace
                        workspaceManager.loadWorkspace(targetWorkspace)
                        applyWorkspace(targetWorkspace, splitViewState, windowProjectState)

                        // Focus the specific tab after a short delay to ensure workspace is applied
                        delay(100)
                        splitViewState.selectTabInPanel(activeTab.tabInfo.id, activeTab.panelId)
                    }
                }

                state.focusRequester.requestFocus()
            }
        )
    }

    // Global search dialog (Issue #92)
    if (state.showGlobalSearchDialog) {
        GlobalSearchDialog(
            projectPath = selectedProject.path,
            workspaceManager = workspaceManager,
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
                            "browser" -> bookmark.tabConfig.url?.let { url ->
                                splitViewState.openUrlInActivePanel(url, bookmark.tabConfig.title)
                            }
                            "editor" -> bookmark.tabConfig.filePath?.let { filePath ->
                                FileEventBus.openFile(filePath, sourceWindowId = windowId, projectPath = selectedProject.path)
                            }
                            // Route .ipynb through the same file bus as editor; the router opens
                            // it in the notebook tab when the plugin is present, else the editor.
                            "jupyter" -> bookmark.tabConfig.filePath?.takeIf { it.isNotBlank() }?.let { filePath ->
                                FileEventBus.openFile(filePath, sourceWindowId = windowId, projectPath = selectedProject.path)
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
                    val config = RunConfigurationManager.currentSettings.value.configurations
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
                    KeymapActions.WINDOW_NEW -> WindowOperations.createNewWindow()
                    KeymapActions.WINDOW_CLOSE -> WindowOperations.closeWindow(windowId)
                    KeymapActions.TAB_NEW -> MenuActionsHandler.triggerNewTab(windowId)
                    KeymapActions.TAB_CLOSE -> MenuActionsHandler.triggerCloseTab(windowId)
                    KeymapActions.BROWSER_RELOAD -> MenuActionsHandler.triggerReloadBrowser(windowId)
                    KeymapActions.BROWSER_ZOOM_RESET -> MenuActionsHandler.triggerActualSize(windowId)
                    KeymapActions.BROWSER_ZOOM_IN -> MenuActionsHandler.triggerZoomIn(windowId)
                    KeymapActions.BROWSER_ZOOM_OUT -> MenuActionsHandler.triggerZoomOut(windowId)
                    KeymapActions.PANEL_NAVIGATE_LEFT -> MenuActionsHandler.triggerNavigatePanelLeft(windowId)
                    KeymapActions.PANEL_NAVIGATE_RIGHT -> MenuActionsHandler.triggerNavigatePanelRight(windowId)
                    KeymapActions.PANEL_NAVIGATE_UP -> MenuActionsHandler.triggerNavigatePanelUp(windowId)
                    KeymapActions.PANEL_NAVIGATE_DOWN -> MenuActionsHandler.triggerNavigatePanelDown(windowId)
                    KeymapActions.PANEL_SPLIT_VERTICAL -> MenuActionsHandler.triggerSplitVertically(windowId)
                    KeymapActions.PANEL_SPLIT_HORIZONTAL -> MenuActionsHandler.triggerSplitHorizontally(windowId)
                    KeymapActions.QUICK_SWITCHER_OPEN -> { state.showTopOfMindDialog = true }
                    KeymapActions.WORKSPACE_SAVE -> MenuActionsHandler.triggerSaveWorkspace(windowId)
                    KeymapActions.CODEBASE_OPEN -> MenuActionsHandler.triggerOpenCodebase(windowId)
                    KeymapActions.GLOBAL_SEARCH_OPEN -> { state.showGlobalSearchDialog = true }
                    KeymapActions.FOCUS_MODE_TOGGLE -> MenuActionsHandler.triggerToggleFocusMode(windowId)
                    KeymapActions.SETTINGS_OPEN -> MenuActionsHandler.triggerOpenSettings(windowId)
                    KeymapActions.HELP_SHORTCUTS -> MenuActionsHandler.triggerShowShortcutHelp(windowId)
                    else -> {} // Unknown command
                }
                state.focusRequester.requestFocus()
            }
        )
    }

    // Settings Window - always available, even in focus mode
    if (state.showSettingsDialog) {
        SettingsWindow(
            onClose = {
                state.showSettingsDialog = false
                state.settingsInitialSection = null
            },
            initialSection = state.settingsInitialSection
        )
    }

    // Keyboard Shortcut Help Dialog
    if (state.showShortcutHelpDialog) {
        ShortcutHelpDialog(
            keymapSettings = keymapSettings,
            onDismiss = {
                state.showShortcutHelpDialog = false
                state.focusRequester.requestFocus()
            },
            onOpenSettings = {
                state.settingsInitialSection = "KEYMAP"
                state.showSettingsDialog = true
            }
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
                openTerminalLink(state.pendingTerminalLinkUrl, mode, splitViewState, state.pendingTerminalSourceId, coroutineScope, windowId = windowId)
                state.pendingTerminalLinkUrl = ""
                state.pendingTerminalSourceId = null
            }
        )
    }

    // Directory picker for project selection (must be outside conditional for Compose)
    val directoryPicker = rememberDirectoryPicker { path ->
        path?.let {
            val projectName = it.extractFileName().ifEmpty { "Unknown" }
            selectProjectInWindow(
                windowProjectState,
                Project(
                    name = projectName,
                    path = it
                )
            )
            // Show CodeBase panel when project is selected
            state.draggablePanelComponent.setPanelVisible(
                left.top,
                true
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
            }
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
            }
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
                val project = Project(
                    name = projectName,
                    path = projectPath
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
            }
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
            }
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
                            logger.info(LogCategory.SYSTEM, "Installing plugins from wizard", mapOf(
                                "pluginCount" to plugins.size.toString()
                            ))
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
            }
        )
    }

    // Generic dialog host for plugin dialogs
    GenericDialogHostContent()
}
