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

/** Breathing room above and below the rule that divides one pane's tabs from the next. */
private val GROUP_RULE_PADDING = 6.dp

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
    tabDragComponent: TabDraggableComponent? = null,
    onTabDropResult: (TabDropResult) -> Unit = {},
    onTabActivated: (() -> Unit)? = null,
    onTransientInteraction: ((Boolean) -> Unit)? = null,
): List<TabBarGroup> {
    // Reading rootNode through getAllPanels() subscribes this composition to the split tree, so
    // splitting or closing a pane adds or drops a group without anything else being told.
    val panels = splitViewState.getAllPanels()
    val activePanelId by splitViewState.activePanelIdState
    val several = panels.size > 1

    return panels.map { panel ->
        key(panel.id) {
            // A lone pane is the pane being worked in, whatever the active id says. Without
            // that clause a stale or not-yet-set activePanelId would render the only bar in the
            // window with the quiet marker instead of the amber one, which is the common case.
            val isActive = panel.id == activePanelId || !several
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
                    )
                }
            TabBarGroup(panelId = panel.id, state = state, isActive = isActive)
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
    width: Dp,
    collapsed: Boolean = false,
    onToggleCollapse: (() -> Unit)? = null,
    onPin: (() -> Unit)? = null,
    tabDragComponent: TabDraggableComponent? = null,
    registerBounds: Boolean = true,
) {
    // The pane the user is working in owns the bar's shared chrome: its bar menu, its Favorites
    // shelf, and where a favourite opens. Falling back to the first group keeps every one of
    // those working during the frame in which the active pane has gone away.
    val lead = groups.firstOrNull { it.isActive } ?: groups.firstOrNull() ?: return

    KeepActiveTabVisible(groups = groups, lead = lead, listState = listState)

    // The strip's own rectangle, which is what the groups are carved out of. Measured on the
    // scrolling column rather than on the whole bar so the Favorites shelf above it is not
    // offered as somewhere to drop a tab.
    var stripBounds by remember { mutableStateOf<Rect?>(null) }

    // The whole bar, which is what the rail registers - a rail has no list to carve up.
    var railBounds by remember { mutableStateOf<Rect?>(null) }

    VerticalBar(
        width = verticalTabBarWidth(collapsed = collapsed, width = width),
        backgroundColor = BossTheme.colors.panel,
        modifier =
            Modifier
                // On the bar rather than on an empty-space sibling, the way BossLeftSideBar does
                // it: the tabs and rail dots carry their own contextMenu and consume the press
                // first, so this fires on bare chrome and nowhere else.
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
    onDismiss: () -> Unit,
    onPin: (() -> Unit)?,
) {
    VerticalTabBarDrawer(
        visible = reveal.drawerVisible,
        hoverSource = reveal.drawerHover,
        hoverEnabled = bar.hoverExpand,
        width = bar.width,
        panelRegion = contentRegion,
        onDismissOutside = reveal.dismissOutside,
    ) {
        val listState = rememberLazyListState()
        val groups =
            rememberWindowTabGroups(
                splitViewState = splitViewState,
                listState = listState,
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
    val itemCounts = groups.map { it.state.listItemCount }
    val leadIndex = groups.indexOfFirst { it.panelId == lead.panelId }
    val activeIndex = lead.state.activeIndex

    LaunchedEffect(lead.panelId, activeIndex, itemCounts) {
        if (leadIndex < 0 || activeIndex < 0 || activeIndex >= lead.state.tabs.size) return@LaunchedEffect
        // List index, not tab index: the pane's own leading rows sit above its first tab, and
        // every earlier pane's rows and rules sit above those.
        val target = groupStartIndex(itemCounts, leadIndex) + lead.state.leadingListItems + activeIndex

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
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WindowTabBarFavorites(lead = lead, onToggleCollapse = onToggleCollapse, onPin = onPin)

        BossVerticalTabStrip(
            listState = listState,
            modifier = Modifier.onGloballyPositioned { coordinates -> onStripBounds(coordinates.boundsInWindow()) },
        ) {
            groups.forEachIndexed { index, group ->
                if (index > 0) {
                    // Unlabelled, because a panel id is not a name and there is nothing truthful
                    // to write here. BossTerm separates its pane clusters the same way. Which
                    // group is live is said by the amber marker on its active tab, not a heading.
                    item(key = "boss-tab-group-rule:${group.panelId}") {
                        Divider(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = GROUP_RULE_PADDING),
                            color = BossTheme.colors.line,
                        )
                    }
                }
                group.state.items(this, null)
            }
        }
    }
}

/** The window-level Favorites shelf, which also carries the bar's one chrome control. */
@Composable
private fun WindowTabBarFavorites(
    lead: TabBarGroup,
    onToggleCollapse: (() -> Unit)?,
    onPin: (() -> Unit)?,
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
    val itemCounts = groups.map { it.state.listItemCount }

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

/**
 * The shared list index the group at [groupIndex] starts at.
 *
 * A rule item precedes every group but the first, and belongs to neither of the two it divides.
 * One definition, because the drop-target partition and the scroll-to-active effect both index
 * the same column and an off-by-one between them is invisible until a drag or a click lands in
 * the wrong place.
 */
internal fun groupStartIndex(
    itemCounts: List<Int>,
    groupIndex: Int,
): Int {
    var cursor = 0
    for (i in 0 until groupIndex) {
        if (i > 0) cursor++
        cursor += itemCounts[i]
    }
    return if (groupIndex > 0) cursor + 1 else cursor
}

/**
 * Where each group's rows actually landed, in window coordinates.
 *
 * Null for a group with nothing laid out - scrolled far enough out of view that the list is not
 * measuring it. That group simply cannot be dropped on, which is the honest answer: it is not on
 * screen to aim at.
 */
internal fun groupSpans(
    info: LazyListLayoutInfo,
    strip: Rect,
    panelIds: List<String>,
    itemCounts: List<Int>,
): List<Pair<String, ClosedFloatingPointRange<Float>?>> =
    panelIds.mapIndexed { index, panelId ->
        val first = groupStartIndex(itemCounts, index)
        val last = first + itemCounts[index] - 1

        val visible = info.visibleItemsInfo.filter { it.index in first..last }
        val span =
            if (visible.isEmpty()) {
                null
            } else {
                val top = strip.top + (visible.minOf { it.offset } - info.viewportStartOffset).toFloat()
                val bottom = strip.top + (visible.maxOf { it.offset + it.size } - info.viewportStartOffset).toFloat()
                top..maxOf(top, bottom)
            }
        panelId to span
    }

/**
 * Carve a shared bar into one rectangle per group, leaving no gap between them.
 *
 * Boundaries sit at the midpoint of the space between two groups, and the first and last groups
 * run to the ends of the bar. The gaps matter: the rule between two groups, and the empty
 * remainder under the last one, are places a tab can be dropped, and every pixel of the bar has
 * to mean something. Extending the ends is also what keeps the single-group case identical to
 * what it was - one group, and its rectangle is the whole bar.
 *
 * Groups with no measured span are left out entirely rather than given an empty rectangle,
 * because [TabDraggableComponent] treats a registered rectangle as a live target.
 */
internal fun splitBarAmongGroups(
    strip: Rect,
    spans: List<Pair<String, ClosedFloatingPointRange<Float>?>>,
): Map<String, Rect> {
    val laid = spans.mapNotNull { (panelId, span) -> span?.let { panelId to it } }
    if (laid.isEmpty()) return emptyMap()

    var top = strip.top
    return laid
        .mapIndexed { index, (panelId, span) ->
            val next = laid.getOrNull(index + 1)?.second
            val bottom =
                if (next == null) {
                    strip.bottom
                } else {
                    ((span.endInclusive + next.start) / 2f).coerceIn(strip.top, strip.bottom)
                }
            val rect = Rect(strip.left, top, strip.right, maxOf(top, bottom))
            top = maxOf(top, bottom)
            panelId to rect
        }.toMap()
}
