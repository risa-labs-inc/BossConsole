package ai.rever.boss.components.window_panel.components.main_window_panels

import ai.rever.boss.components.overlays.HoverTooltipBox
import ai.rever.boss.components.overlays.TooltipPlacement
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.window.MenuActionsHandler
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
 * buttons, no reordering, 24dp instead of 36. Names live in the sidebar, which has room for them.
 * That is also why bringing this back does not undo the one-bar change - what read badly was two
 * 200dp columns of titles, not a row of marks.
 *
 * Only for a window that is actually split. With one pane the sidebar already lists every tab
 * with its name, and this would be the same information twice.
 */
@Composable
internal fun PaneTabStrip(
    tabs: List<TabInfo>,
    activeIndex: Int,
    pinnedCount: Int,
    onSelect: (Int) -> Unit,
    onNewTab: (() -> Unit)?,
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
                .background(BossTheme.colors.panel),
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
 * @param showStrip only once the window is split. The window bar collapses panes the user is not
 *   working in, so without the strip, switching a background pane's tab meant going to the
 *   sidebar, opening that pane's group and reading names. With ONE pane the sidebar already lists
 *   every tab with its name, and this would say the same thing twice.
 */
@Composable
internal fun PaneIndicatedContent(
    tabs: List<TabInfo>,
    activeIndex: Int,
    pinnedCount: Int,
    showStrip: Boolean,
    onSelect: (Int) -> Unit,
    onNewTab: (() -> Unit)?,
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
            )
            Divider(color = BossTheme.colors.line)
        }
        content(Modifier.weight(1f).fillMaxWidth())
    }
}

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
