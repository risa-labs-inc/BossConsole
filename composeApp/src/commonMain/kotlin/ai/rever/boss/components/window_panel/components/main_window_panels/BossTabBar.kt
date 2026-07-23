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
 * Horizontal padding on each side of the inter-tab VDivider. Referenced by
 * both the divider in BossMainWindowPanel.kt's itemsIndexed block and the
 * width budget below, so the two can't drift apart.
 */
internal val INTER_TAB_DIVIDER_PADDING = 4.dp

/**
 * Width consumed between adjacent tabs by the inter-tab VDivider: padding on
 * both sides plus VDivider's fixed 1.dp line. Must be subtracted from the
 * available width before dividing, otherwise the rendered row is wider than
 * the viewport and the LazyRow scrolls even when every tab is well below
 * [MAX_TAB_WIDTH].
 */
private val INTER_TAB_DIVIDER_WIDTH = INTER_TAB_DIVIDER_PADDING * 2 + 1.dp

/**
 * Per-tab width in INTEGER PIXELS, not Dp. A Dp-space division produces a
 * fractional Dp that Compose rounds to the nearest pixel when it measures
 * each tab. With N tabs the rounding can go up, making total content >
 * [rowWidthPx] by a couple of pixels, which triggers the LazyRow scrollbar
 * even when every tab is far below the max. Adding a tab swings the rounding
 * the other way, so the bar flickers in and out.
 *
 * Integer-pixel division floors naturally, so total tab pixels
 * (result * tabCount + dividersPx) is always ≤ [rowWidthPx], with at most
 * `tabCount - 1` pixels of slack on the right — invisible and, crucially,
 * stable.
 *
 * Two distinct fallbacks:
 * - Not yet measured ([rowWidthPx] ≤ 0) or nothing to lay out
 *   ([tabCount] ≤ 0) → [maxTabPx], the first-paint fallback.
 * - Over-cramped (so many tabs that the dividers alone exceed the row and
 *   the division goes ≤ 0) → the coercion clamps to [minTabPx] and the row
 *   scrolls, same as any other below-floor result.
 *
 * Deliberately NOT budgeted: the 3.dp reorder indicator injected into the
 * row during a tab drag. Including it would resize every tab the moment a
 * drag starts; a transient 3px overflow near the fit boundary mid-drag is
 * the lesser evil.
 */
internal fun computeTabWidthPx(
    rowWidthPx: Int,
    tabCount: Int,
    dividerPx: Int,
    minTabPx: Int,
    maxTabPx: Int
): Int {
    if (rowWidthPx <= 0 || tabCount <= 0) return maxTabPx
    val totalDividersPx = dividerPx * (tabCount - 1)
    return ((rowWidthPx - totalDividersPx) / tabCount).coerceIn(minTabPx, maxTabPx)
}

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
        // All arithmetic lives in computeTabWidthPx (pure, unit-tested);
        // here we only convert the Dp constants to pixels and back.
        val tabWidth = with(density) {
            computeTabWidthPx(
                rowWidthPx = rowWidthPx,
                tabCount = tabCount,
                dividerPx = INTER_TAB_DIVIDER_WIDTH.toPx().toInt(),
                minTabPx = MIN_TAB_WIDTH.roundToPx(),
                maxTabPx = MAX_TAB_WIDTH.roundToPx()
            ).toDp()
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
