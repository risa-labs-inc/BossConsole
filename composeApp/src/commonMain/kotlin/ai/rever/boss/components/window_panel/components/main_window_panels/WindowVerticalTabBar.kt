package ai.rever.boss.components.window_panel.components.main_window_panels

import ai.rever.boss.components.bars.vertical.VerticalBar
import ai.rever.boss.components.model.TabDraggableComponent
import ai.rever.boss.components.model.TabDropResult
import ai.rever.boss.components.overlays.contextMenu
import ai.rever.boss.components.plugin.MissingPluginOffer
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.LocalWindowId
import ai.rever.boss.window.MenuActionsHandler
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.Divider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val windowVerticalTabBarLogger = BossLogger.forComponent("WindowVerticalTabBar")

/**
 * Space above the rule that divides one pane's tabs from the next.
 *
 * Larger than the gap around the divider between two tabs, for the same reason that rule runs
 * full bleed while the tab one is inset: the difference is what tells the two divisions apart.
 */
internal val GROUP_RULE_GAP = 10.dp

/**
 * Build one [TabBarGroup] per pane, in the order the panes are laid out on screen.
 *
 * Every group shares the caller's [listState], because they all end up as rows of the same
 * column. Sections are suppressed once there is more than one group and only the active pane's
 * group wears the amber marker - see the corresponding parameters on [rememberTabBarState] for
 * why each of those is decided here rather than inside a group.
 */
@Composable
fun rememberWindowTabGroups(
    splitViewState: SplitViewState,
    listState: LazyListState,
    expansion: TabGroupExpansion,
    tabDragComponent: TabDraggableComponent? = null,
    onTabDropResult: (TabDropResult) -> Unit = {},
    onTabActivated: (() -> Unit)? = null,
    onTransientInteraction: ((Boolean) -> Unit)? = null,
): List<TabBarGroup> {
    // Reading rootNode through getAllPanels() subscribes this composition to the split tree, so
    // splitting or closing a pane adds or drops a group without anything else being told.
    val paneWindowId = LocalWindowId.current
    val panels = splitViewState.getAllPanels()
    val activePanelId by splitViewState.activePanelIdState
    val several = panels.size > 1

    // Every pane's measured rectangle, normalised against the area they cover between them. Read
    // here rather than inside a group because the answer for one pane depends on all of them.
    val glyphs =
        panels
            .mapNotNull { panel -> splitViewState.getPanelBounds(panel.id)?.let { panel.id to it } }
            .let { measured ->
                val all = measured.map { it.second }
                measured.mapNotNull { (id, bounds) -> paneGlyphFor(bounds, all)?.let { id to it } }.toMap()
            }

    return panels.mapIndexed { index, panel ->
        key(panel.id) {
            // A lone pane is the pane being worked in, whatever the active id says. Without
            // that clause a stale or not-yet-set activePanelId would render the only bar in the
            // window with the quiet marker instead of the amber one, which is the common case.
            val isActive = panel.id == activePanelId || !several
            // The pane being worked in is always open. Collapsing it hid the tabs of the one
            // place the user is actually switching between, so every tab change there began with
            // a hover - and the row it collapsed to was the tab they were already on.
            val expanded = !several || isActive || expansion.isExpanded(panel.id)
            val state =
                with(panel.tabsComponent) {
                    rememberTabBarState(
                        splitViewState = splitViewState,
                        currentPanelId = panel.id,
                        // The pane's OWN requester, borrowed from the state that hands it out.
                        // This bar is outside every pane, so it has no other way to give focus
                        // back to one after closing a tab from a menu.
                        focusRequester = splitViewState.focusRequesterFor(panel.id),
                        tabDragComponent = tabDragComponent,
                        onTabDropResult = onTabDropResult,
                        vertical = true,
                        onTransientInteraction = onTransientInteraction,
                        onTabActivated = onTabActivated,
                        sharedListState = listState,
                        autoScrollToActive = false,
                        showSections = !several,
                        isActiveGroup = isActive,
                        // The header carries this pane's "+" once there is a header to carry it.
                        showNewTabRow = !several,
                        // A lone pane is the whole bar and is meant to be read: collapsing it
                        // would hide the tabs the vertical bar exists to show. Collapsing is for
                        // the case where several panes' lists together outrun the bar.
                        collapseToActiveTab = several && !expanded,
                    )
                }
            TabBarGroup(
                panelId = panel.id,
                state = state,
                isActive = isActive,
                glyph = glyphs[panel.id],
                // A name the user gave beats the derived position.
                label = splitViewState.panelName(panel.id) ?: paneLabel(index, glyphs[panel.id]),
                activate = { splitViewState.setActivePanel(panel.id) },
                zoom = { splitViewState.zoomPanel(panel.id) },
                rename = { name -> splitViewState.renamePanel(panel.id, name) },
                // Asks for a tab FIRST, then splits with it. A split that made an empty pane
                // would have it closed again about 50ms later by checkAndCloseEmptyPanels, so the
                // menu entry appeared to do nothing at all. See SplitViewState.pendingSplit.
                split = { orientation ->
                    splitViewState.requestSplitWithNewTab(panel.id, orientation)
                    paneWindowId?.let { MenuActionsHandler.triggerNewTab(it) }
                },
                newTab = state.openNewTab,
                // Only offered where there is a split to undo. closePanel refuses to remove a
                // lone panel anyway, so a button for it would be one that does nothing.
                close = if (several) ({ splitViewState.closePanel(panel.id) }) else null,
                expanded = expanded,
                toggleExpanded = { expansion.togglePinned(panel.id) },
                hoverGroup = { expansion.hover(panel.id) },
            )
        }
    }
}

