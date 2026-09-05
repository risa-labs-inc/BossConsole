package ai.rever.boss.components.window_panel

import ai.rever.boss.components.window_panel.components.main_window_panels.paneAt
import ai.rever.boss.components.window_panel.components.main_window_panels.paneGlyphFor
import ai.rever.boss.components.window_panel.components.main_window_panels.paneGlyphs
import ai.rever.boss.components.window_panel.components.main_window_panels.paneLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Saying which pane a group of tabs belongs to.
 *
 * The rule between two groups says they are different panes and nothing more, which left the
 * reader opening tabs to find out which was which. Both of these read the panes' MEASURED
 * rectangles rather than the split tree, so they stay true for a nested arrangement and follow a
 * divider as it is dragged.
 */
class PaneGlyphTest {
    private fun bounds(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
    ) = PanelBounds(x = x, y = y, width = w, height = h)

    private val leftPane = bounds(0f, 0f, 500f, 800f)
    private val rightPane = bounds(500f, 0f, 500f, 800f)
    private val topPane = bounds(0f, 0f, 1000f, 400f)
    private val bottomPane = bounds(0f, 400f, 1000f, 400f)

    @Test
    fun `a side-by-side split halves the glyph`() {
        val all = listOf(leftPane, rightPane)
        val left = paneGlyphFor(leftPane, all)!!
        val right = paneGlyphFor(rightPane, all)!!

        assertEquals(0f, left.left)
        assertEquals(0.5f, left.right)
        assertEquals(0.5f, right.left)
        assertEquals(1f, right.right)
        // Both run the full height, which is what makes them Left and Right rather than numbered.
        assertEquals(0f, left.top)
        assertEquals(1f, left.bottom)
    }

    @Test
    fun `the glyph is measured against the panes, not the screen`() {
        // The split area does not start at the window origin - a vertical tab bar is to its left.
        val all = listOf(bounds(200f, 100f, 400f, 800f), bounds(600f, 100f, 400f, 800f))
        val first = paneGlyphFor(all[0], all)!!
        assertEquals(0f, first.left)
        assertEquals(0f, first.top)
        assertEquals(0.5f, first.right)
    }

    @Test
    fun `an unmeasured layout produces no glyph rather than a garbage one`() {
        val degenerate = listOf(bounds(0f, 0f, 0f, 0f))
        assertNull(paneGlyphFor(degenerate[0], degenerate))
        assertNull(paneGlyphFor(leftPane, emptyList()))
    }

    @Test
    fun `a lone pane fills its own glyph`() {
        val glyph = paneGlyphFor(leftPane, listOf(leftPane))!!
        assertEquals(0f, glyph.left)
        assertEquals(1f, glyph.right)
        assertEquals(1f, glyph.bottom)
    }

    @Test
    fun `side-by-side panes are named Left and Right`() {
        val all = listOf(leftPane, rightPane)
        assertEquals("Left", paneLabel(0, paneGlyphFor(leftPane, all)))
        assertEquals("Right", paneLabel(1, paneGlyphFor(rightPane, all)))
    }

    @Test
    fun `stacked panes are named Top and Bottom`() {
        val all = listOf(topPane, bottomPane)
        assertEquals("Top", paneLabel(0, paneGlyphFor(topPane, all)))
        assertEquals("Bottom", paneLabel(1, paneGlyphFor(bottomPane, all)))
    }

    @Test
    fun `a pane in a nested split is named by its corner`() {
        // Left half full height; right half split into two stacked quarters. Neither of those is
        // "Right" or "Top" - each spans one axis short - but each does sit in a corner.
        val all =
            listOf(
                bounds(0f, 0f, 500f, 800f),
                bounds(500f, 0f, 500f, 400f),
                bounds(500f, 400f, 500f, 400f),
            )
        assertEquals("Left", paneLabel(0, paneGlyphFor(all[0], all)))
        assertEquals("Top right", paneLabel(1, paneGlyphFor(all[1], all)))
        assertEquals("Bottom right", paneLabel(2, paneGlyphFor(all[2], all)))
    }

    @Test
    fun `a pane touching no useful edge gets a number, not an invented name`() {
        // Three side by side: the middle one is full height but neither left nor right, so no
        // word describes it and the glyph beside the number is what actually locates it.
        val all =
            listOf(
                bounds(0f, 0f, 300f, 800f),
                bounds(300f, 0f, 400f, 800f),
                bounds(700f, 0f, 300f, 800f),
            )
        assertEquals("Left", paneLabel(0, paneGlyphFor(all[0], all)))
        assertEquals("Pane 2", paneLabel(1, paneGlyphFor(all[1], all)))
        assertEquals("Right", paneLabel(2, paneGlyphFor(all[2], all)))
    }

