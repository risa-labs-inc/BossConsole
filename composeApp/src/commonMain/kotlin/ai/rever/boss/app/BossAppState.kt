package ai.rever.boss.app

import ai.rever.boss.components.dialogs.TabType
import ai.rever.boss.components.model.BossDraggableComponent
import ai.rever.boss.components.model.TabDraggableComponent
import ai.rever.boss.components.plugin.AvailablePluginUpdate
import ai.rever.boss.components.plugin.DefaultPlugin
import ai.rever.boss.components.plugin.providers.SplitViewOperationsImpl
import ai.rever.boss.components.plugin.providers.WorkspaceDataProviderImpl
import ai.rever.boss.components.registery.PanelComponentStore
import ai.rever.boss.components.registery.PanelRegistry
import ai.rever.boss.components.registery.TabRegistry
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.window_panel.components.main_window_panels.BossTabsComponent
import ai.rever.boss.components.window_panel.components.main_window_panels.TabCycleOverlayData
import ai.rever.boss.components.window_panel.rememberSplitViewState
import ai.rever.boss.components.wizard.plugin.WizardPluginInfo
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.services.FileHandlerService
import ai.rever.boss.services.TerminalHandlerService
import ai.rever.boss.services.URLHandlerService
import ai.rever.boss.services.WorkspaceHandlerService
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.window.Project
import ai.rever.boss.window.WindowGitState
import ai.rever.boss.window.WindowGitStateRegistry
import ai.rever.boss.window.WindowProjectState
import ai.rever.boss.window.WindowProjectStateRegistry
import ai.rever.boss.window.WindowRunnerState
import ai.rever.boss.window.WindowRunnerStateRegistry
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.CoroutineScope

/**
 * Per-window state for [ai.rever.boss.BossApp].
 *
 * Owns the window's component graph (registries, split view, drag components,
 * per-window project/runner/git state) plus all transient UI state (dialog
 * visibility, wizard progress, startup coordination flags). Effects and UI
 * composables receive this instead of two dozen individual parameters.
 */
