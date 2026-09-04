package ai.rever.boss.app

import ai.rever.boss.components.bars.horizontal.BossBottomBar
import ai.rever.boss.components.bars.horizontal.BossTitleBar
import ai.rever.boss.components.bars.horizontal.BossTopBar
import ai.rever.boss.components.bars.isBarVisible
import ai.rever.boss.components.bars.vertical.BossLeftSideBar
import ai.rever.boss.components.bars.vertical.BossRightSideBar
import ai.rever.boss.components.buttons.ToolLauncherButton
import ai.rever.boss.components.buttons.ToolboxButton
import ai.rever.boss.components.home.LocalPanelRegistry
import ai.rever.boss.components.home.LocalPluginStates
import ai.rever.boss.components.home.LocalRegistryAccess
import ai.rever.boss.components.home.LocalTabRegistry
import ai.rever.boss.components.model.BossDraggableComponent
import ai.rever.boss.components.overlays.DraggingItemOverlay
import ai.rever.boss.components.overlays.OverlayCorner
import ai.rever.boss.components.overlays.TabDraggingOverlay
import ai.rever.boss.components.plugin.LocalPanelPluginIdResolver
import ai.rever.boss.components.plugin.LocalPluginUninstallable
import ai.rever.boss.components.plugin.PanelIds
import ai.rever.boss.components.plugin.panels.left_bottom.TopOfMind.LocalSplitViewState
import ai.rever.boss.components.plugin.panels.left_bottom.TopOfMind.LocalWorkspaceManager
import ai.rever.boss.components.plugin.providers.TopOfMindDataProvider
import ai.rever.boss.components.plugin.providers.WindowIdProviderImpl
import ai.rever.boss.components.plugin.providers.WindowProjectStateProviderImpl
import ai.rever.boss.components.plugin.registries.HomeToolAccess
import ai.rever.boss.components.window_panel.BossWindow
import ai.rever.boss.components.window_panel.components.main_window_panels.TabCycleOverlayHost
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.applyWorkspace
import ai.rever.boss.components.workspaces.extractCurrentWorkspace
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.focusmode.FocusModeSettings
import ai.rever.boss.handleTabDropResult
import ai.rever.boss.layout.BossChrome
import ai.rever.boss.layout.ChromeBudgetReadout
import ai.rever.boss.layout.TrafficLightInset
import ai.rever.boss.layout.asDrawn
import ai.rever.boss.layout.bannerStartInset
import ai.rever.boss.layout.barStartInset
import ai.rever.boss.layout.columnInset
import ai.rever.boss.layout.leftColumnOffsets
import ai.rever.boss.layout.macTrafficLightInset
import ai.rever.boss.layout.needsTitleRow
import ai.rever.boss.plugin.api.LocalBookmarkDataProvider
import ai.rever.boss.plugin.api.LocalProjectPath
import ai.rever.boss.plugin.api.LocalSplitViewOperations
import ai.rever.boss.plugin.api.LocalWindowIdProvider
import ai.rever.boss.plugin.api.LocalWindowProjectStateProvider
import ai.rever.boss.plugin.api.LocalWorkspaceDataProvider
import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.Panel.Companion.right
import ai.rever.boss.plugin.sandbox.notification.PluginToastHost
import ai.rever.boss.plugin.sandbox.notification.PluginToastState
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.services.bookmarks.BookmarkAPIAccess
import ai.rever.boss.updater.UpdateAvailableDialog
import ai.rever.boss.updater.UpdateBanner
import ai.rever.boss.updater.UpdateState
import ai.rever.boss.updater.drawsBanner
import ai.rever.boss.updater.rememberUpdateDialogOwnership
import ai.rever.boss.utils.SystemUtils
import ai.rever.boss.window.LocalWindowGitState
import ai.rever.boss.window.LocalWindowId
import ai.rever.boss.window.LocalWindowProjectState
import ai.rever.boss.window.LocalWindowRunnerState
import ai.rever.boss.window.TabBarPosition
import ai.rever.boss.window.WindowAppearanceSettings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
 *    draws toasts in place, exactly as before this overlay existed.
 *
 *    This guard used to carry a second justification: it also stopped `HeavyweightCorner`'s
 *    frame-clock loop, which would otherwise have run for as long as the toast. That loop is gone -
 *    the corner renderer is event-driven now - so the reason is gone with it. **The guard is not.**
 *    The dead click region over another application is on its own sufficient, and it is the reason
 *    that was always doing the work.
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

    // For the home screen's tool grid. Both are read reactively so a plugin loading, unloading,
    // or the user's role changing re-derives the grid without a relaunch.
    // The flow, not a collected value: collecting here would invalidate this scaffold on every
    // plugin state transition. `rememberHomeTools` collects it, so only the home screen recomposes.
    val pluginStates = state.currentDefaultPlugin?.dynamicPluginManager?.pluginStates
    val registryAccess by HomeToolAccess.access.collectAsState()

    CompositionLocalProvider(
        LocalWindowId provides state.windowId,
        LocalPanelPluginIdResolver provides { panelId ->
            state.currentDefaultPlugin
                ?.dynamicPluginManager
                ?.getRegistrationTracker()
                ?.getPluginIdForPanel(panelId)
        },
        LocalPluginUninstallable provides { pluginId ->
            state.currentDefaultPlugin
                ?.dynamicPluginManager
                ?.getPluginInfo(pluginId)
                ?.manifest
                ?.let { !it.systemPlugin && it.canUnload } ?: false
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
        // For the home screen's tool grid, which derives itself from what plugins registered.
        // Locals rather than parameters because it also renders inside a browser tab showing
        // about:blank, where the caller is a plugin with no access to host state - see
        // HomeRegistryLocals.
        LocalTabRegistry provides state.tabRegistry,
        LocalPanelRegistry provides state.panelRegistry,
        LocalPluginStates provides pluginStates,
        LocalRegistryAccess provides registryAccess,
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
    appearance: WindowAppearanceSettings,
    onToggleMaximize: (() -> Unit)?,
) {
    val coroutineScope = state.coroutineScope
    val splitViewState = state.splitViewState
    val selectedProject by state.windowProjectState.selectedProject.collectAsState()

    // The content area's distance from the window's end and bottom edges, i.e. the right sidebar's
    // width plus the bottom bar's height, whatever they currently are. Measured rather than derived
    // from the reveal flags because both animate, and the quick actions have to follow them.
    //
    // Written from onGloballyPositioned, which BossActionButton deliberately avoids ("non-observable
    // holders: avoid triggering remeasure during the layout phase"). Safe here, and the difference
    // is worth stating: this value feeds only the overlay WINDOW's placement, never the layout of
    // the Box that reports it, so the write cannot feed back into its own measurement. It is passed
    // to the cluster as a lambda so the read lands in that composable's restart scope rather than
    // this one - see FocusModeQuickActions.
    var contentInset by remember { mutableStateOf(DpSize.Zero) }
    val density = LocalDensity.current.density

    // What is actually drawn, which is what these two rules are about - a bar focus mode is
    // clearing is not on screen however the preference reads. See asDrawn.
    val drawn = appearance.asDrawn(focusModeSettings)

    // Whether the hover-revealed bar is up, reported by SplitViewPanel. It decides where the
    // host's actions render while the bar is collapsed - see the placement below.
    var drawerVisible by remember { mutableStateOf(false) }

    // Whether the bar in the layout is the RAIL, reported by SplitViewPanel once it has measured.
    //
    // Seeded from the preference so the first frame has an answer, then corrected within a frame
    // by the thing that actually draws the bar. Asking the preference and stopping there is what
    // sent the host's actions to a foot that was not being drawn: a bar also rails itself when the
    // window is too narrow for a full one, and nothing in the settings says so.
    var barRailed by remember { mutableStateOf(appearance.tabBarCollapsed) }

    // Read before the placement, which asks whether any panel is open; before the rule, which
    // counts an open left panel as a column; and before the offsets, which need to know whether
    // the panel or the bar is the one behind the strip.
    val leftPanelOpen = state.draggablePanelComponent.isVisible(left)

    // Whether the RIGHT plugin panel is open - the one column whose foot can take the host's
    // actions, and the one an overlay in the bottom-right corner actually collides with. It
    // decides whether those actions get a reserved row or that overlay, in a window with no
    // vertical bar to put them in - see `focusQuickActionsPlacement`.
    //
    // Read here, in the scaffold body, so the state subscription lands in a restart scope rather
    // than inside an inline content lambda. One question answers both halves: whether these
    // actions land in a panel foot at all, and which column draws it - see hostActionsPanelEdge.
    // What the bar can offer, read before the placement because the panel measurement below is
    // gated on it: a bar that can host these means no panel is ever asked to.
    val verticalBar =
        verticalBarHost(
            tabBarOnLeft = appearance.tabBarPosition == TabBarPosition.LEFT,
            barCollapsed = barRailed,
            drawerVisible = drawerVisible,
        )

    // Gated, so the measurement costs nothing in the configuration that will never use it. With
    // the top bar up - the default, focus mode off - these actions are not homeless, and without
    // this a right-panel drag would subcompose `PanelFooterHostActions` once per frame to answer
    // a question whose answer is discarded. Nothing here reads `panelFootFits`, so gating it
    // cannot close a loop.
    val panelFooterEdge =
        state.draggablePanelComponent.hostActionsPanelColumn(
            needsAHome =
                hostActionsNeedAPanel(
                    settings = focusModeSettings,
                    topBarHidden = !appearance.showTopBar,
                    showTopBar = reveal.showTopBar,
                    verticalBar = verticalBar,
                ),
        )

    // Whether that column is big enough to hold the row, reported back out of layout by
    // `PanelFooterHostActions` - the same shape `onBarRailedChange` has, and for the same reason:
    // a panel is user-resizable down to a sliver, and how many lines four or five 32dp icons wrap
    // to in it is not something settings can answer.
    //
    // Starts true so the common case - a panel at its 250dp default, where the row is one line
    // under a plugin with 355dp - never flickers. The other order would create and tear down the
    // cluster's native window on every right-panel open, which is the cost this placement exists
    // to avoid paying.
    //
    // KEYED on the column, so closing the panel forgets the answer rather than leaving a
    // sliver's "no" behind for the next panel opened at a perfectly good width. A remember key
    // and not an effect: there is nothing to do on the reset except be true again.
    var panelFootFits by remember(panelFooterEdge) { mutableStateOf(true) }

    // Whether the collapsed tab-bar rail has enough height for its quick actions.
    // Keyed on the vertical-bar host so changing the bar mode starts from the safe default.
    var railActionsFit by remember(verticalBar) { mutableStateOf(true) }

    // Where Settings / Search / Sign Out go while focus mode holds the top bar that owns them.
    // One decision, five mutually exclusive renderings - every piece of chrome the window already
    // draws before the overlay is considered at all. Read once here so the call sites below cannot
    // disagree about it and briefly show two of them.
    val quickActionsPlacement =
        focusQuickActionsPlacement(
            settings = focusModeSettings,
            topBarHidden = !appearance.showTopBar,
            rightStripHidden = !appearance.showRightStrip,
            showTopBar = reveal.showTopBar,
            // Three answers, not "is the bar on the left". A COLLAPSED bar has no foot under its
            // split map, but it still has the bottom of its rail, which is where these go now;
            // hovering it opens the drawer, which IS a full bar, so they move up into its foot for
            // as long as it is up. Only TOP position leaves nothing at all.
            verticalBar = verticalBar,
            railActionsFit = railActionsFit,
            // Only consulted once the bar has offered nothing, i.e. in TOP position.
            panelFootAvailable = panelFootAvailable(panelFooterEdge, panelFootFits),
        )

    // Where the way into the plugins goes, when a strip that would normally hold their icons is
    // switched off. Decided here for the same reason the line above is: three call sites read it,
    // and two of them showing a launcher at once is worse than neither.
    val launcherPlacement =
        toolLauncherPlacement(
            leftStripHidden = !drawn.showLeftStrip,
            rightStripHidden = !drawn.showRightStrip,
        )

    // Non-null only in the HOST_ACTIONS case, so it can be handed to all three hosts of the
    // Settings / Search / Sign Out group unconditionally and render in whichever one is drawing.
    // Which column keeps clear of the macOS traffic lights, now that the title row no longer
    // exists to hold them. Decided once, read by the three places that could carry the inset.
    // Read above the decision below rather than beside the banner, because whether a banner is up
    // is an INPUT to that decision: while one is drawn it is the topmost chrome, so it takes the
    // clearance and nothing beneath it does.
    //
    // The BIT, not the state. UpdateState.Downloading carries a progress float that emits
    // continuously, and collecting the state itself here would invalidate this scaffold - parent
    // of the bars, the strips and the content - on every tick of a download, to answer a question
    // whose answer does not change. UpdateBanner still collects the full state for itself, inside
    // the Column, where a progress tick recomposes the banner and nothing else.
    val updateHandle = state.updateHandle
    val bannerVisible by remember(updateHandle) {
        updateHandle.updateState.map { it.drawsBanner() }.distinctUntilChanged()
    }.collectAsState(initial = false)

    val trafficLights =
        macTrafficLightInset(
            appearance = drawn,
            isMacOs = SystemUtils.isMacOS,
            bannerVisible = bannerVisible,
            // The MEASURED rail, not the preference: a bar rails itself on a narrow window too.
            barCollapsed = barRailed,
            // An open panel is a column, and a wide one - which is what lets a window with a
            // collapsed rail carry the clearance in its columns instead of in a title row.
            leftPanelOpen = leftPanelOpen,
            // The density's width, not the 36dp floor: Comfortable draws 40dp rails.
            stripWidth = BossChrome.dimens.stripWidth,
        )

    // Where each left column starts, so it can ask whether the light box reaches it.
    //
    // The order down the window's left edge is strip, then an open plugin panel, then the vertical
    // tab bar. Only the first 78dp of that is under the lights, so which column needs clearing
    // depends on what is open - and a panel, when there is one, is what the bar used to be.
    val columns =
        leftColumnOffsets(
            showLeftStrip = drawn.showLeftStrip,
            leftPanelOpen = leftPanelOpen,
            stripWidth = BossChrome.dimens.stripWidth,
        )

    val openTools = { state.showToolLauncherDialog = true }

    // The Toolbox's OWN sidebar item, so this button draws the plugin's icon and label rather than
    // a second face chosen here - and follows the plugin when it changes them.
    //
    // Found by PANEL id, not plugin id: the plugin kept the id `plugin-manager` when it was
    // renamed to Toolbox, and matching on a plugin id finds nothing and reports no error. Null
    // only if it is not registered at all, in which case no button is drawn.
    //
    // Deliberately NOT remembered: itemsBySlot is Compose state, so reading it here is what picks
    // up the Toolbox loading, unloading or changing its icon while the window is open.
    val hostToolbox: (@Composable (Panel, Modifier) -> Unit)? =
        state.draggablePanelComponent
            .toolboxSidebarItem()
            ?.let { item ->
                { hintDirection, modifier ->
                    ToolboxButton(
                        item = item,
                        // handleSidebarItemClick, not activatePlugin: it is the entry point the
                        // sidebar icons and the tools launcher use, so a custom onClick the plugin
                        // registered is honoured here too.
                        onClick = { state.draggablePanelComponent.handleSidebarItemClick(item) },
                        hintDirection = hintDirection,
                        modifier = modifier,
                    )
                }
            }

    // Takes its hint direction and size from whichever host draws it, rather than baking in one
    // set here: the top bar hints downwards, and the bar's foot and the floating cluster both sit
    // on a bottom edge and hint up. One baked-in `bottom` put the hint off the window in two of
    // the three, and a baked-in size made it the odd button out in the third.
    val hostToolLauncher: (@Composable (Panel, Modifier) -> Unit)? =
        if (launcherPlacement == ToolLauncherPlacement.HOST_ACTIONS) {
            { hintDirection, modifier ->
                ToolLauncherButton(
                    onClick = openTools,
                    hintDirection = hintDirection,
                    isSelected = state.showToolLauncherDialog,
                    modifier = modifier,
                )
            }
        } else {
            null
        }

    // Remembered, not rebuilt each pass, for the allocations and for a stable reserve - NOT to buy
    // a skip. `kotlin.collections.List` is unstable to Compose's stability inference, so taking one
    // as a parameter makes BossRightSideBar non-skippable whatever the argument identity: it was
    // skippable before this feature (no parameters, @Stable receiver) and is not now, and
    // computeSlotIconLimits re-runs with it. Making it skippable again would take an @Immutable
    // holder around the two parameters, which is not worth it for a 40dp rail. The lambdas capture
    // `state`, a single stable instance for the life of this window.
    val quickActionsRail =
        remember(quickActionsPlacement, state) {
            focusQuickActionsRail(
                placement = quickActionsPlacement,
                onShowSettings = { state.settingsWindow.open() },
                toolbox = hostToolbox,
                onShowSearch = { state.showGlobalSearchDialog = true },
                onSignOut = { state.showLogoutDialog = true },
            )
        }

    // Renders nothing; reports what the chrome costs the page when BOSS_CHROME_BUDGET is set.
    // Here rather than inside a bar so it still reports with every bar switched off.
    //
    // The metrics are read once here and handed to both the readout and the bars, so a provider
    // installed anywhere above this line reaches them together. Resolving them separately inside the
    // readout is what would let it report Comfortable while the bars drew Compact.
    val chromeDimens = BossChrome.dimens
    ChromeBudgetReadout(
        windowId = state.windowId,
        appearance = appearance,
        focusMode = focusModeSettings,
        dimens = chromeDimens,
    )

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
                // Also drawn when the traffic lights have nowhere else to go: with no left strip
                // and the tab bar across the top, the only thing under them is the content, and a
                // full-width reserve costs no more than padding the content would. See
                // TrafficLightInset.CONTENT.
                if (trafficLights.needsTitleRow(appearance.showTitleBar)) {
                    BossTitleBar(
                        onToggleMaximize = onToggleMaximize,
                    )
                }

                // Update banner - always visible (even in focus mode)
                val updateState by updateHandle.updateState.collectAsState()
                // Every action runs on the manager's scope, never this window's
                // rememberCoroutineScope(): that scope dies with the composition, so
                // closing the window mid-install used to cancel the install (leaving
                // UpdateState on Installing) and could drop a persisted dismissal.
                UpdateBanner(
                    updateState = updateState,
                    // Non-zero only when this banner is the chrome holding the lights, in which
                    // case the bars below it have already given up their own clearance.
                    startInset = trafficLights.bannerStartInset(),
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
                    onCancelDownload = { updateHandle.cancelDownload() },
                    onDiscardDownload = { updateHandle.discardDownloadInBackground() },
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

                // Tell the manager every workspace THIS window is running, so the workspace menu
                // can mark them all rather than only the one on screen. Read from the window's
                // own split state, which is where the live ones actually are - the manager's
                // currentWorkspace is one value shared by every window and knows about neither
                // the others' nor the ones running behind this one.
                val liveWorkspaceIds = splitViewState.liveWorkspaceIds
                DisposableEffect(state.windowId, liveWorkspaceIds) {
                    workspaceManager.setWindowWorkspaces(state.windowId, liveWorkspaceIds)
                    onDispose { workspaceManager.releaseWindow(state.windowId) }
                }

                // Switching workspaces, in one place: the top bar offers it and so does the
                // vertical tab bar's foot when the top bar is off. Two copies of "preserve, load,
                // apply" is two places for that order to drift, and the order is the whole of why
                // switching away and back does not lose a layout. See WorkspaceSwitch.kt for why
                // a switch is two decisions rather than one.
                val workspaceSwitch = rememberWorkspaceSwitch(state, splitViewState)
                val applyWorkspaceAndPreserve = workspaceSwitch.request
                WorkspaceSwitchPrompt(state, workspaceSwitch)

                // Top bar - hidden in focus mode with smooth expand/shrink animation, and switched
                // off outright by the appearance preference. Both have to agree for a bar to show:
                // focus mode is the transient posture, the preference is the standing choice.
                AnimatedVisibility(
                    visible = appearance.showTopBar && reveal.showTopBar,
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
                            onApplyWorkspace = applyWorkspaceAndPreserve,
                            getCurrentWorkspace = {
                                extractCurrentWorkspace(splitViewState, selectedProject.path)
                            },
                            onShowTopOfMind = {
                                state.showTopOfMindDialog = true
                            },
                            onShowSettings = {
                                state.settingsWindow.open()
                            },
                            toolbox = hostToolbox,
                            // Only non-null when neither icon strip is on screen, so the top bar
                            // grows a tools button exactly in the configuration where nothing
                            // else can hold one.
                            toolLauncher = hostToolLauncher,
                            // Clearance for the macOS traffic lights, which are drawn over this
                            // bar's start when there is no title row above it.
                            startInset = trafficLights.barStartInset(),
                            onShowSearch = {
                                state.showGlobalSearchDialog = true
                            },
                            onSignOut = {
                                state.showLogoutDialog = true
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
                        visible = appearance.showLeftStrip && reveal.showLeftSidebar,
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
                            modifier =
                                Modifier
                                    .hoverable(interactionSource = reveal.leftSidebarInteractionSource)
                                    // Painted before padded - see WindowBarRow for what an
                                    // unpainted inset shows through to.
                                    .background(BossTheme.colors.raised)
                                    .padding(top = trafficLights.columnInset()),
                        ) {
                            BossLeftSideBar(
                                toolsOpen = state.showToolLauncherDialog,
                                onOpenTools =
                                    openTools.takeIf {
                                        launcherPlacement == ToolLauncherPlacement.LEFT_STRIP
                                    },
                            )
                        }
                    }

                    // Main content area - always visible (contains tabs)
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .reportContentInset(density) { contentInset = it },
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
                            // Composed HERE rather than inside the bar: this is where the
                            // workspace manager and the window's project dialog already are, and
                            // a tab bar has no business knowing about either. It reaches the bar
                            // as a slot. See VerticalBarWindowControls.
                            // Settings / Search / Sign Out (and the launcher, when it joins them)
                            // at the very foot of the bar, under the split map - the placement
                            // that displaces the floating cluster wherever this bar is on screen.
                            // The tab bar is the leftmost column when no strip is on, so its top
                            // is what the lights would land on.
                            // Offset past an open plugin panel, which sits between the strip and
                            // this bar: with one open the bar is the THIRD column and well clear
                            // of the box, and the 28dp gap it used to keep was pure dead space.
                            verticalBarTopInset = trafficLights.columnInset(columns.bar),
                            // The panel itself is second when it is open, so the clearance moves
                            // onto it - this is what the lights were landing on.
                            leftPanelTopInset = trafficLights.columnInset(columns.panel),
                            // Settings / Search / Sign Out at the foot of the open right panel's
                            // column, for a TOP tab bar with no bar of its own to hold them. Empty
                            // - and so no row at all - for every other placement.
                            panelFooterEdge = panelFooterEdge,
                            panelFooter = {
                                PanelFooterHostActions(
                                    // What the row WOULD hold, which the fit measurement needs
                                    // while the list is empty - the state a "does not fit" answer
                                    // puts it in. Counted as `focusQuickActionsRailRows` counts
                                    // the rail's reserve: the four, plus the launcher when both
                                    // strips are off and this group is where it landed.
                                    actionCount =
                                        hostActionsRowSize(
                                            hasToolbox = hostToolbox != null,
                                            hasLauncher = hostToolLauncher != null,
                                        ),
                                    actions =
                                        focusQuickActionsPanelFooter(
                                            placement = quickActionsPlacement,
                                            onShowSettings = { state.settingsWindow.open() },
                                            toolbox = hostToolbox,
                                            onShowSearch = { state.showGlobalSearchDialog = true },
                                            onSignOut = { state.showLogoutDialog = true },
                                            toolLauncher = hostToolLauncher,
                                        ),
                                    onColumnFitsChange = { fits -> panelFootFits = fits },
                                )
                            },
                            onDrawerVisibleChange = { visible -> drawerVisible = visible },
                            onBarRailedChange = { railed -> barRailed = railed },
                            onRailFitsActionsChange = { fits -> railActionsFit = fits },
                            verticalBarBelowMap = {
                                VerticalBarHostActions(
                                    actions =
                                        focusQuickActionsFooter(
                                            placement = quickActionsPlacement,
                                            onShowSettings = { state.settingsWindow.open() },
                                            toolbox = hostToolbox,
                                            onShowSearch = { state.showGlobalSearchDialog = true },
                                            onSignOut = { state.showLogoutDialog = true },
                                            toolLauncher = hostToolLauncher,
                                        ),
                                )
                            },
                            // The rail's own layout for the same actions, in its OWN slot: the
                            // rail and the hover drawer are on screen together, so a slot handed
                            // to both drew these twice - see `WindowVerticalTabBar.belowTabs`.
                            verticalBarRailActions = {
                                VerticalBarRailActions(
                                    actions =
                                        focusQuickActionsTabRail(
                                            placement = quickActionsPlacement,
                                            onShowSettings = { state.settingsWindow.open() },
                                            toolbox = hostToolbox,
                                            onShowSearch = { state.showGlobalSearchDialog = true },
                                            onSignOut = { state.showLogoutDialog = true },
                                            toolLauncher = hostToolLauncher,
                                        ),
                                )
                            },
                            verticalBarFooter = {
                                VerticalBarWindowControls(
                                    // `drawn`, not the preference: a top bar focus mode has
                                    // cleared is not on screen, and the project and workspace
                                    // pickers live nowhere else.
                                    topBarHidden = !drawn.showTopBar,
                                    project = selectedProject,
                                    onOpenProject = { state.showProjectDialog = true },
                                    workspaceManager = workspaceManager,
                                    onApplyWorkspace = applyWorkspaceAndPreserve,
                                    getCurrentWorkspace = {
                                        extractCurrentWorkspace(splitViewState, selectedProject.path)
                                    },
                                    onShowTopOfMind = { state.showTopOfMindDialog = true },
                                )
                            },
                        )

                        // Settings / Search / Sign Out, which the top bar otherwise owns outright.
                        // The FLOATING half of the placement: reached only when focus mode has
                        // cleared the right sidebar too, since with the rail up they go in it.
                        // Composed inside the content area so the lightweight path aligns where it
                        // draws; contentInset is what makes the heavyweight path agree.
                        //
                        // Deliberately NOT also gated on "is a dialog open". An earlier revision
                        // listed the two dialogs this cluster opens, which would have had to grow
                        // every time anyone added a dialog to BossAppDialogs and would have gone
                        // stale silently. It is not needed on either path: a lightweight BossDialog
                        // falls back to Compose's own Dialog, a real platform window ABOVE the
                        // composition, so an in-place cluster is underneath it and never over it;
                        // and a heavyweight modal takes window focus, which drops this to the same
                        // in-place path by the focus guard in FocusModeQuickActions.
                        //
                        // `hides(TOP)` is not redundant with `!showTopBar`, it is what keeps this
                        // off the launch path entirely. `FocusModeEdgeRevealState.shown` starts
                        // false and is only turned back on by a LaunchedEffect, so on the FIRST
                        // composition of every window `!showTopBar` is true whether or not focus
                        // mode is even enabled. Without this the heavyweight path would create and
                        // immediately dispose a native always-on-top window on every window open,
                        // flash it in the corner for users who never turn focus mode on, and call
                        // contentPaneBounds before the pane is reliably showing - which can burn
                        // the one-per-session warning flag that exists to make a REAL failure
                        // visible. Gating on the setting is also just the honest condition: the
                        // cluster exists because focus mode clears the top bar.
                        FocusModeQuickActions(
                            visible = quickActionsPlacement == FocusQuickActionsPlacement.FLOATING,
                            inset = { contentInset },
                            onShowSettings = { state.settingsWindow.open() },
                            toolbox = hostToolbox,
                            onShowSearch = { state.showGlobalSearchDialog = true },
                            onSignOut = { state.showLogoutDialog = true },
                            toolLauncher = hostToolLauncher,
                        )
                    }

                    // Right sidebar - hidden in focus mode with smooth expand/shrink animation
                    AnimatedVisibility(
                        visible = appearance.showRightStrip && reveal.showRightSidebar,
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
                            // The RIGHT_RAIL half of the placement, and empty for the other two.
                            // The rail is where these belong whenever there is a rail: three more
                            // icons on a strip that is already icon chrome, instead of an overlay
                            // over live content.
                            //
                            // The reserve is deliberately NOT the rendered count: it is held for
                            // as long as focus mode owns the top bar, so hover-revealing that bar
                            // takes the three icons away without also handing their rows back to
                            // the plugin slots and reshuffling them. See focusQuickActionsRailRows.
                            BossRightSideBar(
                                toolsOpen = state.showToolLauncherDialog,
                                onOpenTools =
                                    openTools.takeIf {
                                        launcherPlacement == ToolLauncherPlacement.RIGHT_STRIP
                                    },
                                bottomActions = quickActionsRail,
                                bottomActionRows =
                                    focusQuickActionsRailRows(
                                        settings = focusModeSettings,
                                        topBarHidden = !appearance.showTopBar,
                                        rightStripHidden = !appearance.showRightStrip,
                                    ),
                            )
                        }
                    }
                }

                // Bottom bar - hidden in focus mode with smooth expand/shrink animation
                AnimatedVisibility(
                    visible = appearance.showBottomBar && reveal.showBottomBar,
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
                // No strip for a bar switched off in settings: hover cannot bring it back, so the
                // band would sit dead over live content and, at the top edge, hide the quick actions.
                barVisible = { edge -> appearance.isBarVisible(edge) },
            )

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

