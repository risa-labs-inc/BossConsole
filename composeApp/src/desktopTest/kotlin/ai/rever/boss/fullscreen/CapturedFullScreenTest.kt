package ai.rever.boss.fullscreen

import java.awt.Point
import java.awt.Rectangle
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the parts of captured full screen that decide whether a user can get their input back.
 *
 * These are pure functions on purpose. The grabs themselves are three platform APIs that cannot run
 * in CI, so what is testable is the reasoning around them - which window a session belongs to, what
 * the user is told, and which keys are taken - and that is where every bug found while building this
 * actually was.
 */
class CapturedFullScreenTest {
    @AfterTest
    fun reset() = CapturedFullScreenState.clear()

    // --- which window owns the session -------------------------------------------------

    @Test
    fun `an inactive session captures no window`() {
        val session = CapturedFullScreen()
        assertFalse(session.active)
        assertFalse(session.capturing("window-1"))
    }

    @Test
    fun `only the holding window is captured`() {
        val session = CapturedFullScreen(windowId = "window-1")
        assertTrue(session.capturing("window-1"))
        assertFalse(
            session.capturing("window-2"),
            "A second window is an ordinary window while another one is captured. Reading the " +
                "global active flag instead would strip its chrome to serve a session it is not in.",
        )
    }

    @Test
    fun `a null window id never matches, even against an inactive session`() {
        // Both sides null must not read as equal: LocalWindowId is nullable at the call site, and
        // `windowId == id` alone would report every window captured whenever nothing was.
        assertFalse(CapturedFullScreen().capturing(null))
        assertFalse(CapturedFullScreen(windowId = "window-1").capturing(null))
    }

    @Test
    fun `chrome is suppressed only for the capturing window`() {
        val session = CapturedFullScreen(windowId = "window-1")
        assertTrue(session.suppressesChrome("window-1"))
        assertFalse(session.suppressesChrome("window-2"))
    }

    // --- the shared state holder -------------------------------------------------------

    @Test
    fun `clear is idempotent, because every teardown path calls it`() {
        CapturedFullScreenState.set(CapturedFullScreen(windowId = "window-1", pointerConfined = true))
        CapturedFullScreenState.clear()
        CapturedFullScreenState.clear()
        assertFalse(CapturedFullScreenState.current.value.active)
    }

    @Test
    fun `releasing the pointer keeps the session and the keyboard grab`() {
        CapturedFullScreenState.set(
            CapturedFullScreen(windowId = "window-1", pointerConfined = true, keyboardGrabbed = true),
        )
        CapturedFullScreenState.setPointerConfined(false)

        val session = CapturedFullScreenState.current.value
        assertTrue(session.active, "Releasing the pointer must not end the mode")
        assertTrue(session.keyboardGrabbed)
        assertFalse(session.pointerConfined)
    }

    @Test
    fun `the pointer flag cannot be set on a session that is not running`() {
        CapturedFullScreenState.clear()
        CapturedFullScreenState.setPointerConfined(true)
        assertFalse(
            CapturedFullScreenState.current.value.active,
            "A late write from the confiner thread must not resurrect a session that has ended.",
        )
    }

    // --- clamping ----------------------------------------------------------------------

    @Test
    fun `a point inside is returned unchanged`() {
        val area = Rectangle(0, 0, 1920, 1080)
        assertEquals(Point(100, 200), clampIntoBounds(Point(100, 200), area))
    }

    @Test
    fun `the far edge clamps to the last pixel inside, not to the boundary`() {
        // Rectangle.contains is exclusive at the far edge, so returning 1920 here would be judged
        // outside on the next tick and warped again, for ever. This is the whole reason the
        // function exists.
        val area = Rectangle(0, 0, 1920, 1080)
        val clamped = clampIntoBounds(Point(5000, 5000), area)
        assertEquals(Point(1919, 1079), clamped)
        assertTrue(area.contains(clamped), "The clamped point must satisfy the same predicate the loop uses")
    }

    @Test
    fun `clamps into a display that does not start at the origin`() {
        // A second monitor to the left has negative origin coordinates; clamping to 0 would drag
        // the pointer onto the primary display, which is precisely what the mode prevents.
        val area = Rectangle(-1920, -100, 1920, 1080)
        assertEquals(Point(-1920, -100), clampIntoBounds(Point(-4000, -4000), area))
        assertEquals(Point(-1, 979), clampIntoBounds(Point(500, 5000), area))
    }
}
