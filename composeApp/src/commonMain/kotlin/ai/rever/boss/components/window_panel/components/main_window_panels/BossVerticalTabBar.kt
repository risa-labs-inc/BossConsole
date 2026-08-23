package ai.rever.boss.components.window_panel.components.main_window_panels

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
 * Height of a section header row ("Pinned" / "Open"), which also hosts that section's hover "+".
 */
private val SECTION_HEADER_HEIGHT = 24.dp

/** Height of the Arc-style "New Tab" row pinned to the bottom of the bar. */
private val NEW_TAB_ROW_HEIGHT = 32.dp

/** Diameter of one tab's dot on the collapsed rail. */
private val RAIL_DOT_SIZE = 10.dp

/** Diameter of the ring drawn around the ACTIVE tab's rail dot. */
private val RAIL_ACTIVE_RING_SIZE = 18.dp

/** Hit target around a rail dot. Larger than the dot so the click is not a pixel hunt. */
private val RAIL_DOT_TOUCH_SIZE = 24.dp

/** Vertical gap between rail dots. */
private val RAIL_DOT_GAP = 6.dp

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
 * The `+` is a sibling BELOW the column rather than a trailing item inside it, so it cannot
 * scroll out of reach - the same guarantee [BossLeftTabBar]'s trailing slot provides, reached
 * more simply because nothing here has to reserve space for it.
 *
 * @param listState scroll state, shared with the drag system's edge-scroll callback.
 * @param trailing rendered under the column, outside the scrollable area.
 * @param content the tab rows.
 */
@Composable
fun ColumnScope.BossVerticalTabStrip(
    listState: LazyListState,
    trailing: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier =
            Modifier
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
    trailing()
}

/**
 * One section header in the vertical bar: a quiet label, and a "+" that fades in on hover.
 *
 * Arc draws no label above its pinned block - just a separator line. A label is used here for two
 * reasons: this bar has two sections whose difference is not visually obvious the way a favicon
 * grid is, and the header is what the per-section "+" hangs off. Both are set small, uppercase and
 * muted so they read as chrome rather than as content.
 *
 * The "+" appears on hover rather than always, so the resting bar stays as quiet as Arc's. It is
 * still reachable without hovering: the row at the bottom of the bar is always visible, and it is
 * the same action for the Open section.
 */
@Composable
fun SectionHeader(
    label: String,
    onAdd: () -> Unit,
    addHint: String,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(SECTION_HEADER_HEIGHT)
                .hoverable(interactionSource)
                .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = BossTheme.colors.textSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Space is reserved whether or not the icon is drawn, so the label does not shift
        // sideways as the pointer crosses the row.
        Box(modifier = Modifier.size(SECTION_HEADER_HEIGHT), contentAlignment = Alignment.Center) {
            if (hovered) {
                // The whole 20dp box is the target, not the 14dp glyph inside it: a header "+"
                // that only exists while the pointer is on the row is hard enough to hit without
                // also being a pixel hunt.
                HoverTooltipBox(
                    text = addHint,
                    placement = TooltipPlacement.END,
                    modifier =
                        Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(onClick = onAdd),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = addHint,
                        tint = BossTheme.colors.textSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

/**
 * The line between the pinned block and the open one, plus the Open section's own header.
 *
 * Arc's equivalent is a single thin rule with no label. The label is kept here because this bar
 * carries two named sections rather than a favicon grid and a list, and because it is what the
 * section's "+" hangs off.
 */
@Composable
fun SectionBreak(onAdd: () -> Unit) {
    Divider(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        color = BossTheme.colors.line,
    )
    SectionHeader(label = "OPEN", onAdd = onAdd, addHint = "New tab")
}

/**
 * The always-visible "New Tab" row along the bottom of the bar (Arc and Dia both place it there).
 *
 * Full width and left-aligned rather than a centred square button, so it reads as a row of the
 * list it extends rather than as a floating control - which is the whole difference between this
 * and the "+" the horizontal strip uses, where a row would have nowhere to sit.
 */
@Composable
fun NewTabRow(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(NEW_TAB_ROW_HEIGHT)
                .hoverable(interactionSource)
                .background(if (hovered) BossTheme.colors.raised else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            tint = BossTheme.colors.textSecondary,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = "New Tab",
            color = BossTheme.colors.textSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The collapsed vertical tab bar: an expand chevron, one dot per tab, and the `+`.
 *
 * A rail is not a narrow tab bar - it is a different control with the same job, which is why it
 * is its own composable rather than [BossVerticalTabStrip] under a width. There is no room for
 * a title, so a tab is a dot; identity comes from the tooltip and from the ring on the active
 * one. Ported from BossTerm's `TabBar` collapsed branch.
 *
 * The dots carry the same [contextMenuItems] the full tabs do, so collapsing the bar never
 * takes an action away - only the labels that named them.
 *
 * @param onSelect invoked with the tab's index.
 * @param contextMenuItems per-tab menu, built by the caller exactly as for a full tab row.
 */
@Composable
fun BossTabRail(
    tabs: List<TabInfo>,
    activeIndex: Int,
    pinnedCount: Int,
    onExpand: () -> Unit,
    onNewTab: () -> Unit,
    onSelect: (Int) -> Unit,
    contextMenuItems: (Int) -> List<ContextMenuItem>,
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

        // A plain verticalScroll rather than a LazyColumn: a dot is 24dp of nothing, so
        // virtualising them buys less than the subcomposition costs, and the rail is the case
        // where the whole list is meant to be visible at once.
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(RAIL_DOT_GAP),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            tabs.forEachIndexed { index, tab ->
                val isActive = index == activeIndex
                // The rail keeps the section break too, as a short rule between the two groups
                // of dots. Without it the rail is the one place where pinning is invisible, and
                // the whole point of pinning something is that you can find it again.
                if (pinnedCount in 1 until tabs.size && index == pinnedCount) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.5f)
                            .height(1.dp)
                            .background(colors.line),
                    )
                }
                RailDot(
                    title = tab.title,
                    isActive = isActive,
                    onSelect = { onSelect(index) },
                    contextMenuItems = contextMenuItems(index),
                )
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
    }
}

/**
 * One tab on the collapsed rail: an accent dot, ringed when it is the active tab.
 *
 * The title is the tooltip, which is the rail's only way to name a tab - so it is not decoration
 * here the way it is on a labelled row, and it has to survive being drawn over a browser's native
 * surface. [HoverTooltipBox] is that guarantee; a plain TooltipArea would not be.
 */
@Composable
private fun RailDot(
    title: String,
    isActive: Boolean,
    onSelect: () -> Unit,
    contextMenuItems: List<ContextMenuItem>,
) {
    val colors = BossTheme.colors
    HoverTooltipBox(
        text = title,
        placement = TooltipPlacement.END,
        modifier =
            Modifier
                .size(RAIL_DOT_TOUCH_SIZE)
                .clip(RoundedCornerShape(6.dp))
                .contextMenu(items = contextMenuItems)
                .clickable(onClick = onSelect),
    ) {
        if (isActive) {
            Box(Modifier.size(RAIL_ACTIVE_RING_SIZE).border(1.5.dp, colors.signal, CircleShape))
        }
        Box(
            Modifier
                .size(RAIL_DOT_SIZE)
                .clip(CircleShape)
                .background(if (isActive) colors.signal else colors.textSecondary.copy(alpha = 0.55f)),
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