/**
 * Which plugin panel column takes the host's actions, or null when the right one is shut.
 *
 * The read; [hostActionsPanelEdge] is the rule, including why the left and bottom columns are not
 * candidates. One state subscription added to this composable's restart scope, on top of the
 * `isVisible(left)` the traffic-light rule already reads.
 *
 * Slightly broader than "the right panel opened or closed", stated exactly because the file is
 * careful about this elsewhere: `isVisible` folds `right.top` and `right.bottom`, and
 * `setPanelVisible` and `toggleVisibility` write a whole fresh `PanelData`, so any write to either
 * half - a `sidebarItem` change that does not touch visibility included - recomposes this
 * scaffold. Still user-scale in every case, and the same mechanism already behind the overlay
 * teardown this placement documents; noted rather than narrowed because a `derivedStateOf` here
 * would buy a comparison per write and cost a reader the ability to see what is subscribed.
 *
 * A plain function rather than a `@Composable`: it only reads snapshot state, so called during
 * composition it subscribes its caller exactly as an inline chain would. Named at all because
 * this composable is at detekt's cyclomatic ceiling.
 */
private fun BossDraggableComponent.hostActionsPanelColumn(needsAHome: Boolean): Panel? =
    hostActionsPanelEdge(rightOpen = isVisible(right), needsAHome = needsAHome)

