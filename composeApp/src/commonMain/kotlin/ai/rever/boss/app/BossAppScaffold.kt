package ai.rever.boss.app

import ai.rever.boss.components.bars.horizontal.BossBottomBar
import ai.rever.boss.components.bars.horizontal.BossTitleBar
import ai.rever.boss.components.bars.horizontal.BossTopBar
import ai.rever.boss.components.bars.vertical.BossLeftSideBar
import ai.rever.boss.components.bars.vertical.BossRightSideBar
import ai.rever.boss.components.dialogs.LogoutConfirmationDialog
import ai.rever.boss.components.overlays.DraggingItemOverlay
import ai.rever.boss.components.overlays.OverlayCorner
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
import ai.rever.boss.focusmode.FocusModeSettings
import ai.rever.boss.handleTabDropResult
import ai.rever.boss.plugin.api.LocalBookmarkDataProvider
import ai.rever.boss.plugin.api.LocalProjectPath
import ai.rever.boss.plugin.api.LocalSplitViewOperations
import ai.rever.boss.plugin.api.LocalWindowIdProvider
import ai.rever.boss.plugin.api.LocalWindowProjectStateProvider
import ai.rever.boss.plugin.api.LocalWorkspaceDataProvider
import ai.rever.boss.plugin.sandbox.notification.PluginToastHost
import ai.rever.boss.plugin.sandbox.notification.PluginToastState
import ai.rever.boss.services.bookmarks.BookmarkAPIAccess
import ai.rever.boss.updater.UpdateAvailableDialog
import ai.rever.boss.updater.UpdateBanner
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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Hard upper bound on the toast overlay: its size before measurement, and the ceiling every later
 * measurement is taken against.
 *
 * A bound, not an estimate. The overlay measures its content against this rather than against its
 * own current size, so content that would exceed it is CLIPPED rather than merely starting small.
 * Width is `PluginToastHost`'s own `widthIn(max = 400.dp)` plus its 16.dp padding on each side;
 * height comfortably clears `PluginToastState`'s three-toast maximum. It is also the region the
 * overlay swallows clicks in until measurement lands, so it is kept no larger than it needs to be.
 */
private val TOAST_OVERLAY_INITIAL_SIZE = DpSize(432.dp, 600.dp)

/**
 * Plugin toasts, layered above the heavyweight browser surface.
 *
 * Drawn in the scaffold they land BEHIND that surface, so a toast raised while a browser tab is
 * showing is invisible. `OverlayCorner` rather than `OverlayHud` because a toast stays up for
 * seconds: a parent-sized HUD swallows every click beneath it, which is fine for a switcher held
 * for a moment and not for this.
 *
 * **Two guards, and both are load-bearing**, because the overlay window is always-on-top and a
 * non-focusable AWT window still eats mouse events (the JVM has no portable click-through). So
 * wherever it sits is a dead region, of this app and of whatever is in front of it.
 *
 *  - **Empty.** `PluginToastHost` composes its padded `Column` unconditionally and
 *    `DefaultPlugin.pluginToastState` is never null, so without this every loaded plugin would
 *    hold a window open for the whole session.
 *  - **Window focus.** Toast lifetime is NOT bounded by a timer: `ToastDuration.INDEFINITE` skips
 *    auto-dismissal entirely, it is part of the plugin IPC surface, and the host itself raises one
 *    (`BossPluginNotificationService.notifyPluginDisabled`, an ERROR toast with a "Re-enable"
 *    action - precisely the kind a user leaves sitting while they go elsewhere). Escaping the
 *    scene is only worth anything while the user is looking at this window, so an unfocused window
 *    draws toasts in place, exactly as before this overlay existed. That also stops
 *    `HeavyweightCorner`'s frame-clock loop, which would otherwise run for as long as the toast.
 *
 * Guarding on content matches what `TabCycleOverlayHost` and both drag ghosts already do.
 *
 * Extracted from the scaffold so both guards can be tested directly; see `ToastOverlayTest`.
 */
@Composable
internal fun BoxScope.ToastOverlay(toastState: PluginToastState) {
    val toasts by toastState.toasts.collectAsState()
    if (toasts.isEmpty()) return

    if (!LocalWindowInfo.current.isWindowFocused) {
        PluginToastHost(toastState = toastState, modifier = Modifier.align(Alignment.TopEnd))
        return
    }
    OverlayCorner(
        alignment = Alignment.TopEnd,
        initialSize = TOAST_OVERLAY_INITIAL_SIZE,
    ) {
        PluginToastHost(toastState = toastState)
    }
}

/**
 * Provides every CompositionLocal that plugins and host UI below BossApp read:
 * window identity, split view state/operations, workspace + bookmark data
 * providers, per-window project/runner/git state.
 */
