package ai.rever.boss.components.window_panel.components.main_window_panels

import ai.rever.boss.components.model.TabDraggableComponent
import ai.rever.boss.components.model.TabDropResult
import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.components.overlays.HoverTooltipBox
import ai.rever.boss.components.overlays.TooltipPlacement
import ai.rever.boss.components.overlays.contextMenu
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.window.MenuActionsHandler
import ai.rever.boss.window.WindowAppearanceSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Height of the strip. Deliberately close to the chip it holds: this is an indicator, not a bar. */
private val PANE_STRIP_HEIGHT = 24.dp

/** The short rule dividing a pane's pinned tabs from the rest. Shorter than a chip, so it reads
 * as a divider between them rather than as one more thing in the row. */
private val PINNED_RULE_HEIGHT = 14.dp

/**
 * Every tab in one pane, as favicons, across the top of that pane.
 *
 * With the window bar collapsing panes the user is not working in, switching a background pane's
 * tab meant going to the sidebar, opening that pane's group, and reading names. This puts the
 * pane's own tabs where the pane is.
 *
 * It is an indicator rather than a tab bar, and the difference is the point: no titles, no close
 * buttons, no reorder indicator of its own, 24dp instead of 36. Names live in the sidebar, which
 * has room for them. That is also why bringing this back does not undo the one-bar change - what
 * read badly was two 200dp columns of titles, not a row of marks.
 *
 * A tab can be DRAGGED out of it, though. It cannot be dropped back into it: the chips register no
 * bounds, so every landing place is still the bar's rows or a pane's own drop zones. See
 * TabFaviconChip for why a second surface must not register bounds for tabs the bar already has.
 *
 * Its own empty space carries the same menu the vertical bar's does, including the entry that
 * hides this strip - see [rememberBarMenuItems]. That menu is on the ROW, so it opens only where
 * no chip is: a chip consumes the press for its own menu, and the row checks for that before
 * opening a second one on top of it.
 *
 * For a split window by default, and for an unsplit one too if asked. Whether the pane count
 * gates it is a setting rather than a rule - see `WindowAppearanceSettings.paneTabStripOnlyWhenSplit`.
 */
@Composable
internal fun PaneTabStrip(
    tabs: List<TabInfo>,
    activeIndex: Int,
    pinnedCount: Int,
    onSelect: (Int) -> Unit,
    onNewTab: (() -> Unit)?,
    /**
     * The tab's own right-click menu - the same one its row in the sidebar carries.
     *
     * This strip exists so a pane's tabs can be reached without going to the sidebar. Without the
     * menu it sends you back there for pin, split, bookmark and close, which is exactly the trip
     * it was meant to save.
     */
    tabMenuItems: (Int, TabInfo) -> List<ContextMenuItem>,
    /**
     * The drag system, so a tab can be picked up from the strip.
     *
     * The strip is where a pane's tabs are while the bar has that pane collapsed, so without this
     * the only way to move one is to open that pane's group in the sidebar first - the trip the
     * strip exists to save.
     */
    tabDragComponent: TabDraggableComponent?,
    /** This pane, for the drag. */
    panelId: String?,
    /** The drop, once the pointer is released. */
    onTabDropResult: (TabDropResult) -> Unit,
    /**
     * The strip's own right-click menu, for the space between the chips and the end of the row.
     *
     * The same menu the vertical bar offers on its empty space, so the two surfaces cannot come
     * to mean different things, and the way to hide this strip is on the strip itself.
     */
    menuItems: List<ContextMenuItem>,
) {
    if (tabs.isEmpty()) return

    val listState = rememberLazyListState()

    // Keep the current tab in view. A pane narrow enough to scroll this strip is exactly the one
    // where the current tab can end up off the end of it.
    LaunchedEffect(activeIndex) {
        if (activeIndex in tabs.indices) listState.scrollToItem(activeIndex)
    }

    LazyRow(
        state = listState,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(PANE_STRIP_HEIGHT)
                .background(BossTheme.colors.panel)
                .contextMenu(items = menuItems),
        contentPadding = PaddingValues(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(
            items = tabs.withIndex().toList(),
            key = { (_, tab) -> tab.id },
        ) { (index, tab) ->
            // The rule between the pinned block and the rest, exactly as the rail draws it. A
            // chip has no room for a pin glyph of its own, so here "pinned" means "before the
            // rule" - the same thing it means on the rail and under the sidebar's PINNED heading.
            val opensUnpinnedBlock = pinnedCount in 1 until tabs.size && index == pinnedCount
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (opensUnpinnedBlock) {
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(PINNED_RULE_HEIGHT)
                            .background(BossTheme.colors.line),
                    )
                }
                TabFaviconChip(
                    tab = tab,
                    isActive = index == activeIndex,
                    onClick = { onSelect(index) },
                    contextMenuItems = tabMenuItems(index, tab),
                    tabDragComponent = tabDragComponent,
                    panelId = panelId,
                    tabIndex = index,
                    onDragEnd = { result -> result?.let(onTabDropResult) },
                )
            }
        }

        // Inside the scrolling row, straight after the last tab, rather than pinned to the end of
        // the strip. This row holds a handful of icons and rarely scrolls at all; where it does,
        // a "+" that travels with the tabs is where the eye already is, and one welded to the
        // right edge would sit over them.
        if (onNewTab != null) {
            item(key = "pane-strip-new-tab") {
                NewTabChip(onClick = onNewTab)
            }
        }
    }
}

