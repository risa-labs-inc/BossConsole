package ai.rever.boss.components.window_panel.components.main_window_panels

import ai.rever.boss.app.FOCUS_QUICK_ACTION_COUNT
import ai.rever.boss.app.railFitsActions
import ai.rever.boss.components.bars.getPanelScrollbarConfig
import ai.rever.boss.components.bars.lazyListScrollbar
import ai.rever.boss.components.dividers.SDivider
import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.components.overlays.HoverTooltipBox
import ai.rever.boss.components.overlays.TooltipPlacement
import ai.rever.boss.components.overlays.contextMenu
import ai.rever.boss.layout.BossChrome
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Width of the vertical tab bar when collapsed to its slim icon rail.
 *
 * Reads [ChromeDimens.stripWidth] rather than carrying a constant, so it tracks the density
 * preset alongside every other bar. The rail sits directly beside the window's own icon strip in
 * the default layout, and two adjacent vertical strips of different widths read as a mistake
 * rather than a hierarchy - which is the same reason `ChromeMetrics` charges the rail at this
 * width when it budgets a LEFT bar.
 */
val tabBarRailWidth: Dp
    @Composable get() = BossChrome.dimens.stripWidth

/**
 * Panel width below which the vertical bar is FORCED down to the rail, whatever
 * `tabBarCollapsed` says, and the full bar becomes a hover-reveal drawer instead.
 *
 * Keyed on the PANEL, not the window, which is the whole reason this threshold is not simply
 * BossTerm's 700dp: a split can leave one panel at 300dp inside a 2000dp window, and it is that
 * panel whose content a 200dp bar would swallow. The value is a little over three times the bar
 * at its default width, so the content still gets the majority of the panel at the boundary.
 */
val TAB_BAR_AUTO_COLLAPSE_WIDTH = 520.dp

/**
 * Height of one tab row in the vertical bar.
 *
 * Uniform, and there is no equivalent of `TabWidthMode` here: a vertical tab's width is the
 * bar's width, so there is no budget to divide and nothing to shrink. When the rows overflow
 * the bar simply scrolls, which is the behaviour the horizontal strip only reaches after every
 * tab has been squeezed to a favicon.
 */
val VERTICAL_TAB_HEIGHT = 32.dp

/**
 * Side of one tab's favicon on the collapsed rail.
 *
 * Larger than the same chip elsewhere, because on the rail it IS the tab - there is no title
 * beside it to read, so the mark has to carry the whole identity. It still fits the rail's own
 * width with room to spare.
 */
private val RAIL_CHIP_SIZE = 24.dp

/** Vertical gap between rail tabs. */
private val RAIL_ITEM_GAP = 6.dp

/**
 * Size of the chevron / "+" buttons that top and tail the rail, matching
 * [NEW_TAB_BUTTON_SIZE] so the rail's ends line up with the horizontal strip's trailing slot.
 */
private val RAIL_BUTTON_SIZE = NEW_TAB_BUTTON_SIZE

/**
 * The scrollable tab column of the vertical tab bar.
 *
 * The vertical counterpart of [BossLeftTabBar], and deliberately a fraction of its size. That
 * one exists almost entirely to divide a width budget between tabs that must all stay visible;
 * here a tab's width IS the bar's width, so there is no budget, no measured trailing reserve
 * and no integer-pixel rounding to get wrong. What is left is a [LazyColumn] that scrolls.
 *

 * The "New Tab" row is the FIRST item of this list rather than a slot beneath it, directly under
 * the bar's header - which is where Arc puts it, and the one row whose position should not depend
 * on how many tabs there are. That is also why this takes no trailing slot: the horizontal strip
 * needs one because its "+" must survive the row scrolling sideways, and here nothing scrolls
 * away from the top.
 *
 * @param listState scroll state, shared with the drag system's edge-scroll callback. A window-level
 *   bar passes one state for several panes' rows, which is what makes them one column.
 * @param content the tab rows, and for a window bar the rules between one pane's and the next's.
 */
