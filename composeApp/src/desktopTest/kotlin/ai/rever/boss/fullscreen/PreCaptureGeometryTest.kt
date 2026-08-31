package ai.rever.boss.fullscreen

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the geometry a captured session has to hand back.
 *
 * The bug these exist for: the restore ran only in the toggle branch, so the focus guard, the
 * dispose path and the hardwired Escape hold each released the pointer and keyboard and left the
 * window in AppKit full screen. The chrome came back with no traffic lights, and pressing the
 * button then re-entered - recording `Fullscreen` as the placement to return to, which made it
 * permanent. Both halves are covered here: what is recorded, and that recording never captures the
 * state it is supposed to undo.
 */
class PreCaptureGeometryTest {
    private fun stateOf(
        placement: WindowPlacement,
        size: DpSize = DpSize(800.dp, 600.dp),
        position: WindowPosition = WindowPosition(100.dp, 50.dp),
    ) = WindowState(placement = placement, size = size, position = position)

    @Test
    fun `an ordinary window round-trips`() {
        val state = stateOf(WindowPlacement.Floating)
        val restore = PreCaptureGeometry()
        restore.captureFrom(state)

        state.placement = WindowPlacement.Fullscreen
        restore.applyTo(state)

        assertEquals(WindowPlacement.Floating, state.placement)
        assertEquals(DpSize(800.dp, 600.dp), state.size)
    }

    @Test
    fun `a maximized window goes back to maximized`() {
        val state = stateOf(WindowPlacement.Maximized)
        val restore = PreCaptureGeometry()
        restore.captureFrom(state)

        state.placement = WindowPlacement.Fullscreen
        restore.applyTo(state)

        assertEquals(WindowPlacement.Maximized, state.placement)
    }

    @Test
    fun `capturing a window that is already full screen never records Fullscreen`() {
        // The trap. An exit that released the input without restoring the placement leaves exactly
        // this state, and the next entry would record it as the thing to go back to - so leaving
        // the mode would put the window straight back into full screen, for ever.
        val state = stateOf(WindowPlacement.Fullscreen)
        val restore = PreCaptureGeometry()
        restore.captureFrom(state)
        restore.applyTo(state)

        assertEquals(
            WindowPlacement.Floating,
            state.placement,
            "Recording Fullscreen makes the mode impossible to leave",
        )
    }

    @Test
    fun `applying without capturing moves nothing`() {
        // The restore effect fires once with capturing=false on every window, including windows
        // that have never entered the mode. It must not move them.
        val state = stateOf(WindowPlacement.Maximized, size = DpSize(1024.dp, 768.dp))
        PreCaptureGeometry().applyTo(state)

        assertEquals(WindowPlacement.Maximized, state.placement)
        assertEquals(DpSize(1024.dp, 768.dp), state.size)
    }

    @Test
    fun `a second apply is a no-op, because several exit routes can fire for one exit`() {
        val state = stateOf(WindowPlacement.Floating)
        val restore = PreCaptureGeometry()
        restore.captureFrom(state)
        restore.applyTo(state)

        // The user resizes after leaving the mode; a stale second restore must not undo that.
        state.size = DpSize(1200.dp, 900.dp)
        state.placement = WindowPlacement.Maximized
        restore.applyTo(state)

        assertEquals(WindowPlacement.Maximized, state.placement)
        assertEquals(DpSize(1200.dp, 900.dp), state.size)
    }
}
