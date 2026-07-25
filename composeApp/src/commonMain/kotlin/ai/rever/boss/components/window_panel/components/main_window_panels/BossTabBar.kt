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
import androidx.compose.ui.unit.Density
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

/** The line VDivider draws between two tabs (`Modifier.width(1.dp)` there). */
private val INTER_TAB_DIVIDER_LINE = 1.dp

/**
 * Width consumed between adjacent tabs by the inter-tab VDivider: padding on
 * both sides plus VDivider's fixed line. Must be subtracted from the available
 * width before dividing, otherwise the rendered row is wider than the viewport
 * and the LazyRow scrolls even when every tab is well below [MAX_TAB_WIDTH].
 *
 * Each of the three pieces is rounded to whole pixels SEPARATELY, exactly as
 * Compose measures them — do not sum in Dp space and convert once. A composite
 * `9.dp.toPx().toInt()` truncates where the three rendered pieces round up: at
 * density 1.5 it budgets 13px against a rendered 6 + 6 + 2 = 14px, and at 1.75
 * 15px against 7 + 7 + 2 = 16px. That 1px-per-divider shortfall is ~19px of
 * unbudgeted overflow at 20 tabs — precisely the scrollbar flicker this whole
 * integer-pixel design exists to prevent, and it only shows up at 150%/175%
 * display scaling (i.e. on Windows, not on the 1x/2x Macs we develop on).
 */
private fun Density.interTabDividerPx(): Int {
    val paddingPx = INTER_TAB_DIVIDER_PADDING.roundToPx()
    return paddingPx * 2 + INTER_TAB_DIVIDER_LINE.roundToPx()
}

/**
 * The strip's fixed sizing constants in pixel space: the inter-tab divider
 * (see [interTabDividerPx]) and the [MIN_TAB_WIDTH]/[MAX_TAB_WIDTH] clamp.
 * Converted from Dp at the call site because that needs a density, and bundled
 * rather than passed loose so [computeTabWidthPx] keeps a signature that reads
 * at a glance (and stays under detekt's parameter-count threshold).
 */
internal data class TabStripMetrics(
    val dividerPx: Int,
    val minTabPx: Int,
    val maxTabPx: Int,
)

/** [TabStripMetrics] for the current density. */
internal fun Density.tabStripMetrics(): TabStripMetrics =
    TabStripMetrics(
        dividerPx = interTabDividerPx(),
        minTabPx = MIN_TAB_WIDTH.roundToPx(),
        maxTabPx = MAX_TAB_WIDTH.roundToPx(),
    )

/**
 * Geometry of the "+" button that occupies the strip's trailing slot, kept
 * here rather than at the button's definition so [BossLeftTabBar] can seed its
 * reserve with the right value on the very first frame (see
 * [NEW_TAB_SLOT_WIDTH]).
 *
 * The paddings are part of the *slot*: all spacing around the trailing content
 * must live INSIDE it, never as arrangement or padding on the strip Row, so
 * that the slot's measured width is the whole reserve.
 */
internal val NEW_TAB_BUTTON_SIZE = 32.dp

/** Gap between the last tab and the "+", matching [INTER_TAB_DIVIDER_PADDING]'s rhythm. */
internal val NEW_TAB_BUTTON_GAP = 4.dp

/**
 * Total width the trailing slot occupies once it renders. Only a first-frame
 * seed — the slot's measured width is the authority, so this going stale costs
 * one frame of slightly-too-generous tabs, not a permanent mis-budget.
 */
internal val NEW_TAB_SLOT_WIDTH = NEW_TAB_BUTTON_SIZE + NEW_TAB_BUTTON_GAP * 2

/**
 * Per-tab width in INTEGER PIXELS, not Dp. A Dp-space division produces a
 * fractional Dp that Compose rounds to the nearest pixel when it measures
 * each tab. With N tabs the rounding can go up, making total content >
 * [stripWidthPx] by a couple of pixels, which triggers the LazyRow scrollbar
 * even when every tab is far below the max. Adding a tab swings the rounding
 * the other way, so the bar flickers in and out.
 *
 * Integer-pixel division floors naturally, so total tab pixels
 * (result * tabCount + dividersPx) is always ≤ [stripWidthPx] - [trailingPx],
 * with at most `tabCount - 1` pixels of slack on the right — invisible and,
 * crucially, stable.
 *
 * Two distinct fallbacks:
 * - Not yet measured ([stripWidthPx] ≤ 0) or nothing to lay out
 *   ([tabCount] ≤ 0) → [TabStripMetrics.maxTabPx], the first-paint fallback.
 * - Over-cramped (so many tabs that the dividers alone exceed the row and
 *   the division goes ≤ 0) → the coercion clamps to
 *   [TabStripMetrics.minTabPx] and the row scrolls, same as any other
 *   below-floor result.
 *
 * [trailingPx] is the width of the trailing slot (the "+" button) that shares
 * the strip with the tabs. It is subtracted up front so tabs shrink to fit
 * *beside* the button; without it the last tab would slide under the button
 * and the row would scroll a fraction of a tab early.
 *
 * Deliberately NOT budgeted: the 3.dp reorder indicator injected into the
 * row during a tab drag. Including it would resize every tab the moment a
 * drag starts; a transient 3px overflow near the fit boundary mid-drag is
 * the lesser evil.
 */
internal fun computeTabWidthPx(
    stripWidthPx: Int,
    tabCount: Int,
    trailingPx: Int,
    metrics: TabStripMetrics,
): Int {
    if (stripWidthPx <= 0 || tabCount <= 0) return metrics.maxTabPx
    val totalDividersPx = metrics.dividerPx * (tabCount - 1)
    return ((stripWidthPx - trailingPx - totalDividersPx) / tabCount)
        .coerceIn(metrics.minTabPx, metrics.maxTabPx)
}