/** The "+" at the end of a pane's strip. Opens a tab in THAT pane, whichever one it belongs to. */
@Composable
private fun NewTabChip(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val colors = BossTheme.colors

    HoverTooltipBox(
        text = "New tab in this pane",
        placement = TooltipPlacement.END,
        modifier = Modifier.size(FAVICON_CHIP_SIZE),
    ) {
        Box(
            modifier =
                Modifier
                    .size(FAVICON_CHIP_SIZE)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (hovered) colors.raised else Color.Transparent)
                    .hoverable(interactionSource)
                    .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "New tab in this pane",
                tint = if (hovered) colors.textPrimary else colors.textSecondary,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/**
 * A panel's content, under the favicon strip that stands in for its tab bar.
 *
 * @param showStrip the Settings toggle, and nothing else. See [PaneTabStrip] for why the pane
 *   count is no longer part of the answer.
 */
@Composable
internal fun PaneIndicatedContent(
    tabs: List<TabInfo>,
    activeIndex: Int,
    pinnedCount: Int,
    showStrip: Boolean,
    onSelect: (Int) -> Unit,
    onNewTab: (() -> Unit)?,
    tabMenuItems: (Int, TabInfo) -> List<ContextMenuItem>,
    tabDragComponent: TabDraggableComponent?,
    panelId: String?,
    onTabDropResult: (TabDropResult) -> Unit,
    /** The strip's own right-click menu. See [PaneTabStrip]. */
    menuItems: List<ContextMenuItem>,
    content: @Composable (Modifier) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (showStrip) {
            PaneTabStrip(
                tabs = tabs,
                activeIndex = activeIndex,
                pinnedCount = pinnedCount,
                onSelect = onSelect,
                onNewTab = onNewTab,
                tabMenuItems = tabMenuItems,
                tabDragComponent = tabDragComponent,
                panelId = panelId,
                onTabDropResult = onTabDropResult,
                menuItems = menuItems,
            )
            Divider(color = BossTheme.colors.line)
        }
        content(Modifier.weight(1f).fillMaxWidth())
    }
}

/**
 * Whether a pane draws its favicon strip, given how many panes the window has.
 *
 * The pane count is a preference rather than a rule - see
 * [WindowAppearanceSettings.paneTabStripOnlyWhenSplit] for why it stopped being one. A named
 * predicate rather than an expression at the call site, because that call site is already at
 * detekt's complexity ceiling and because this is worth being able to test on its own.
 */
internal fun WindowAppearanceSettings.stripShownFor(paneCount: Int?): Boolean =
    showPaneTabStrip && (!paneTabStripOnlyWhenSplit || (paneCount ?: 1) > 1)

/**
 * What a pane's "+" does, or null when there is no window to ask.
 *
 * Activates the pane and then asks the WINDOW for a tab. The window's new-tab dialog adds to
 * whichever panel is active (see BossAppDialogs), so making this pane active first is what aims
 * it - and it reuses the one dialog the menu bar, the keyboard shortcut and the sidebar's "+"
 * already go through. A second copy of that tab-type flow inside the panel would be a second
 * thing to keep in step with every new tab type.
 */
internal fun paneNewTabAction(
    windowId: String?,
    panelId: String?,
    splitViewState: SplitViewState?,
): (() -> Unit)? {
    if (windowId == null) return null
    return {
        if (panelId != null) splitViewState?.setActivePanel(panelId)
        MenuActionsHandler.triggerNewTab(windowId)
    }
}
