package ai.rever.boss.components.overlays

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where a heavyweight drag ghost's window is placed.
 *
 * Two properties matter and only one of them is cosmetic.
 *
 * The load-bearing one is that the POINTER STAYS OUTSIDE THE GHOST. A non-focusable AWT window still
 * receives mouse events, and the JVM has no portable click-through, so a ghost window under the
 * pointer swallows the drag that is moving it: the ghost follows the cursor and the drop never lands.
 * That is why the placement flips to the other side of the cursor at a screen edge instead of being
 * pulled back across it, and why nearly every case below re-asserts the invariant.
 *
 * The other is the CLAMP, which keeps the ghost on the monitor the pointer is on rather than spilling
 * over a taskbar or onto the wrong display.
 */
class HeavyweightGhostPlacementTest {
    private companion object {
        /** A single 1920x1080 monitor at the origin with a 25px menu bar. */
        val SINGLE = listOf(intArrayOf(0, 25, 1920, 1055))

        /** The same, plus a second monitor to its right whose origin is NOT (0, 0). */
        val DUAL = SINGLE + listOf(intArrayOf(1920, 0, 1280, 800))

        const val GHOST_W = 180
        const val GHOST_H = 32
    }

    private fun place(
        x: Int,
        y: Int,
        screens: List<IntArray> = SINGLE,
    ) = clampGhostToScreens(cursorX = x, cursorY = y, width = GHOST_W, height = GHOST_H, screens = screens)

    /** The invariant the gap exists for: a pointer inside the ghost eats its own drag. */
    private fun assertPointerOutside(
        cursorX: Int,
        cursorY: Int,
        placed: Pair<Int, Int>,
    ) {
        val (gx, gy) = placed
        val inside = cursorX >= gx && cursorX < gx + GHOST_W && cursorY >= gy && cursorY < gy + GHOST_H
        assertFalse(inside, "pointer ($cursorX, $cursorY) landed inside the ghost at $placed")
    }

    @Test
    fun `mid-screen, the ghost sits below-right of the cursor`() {
        val placed = place(600, 400)
        assertTrue(placed.first > 600 && placed.second > 400, "expected below-right, got $placed")
        assertPointerOutside(600, 400, placed)
    }

    @Test
    fun `at the right edge it flips to the cursor's left rather than sliding under it`() {
        // This is the case a plain clamp gets wrong. Clamping to (1920 - 180) = 1740 with the cursor at
        // 1900 puts the pointer inside the ghost, which would stall the drag in the last 180px of the
        // screen. Flipping puts the ghost entirely left of the cursor instead.
        val placed = place(1900, 400)
        assertTrue(placed.first + GHOST_W <= 1900, "ghost should end left of the cursor, got $placed")
        assertTrue(placed.first >= 0, "ghost should stay on screen, got $placed")
        assertPointerOutside(1900, 400, placed)
    }

    @Test
    fun `at the bottom edge it flips above the cursor, clear of the menu-bar-adjusted floor`() {
        val placed = place(600, 1070)
        assertTrue(placed.second + GHOST_H <= 1070, "ghost should end above the cursor, got $placed")
        assertPointerOutside(600, 1070, placed)
    }

    @Test
    fun `a bottom edge that still fits below the cursor is left below it`() {
        // Only flip when it is actually needed: 25 + 1055 = 1080 is the floor, and a cursor at 1000
        // leaves room for 16 + 32 underneath.
        val placed = place(600, 1000)
        assertEquals(1016, placed.second)
    }

    @Test
    fun `the ghost is clamped to the monitor the pointer is on, not the primary one`() {
        // The trap: clamping against the primary screen's rect would drag a ghost on the second
        // monitor back to x < 1920, i.e. onto the wrong display entirely.
        val placed = place(2000, 300, DUAL)
        assertTrue(placed.first >= 1920, "ghost jumped back to the primary monitor: $placed")
        assertPointerOutside(2000, 300, placed)
    }

    @Test
    fun `the second monitor's own origin is respected, not treated as zero`() {
        // Cursor at the second monitor's top-left. An axis computed from width/height without adding
        // the monitor's origin would place the ghost at (16, 16) on the PRIMARY screen.
        val placed = place(1920, 0, DUAL)
        assertEquals(Pair(1936, 16), placed)
    }

    @Test
    fun `a pointer on no known monitor is pulled back onto one, not left off-screen`() {
        // Monitors can be unplugged mid-drag, and the coordinates are then off every rect we cached.
        // The near-edge half of the fit test is what catches this: a cursor far to the LEFT satisfies
        // "does not run past the right edge" trivially, so testing only the far edge would leave the
        // ghost thousands of pixels off-screen and invisible for the rest of the drag.
        val placed = place(-5000, -5000, DUAL)
        assertEquals(Pair(0, 25), placed)
    }

    @Test
    fun `a ghost larger than the monitor pins inside it instead of inverting the range`() {
        // Neither the offset nor the flip fits, so both axes fall through to the clamp. coerceIn throws
        // on an inverted range, and an exception here would take the whole drag gesture down with it.
        // 100 wide cannot hold 180, so x pins to the origin; 60 tall can hold 32, so y pins to 60 - 32.
        val tiny = listOf(intArrayOf(0, 0, 100, 60))
        val placed = clampGhostToScreens(cursorX = 50, cursorY = 30, width = GHOST_W, height = GHOST_H, screens = tiny)
        assertEquals(Pair(0, 28), placed)
    }

    @Test
    fun `with no monitors at all it still returns the offset point`() {
        val placed =
            clampGhostToScreens(cursorX = 40, cursorY = 60, width = GHOST_W, height = GHOST_H, screens = emptyList())
        assertEquals(Pair(56, 76), placed)
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
