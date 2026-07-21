package ai.rever.boss

import BossTheme
import ai.rever.boss.components.bars.horizontal.BossBottomBar
import ai.rever.boss.components.bars.horizontal.BossTitleBar
import ai.rever.boss.components.bars.horizontal.BossTopBar
import ai.rever.boss.components.bars.horizontal.StatusMessageManager
import ai.rever.boss.components.bars.vertical.BossLeftSideBar
import ai.rever.boss.components.bars.vertical.BossRightSideBar
import ai.rever.boss.components.model.BossDraggableComponent
import ai.rever.boss.components.plugin.tab_types.registerPanelHostTab
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.Panel.Companion.right
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.components.model.TabDraggableComponent
import ai.rever.boss.components.model.TabDropResult
import ai.rever.boss.components.overlays.DraggingItemOverlay
import ai.rever.boss.components.overlays.TabDraggingOverlay
import ai.rever.boss.components.plugin.DefaultPlugin
import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabComponent
import ai.rever.boss.plugin.tab.fluck.FluckTabType
import ai.rever.boss.plugin.tab.codeeditor.EditorTabInfo
import ai.rever.boss.plugin.tab.jupyter.JupyterTabInfo
import ai.rever.boss.plugin.tab.codeeditor.CodeEditorTabType
import ai.rever.boss.components.registery.*
import ai.rever.boss.components.dialogs.NewTabDialog
import ai.rever.boss.components.dialogs.TabType
import ai.rever.boss.components.dialogs.TerminalLinkOpenDialog
import ai.rever.boss.components.dialogs.openUrlInBrowser
import ai.rever.boss.components.dialogs.ShortcutHelpDialog
import ai.rever.boss.components.dialogs.NewProjectWizardDialog
import ai.rever.boss.components.dialogs.CloneProjectDialog
import ai.rever.boss.components.wizard.plugin.PluginWizardWindow
import ai.rever.boss.components.wizard.plugin.PluginWizardIntegration
import ai.rever.boss.components.wizard.plugin.WizardPluginInfo
import ai.rever.boss.components.wizard.plugin.rememberPluginInstallWizardState
import ai.rever.boss.services.auth.UserDataStorage
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.components.dialogs.ProjectSelectionDialog
import ai.rever.boss.components.dialogs.ProjectOpenModeDialog
import ai.rever.boss.icons.FileIcons
import ai.rever.boss.utils.extractFileName
import ai.rever.boss.terminal.ExistingSplitTargetMode
import ai.rever.boss.terminal.TerminalLinkOpenMode
import ai.rever.boss.terminal.TerminalLinkSettingsManager
import ai.rever.boss.components.window_panel.BossWindow
import ai.rever.boss.components.window_panel.components.main_window_panels.BossTabsComponent
import ai.rever.boss.components.window_panel.components.main_window_panels.TabCycleOverlayData
import ai.rever.boss.components.window_panel.components.main_window_panels.TabCycleOverlayHost
import ai.rever.boss.components.window_panel.rememberSplitViewState
import ai.rever.boss.components.window_panel.SplitNode
import ai.rever.boss.components.window_panel.SplitViewStateRegistry
import ai.rever.boss.window.WindowProjectStateRegistry
import ai.rever.boss.window.LocalWindowProjectState
import ai.rever.boss.window.LocalWindowId
import ai.rever.boss.window.selectProjectInWindow
import ai.rever.boss.window.WindowRunnerStateRegistry
import ai.rever.boss.window.LocalWindowRunnerState
import ai.rever.boss.window.WindowGitStateRegistry
import ai.rever.boss.window.LocalWindowGitState
import ai.rever.boss.window.MenuActionsHandler
import ai.rever.boss.window.WindowOperations
import ai.rever.boss.window.WindowProjectState
import ai.rever.boss.components.sidebar.SidebarVisibilitySettings
import ai.rever.boss.components.sidebar.SidebarVisibilitySettingsManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.take
import ai.rever.boss.components.events.FileEventBus
import ai.rever.boss.plugin.events.FileValidationResult
import ai.rever.boss.components.events.ParsedFileReference
import ai.rever.boss.components.events.parseFileReference
import ai.rever.boss.components.events.stripFilePrefix
import ai.rever.boss.components.events.validateFilePath
import ai.rever.boss.components.events.TerminalEventBus
import ai.rever.boss.components.events.TerminalLinkEventBus
import ai.rever.boss.components.events.PanelEventBus
import ai.rever.boss.components.events.RunEventBus
import ai.rever.boss.components.events.GitTerminalEventBus
import ai.rever.boss.components.events.RunnerTerminalEventBus
import ai.rever.boss.components.events.RunnerTerminalOpenEvent
import ai.rever.boss.components.events.WorkspaceEventBus
import ai.rever.boss.components.events.URLEventBus
import ai.rever.boss.git.GitTerminalService
import ai.rever.boss.components.events.NavigationTargetBus
import ai.rever.boss.components.events.DashboardEventBus
import ai.rever.boss.run.RunConfigurationManager
import ai.rever.boss.run.RunExecutionService
import ai.rever.boss.run.RunnerSettingsManager
import ai.rever.boss.run.RunnerTerminalService
import ai.rever.boss.run.RunnerTerminalTarget
import ai.rever.boss.startup.StartupSettingsManager
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.topofmind.ActiveTab
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import com.arkivanov.decompose.ComponentContext
import kotlin.random.Random
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.applyWorkspace
import ai.rever.boss.components.workspaces.extractCurrentWorkspace
import ai.rever.boss.components.workspaces.WorkspaceSettingsManager
import ai.rever.boss.components.workspaces.WorkspaceSerializer
import ai.rever.boss.dashboard.DashboardStatsManager
import ai.rever.boss.dashboard.TemplatePanelConfig
import ai.rever.boss.dashboard.SplitTemplatesManager
import ai.rever.boss.platform.openFileWithSystemDefault
import ai.rever.boss.platform.rememberDirectoryPicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import ai.rever.boss.components.plugin.panels.left_bottom.TopOfMind.LocalSplitViewState
import ai.rever.boss.components.plugin.providers.SplitViewOperationsImpl
import ai.rever.boss.components.plugin.providers.WorkspaceDataProviderImpl
import ai.rever.boss.plugin.api.LocalSplitViewOperations
import ai.rever.boss.plugin.api.LocalBookmarkDataProvider
import ai.rever.boss.plugin.api.LocalWorkspaceDataProvider
import ai.rever.boss.plugin.api.LocalProjectPath
import ai.rever.boss.services.bookmarks.BookmarkAPIAccess
import ai.rever.boss.services.terminal.TerminalAPIAccess
import ai.rever.boss.components.plugin.panels.left_bottom.TopOfMind.LocalWorkspaceManager
import ai.rever.boss.topofmind.TabTreeState
import ai.rever.boss.components.dialogs.GlobalSearchDialog
import ai.rever.boss.components.dialogs.TopOfMindDialog
import ai.rever.boss.components.windows.SettingsWindow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import ai.rever.boss.components.plugin.panels.right_top.LLMSettingsManager
import ai.rever.boss.updater.UpdateManager
import ai.rever.boss.updater.UpdateBanner
import ai.rever.boss.updater.UpdateSettings
import ai.rever.boss.updater.UpdateState
import ai.rever.boss.updater.UpdateAvailableDialog
import ai.rever.boss.updater.rememberUpdateDialogOwnership
import androidx.compose.runtime.collectAsState
import kotlin.time.Clock
import ai.rever.boss.services.auth.CoreAuthService
import ai.rever.boss.services.URLHandlerService
import ai.rever.boss.services.TerminalHandlerService
import ai.rever.boss.services.FileHandlerService
import ai.rever.boss.services.WorkspaceHandlerService
import ai.rever.boss.utils.WindowFocusManager
import ai.rever.boss.utils.awaitRegistryCondition
import ai.rever.boss.utils.CLIVersionManager
import ai.rever.boss.utils.CLIInstaller
import ai.rever.boss.utils.Version
import ai.rever.boss.keymap.KeymapSettingsManager
import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.keymap.model.ShortcutContext
import ai.rever.boss.keymap.lifecycle.conditions.*
import ai.rever.boss.focusmode.FocusModeSettingsManager
import ai.rever.boss.components.window_panel.SplitOrientation
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.window_panel.NavigationDirection
import ai.rever.boss.window.WindowAppearanceSettingsManager
import ai.rever.boss.performance.PerformanceState
import ai.rever.boss.performance.BrowserTabInfo
import ai.rever.boss.performance.TerminalInfo
import ai.rever.boss.performance.EditorTabResourceInfo
import ai.rever.boss.components.plugin.panels.left_top.ProjectState
import ai.rever.boss.window.Project
import ai.rever.boss.components.plugin.PanelIds

// Platform-specific download tab close callback setup
expect fun setupDownloadTabCloseCallback(splitViewState: SplitViewState)

// Platform-specific function to consume pending initial tab for a window
// Returns the TabInfo if there's a pending tab for this window, null otherwise
expect fun consumePendingInitialTab(windowId: String): TabInfo?

/**
 * Platform-specific function to consume pending initial project for a window.
 * When a window is created with a project via "Open in New Window", the project
 * is stored as pending and consumed here when the window initializes.
 *
 * @param windowId The window ID to get the pending project for
 * @return The pending Project if one exists, null otherwise
 */
expect fun consumePendingInitialProject(windowId: String): Project?

/**
 * Handle the result of a tab drop operation.
 * Includes bounds checking to handle cases where tab list may have changed during drag.
 * Internal (not private) so the drop-path branching is unit-testable.
 */