/**
 * The window's one vertical tab bar.
 *
 * BossConsole nests `Window -> Split -> Panel -> Tabs`, so every pane owns an independent tab
 * stack. Drawing a bar per pane made that nesting the user's problem: a two-way split showed two
 * ~200dp bars side by side and there was no single place to see what was open. This draws ONE
 * bar for the window and renders each pane as a group of rows inside it, which is the shape
 * BossTerm has always had - without moving a single tab out of the pane that owns it.
 *
 * With one pane there are no groups and no rules: the bar is exactly what it was.
 *
 * @param groups from [rememberWindowTabGroups], in pane order.
 * @param listState the same state those groups were built with.
 * @param collapsed render the slim rail instead of the labelled column.
 * @param onToggleCollapse null hides the chevron entirely.
 * @param onPin replaces the chevron while this bar is a transient hover reveal. See
 *   `BossMainPanel`'s drawer for why the two are never offered together.
 * @param registerBounds false for a hover-revealed drawer, whose coordinates belong to another
 *   window entirely and would overwrite the real bar's.
 */
@Composable
fun WindowVerticalTabBar(
    groups: List<TabBarGroup>,
    listState: LazyListState,
    expansion: TabGroupExpansion,
    width: Dp,
    collapsed: Boolean = false,
    onToggleCollapse: (() -> Unit)? = null,
    onPin: (() -> Unit)? = null,
    tabDragComponent: TabDraggableComponent? = null,
    registerBounds: Boolean = true,
    /** One pane is filling the window, so the map is the way back to all of them. */
    zoomed: Boolean = false,
    /** Put the split back. */
    onExitZoom: () -> Unit = {},
    /**
     * Window chrome to sit at the foot of the bar, above the split map.
     *
     * A slot rather than parameters, because what goes here - today the project and workspace
     * pickers, when the top bar is not on screen to hold them - is nothing a tab bar should know
     * about. It measures panes and lists tabs; whoever composes the window knows about projects.
     */
    footer: @Composable () -> Unit = {},
) {
    // The pane the user is working in owns the bar's shared chrome: its bar menu, its Favorites
    // shelf, and where a favourite opens. Falling back to the first group keeps every one of
    // those working during the frame in which the active pane has gone away.
    val lead = groups.firstOrNull { it.isActive } ?: groups.firstOrNull() ?: return

    KeepActiveTabVisible(groups = groups, lead = lead, listState = listState)

    // Panes come and go and the expansion state is keyed by panel id, but nothing tells it when
    // one closes. Without this it accumulates ids for panes that are gone, and a pane handed a
    // recycled id would come up pinned open for no reason the user could see.
    val livePanelIds = groups.map { it.panelId }
    LaunchedEffect(livePanelIds) { expansion.retainOnly(livePanelIds.toSet()) }

    // The strip's own rectangle, which is what the groups are carved out of. Measured on the
    // scrolling column rather than on the whole bar so the Favorites shelf above it is not
    // offered as somewhere to drop a tab.
    var stripBounds by remember { mutableStateOf<Rect?>(null) }

    // The whole bar, which is what the rail registers - a rail has no list to carve up.
    var railBounds by remember { mutableStateOf<Rect?>(null) }

    // Leaving the bar drops the hover choice. Tracked on the bar rather than per group because
    // that is the only boundary the sticky-hover model cares about - see TabGroupExpansion.
    val barInteraction = remember { MutableInteractionSource() }
    val barHovered by barInteraction.collectIsHoveredAsState()
    LaunchedEffect(barHovered) { if (!barHovered) expansion.barExited() }

    VerticalBar(
        width = verticalTabBarWidth(collapsed = collapsed, width = width),
        backgroundColor = BossTheme.colors.panel,
        modifier =
            Modifier
                .hoverable(barInteraction)
                // The whole bar, which is what the collapsed rail and the strips of chrome above
                // and below the tab list rely on. The tab list carries its own copy - see
                // ExpandedGroups - because that is where most of the bare chrome actually is.
                //
                // Fires on bare chrome and nowhere else: tabs and rail dots consume on the INITIAL
                // pass, and contextMenu skips a press already claimed.
                .contextMenu(items = lead.state.barContextMenuItems)
                .onGloballyPositioned { coordinates -> railBounds = coordinates.boundsInWindow() },
    ) {
        if (collapsed) {
            // The rail is the same bar with its labels taken away, not a lesser one: every tab
            // is still here, still selectable, and still carries its whole menu.
            BossTabRail(
                groups = groups,
                onExpand = { onToggleCollapse?.invoke() },
                onNewTab = lead.state.openNewTab,
            )
        } else {
            ExpandedGroups(
                groups = groups,
                lead = lead,
                listState = listState,
                onToggleCollapse = onToggleCollapse,
                onPin = onPin,
                onStripBounds = { stripBounds = it },
                tabDragComponent = tabDragComponent.takeIf { registerBounds },
                footer = footer,
                zoomed = zoomed,
                onExitZoom = onExitZoom,
            )
        }
    }

    if (registerBounds && tabDragComponent != null) {
        RegisterGroupBounds(
            groups = groups,
            listState = listState,
            // A rail draws dots, not rows, and the list is not laid out at all - so there is
            // nothing to carve up and every group's rectangle is retracted. The rail's own
            // rectangle is registered for the lead group below, which keeps a drag onto it
            // meaning what it always meant.
            strip = stripBounds.takeIf { !collapsed },
            leadPanelId = lead.panelId,
            railBounds = railBounds.takeIf { collapsed },
            tabDragComponent = tabDragComponent,
        )
    }

    // Every group's dialogs, not just the lead's: a new-tab dialog opened from a background
    // pane's "+" belongs to that pane and has to stay mounted for as long as it is open.
    groups.forEach { group -> key(group.panelId) { group.state.dialogs() } }
}

