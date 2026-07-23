package ai.rever.boss.app

import ai.rever.boss.components.bars.horizontal.BossBottomBar
import ai.rever.boss.components.bars.horizontal.BossTitleBar
import ai.rever.boss.components.bars.horizontal.BossTopBar
import ai.rever.boss.components.bars.vertical.BossLeftSideBar
import ai.rever.boss.components.bars.vertical.BossRightSideBar
import ai.rever.boss.components.overlays.DraggingItemOverlay
import ai.rever.boss.components.overlays.TabDraggingOverlay
import ai.rever.boss.components.plugin.LocalPanelPluginIdResolver
import ai.rever.boss.components.plugin.panels.left_bottom.TopOfMind.LocalSplitViewState
import ai.rever.boss.components.plugin.panels.left_bottom.TopOfMind.LocalWorkspaceManager
import ai.rever.boss.components.plugin.providers.TopOfMindDataProvider
import ai.rever.boss.components.plugin.providers.WindowIdProviderImpl
import ai.rever.boss.components.plugin.providers.WindowProjectStateProviderImpl
import ai.rever.boss.components.window_panel.BossWindow
import ai.rever.boss.components.window_panel.components.main_window_panels.TabCycleOverlayHost
import ai.rever.boss.components.workspaces.applyWorkspace
import ai.rever.boss.components.workspaces.extractCurrentWorkspace
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.handleTabDropResult
import ai.rever.boss.plugin.api.LocalBookmarkDataProvider
import ai.rever.boss.plugin.api.LocalProjectPath
import ai.rever.boss.plugin.api.LocalSplitViewOperations
import ai.rever.boss.plugin.api.LocalWindowIdProvider
import ai.rever.boss.plugin.api.LocalWindowProjectStateProvider
import ai.rever.boss.plugin.api.LocalWorkspaceDataProvider
import ai.rever.boss.plugin.sandbox.notification.PluginToastHost
import ai.rever.boss.services.bookmarks.BookmarkAPIAccess
import ai.rever.boss.updater.UpdateAvailableDialog
import ai.rever.boss.updater.UpdateBanner
import ai.rever.boss.updater.UpdateManager
import ai.rever.boss.updater.UpdateState
import ai.rever.boss.updater.rememberUpdateDialogOwnership
import ai.rever.boss.window.LocalWindowGitState
import ai.rever.boss.window.LocalWindowId
import ai.rever.boss.window.LocalWindowProjectState
import ai.rever.boss.window.LocalWindowRunnerState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch

/**
 * Provides every CompositionLocal that plugins and host UI below BossApp read:
 * window identity, split view state/operations, workspace + bookmark data
 * providers, per-window project/runner/git state.
 */
@Composable
internal fun BossAppCompositionLocals(state: BossAppState, content: @Composable () -> Unit) {
    val selectedProject by state.windowProjectState.selectedProject.collectAsState()

    // Bookmark data provider is provided by the bookmarks plugin via registerPluginAPI()
    // Get it from BookmarkAPIAccess which queries the plugin system
    val bookmarkDataProvider = BookmarkAPIAccess.getProvider()

    // Create window provider implementations for plugins
    val windowIdProvider = WindowIdProviderImpl(state.windowId)
    val windowProjectStateProvider = WindowProjectStateProviderImpl(state.windowProjectState)

    CompositionLocalProvider(
        LocalWindowId provides state.windowId,
        LocalPanelPluginIdResolver provides { panelId ->
            state.currentDefaultPlugin?.dynamicPluginManager?.getRegistrationTracker()?.getPluginIdForPanel(panelId)
        },
        LocalSplitViewState provides state.splitViewState,
        LocalSplitViewOperations provides state.splitViewOperations,
        LocalWorkspaceManager provides workspaceManager,
        LocalWorkspaceDataProvider provides state.workspaceDataProvider,
        LocalBookmarkDataProvider provides bookmarkDataProvider,
        LocalProjectPath provides selectedProject.path,
        LocalWindowProjectState provides state.windowProjectState,
        LocalWindowRunnerState provides state.windowRunnerState,
        LocalWindowGitState provides state.windowGitState,
        LocalWindowIdProvider provides windowIdProvider,
        LocalWindowProjectStateProvider provides windowProjectStateProvider
    ) {
        // Initialize TopOfMind data provider for this window
        DisposableEffect(state.splitViewState, workspaceManager, state.windowId) {
            TopOfMindDataProvider.initialize(
                state.splitViewState, workspaceManager, state.windowId
            )
            onDispose {
                TopOfMindDataProvider.clear()
            }
        }

        content()
    }
}

/**
 * The window chrome: title bar, update banner/dialog, top bar, sidebars, the
 * split-view main content, bottom bar, focus-mode hover strips, and the drag /
 * toast / tab-cycle overlays. Bars hide and hover-reveal in focus mode.
 */
