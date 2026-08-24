package ai.rever.boss.components.window_panel.components.main_window_panels

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.geometry.Rect

/**
 * Turning one shared column back into one rectangle per pane.
 *
 * The drag system asks a bar exactly one question - whose tabs are under the pointer - and
 * answers it from [ai.rever.boss.components.model.TabDraggableComponent.tabBarBounds], one
 * rectangle per panel. With a bar per pane that rectangle was the bar. These are what a
 * window-level bar answers it with instead, and they are pure so the arithmetic is testable
 * rather than only reachable by dragging a tab and watching where it lands.
 */

/**
 * Items each group contributes to the shared column, its own header included.
 *
 * One definition rather than three, because the header exists only when there is more than one
 * group - and a caller that forgot that would index the column one row out for every pane after
 * the first.
 */
internal fun List<TabBarGroup>.listItemCounts(): List<Int> {
    val chrome = groupChromeItems()
    return map { chrome + it.state.listItemCount + it.summaryRows }
}

/** Rows a group draws before its own: its header, and only when the bar holds more than one. */
internal fun List<TabBarGroup>.groupChromeItems(): Int = if (size > 1) 1 else 0

/**
 * The shared list index the group at [groupIndex] starts at.
 *
 * A plain prefix sum, because a group's header is one of ITS items rather than something between
 * two groups - which is also the right answer for dropping a tab on a header. One definition,
 * because the drop-target partition and the scroll-to-active effect both index the same column
 * and an off-by-one between them is invisible until a drag or a click lands in the wrong place.
 *
 * @param itemCounts each group's total item count, its own header included.
 */
internal fun groupStartIndex(
    itemCounts: List<Int>,
    groupIndex: Int,
): Int = itemCounts.take(groupIndex).sum()

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
