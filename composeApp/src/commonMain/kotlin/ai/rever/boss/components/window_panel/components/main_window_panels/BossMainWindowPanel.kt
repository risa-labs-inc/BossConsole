package ai.rever.boss.components.window_panel.components.main_window_panels

import ai.rever.boss.components.bars.ScrollbarConfig
import ai.rever.boss.components.bars.horizontal.HorizontalBar
import ai.rever.boss.components.bars.horizontal.HorizontalBarRow
import ai.rever.boss.components.bars.horizontal.StatusMessageManager
import ai.rever.boss.components.bars.horizontalScrollWithScrollbar
import ai.rever.boss.components.bars.vertical.VerticalBar
import ai.rever.boss.components.bookmarks.Bookmark
import ai.rever.boss.components.bookmarks.WorkspacePanelTarget
import ai.rever.boss.components.buttons.BossTabButton
import ai.rever.boss.components.common.rememberFaviconLoader
import ai.rever.boss.components.dialogs.BookmarkDialog
import ai.rever.boss.components.dialogs.NewTabDialog
import ai.rever.boss.components.dialogs.RemoveBookmarkConfirmationDialog
import ai.rever.boss.components.dialogs.TabType
import ai.rever.boss.components.dividers.VDivider
import ai.rever.boss.components.home.HomeScreen
import ai.rever.boss.components.model.ScrollDirection
import ai.rever.boss.components.model.TabDraggableComponent
import ai.rever.boss.components.model.TabDropResult
import ai.rever.boss.components.model.TabDropTarget
import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.components.overlays.contextMenu
import ai.rever.boss.components.plugin.DynamicPluginManager
import ai.rever.boss.components.plugin.LocalPanelPluginIdResolver
import ai.rever.boss.components.plugin.MissingPluginOffer
import ai.rever.boss.components.plugin.PluginBuildRegistry
import ai.rever.boss.components.plugin.PluginBuildTag
import ai.rever.boss.components.plugin.TabUpdateRegistry
import ai.rever.boss.components.plugin.providers.publishSystemEvent
import ai.rever.boss.components.plugin.tab_types.PanelHostTabInfo
import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.components.tabs_navigation.TabsNavigation
import ai.rever.boss.components.window_panel.SplitOrientation
import ai.rever.boss.components.workspaces.PredefinedWorkspaces
import ai.rever.boss.components.workspaces.TabConfig
import ai.rever.boss.components.workspaces.applyWorkspace
import ai.rever.boss.components.workspaces.createTabFromWorkspaceConfig
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.icons.FileIcons
import ai.rever.boss.keymap.KeymapSettingsManager
import ai.rever.boss.keymap.model.TabSwitchMode
import ai.rever.boss.layout.BossChrome
import ai.rever.boss.plugin.api.LocalIsPanelActive
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabEvent
import ai.rever.boss.plugin.api.TabEventType
import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabUpdateProvider
import ai.rever.boss.plugin.api.TabUpdateProviderFactory
import ai.rever.boss.plugin.sandbox.TabSandboxRegistry
import ai.rever.boss.plugin.sandbox.ui.PluginCrashRegistry
import ai.rever.boss.plugin.sandbox.ui.PluginErrorBoundary
import ai.rever.boss.plugin.tab.codeeditor.CodeEditorTabType
import ai.rever.boss.plugin.tab.codeeditor.EditorTabInfo
import ai.rever.boss.plugin.tab.fluck.FluckTabType
import ai.rever.boss.plugin.tab.jupyter.JupyterTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.project.DefaultWorkingDirectory
import ai.rever.boss.run.RUNNER_TERMINAL_PREFIX
import ai.rever.boss.run.RunnerTerminalService
import ai.rever.boss.services.bookmarks.BookmarkAPIAccess
import ai.rever.boss.services.bookmarks.rememberBookmarkCollections
import ai.rever.boss.utils.extractFileName
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.revealInFileManager
import ai.rever.boss.utils.revealInFileManagerLabel
import ai.rever.boss.window.LocalWindowId
import ai.rever.boss.window.LocalWindowProjectState
import ai.rever.boss.window.MenuActionsHandler
import ai.rever.boss.window.Project
import ai.rever.boss.window.TabBarPosition
import ai.rever.boss.window.TabBarVerticalWidthRange
import ai.rever.boss.window.TabWidthMode
import ai.rever.boss.window.WindowAppearanceSettingsManager
import ai.rever.boss.window.WindowOperations
import ai.rever.boss.window.displayName
import ai.rever.boss.window.selectProjectInWindow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Splitscreen
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.runtime.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

private val bossMainWindowPanelLogger = BossLogger.forComponent("BossMainWindowPanel")

/**
 * Wrapper for BossTabButton that loads and displays favicons from cache
 * Uses shared rememberFaviconLoader composable for DRY and error handling
 */
@Composable
private fun BossTabButtonWithFavicon(
    config: TabInfo,
    isSelected: Boolean,
    isFocused: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    contextMenuItems: List<ContextMenuItem>,
    tabWidth: Dp?,
    vertical: Boolean = false,
    tabHeight: Dp = VERTICAL_TAB_HEIGHT,
    // Drag-related parameters
    tabDragComponent: TabDraggableComponent? = null,
    panelId: String? = null,
    tabIndex: Int = -1,
    onDragEnd: (TabDropResult?) -> Unit = {},
    onContextMenuVisibilityChange: (Boolean) -> Unit = {},
) {
    // Load favicon using shared composable (with error handling and caching)
    val loadedFavicon = rememberFaviconLoader(config)

    // Determine which icon to use: loaded favicon > config.tabIcon > fallback to config.icon
    val effectiveTabIcon = loadedFavicon ?: config.tabIcon

    // A plugin panel opened as a tab carries the same build tag its sidebar header would. Resolved
    // here rather than baked into PanelHostTabInfo.title: tab titles are persisted into workspace
    // layouts, so a suffixed title would be restored later and go stale.
    val panelHost = config as? PanelHostTabInfo
    val pluginBuilds by PluginBuildRegistry.builds.collectAsState()
    val resolvePluginId = LocalPanelPluginIdResolver.current
    val tabWindowId = LocalWindowId.current
    val buildInfo =
        panelHost?.let { host -> resolvePluginId(host.panelId)?.let { pluginBuilds[it] } }?.takeIf { it.isTagged }

    // Middle-click handling is now in BossTabButton.kt (Issue #328)
    BossTabButton(
        fileName = config.title,
        icon = config.icon,
        tabIcon = effectiveTabIcon,
        titleBadge =
            buildInfo?.let { info ->
                // panelHost is non-null here by construction: buildInfo was resolved from it.
                val hostedPanelId = panelHost.panelId
                {
                    PluginBuildTag(
                        info = info,
                        onClick =
                            if (tabWindowId != null) {
                                { MenuActionsHandler.triggerInstallStoreVersion(tabWindowId, hostedPanelId) }
                            } else {
                                null
                            },
                    )
                }
            },
        isSelected = isSelected,
        isFocused = isFocused,
        onClick = onClick,
        onClose = onClose,
        contextMenuItems = contextMenuItems,
        tabWidth = tabWidth,
        vertical = vertical,
        tabHeight = tabHeight,
        tabDragComponent = tabDragComponent,
        tabInfo = config,
        panelId = panelId,
        tabIndex = tabIndex,
        onDragEnd = onDragEnd,
        onContextMenuVisibilityChange = onContextMenuVisibilityChange,
    )
}

/**
 * The "+" (new tab) button. Rendered either as a LazyRow item hugging the
 * last tab (legacy FIXED sizing while everything fits) or as the tab strip's
 * non-scrolling trailing slot, which also sits directly after the last tab —
 * see the call sites in BossMainTabBar.
 */
@Composable
private fun NewTabButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            modifier
                .size(NEW_TAB_BUTTON_SIZE)
                .padding(4.dp)
                .background(
                    color = BossTheme.colors.raised,
                    shape =
                        androidx.compose.foundation.shape
                            .RoundedCornerShape(4.dp),
                ).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = "New Tab",
            tint = BossTheme.colors.textSecondary,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * Everything one panel's tab bar needs, separated from the container that draws it.
 *
 * Exists because a WINDOW-level vertical bar has to put several panels' tabs inside ONE
 * `LazyColumn`, and a lazy list's items can only be produced inside its own content lambda. So
 * the per-panel state has to be hoisted ABOVE that list and handed down as items, rather than
 * each panel bringing its own container - which is exactly what made a split grow a second bar.
 *
 * Deliberately NOT `remember`ed. It is a bundle of lambdas over values that change every
 * composition (the drop target, the width mode, the tab list), so a remembered instance would
 * capture the first frame's and never update. The MUTABLE state inside [rememberTabBarState] is
 * remembered individually, which is what actually needs to survive.
 */
@Stable
class TabBarState
    // A state bundle, not a call site: every field is passed by name and each one is a
    // distinct thing a container needs. Grouping them into sub-holders to satisfy the count
    // would add indirection at every use without making anything clearer.
    @Suppress("LongParameterList")
    internal constructor(
        /** The tab rows, spliced into whichever list is drawing them. */
        val items: LazyListScope.(tabWidth: Dp?) -> Unit,
        /** Right-click menu for the bar's own empty chrome. */
        val barContextMenuItems: List<ContextMenuItem>,
        /** Open the new-tab dialog for this panel. */
        val openNewTab: () -> Unit,
        /** Open it such that what comes out is pinned. */
        val openPinnedTab: () -> Unit,
        /** This panel's tabs, already subscribed. */
        val tabs: List<TabInfo>,
        /** Index of this panel's active tab. */
        val activeIndex: Int,
        /** How many leading tabs are pinned. */
        val pinnedCount: Int,
        /** Per-tab right-click menu, shared by the full rows and the collapsed rail's dots. */
        val tabMenuItems: (Int, TabInfo) -> List<ContextMenuItem>,
        /** Select a tab in this panel. */
        val activateTab: (Int) -> Unit,
        /** Bookmarks for the Favorites shelf, flattened across collections. */
        val favorites: List<ai.rever.boss.plugin.bookmark.Bookmark>,
        /** Remove a favourite, resolving its owning collection. */
        val removeFavorite: (ai.rever.boss.plugin.bookmark.Bookmark) -> Unit,
        /** Open a favourite as a tab in this panel. */
        val openFavorite: (ai.rever.boss.plugin.bookmark.Bookmark) -> Unit,
        /** Whether the bookmarks plugin is installed, by the Install button's own predicate. */
        val bookmarksInstalled: Boolean?,
        /** Whether that plugin is actually serving its API. */
        val bookmarksApiReachable: Boolean,
        /** Safari-style shrink-to-fit, which only the top strip consults. */
        val shrinkTabsToFit: Boolean,
        /** This panel's dialogs. Must stay mounted wherever the bar is drawn. */
        val dialogs: @Composable () -> Unit,
        /** Lazy-list items that precede the first tab, for anything indexing the list. */
        val leadingListItems: Int,
        /** Scroll state for this panel's rows. */
        val listState: LazyListState,
    ) {
        /**
         * How many lazy-list items this group contributes, tabs and its own leading rows alike.
         *
         * A window bar adds these up to know where each group starts in the shared column.
         */
        val listItemCount: Int get() = leadingListItems + tabs.size
    }

/**
 * Build a panel's [TabBarState].
 *
 * This is the whole of what `BossMainTabBar` used to do before it drew anything, moved out
 * verbatim so that both containers - the top strip and the window-level vertical bar - can share
 * it without either owning the other's layout.
 */