@Composable
internal fun BossAppCompositionLocals(
    state: BossAppState,
    content: @Composable () -> Unit,
) {
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
            state.currentDefaultPlugin
                ?.dynamicPluginManager
                ?.getRegistrationTracker()
                ?.getPluginIdForPanel(panelId)
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
        LocalWindowProjectStateProvider provides windowProjectStateProvider,
    ) {
        // Initialize TopOfMind data provider for this window
        DisposableEffect(state.splitViewState, workspaceManager, state.windowId) {
            TopOfMindDataProvider.initialize(
                state.splitViewState,
                workspaceManager,
                state.windowId,
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
    focusModeSettings: FocusModeSettings,
    revealOffsetDp: Dp,
    showTitleBar: Boolean,
    onToggleMaximize: (() -> Unit)?,
) {
    val coroutineScope = state.coroutineScope
    val splitViewState = state.splitViewState
    val selectedProject by state.windowProjectState.selectedProject.collectAsState()

    // Sign-out is reachable from the focus-mode quick actions, whose buttons live in a separate
    // overlay window. The confirmation is owned here so the dialog is drawn in the MAIN
    // composition: a content-sized overlay window has nowhere to put one.
    var showLogoutDialog by remember { mutableStateOf(false) }
    // The content area's distance from the window's end and bottom edges, i.e. the right sidebar's
    // width plus the bottom bar's height, whatever they currently are. Measured rather than derived
    // from the reveal flags because both animate, and the quick actions have to follow them.
    var contentInset by remember { mutableStateOf(DpSize.Zero) }
    val density = LocalDensity.current.density

    with(state.draggablePanelComponent) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .focusRequester(state.focusRequester)
                    .focusable(),
        ) {
            // Use Box to allow overlaying the drag ghost
            Column(modifier = Modifier.fillMaxSize()) {
                // Title bar - conditionally shown based on settings
                // Default: hidden on Linux/Windows, shown on macOS
                if (showTitleBar) {
                    BossTitleBar(
                        onToggleMaximize = onToggleMaximize,
                    )
                }

                // Update banner - always visible (even in focus mode)
                val updateHandle = state.updateHandle
                val updateState by updateHandle.updateState.collectAsState()
                // Every action runs on the manager's scope, never this window's
                // rememberCoroutineScope(): that scope dies with the composition, so
                // closing the window mid-install used to cancel the install (leaving
                // UpdateState on Installing) and could drop a persisted dismissal.
                UpdateBanner(
                    updateState = updateState,
                    onCheckForUpdates = {
                        // Manual retry: bypass per-version dismissal
                        updateHandle.checkForUpdatesInBackground(force = true)
                    },
                    onDownloadUpdate = { updateInfo ->
                        updateHandle.downloadUpdateInBackground(updateInfo)
                    },
                    onInstallUpdate = { downloadPath ->
                        updateHandle.installUpdateInBackground(downloadPath)
                    },
                    onDismiss = {
                        val currentState = updateState
                        if (currentState is UpdateState.UpdateAvailable) {
                            // Persist dismissal so this version doesn't re-prompt
                            updateHandle.dismissVersionInBackground(currentState.updateInfo.latestVersion)
                        } else {
                            updateHandle.resetState()
                        }
                    },
                )

                // Update dialog - dismissible prompt for a new app version,
                // rendered by exactly one window (ownership is reactive)
                val showUpdateDialog by updateHandle.showUpdateDialog.collectAsState()
                val isUpdateDialogOwner = rememberUpdateDialogOwnership(state.windowId)
                val updateStateForDialog = updateState
                if (showUpdateDialog && isUpdateDialogOwner && updateStateForDialog is UpdateState.UpdateAvailable) {
                    UpdateAvailableDialog(
                        updateInfo = updateStateForDialog.updateInfo,
                        onUpdateNow = {
                            updateHandle.dismissDialogOnly()
                            // Manager-owned scope: the dialog lives only in the owner
                            // window — closing it must not cancel the download
                            updateHandle.downloadUpdateInBackground(updateStateForDialog.updateInfo)
                        },
                        onLater = {
                            updateHandle.dismissVersionInBackground(updateStateForDialog.updateInfo.latestVersion)
                        },
                    )
                }

                // Top bar - hidden in focus mode with smooth expand/shrink animation
                AnimatedVisibility(
                    visible = reveal.showTopBar,
                    enter =
                        expandVertically(
                            expandFrom = Alignment.Top,
                            animationSpec = tween(durationMillis = 250),
                        ),
                    exit =
                        shrinkVertically(
                            shrinkTowards = Alignment.Top,
                            animationSpec = tween(durationMillis = 250),
                        ),
                ) {
                    Box(
                        modifier = Modifier.hoverable(interactionSource = reveal.topBarInteractionSource),
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
                            },
                        )
                    }
                }

                Row(
                    modifier = Modifier.weight(1f),
                ) {
                    // Left sidebar - hidden in focus mode with smooth expand/shrink animation
                    AnimatedVisibility(
                        visible = reveal.showLeftSidebar,
                        enter =
                            expandHorizontally(
                                expandFrom = Alignment.Start,
                                animationSpec = tween(durationMillis = 250),
                            ),
                        exit =
                            shrinkHorizontally(
                                shrinkTowards = Alignment.Start,
                                animationSpec = tween(durationMillis = 250),
                            ),
                    ) {
                        Box(
                            modifier = Modifier.hoverable(interactionSource = reveal.leftSidebarInteractionSource),
                        ) {
                            BossLeftSideBar()
                        }
                    }

                    // Main content area - always visible (contains tabs)
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .reportContentInset(density) { next ->
                                    if (next != contentInset) contentInset = next
                                },
                    ) {
                        BossWindow(
                            modifier = Modifier.fillMaxSize(),
                            tabsComponent = state.tabsComponent,
                            panelComponentStore = state.panelComponentStore,
                            splitViewState = splitViewState,
                            tabDragComponent = state.tabDragComponent,
                            onTabDropResult = { result ->
                                handleTabDropResult(result, splitViewState)
                            },
                            onShowSettings = { state.showSettingsDialog = true },
                            onOpenProjectDialog = { state.showProjectDialog = true },
                            onNewProject = { state.showNewProjectDialog = true },
                        )

                        // Settings / Search / Sign Out, which the top bar otherwise owns outright.
                        // Composed inside the content area so the lightweight path aligns where it
                        // draws; contentInset is what makes the heavyweight path agree.
                        FocusModeQuickActions(
                            visible = !reveal.showTopBar,
                            inset = contentInset,
                            onShowSettings = { state.showSettingsDialog = true },
                            onShowSearch = { state.showGlobalSearchDialog = true },
                            onSignOut = { showLogoutDialog = true },
                        )
                    }

                    // Right sidebar - hidden in focus mode with smooth expand/shrink animation
                    AnimatedVisibility(
                        visible = reveal.showRightSidebar,
                        enter =
                            expandHorizontally(
                                expandFrom = Alignment.End,
                                animationSpec = tween(durationMillis = 250),
                            ),
                        exit =
                            shrinkHorizontally(
                                shrinkTowards = Alignment.End,
                                animationSpec = tween(durationMillis = 250),
                            ),
                    ) {
                        Box(
                            modifier = Modifier.hoverable(interactionSource = reveal.rightSidebarInteractionSource),
                        ) {
                            BossRightSideBar()
                        }
                    }
                }

                // Bottom bar - hidden in focus mode with smooth expand/shrink animation
                AnimatedVisibility(
                    visible = reveal.showBottomBar,
                    enter =
                        expandVertically(
                            expandFrom = Alignment.Bottom,
                            animationSpec = tween(durationMillis = 250),
                        ),
                    exit =
                        shrinkVertically(
                            shrinkTowards = Alignment.Bottom,
                            animationSpec = tween(durationMillis = 250),
                        ),
                ) {
                    Box(
                        modifier = Modifier.hoverable(interactionSource = reveal.bottomBarInteractionSource),
                    ) {
                        BossBottomBar(splitViewState.getActiveTabsComponent())
                    }
                }
            }

            // Hover reveal strips for focus mode - dynamic sizing to avoid blocking clicks
            FocusModeHoverStrips(
                state = reveal,
                settings = focusModeSettings,
                revealOffsetDp = revealOffsetDp,
            )

            // Raised by the focus-mode quick actions, drawn here rather than in their overlay
            // window - which is sized to its content and so has no room for a dialog.
            if (showLogoutDialog) {
                LogoutConfirmationDialog(
                    onDismiss = { showLogoutDialog = false },
                )
            }

            // Draw the dragging item overlay (ghost) if an item is being dragged
            DraggingItemOverlay()

            // Draw the tab dragging overlay (ghost tab) if a tab is being dragged
            state.tabDragComponent.TabDraggingOverlay()

            // Plugin notification toasts — the render surface for every plugin's
            // PluginContext.notificationProvider.showToast().
            state.currentDefaultPlugin?.pluginToastState?.let { toastState ->
                ToastOverlay(toastState = toastState)
            }

            // MRU tab-switcher overlay (Ctrl+Tab in most-recently-used mode)
            TabCycleOverlayHost(
                data = state.tabCycleOverlay,
                alignment = Alignment.Center,
            )
        }
    }
}