/**
 * The full window bar, revealed over a main area whose bar is down to its rail.
 *
 * A second, differently wired copy of [WindowVerticalTabBar] rather than another branch of the
 * layout, and the four things it does differently from the in-flow one each need saying - see the
 * arguments below.
 */
@Composable
fun BoxScope.WindowRevealedTabBarDrawer(
    splitViewState: SplitViewState,
    bar: TabBarLayout,
    reveal: TabBarRevealState,
    contentRegion: IntRect?,
    onPin: (() -> Unit)?,
) {
    // Built here rather than taken as a parameter: dismissing a drawer is the drawer's own
    // business, and the pointer state it needs is a composable read the caller had to make on the
    // drawer's behalf. Nothing outside dismisses this.
    val pointerInSidebar = reveal.pointerInSidebar()
    val onDismiss: () -> Unit = { reveal.dismiss(pointerInSidebar) }

    VerticalTabBarDrawer(
        visible = reveal.drawerVisible,
        hoverSource = reveal.drawerHover,
        hoverEnabled = bar.hoverExpand,
        width = bar.width,
        panelRegion = contentRegion,
        onDismissOutside = reveal.dismissOutside,
    ) {
        val listState = rememberLazyListState()
        // The drawer's own expansion state, not the in-flow bar's: it is a second bar with its
        // own composition, and sharing the choice would have hovering one of them re-arrange the
        // other behind it.
        val expansion = rememberTabGroupExpansion()
        val groups =
            rememberWindowTabGroups(
                splitViewState = splitViewState,
                listState = listState,
                expansion = expansion,
                // Deliberately no drag component. A drawer is a SECOND bar for panels that
                // already have one registered, and under HARDWARE it lives in its own window
                // where these coordinates mean nothing - so a drag started here could only ever
                // be dropped somewhere wrong. Withholding it disables the gesture outright
                // (BossTabButton gates on it being non-null), which is honest.
                tabDragComponent = null,
                // Picking a tab is finishing with the drawer. Without this it stays open under
                // the pointer that just used it.
                onTabActivated = onDismiss,
                onTransientInteraction = reveal::setBusy,
            )
        WindowVerticalTabBar(
            groups = groups,
            listState = listState,
            expansion = expansion,
            width = bar.width,
            collapsed = false,
            // The chevron dismisses the drawer rather than writing tabBarCollapsed: the bar it is
            // collapsing IS the drawer. It has to exist even though hover already closes this
            // thing, because a chevron-opened drawer (narrow window) otherwise has no way out at
            // all - the click-catcher behind it is a Compose node, and under HARDWARE the
            // browser's native surface is painted above it, so clicking the page never reaches it.
            onToggleCollapse = onDismiss,
            onPin = onPin,
            tabDragComponent = null,
            registerBounds = false,
        )
    }
}