@Composable
fun BossTabsComponent.rememberTabBarState(
    splitViewState: ai.rever.boss.components.window_panel.SplitViewState? = null,
    currentPanelId: String? = null,
    focusRequester: FocusRequester? = null,
    tabDragComponent: TabDraggableComponent? = null,
    onTabDropResult: (TabDropResult) -> Unit = {},
    /**
     * Render as a vertical bar down the panel's leading edge rather than a strip across its top.
     *
     * Passed in rather than read from `WindowAppearanceSettings` here, because the caller has to
     * know it too - it is what decides whether the panel is a Row or a Column - and a second,
     * independent read is a second thing that can disagree.
     */
    vertical: Boolean = false,
    /**
     * Reports whether an interaction that outlives a single click is in flight - today, an open
     * tab context menu.
     *
     * All of it is state owned by this composition, so an owner that disposes the bar on its own
     * schedule (the hover-reveal drawer, which retracts when the pointer leaves) has to keep it
     * alive while this is true. Otherwise right-clicking a tab in the drawer and moving onto the
     * menu drops the menu. Null for every owner whose lifetime is not tied to the pointer.
     */
    onTransientInteraction: ((Boolean) -> Unit)? = null,
    /**
     * Invoked after a tab is activated from this bar, whichever control did it.
     *
     * For the hover-reveal drawer, which is finished the moment the user picks something and
     * would otherwise sit open under the pointer that just used it.
     */
    onTabActivated: (() -> Unit)? = null,
    /**
     * The list these rows will actually live in, when that list is not this panel's own.
     *
     * A window-level bar splices several panels' rows into ONE column, and everything here that
     * scrolls - the drag edge-scroll, the keep-the-active-tab-visible effect - has to move that
     * column rather than a state nothing is attached to. Null means "you are the only group in
     * your list", which is the top strip and the single-panel case.
     */
    sharedListState: LazyListState? = null,
    /**
     * Whether this panel may scroll the list to keep its own active tab in view.
     *
     * False for a group in a shared list: a background pane changing tabs would otherwise yank
     * the viewport away from the pane the user is looking at. The owner of a shared list runs
     * one such effect, for the active pane only.
     */
    autoScrollToActive: Boolean = true,
    /**
     * Whether a pinned block may carry its PINNED / OPEN headers.
     *
     * False once the bar holds more than one group. The group rules already divide the column,
     * and a second kind of divider inside each of them turns the bar into mostly headers. Pinned
     * tabs stay first either way - that is the invariant, not the labels.
     */
    showSections: Boolean = true,
    /**
     * Whether this group belongs to the pane the user is working in.
     *
     * Only the active pane's selected tab wears the amber marker; every other group's shows the
     * quiet line. With one bar for several panes this is the only thing saying which pane a click
     * will land in - see BossTabButton's isFocused.
     */
    isActiveGroup: Boolean = true,
): TabBarState {
    val tabsState = tabsState.subscribeAsState()
    var showNewTabDialog by remember { mutableStateOf(false) }
    var selectedTabType by remember { mutableStateOf<TabType?>(null) }
    // Per-window project state for terminal working directory
    val windowProjectState = LocalWindowProjectState.current
    var showBookmarkDialog by remember { mutableStateOf(false) }
    var tabToBookmark by remember { mutableStateOf<TabInfo?>(null) }

    // Observe collections for reactive context menu updates (gracefully handles missing plugin)
    val collections = rememberBookmarkCollections()

    // Favorites: every bookmark across every collection, flattened. Collections organise the
    // bookmarks panel; the sidebar grid is a flat "things I go to constantly", so the grouping
    // is deliberately dropped here rather than rendered as more sections.
    val favorites = remember(collections) { collections.flatMap { it.bookmarks } }

    // Whether the plugin that owns bookmarks is present at all. Null provider is how
    // BookmarkAPIAccess reports its absence, and it is the difference between "you have saved
    // nothing" and "you have nowhere to save it".
    //
    // Keyed on the manager's OBSERVABLE plugin states rather than read bare. getProvider() is a
    // plain registry lookup, so on its own this would still say "not installed" after the Install
    // button had finished - the shelf would sit on its offer until some unrelated recomposition
    // happened to refresh it, which is the same class of failure as a button that does nothing.
    val pluginStates =
        DynamicPluginManager
            .anyActiveManager()
            ?.pluginStates
            ?.collectAsState()
            ?.value
    // Two DIFFERENT questions, and conflating them is what made the Install button a no-op:
    // whether the plugin is installed (what the Install button's own predicate answers, so the
    // shelf and the button can never disagree), and whether it is actually serving its API right
    // now. A plugin rejected by BinaryCompatibilityValidator is installed and enabled and NOT
    // running, which is exactly the gap the first version fell into.
    val bookmarksInstalled =
        remember(pluginStates) { MissingPluginOffer.isInstalled(BOOKMARKS_PLUGIN_ID) }
    val bookmarksApiReachable =
        remember(pluginStates) { BookmarkAPIAccess.getProvider() != null }

    // Remove bookmark dialog state
    var showRemoveBookmarkDialog by remember { mutableStateOf(false) }
    var bookmarkToRemove by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    // Triple = (collectionId, bookmarkId, tabTitle)

    // LazyListState for tab bar scrolling. Remembered unconditionally even when the caller
    // supplies one: a remember that appears only on some compositions is a positional slot that
    // moves, and this call site is shared by every container.
    val ownListState = rememberLazyListState()
    val listState = sharedListState ?: ownListState

    // Tab sizing behaviour (Settings → Window Appearance → Tab Bar).
    // SHRINK_TO_FIT passes the computed per-tab width down to each button;
    // FIXED passes null, which falls back to the legacy intrinsic sizing.
    val appearanceSettings by WindowAppearanceSettingsManager.currentSettings.collectAsState()
    val shrinkTabsToFit = appearanceSettings.tabWidthMode == TabWidthMode.SHRINK_TO_FIT

    // Legacy FIXED mode only: drives whether the "+" button renders inside
    // the row (hugging the last tab) or outside (fixed at the right edge).
    // SHRINK_TO_FIT ignores this — its tabs always fill the bar, so the
    // outside placement is both natural and race-free.
    val isScrollable by remember {
        derivedStateOf {
            listState.canScrollForward || listState.canScrollBackward
        }
    }

    // Coroutine scope for edge scroll animation
    val edgeScrollScope = rememberCoroutineScope()

    // Separate scope for the bar's own settings writes (the Tab Bar Position submenu). Kept
    // apart from the edge-scroll scope so a settings write is never cancelled by a tab bar that
    // recomposed mid-drag, and so neither reads as the other's concern.
    val barSettingsScope = rememberCoroutineScope()

    // Current position, read for the submenu's checkmark only - the layout itself is driven by
    // the `vertical` parameter, which the caller resolved.
    val tabBarPosition = appearanceSettings.tabBarPosition

    // Menus open right now, counted rather than latched: several tabs can each have had one, and
    // a single Boolean would be cleared by whichever closed first while another was still up.
    var openMenuCount by remember { mutableIntStateOf(0) }
    val latestTransientInteraction by rememberUpdatedState(onTransientInteraction)
    LaunchedEffect(openMenuCount > 0) { latestTransientInteraction?.invoke(openMenuCount > 0) }

    // Whether the tab the new-tab dialog is about to create should land pinned. Set by the
    // Pinned section's "+", cleared as soon as a tab is created or the dialog is dismissed, so it
    // can never leak into the next unrelated "+".
    var pinCreatedTab by remember { mutableStateOf(false) }

    // Opening a new tab, in one place: four "+" controls reach it (the top strip's trailing slot,
    // its legacy in-row placement, the vertical bar's bottom row and the rail's) plus the bar's
    // context menu, and they must not drift on the panel-activation half.
    val openNewTab = {
        pinCreatedTab = false
        showNewTabDialog = true
        // Track panel interaction when the plus button is clicked
        if (splitViewState != null && currentPanelId != null) {
            splitViewState.setActivePanel(currentPanelId)
        }
    }

    // The Pinned section header's "+". Same dialog, but what comes out of it is pinned.
    val openPinnedTab = {
        openNewTab()
        pinCreatedTab = true
    }

    // Every path out of the new-tab dialog lands here, which is what makes "open it pinned" one
    // flag rather than a branch in each of the five tab kinds the dialog can produce.
    val openCreatedTab: (TabInfo) -> Unit = { tabInfo ->
        val tabIndex = addTab(tabInfo)
        if (tabIndex >= 0) {
            if (pinCreatedTab) {
                pinTab(tabIndex)
                // pinTab MOVED it, so the index it was added at is stale; it is now last in the
                // pinned block.
                selectTab(pinnedCount - 1)
            } else {
                selectTab(tabIndex)
            }
        }
        pinCreatedTab = false
    }

    // Opening a favourite. Routed through the WORKSPACE converter rather than a second
    // TabConfig -> TabInfo mapping of its own: a bookmark stores exactly the TabConfig a
    // workspace does, and that function already knows how to rebuild every tab type from one,
    // favicon cache included. A private copy here would be a second mapping to keep in step with
    // every new tab type.
    val openBookmark: (ai.rever.boss.plugin.bookmark.Bookmark) -> Unit = { bookmark ->
        val projectPath =
            windowProjectState
                ?.selectedProject
                ?.value
                ?.path
                .orEmpty()
        val resolved = DefaultWorkingDirectory.resolve(projectPath)
        val tabInfo =
            splitViewState?.let { state ->
                createTabFromWorkspaceConfig(bookmark.tabConfig, resolved, state)
            }
        if (tabInfo != null) {
            openCreatedTab(tabInfo)
        } else {
            bossMainWindowPanelLogger.warn(
                LogCategory.UI,
                "Favourite could not be opened",
                mapOf("type" to bookmark.tabConfig.type, "title" to bookmark.tabConfig.title),
            )
        }
    }

    // Activating a tab, in one place: a full tab row and a rail dot both do it, and both owe the
    // owner an onTabActivated afterwards.
    val activateTab: (Int) -> Unit = { index ->
        selectTab(index)
        // Track this tab interaction for Cmd+R/Cmd+N
        if (splitViewState != null && currentPanelId != null) {
            tabsState.value.tabs.getOrNull(index)?.let { tab ->
                splitViewState.trackTabInteraction(currentPanelId, tab.id)
            }
        }
        onTabActivated?.invoke()
    }

    // Set up edge scroll handler for drag-and-drop
    // Each panel registers its own callback to avoid race conditions with multiple panels
    DisposableEffect(tabDragComponent, currentPanelId) {
        if (tabDragComponent != null && currentPanelId != null) {
            tabDragComponent.registerEdgeScrollCallback(currentPanelId) { direction ->
                edgeScrollScope.launch {
                    val layoutInfo = listState.layoutInfo
                    val visibleItems = layoutInfo.visibleItemsInfo

                    when (direction) {
                        ScrollDirection.BACKWARD -> {
                            // Scroll to previous item
                            val firstVisible = visibleItems.firstOrNull()?.index ?: 0
                            if (firstVisible > 0) {
                                listState.animateScrollToItem(firstVisible - 1)
                            }
                        }

                        ScrollDirection.FORWARD -> {
                            // Scroll to next item. Counted off the LIST, not off this panel's
                            // tabs: the two are the same number only when this panel is the
                            // whole list, and in a window bar it is one group of several.
                            val lastVisible = visibleItems.lastOrNull()?.index ?: 0
                            val totalItems = layoutInfo.totalItemsCount
                            if (lastVisible < totalItems - 1) {
                                listState.animateScrollToItem(lastVisible + 1)
                            }
                        }
                    }
                }
            }
        }
        onDispose {
            // Unregister this panel's callback to prevent memory leaks
            if (tabDragComponent != null && currentPanelId != null) {
                tabDragComponent.unregisterEdgeScrollCallback(currentPanelId)
            }
        }
    }

    // Sections exist only once something is pinned, and only in the vertical bar. A panel with
    // nothing pinned is a plain list with no headers - the common case, and labelling a lone
    // section "Open" would be noise. The top strip never sections: it has no room for a header,
    // and pinned tabs are already first in it by the invariant.
    val sectionsShown = vertical && pinnedCount > 0 && showSections

    // Lazy-list items that come BEFORE this group's first tab: the vertical bar's "New Tab" row,
    // and the "PINNED" header when there is one. The separator and the "OPEN" header ride inside
    // the first unpinned tab's item rather than being items of their own. Anything indexing the
    // lazy list rather than the tab model has to add this - the scroll-to-active effect below,
    // and a window bar working out where each group starts.
    val leadingListItems = (if (vertical) 1 else 0) + (if (sectionsShown) 1 else 0)

    // Auto-scroll to active tab when it changes
    LaunchedEffect(tabsState.value.activeIndex, autoScrollToActive) {
        val activeIndex = tabsState.value.activeIndex
        if (autoScrollToActive && activeIndex >= 0 && activeIndex < tabsState.value.tabs.size) {
            // Only scroll if the tab is not fully visible
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo

            // Check if the tab is fully visible (both left and right edges within viewport)
            val activeItem = visibleItems.find { it.index == activeIndex }
            val isFullyVisible =
                activeItem?.let { item ->
                    val itemStart = item.offset
                    val itemEnd = item.offset + item.size
                    val viewportStart = layoutInfo.viewportStartOffset
                    val viewportEnd = layoutInfo.viewportEndOffset

                    // Item is fully visible if both edges are within viewport
                    itemStart >= viewportStart && itemEnd <= viewportEnd
                } ?: false

            if (!isFullyVisible) {
                // Scroll to bring the tab fully into view
                // Lazy-list index, not tab index: a section header sits above the first tab.
                listState.scrollToItem(activeIndex + leadingListItems)
            }
        }
    }

    // Track drop target for reorder indicator
    val dropTarget = tabDragComponent?.dropTarget

    // Wording that follows the axis: "to the Right" is a lie in a column, and a menu that lies
    // about direction is worse than one that omits it. BossTerm renames its own Move Tab items
    // the same way, for the same reason.
    val closeAfterLabel = if (vertical) "Close Tabs Below" else "Close Tabs to the Right"
    val closeBeforeLabel = if (vertical) "Close Tabs Above" else "Close Tabs to the Left"
    val closeAfterIcon = if (vertical) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.ChevronRight
    val closeBeforeIcon = if (vertical) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.ChevronLeft

    // The tab rows, shared verbatim by both orientations. Only the container around them and two
    // axis-shaped details inside it - the reorder indicator and the inter-tab divider - differ.
    // Everything a tab DOES (select, close, drag, its entire context menu) is one body, because
    // none of it is about which way the bar runs.
    //
    // Deliberately not a @Composable: a lazy list's DSL body is not one either. The composable
    // work happens inside the `itemsIndexed` content lambda, which is exactly where it was
    // before this was hoisted out of the container.
    // The per-tab right-click menu, hoisted out of the tab row because the COLLAPSED bar needs
    // it too: a rail dot is still that tab, and collapsing the bar must take away labels, not
    // actions. Splitting it in two would have been two menus to keep in step.
    //
    // Plain lambda, not @Composable, and that is checked rather than assumed - nothing in here
    // calls remember or reads a state holder that needs one. It runs during composition on every
    // tab-bar recomposition, which is the constraint the NOTE below is about.
    val tabMenuItems: (Int, TabInfo) -> List<ContextMenuItem> = { index, config ->
        val totalTabs = tabsState.value.tabs.size
        buildList {
            // NOTE: Do NOT call trackTabInteraction/setActivePanel here.
            // buildList runs during composition (every tab-bar
            // recomposition, e.g. on every terminal output line), so
            // doing it here flips the active panel away from whichever
            // split the user is actually in — stealing focus back to the
            // output-producing panel. Panel activation on right-click is
            // already handled by the panel's pointerInput press handler;
            // left-click activation by the tab onClick above.

            // Pin / Unpin. First in the menu because it is the one action here that changes
            // where the tab lives rather than what happens to it, and because it is the only way
            // to discover pinning - the sidebar shows no Pinned section until something is in it.
            //
            // Offered in BOTH orientations even though only the vertical bar draws sections: the
            // ordering invariant is the model's, not the sidebar's, so pinning from the top strip
            // still moves the tab to the front and still survives restart.
            if (isPinned(index)) {
                add(
                    ContextMenuItem("Unpin Tab", Icons.Outlined.PushPin, onClick = { unpinTab(index) }),
                )
            } else {
                add(
                    ContextMenuItem("Pin Tab", Icons.Outlined.PushPin, onClick = { pinTab(index) }),
                )
            }
            add(ContextMenuItem(isDivider = true))

            // Split operations (if split state is available)
            if (splitViewState != null && currentPanelId != null) {
                add(
                    ContextMenuItem("Split Right", Icons.Outlined.ViewColumn, onClick = {
                        splitViewState.splitPanel(
                            panelId = currentPanelId,
                            orientation = ai.rever.boss.components.window_panel.SplitOrientation.VERTICAL,
                            tabToMove = config,
                        )
                    }),
                )
                add(
                    ContextMenuItem("Split Down", Icons.Outlined.Splitscreen, onClick = {
                        splitViewState.splitPanel(
                            panelId = currentPanelId,
                            orientation = ai.rever.boss.components.window_panel.SplitOrientation.HORIZONTAL,
                            tabToMove = config,
                        )
                    }),
                )
                add(ContextMenuItem(isDivider = true))
            }

            // Reveal the tab's backing file in the OS file manager (file-backed tabs).
            // Host tab types expose filePath directly. Dynamic plugin tabs (e.g. the
            // editor-tab plugin's EditorTabData) live in a plugin classloader we can't
            // reference by type, so fall back to reading a `filePath` getter reflectively
            // — the same duck-typing the editor-tab plugin uses for host tab types.
            // The reflected value is assumed absolute: revealInFileManager resolves via
            // File(path).absolutePath, so a relative path would resolve against the CWD.
            val revealPath =
                when (val tab = config) {
                    is EditorTabInfo -> {
                        tab.filePath
                    }

                    is JupyterTabInfo -> {
                        tab.filePath
                    }

                    else -> {
                        runCatching {
                            tab.javaClass.getMethod("getFilePath").invoke(tab) as? String
                        }.getOrNull()
                    }
                }?.takeIf { it.isNotBlank() }
            if (revealPath != null) {
                add(
                    ContextMenuItem(revealInFileManagerLabel(), Icons.Outlined.FolderOpen, onClick = {
                        revealInFileManager(revealPath)
                    }),
                )
                add(ContextMenuItem(isDivider = true))
            }

            // Bookmark current tab
            // Deliberate bare snapshot read: subscribes this scope to
            // recomposition on bookmark-collection changes.
            @Suppress("UNUSED_EXPRESSION")
            collections

            val tabConfig = convertTabInfoToTabConfig(config)
            val existingBookmark = BookmarkAPIAccess.findBookmarkForTab(tabConfig)

            if (existingBookmark != null) {
                // Tab is already bookmarked - show remove option WITH CONFIRMATION
                val (collectionId, bookmarkId) = existingBookmark
                add(
                    ContextMenuItem("Remove from Bookmarks", Icons.Filled.Star, onClick = {
                        bookmarkToRemove = Triple(collectionId, bookmarkId, config.title)
                        showRemoveBookmarkDialog = true
                    }),
                )
            } else {
                // Tab is not bookmarked - show add option
                add(
                    ContextMenuItem("Add to Bookmarks", Icons.Outlined.Star, onClick = {
                        tabToBookmark = config
                        showBookmarkDialog = true
                    }),
                )
            }

            // Favorite current workspace
            val currentWorkspace = workspaceManager.currentWorkspace.value
            if (currentWorkspace != null) {
                val isFavorited = BookmarkAPIAccess.isFavorite(currentWorkspace.id)
                add(
                    ContextMenuItem(
                        if (isFavorited) "Unfavorite Workspace" else "Favorite Workspace",
                        // The icon shows what the action DOES, matching the label: "Unfavorite"
                        // empties the star, "Favorite" fills it.
                        if (isFavorited) Icons.Outlined.StarBorder else Icons.Filled.Star,
                        onClick = {
                            if (isFavorited) {
                                BookmarkAPIAccess.removeFavoriteWorkspace(currentWorkspace.id)
                            } else {
                                BookmarkAPIAccess.addFavoriteWorkspace(currentWorkspace.id, currentWorkspace.name)
                            }
                        },
                    ),
                )
            }

            add(ContextMenuItem(isDivider = true))

            // Open in New Window (if multi-window is supported)
            if (ai.rever.boss.window.WindowOperations
                    .isMultiWindowSupported()
            ) {
                add(
                    ContextMenuItem("Open in New Window", Icons.AutoMirrored.Outlined.OpenInNew, onClick = {
                        ai.rever.boss.window.WindowOperations
                            .openTabInNewWindow(config)
                        // Remove tab from current window after opening in new window
                        removeTab(index)
                        // Request focus back to the main panel
                        focusRequester?.requestFocus()
                    }),
                )
                add(ContextMenuItem(isDivider = true))
            }

            // Close current tab
            add(
                ContextMenuItem("Close Tab", Icons.Outlined.Close, onClick = {
                    removeTab(index)
                    // Request focus back to the main panel
                    focusRequester?.requestFocus()
                }),
            )

            // Close other tabs (only show if there are other tabs)
            if (totalTabs > 1) {
                add(
                    ContextMenuItem("Close Other Tabs", Icons.Outlined.Clear, onClick = {
                        closeOtherTabs(index)
                        // Request focus back to the main panel
                        focusRequester?.requestFocus()
                    }),
                )
            }

            // Close tabs to the right (only show if there are tabs to the right)
            if (index < totalTabs - 1) {
                add(
                    ContextMenuItem(closeAfterLabel, closeAfterIcon, onClick = {
                        closeTabsToRight(index)
                        // Request focus back to the main panel
                        focusRequester?.requestFocus()
                    }),
                )
            }

            // Close tabs to the left (only show if there are tabs to the left)
            if (index > 0) {
                add(
                    ContextMenuItem(closeBeforeLabel, closeBeforeIcon, onClick = {
                        closeTabsToLeft(index)
                        // Request focus back to the main panel
                        focusRequester?.requestFocus()
                    }),
                )
            }
        }
    }

    // Namespace for this group's fixed item keys. The component's identity rather than the panel
    // id, because a bar can be built without one and two key-less groups would then collide.
    val itemKeyScope = currentPanelId ?: this.hashCode().toString()

    val tabItems: LazyListScope.(tabWidth: Dp?) -> Unit = { tabWidth ->
        // Sections exist only once something is pinned, and only in the vertical bar. A panel
        // with nothing pinned is a plain list with no headers - which is the common case, and
        // labelling a single section "Open" would be noise. The top strip never sections: it has
        // no room for a header, and pinned tabs are already first in it by the invariant.
        // "New Tab" sits ABOVE the tabs, directly under the bar's header - where Arc puts it,
        // under the space name and its rule and before the first tab. It is the one row whose
        // position should not depend on how many tabs there are, and at the top it never scrolls
        // away, which is what a bottom-anchored slot was reaching for the hard way.
        if (vertical) {
            // Keys carry the panel id because a window bar splices several groups into ONE list,
            // and a LazyColumn throws on a duplicate key. The tab items below are keyed
            // positionally by the list itself, so only these fixed ones need it.
            item(key = "boss-tab-new-row:$itemKeyScope") {
                NewTabRow(onClick = openNewTab)
            }
        }

        if (sectionsShown) {
            item(key = "boss-tab-section-pinned:$itemKeyScope") {
                SectionHeader(
                    label = "PINNED",
                    onAdd = openPinnedTab,
                    addHint = "New pinned tab",
                )
            }
        }

        // Render tab buttons as lazy items
        itemsIndexed(tabsState.value.tabs) { index, config ->
            val isSelected = index == tabsState.value.activeIndex

            // The separator and second header ride on the first UNPINNED tab rather than being
            // items of their own, so the tab indices itemsIndexed hands out stay the model's own
            // indices - which the drag bounds registration and every menu action key off.
            if (sectionsShown && index == pinnedCount) {
                SectionBreak(onAdd = openNewTab)
            }

            // Show reorder indicator before this tab if it's the drop target
            val showIndicatorBefore =
                dropTarget is TabDropTarget.Reorder &&
                    dropTarget.panelId == currentPanelId &&
                    dropTarget.targetIndex == index

            // Deliberately AFTER the section break: an indicator drawn below the separator is
            // exactly what dropping there does, which is land the tab unpinned (see
            // pinnedCountAfterMove). Dropping above the line renders its indicator in an earlier
            // item, above the separator, and pins.
            if (showIndicatorBefore) {
                ReorderIndicator(vertical = vertical)
            }

            BossTabButtonWithFavicon(
                config = config,
                isSelected = isSelected,
                // Focused means "a click here lands where the user is working". With one bar per
                // panel that is always true; with one bar for several panes only the active
                // pane's group can say it.
                isFocused = isActiveGroup,
                // A vertical tab's width is the bar's, so tabWidth is not consulted there at all.
                tabWidth = if (!vertical && shrinkTabsToFit) tabWidth else null,
                vertical = vertical,
                tabHeight = VERTICAL_TAB_HEIGHT,
                onClick = { activateTab(index) },
                onClose = {
                    removeTab(index)
                    // Request focus back to the main panel after closing tab
                    // This ensures keyboard shortcuts continue to work
                    focusRequester?.requestFocus()
                },
                tabDragComponent = tabDragComponent,
                panelId = currentPanelId,
                tabIndex = index,
                onDragEnd = { result ->
                    // endDrag() already called in BossTabButton, just handle result
                    result?.let { onTabDropResult(it) }
                },
                contextMenuItems = tabMenuItems(index, config),
                onContextMenuVisibilityChange = { open ->
                    openMenuCount = (openMenuCount + if (open) 1 else -1).coerceAtLeast(0)
                },
            )

            // Divider after tab (only if not the last tab). It runs ACROSS the tab order, so it
            // is a vertical line between two side-by-side tabs and a horizontal one between two
            // stacked ones.
            //
            // Horizontal padding is shared with BossTabBar's width budget — change it there, not
            // here. The vertical bar has no such budget, which is why its divider is free to be
            // a plain full-width line.
            if (index < tabsState.value.tabs.size - 1) {
                if (vertical) {
                    Divider(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        color = BossTheme.colors.line,
                    )
                } else {
                    VDivider(modifier = Modifier.padding(vertical = 8.dp, horizontal = INTER_TAB_DIVIDER_PADDING))
                }
            }

            // Show reorder indicator after the last tab if dropping at the end
            val isLastTab = index == tabsState.value.tabs.size - 1
            val showIndicatorAfter =
                isLastTab &&
                    dropTarget is TabDropTarget.Reorder &&
                    dropTarget.panelId == currentPanelId &&
                    dropTarget.targetIndex == tabsState.value.tabs.size

            if (showIndicatorAfter) {
                ReorderIndicator(vertical = vertical)
            }
        }

        // Legacy FIXED mode keeps its historical "+" placement:
        // inside the row, hugging the last tab, while everything
        // fits. This reintroduces the known one-frame glitch when
        // isScrollable flips mid-drag, but that trade-off is what
        // FIXED users always had — FIXED means legacy, glitch
        // included.
        // Legacy FIXED mode keeps the horizontal strip's historical "+" placement: inside the
        // row, hugging the last tab, while everything fits.
        if (!vertical && !shrinkTabsToFit && !isScrollable) {
            item {
                NewTabButton(onClick = openNewTab)
            }
        }
    }

    // Right-click on the bar's own empty chrome (as opposed to on a tab). Hoisted out of the
    // container below because both orientations offer it and neither owns it - and because the
    // Tab Bar Position submenu it now carries is the fastest way back for someone who flipped
    // the bar somewhere it does not suit them.
    val barContextMenuItems: List<ContextMenuItem> =
        buildList {
            add(
                ContextMenuItem("New Tab", Icons.Default.Add, onClick = openNewTab),
            )

            add(ContextMenuItem(isDivider = true))

            // Tab bar position. A label-only submenu, so it stays isNativeRepresentable() and
            // survives the native-NSMenu path on macOS; the checkmark is spelled as a trailing
            // dot in the label because a native menu item has nowhere else to put one.
            add(
                ContextMenuItem(
                    "Tab Bar Position",
                    Icons.Outlined.ViewColumn,
                    subMenu =
                        TabBarPosition.entries.map { position ->
                            ContextMenuItem(
                                if (position == tabBarPosition) "${position.displayName} ✓" else position.displayName,
                                onClick = {
                                    barSettingsScope.launch {
                                        WindowAppearanceSettingsManager.updateSettings(
                                            WindowAppearanceSettingsManager.currentSettings.value
                                                .copy(tabBarPosition = position),
                                        )
                                    }
                                },
                            )
                        },
                ),
            )

            add(ContextMenuItem(isDivider = true))

            // Favorite current workspace
            val currentWorkspace = workspaceManager.currentWorkspace.value
            if (currentWorkspace != null) {
                val isFavorited = BookmarkAPIAccess.isFavorite(currentWorkspace.id)
                add(
                    ContextMenuItem(
                        if (isFavorited) "Unfavorite Workspace" else "Favorite Workspace",
                        // The icon shows what the action DOES, matching the label: "Unfavorite"
                        // empties the star, "Favorite" fills it.
                        if (isFavorited) Icons.Outlined.StarBorder else Icons.Filled.Star,
                        onClick = {
                            if (isFavorited) {
                                BookmarkAPIAccess.removeFavoriteWorkspace(currentWorkspace.id)
                            } else {
                                BookmarkAPIAccess.addFavoriteWorkspace(currentWorkspace.id, currentWorkspace.name)
                            }
                        },
                    ),
                )
            }
        }

    return TabBarState(
        items = tabItems,
        barContextMenuItems = barContextMenuItems,
        openNewTab = openNewTab,
        openPinnedTab = openPinnedTab,
        tabs = tabsState.value.tabs,
        activeIndex = tabsState.value.activeIndex,
        pinnedCount = pinnedCount,
        tabMenuItems = tabMenuItems,
        activateTab = activateTab,
        favorites = favorites,
        removeFavorite = { bookmark ->
            collections
                .firstOrNull { collection -> collection.bookmarks.any { it.id == bookmark.id } }
                ?.let { BookmarkAPIAccess.removeBookmark(it.id, bookmark.id) }
        },
        openFavorite = openBookmark,
        bookmarksInstalled = bookmarksInstalled,
        bookmarksApiReachable = bookmarksApiReachable,
        shrinkTabsToFit = shrinkTabsToFit,
        dialogs = {
            // New Tab Dialog
            if (showNewTabDialog) {
                NewTabDialog(
                    onDismiss = {
                        showNewTabDialog = false
                        selectedTabType = null
                    },
                    tabRegistry = tabRegistry,
                    initialTabType = selectedTabType,
                    onCreateTab = { type, path ->
                        when (type) {
                            TabType.URL -> {
                                val timestamp = Clock.System.now().toEpochMilliseconds()
                                val fluckTab =
                                    ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo(
                                        id = "fluck-$timestamp",
                                        typeId = FluckTabType.typeId,
                                        _title = "Loading...",
                                        url = path,
                                    )
                                openCreatedTab(fluckTab)
                            }

                            TabType.FILE -> {
                                val timestamp = Clock.System.now().toEpochMilliseconds()
                                val fileName = path.extractFileName().ifEmpty { "untitled.txt" }
                                val fileIconInfo = FileIcons.forFile(fileName)
                                val editorTab =
                                    EditorTabInfo(
                                        id = "editor-$timestamp",
                                        title = fileName,
                                        typeId = CodeEditorTabType.typeId,
                                        icon = fileIconInfo.icon,
                                        tabIcon =
                                            ai.rever.boss.plugin.api.TabIcon
                                                .Vector(fileIconInfo.icon, fileIconInfo.color),
                                        filePath = path,
                                    )
                                openCreatedTab(editorTab)
                            }

                            TabType.TERMINAL -> {
                                val timestamp = Clock.System.now().toEpochMilliseconds()
                                // Get current project path for terminal working directory (per-window)
                                val projectPath = windowProjectState?.selectedProject?.value?.path ?: ""
                                val terminalTab =
                                    TerminalTabInfo(
                                        id = "terminal-$timestamp",
                                        typeId = ai.rever.boss.plugin.tab.terminal.TerminalTabType.typeId,
                                        title = "Terminal",
                                        icon = ai.rever.boss.plugin.tab.terminal.TerminalTabType.icon,
                                        initialCommand = path.ifBlank { null },
                                        workingDirectory = DefaultWorkingDirectory.resolve(projectPath),
                                    )
                                openCreatedTab(terminalTab)
                            }

                            TabType.JUPYTER -> {
                                val jupyterTab = JupyterTabInfo.createUntitled(path)
                                openCreatedTab(jupyterTab)
                            }
                        }
                    },
                    // Plugin tab types build their own TabInfo; open it the same way.
                    onCreateTabInfo = { tabInfo ->
                        openCreatedTab(tabInfo)
                    },
                    projectPath = windowProjectState?.selectedProject?.value?.path,
                )
            }

            // Bookmark dialog (gracefully handles missing bookmarks plugin)
            if (showBookmarkDialog && tabToBookmark != null) {
                val dialogCollections = rememberBookmarkCollections()
                val workspaces by workspaceManager.workspaces.collectAsState()
                BookmarkDialog(
                    tabTitle = tabToBookmark!!.title,
                    collections = dialogCollections,
                    workspaces = workspaces,
                    onDismiss = {
                        showBookmarkDialog = false
                        tabToBookmark = null
                    },
                    onConfirm = { collectionIds, workspacePanelMap ->
                        val tabConfig = convertTabInfoToTabConfig(tabToBookmark!!)
                        val workspace = workspaceManager.currentWorkspace.value

                        // Convert workspacePanelMap to list of WorkspacePanelTarget
                        val targetWorkspaces =
                            workspacePanelMap.map { (workspaceName, panelId) ->
                                WorkspacePanelTarget(workspaceName = workspaceName, panelId = panelId)
                            }

                        // Create bookmark for each selected collection
                        collectionIds.forEach { collectionId ->
                            val bookmark =
                                Bookmark(
                                    tabConfig = tabConfig,
                                    workspaceName = workspace?.name ?: "Unknown",
                                    targetWorkspaces = targetWorkspaces,
                                )
                            val collection = dialogCollections.find { it.id == collectionId }
                            if (collection != null) {
                                BookmarkAPIAccess.addBookmark(collection.name, bookmark)
                            }
                        }

                        showBookmarkDialog = false
                        tabToBookmark = null
                    },
                )
            }

            // Remove bookmark confirmation dialog
            if (showRemoveBookmarkDialog && bookmarkToRemove != null) {
                RemoveBookmarkConfirmationDialog(
                    bookmarkTitle = bookmarkToRemove!!.third,
                    onDismiss = {
                        showRemoveBookmarkDialog = false
                        bookmarkToRemove = null
                    },
                    onConfirm = {
                        bookmarkToRemove?.let { (collectionId, bookmarkId, _) ->
                            BookmarkAPIAccess.removeBookmark(collectionId, bookmarkId)
                        }
                        showRemoveBookmarkDialog = false
                        bookmarkToRemove = null
                    },
                )
            }
        },
        leadingListItems = leadingListItems,
        listState = listState,
    )
}