@Composable
fun ColumnScope.BossVerticalTabStrip(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier =
            modifier
                .weight(1f)
                .fillMaxWidth()
                .lazyListScrollbar(
                    listState = listState,
                    direction = Orientation.Vertical,
                    config = getPanelScrollbarConfig(),
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/**
 * The collapsed vertical tab bar: an expand chevron, one favicon per tab, and the `+`.
 *
 * A rail is not a narrow tab bar - it is a different control with the same job, which is why it
 * is its own composable rather than [BossVerticalTabStrip] under a width. There is no room for
 * a title, so a tab is its favicon - the one mark that names a page without words, and the same
 * mark the expanded bar and each pane's own strip already use. A tab with no favicon falls back
 * to its type icon and then to a dot, so the rail always has one mark per tab. The tooltip is
 * what spells the title out. Ported from BossTerm's `TabBar` collapsed branch, which used a plain
 * accent dot - recognisable as "a tab", but not as WHICH tab.
 *
 * They carry the same context menus the full tabs do, so collapsing the bar never takes an
 * action away - only the labels that named them.
 *
 * Every pane's tabs are here, in pane order, divided by the same rule the expanded bar uses. A
 * collapsed bar that showed only the active pane would be the duplicate-bar problem again, in
 * miniature: the user would have to activate a pane to find out what was in it.
 *
 * @param groups one per pane, from `rememberWindowTabGroups`.
 * @param onNewTab the "+" at the foot, which opens a tab in the pane that owns the bar's chrome.
 * @param belowTabs window chrome for the very bottom of the rail, under the "+". The host's own
 *   actions - Sign Out, Settings, Tools, Search - land here when nothing else is left to hold
 *   them, which is the whole reason a collapsed bar no longer sends them to a floating overlay.
 *   See `focusQuickActionsPlacement`. Renders nothing when there is nothing to put there.
 */
@Composable
fun BossTabRail(
    groups: List<TabBarGroup>,
    onExpand: () -> Unit,
    onNewTab: () -> Unit,
    belowTabs: @Composable () -> Unit = {},
    onRailFitsActionsChange: (Boolean) -> Unit = {},
) {
    val colors = BossTheme.colors
    Column(
        modifier = Modifier.fillMaxSize().padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RailIconButton(
            icon = Icons.Default.ChevronRight,
            contentDescription = "Expand tab bar",
            onClick = onExpand,
        )
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth(0.6f).height(1.dp).background(colors.line))
        Spacer(Modifier.height(6.dp))

        // A plain verticalScroll rather than a LazyColumn: a rail tab is 24dp of icon, so
        // virtualising them buys less than the subcomposition costs, and the rail is the case
        // where the whole list is meant to be visible at once.
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(RAIL_ITEM_GAP),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            groups.forEachIndexed { groupIndex, group ->
                // The rule between panes is wider than the one between a pane's pinned and open
                // tabs, so the two divisions cannot be mistaken for each other at this size.
                if (groupIndex > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.7f)
                            .height(1.dp)
                            .background(colors.line),
                    )
                }
                RailGroupDots(group = group, showPinnedRule = groups.size == 1)
            }
        }

        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth(0.6f).height(1.dp).background(colors.line))
        Spacer(Modifier.height(4.dp))
        RailIconButton(
            icon = Icons.Default.Add,
            contentDescription = "New Tab",
            onClick = onNewTab,
        )
        // Below the "+", not above it: that button belongs to the tabs this rail is a list of,
        // and these belong to the app. The slot draws its own rule, so a rail with nothing to put
        // here ends at the "+" exactly as it always did.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val fits =
                railFitsActions(
                    availableHeight = maxHeight,
                    actionCount = FOCUS_QUICK_ACTION_COUNT,
                )
            val report by rememberUpdatedState(onRailFitsActionsChange)
            LaunchedEffect(fits) { report(fits) }
            belowTabs()
        }
    }
}

/**
 * One pane's tabs on the rail.
 *
 * @param showPinnedRule whether to keep the short rule between this pane's pinned and open tabs.
 *   Dropped once the rail holds several panes, for the same reason the expanded bar drops its
 *   section headers there: two kinds of divider at this size read as one arbitrary one.
 */