    @Test
    fun `a pane nested two deep is numbered`() {
        // Left half; right half split top/bottom; the bottom-right then split left/right. The
        // last one touches the bottom edge but neither side of the whole area.
        val all =
            listOf(
                bounds(0f, 0f, 500f, 800f),
                bounds(500f, 0f, 500f, 400f),
                bounds(500f, 400f, 250f, 400f),
                bounds(750f, 400f, 250f, 400f),
            )
        assertEquals("Bottom right", paneLabel(3, paneGlyphFor(all[3], all)))
        // Touches the bottom, but its right edge is mid-area - no corner, so a number.
        assertEquals("Pane 3", paneLabel(2, paneGlyphFor(all[2], all)))
    }

    @Test
    fun `a divider's own width does not cost a pane its name`() {
        // Real panes are separated by a divider and inset by their border ring, so neither
        // reaches the exact halfway mark and neither starts at exactly zero.
        val all = listOf(bounds(2f, 2f, 496f, 796f), bounds(504f, 2f, 496f, 796f))
        assertEquals("Left", paneLabel(0, paneGlyphFor(all[0], all)))
        assertEquals("Right", paneLabel(1, paneGlyphFor(all[1], all)))
    }

    @Test
    fun `no glyph still yields a usable name`() {
        assertEquals("Pane 2", paneLabel(1, null))
    }

    // ---- paneAt: clicking the split map --------------------------------------

    private fun glyphsOf(all: List<PanelBounds>) = all.map { paneGlyphFor(it, all) }

    @Test
    fun `a click inside a pane picks that pane`() {
        val glyphs = glyphsOf(listOf(leftPane, rightPane))
        assertEquals(0, paneAt(glyphs, x = 0.25f, y = 0.5f))
        assertEquals(1, paneAt(glyphs, x = 0.75f, y = 0.5f))
    }

    @Test
    fun `a click on the divider picks the nearest pane rather than nothing`() {
        // Real panes do not tile the area exactly - a divider and each pane's border ring sit
        // between them. A map you can click and have nothing happen is worse than one that picks
        // the obvious neighbour.
        val all = listOf(bounds(0f, 0f, 495f, 800f), bounds(505f, 0f, 495f, 800f))
        val glyphs = glyphsOf(all)
        assertEquals(0, paneAt(glyphs, x = 0.499f, y = 0.5f))
        assertEquals(1, paneAt(glyphs, x = 0.501f, y = 0.5f))
    }

    @Test
    fun `a click outside every pane still lands somewhere`() {
        val glyphs = glyphsOf(listOf(topPane, bottomPane))
        assertEquals(0, paneAt(glyphs, x = -0.2f, y = -0.2f))
        assertEquals(1, paneAt(glyphs, x = 1.4f, y = 1.4f))
    }

    @Test
    fun `a nested split routes clicks to the right quarter`() {
        val all =
            listOf(
                bounds(0f, 0f, 500f, 800f),
                bounds(500f, 0f, 500f, 400f),
                bounds(500f, 400f, 500f, 400f),
            )
        val glyphs = glyphsOf(all)
        assertEquals(0, paneAt(glyphs, x = 0.2f, y = 0.9f))
        assertEquals(1, paneAt(glyphs, x = 0.8f, y = 0.2f))
        assertEquals(2, paneAt(glyphs, x = 0.8f, y = 0.8f))
    }

    @Test
    fun `panes with no measured rectangle are never picked`() {
        // An unmeasured pane has no region on the map, so it cannot be the answer - not even as
        // the nearest one, which would send the user to a pane the map never drew.
        val glyphs = listOf(null, paneGlyphFor(rightPane, listOf(leftPane, rightPane)))
        assertEquals(1, paneAt(glyphs, x = 0.1f, y = 0.1f))
        assertNull(paneAt(listOf(null, null), x = 0.5f, y = 0.5f))
    }

    @Test
    fun `paneGlyphs normalises the whole set against the panes it could measure`() {
        val measured = mapOf("a" to leftPane, "b" to rightPane)
        val glyphs = paneGlyphs(listOf("a", "b"), measured::get)
        assertEquals("Left", paneLabel(0, glyphs["a"]))
        assertEquals("Right", paneLabel(1, glyphs["b"]))
    }

    @Test
    fun `an unmeasured pane is absent rather than guessed at`() {
        // Absent from the map AND excluded from the area, so the pane that DID measure is still
        // normalised against something real. The caller then falls through to a number for the
        // other one, which is what the tab bar's header already does.
        val glyphs = paneGlyphs(listOf("a", "b"), mapOf("b" to rightPane)::get)
        assertNull(glyphs["a"])
        assertEquals("Pane 1", paneLabel(0, glyphs["a"]))
        // One measured pane covers the whole area it defines, so no side describes it.
        assertEquals("Pane 2", paneLabel(1, glyphs["b"]))
    }
}