/**
 * The tab strip across the top of a panel.
 *
 * Top position only. The LEFT position is drawn once for the whole window by
 * [ai.rever.boss.components.window_panel.components.main_window_panels.WindowVerticalTabBar],
 * which lists every panel's tabs as its own group - a split there adds a group, not a second bar.
 */
@Composable
fun BossTabsComponent.BossMainTabBar(
    splitViewState: ai.rever.boss.components.window_panel.SplitViewState? = null,
    currentPanelId: String? = null,
    focusRequester: FocusRequester? = null,
    tabDragComponent: TabDraggableComponent? = null,
    onTabDropResult: (TabDropResult) -> Unit = {},
) {
    val state =
        rememberTabBarState(
            splitViewState = splitViewState,
            currentPanelId = currentPanelId,
            focusRequester = focusRequester,
            tabDragComponent = tabDragComponent,
            onTabDropResult = onTabDropResult,
            vertical = false,
        )

    // Legacy FIXED mode only: whether the row scrolls decides where its "+" sits. Derived here
    // rather than on the state because it is a property of THIS container's layout, not of the
    // panel.
    val isScrollable by remember(state.listState) {
        derivedStateOf { state.listState.canScrollForward || state.listState.canScrollBackward }
    }

    HorizontalBar(
        height = BossChrome.dimens.tabBarHeight,
        backgroundColor = BossTheme.colors.panel,
    ) {
        HorizontalBarRow(
            modifier =
                Modifier.onGloballyPositioned { coordinates ->
                    // Register tab bar bounds for drag detection
                    if (currentPanelId != null && tabDragComponent != null) {
                        val bounds = coordinates.boundsInWindow()
                        tabDragComponent.registerTabBarBounds(currentPanelId, bounds, vertical = false)
                    }
                },
        ) {
            BossLeftTabBar(
                state.listState,
                tabCount = state.tabs.size,
                // Plus button outside the LazyRow but still inside the strip,
                // sitting directly after the last tab: always in
                // SHRINK_TO_FIT (immune to the isScrollable race), and in
                // FIXED mode once the row scrolls (so the button can't
                // scroll away). Once the tabs fill the strip this lands
                // flush right, same as before.
                //
                // Both gaps belong to the slot, not to the strip Row — see the
                // reserve rule in BossLeftTabBar's KDoc. NEW_TAB_BUTTON_GAP on
                // the end side plus the strip's own 8.dp inset reproduces the
                // 12.dp right margin the pinned-right button used to have.
                trailingReserve = if (state.shrinkTabsToFit || isScrollable) NEW_TAB_SLOT_WIDTH else 0.dp,
                trailing = {
                    if (state.shrinkTabsToFit || isScrollable) {
                        NewTabButton(
                            modifier = Modifier.padding(horizontal = NEW_TAB_BUTTON_GAP),
                            onClick = state.openNewTab,
                        )
                    }
                },
            ) { tabWidth ->
                state.items(this, tabWidth)
            }

            Spacer(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .contextMenu(items = state.barContextMenuItems),
            )
        }
    }

    state.dialogs()
}

