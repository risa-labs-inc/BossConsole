package ai.rever.boss.components.window_panel.components.main_window_panels

import ai.rever.boss.components.bars.getBarScrollbarConfig
import ai.rever.boss.components.bars.horizontalLazyListScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Smallest a tab is allowed to shrink to. At this width only the favicon
 * remains visible; the title ellipses to nothing and the close button only
 * shows on hover/select (per BossTabButton's `isSelected || isHovered`
 * gate). Matches Safari's shrink floor.
 */
private val MIN_TAB_WIDTH = 36.dp

/** Largest a tab will grow to even with plenty of room. Matches Safari's default. */
private val MAX_TAB_WIDTH = 240.dp

/**
 * Width consumed between adjacent tabs by the inter-tab VDivider
 * (Modifier.padding(horizontal = 4.dp) + the 1.dp line itself). Must be
 * subtracted from the available width before dividing, otherwise the rendered
 * row is wider than the viewport and the LazyRow scrolls even when every tab
 * is well below [MAX_TAB_WIDTH].
 *
 * Keep in sync with the VDivider in BossMainWindowPanel.kt's itemsIndexed block.
 */
private val INTER_TAB_DIVIDER_WIDTH = 9.dp

/**
 * Horizontal scrollable tab bar for the left section of the main tab bar.
 *
 * Tabs shrink uniformly to fit the available width (Safari behaviour). When they
 * would shrink below [MIN_TAB_WIDTH], the width is clamped and the row scrolls.
 *
 * Implementation note: the available width is captured via [onSizeChanged]
 * on the [LazyRow] itself, rather than wrapping the row in
 * [BoxWithConstraints]. Both `BoxWithConstraints` and `LazyRow` are
 * `SubcomposeLayout`s; nesting them caused every `tabCount` change to thrash
 * the inner `LazyRow` through `disposeOrReuseStartingFromIndex` and resize
 * the semantics `ScatterMap` on each reuse, pinning the EDT at 100% CPU
 * after ~10 tabs. The trade-off here is one extra frame on first paint
 * before [rowWidthPx] is populated (tabs initially render at
 * [MAX_TAB_WIDTH], then re-measure once); after that, only tab additions
 * trigger a re-measure and there is no nested subcomposition.
 *
 * @param listState The LazyListState for controlling scroll position
 * @param tabCount Number of tabs being rendered; needed to divide the available width
 * @param content Receives the computed per-tab width and renders the tab buttons.
 *   Each [content] body should size its tab to the supplied `tabWidth`.
 */
@Composable
fun RowScope.BossLeftTabBar(
    listState: LazyListState,
    tabCount: Int,
    content: LazyListScope.(tabWidth: Dp) -> Unit
) {
    var rowWidthPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current

    Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
        // Compute tab width in INTEGER PIXELS, not Dp. A Dp-space division
        // produces a fractional Dp that Compose rounds to the nearest pixel
        // when it measures each tab. With N tabs the rounding can go up,
        // making total content > rowWidthPx by a couple of pixels, which
        // triggers the LazyRow scrollbar even when every tab is far below
        // MAX_TAB_WIDTH. Adding a tab swings the rounding the other way, so
        // the bar flickers in and out.
        //
        // Integer-pixel division floors naturally, so total tab pixels
        // (perTabPx * tabCount + dividersPx) is always ≤ rowWidthPx, with
        // at most `tabCount - 1` pixels of slack on the right — invisible
        // and, crucially, stable.
        val dividerPx = with(density) { INTER_TAB_DIVIDER_WIDTH.toPx().toInt() }
        val totalDividersPx = if (tabCount > 1) dividerPx * (tabCount - 1) else 0
        val perTabPx = if (tabCount > 0 && rowWidthPx > 0) {
            (rowWidthPx - totalDividersPx) / tabCount
        } else {
            -1
        }
        val tabWidth = if (perTabPx >= 0) {
            with(density) { perTabPx.toDp() }.coerceIn(MIN_TAB_WIDTH, MAX_TAB_WIDTH)
        } else {
            MAX_TAB_WIDTH
        }

        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { size -> rowWidthPx = size.width }
                .horizontalLazyListScrollbar(
                    listState = listState,
                    scrollbarConfig = getBarScrollbarConfig()
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            content(tabWidth)
        }
    }
}