@Composable
internal fun BossAppScaffold(
    state: BossAppState,
    reveal: FocusModeRevealState,
    isFocusModeEnabled: Boolean,
    isAutoRevealEnabled: Boolean,
    revealOffsetDp: Dp,
    showTitleBar: Boolean,
    onToggleMaximize: (() -> Unit)?,
) {
    val coroutineScope = state.coroutineScope
    val splitViewState = state.splitViewState
    val selectedProject by state.windowProjectState.selectedProject.collectAsState()

    with(state.draggablePanelComponent) {
        Box(modifier = Modifier
            .fillMaxSize()
            .focusRequester(state.focusRequester)
            .focusable()
        ) { // Use Box to allow overlaying the drag ghost
            Column(modifier = Modifier.fillMaxSize()) {
                // Title bar - conditionally shown based on settings
                // Default: hidden on Linux/Windows, shown on macOS
                if (showTitleBar) {
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
                            UpdateManager.instance.installUpdate(downloadPath)
                        }
                    },
                    onDismiss = {
                        val currentState = updateState
                        if (currentState is UpdateState.UpdateAvailable) {
                            // Persist dismissal so this version doesn't re-prompt
                            coroutineScope.launch {
                                UpdateManager.instance.dismissVersion(currentState.updateInfo.latestVersion)
                            }
                        } else {
                            UpdateManager.instance.resetState()
                        }
                    }
                )

                // Update dialog - dismissible prompt for a new app version,
                // rendered by exactly one window (ownership is reactive)
                val showUpdateDialog by UpdateManager.instance.showUpdateDialog.collectAsState()
                val isUpdateDialogOwner = rememberUpdateDialogOwnership(state.windowId)
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
                    visible = reveal.showTopBar,
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
                        modifier = Modifier.hoverable(interactionSource = reveal.topBarInteractionSource)
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
                                    applyWorkspace(workspace, splitViewState, state.windowProjectState)
                                }
                            },
                            getCurrentWorkspace = {
                                extractCurrentWorkspace(splitViewState, selectedProject.path)
                            },
                            onShowTopOfMind = {
                                state.showTopOfMindDialog = true
                            },
                            onShowSettings = {
                                state.showSettingsDialog = true
                            },
                            onShowSearch = {
                                state.showGlobalSearchDialog = true
                            },
                            onNewProject = {
                                state.showNewProjectDialog = true
                            },
                            onCloneProject = {
                                state.showCloneProjectDialog = true
                            }
                        )
                    }
                }

                Row(
                    modifier = Modifier.weight(1f)
                ) {
                    // Left sidebar - hidden in focus mode with smooth expand/shrink animation
                    AnimatedVisibility(
                        visible = reveal.showLeftSidebar,
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
                            modifier = Modifier.hoverable(interactionSource = reveal.leftSidebarInteractionSource)
                        ) {
                            BossLeftSideBar()
                        }
                    }

                    // Main content area - always visible (contains tabs)
                    BossWindow(
                        modifier = Modifier.weight(1f),
                        tabsComponent = state.tabsComponent,
                        panelComponentStore = state.panelComponentStore,
                        splitViewState = splitViewState,
                        tabDragComponent = state.tabDragComponent,
                        onTabDropResult = { result ->
                            handleTabDropResult(result, splitViewState)
                        },
                        onShowSettings = { state.showSettingsDialog = true },
                        onOpenProjectDialog = { state.showProjectDialog = true },
                        onNewProject = { state.showNewProjectDialog = true }
                    )

                    // Right sidebar - hidden in focus mode with smooth expand/shrink animation
                    AnimatedVisibility(
                        visible = reveal.showRightSidebar,
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
                            modifier = Modifier.hoverable(interactionSource = reveal.rightSidebarInteractionSource)
                        ) {
                            BossRightSideBar()
                        }
                    }
                }

                // Bottom bar - hidden in focus mode with smooth expand/shrink animation
                AnimatedVisibility(
                    visible = reveal.showBottomBar,
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
                        modifier = Modifier.hoverable(interactionSource = reveal.bottomBarInteractionSource)
                    ) {
                        BossBottomBar(splitViewState.getActiveTabsComponent())
                    }
                }
            }

            // Hover reveal strips for focus mode - dynamic sizing to avoid blocking clicks
            FocusModeHoverStrips(
                state = reveal,
                isFocusModeEnabled = isFocusModeEnabled,
                isAutoRevealEnabled = isAutoRevealEnabled,
                revealOffsetDp = revealOffsetDp,
            )

            // Draw the dragging item overlay (ghost) if an item is being dragged
            DraggingItemOverlay()

            // Draw the tab dragging overlay (ghost tab) if a tab is being dragged
            state.tabDragComponent.TabDraggingOverlay()

            // Plugin notification toasts — the render surface for every plugin's
            // PluginContext.notificationProvider.showToast().
            state.currentDefaultPlugin?.pluginToastState?.let { toastState ->
                PluginToastHost(
                    toastState = toastState,
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }

            // MRU tab-switcher overlay (Ctrl+Tab in most-recently-used mode)
            TabCycleOverlayHost(
                data = state.tabCycleOverlay,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}