@Composable
private fun RailGroupDots(
    group: TabBarGroup,
    showPinnedRule: Boolean,
) {
    val colors = BossTheme.colors
    val tabs = group.state.tabs
    val pinnedCount = group.state.pinnedCount
    tabs.forEachIndexed { index, tab ->
        // Guarded rather than indexed blindly: the rail renders from the same snapshot it was
        // passed, but a menu is built on a later frame and a tab can close in between.
        val menuItems = tabs.getOrNull(index)?.let { group.state.tabMenuItems(index, it) }.orEmpty()

        // The rail keeps the section break too, as a short rule between the two groups.
        // Without it the rail is the one place where pinning is invisible, and the whole point of
        // pinning something is that you can find it again.
        if (showPinnedRule && pinnedCount in 1 until tabs.size && index == pinnedCount) {
            Box(
                Modifier
                    .fillMaxWidth(0.5f)
                    .height(1.dp)
                    .background(colors.line),
            )
        }
        TabFaviconChip(
            tab = tab,
            // Marked only in the pane the user is working in: with several panes on the rail, one
            // mark per pane would be several claims to be the current tab.
            isActive = index == group.state.activeIndex && group.isActive,
            onClick = { group.state.activateTab(index) },
            size = RAIL_CHIP_SIZE,
            // The rail's contract: taking the labels away must not take the actions with them.
            contextMenuItems = menuItems,
        )
    }
}

/**
 * The chevron that collapses the FULL vertical bar back down to the rail.
 *
 * Lives here rather than at its call site so the rail's expand chevron and this one are the
 * same button pointing opposite ways, and cannot drift in size or padding.
 */
@Composable
fun BossTabBarCollapseButton(onCollapse: () -> Unit) {
    RailIconButton(
        icon = Icons.Default.ChevronLeft,
        contentDescription = "Collapse tab bar",
        onClick = onCollapse,
    )
}

/**
 * The pin a hover-revealed drawer offers in place of the collapse chevron.
 *
 * A drawer that exists only because the pointer is resting here is not a bar yet, and offering to
 * "collapse" it is offering to undo something the user did not do - the pointer leaving already
 * does that. Pinning is the action that IS missing: it turns the reveal into the real bar, so
 * whatever you came here to do survives moving the mouse. Ported from BossTerm's `onPin`.
 */
@Composable
fun BossTabBarPinButton(onPin: () -> Unit) {
    RailIconButton(
        icon = Icons.Default.PushPin,
        contentDescription = "Keep sidebar open",
        onClick = onPin,
    )
}

/** A rail-sized icon button, styled like [NewTabButton] so the two read as one family. */
@Composable
private fun RailIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(RAIL_BUTTON_SIZE)
                .padding(4.dp)
                .background(
                    color = BossTheme.colors.raised,
                    shape = RoundedCornerShape(4.dp),
                ).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = BossTheme.colors.textSecondary,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * The 3dp line showing where a dragged tab would land.
 *
 * It runs ACROSS the tab order, which is the whole of what changes between orientations: a
 * vertical line standing between two side-by-side tabs, a horizontal one lying between two
 * stacked ones. Drawn at the same 3dp either way, and deliberately NOT budgeted for by the
 * horizontal strip's width math - see the closing note on `computeTabWidthPx`.
 */
@Composable
fun ReorderIndicator(vertical: Boolean) {
    Box(
        modifier =
            Modifier
                .then(
                    if (vertical) {
                        Modifier.fillMaxWidth().height(3.dp).padding(horizontal = 8.dp)
                    } else {
                        Modifier.width(3.dp).fillMaxHeight().padding(vertical = 8.dp)
                    },
                ).background(BossTheme.colors.signal),
    )
}

/** Width the vertical bar occupies right now: the rail when collapsed, else the set width. */
@Composable
fun verticalTabBarWidth(
    collapsed: Boolean,
    width: Dp,
): Dp = if (collapsed) tabBarRailWidth else width
