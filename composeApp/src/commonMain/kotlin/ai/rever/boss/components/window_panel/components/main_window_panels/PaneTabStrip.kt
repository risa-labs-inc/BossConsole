package ai.rever.boss.components.window_panel.components.main_window_panels

import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.Divider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Height of the strip. Deliberately close to the chip it holds: this is an indicator, not a bar. */
private val PANE_STRIP_HEIGHT = 24.dp

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
    onSelect: (Int) -> Unit,
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
            TabFaviconChip(
                tab = tab,
                isActive = index == activeIndex,
                onClick = { onSelect(index) },
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
    showStrip: Boolean,
    onSelect: (Int) -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (showStrip) {
            PaneTabStrip(tabs = tabs, activeIndex = activeIndex, onSelect = onSelect)
            Divider(color = BossTheme.colors.line)
        }
        content(Modifier.weight(1f).fillMaxWidth())
    }
}
