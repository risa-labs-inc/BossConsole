package ai.rever.boss.components.window_panel.components.main_window_panels

import ai.rever.boss.components.bars.getBarScrollbarConfig
import ai.rever.boss.components.bars.horizontalLazyListScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Horizontal scrollable tab bar for the left section of the main tab bar.
 *
 * Displays tabs in a horizontally scrollable lazy row with a custom scrollbar indicator.
 * The scrollbar appears at the top of the content when scrolling is available.
 * Supports auto-scrolling to active tabs via the provided LazyListState.
 *
 * @param listState The LazyListState for controlling scroll position
 * @param content Composable content to display in the tab bar (typically tab buttons)
 */
@Composable
fun RowScope.BossLeftTabBar(
    listState: LazyListState,
    content: LazyListScope.() -> Unit
) {
    Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
        LazyRow(
            state = listState,
            modifier = Modifier
                .horizontalLazyListScrollbar(
                    listState = listState,
                    scrollbarConfig = getBarScrollbarConfig()
                ),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}