internal class BossAppState(
    val windowId: String,
    val isFirstWindow: Boolean,
    val panelRegistry: PanelRegistry,
    val tabRegistry: TabRegistry,
    val panelComponentStore: PanelComponentStore,
    val draggablePanelComponent: BossDraggableComponent,
    val tabDragComponent: TabDraggableComponent,
    val tabsComponent: BossTabsComponent,
    val splitViewState: SplitViewState,
    val splitViewOperations: SplitViewOperationsImpl,
    val workspaceDataProvider: WorkspaceDataProviderImpl,
    val windowProjectState: WindowProjectState,
    val windowRunnerState: WindowRunnerState,
    val windowGitState: WindowGitState,
    val coroutineScope: CoroutineScope,
) {
    val logger = BossLogger.forComponent("BossApp")

    /** Focus target for global keyboard shortcuts; re-requested whenever a dialog closes. */
    val focusRequester = FocusRequester()

    // --- Dialog visibility --------------------------------------------------
    var showNewTabDialog by mutableStateOf(false)
    var newTabDialogInitialType by mutableStateOf<TabType?>(null)
    var showTopOfMindDialog by mutableStateOf(false)
    var showGlobalSearchDialog by mutableStateOf(false)
    var showProjectDialog by mutableStateOf(false)
    var showNewProjectDialog by mutableStateOf(false)
    var showCloneProjectDialog by mutableStateOf(false)
    var projectToOpen by mutableStateOf<Project?>(null)
    var showSettingsDialog by mutableStateOf(false)
    var settingsInitialSection by mutableStateOf<String?>(null)
    var showShortcutHelpDialog by mutableStateOf(false)

    // Terminal link open dialog (Issue #346)
    var showTerminalLinkDialog by mutableStateOf(false)
    var pendingTerminalLinkUrl by mutableStateOf("")
    var pendingTerminalSourceId by mutableStateOf<String?>(null)

    // Snapshot of the in-progress MRU tab cycle, drives the Ctrl+Tab switcher overlay
    // (null in positional mode and whenever no cycle is active).
    var tabCycleOverlay by mutableStateOf<TabCycleOverlayData?>(null)

    // Plugin update confirmation prompt (from "Check for Updates" or the header badge)
    var pluginUpdatePrompt by mutableStateOf<AvailablePluginUpdate?>(null)

    // --- Plugin install wizard (shown on first login) ------------------------
    var showPluginInstallWizard by mutableStateOf(false)
    var pluginWizardChecked by mutableStateOf(false)
    var pluginWizardRetryCount by mutableStateOf(0)
    var availablePluginsForWizard by mutableStateOf<List<WizardPluginInfo>>(emptyList())
    var currentDefaultPlugin by mutableStateOf<DefaultPlugin?>(null)

    // --- Startup coordination -------------------------------------------------
    // Track if workspace restoration has completed (for first window only)
    // New windows don't restore Last Session, so start as complete
    var workspaceRestorationComplete by mutableStateOf(!isFirstWindow)

    // Track if handlers have been marked ready (prevents race condition between workspace load and timeout)
    // Uses atomic flag to ensure handler marking happens exactly once
    private val handlersMarked =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /**
     * Mark the URL/file/workspace/terminal handlers ready exactly once.
     *
     * Called AFTER Last Session loads (or after determining no session exists) so
     * handler-created tabs aren't destroyed by the restore's clearAllPanels. The
     * terminal handler additionally waits for auth: when [isSessionResolved] is
     * false here, a separate effect marks it ready once the session resolves.
     */
    fun markHandlersReady(isSessionResolved: Boolean) {
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

/**
 * Builds the window's [BossAppState]: the component graph is remembered against
 * this window's composition, per-window states come from their global registries.
 */
@Composable
internal fun ComponentContext.rememberBossAppState(
    windowId: String,
    isFirstWindow: Boolean,
    panelRegistry: PanelRegistry,
): BossAppState {
    // Use the passed panelRegistry instance (created in BossWindow for menu access)
    val tabRegistry = remember { TabRegistry() }
    val panelComponentStore = remember { PanelComponentStore(this, panelRegistry) }
    val draggablePanelComponent = remember { BossDraggableComponent(panelRegistry) }
    val tabDragComponent = remember { TabDraggableComponent() }
    val tabsComponent = remember { BossTabsComponent(this, tabRegistry, windowId) }

    // Create split view state that manages all tab panels
    val splitViewState =
        rememberSplitViewState(
            tabRegistry = tabRegistry,
            windowId = windowId,
            initialTabsComponent = tabsComponent,
        )

    // Create split view operations wrapper for plugins
    val splitViewOperations =
        remember(splitViewState, windowId) {
            SplitViewOperationsImpl(splitViewState, windowId)
        }

    // Create workspace data provider wrapper for plugins
    val workspaceDataProvider =
        remember(workspaceManager) {
            WorkspaceDataProviderImpl(workspaceManager)
        }

    // Create per-window project/runner/git state (each window is independent).
    // Per-window git state fixes the issue where opening a new window with no
    // project would hide git UI in all windows.
    val windowProjectState = remember(windowId) { WindowProjectStateRegistry.getOrCreate(windowId) }
    val windowRunnerState = remember(windowId) { WindowRunnerStateRegistry.getOrCreate(windowId) }
    val windowGitState = remember(windowId) { WindowGitStateRegistry.getOrCreate(windowId) }

    val coroutineScope = rememberCoroutineScope()

    return remember(windowId, panelRegistry, splitViewState) {
        BossAppState(
            windowId = windowId,
            isFirstWindow = isFirstWindow,
            panelRegistry = panelRegistry,
            tabRegistry = tabRegistry,
            panelComponentStore = panelComponentStore,
            draggablePanelComponent = draggablePanelComponent,
            tabDragComponent = tabDragComponent,
            tabsComponent = tabsComponent,
            splitViewState = splitViewState,
            splitViewOperations = splitViewOperations,
            workspaceDataProvider = workspaceDataProvider,
            windowProjectState = windowProjectState,
            windowRunnerState = windowRunnerState,
            windowGitState = windowGitState,
            coroutineScope = coroutineScope,
        )
    }
}
