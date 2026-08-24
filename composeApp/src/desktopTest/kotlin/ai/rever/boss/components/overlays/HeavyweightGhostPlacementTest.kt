package ai.rever.boss.components.overlays

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where a heavyweight drag ghost's window is placed.
 *
 * The property that matters is that THE POINTER SITS ON THE HOTSPOT: a drag ghost is the thing the
 * pointer is carrying, so its own window has to land exactly where the lightweight ghost draws
 * itself in Compose coordinates. It used to be pushed 16px below-right of the cursor instead, to
 * keep the pointer outside a window that has no click-through - measured on macOS, an always-on-top
 * window that appears AFTER the press takes no mouse events at all (the source window holds the
 * implicit grab), so that gap bought nothing and cost ~27px of visible detachment.
 *
 * The other property is the CLAMP, which keeps a ghost at the edge of the desktop from hanging half
 * off it. It is the one thing allowed to move the ghost off its hotspot, so it is asked only when
 * the ghost would land where no display is - spilling onto the next monitor, or over a dock, costs
 * nothing and is left alone.
 */
class HeavyweightGhostPlacementTest {
    private companion object {
        /**
         * A single 1920x1080 monitor at the origin.
         *
         * Full bounds, not the working area: a ghost is an always-on-top overlay following the
         * pointer, so a dock or a menu bar is somewhere it may pass over, not somewhere there is no
         * screen. See screenRects.
         */
        val SINGLE = listOf(intArrayOf(0, 0, 1920, 1080))

        /** The same, plus a second monitor to its right whose origin is NOT (0, 0). */
        val DUAL = SINGLE + listOf(intArrayOf(1920, 0, 1280, 800))

        /** A tab card, carried a quarter in from its leading edge and vertically centred. */
        const val GHOST_W = 180
        const val GHOST_H = 32
        const val HOT_X = GHOST_W / 4
        const val HOT_Y = GHOST_H / 2
    }

    private fun place(
        x: Int,
        y: Int,
        screens: List<IntArray> = SINGLE,
    ) = clampGhostToScreens(
        cursorX = x,
        cursorY = y,
        size = IntSize(GHOST_W, GHOST_H),
        hotspot = IntOffset(HOT_X, HOT_Y),
        screens = screens,
    )

    @Test
    fun `mid-screen, the pointer lands exactly on the ghost's hotspot`() {
        val placed = place(600, 400)
        assertEquals(Pair(600 - HOT_X, 400 - HOT_Y), placed)
    }

    @Test
    fun `a carried ghost holds the pointer inside itself, which is the point of the hotspot`() {
        // The reversal, stated as a test: this is exactly what the old gap existed to prevent, and
        // what makes the ghost read as being carried rather than trailing behind the cursor.
        val (gx, gy) = place(600, 400)
        val inside = 600 >= gx && 600 < gx + GHOST_W && 400 >= gy && 400 < gy + GHOST_H
        assertTrue(inside, "pointer should sit on the ghost it is dragging")
    }

    @Test
    fun `a centred icon ghost is centred on the cursor`() {
        // The sidebar rail's ghost: 22dp square, carried by its middle.
        val placed =
            clampGhostToScreens(
                cursorX = 600,
                cursorY = 400,
                size = IntSize(22, 22),
                hotspot = IntOffset(11, 11),
                screens = SINGLE,
            )
        assertEquals(Pair(589, 389), placed)
    }

    @Test
    fun `at the right edge of the desktop the ghost is pulled back onto the screen`() {
        // The clamp wins over the hotspot here: half a ghost hanging off the display is worse than
        // a ghost the pointer is no longer centred on.
        val placed = place(1900, 400)
        assertEquals(1920 - GHOST_W, placed.first)
    }

    @Test
    fun `at the bottom edge it is pulled up clear of the floor`() {
        val placed = place(600, 1075)
        assertEquals(1080 - GHOST_H, placed.second)
    }

    @Test
    fun `an edge that still fits is left exactly on the hotspot`() {
        // Only clamp when it is actually needed: a cursor at 1000 leaves room for the half of the
        // card that hangs below it.
        val placed = place(600, 1000)
        assertEquals(1000 - HOT_Y, placed.second)
    }

    @Test
    fun `the ghost is clamped to the monitor the pointer is on, not the primary one`() {
        // The trap: clamping against the primary screen's rect would drag a ghost near the second
        // monitor's right edge back to x < 1920, i.e. onto the wrong display entirely.
        val placed = place(3190, 300, DUAL)
        assertEquals(1920 + 1280 - GHOST_W, placed.first)
    }

    @Test
    fun `crossing the seam between two monitors leaves the ghost on the pointer`() {
        // The card is 180 wide and carried 45 in, so clamping to whichever monitor the cursor is on
        // would yank it up to 45px sideways at the seam and snap it back on the other side - for a
        // spill that costs nothing, since the next display is right there. It is left alone.
        val placed = place(1930, 300, DUAL)
        assertEquals(Pair(1930 - HOT_X, 300 - HOT_Y), placed)
    }