/**
 * Horizontal scrollable tab bar for the left section of the main tab bar.
 *
 * Tabs shrink uniformly to fit the available width (Safari behaviour). When they
 * would shrink below [MIN_TAB_WIDTH], the width is clamped and the row scrolls.
 *
 * Implementation note: the available width is captured via [onSizeChanged]
 * on the plain [Row] wrapping the strip, rather than with
 * [BoxWithConstraints]. Both `BoxWithConstraints` and `LazyRow` are
 * `SubcomposeLayout`s; nesting them caused every `tabCount` change to thrash
 * the inner `LazyRow` through `disposeOrReuseStartingFromIndex` and resize
 * the semantics `ScatterMap` on each reuse, pinning the EDT at 100% CPU
 * after ~10 tabs. The trade-off here is one extra frame on first paint
 * before [stripWidthPx] is populated (tabs initially render at
 * [MAX_TAB_WIDTH], then re-measure once); after that, only tab additions
 * trigger a re-measure and there is no nested subcomposition. The trailing
 * reserve has the same shape — measured, therefore one frame behind — which
 * is why [trailingReserve] seeds it: without a seed the first measured frame
 * budgets a slot-width too much, and on a full strip that overflow can flash
 * the scrollbar for a frame before the second measure settles.
 *
 * The [trailing] slot (the "+" button) is laid out immediately after the last
 * tab rather than pinned to the far right of the bar: the strip container
 * takes the full width (so the shrink math sees the real budget) while the
 * [LazyRow] inside it wraps its content, leaving the empty remainder to the
 * *right* of the trailing slot. Once the tabs fill the strip the button ends
 * up flush right anyway, so both regimes read the same.
 *
 * The [LazyRow] must therefore NOT be the node that reports the available
 * width — a wrapping row reports its content width, and feeding that back
 * into the shrink math latches the tabs at their current size (they could
 * never grow again when the window widens). The width is measured on the
 * filling container instead, and the trailing slot's own measured width is
 * subtracted from the budget.
 *
 * The reserve is therefore expressed twice — implicitly by this Row's weight
 * distribution and explicitly as `trailingPx` — and they agree only because
 * both come from the same measured slot. Hence the rule stated on
 * [NEW_TAB_BUTTON_SIZE]: **all spacing around the trailing content lives
 * inside the slot**, never as `Arrangement.spacedBy` on this Row or padding on
 * the wrapper Box, or the explicit reserve silently under-counts and the row
 * scrolls early.
 *
 * A caller that gates its [trailing] content on scroll state (FIXED mode does:
 * "+" moves in-row while everything fits) closes a loop — slot presence
 * depends on scrollability depends on the LazyRow's cap depends on slot width.
 * It settles rather than oscillates. With strip width `W` and content width
 * `C` (the in-row "+" included), no-slot is scrollable iff `C > W`, and
 * with-slot iff `C > W - (slot - button)`; both states are self-consistent
 * across that narrow band, so it is hysteretic, and both render exactly one
 * "+", so the button can never blink out. The old pinned-right "+" took the
 * same width off the weighted container, so this is no new hazard.
 *
 * @param listState The LazyListState for controlling scroll position
 * @param tabCount Number of tabs being rendered; needed to divide the available width
 * @param trailingReserve First-frame seed for the trailing slot's width; the
 *   slot's measured width is the authority, so a stale value costs one frame,
 *   not a permanent mis-budget. Pass 0.dp when [trailing] renders nothing.
 * @param trailing Rendered directly after the last tab, outside the scrollable
 *   row so it can never scroll away. Its measured width is reserved from the
 *   per-tab budget.
 * @param content Receives the computed per-tab width and renders the tab buttons.
 *   Each [content] body should size its tab to the supplied `tabWidth`.
 */
@Composable
fun RowScope.BossLeftTabBar(
    listState: LazyListState,
    tabCount: Int,
    trailingReserve: Dp = 0.dp,
    trailing: @Composable () -> Unit = {},
    content: LazyListScope.(tabWidth: Dp) -> Unit,
) {
    val density = LocalDensity.current
    var stripWidthPx by remember { mutableStateOf(0) }
    var trailingWidthPx by remember { mutableStateOf(with(density) { trailingReserve.roundToPx() }) }

    Row(
        modifier =
            Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .onSizeChanged { size -> stripWidthPx = size.width },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // All arithmetic lives in computeTabWidthPx (pure, unit-tested); here
        // we only convert the Dp constants to pixels and back. Remembered on
        // its four inputs because this bar recomposes on hover, selection,
        // favicon and drop-target changes, none of which move a tab edge.
        val tabWidth =
            remember(density, stripWidthPx, tabCount, trailingWidthPx) {
                with(density) {
                    computeTabWidthPx(
                        stripWidthPx = stripWidthPx,
                        tabCount = tabCount,
                        trailingPx = trailingWidthPx,
                        metrics = tabStripMetrics(),
                    ).toDp()
                }
            }

        LazyRow(
            state = listState,
            // fill = false lets the row wrap its tabs so [trailing] hugs the
            // last one; the weight still caps it at everything the trailing
            // slot doesn't need, so a full strip scrolls exactly as before.
            modifier =
                Modifier
                    .weight(1f, fill = false)
                    .horizontalLazyListScrollbar(
                        listState = listState,
                        scrollbarConfig = getBarScrollbarConfig(),
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content(tabWidth)
        }

        // No padding or arrangement here — see the reserve rule in the KDoc:
        // this Box's measured width must be the entire trailing reserve.
        Box(modifier = Modifier.onSizeChanged { size -> trailingWidthPx = size.width }) {
            trailing()
        }
    }
}