/**
 * Scroll the shared column so the active pane's active tab is on screen.
 *
 * One effect for the whole bar, watching one pane. Each group could run its own - that is what a
 * bar per pane did - but they would all be scrolling the SAME column, so a background pane
 * changing tabs would yank the viewport away from the pane the user is looking at. That is why
 * every group is built with `autoScrollToActive = false`.
 */
@Composable
private fun KeepActiveTabVisible(
    groups: List<TabBarGroup>,
    lead: TabBarGroup,
    listState: LazyListState,
) {
    val itemCounts = groups.listItemCounts()
    val leadIndex = groups.indexOfFirst { it.panelId == lead.panelId }
    val activeIndex = lead.state.activeIndex

    LaunchedEffect(lead.panelId, activeIndex, itemCounts) {
        if (leadIndex < 0 || activeIndex < 0 || activeIndex >= lead.state.tabs.size) return@LaunchedEffect
        // List index, not tab index: this pane's header and its own leading rows sit above its
        // first tab, and every earlier pane's rows sit above those.
        //
        // A COLLAPSED group emits its active tab as its only tab row, so that row is at offset
        // zero whatever the tab's index in the panel happens to be. Adding the index there would
        // aim past the end of the group and scroll to some later pane's rows.
        val offsetInGroup = if (lead.state.collapsedToActiveTab) 0 else activeIndex
        val target =
            groupStartIndex(itemCounts, leadIndex) +
                groups.groupChromeItems() +
                lead.state.leadingListItems +
                offsetInGroup

        val layoutInfo = listState.layoutInfo
        val item = layoutInfo.visibleItemsInfo.find { it.index == target }
        val fullyVisible =
            item != null &&
                item.offset >= layoutInfo.viewportStartOffset &&
                item.offset + item.size <= layoutInfo.viewportEndOffset
        if (!fullyVisible) listState.scrollToItem(target)
    }
}