internal fun handleTabDropResult(result: TabDropResult, splitViewState: SplitViewState) {
    when (result) {
        is TabDropResult.Reorder -> {
            // Reorder within the same panel
            val panel = splitViewState.getPanel(result.panelId)
            val tabCount = panel?.tabsComponent?.tabsState?.value?.tabs?.size ?: 0
            // Validate indices are within bounds before reordering
            if (result.fromIndex in 0 until tabCount && result.toIndex in 0..tabCount) {
                panel?.tabsComponent?.moveTab(result.fromIndex, result.toIndex)
            }
        }
        is TabDropResult.MoveToPanel -> {
            // Move tab from source panel to target panel
            val sourcePanel = splitViewState.getPanel(result.sourcePanelId)
            val targetPanel = splitViewState.getPanel(result.targetPanelId)

            if (sourcePanel != null && targetPanel != null) {
                fun activate(newIndex: Int) {
                    if (newIndex >= 0) {
                        targetPanel.tabsComponent.selectTab(newIndex)
                    }
                    splitViewState.setActivePanel(result.targetPanelId)
                }

                // Transfer the live component instance: the tab keeps running across the
                // move (a browser tab keeps its page and playing media) instead of being
                // destroyed in one panel and recreated-from-config in the other.
                val detached = sourcePanel.tabsComponent.detachTab(result.tabInfo.id)
                if (detached != null) {
                    activate(targetPanel.tabsComponent.adoptTab(detached))
                } else if (sourcePanel.tabsComponent.removeTabById(result.tabInfo.id)) {
                    // Component missing but the tab entry survived: recreate-from-config in
                    // the target after cleaning up the source. When the tab is gone entirely
                    // (closed mid-drag) the move is dropped — recreating from the stale
                    // drag-start snapshot would resurrect a closed tab.
                    activate(targetPanel.tabsComponent.addTab(result.tabInfo))
                }
            }
        }
        is TabDropResult.CreateSplit -> {
            // Cross-panel edge drag is a MOVE: detach the live component from the source so
            // the new split adopts it as-is (no reload, no leaked instance). Same-panel edge
            // drops keep their existing copy semantics (handled by tabToMove below).
            val crossPanel = result.sourcePanelId != result.targetPanelId
            val detached = if (crossPanel) {
                splitViewState.getPanel(result.sourcePanelId)
                    ?.tabsComponent?.detachTab(result.tabInfo.id)
            } else {
                null
            }
            // Cross-panel detach failed: recreate-from-config only if the tab entry still
            // exists in the source (component missing) — removing it first, mirroring
            // MoveToPanel. A tab closed mid-drag drops the move instead of resurrecting.
            val recreateTab = if (crossPanel && detached == null) {
                val stillInSource = splitViewState.getPanel(result.sourcePanelId)
                    ?.tabsComponent?.removeTabById(result.tabInfo.id) == true
                if (stillInSource) result.tabInfo else null
            } else if (!crossPanel) {
                result.tabInfo
            } else {
                null
            }

            // Create a new split with the tab
            if (detached != null || recreateTab != null) {
                splitViewState.splitPanel(
                    panelId = result.targetPanelId,
                    orientation = result.orientation,
                    tabToMove = recreateTab,
                    detachedTab = detached
                )
            }
        }
    }
}

/**
 * Helper function to open a runner terminal in the main panel.
 * Creates a terminal tab with the run command and adds it to the active panel.
 */
private fun openRunnerInMainPanel(
    event: RunnerTerminalOpenEvent,
    splitViewState: SplitViewState
) {
    // Create terminal tab in active panel
    val terminalTab = TerminalTabInfo(
        id = event.terminalId,
        typeId = TabTypeId("terminal"),
        title = "Run: ${event.configName}",
        initialCommand = event.command,
        workingDirectory = event.workingDirectory
    )

    // Find existing tab or create new one
    val existingPanel = splitViewState.findPanelWithTab(event.terminalId)
    if (existingPanel != null && event.isRerun) {
        // Re-run: Update existing tab with new command
        existingPanel.tabsComponent.removeTabById(event.terminalId)
    }

    // Add to active panel (or first available)
    val activeComponent = splitViewState.getActiveTabsComponent()
        ?: splitViewState.getAllPanels().firstOrNull()?.tabsComponent

    if (activeComponent != null) {
        val tabIndex = activeComponent.addTab(terminalTab)
        if (tabIndex >= 0) {
            activeComponent.selectTab(tabIndex)
        }
    } else {
    }
}

/**
 * Creates a browser tab for the given URL.
 * Extracted to reduce duplication in openTerminalLink.
 */
private fun createBrowserTab(url: String): FluckTabInfo {
    return FluckTabInfo(
        id = "browser-${kotlin.random.Random.nextLong()}",
        typeId = TabTypeId("fluck"),
        _title = "Loading...",
        url = url
    )
}

/**
 * Creates an editor tab for the given file path.
 * Used in openTerminalLink when handling file: URLs.
 *
 * Note: This function assumes the path has already been validated by the caller.
 * Use [validateFilePath] before calling this function.
 *
 * @param filePath The validated file path (may include "file:" prefix, which will be stripped)
 */
private fun createEditorTab(filePath: String): EditorTabInfo {
    val cleanPath = stripFilePrefix(filePath)
    val fileName = cleanPath.extractFileName().ifEmpty { "untitled" }
    val fileIconInfo = FileIcons.forFile(fileName)
    return EditorTabInfo(
        id = "editor-${Random.nextLong()}",
        typeId = TabTypeId("editor"),
        title = fileName,
        icon = fileIconInfo.icon,
        tabIcon = ai.rever.boss.plugin.api.TabIcon.Vector(fileIconInfo.icon, fileIconInfo.color),
        filePath = cleanPath
    )
}

/**
 * Checks if a URL is a file URL (starts with "file:").
 */
private fun isFileUrl(url: String): Boolean = url.startsWith("file:")

/**
 * Helper function to open a terminal link based on user's selected mode.
 * Handles creating browser tabs (for HTTP) or editor tabs (for file:) and splitting panels.
 *
 * Issue #346: Terminal link click prompt with remember preference
 * Issue #506: Added windowId for multi-window navigation filtering
 *
 * @param url The URL to open (HTTP or file: URL)
 * @param mode How to open the link (split or new tab)
 * @param splitViewState The split view state for panel operations
 * @param sourceTerminalId Optional terminal tab ID where the link was clicked (for finding source panel)
 * @param scope CoroutineScope for launching navigation events (structured concurrency)
 * @param windowId The window ID for multi-window filtering (Issue #506)
 */
private fun openTerminalLink(
    url: String,
    mode: TerminalLinkOpenMode,
    splitViewState: SplitViewState,
    sourceTerminalId: String? = null,
    scope: CoroutineScope,
    windowId: String? = null
) {
    // Find the source panel (where the terminal is) to correctly identify "the other" panel
    // This is important because cmd+click doesn't change focus, so activePanelId may not be the terminal panel
    val sourcePanelId = sourceTerminalId?.let { terminalId ->
        splitViewState.findPanelWithTab(terminalId)?.id
    } ?: splitViewState.activePanelId

    // Defensive check: verify source panel still exists (could be closed between link click and handling)
    // Fall back to active panel if source panel no longer exists
    val validSourcePanelId = if (splitViewState.findPanel(sourcePanelId) != null) {
        sourcePanelId
    } else {
        splitViewState.activePanelId
    }

    // Determine if this is a file URL - file links open in editor, HTTP links open in browser
    val isFile = isFileUrl(url)

    // For file URLs, perform defensive validation (primary validation happens in DesktopTerminalContent)
    // This protects against race conditions or direct calls to this function
    if (isFile) {
        // Parse file reference to extract line:column (e.g., file:/path/file.kt:123:45)
        val rawPath = stripFilePrefix(url)
        val parsed = parseFileReference(rawPath)

        when (val result = validateFilePath(parsed.path)) {
            is FileValidationResult.Invalid -> {
                return
            }
            is FileValidationResult.Valid -> {
                // Continue with validated path - use canonical path for consistency
                // TOCTOU note: There's a small window between validation and opening where
                // the file could be deleted. This is acceptable as the editor handles missing
                // files gracefully, and fully preventing this race is impractical.
                openTerminalLinkInternal(
                    url = "file:${result.canonicalPath}",
                    mode = mode,
                    splitViewState = splitViewState,
                    validSourcePanelId = validSourcePanelId,
                    isFile = true,
                    fileLine = parsed.line,
                    fileColumn = parsed.column,
                    scope = scope,
                    windowId = windowId
                )
            }
        }
    } else {
        // HTTP URLs don't need validation
        openTerminalLinkInternal(
            url = url,
            mode = mode,
            splitViewState = splitViewState,
            validSourcePanelId = validSourcePanelId,
            isFile = false,
            scope = scope,
            windowId = windowId
        )
    }
}

/**
 * Internal implementation of openTerminalLink after validation.
 * This is separated to avoid code duplication after the file validation branch.
 *
 * @param url The URL to open (HTTP or file: URL with canonical path)
 * @param mode How to open the link (split or new tab)
 * @param splitViewState The split view state for panel operations
 * @param validSourcePanelId The validated source panel ID
 * @param isFile Whether this is a file URL (vs HTTP)
 * @param fileLine 1-based line number for file navigation (0 = no navigation)
 * @param fileColumn 1-based column number for file navigation (0 = no navigation)
 * @param scope CoroutineScope for launching navigation events (structured concurrency)
 * @param windowId The window ID for multi-window filtering (Issue #506)
 */