    @Test
    fun `the second monitor's own origin is respected, not treated as zero`() {
        // Cursor at the second monitor's top-left. An axis computed from width/height without adding
        // the monitor's origin would place the ghost on the PRIMARY screen.
        val placed = place(1920, 0, DUAL)
        assertEquals(Pair(1920, 0), placed)
    }

    @Test
    fun `the seam holds along the top of the displays too, not only mid-screen`() {
        // The trap that a mid-screen seam case cannot see: with the WORKING area as the coverage
        // test, a card whose left corners reached into the strip a menu bar occupies counted as off
        // the desktop, so it was pulled fully onto the cursor's monitor - the seam artifact back
        // again, in a band along the top of every display.
        val placed = place(1930, 30, DUAL)
        assertEquals(Pair(1930 - HOT_X, 30 - HOT_Y), placed)
    }

    @Test
    fun `a pointer on no known monitor is pulled back onto one, not left off-screen`() {
        // Monitors can be unplugged mid-drag, and the coordinates are then off every rect we cached.
        // Without the clamp the ghost would sit thousands of pixels off-screen, invisible for the
        // rest of the drag.
        val placed = place(-5000, -5000, DUAL)
        assertEquals(Pair(0, 0), placed)
    }

    @Test
    fun `a ghost larger than the monitor pins inside it instead of inverting the range`() {
        // coerceIn throws on an inverted range, and an exception here would take the whole drag
        // gesture down with it. 100 wide cannot hold 180, so x pins to the origin.
        val tiny = listOf(intArrayOf(0, 0, 100, 60))
        val placed =
            clampGhostToScreens(
                cursorX = 50,
                cursorY = 30,
                size = IntSize(GHOST_W, GHOST_H),
                hotspot = IntOffset(HOT_X, HOT_Y),
                screens = tiny,
            )
        assertEquals(Pair(0, 14), placed)
    }

    @Test
    fun `with no monitors at all it still hangs the ghost off the pointer`() {
        val placed =
            clampGhostToScreens(
                cursorX = 40,
                cursorY = 60,
                size = IntSize(GHOST_W, GHOST_H),
                hotspot = IntOffset(HOT_X, HOT_Y),
                screens = emptyList(),
            )
        assertEquals(Pair(40 - HOT_X, 60 - HOT_Y), placed)
    }
}

/**
 * Where an anchored heavyweight popup sits, i.e. the content-pane correction.
 *
 * The overlay window is placed at the parent FRAME's origin, while Compose measures an anchor against
 * the CONTENT PANE. On a decorated window those differ by the title bar, so an anchored popup placed
 * without the correction floats a title-bar's height above the control it belongs to. That is the same
 * off-by-a-title-bar the pinch-zoom gate had to solve, it is invisible in review, and it is the reason
 * the URL-bar suggestion list needed this rather than cursor placement.
 */
class AnchoredPopupPlacementTest {
    private companion object {
        /** A URL bar 28dp tall, 40px down the content pane. */
        val URL_BAR = IntRect(left = 120, top = 40, right = 620, bottom = 68)
        const val TITLE_BAR = 28
    }

    @Test
    fun `an anchored popup opens directly below the anchor, not over it`() {
        val at = anchoredContentOffset(URL_BAR, insetX = 0, insetY = TITLE_BAR)
        assertEquals(URL_BAR.bottom + TITLE_BAR, at.y)
        assertTrue(at.y > URL_BAR.top, "popup must not cover the control it belongs to")
    }

    @Test
    fun `it lines up with the anchor's left edge`() {
        val at = anchoredContentOffset(URL_BAR, insetX = 0, insetY = TITLE_BAR)
        assertEquals(URL_BAR.left, at.x)
    }

    @Test
    fun `the title-bar inset is added, which is the whole point`() {
        // Without the correction the popup would land at the anchor's own y, i.e. a title bar too
        // high - overlapping the URL bar rather than sitting under it.
        val corrected = anchoredContentOffset(URL_BAR, insetX = 0, insetY = TITLE_BAR)
        val uncorrected = anchoredContentOffset(URL_BAR, insetX = 0, insetY = 0)
        assertEquals(TITLE_BAR, corrected.y - uncorrected.y)
    }

    @Test
    fun `an undecorated window needs no correction and gets none`() {
        // Zero inset is the correct answer for an undecorated window, not a failure to measure.
        val at = anchoredContentOffset(URL_BAR, insetX = 0, insetY = 0)
        assertEquals(IntOffset(URL_BAR.left, URL_BAR.bottom), at)
    }

    @Test
    fun `a horizontal inset is honoured too, for platforms that have one`() {
        val at = anchoredContentOffset(URL_BAR, insetX = 8, insetY = TITLE_BAR)
        assertEquals(URL_BAR.left + 8, at.x)
    }
}