/**
 * Whether the host's actions are looking for a panel to live in at all.
 *
 * Both halves of "nothing above the panel can take them": focus mode has actually cleared the top
 * bar that owns them, AND there is no vertical bar, which is the only host ahead of the panel in
 * the ladder. See `focusQuickActionsPlacement` for the ladder itself.
 *
 * Deliberately reads none of the measured state. It gates the panel MEASUREMENT, so a term here
 * that depended on what that measurement reports would be a cycle rather than a gate.
 */
private fun hostActionsNeedAPanel(
    settings: FocusModeSettings,
    topBarHidden: Boolean,
    showTopBar: Boolean,
    verticalBar: VerticalBarHost,
): Boolean = focusQuickActionsVisible(settings, topBarHidden, showTopBar) && verticalBar == VerticalBarHost.NONE

/**
 * Whether the host's actions have a panel foot to go in: there is a column, and it can hold the row.
 *
 * Both halves, because either one alone ships a bug. Without [edge] the row is drawn into a column
 * nothing composes; without [fits] it is drawn into a 20dp sliver, where it wraps five lines deep
 * and takes 188dp out of the plugin - see `panelFooterFitsColumn`, which is where [fits] comes
 * from, and `PanelFooterHostActions`, which measures it.
 *
 * Named rather than written inline for the same reason [hostActionsPanelEdge] is: this composable
 * is at detekt's cyclomatic ceiling, and one more `&&` in its body puts it over.
 */
private fun panelFootAvailable(
    edge: Panel?,
    fits: Boolean,
): Boolean = edge != null && fits

/**
 * How many buttons the host's action row would hold.
 *
 * What `PanelFooterHostActions` measures its column against, and it has to be answerable while
 * the list itself is empty - which is the state a "does not fit" answer puts that row in.
 *
 * EXACT, which is where this parts company with `focusQuickActionsRailRows`. That function
 * over-counts on purpose: its number is a height reserve, and a reserve that tracked the rendered
 * list would move the icons above it on every hover. This one answers a one-shot geometry
 * question with no stability to protect, and over-counting only means a column that would have
 * held the real row falls back to the overlay - `PanelFooterFitTest` shows the band where one
 * button decides it. So `listOfNotNull` dropping an unregistered Toolbox has to be counted too.
 *
 * Named rather than inlined because [BossAppScaffold] is at detekt's cyclomatic ceiling and one
 * more `if` in its body puts it over.
 */
private fun hostActionsRowSize(
    hasToolbox: Boolean,
    hasLauncher: Boolean,
): Int = FOCUS_QUICK_ACTION_COUNT - (if (hasToolbox) 0 else 1) + (if (hasLauncher) 1 else 0)