/**
 * The focus requester a panel attaches, shared with whoever else needs to focus that panel.
 *
 * A window-level tab bar is outside every panel and gives focus back to one after closing a tab
 * from a menu, so the instance cannot be a local `remember` here any more. A panel with no id to
 * share one under - the unmanaged case - keeps its own.
 */
@Composable
private fun rememberPanelFocusRequester(
    splitViewState: ai.rever.boss.components.window_panel.SplitViewState?,
    currentPanelId: String?,
): FocusRequester {
    val own = remember { FocusRequester() }
    return if (currentPanelId != null && splitViewState != null) {
        splitViewState.focusRequesterFor(currentPanelId)
    } else {
        own
    }
}

@Composable
fun BossTabsComponent.BossMainPanel(
    modifier: Modifier = Modifier,
    splitViewState: ai.rever.boss.components.window_panel.SplitViewState? = null,
    currentPanelId: String? = null,
    tabDragComponent: TabDraggableComponent? = null,
    onTabDropResult: (TabDropResult) -> Unit = {},
    /**
     * Whether this panel draws its own tab bar.
     *
     * False in LEFT position, where `SplitViewPanel` draws one bar for the whole window and this
     * panel's tabs are a group inside it. The panel keeps everything else it owns - its border
     * ring, its focus handling, `LocalIsPanelActive` - because none of that was ever the bar's.
     */
    showTabBar: Boolean = true,
) {
    val focusRequester = rememberPanelFocusRequester(splitViewState, currentPanelId)
    val isFocused = remember { mutableStateOf(false) }

    // Track the active panel state to force recomposition
    val activePanelId by splitViewState?.activePanelIdState ?: remember { mutableStateOf("") }
    val isActivePanel = activePanelId == currentPanelId
    val panelBorder = BossChrome.dimens.panelBorderThickness

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .onFocusChanged { focusState ->
                    isFocused.value = focusState.isFocused || focusState.hasFocus
                    if ((focusState.isFocused || focusState.hasFocus) && currentPanelId != null) {
                        splitViewState?.setActivePanel(currentPanelId)
                    }
                }.focusable()
                // Detect pointer presses to mark this panel active even when child content
                // (JxBrowser, native AWT components) doesn't propagate Compose focus events
                // upward. PointerEventPass.Initial observes without consuming, so children
                // still receive the press unchanged.
                .pointerInput(currentPanelId, splitViewState) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press && currentPanelId != null) {
                                splitViewState?.setActivePanel(currentPanelId)
                            }
                        }
                    }
                }
                // Removed .clickable() - it was stealing focus from child components (terminals)
                // Panel activation is handled by .onFocusChanged() above and .pointerInput() above
                //
                // The active-panel border RESERVES its own ring rather than being painted over the
                // content, and the ring exists whether or not this panel is active.
                //
                // Both halves matter. Painting over content is fine for Compose-rendered children
                // (a terminal, an editor) because Compose draws the border on top of them. It is
                // NOT fine for a child that is a foreign native surface: under
                // HARDWARE_ACCELERATED the browser is Chromium's own native window composited
                // ABOVE the Compose scene, so Compose cannot draw over it or clip it, and the page
                // simply covered this border along every edge it touched. Reported from a live
                // macOS build as the selected panel's border being cut off across the browser.
                // Reserving the ring is the fix that works for any such child, present or future,
                // rather than teaching each one to inset itself by a number it should not know.
                //
                // Unconditional because a ring that appeared only when active would resize the
                // content on every activation — and for a browser that means a reflow of the page
                // each time the user clicks into the panel.
                //
                // The ring is FILLED with the panel surface, and that is not optional. Reserving
                // space without painting it leaves the parent showing through: the split-view Box
                // behind this panel paints no background at all, so the bare ring rendered as a
                // white outline around every panel regardless of theme. Filling it with
                // BossTheme.colors.panel — the same token the tab bar above uses — makes an
                // inactive panel look exactly as it did before the ring existed, and gives the
                // active border something themed to sit on.
                .background(BossTheme.colors.panel)
                // Border width and content inset must agree exactly, which is why both read the one
                // value; see ChromeDimens.panelBorderThickness for what goes wrong if they drift.
                // It is also 4dp of every panel's height and width, so ChromeMetrics charges for it.
                .border(
                    panelBorder,
                    if (isActivePanel) MaterialTheme.colors.primary.copy(alpha = 0.5f) else Color.Transparent,
                ).padding(panelBorder),
    ) {
        // Expose `isActivePanel` to nested plugin composables via a
        // CompositionLocal. Plugins that embed widgets sensitive to
        // host focus transitions (e.g. the BossTerm-backed terminal-tab
        // plugin) read this and forward it into their widget so the
        // widget can re-issue its internal focus requester when the
        // surrounding panel regains user attention.
        // LocalInMainWindowPanel rides alongside because LocalIsPanelActive cannot answer the
        // question a shortcut needs. Its default is `true`, so a surface rendered OUTSIDE a managed
        // panel - a sidebar slot, a dialog, a test host - reads as active too, and a window-scoped
        // shortcut broadcast to every collector then has no way to prefer the real one.
        //
        // A lambda taking the modifier because the content is weighted by whichever of the two
        // containers below is in play, and RowScope.weight and ColumnScope.weight are different
        // receivers. The tree it produces is identical either way, which is the point: switching
        // the bar's edge must not rebuild the panel's content.
        val panelContent: @Composable (Modifier) -> Unit = { contentModifier ->
            CompositionLocalProvider(
                LocalIsPanelActive provides isActivePanel,
                LocalInMainWindowPanel provides true,
            ) {
                BossMainPanelContent(modifier = contentModifier)
            }
        }

        if (!showTabBar) {
            // The window draws one bar for every panel, and this is that position: LEFT. Nothing
            // else is skipped along with it - the border ring, the focus wiring and
            // LocalIsPanelActive above are all this panel's own, and the content below is the
            // same tree either way.
            panelContent(Modifier.fillMaxSize())
            return@Box
        }

        Column(modifier = Modifier.fillMaxSize()) {
            BossMainTabBar(
                splitViewState = splitViewState,
                currentPanelId = currentPanelId,
                focusRequester = focusRequester,
                tabDragComponent = tabDragComponent,
                onTabDropResult = onTabDropResult,
            )
            Divider(color = BossTheme.colors.line)
            panelContent(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/**
 * Turn a hover reveal into the real bar, or null when there is nothing to pin.
 *
 * On a wide panel pinning clears the collapse preference, so the drawer becomes the bar and
 * survives both the pointer leaving and the next launch. On a narrow one the rail is forced by
 * width and no setting can undo that, so the best available "stay" is the chevron-opened drawer,
 * which outlives hover until it is dismissed.
 *
 * Null unless this drawer is a transient reveal: a chevron-opened drawer is already as pinned as
 * it can get, and offering to pin it again would do nothing.
 */
@Composable
fun rememberPinDrawerAction(
    reveal: TabBarRevealState,
    bar: TabBarLayout,
): (() -> Unit)? {
    val scope = rememberCoroutineScope()
    if (!reveal.isTransientReveal) return null
    return {
        if (bar.narrow) {
            reveal.openDrawer()
        } else {
            scope.launch {
                WindowAppearanceSettingsManager.updateSettings(
                    WindowAppearanceSettingsManager.currentSettings.value
                        .copy(tabBarCollapsed = false),
                )
            }
        }
    }
}

/**
 * Collapse the vertical tab bar to its rail, or give it back.
 *
 * On a wide window that is the `tabBarCollapsed` preference, which persists. On one already too
 * narrow for a full bar the preference can change nothing, so the chevron opens the hover drawer
 * instead - the only shape a full bar can take at that width.
 */
@Composable
fun rememberToggleCollapseAction(
    bar: TabBarLayout,
    reveal: TabBarRevealState,
): () -> Unit {
    val scope = rememberCoroutineScope()
    return {
        if (bar.narrow) {
            reveal.openDrawer()
        } else {
            scope.launch {
                val current = WindowAppearanceSettingsManager.currentSettings.value
                WindowAppearanceSettingsManager.updateSettings(
                    current.copy(tabBarCollapsed = !current.tabBarCollapsed),
                )
            }
        }
    }
}

/**
 * Main UI composable that displays the root component
 */
@Composable
fun BossTabsComponent.BossMainPanelContent(modifier: Modifier) {
    // Subscribe to tab state changes to trigger recomposition
    val tabsState = tabsState.subscribeAsState()

    // State for new tab dialog (needed for EmptyContent callbacks)
    var showNewTabDialog by remember { mutableStateOf(false) }
    var selectedTabType by remember { mutableStateOf<TabType?>(null) }

    // Coroutine scope for async operations
    val scope = rememberCoroutineScope()

    // Per-window project state for Dashboard (required for multi-window support)
    val windowProjectState = LocalWindowProjectState.current
    val selectedProject by windowProjectState?.selectedProject?.collectAsState()
        ?: remember { mutableStateOf(Project("No Project", "", 0L)) }

    Box(modifier = modifier) {
        val activeTab = tabsState.value.activeTab
        val activeComponent = getActiveComponent()

        // Only render the active tab - hidden tabs would still receive input
        // Terminal state is preserved by TerminalStateRegistry (keyed by tab ID)
        if (activeTab != null && activeComponent != null) {
            val sandbox = TabSandboxRegistry.getSandbox(activeTab.typeId)

            // Register pluginId → (tabId, closeAction) BEFORE entering PluginErrorBoundary.
            // This runs during composition (via remember), so it's set before content()
            // is invoked and before any crash can occur. The closeAction captures a direct
            // reference to this BossTabsComponent, avoiding dependency on SplitViewStateRegistry
            // (which may not be populated yet during the first composition frame).
            if (sandbox != null) {
                val tabIdToClose = activeTab.id
                val pluginIdToRegister = sandbox.pluginId
                remember(tabIdToClose, pluginIdToRegister) {
                    PluginCrashRegistry.registerActiveTab(
                        pluginIdToRegister,
                        tabIdToClose,
                        closeAction = { this@BossMainPanelContent.removeTabById(tabIdToClose) },
                    )
                }
                DisposableEffect(tabIdToClose, pluginIdToRegister) {
                    onDispose {
                        PluginCrashRegistry.unregisterActiveTab(pluginIdToRegister, tabIdToClose)
                    }
                }
            }

            val pluginLogger = if (sandbox != null) remember { BossLogger.forComponent("BossMainPanelContent") } else null

            key(activeTab.id) {
                if (sandbox != null) {
                    PluginErrorBoundary(
                        pluginId = sandbox.pluginId,
                        sandbox = sandbox,
                        onRestart = {
                            // Through the manager, never sandbox.restart()
                            // directly - see SidePanel's copy of this.
                            scope.launch {
                                val restarted = DynamicPluginManager.restartOwning(sandbox)
                                if (!restarted) {
                                    pluginLogger?.error(
                                        LogCategory.UI,
                                        "Failed to restart plugin",
                                        mapOf(
                                            "pluginId" to sandbox.pluginId,
                                        ),
                                    )
                                    StatusMessageManager.showMessage(
                                        "Failed to restart plugin: ${sandbox.pluginId}",
                                        durationMs = 5000,
                                    )
                                }
                            }
                        },
                    ) {
                        activeComponent.Content()
                    }
                } else {
                    // No sandbox - render directly (built-in tabs or backwards compatibility)
                    activeComponent.Content()
                }
            }
        } else {
            // Show the home screen when this panel has no tabs.
            //
            // No callbacks: HomeScreen emits on DashboardEventBus, whose handlers live in
            // BossAppEventBusEffects. The block that used to be here passed twelve lambdas,
            // which is what let the other mount point (DashboardContentProviderImpl, for a
            // browser showing about:blank) supply eleven empty ones and leave most of the
            // screen inert. Two of the twelve - onActivatePlugin and onNewTerminal - were
            // never invoked by the screen at all.
            HomeScreen()
        }
    }

    // New Tab Dialog (for EmptyContent interactions)
    if (showNewTabDialog) {
        NewTabDialog(
            onDismiss = {
                showNewTabDialog = false
                selectedTabType = null
            },
            tabRegistry = tabRegistry,
            initialTabType = selectedTabType,
            onCreateTab = { type, path ->
                when (type) {
                    TabType.URL -> {
                        val timestamp = Clock.System.now().toEpochMilliseconds()
                        val fluckTab =
                            ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo(
                                id = "fluck-$timestamp",
                                typeId = FluckTabType.typeId,
                                _title = "Loading...",
                                url = path,
                            )
                        val tabIndex = addTab(fluckTab)
                        if (tabIndex >= 0) {
                            selectTab(tabIndex)
                        }
                    }

                    TabType.FILE -> {
                        val timestamp = Clock.System.now().toEpochMilliseconds()
                        val fileName = path.extractFileName().ifEmpty { "untitled.txt" }
                        val fileIconInfo = FileIcons.forFile(fileName)
                        val editorTab =
                            EditorTabInfo(
                                id = "editor-$timestamp",
                                title = fileName,
                                typeId = CodeEditorTabType.typeId,
                                icon = fileIconInfo.icon,
                                tabIcon =
                                    ai.rever.boss.plugin.api.TabIcon
                                        .Vector(fileIconInfo.icon, fileIconInfo.color),
                                filePath = path,
                            )
                        val tabIndex = addTab(editorTab)
                        if (tabIndex >= 0) {
                            selectTab(tabIndex)
                        }
                    }

                    TabType.TERMINAL -> {
                        val timestamp = Clock.System.now().toEpochMilliseconds()
                        // Get current project path for terminal working directory (per-window)
                        val projectPath = selectedProject.path
                        val terminalTab =
                            TerminalTabInfo(
                                id = "terminal-$timestamp",
                                typeId = ai.rever.boss.plugin.tab.terminal.TerminalTabType.typeId,
                                title = "Terminal",
                                icon = ai.rever.boss.plugin.tab.terminal.TerminalTabType.icon,
                                initialCommand = path.ifBlank { null },
                                workingDirectory = DefaultWorkingDirectory.resolve(projectPath),
                            )
                        val tabIndex = addTab(terminalTab)
                        if (tabIndex >= 0) {
                            selectTab(tabIndex)
                        }
                    }

                    TabType.JUPYTER -> {
                        val jupyterTab = JupyterTabInfo.createUntitled(path)
                        val tabIndex = addTab(jupyterTab)
                        if (tabIndex >= 0) {
                            selectTab(tabIndex)
                        }
                    }
                }
            },
            // Plugin tab types build their own TabInfo; open it the same way.
            onCreateTabInfo = { tabInfo ->
                val tabIndex = addTab(tabInfo)
                if (tabIndex >= 0) {
                    selectTab(tabIndex)
                }
            },
            projectPath = selectedProject.path.ifEmpty { null },
        )
    }
}

val createBossAppContext get() = DefaultComponentContext(LifecycleRegistry())

/**
 * Snapshot of an in-progress MRU tab cycle, used to render the tab-switcher overlay:
 * the open tabs in cycle order plus the index of the currently-highlighted candidate.
 */
data class TabCycleOverlayData(
    val tabs: List<TabInfo>,
    val highlightedIndex: Int,
)

/**
 * Root component for the BOSS app using Decompose for navigation
 *
 * @param windowId The window ID for per-window terminal isolation (Issue #498)
 */
class BossTabsComponent(
    componentContext: ComponentContext,
    val tabRegistry: TabRegistry,
    val windowId: String,
) : ComponentContext by componentContext {
    // Unique ID for this component (used for TabUpdateRegistry)
    private val componentId = "${windowId}_${System.identityHashCode(this)}"

    private val tabComponents = mutableStateMapOf<String, TabComponentWithUI>()

    // Per-tab lifecycle registries. Each tab component gets its own ComponentContext whose
    // lifecycle is destroyed when the tab is closed, so components that clean up in
    // lifecycle.onDestroy (e.g. the fluck-browser plugin disposing its JxBrowser handle)
    // actually get destroyed. Previously all tab components shared this panel's context,
    // whose LifecycleRegistry is never destroyed — closing or moving a browser tab leaked
    // a live Chromium process (audio kept playing in the background).
    //
    // Plain map on purpose (tabComponents is a state map only because composition reads
    // it): all tab mutations happen on the UI thread, matching Essenty's lifecycle
    // threading expectations.
    private val tabLifecycles = mutableMapOf<String, LifecycleRegistry>()
    private val tabsNavigation = TabsNavigation<TabInfo>()

    // Expose tab state for UI
    val tabsState: Value<TabsNavigation.TabsState<TabInfo>> = tabsNavigation.state

    // How many of this panel's tabs are pinned. Pinned tabs are ALWAYS the first N - see
    // TabPinning.kt for why that invariant is the design rather than a Set<String>.
    private val _pinnedCount = mutableStateOf(0)

    /** Number of leading tabs that are pinned. Tabs `0 until pinnedCount` are the pinned ones. */
    val pinnedCount: Int get() = _pinnedCount.value

    /** Whether the tab at [index] is pinned. */
    fun isPinned(index: Int): Boolean = index < pinnedCount

    /**
     * Pin the tab at [index], moving it to the end of the pinned block.
     *
     * Not routed through [moveTab]: that one INFERS pinned-ness from where a drag landed, which is
     * right for a drag and wrong here, where the user has said which they want.
     */
    fun pinTab(index: Int) {
        val tabs = tabsState.value.tabs
        if (index !in tabs.indices || isPinned(index)) return
        tabsNavigation.moveTab(index, pinnedCount)
        _pinnedCount.value = pinnedCount + 1
    }

    /** Unpin the tab at [index], moving it to the head of the unpinned block. */
    fun unpinTab(index: Int) {
        val tabs = tabsState.value.tabs
        if (index !in tabs.indices || !isPinned(index)) return
        // To the LAST pinned slot, so that decrementing the count leaves it first among the
        // unpinned rather than buried at the bottom of a long list.
        tabsNavigation.moveTab(index, pinnedCount - 1)
        _pinnedCount.value = pinnedCount - 1
    }

    /**
     * Restore the pinned count for this panel, clamped to the tabs that actually came back.
     *
     * One call per panel is the whole of restore, which is what the "pinned tabs are the first N"
     * invariant buys - see TabPinning.kt.
     */
    fun setPinnedCount(count: Int) {
        _pinnedCount.value = clampPinnedCount(count, tabsState.value.tabs.size)
    }

    // --- Ctrl+Tab tab switching state ---
    // Most-recently-used order of tab ids (most recent first), used by MRU switch mode.
    private val mruTabIds = mutableListOf<String>()

    // Snapshot of the cycle order while an MRU cycle is in progress (hold-modifier,
    // tap-Tab, commit on release); null when no cycle is active.
    private var tabCycleOrder: List<String>? = null
    private var tabCyclePointer: Int = 0

    // Listener for tab type unregistration
    private val unregisterListener: (ai.rever.boss.plugin.api.TabTypeId) -> Unit = { typeId ->
        bossMainWindowPanelLogger.info(
            LogCategory.UI,
            "Received unregister notification",
            mapOf(
                "typeId" to typeId.typeId,
                "pluginId" to typeId.pluginId,
                "windowId" to windowId,
            ),
        )
        closeTabsByType(typeId)
    }

    /**
     * Factory for creating TabUpdateProviders for dynamic plugins.
     *
     * This allows tab-based plugins to update their tab's title, icon, and other
     * metadata displayed in the tab bar without needing direct access to the
     * BossTabsComponent.
     */
    val tabUpdateProviderFactory: TabUpdateProviderFactory =
        object : TabUpdateProviderFactory {
            override fun createProvider(
                tabId: String,
                typeId: TabTypeId,
            ): TabUpdateProvider? {
                // Find the tab index
                val tabs = tabsState.value.tabs
                val tabIndex = tabs.indexOfFirst { it.id == tabId }
                if (tabIndex < 0) {
                    bossMainWindowPanelLogger.warn(
                        LogCategory.UI,
                        "Cannot create TabUpdateProvider - tab not found",
                        mapOf(
                            "tabId" to tabId,
                            "typeId" to typeId.typeId,
                        ),
                    )
                    return null
                }

                return BossTabUpdateProvider(
                    tabId = tabId,
                    typeId = typeId,
                    bossTabsComponent = this@BossTabsComponent,
                )
            }
        }

    /**
     * Implementation of TabUpdateProvider that updates tabs in BossTabsComponent.
     */
    private inner class BossTabUpdateProvider(
        override val tabId: String,
        private val typeId: TabTypeId,
        private val bossTabsComponent: BossTabsComponent,
    ) : TabUpdateProvider {
        override fun updateTitle(title: String) {
            // A blank title never improves the tab chip — e.g. about:blank (the
            // dashboard/home state) fires TitleChanged with an empty title on
            // back-navigation, which used to blank the tab. Keep the last
            // meaningful title instead.
            if (title.isBlank()) return

            val tabs = bossTabsComponent.tabsState.value.tabs
            val tabIndex = tabs.indexOfFirst { it.id == tabId }
            if (tabIndex < 0) return

            val currentTab = tabs[tabIndex]

            // Update based on tab type - built-in FluckTabInfo or generic via reflection
            val updatedTab =
                when (currentTab) {
                    is FluckTabInfo -> {
                        currentTab.updateTitle(title)
                    }

                    else -> {
                        // Try reflection for dynamic plugin tab types that have updateTitle(String)
                        try {
                            val updateMethod =
                                currentTab::class.members.find {
                                    it.name == "updateTitle" && it.parameters.size == 2 // receiver + title param
                                }
                            val result = updateMethod?.call(currentTab, title) as? TabInfo
                            if (result != null) {
                                result
                            } else {
                                bossMainWindowPanelLogger.debug(
                                    LogCategory.UI,
                                    "Cannot update title - no updateTitle method",
                                    mapOf(
                                        "tabId" to tabId,
                                        "tabType" to currentTab::class.simpleName,
                                    ),
                                )
                                return
                            }
                        } catch (e: Exception) {
                            bossMainWindowPanelLogger.debug(
                                LogCategory.UI,
                                "Cannot update title via reflection",
                                mapOf(
                                    "tabId" to tabId,
                                    "tabType" to currentTab::class.simpleName,
                                    "error" to (e.message ?: "unknown"),
                                ),
                            )
                            return
                        }
                    }
                }

            bossTabsComponent.updateTab(tabIndex, updatedTab)
        }

        override fun updateFavicon(faviconUrl: String?) {
            val tabs = bossTabsComponent.tabsState.value.tabs
            val tabIndex = tabs.indexOfFirst { it.id == tabId }
            if (tabIndex < 0) return

            val currentTab = tabs[tabIndex]

            // Update based on tab type - built-in FluckTabInfo or generic via reflection
            val updatedTab =
                when (currentTab) {
                    is FluckTabInfo -> {
                        currentTab.updateFaviconCacheKey(faviconUrl)
                    }

                    else -> {
                        // Try reflection for dynamic plugin tab types that have updateFaviconCacheKey(String?)
                        try {
                            val updateMethod =
                                currentTab::class.members.find {
                                    it.name == "updateFaviconCacheKey" && it.parameters.size == 2
                                }
                            val result = updateMethod?.call(currentTab, faviconUrl) as? TabInfo
                            if (result != null) {
                                result
                            } else {
                                bossMainWindowPanelLogger.debug(
                                    LogCategory.UI,
                                    "Cannot update favicon - no updateFaviconCacheKey method",
                                    mapOf(
                                        "tabId" to tabId,
                                        "tabType" to currentTab::class.simpleName,
                                    ),
                                )
                                return
                            }
                        } catch (e: Exception) {
                            bossMainWindowPanelLogger.debug(
                                LogCategory.UI,
                                "Cannot update favicon via reflection",
                                mapOf(
                                    "tabId" to tabId,
                                    "tabType" to currentTab::class.simpleName,
                                    "error" to (e.message ?: "unknown"),
                                ),
                            )
                            return
                        }
                    }
                }

            bossTabsComponent.updateTab(tabIndex, updatedTab)
        }

        override fun updateUrl(url: String) {
            val tabs = bossTabsComponent.tabsState.value.tabs
            val tabIndex = tabs.indexOfFirst { it.id == tabId }
            if (tabIndex < 0) return

            val currentTab = tabs[tabIndex]

            if (currentTab is FluckTabInfo) {
                // Landing on home (about:blank renders the dashboard) means no
                // TitleChanged/FaviconChanged will follow — apply the home identity
                // here so the tab never keeps the previous page's title/favicon.
                // The home title also goes into the navigation-history entry, so
                // the visit isn't recorded under the previous page's title.
                val isHome = FluckTabInfo.isHomeUrl(url)
                val title = if (isHome) FluckTabInfo.HOME_TITLE else currentTab.title
                var updatedTab = currentTab.updateNavigation(title, url)
                if (isHome) {
                    updatedTab =
                        updatedTab
                            .updateTitle(FluckTabInfo.HOME_TITLE)
                            .updateFaviconCacheKey(null)
                }
                bossTabsComponent.updateTab(tabIndex, updatedTab)
            }
        }

        override fun closeTab() {
            bossTabsComponent.removeTabById(tabId)
        }

        override fun openNewTab(url: String): String? {
            val newTabId = "browser_${System.currentTimeMillis()}"
            val newTab =
                FluckTabInfo(
                    id = newTabId,
                    typeId =
                        ai.rever.boss.plugin.api
                            .TabTypeId("fluck"),
                    _title = "Loading...",
                    url = url,
                )
            val index = bossTabsComponent.addTab(newTab)
            return if (index >= 0) newTabId else null
        }
    }

    init {
        // Register listener to close tabs when their type is unregistered (plugin disabled)
        tabRegistry.addUnregisterListener(unregisterListener)
        bossMainWindowPanelLogger.info(
            LogCategory.UI,
            "Registered unregister listener",
            mapOf(
                "windowId" to windowId,
            ),
        )

        // Register this component's TabUpdateProviderFactory with the global registry
        TabUpdateRegistry.register(componentId, tabUpdateProviderFactory)
        bossMainWindowPanelLogger.debug(
            LogCategory.UI,
            "Registered TabUpdateProviderFactory",
            mapOf(
                "componentId" to componentId,
            ),
        )
    }

    /**
     * Close all tabs of a specific type.
     * Called when a plugin is disabled/unloaded to clean up its open tabs.
     */
    fun closeTabsByType(typeId: ai.rever.boss.plugin.api.TabTypeId) {
        val tabs = tabsState.value.tabs
        val indicesToRemove = mutableListOf<Int>()

        bossMainWindowPanelLogger.info(
            LogCategory.UI,
            "closeTabsByType called",
            mapOf(
                "targetTypeId" to typeId.typeId,
                "targetPluginId" to typeId.pluginId,
                "tabCount" to tabs.size,
            ),
        )

        for (i in tabs.indices) {
            val tabTypeId = tabs[i].typeId
            bossMainWindowPanelLogger.debug(
                LogCategory.UI,
                "Checking tab",
                mapOf(
                    "index" to i,
                    "tabId" to tabs[i].id,
                    "tabTypeId" to tabTypeId.typeId,
                    "tabPluginId" to tabTypeId.pluginId,
                    "matches" to (tabTypeId == typeId),
                ),
            )
            if (tabTypeId == typeId) {
                indicesToRemove.add(i)
                bossMainWindowPanelLogger.info(
                    LogCategory.UI,
                    "Will close tab",
                    mapOf(
                        "tabId" to tabs[i].id,
                        "typeId" to typeId.typeId,
                    ),
                )
            }
        }

        // Remove tabs in reverse order to avoid index issues
        for (i in indicesToRemove.sortedDescending()) {
            removeTab(i)
        }

        if (indicesToRemove.isNotEmpty()) {
            bossMainWindowPanelLogger.info(
                LogCategory.UI,
                "Closed tabs for disabled plugin",
                mapOf(
                    "typeId" to typeId.typeId,
                    "count" to indicesToRemove.size,
                ),
            )
        }
    }

    // Add a new tab
    fun addTab(config: TabInfo): Int {
        // Create component for this tab, with its own lifecycle so tab close can destroy it
        // (fires the component's lifecycle.onDestroy — see tabLifecycles).
        val tabLifecycle = LifecycleRegistry()
        val component = tabRegistry.createTabComponent(config, DefaultComponentContext(tabLifecycle))

        if (component != null) {
            // Drive the lifecycle to RESUMED: subscribers added in the component's init get
            // their up-callbacks replayed, and destroy() below CREATED would otherwise be a
            // silent no-op (Essenty only fires onDestroy from CREATED or above).
            tabLifecycle.resume()

            // Store component
            tabComponents[config.id] = component
            tabLifecycles[config.id] = tabLifecycle

            // Register tab with TabUpdateRegistry for plugin updates
            TabUpdateRegistry.registerTab(config.id, componentId)

            // Add to navigation
            val index = tabsNavigation.addTab(config)
            // A newly opened tab becomes active; record it as most-recently-used and end
            // any in-progress MRU cycle.
            recordTabUsage(config.id)
            tabCycleOrder = null
            publishSystemEvent(TabEvent(tabId = config.id, tabType = TabEventType.OPENED, windowId = windowId))
            return index
        }

        // No factory for this type — usually the owning plugin hasn't finished
        // loading. The tab is dropped; workspace restore gates on tab-type
        // registration to avoid hitting this, so reaching here is worth a log.
        // (tabLifecycle stays INITIALIZED with no subscribers and no references — GC'd.)
        bossMainWindowPanelLogger.warn(
            LogCategory.UI,
            "Dropped tab - no factory registered for its type",
            mapOf(
                "typeId" to config.typeId.typeId,
                "title" to config.title,
            ),
        )
        return -1 // Failed to create component
    }

    // Remove a tab by index
    fun removeTab(index: Int) {
        val config = tabsState.value.tabs.getOrNull(index)
        config?.let {
            // Unregister tab from TabUpdateRegistry (ownership-checked: a no-op if a move
            // already re-registered this tab id to its destination component)
            TabUpdateRegistry.unregisterTab(it.id, componentId)
            publishSystemEvent(TabEvent(tabId = it.id, tabType = TabEventType.CLOSED, windowId = windowId))

            // Dispose the component if it has a dispose method
            val component = tabComponents.remove(it.id)
            if (component is ai.rever.boss.components.plugin.tab_types.fluck.FluckTabComponent) {
                component.dispose()
            }
            // Destroy the tab's own lifecycle so components that clean up in
            // lifecycle.onDestroy (dynamic plugin tabs like fluck-browser) release their
            // resources — without this a closed browser tab's Chromium process lives on.
            tabLifecycles.remove(it.id)?.destroy()
            // Panel-host tabs keep an explicit close signal (the hosted panel component is
            // owned by PanelComponentStore, not the tab lifecycle — see PanelHostTab.kt):
            // decrements the hosted-as-tab count so the sidebar icon reopens the plugin
            // in its sidebar location once the last hosting tab is closed.
            if (component is ai.rever.boss.components.plugin.tab_types.PanelHostTabComponent) {
                component.onClosed()
            }

            // If this is a runner terminal, notify the service to clean up tracking
            // This handles the case where user closes the tab directly (not via Stop button)
            if (it.id.startsWith(RUNNER_TERMINAL_PREFIX)) {
                RunnerTerminalService.removeTerminal(windowId, it.id)
            }
            // Drop the closed tab from MRU tracking and abandon any in-progress cycle.
            mruTabIds.remove(it.id)
            tabCycleOrder = null
        }
        // Before the removal, while `index` still refers to the tab being closed. Every
        // close-many helper funnels through here, so none of them has to know about pinning.
        _pinnedCount.value = pinnedCountAfterRemove(pinnedCount, index)
        tabsNavigation.removeTab(index)
    }

    // Remove a tab by ID - safer than index-based removal when state may have changed.
    // Returns true if a tab with that id existed and was removed.
    fun removeTabById(tabId: String): Boolean {
        val index = tabsState.value.tabs.indexOfFirst { it.id == tabId }
        if (index >= 0) {
            removeTab(index)
            return true
        }
        return false
    }

    /**
     * A tab lifted out of one panel for adoption by another (see [detachTab]/[adoptTab]).
     * Carries the live component instance and its lifecycle so a cross-panel move transfers
     * the running tab instead of destroy-and-recreate — a moved browser tab keeps its page
     * (and playing media) instead of reloading and leaking the old browser instance.
     *
     * Contract: the caller MUST hand a non-null DetachedTab to [adoptTab] or call
     * [destroy] on it. Dropping it on the floor keeps the component running (its
     * lifecycle stays RESUMED) with no panel showing it and no cleanup path — the
     * exact leak the detach/adopt mechanism exists to eliminate.
     */
    class DetachedTab internal constructor(
        val config: TabInfo,
        internal val component: TabComponentWithUI,
        internal val lifecycle: LifecycleRegistry?,
    ) {
        /** Destroy the detached component instead of adopting it (fires its onDestroy cleanup). */
        fun destroy() {
            lifecycle?.destroy()
        }
    }

    /**
     * Detach a tab for a move: remove it from this panel WITHOUT destroying its component or
     * publishing a CLOSED event. Returns null if the tab or its component is unknown (caller
     * should fall back to remove+add). The returned [DetachedTab.config] is the panel's
     * CURRENT TabInfo (fresh navigation state), not whatever the caller captured at drag start.
     */
    fun detachTab(tabId: String): DetachedTab? {
        val index = tabsState.value.tabs.indexOfFirst { it.id == tabId }
        if (index < 0) return null
        val config = tabsState.value.tabs[index]
        val component = tabComponents.remove(tabId) ?: return null
        val lifecycle = tabLifecycles.remove(tabId)

        // Ownership-checked: no-op if the destination already re-registered this id.
        TabUpdateRegistry.unregisterTab(tabId, componentId)
        mruTabIds.remove(tabId)
        tabCycleOrder = null
        tabsNavigation.removeTab(index)
        return DetachedTab(config, component, lifecycle)
    }

    /**
     * Adopt a tab detached from another panel: the component instance (and its lifecycle)
     * transfer as-is, so the tab keeps running across the move. Counterpart of [detachTab].
     */
    fun adoptTab(detached: DetachedTab): Int {
        // Tab ids are unique across a window, but guard anyway: silently overwriting an
        // existing entry would orphan its component without destroy — the leak shape this
        // change exists to eliminate. Close the stale holder first.
        if (tabComponents.containsKey(detached.config.id)) {
            removeTabById(detached.config.id)
        }
        tabComponents[detached.config.id] = detached.component
        detached.lifecycle?.let { tabLifecycles[detached.config.id] = it }
        TabUpdateRegistry.registerTab(detached.config.id, componentId)
        val index = tabsNavigation.addTab(detached.config)
        recordTabUsage(detached.config.id)
        tabCycleOrder = null
        publishSystemEvent(TabEvent(tabId = detached.config.id, tabType = TabEventType.MOVED, windowId = windowId))
        return index
    }

    // Select a tab
    fun selectTab(index: Int) {
        // A direct selection (tab click, programmatic open) ends any in-progress MRU
        // cycle and marks the tab as most-recently-used.
        tabCycleOrder = null
        tabsNavigation.selectTab(index)
        tabsState.value.tabs
            .getOrNull(index)
            ?.let { recordTabUsage(it.id) }
    }

    /**
     * Switch to the next tab via Ctrl+Tab. Behavior follows the configured
     * [TabSwitchMode]: positional (next in tab-bar order) or MRU (Alt+Tab style).
     */
    fun switchToNextTab() = switchTab(forward = true)

    /** Switch to the previous tab via Ctrl+Shift+Tab. See [switchToNextTab]. */
    fun switchToPreviousTab() = switchTab(forward = false)

    private fun switchTab(forward: Boolean) {
        val tabs = tabsState.value.tabs
        if (tabs.size <= 1) return
        when (KeymapSettingsManager.currentSettings.value.tabSwitchMode) {
            TabSwitchMode.POSITIONAL -> {
                val cur = tabsState.value.activeIndex.coerceAtLeast(0)
                val step = if (forward) 1 else -1
                val next = ((cur + step) % tabs.size + tabs.size) % tabs.size
                selectTab(next)
            }

            TabSwitchMode.MRU -> {
                stepMruCycle(forward)
            }
        }
    }

    private fun stepMruCycle(forward: Boolean) {
        val tabs = tabsState.value.tabs
        // Build the cycle order once at the start of a cycle: MRU order first, then any
        // open tabs not yet tracked (in tab-bar order) so every tab stays reachable.
        var order = tabCycleOrder
        if (order == null) {
            val tabIds = tabs.map { it.id }
            val tracked = tabIds.filter { mruTabIds.contains(it) }.sortedBy { mruTabIds.indexOf(it) }
            val untracked = tabIds.filter { !mruTabIds.contains(it) }
            order = tracked + untracked
            tabCycleOrder = order
            tabCyclePointer = order.indexOf(tabsState.value.activeTab?.id).coerceAtLeast(0)
        }
        if (order.isEmpty()) return
        val step = if (forward) 1 else -1
        tabCyclePointer = ((tabCyclePointer + step) % order.size + order.size) % order.size
        val targetIndex = tabs.indexOfFirst { it.id == order[tabCyclePointer] }
        // Move selection without reordering MRU; commitTabCycle() promotes the landed tab.
        if (targetIndex >= 0) tabsNavigation.selectTab(targetIndex)
    }

    /**
     * Commit an in-progress MRU cycle (called when the cycling modifier is released):
     * promote the landed tab to the front of the MRU order and end the cycle.
     * No-op when no cycle is active (e.g. positional mode).
     */
    fun commitTabCycle() {
        tabCycleOrder ?: return
        tabCycleOrder = null
        tabsState.value.activeTab?.let { recordTabUsage(it.id) }
    }

    /**
     * Snapshot of the in-progress MRU cycle for the switcher overlay, or null when no
     * cycle is active (e.g. positional mode or after commit).
     */
    fun currentCycleOverlay(): TabCycleOverlayData? {
        val order = tabCycleOrder ?: return null
        val byId = tabsState.value.tabs.associateBy { it.id }
        val tabs = order.mapNotNull { byId[it] }
        if (tabs.isEmpty()) return null
        return TabCycleOverlayData(tabs = tabs, highlightedIndex = tabCyclePointer.coerceIn(0, tabs.size - 1))
    }

    private fun recordTabUsage(tabId: String) {
        mruTabIds.remove(tabId)
        mruTabIds.add(0, tabId)
    }

    // Move a tab from one position to another

    /**
     * Move a tab, letting pinned-ness follow where it landed.
     *
     * This is the drag path: dragging across the sidebar's separator is how a tab gets pinned or
     * unpinned by direct manipulation, the way it works in Arc. [pinTab]/[unpinTab] are the
     * explicit path and deliberately do not come through here.
     */
    fun moveTab(
        fromIndex: Int,
        toIndex: Int,
    ) {
        val tabs = tabsState.value.tabs
        if (fromIndex !in tabs.indices || toIndex !in tabs.indices) return
        _pinnedCount.value = pinnedCountAfterMove(pinnedCount, fromIndex, toIndex)
        tabsNavigation.moveTab(fromIndex, toIndex)
    }

    // Update a tab
    fun updateTab(
        index: Int,
        config: TabInfo,
    ) {
        tabsNavigation.updateTab(index, config)
    }

    // Get active tab component
    fun getActiveComponent(): TabComponentWithUI? {
        val activeTab = tabsState.value.activeTab ?: return null
        return tabComponents[activeTab.id]
    }

    // Get tab component by ID
    fun getComponentById(tabId: String): TabComponentWithUI? = tabComponents[tabId]

    // Get the currently selected tab
    fun getCurrentTab(): TabInfo? = tabsState.value.activeTab

    // Clear all tabs safely
    fun clearAllTabs() {
        // Remove tabs in reverse order to avoid index issues
        val tabCount = tabsState.value.tabs.size
        for (i in tabCount - 1 downTo 0) {
            removeTab(i)
        }
    }

    // Close other tabs (keep only the specified tab)
    fun closeOtherTabs(keepIndex: Int) {
        val tabs = tabsState.value.tabs
        if (keepIndex < 0 || keepIndex >= tabs.size) return

        // Remove tabs in reverse order to avoid index issues
        for (i in tabs.size - 1 downTo 0) {
            if (i != keepIndex) {
                removeTab(i)
            }
        }
    }

    // Close tabs to the right of the specified index
    fun closeTabsToRight(fromIndex: Int) {
        val tabs = tabsState.value.tabs
        if (fromIndex < 0 || fromIndex >= tabs.size - 1) return

        // Remove tabs from right to left to avoid index issues
        for (i in tabs.size - 1 downTo fromIndex + 1) {
            removeTab(i)
        }
    }

    // Close tabs to the left of the specified index
    fun closeTabsToLeft(fromIndex: Int) {
        if (fromIndex <= 0) return

        // Remove tabs from right to left to avoid index issues
        for (i in fromIndex - 1 downTo 0) {
            removeTab(i)
        }
    }

    // Close tab by URL (used for auto-closing download redirects)
    fun closeTabByUrl(url: String) {
        val tabs = tabsState.value.tabs

        // Find all tabs with matching URL (might be multiple)
        val indicesToRemove = mutableListOf<Int>()
        for (i in tabs.indices) {
            val tab = tabs[i]
            val tabUrl =
                when (tab) {
                    is FluckTabInfo -> tab.currentUrl
                    else -> null
                }

            if (tabUrl == url) {
                indicesToRemove.add(i)
                bossMainWindowPanelLogger.debug(LogCategory.UI, "Found tab to close", mapOf("index" to i))
            }
        }

        // Remove tabs in reverse order to avoid index issues
        for (i in indicesToRemove.sortedDescending()) {
            removeTab(i)
        }

        if (indicesToRemove.isNotEmpty()) {
            bossMainWindowPanelLogger.debug(LogCategory.UI, "Closed tabs", mapOf("count" to indicesToRemove.size))
        }
    }

    // Close the most recently opened tab (used for auto-closing download redirects)
    fun closeMostRecentTab() {
        val tabs = tabsState.value.tabs
        if (tabs.isNotEmpty()) {
            val lastIndex = tabs.size - 1
            bossMainWindowPanelLogger.debug(LogCategory.UI, "Closing most recent tab", mapOf("index" to lastIndex))
            removeTab(lastIndex)
        } else {
            bossMainWindowPanelLogger.debug(LogCategory.UI, "No tabs to close")
        }
    }

    /**
     * Synchronously dispose all browser tabs in this component.
     * Called when the window is closing to ensure JxBrowser instances
     * are fully closed before AWT window destruction.
     *
     * This prevents crashes caused by JxBrowser trying to access
     * disposed AWT window handles during rendering.
     */
    fun disposeAllTabsBlocking() {
        tabComponents.values.toList().forEach { component ->
            if (component is ai.rever.boss.components.plugin.tab_types.fluck.FluckTabComponent) {
                component.disposeBlocking()
            }
        }
        tabComponents.clear()
        // Destroy the per-tab lifecycles so plugin components that clean up in
        // lifecycle.onDestroy release their resources on window close too — same
        // contract as removeTab. SplitViewState performs the window-scoped
        // BrowserService fallback after every panel lifecycle has been destroyed.
        tabLifecycles.values.toList().forEach { it.destroy() }
        tabLifecycles.clear()
    }
}

/**
 * Convert TabInfo to TabConfig for bookmark storage
 */
private fun convertTabInfoToTabConfig(tabInfo: TabInfo): TabConfig =
    when (tabInfo) {
        is FluckTabInfo -> {
            TabConfig(
                type = "browser",
                title = tabInfo.title,
                url = tabInfo.url,
                faviconCacheKey = tabInfo.faviconCacheKey,
            )
        }

        is EditorTabInfo -> {
            TabConfig(
                type = "editor",
                title = tabInfo.title,
                filePath = tabInfo.filePath,
            )
        }

        is TerminalTabInfo -> {
            TabConfig(
                type = "terminal",
                title = tabInfo.title,
            )
        }

        is JupyterTabInfo -> {
            TabConfig(
                type = "jupyter",
                title = tabInfo.title,
                filePath = tabInfo.filePath,
            )
        }

        else -> {
            TabConfig(
                type = "unknown",
                title = tabInfo.title,
            )
        }
    }

/**
 * Whether the surrounding composition is inside a [BossMainWindowPanel].
 *
 * Host-internal, and deliberately **not** on `PluginContext`: it exists only so a window-scoped
 * keyboard shortcut broadcast to every candidate surface can prefer the one in the main content
 * area over one in a sidebar slot. `LocalIsPanelActive` cannot answer that - it defaults to `true`,
 * so "outside any panel" and "in the active panel" are the same value.
 *
 * Defaults to `false`, so the only thing that reads as a main-panel surface is one that actually is.
 */
val LocalInMainWindowPanel: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }
