package ai.rever.boss.components.window_panel

import ai.rever.boss.components.window_panel.components.main_window_panels.groupSpans
import ai.rever.boss.components.window_panel.components.main_window_panels.groupStartIndex
import ai.rever.boss.components.window_panel.components.main_window_panels.splitBarAmongGroups
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How one bar is divided between several panes.
 *
 * The window-level vertical bar lists every pane's tabs in one column, but the drag system asks a
 * bar exactly one question - "whose tabs are under the pointer" - and answers it from one
 * rectangle per panel. These two functions are what turn a shared column back into that answer,
 * and getting them wrong is invisible until a drag lands in the wrong pane.
 */
class WindowTabBarGroupsTest {
    private val strip = Rect(left = 0f, top = 100f, right = 200f, bottom = 900f)

    private fun item(
        index: Int,
        offset: Int,
        size: Int = 32,
    ): LazyListItemInfo =
        object : LazyListItemInfo {
            override val index: Int = index
            override val key: Any = "item-$index"
            override val offset: Int = offset
            override val size: Int = size
        }

    private fun layout(
        items: List<LazyListItemInfo>,
        viewportStart: Int = 0,
    ): LazyListLayoutInfo =
        object : LazyListLayoutInfo {
            override val visibleItemsInfo: List<LazyListItemInfo> = items
            override val viewportStartOffset: Int = viewportStart
            override val viewportEndOffset: Int = viewportStart + 800
            override val totalItemsCount: Int = items.size
            override val viewportSize: IntSize = IntSize(200, 800)
            override val orientation: Orientation = Orientation.Vertical
            override val reverseLayout: Boolean = false
            override val beforeContentPadding: Int = 0
            override val afterContentPadding: Int = 0
            override val mainAxisItemSpacing: Int = 0
        }

    // ---- groupStartIndex: where a pane's rows begin in the shared column ----

    @Test
    fun `the first group starts at the top of the list`() {
        assertEquals(0, groupStartIndex(listOf(4, 5), 0))
    }

    @Test
    fun `later groups start after everything the earlier ones own`() {
        // Counts include each group's own header, so this is a plain prefix sum.
        assertEquals(4, groupStartIndex(listOf(4, 5), 1))
        assertEquals(9, groupStartIndex(listOf(4, 5, 3), 2))
    }

    @Test
    fun `a group with only its header still takes a row`() {
        assertEquals(1, groupStartIndex(listOf(1, 2), 1))
    }

    @Test
    fun `the start index agrees with the span the same group is measured over`() {
        // The two callers - the drop-target partition and the scroll-to-active effect - must
        // index the same column, and an off-by-one between them is invisible until a click or a
        // drag lands in the wrong pane.
        val counts = listOf(4, 4)
        val info = layout((0..7).map { item(it, offset = it * 32) })
        val spans = groupSpans(info, strip, listOf("p1", "p2"), counts)
        val secondGroupTop = strip.top + groupStartIndex(counts, 1) * 32f
        assertEquals(secondGroupTop, spans[1].second?.start)
    }

    // ---- groupSpans: which list indices belong to which pane ----------------

    @Test
    fun `one group owns the whole list and draws no header`() {
        // A lone group has no header: 1 New Tab row + 2 tabs.
        val info = layout((0..2).map { item(it, offset = it * 32) })
        val spans = groupSpans(info, strip, listOf("p1"), listOf(3))

        assertEquals(listOf("p1"), spans.map { it.first })
        assertEquals(100f..196f, spans[0].second)
    }

    @Test
    fun `two groups own contiguous, non-overlapping ranges`() {
        // p1 owns indices 0..3 (its header and 3 rows), p2 owns 4..7.
        val info = layout((0..7).map { item(it, offset = it * 32) })
        val spans = groupSpans(info, strip, listOf("p1", "p2"), listOf(4, 4))

        assertEquals(100f..228f, spans[0].second)
        assertEquals(100f + 128f..100f + 256f, spans[1].second)
    }

    @Test
    fun `a group scrolled out of the list has no span at all`() {
        // Only p2's items are laid out; p1 has scrolled off the top.
        val info = layout((4..7).map { item(it, offset = (it - 4) * 32) }, viewportStart = 0)
        val spans = groupSpans(info, strip, listOf("p1", "p2"), listOf(4, 4))

        assertEquals(null, spans[0].second)
        assertEquals(100f..228f, spans[1].second)
    }

    @Test
    fun `spans are measured from the strip, not from the window origin`() {
        val info = layout(listOf(item(0, offset = 0)), viewportStart = 0)
        val lower = Rect(left = 0f, top = 500f, right = 200f, bottom = 900f)
        assertEquals(500f..532f, groupSpans(info, lower, listOf("p1"), listOf(1))[0].second)
    }

    @Test
    fun `a negative offset is a partly scrolled item, not a negative position`() {
        // The first item is half above the viewport: offset -16 against viewportStart 0.
        val info = layout(listOf(item(0, offset = -16), item(1, offset = 16)))
        val spans = groupSpans(info, strip, listOf("p1"), listOf(2))
        assertEquals(84f..148f, spans[0].second)
    }

    // ---- splitBarAmongGroups: turning spans into drop targets ---------------

    @Test
    fun `a single group gets the whole bar`() {
        val rects = splitBarAmongGroups(strip, listOf("p1" to 200f..300f))
        assertEquals(mapOf("p1" to strip), rects)
    }

    @Test
    fun `two groups partition the bar with no gap and no overlap`() {
        val rects = splitBarAmongGroups(strip, listOf("p1" to 120f..200f, "p2" to 240f..400f))

        val p1 = rects.getValue("p1")
        val p2 = rects.getValue("p2")
        // The boundary is the midpoint of the space between them: (200 + 240) / 2.
        assertEquals(220f, p1.bottom)
        assertEquals(220f, p2.top)
        // The ends run to the bar's own ends, so the rule above the first group and the empty
        // remainder below the last one both belong to somebody.
        assertEquals(strip.top, p1.top)
        assertEquals(strip.bottom, p2.bottom)
        assertEquals(strip.width, p1.width)
    }

    @Test
    fun `groups with no span are left out rather than given an empty rectangle`() {
        // TabDraggableComponent treats any registered rectangle as a live target, and an empty
        // one at the origin would answer for a pane that is not on screen.
        val rects = splitBarAmongGroups(strip, listOf("p1" to null, "p2" to 300f..400f))

        assertEquals(setOf("p2"), rects.keys)
        assertEquals(strip, rects.getValue("p2"))
    }

    @Test
    fun `nothing laid out registers nothing`() {
        assertTrue(splitBarAmongGroups(strip, listOf("p1" to null, "p2" to null)).isEmpty())
    }

    @Test
    fun `three groups stay in order and stay inside the bar`() {
        val rects =
            splitBarAmongGroups(
                strip,
                listOf("p1" to 110f..200f, "p2" to 260f..400f, "p3" to 460f..600f),
            )

        val ordered = listOf("p1", "p2", "p3").map { rects.getValue(it) }
        ordered.zipWithNext { a, b -> assertEquals(a.bottom, b.top) }
        assertTrue(ordered.all { it.top >= strip.top && it.bottom <= strip.bottom })
    }

    @Test
    fun `overlapping spans still produce forward-only boundaries`() {
        // A measurement race can hand back spans that overlap. A boundary that went backwards
        // would make one rectangle inverted, and every `contains` against it silently false.
        val rects = splitBarAmongGroups(strip, listOf("p1" to 300f..500f, "p2" to 200f..400f))

        val p1 = rects.getValue("p1")
        val p2 = rects.getValue("p2")
        assertTrue(p1.bottom >= p1.top)
        assertTrue(p2.bottom >= p2.top)
        assertEquals(p1.bottom, p2.top)
    }
}