/** The bar's shelf and its one scrolling column of every pane's rows. */
@Composable
private fun ExpandedGroups(
    groups: List<TabBarGroup>,
    lead: TabBarGroup,
    listState: LazyListState,
    onToggleCollapse: (() -> Unit)?,
    onPin: (() -> Unit)?,
    onStripBounds: (Rect) -> Unit,
    tabDragComponent: TabDraggableComponent?,
    footer: @Composable () -> Unit,
    zoomed: Boolean,
    onExitZoom: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WindowTabBarFavorites(
            lead = lead,
            onToggleCollapse = onToggleCollapse,
            onPin = onPin,
            tabDragComponent = tabDragComponent,
        )

        BossVerticalTabStrip(
            listState = listState,
            modifier =
                Modifier
                    // The bar's own menu, on the scrolling column as well as on the bar behind it.
                    //
                    // Most of a vertical bar is the empty space below the last tab, and that space
                    // belongs to this list rather than to the bar - so reaching the bar's menu from
                    // it meant the press travelling up through the list, the column and the bar Box
                    // untouched. Nothing in that chain consumes today, but the same assumption was
                    // written down once before and was false: the comment on the bar root said
                    // children consume first when the modifier it relied on was not checking. The
                    // nearest handler to the space being clicked is the one that cannot be broken
                    // by something appearing in between.
                    //
                    // A tab still wins: tabs consume on the INITIAL pass, and contextMenu skips a
                    // press already claimed - so this fires on bare chrome and nowhere else.
                    .contextMenu(items = lead.state.barContextMenuItems)
                    .onGloballyPositioned { coordinates -> onStripBounds(coordinates.boundsInWindow()) },
        ) {
            groups.forEachIndexed { index, group ->
                // One header per group, including the first: without one on the first group the
                // reader has a labelled second pane and an unlabelled first, and has to infer
                // that the rows above the rule are the other one.
                if (groups.size > 1) {
                    item(key = "boss-tab-group-header:${group.panelId}") {
                        TabBarGroupHeader(group = group, showRule = index > 0)
                    }
                }
                group.state.items(this, null)
                if (group.summaryRows > 0) {
                    item(key = "boss-tab-group-more:${group.panelId}") {
                        TabGroupSummaryRow(group = group)
                    }
                }
            }
        }

        // Everything below the strip is pinned to the foot of the bar, because the strip above
        // takes weight(1f) and this is what is left.
        footer()

        // The one place that shows the whole arrangement at once, which is what makes a four-way
        // split legible rather than a run of headers to read in order.
        SplitMap(groups = groups, zoomed = zoomed, onExitZoom = onExitZoom)
    }
}