private fun openTerminalLinkInternal(
    url: String,
    mode: TerminalLinkOpenMode,
    splitViewState: SplitViewState,
    validSourcePanelId: String,
    isFile: Boolean,
    fileLine: Int = 0,
    fileColumn: Int = 0,
    scope: CoroutineScope,
    windowId: String? = null
) {
    // Helper to create the appropriate tab type
    fun createTab() = if (isFile) createEditorTab(url) else createBrowserTab(url)

    // Helper to trigger navigation after opening a file with line:column
    // Uses structured concurrency - coroutine is cancelled if the composable is disposed
    // Issue #506: Pass windowId for multi-window filtering
    fun navigateToLineIfNeeded() {
        if (isFile && fileLine > 0 && windowId != null) {
            val cleanPath = stripFilePrefix(url)
            scope.launch(Dispatchers.Main) {
                NavigationTargetBus.navigateTo(cleanPath, fileLine, fileColumn, sourceWindowId = windowId)
            }
        }
    }

    when (mode) {
        TerminalLinkOpenMode.EXISTING_SPLIT -> {
            // Open in existing split panel (not the source panel where terminal is)
            // Use the target mode setting to determine which panel to use
            val targetMode = TerminalLinkSettingsManager.currentSettings.value.existingSplitTarget
            val targetPanel = when (targetMode) {
                ExistingSplitTargetMode.MOST_RECENT_ACTIVE ->
                    splitViewState.getOtherPanelExcluding(validSourcePanelId)
                ExistingSplitTargetMode.FIRST_AVAILABLE ->
                    splitViewState.getFirstOtherPanelExcluding(validSourcePanelId)
            }
            if (targetPanel != null) {
                val tab = createTab()
                val tabIndex = targetPanel.tabsComponent.addTab(tab)
                if (tabIndex >= 0) {
                    targetPanel.tabsComponent.selectTab(tabIndex)
                    splitViewState.setActivePanel(targetPanel.id)
                    navigateToLineIfNeeded()
                }
            } else {
                // IMPORTANT: Fallback when user saved EXISTING_SPLIT preference but later closed all splits.
                // Creates a new vertical split instead of failing silently.
                splitViewState.splitPanel(
                    panelId = validSourcePanelId,
                    orientation = SplitOrientation.VERTICAL,
                    tabToMove = createTab()
                )
                navigateToLineIfNeeded()
            }
        }
        TerminalLinkOpenMode.VERTICAL_SPLIT, TerminalLinkOpenMode.HORIZONTAL_SPLIT -> {
            val orientation = if (mode == TerminalLinkOpenMode.VERTICAL_SPLIT) {
                SplitOrientation.VERTICAL
            } else {
                SplitOrientation.HORIZONTAL
            }
            // Create split from the source panel (where terminal is), not from active panel
            splitViewState.splitPanel(
                panelId = validSourcePanelId,
                orientation = orientation,
                tabToMove = createTab()
            )
            navigateToLineIfNeeded()
        }
        TerminalLinkOpenMode.NEW_TAB, TerminalLinkOpenMode.ALWAYS_ASK -> {
            // NEW_TAB opens in current panel; ALWAYS_ASK shouldn't reach here but handle gracefully
            if (isFile) {
                // For file URLs, use openFileInActivePanel for consistent behavior
                val cleanPath = stripFilePrefix(url)
                val fileName = cleanPath.extractFileName().ifEmpty { "untitled" }
                splitViewState.openFileInActivePanel(cleanPath, fileName)
                navigateToLineIfNeeded()
            } else {
                splitViewState.openUrlInActivePanel(url, "Loading...")
            }
        }
        TerminalLinkOpenMode.SYSTEM_DEFAULT -> {
            // Open outside BOSS with the OS default handler (browser or file app).
            // Line:column navigation doesn't apply to external apps.
            if (isFile) {
                openFileWithSystemDefault(stripFilePrefix(url))
            } else {
                openUrlInBrowser(url)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ComponentContext.BossApp(
    windowId: String,
    isFirstWindow: Boolean = false,
    panelRegistry: PanelRegistry,
    onToggleMaximize: (() -> Unit)? = null
) {
    val logger = remember { BossLogger.forComponent("BossApp") }

    // Log once when BossApp first composes
    LaunchedEffect(Unit) {
        logger.info(LogCategory.SYSTEM, "BossApp initialized", mapOf(
            "windowId" to windowId,
            "isFirstWindow" to isFirstWindow.toString()
        ))
    }

    // Use the passed panelRegistry instance (created in BossWindow for menu access)
    val tabRegistry = remember { TabRegistry() }

    val panelComponentStore = remember { PanelComponentStore(this, panelRegistry) }

    val draggablePanelComponent = remember { BossDraggableComponent(panelRegistry) }
    val tabDragComponent = remember { TabDraggableComponent() }
    val tabsComponent = remember { BossTabsComponent(this, tabRegistry, windowId) }

    // Register the host-internal "panel host" tab type so a sidebar plugin can be
    // opened as a main tab (header "Open as Tab" / drag-out). Idempotent. Also let the
    // header drag-out resolve the same per-panel split zones the tab drag uses.
    LaunchedEffect(tabRegistry) {
        tabRegistry.registerPanelHostTab(panelComponentStore, draggablePanelComponent)
        draggablePanelComponent.panelDropZonesProvider = { tabDragComponent.panelDropZones }
    }
    
    // Create split view state that manages all tab panels
    val splitViewState = rememberSplitViewState(
        tabRegistry = tabRegistry,
        windowId = windowId,
        initialTabsComponent = tabsComponent
    )

    // Create split view operations wrapper for plugins
    val splitViewOperations = remember(splitViewState, windowId) {
        SplitViewOperationsImpl(splitViewState, windowId)
    }

    // Create workspace data provider wrapper for plugins
    val workspaceDataProvider = remember(workspaceManager) {
        WorkspaceDataProviderImpl(workspaceManager)
    }

    // Bookmark data provider is now provided by the bookmarks plugin via registerPluginAPI()
    // Get it from BookmarkAPIAccess which queries the plugin system
    val bookmarkDataProvider = BookmarkAPIAccess.getProvider()

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
    DisposableEffect(panelComponentStore, windowId) {
        PanelComponentStoreRegistry.register(windowId, panelComponentStore)
        onDispose {
            PanelComponentStoreRegistry.unregister(windowId)
        }
    }

    // Register callback for FluckEngine to auto-close download redirect tabs (desktop only)
    LaunchedEffect(splitViewState) {
        setupDownloadTabCloseCallback(splitViewState)
    }

    // Cancel any active drag when window loses focus (prevents stuck ghost)
    LaunchedEffect(tabDragComponent, windowId) {
        WindowFocusManager.focusedWindowFlow.collect { focusedWindowId ->
            // If this window lost focus and there's an active drag, cancel it
            if (focusedWindowId != windowId && tabDragComponent.isDragging) {
                tabDragComponent.cancelDrag()
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

    // Create per-window project state (each window has independent project)
    val windowProjectState = remember(windowId) {
        WindowProjectStateRegistry.getOrCreate(windowId)
    }

    // Create per-window runner state (each window has independent selected configuration)
    val windowRunnerState = remember(windowId) {
        WindowRunnerStateRegistry.getOrCreate(windowId)
    }

    // Create per-window git state (each window has independent git state)
    // This fixes the issue where opening a new window with no project would hide git UI in all windows
    val windowGitState = remember(windowId) {
        WindowGitStateRegistry.getOrCreate(windowId)
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

    // NOTE: Default workspace application is handled in a LaunchedEffect below
    // (see "Apply default workspace when project is selected")

    // Register resource count providers for performance monitoring
    // Use DisposableEffect to clean up on disposal and prevent memory leaks
    DisposableEffect(splitViewState, draggablePanelComponent) {
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
                ).count { panel -> draggablePanelComponent.isVisible(panel) }
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

    // Workspace manager - use global singleton to ensure Bookmarks panel sees updates
    val workspaceManager = remember { workspaceManager }
    val coroutineScope = rememberCoroutineScope()

    // Focus requester for keyboard shortcuts
    val focusRequester = remember { FocusRequester() }

    // Keymap settings (used by ShortcutHelpDialog)
    val keymapSettings by KeymapSettingsManager.currentSettings.collectAsState()

    // Focus mode settings
    val focusModeSettings by FocusModeSettingsManager.currentSettings.collectAsState()
    val isFocusModeEnabled = focusModeSettings.enabled

    // Window appearance settings
    val windowAppearanceSettings by WindowAppearanceSettingsManager.currentSettings.collectAsState()
    val showTitleBarSetting = windowAppearanceSettings.showTitleBar
    val isAutoRevealEnabled = focusModeSettings.autoRevealEnabled
    val revealOffsetDp = with(LocalDensity.current) { focusModeSettings.revealOffsetPx.toDp() }
    val revealDelayMs = focusModeSettings.revealDelayMs

    // Focus mode hover reveal state - edge strip hover detection
    // Hovering states track raw cursor position in hover strips
    var hoveringTopStrip by remember { mutableStateOf(false) }
    var hoveringLeftStrip by remember { mutableStateOf(false) }
    var hoveringRightStrip by remember { mutableStateOf(false) }
    var hoveringBottomStrip by remember { mutableStateOf(false) }

    // Reveal states are set after delay threshold is met
    var hoverRevealTop by remember { mutableStateOf(false) }
    var hoverRevealLeft by remember { mutableStateOf(false) }
    var hoverRevealRight by remember { mutableStateOf(false) }
    var hoverRevealBottom by remember { mutableStateOf(false) }

    // Apply reveal delay before triggering reveal
    LaunchedEffect(hoveringTopStrip, revealDelayMs) {
        if (hoveringTopStrip) {
            delay(revealDelayMs)
            hoverRevealTop = true
        } else {
            hoverRevealTop = false
        }
    }

    LaunchedEffect(hoveringLeftStrip, revealDelayMs) {
        if (hoveringLeftStrip) {
            delay(revealDelayMs)
            hoverRevealLeft = true
        } else {
            hoverRevealLeft = false
        }
    }

    LaunchedEffect(hoveringRightStrip, revealDelayMs) {
        if (hoveringRightStrip) {
            delay(revealDelayMs)
            hoverRevealRight = true
        } else {
            hoverRevealRight = false
        }
    }

    LaunchedEffect(hoveringBottomStrip, revealDelayMs) {
        if (hoveringBottomStrip) {
            delay(revealDelayMs)
            hoverRevealBottom = true
        } else {
            hoverRevealBottom = false
        }
    }

    // Interaction sources for sidebar hover tracking
    val topBarInteractionSource = remember { MutableInteractionSource() }
    val leftSidebarInteractionSource = remember { MutableInteractionSource() }
    val rightSidebarInteractionSource = remember { MutableInteractionSource() }
    val bottomBarInteractionSource = remember { MutableInteractionSource() }

    // Track hover state on revealed content itself
    val topBarHovered by topBarInteractionSource.collectIsHoveredAsState()
    val leftSidebarHovered by leftSidebarInteractionSource.collectIsHoveredAsState()
    val rightSidebarHovered by rightSidebarInteractionSource.collectIsHoveredAsState()
    val bottomBarHovered by bottomBarInteractionSource.collectIsHoveredAsState()

    // Debounced visibility states with grace period for smoother transitions
    var showTopBar by remember { mutableStateOf(false) }
    var showLeftSidebar by remember { mutableStateOf(false) }
    var showRightSidebar by remember { mutableStateOf(false) }
    var showBottomBar by remember { mutableStateOf(false) }

    // Add grace period before hiding to prevent flicker when moving mouse from strip to sidebar
    LaunchedEffect(hoverRevealTop, topBarHovered, isFocusModeEnabled) {
        if (!isFocusModeEnabled) {
            showTopBar = true
        } else if (hoverRevealTop || topBarHovered) {
            showTopBar = true
        } else {
            // Add 2000ms delay before hiding for menu interactions
            delay(2000)
            if (!hoverRevealTop && !topBarHovered) {
                showTopBar = false
            }
        }
    }

    LaunchedEffect(hoverRevealLeft, leftSidebarHovered, isFocusModeEnabled) {
        if (!isFocusModeEnabled) {
            showLeftSidebar = true
        } else if (hoverRevealLeft || leftSidebarHovered) {
            showLeftSidebar = true
        } else {
            // Add 2000ms delay before hiding for menu interactions
            delay(2000)
            if (!hoverRevealLeft && !leftSidebarHovered) {
                showLeftSidebar = false
            }
        }
    }

    LaunchedEffect(hoverRevealRight, rightSidebarHovered, isFocusModeEnabled) {
        if (!isFocusModeEnabled) {
            showRightSidebar = true
        } else if (hoverRevealRight || rightSidebarHovered) {
            showRightSidebar = true
        } else {
            // Add 2000ms delay before hiding for menu interactions
            delay(2000)
            if (!hoverRevealRight && !rightSidebarHovered) {
                showRightSidebar = false
            }
        }
    }

    LaunchedEffect(hoverRevealBottom, bottomBarHovered, isFocusModeEnabled) {
        if (!isFocusModeEnabled) {
            showBottomBar = true
        } else if (hoverRevealBottom || bottomBarHovered) {
            showBottomBar = true
        } else {
            // Add 2000ms delay before hiding for menu interactions
            delay(2000)
            if (!hoverRevealBottom && !bottomBarHovered) {
                showBottomBar = false
            }
        }
    }

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
                showLeftSidebar = true
            } else {
                showRightSidebar = true
            }
        }
    }

    // Request focus when auth session resolves (event-driven, no delays)
    val isSessionResolved by CoreAuthService.isSessionResolved.collectAsState()

    LaunchedEffect(isSessionResolved) {
        if (isSessionResolved) {
            focusRequester.requestFocus()
        }
    }

    // Set up workspace deletion callback to cleanup tabs
    LaunchedEffect(workspaceManager, splitViewState) {
        workspaceManager.setOnWorkspaceDeleted { deletedWorkspaceId ->
            // Clean up preserved states for the deleted workspace
            splitViewState.cleanupDeletedWorkspace(deletedWorkspaceId)
        }
    }
    
    // State for showing new tab dialog
    var showNewTabDialog by remember { mutableStateOf(false) }
    var newTabDialogInitialType by remember { mutableStateOf<TabType?>(null) }
    // Track if workspace restoration has completed (for first window only)
    // New windows don't restore Last Session, so start as complete
    var workspaceRestorationComplete by remember { mutableStateOf(!isFirstWindow) }
    // Track if handlers have been marked ready (prevents race condition between workspace load and timeout)
    // Uses atomic flag to ensure handler marking happens exactly once
    val handlersMarked = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var showTopOfMindDialog by remember { mutableStateOf(false) }
    var showGlobalSearchDialog by remember { mutableStateOf(false) }
    // Snapshot of the in-progress MRU tab cycle, drives the Ctrl+Tab switcher overlay
    // (null in positional mode and whenever no cycle is active).
    var tabCycleOverlay by remember { mutableStateOf<TabCycleOverlayData?>(null) }
    var showProjectDialog by remember { mutableStateOf(false) }
    var showNewProjectDialog by remember { mutableStateOf(false) }
    var showCloneProjectDialog by remember { mutableStateOf(false) }
    var projectToOpen by remember { mutableStateOf<Project?>(null) }

    // Plugin install wizard state (shown on first login)
    var showPluginInstallWizard by remember { mutableStateOf(false) }
    var pluginWizardChecked by remember { mutableStateOf(false) }
    var pluginWizardRetryCount by remember { mutableStateOf(0) }
    var availablePluginsForWizard by remember { mutableStateOf<List<WizardPluginInfo>>(emptyList()) }
    var currentDefaultPlugin by remember { mutableStateOf<DefaultPlugin?>(null) }

    // State for save feedback
    var saveMessage by remember { mutableStateOf<String?>(null) }

    // State for showing settings dialog
    var showSettingsDialog by remember { mutableStateOf(false) }
    var settingsInitialSection by remember { mutableStateOf<String?>(null) }

    // State for showing keyboard shortcut help dialog
    var showShortcutHelpDialog by remember { mutableStateOf(false) }

    // State for terminal link open dialog (Issue #346)
    var showTerminalLinkDialog by remember { mutableStateOf(false) }
    var pendingTerminalLinkUrl by remember { mutableStateOf("") }
    var pendingTerminalSourceId by remember { mutableStateOf<String?>(null) }

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

    DisposableEffect(panelRegistry, tabRegistry, windowProjectState, windowGitState, windowId, workspaceManager, splitViewState) {
        val plugin = DefaultPlugin(
            panelRegistry = panelRegistry,
            tabRegistry = tabRegistry,
            windowProjectState = windowProjectState,
            windowGitState = windowGitState,
            _windowId = windowId,
            workspaceManager = workspaceManager,
            splitViewState = splitViewState
        )
        currentDefaultPlugin = plugin
        draggablePanelComponent.update()

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
            }

            // Cleanup plugin coroutines
            plugin.dispose()

            // Cleanup update manager
            UpdateManager.instance.cleanup()

            // Unregister this window's state from the global registry
            SplitViewStateRegistry.unregister(windowId)

            // Unregister this window's panel component store from the registry
            PanelComponentStoreRegistry.unregister(windowId)

            // Unregister this window's project state from the registry
            WindowProjectStateRegistry.unregister(windowId)

            // Unregister this window's runner state from the registry
            WindowRunnerStateRegistry.unregister(windowId)

            // Unregister this window's git state from the registry
            WindowGitStateRegistry.unregister(windowId)
        }
    }
    
    // Load LLM settings on startup
    LaunchedEffect(Unit) {
        try {
            LLMSettingsManager.loadSettings()
        } catch (e: Exception) {
            // Ignore errors during settings load to prevent app crash
        }
    }

    // Check if plugin install wizard should be shown (only for first window)
    // Depend on pluginWizardRetryCount to prevent race conditions - retry logic is explicit
    LaunchedEffect(isFirstWindow, currentDefaultPlugin, pluginWizardRetryCount) {
        val defaultPlugin = currentDefaultPlugin

        // Early exit: Check retry limit FIRST to prevent race conditions
        if (pluginWizardRetryCount >= 3) {
            logger.error(LogCategory.SYSTEM, "Plugin wizard fetch failed after 3 attempts, giving up")
            return@LaunchedEffect
        }

        // Early exit: Already checked successfully
        if (pluginWizardChecked) {
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
                    pluginWizardChecked = true
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
                if (pluginWizardRetryCount > 0) {
                    val delayMs = pluginWizardRetryCount * 500L
                    logger.info(LogCategory.SYSTEM, "Retrying plugin fetch after ${delayMs}ms delay", mapOf(
                        "attempt" to (pluginWizardRetryCount + 1).toString()
                    ))
                    delay(delayMs)
                }

                try {
                    val plugins = withContext(Dispatchers.IO) {
                        PluginWizardIntegration.getAvailablePlugins()
                    }

                    if (plugins.isNotEmpty()) {
                        availablePluginsForWizard = plugins
                        showPluginInstallWizard = true
                        pluginWizardChecked = true  // Set AFTER successfully showing wizard

                        val reason = if (!wizardCompleted) "first_time" else "no_plugins_installed"
                        logger.info(LogCategory.SYSTEM, "Plugin wizard shown", mapOf(
                            "reason" to reason,
                            "availablePlugins" to plugins.size.toString()
                        ))
                    } else {
                        // No plugins available to install
                        logger.info(LogCategory.SYSTEM, "No plugins available to install")
                        pluginWizardChecked = true  // Set to prevent further attempts
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
                        "attempt" to (pluginWizardRetryCount + 1).toString(),
                        "maxAttempts" to "3"
                    ), error = e)
                    // Increment counter to trigger retry via LaunchedEffect dependency
                    pluginWizardRetryCount++
                }
            } else {
                logger.info(LogCategory.SYSTEM, "Plugin wizard not needed", mapOf(
                    "wizardCompleted" to wizardCompleted.toString(),
                    "pluginsInstalled" to installedPlugins.size.toString()
                ))
                pluginWizardChecked = true  // Set to prevent further checks
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
        }
    }

    // Check and auto-update CLI version on startup
    LaunchedEffect(Unit) {
        launch {
            try {
                if (CLIVersionManager.needsCLIUpdate()) {
                    val result = CLIInstaller.installCLI()
                    if (result.success) {
                    } else {
                    }
                }
            } catch (e: Exception) {
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

                        } else {
                        }

                        // Mark workspace restoration as complete (for auto-show dialog logic)
                        workspaceRestorationComplete = true

                        // CRITICAL: Mark handlers as ready AFTER Last Session loads (or after determining no session exists)
                        // This ensures URLs/terminals/files/workspaces create tabs AFTER workspace is loaded,
                        // not before (which would cause tabs to be destroyed by clearAllPanels)
                        // Uses atomic compareAndSet to prevent race with timeout fallback
                        if (handlersMarked.compareAndSet(false, true)) {
                            URLHandlerService.markAppReady()

                            FileHandlerService.markReady()

                            WorkspaceHandlerService.markReady()

                            // Wait for session to resolve before marking terminal handler ready
                            // This ensures terminal tabs only appear after authentication is fully initialized
                            if (isSessionResolved) {
                                TerminalHandlerService.markReady()
                            }
                        }
                    }
                    // Else: New window - don't load Last Session, start with empty workspace, but still mark ready
                    else {
                        // Uses atomic compareAndSet to prevent race with timeout fallback
                        if (handlersMarked.compareAndSet(false, true)) {
                            URLHandlerService.markAppReady()

                            FileHandlerService.markReady()

                            WorkspaceHandlerService.markReady()

                            if (isSessionResolved) {
                                TerminalHandlerService.markReady()
                            }
                        }
                    }
                }
            }
            .launchIn(this)
    }

    // Fallback timeout for fresh install (no workspaces on disk at all)
    // This handles the case where workspace manager never emits non-empty configs
    LaunchedEffect(isFirstWindow, isSessionResolved) {
        if (isFirstWindow && !workspaceRestorationComplete) {
            // Read timeout from settings (use current value, don't make it a key to avoid restart)
            val timeoutMs = StartupSettingsManager.currentSettings.value.workspaceLoadTimeoutMs
            delay(timeoutMs) // Wait for workspace manager to load from disk
            // currentWorkspace != null means Last Session restore is already in
            // flight (it can outlast this timeout while applyWorkspace waits for
            // plugin tab types) — let it mark handlers ready itself, otherwise
            // handler-created tabs get destroyed by the restore's clearAllPanels.
            if (!workspaceRestorationComplete && workspaceManager.currentWorkspace.value == null) {
                // Still not complete after timeout - assume fresh install
                workspaceRestorationComplete = true

                // Uses atomic compareAndSet to prevent race with workspace loading flow
                if (handlersMarked.compareAndSet(false, true)) {
                    URLHandlerService.markAppReady()
                    FileHandlerService.markReady()
                    WorkspaceHandlerService.markReady()
                    if (isSessionResolved) {
                        TerminalHandlerService.markReady()
                    }
                }
            }
        }
    }

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

                    if (success) {
                    } else {
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
            .onEach { event ->
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
                val success = GitTerminalService.openInSidebarTerminal(
                    windowId = windowId,
                    command = event.command,
                    workingDirectory = event.workingDirectory,
                    operationName = event.operationName
                )

                if (success) {
                } else {
                }
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
                        pendingTerminalLinkUrl = event.url
                        pendingTerminalSourceId = event.sourceTerminalId
                        showTerminalLinkDialog = true
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
                    windowRunnerState.selectConfiguration(configToSelect)
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

    // NOTE: Removed auto-scan on project change (IntelliJ-style behavior)
    // Run configuration detection should be done via a dedicated plugin,
    // not automatically when project changes.

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

                    } else {
                    }
                } catch (e: Exception) {
                }
            }
            .launchIn(this)
    }

    // Listen for panel open events (e.g., from CLI folder command)
    // Issue #506: Filter by window to prevent panel opening in all windows
    LaunchedEffect(draggablePanelComponent, panelRegistry, windowId) {
        PanelEventBus.panelOpenEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach { event ->
                try {
                    // Find the panel info from registry
                    // Compare only panelId and pluginId, ignore defaultOrder (UI metadata)
                    fun findPanelInfo() = panelRegistry.getAllPanels().find {
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
                            panelRegistry::addChangeListener,
                            panelRegistry::removeChangeListener
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
                        val panelItems = draggablePanelComponent.getItemsForSlotUnfiltered(panelSlot)
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
                                val isAlreadyVisible = draggablePanelComponent.isVisible(targetPanel)
                                val currentPanelId = draggablePanelComponent.getPanelContentId(targetPanel)
                                val isSamePanel = currentPanelId?.panelId == event.panelId.panelId

                                // Only invoke onClick if panel is not already visible showing this content
                                if (!isAlreadyVisible || !isSamePanel) {
                                    draggablePanelComponent.onClick.invoke(targetItem)
                                }
                            } else {
                            }
                        } else {
                        }
                    } else {
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
                showNewTabDialog = true
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
                showProjectDialog = true
            }
            .launchIn(this)

        // Handle file dialog events
        DashboardEventBus.showFileDialogEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach {
                // File dialog is typically handled by a system file chooser
                // For now, show new tab dialog with file option
                showNewTabDialog = true
            }
            .launchIn(this)

        // Handle new project events
        DashboardEventBus.showNewProjectEvents
            .filter { event -> event.sourceWindowId == windowId }
            .onEach {
                showNewProjectDialog = true
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
                draggablePanelComponent.activatePlugin(event.pluginId)
            }
            .launchIn(this)
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

        // Step 3: Observe tab count AND processing state (URLs + Terminals + Files + Workspace Restoration) reactively
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
            ProcessingState(totalTabs, isProcessingURLs, isProcessingTerminals, isProcessingFiles, workspaceRestorationComplete)
        }
            .debounce(200) // Wait for 200ms of stability
            .take(1)       // Only take first stabilized value
            .collect { state ->

                // Only show dialog if no tabs AND nothing being processed AND workspace restoration is complete
                if (state.totalTabs == 0 && !state.isProcessingURLs && !state.isProcessingTerminals && !state.isProcessingFiles && state.isRestorationComplete) {
                    showNewTabDialog = true
                } else {
                }
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
    
    // Listen for panel close events
    // Issue #506: Filter by window to prevent panel closing in all windows
    LaunchedEffect(draggablePanelComponent, windowId) {
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
                    val panelContentId = draggablePanelComponent.getPanelContentId(panel)
                    if (panelContentId == event.panelId) {
                        draggablePanelComponent.setPanelVisible(panel, false)
                        // Remove the component from store to ensure fresh instance next time
                        panelComponentStore.removeComponent(event.panelId)
                        break
                    }
                }
            }
            .launchIn(this)
    }

    // Listen for panel toggle events (open if closed, close if open)
    // Issue #506: Filter by window to prevent panel toggling in all windows
    LaunchedEffect(draggablePanelComponent, panelRegistry, windowId) {
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
                        val panelContentId = draggablePanelComponent.getPanelContentId(panel)
                        if (panelContentId?.panelId == event.panelId.panelId &&
                            draggablePanelComponent.isVisible(panel)) {
                            // Panel is visible - close it
                            draggablePanelComponent.setPanelVisible(panel, false)
                            panelComponentStore.removeComponent(event.panelId)
                            foundVisible = true
                            break
                        }
                    }

                    if (!foundVisible) {
                        // Panel is not visible - open it using the same logic as panelOpenEvents
                        val panelInfo = panelRegistry.getAllPanels().find {
                            it.id.panelId == event.panelId.panelId &&
                            it.id.pluginId == event.panelId.pluginId
                        }

                        if (panelInfo != null) {
                            val panelSlot = panelInfo.defaultSlotPosition
                            // Use the unfiltered listing — programmatic
                            // activation should work even if the user has
                            // hidden the panel's icon.
                            val panelItems = draggablePanelComponent.getItemsForSlotUnfiltered(panelSlot)
                            val targetItem = panelItems.find { it.pluginContentId.panelId == event.panelId.panelId }

                            if (targetItem != null) {
                                draggablePanelComponent.onClick.invoke(targetItem)
                            }
                        }
                    }
                } catch (e: Exception) {
                }
            }
            .launchIn(this)
    }

    // Listen for menu actions from MenuBar (File > New Tab, etc.)
    LaunchedEffect(windowId) {
        MenuActionsHandler.newTabEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    // Show new tab dialog when menu item is clicked
                    showNewTabDialog = true
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
                        tabCycleOverlay = comp?.currentCycleOverlay()
                    }
                    MenuActionsHandler.TabSwitchAction.PREVIOUS -> {
                        comp?.switchToPreviousTab()
                        tabCycleOverlay = comp?.currentCycleOverlay()
                    }
                    MenuActionsHandler.TabSwitchAction.COMMIT -> {
                        comp?.commitTabCycle()
                        tabCycleOverlay = null
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
                    showProjectDialog = true
                }
            }
            .launchIn(this)
    }

    LaunchedEffect(windowId) {
        MenuActionsHandler.openFileEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    // Open file tab selection - show new tab dialog with File tab pre-selected
                    newTabDialogInitialType = TabType.FILE
                    showNewTabDialog = true
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
                    showTopOfMindDialog = true
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
                    settingsInitialSection = section
                    showSettingsDialog = true
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

    // Track whether split is enabled (has tabs in active panel)
    val activePanelId by splitViewState.activePanelIdState
    val activeTabsComponent = splitViewState.getActiveTabsComponent()
    val hasActiveTabs = activeTabsComponent?.tabsState?.value?.tabs?.isNotEmpty() == true
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
                    draggablePanelComponent.activatePlugin(pluginId)
                }
            }
            .launchIn(this)
    }

    // Handle Browser Reload menu events
    LaunchedEffect(windowId) {
        MenuActionsHandler.reloadBrowserEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    val activeTabsComponent = splitViewState.getPanelTabsComponent(splitViewState.activePanelId)
                    val activeTab = activeTabsComponent?.tabsState?.value?.activeTab
                    if (activeTab is FluckTabInfo) {
                        val activeTabComponent = activeTabsComponent.getActiveComponent()
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
                            timestamp = kotlin.time.Clock.System.now().toEpochMilliseconds()
                        )
                        workspaceManager.updateCurrentWorkspace(updatedConfig)
                        workspaceManager.saveCurrentWorkspace()
                        TabTreeState.markWorkspaceAsSaved(currentConfig.id)
                        StatusMessageManager.showMessage("Workspace Saved")
                    } else {
                        val currentLayout = extractCurrentWorkspace(splitViewState, windowProjectState.selectedProject.value.path)
                        val newConfig = currentLayout.copy(
                            name = "Workspace ${kotlin.time.Clock.System.now().toEpochMilliseconds() / 1000}",
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
                    draggablePanelComponent.activatePlugin("codebase")
                }
            }
            .launchIn(this)
    }

    // Handle Open Global Search menu events (Issue #92)
    LaunchedEffect(windowId) {
        MenuActionsHandler.openGlobalSearchEvents
            .onEach { eventWindowId ->
                if (eventWindowId == windowId) {
                    showGlobalSearchDialog = true
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
                    showShortcutHelpDialog = true
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
                    if (availablePluginsForWizard.isEmpty()) {
                        val plugins = withContext(Dispatchers.IO) {
                            PluginWizardIntegration.getAvailablePlugins()
                        }
                        availablePluginsForWizard = plugins
                    }
                    if (availablePluginsForWizard.isNotEmpty()) {
                        showPluginInstallWizard = true
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
                    val manager = currentDefaultPlugin?.dynamicPluginManager ?: return@onEach
                    val result = manager.reloadAllPlugins()
                    val count = result.getOrElse { 0 }
                    StatusMessageManager.showMessage("Reloaded $count plugin(s)")
                }
            }
            .launchIn(this)
    }

    var pluginUpdatePrompt by remember { mutableStateOf<ai.rever.boss.components.plugin.AvailablePluginUpdate?>(null) }

    // Handle Reload Plugin (by panel ID) menu events
    LaunchedEffect(windowId) {
        MenuActionsHandler.reloadPluginEvents
            .onEach { (eventWindowId, panelId) ->
                if (eventWindowId == windowId) {
                    val manager = currentDefaultPlugin?.dynamicPluginManager ?: return@onEach
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

    // One-time plugin update check after plugins load (populates header update badges).
    LaunchedEffect(isFirstWindow, currentDefaultPlugin) {
        if (!isFirstWindow) return@LaunchedEffect
        val manager = currentDefaultPlugin?.dynamicPluginManager ?: return@LaunchedEffect
        val refs = manager.getInstalledPlugins().map {
            ai.rever.boss.components.plugin.InstalledPluginRef(it.manifest.pluginId, it.manifest.displayName, it.manifest.version)
        }
        if (refs.isNotEmpty()) {
            ai.rever.boss.components.plugin.PluginUpdateBridge.refreshAll(refs)
        }
    }

    // Handle "Check for Updates" (by panel ID) menu / header-badge events
    LaunchedEffect(windowId) {
        MenuActionsHandler.checkPluginUpdatesEvents
            .onEach { (eventWindowId, panelId) ->
                if (eventWindowId == windowId) {
                    val manager = currentDefaultPlugin?.dynamicPluginManager ?: return@onEach
                    val pluginId = manager.getRegistrationTracker().getPluginIdForPanel(panelId) ?: return@onEach
                    val info = manager.getPluginInfo(pluginId) ?: return@onEach
                    val ref = ai.rever.boss.components.plugin.InstalledPluginRef(
                        pluginId, info.manifest.displayName, info.manifest.version
                    )
                    when (val outcome = ai.rever.boss.components.plugin.PluginUpdateBridge.checkOne(ref)) {
                        is ai.rever.boss.components.plugin.UpdateCheckOutcome.Available ->
                            pluginUpdatePrompt = ai.rever.boss.components.plugin.AvailablePluginUpdate(
                                pluginId, outcome.displayName, outcome.currentVersion, outcome.newVersion
                            )
                        ai.rever.boss.components.plugin.UpdateCheckOutcome.UpToDate ->
                            StatusMessageManager.showMessage("${info.manifest.displayName} is up to date")
                        is ai.rever.boss.components.plugin.UpdateCheckOutcome.Incompatible ->
                            StatusMessageManager.showMessage("Update v${outcome.advertisedLatest} needs a newer BOSS")
                        is ai.rever.boss.components.plugin.UpdateCheckOutcome.Error ->
                            StatusMessageManager.showMessage("Couldn't check for updates: ${outcome.message}")
                    }
                }
            }
            .launchIn(this)
    }

    with(draggablePanelComponent) {
        BossTheme {
            // Create window provider implementations for plugins
            val windowIdProvider = ai.rever.boss.components.plugin.providers.WindowIdProviderImpl(windowId)
            val windowProjectStateProvider = ai.rever.boss.components.plugin.providers.WindowProjectStateProviderImpl(windowProjectState)

            CompositionLocalProvider(
                LocalWindowId provides windowId,
                ai.rever.boss.components.plugin.LocalPanelPluginIdResolver provides { panelId ->
                    currentDefaultPlugin?.dynamicPluginManager?.getRegistrationTracker()?.getPluginIdForPanel(panelId)
                },
                LocalSplitViewState provides splitViewState,
                LocalSplitViewOperations provides splitViewOperations,
                LocalWorkspaceManager provides workspaceManager,
                LocalWorkspaceDataProvider provides workspaceDataProvider,
                LocalBookmarkDataProvider provides bookmarkDataProvider,
                LocalProjectPath provides selectedProject.path,
                LocalWindowProjectState provides windowProjectState,
                LocalWindowRunnerState provides windowRunnerState,
                LocalWindowGitState provides windowGitState,
                ai.rever.boss.plugin.api.LocalWindowIdProvider provides windowIdProvider,
                ai.rever.boss.plugin.api.LocalWindowProjectStateProvider provides windowProjectStateProvider
            ) {
                // Initialize TopOfMind data provider for this window
                DisposableEffect(splitViewState, workspaceManager, windowId) {
                    ai.rever.boss.components.plugin.providers.TopOfMindDataProvider.initialize(
                        splitViewState, workspaceManager, windowId
                    )
                    onDispose {
                        ai.rever.boss.components.plugin.providers.TopOfMindDataProvider.clear()
                    }
                }

                Box(modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester)
                    .focusable()
                ) { // Use Box to allow overlaying the drag ghost
                Column(modifier = Modifier.fillMaxSize()) {
                    // Title bar - conditionally shown based on settings
                    // Default: hidden on Linux/Windows, shown on macOS
                    if (showTitleBarSetting) {
                        BossTitleBar(
                            onToggleMaximize = onToggleMaximize
                        )
                    }

                    // Update banner - always visible (even in focus mode)
                    val updateState by UpdateManager.instance.updateState.collectAsState()
                    UpdateBanner(
                        updateState = updateState,
                        onCheckForUpdates = {
                            coroutineScope.launch {
                                // Manual retry: bypass per-version dismissal
                                UpdateManager.instance.checkForUpdates(force = true)
                            }
                        },
                        onDownloadUpdate = { updateInfo ->
                            // Manager-owned scope: the download must survive this window closing
                            UpdateManager.instance.downloadUpdateInBackground(updateInfo)
                        },
                        onInstallUpdate = { downloadPath ->
                            coroutineScope.launch {
                                val success = UpdateManager.instance.installUpdate(downloadPath)
                                if (success) {
                                    // Optionally restart the application here
                                    // ApplicationRestarter.restart()
                                }
                            }
                        },
                        onDismiss = {
                            val state = updateState
                            if (state is UpdateState.UpdateAvailable) {
                                // Persist dismissal so this version doesn't re-prompt
                                coroutineScope.launch {
                                    UpdateManager.instance.dismissVersion(state.updateInfo.latestVersion)
                                }
                            } else {
                                UpdateManager.instance.resetState()
                            }
                        }
                    )

                    // Update dialog - dismissible prompt for a new app version,
                    // rendered by exactly one window (ownership is reactive)
                    val showUpdateDialog by UpdateManager.instance.showUpdateDialog.collectAsState()
                    val isUpdateDialogOwner = rememberUpdateDialogOwnership(windowId)
                    val updateStateForDialog = updateState
                    if (showUpdateDialog && isUpdateDialogOwner && updateStateForDialog is UpdateState.UpdateAvailable) {
                        UpdateAvailableDialog(
                            updateInfo = updateStateForDialog.updateInfo,
                            onUpdateNow = {
                                UpdateManager.instance.dismissDialogOnly()
                                // Manager-owned scope: the dialog lives only in the owner
                                // window — closing it must not cancel the download
                                UpdateManager.instance.downloadUpdateInBackground(updateStateForDialog.updateInfo)
                            },
                            onLater = {
                                coroutineScope.launch {
                                    UpdateManager.instance.dismissVersion(updateStateForDialog.updateInfo.latestVersion)
                                }
                            }
                        )
                    }

                    // Top bar - hidden in focus mode with smooth expand/shrink animation
                    AnimatedVisibility(
                        visible = showTopBar,
                        enter = expandVertically(
                            expandFrom = Alignment.Top,
                            animationSpec = tween(durationMillis = 250)
                        ),
                        exit = shrinkVertically(
                            shrinkTowards = Alignment.Top,
                            animationSpec = tween(durationMillis = 250)
                        )
                    ) {
                        Box(
                            modifier = Modifier.hoverable(interactionSource = topBarInteractionSource)
                        ) {
                            BossTopBar(
                                workspaceManager = workspaceManager,
                                onApplyWorkspace = { workspace ->
                                    coroutineScope.launch {
                                        // Preserve current state before switching
                                        val currentWorkspace = workspaceManager.currentWorkspace.value
                                        if (currentWorkspace != null && currentWorkspace.id.isNotEmpty()) {
                                            splitViewState.preserveCurrentState(currentWorkspace.id, currentWorkspace.name)
                                        }

                                        // First load the workspace to reset dirty state
                                        workspaceManager.loadWorkspace(workspace)
                                        // Then apply it to the UI (which will try to restore preserved state)
                                        applyWorkspace(workspace, splitViewState, windowProjectState)
                                    }
                                },
                                getCurrentWorkspace = {
                                    extractCurrentWorkspace(splitViewState, selectedProject.path)
                                },
                                onShowTopOfMind = {
                                    showTopOfMindDialog = true
                                },
                                onShowSettings = {
                                    showSettingsDialog = true
                                },
                                onShowSearch = {
                                    showGlobalSearchDialog = true
                                },
                                onNewProject = {
                                    showNewProjectDialog = true
                                },
                                onCloneProject = {
                                    showCloneProjectDialog = true
                                }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.weight(1f)
                    ) {
                        // Left sidebar - hidden in focus mode with smooth expand/shrink animation
                        AnimatedVisibility(
                            visible = showLeftSidebar,
                            enter = expandHorizontally(
                                expandFrom = Alignment.Start,
                                animationSpec = tween(durationMillis = 250)
                            ),
                            exit = shrinkHorizontally(
                                shrinkTowards = Alignment.Start,
                                animationSpec = tween(durationMillis = 250)
                            )
                        ) {
                            Box(
                                modifier = Modifier.hoverable(interactionSource = leftSidebarInteractionSource)
                            ) {
                                BossLeftSideBar()
                            }
                        }

                        // Main content area - always visible (contains tabs)
                        BossWindow(
                            modifier = Modifier.weight(1f),
                            tabsComponent = tabsComponent,
                            panelComponentStore = panelComponentStore,
                            splitViewState = splitViewState,
                            tabDragComponent = tabDragComponent,
                            onTabDropResult = { result ->
                                handleTabDropResult(result, splitViewState)
                            },
                            onShowSettings = { showSettingsDialog = true },
                            onOpenProjectDialog = { showProjectDialog = true },
                            onNewProject = { showNewProjectDialog = true }
                        )

                        // Right sidebar - hidden in focus mode with smooth expand/shrink animation
                        AnimatedVisibility(
                            visible = showRightSidebar,
                            enter = expandHorizontally(
                                expandFrom = Alignment.End,
                                animationSpec = tween(durationMillis = 250)
                            ),
                            exit = shrinkHorizontally(
                                shrinkTowards = Alignment.End,
                                animationSpec = tween(durationMillis = 250)
                            )
                        ) {
                            Box(
                                modifier = Modifier.hoverable(interactionSource = rightSidebarInteractionSource)
                            ) {
                                BossRightSideBar()
                            }
                        }
                    }

                    // Bottom bar - hidden in focus mode with smooth expand/shrink animation
                    AnimatedVisibility(
                        visible = showBottomBar,
                        enter = expandVertically(
                            expandFrom = Alignment.Bottom,
                            animationSpec = tween(durationMillis = 250)
                        ),
                        exit = shrinkVertically(
                            shrinkTowards = Alignment.Bottom,
                            animationSpec = tween(durationMillis = 250)
                        )
                    ) {
                        Box(
                            modifier = Modifier.hoverable(interactionSource = bottomBarInteractionSource)
                        ) {
                            BossBottomBar(splitViewState.getActiveTabsComponent())
                        }
                    }
                }

                // Hover reveal strips for focus mode - dynamic sizing to avoid blocking clicks
                // Top hover strip - uses revealOffsetPx when hidden, 1dp when visible (doesn't block clicks)
                if (isFocusModeEnabled && isAutoRevealEnabled) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (showTopBar) 1.dp else revealOffsetDp)
                            .align(Alignment.TopStart)
                            .zIndex(10f)
                            .background(Color.Transparent)
                            .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Enter) {
                                hoveringTopStrip = true
                            }
                            .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Exit) {
                                hoveringTopStrip = false
                            }
                    )
                }

                // Left hover strip - uses revealOffsetPx when hidden, 1dp when visible (doesn't block clicks)
                if (isFocusModeEnabled && isAutoRevealEnabled) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(if (showLeftSidebar) 1.dp else revealOffsetDp)
                            .align(Alignment.CenterStart)
                            .zIndex(10f)
                            .background(Color.Transparent)
                            .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Enter) {
                                hoveringLeftStrip = true
                            }
                            .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Exit) {
                                hoveringLeftStrip = false
                            }
                    )
                }

                // Right hover strip - uses revealOffsetPx when hidden, 1dp when visible (doesn't block clicks)
                if (isFocusModeEnabled && isAutoRevealEnabled) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(if (showRightSidebar) 1.dp else revealOffsetDp)
                            .align(Alignment.CenterEnd)
                            .zIndex(10f)
                            .background(Color.Transparent)
                            .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Enter) {
                                hoveringRightStrip = true
                            }
                            .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Exit) {
                                hoveringRightStrip = false
                            }
                    )
                }

                // Bottom hover strip - uses revealOffsetPx when hidden, 1dp when visible (doesn't block clicks)
                if (isFocusModeEnabled && isAutoRevealEnabled) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (showBottomBar) 1.dp else revealOffsetDp)
                            .align(Alignment.BottomStart)
                            .zIndex(10f)
                            .background(Color.Transparent)
                            .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Enter) {
                                hoveringBottomStrip = true
                            }
                            .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Exit) {
                                hoveringBottomStrip = false
                            }
                    )
                }

                // Draw the dragging item overlay (ghost) if an item is being dragged
                DraggingItemOverlay()

                // Draw the tab dragging overlay (ghost tab) if a tab is being dragged
                tabDragComponent.TabDraggingOverlay()

                // Plugin notification toasts — the render surface for every plugin's
                // PluginContext.notificationProvider.showToast().
                currentDefaultPlugin?.pluginToastState?.let { toastState ->
                    ai.rever.boss.plugin.sandbox.notification.PluginToastHost(
                        toastState = toastState,
                        modifier = Modifier.align(Alignment.TopEnd)
                    )
                }

                // MRU tab-switcher overlay (Ctrl+Tab in most-recently-used mode)
                TabCycleOverlayHost(
                    data = tabCycleOverlay,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            
            // Plugin update confirmation prompt (from "Check for Updates" or the header badge).
            pluginUpdatePrompt?.let { prompt ->
                ai.rever.boss.components.dialogs.ConfirmationDialog(
                    title = "Update Available",
                    message = "Update \"${prompt.displayName}\" from v${prompt.currentVersion} to v${prompt.newVersion}?",
                    confirmText = "Update",
                    onDismiss = { pluginUpdatePrompt = null },
                    onConfirm = {
                        val mgr = currentDefaultPlugin?.dynamicPluginManager
                        if (mgr != null) {
                            coroutineScope.launch {
                                StatusMessageManager.showMessage("Updating ${prompt.displayName}…")
                                val r = ai.rever.boss.components.plugin.PluginUpdateBridge.performUpdate(prompt.pluginId, mgr)
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
            if (showNewTabDialog) {
                NewTabDialog(
                    onDismiss = {
                        showNewTabDialog = false
                        newTabDialogInitialType = null
                        focusRequester.requestFocus()
                    },
                    tabRegistry = tabRegistry,
                    onCreateTab = { type, path ->
                        // Get the active panel component first, fallback to last interacted, then original
                        val targetComponent = splitViewState.getActiveTabsComponent()
                            ?: splitViewState.getLastInteractedTabComponent()
                            ?: tabsComponent

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
                        newTabDialogInitialType = null
                    },
                    initialTabType = newTabDialogInitialType,
                    // Plugin tab types build their own TabInfo; open it in the
                    // same target component as the built-in types.
                    onCreateTabInfo = { tabInfo ->
                        val targetComponent = splitViewState.getActiveTabsComponent()
                            ?: splitViewState.getLastInteractedTabComponent()
                            ?: tabsComponent
                        targetComponent.addTab(tabInfo)
                        newTabDialogInitialType = null
                    },
                    projectPath = windowProjectState.selectedProject.value.path.ifEmpty { null }
                )
            }

            // Top of mind quick switcher dialog
            if (showTopOfMindDialog) {
                TopOfMindDialog(
                    splitViewState = splitViewState,
                    workspaceManager = workspaceManager,
                    onDismiss = {
                        showTopOfMindDialog = false
                        focusRequester.requestFocus()
                    },
                    onTabSelect = { activeTab ->
                        showTopOfMindDialog = false
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

                        focusRequester.requestFocus()
                    }
                )
            }

            // Global search dialog (Issue #92)
            if (showGlobalSearchDialog) {
                GlobalSearchDialog(
                    projectPath = selectedProject.path,
                    workspaceManager = workspaceManager,
                    onDismiss = {
                        showGlobalSearchDialog = false
                        focusRequester.requestFocus()
                    },
                    onFileSelect = { filePath ->
                        showGlobalSearchDialog = false
                        coroutineScope.launch {
                            FileEventBus.openFile(filePath, sourceWindowId = windowId, projectPath = selectedProject.path)
                        }
                        focusRequester.requestFocus()
                    },
                    onTabSelect = { targetWindowId, panelId, tabId ->
                        showGlobalSearchDialog = false
                        // Only handle tabs in this window
                        if (targetWindowId == windowId) {
                            coroutineScope.launch {
                                delay(100)
                                splitViewState.selectTabInPanel(tabId, panelId)
                            }
                        }
                        focusRequester.requestFocus()
                    },
                    onBookmarkSelect = { bookmarkId, collectionId ->
                        showGlobalSearchDialog = false
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
                        focusRequester.requestFocus()
                    },
                    onRunConfigSelect = { configId ->
                        showGlobalSearchDialog = false
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
                        focusRequester.requestFocus()
                    },
                    onCommandSelect = { actionId ->
                        showGlobalSearchDialog = false
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
                            KeymapActions.QUICK_SWITCHER_OPEN -> { showTopOfMindDialog = true }
                            KeymapActions.WORKSPACE_SAVE -> MenuActionsHandler.triggerSaveWorkspace(windowId)
                            KeymapActions.CODEBASE_OPEN -> MenuActionsHandler.triggerOpenCodebase(windowId)
                            KeymapActions.GLOBAL_SEARCH_OPEN -> { showGlobalSearchDialog = true }
                            KeymapActions.FOCUS_MODE_TOGGLE -> MenuActionsHandler.triggerToggleFocusMode(windowId)
                            KeymapActions.SETTINGS_OPEN -> MenuActionsHandler.triggerOpenSettings(windowId)
                            KeymapActions.HELP_SHORTCUTS -> MenuActionsHandler.triggerShowShortcutHelp(windowId)
                            else -> {} // Unknown command
                        }
                        focusRequester.requestFocus()
                    }
                )
            }

            // Settings Window - always available, even in focus mode
            if (showSettingsDialog) {
                SettingsWindow(
                    onClose = {
                        showSettingsDialog = false
                        settingsInitialSection = null
                    },
                    initialSection = settingsInitialSection
                )
            }

            // Keyboard Shortcut Help Dialog
            if (showShortcutHelpDialog) {
                ShortcutHelpDialog(
                    keymapSettings = keymapSettings,
                    onDismiss = {
                        showShortcutHelpDialog = false
                        focusRequester.requestFocus()
                    },
                    onOpenSettings = {
                        settingsInitialSection = "KEYMAP"
                        showSettingsDialog = true
                    }
                )
            }

            // Terminal link open dialog (Issue #346)
            if (showTerminalLinkDialog) {
                TerminalLinkOpenDialog(
                    url = pendingTerminalLinkUrl,
                    hasTabs = splitViewState.hasTabs(),
                    hasSplits = splitViewState.hasSplits(),
                    onDismiss = {
                        showTerminalLinkDialog = false
                        pendingTerminalLinkUrl = ""
                        pendingTerminalSourceId = null
                    },
                    onOpenLink = { mode, rememberChoice ->
                        showTerminalLinkDialog = false

                        // Save preference if user wants to remember
                        if (rememberChoice) {
                            coroutineScope.launch {
                                TerminalLinkSettingsManager.setOpenMode(mode)
                            }
                        }

                        // Open the link using helper function
                        // Issue #506: Pass windowId for multi-window navigation filtering
                        openTerminalLink(pendingTerminalLinkUrl, mode, splitViewState, pendingTerminalSourceId, coroutineScope, windowId = windowId)
                        pendingTerminalLinkUrl = ""
                        pendingTerminalSourceId = null
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
                    draggablePanelComponent.setPanelVisible(
                        left.top,
                        true
                    )
                    // Close the dialog after selection
                    showProjectDialog = false
                }
            }

            // Project selection dialog (triggered from File > Open Project menu)
            // Note: Dialog handles empty recentProjects case internally by opening directory picker directly
            if (showProjectDialog) {
                ProjectSelectionDialog(
                    onDismiss = { showProjectDialog = false },
                    onOpenDirectoryPicker = {
                        showProjectDialog = false
                        directoryPicker.pickDirectory()
                    }
                )
            }

            // New project wizard dialog (Issue #436)
            if (showNewProjectDialog) {
                NewProjectWizardDialog(
                    onDismiss = {
                        showNewProjectDialog = false
                        focusRequester.requestFocus()
                    },
                    onProjectCreated = { project ->
                        selectProjectInWindow(windowProjectState, project)
                        showNewProjectDialog = false
                        focusRequester.requestFocus()
                    }
                )
            }

            // Clone project dialog (Issue #550)
            if (showCloneProjectDialog) {
                CloneProjectDialog(
                    onDismiss = {
                        showCloneProjectDialog = false
                        focusRequester.requestFocus()
                    },
                    onProjectCloned = { projectPath ->
                        val projectName = projectPath.substringAfterLast(java.io.File.separator)
                        val project = Project(
                            name = projectName,
                            path = projectPath
                        )
                        showCloneProjectDialog = false
                        // Check if a project is already open
                        if (selectedProject.path.isNotEmpty()) {
                            // Show dialog to choose between current window or new window
                            projectToOpen = project
                        } else {
                            // No project open, directly open in current window
                            selectProjectInWindow(windowProjectState, project)
                            focusRequester.requestFocus()
                        }
                    }
                )
            }

            // Project open mode dialog (for cloned projects and other project opening flows)
            projectToOpen?.let { project ->
                ProjectOpenModeDialog(
                    project = project,
                    onDismiss = {
                        projectToOpen = null
                        focusRequester.requestFocus()
                    },
                    onOpenInCurrentWindow = { selectedProj ->
                        selectProjectInWindow(windowProjectState, selectedProj)
                        projectToOpen = null
                        focusRequester.requestFocus()
                    },
                    onOpenInNewWindow = { selectedProj ->
                        // Create new window with the project - each window has independent project state
                        WindowOperations.createNewWindowWithProject(selectedProj)
                        projectToOpen = null
                        focusRequester.requestFocus()
                    }
                )
            }

            // Plugin install wizard (shown on first login)
            if (showPluginInstallWizard && availablePluginsForWizard.isNotEmpty()) {
                val wizardState = rememberPluginInstallWizardState(availablePluginsForWizard)
                val dynamicPluginManager = currentDefaultPlugin?.dynamicPluginManager

                PluginWizardWindow(
                    state = wizardState,
                    onDismiss = {
                        // User dismissed without completing - still mark as completed
                        // so they're not prompted again
                        coroutineScope.launch(Dispatchers.IO) {
                            UserDataStorage.setPluginWizardCompleted(true)
                        }
                        showPluginInstallWizard = false
                        focusRequester.requestFocus()
                        logger.info(LogCategory.SYSTEM, "Plugin wizard dismissed by user")
                    },
                    onComplete = {
                        coroutineScope.launch(Dispatchers.IO) {
                            UserDataStorage.setPluginWizardCompleted(true)
                        }
                        showPluginInstallWizard = false
                        focusRequester.requestFocus()
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

            // Save feedback snackbar
            saveMessage?.let { message ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Surface(
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colors.primary,
                        shape = RoundedCornerShape(8.dp),
                        elevation = 8.dp
                    ) {
                        Text(
                            text = message,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            color = MaterialTheme.colors.onPrimary,
                            style = MaterialTheme.typography.body2
                        )
                    }
                }
            }

            // Phase 4: Generic dialog host for plugin dialogs
            ai.rever.boss.components.plugin.providers.GenericDialogHostContent()
            }
        }
    }
}

/**
 * Create a tab from template panel configuration.
 * Used by DashboardEventBus handlers for split template events from Fluck Dashboard.
 */
private fun createTabFromTemplateConfig(
    panelConfig: TemplatePanelConfig,
    projectPath: String
): TabInfo? {
    val timestamp = System.currentTimeMillis()

    return when (panelConfig.type) {
        "terminal" -> {
            val command = panelConfig.content.command?.let {
                // shell command → quote {projectPath} so paths with spaces/quotes survive
                SplitTemplatesManager.processPlaceholders(it, projectPath, null, quoteProjectPath = true)
            }
            TerminalTabInfo(
                id = "terminal-$timestamp",
                typeId = TerminalTabType.typeId,
                title = command?.substringBefore(" ")?.extractFileName() ?: "Terminal",
                icon = TerminalTabType.icon,
                workingDirectory = projectPath,
                initialCommand = command
            )
        }
        "browser" -> {
            val url = panelConfig.content.url ?: ""
            FluckTabInfo(
                id = "fluck-$timestamp",
                typeId = FluckTabType.typeId,
                _title = "Loading...",
                url = url
            )
        }
        "editor" -> {
            val filePath = panelConfig.content.filePath?.let {
                SplitTemplatesManager.processPlaceholders(it, projectPath, null)
            } ?: return null
            EditorTabInfo(
                id = "editor-$timestamp",
                typeId = CodeEditorTabType.typeId,
                title = filePath.extractFileName(),
                icon = CodeEditorTabType.icon,
                filePath = filePath
            )
        }
        else -> null
    }
}