/** The window-level Favorites shelf, which also carries the bar's one chrome control. */
@Composable
private fun WindowTabBarFavorites(
    lead: TabBarGroup,
    onToggleCollapse: (() -> Unit)?,
    onPin: (() -> Unit)?,
    tabDragComponent: TabDraggableComponent?,
) {
    TabBarFavorites(
        bookmarks = lead.state.favorites,
        pluginInstalled = lead.state.bookmarksInstalled,
        apiReachable = lead.state.bookmarksApiReachable,
        onOpen = lead.state.openFavorite,
        onRemove = lead.state.removeFavorite,
        // Never a silent click: if the offer declines to raise a prompt, say so in the log rather
        // than leaving a button that does nothing and reports nothing.
        onInstallPlugin = {
            if (!MissingPluginOffer.offerIfMissing(BOOKMARKS_PLUGIN_ID)) {
                windowVerticalTabBarLogger.warn(
                    LogCategory.UI,
                    "Install Bookmarks raised no prompt",
                    mapOf(
                        "pluginId" to BOOKMARKS_PLUGIN_ID,
                        "installed" to lead.state.bookmarksInstalled.toString(),
                        "apiReachable" to lead.state.bookmarksApiReachable.toString(),
                    ),
                )
            }
        },
        // The bar's one chrome control rides on this section's header line rather than owning a
        // row: pin when this is a hover reveal, collapse when it is the real bar.
        tabDragComponent = tabDragComponent,
        trailing = {
            if (onPin != null) {
                BossTabBarPinButton(onPin = onPin)
            } else {
                onToggleCollapse?.let { BossTabBarCollapseButton(onCollapse = it) }
            }
        },
    )
    Divider(color = BossTheme.colors.line)
}

/**
 * Keep each pane's slice of the shared bar registered with the drag system.
 *
 * The drag system asks one question of a bar - "whose tabs are under the pointer" - and answers
 * it from [TabDraggableComponent.tabBarBounds], one rectangle per panel. With a bar per pane that
 * rectangle was the bar. Here it is the part of the bar that pane's rows occupy, measured from
 * the list itself, so dragging within a group still reorders and dragging across the rule still
 * moves the tab to the other pane - both through the paths that already existed.
 */
@Composable
private fun RegisterGroupBounds(
    groups: List<TabBarGroup>,
    listState: LazyListState,
    strip: Rect?,
    leadPanelId: String,
    railBounds: Rect?,
    tabDragComponent: TabDraggableComponent,
) {
    val panelIds = groups.map { it.panelId }
    val itemCounts = groups.listItemCounts()

    LaunchedEffect(tabDragComponent, strip, railBounds, leadPanelId, panelIds, itemCounts) {
        if (strip == null) {
            // Collapsed, or not yet measured. Either way no group has rows on screen, so no
            // group may keep a rectangle - a stale one outlives the bar it was measured in and
            // goes on claiming that area for tabs nothing is drawing there.
            panelIds.forEach { panelId ->
                if (panelId != leadPanelId || railBounds == null) tabDragComponent.unregisterTabBarBounds(panelId)
            }
            railBounds?.let { tabDragComponent.registerTabBarBounds(leadPanelId, it, vertical = true) }
            return@LaunchedEffect
        }
        snapshotFlow { listState.layoutInfo }
            .map { info -> splitBarAmongGroups(strip, groupSpans(info, strip, panelIds, itemCounts)) }
            .distinctUntilChanged()
            .collect { rects ->
                panelIds.forEach { panelId ->
                    val bounds = rects[panelId]
                    if (bounds == null) {
                        tabDragComponent.unregisterTabBarBounds(panelId)
                    } else {
                        // The SLICE is what a drop lands in; the STRIP is what an edge-scroll
                        // triggers at. See TabBarBoundInfo.scrollBounds for why those differ here
                        // and nowhere else.
                        tabDragComponent.registerTabBarBounds(panelId, bounds, vertical = true, scrollBounds = strip)
                    }
                }
            }
    }

    // Leaving the composition takes every rectangle with it. Switching the bar to TOP disposes
    // this while the panels stay exactly where they are, and each panel's top strip registers a
    // horizontal rectangle of its own on the next layout - but only for panels that draw one, and
    // only once. Until then a leftover vertical slice would answer for them.
    DisposableEffect(tabDragComponent, panelIds) {
        onDispose { panelIds.forEach { tabDragComponent.unregisterTabBarBounds(it) } }
    }
}
