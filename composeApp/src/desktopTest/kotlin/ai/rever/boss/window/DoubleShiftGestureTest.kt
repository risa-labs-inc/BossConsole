package ai.rever.boss.window

import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DoubleShiftGestureTest {
    @Test
    fun `two taps match once on the second press`() {
        val gesture = DoubleShiftGesture()
        assertFalse(gesture.handle(KeyEvent.KEY_PRESSED, 1000))
        assertFalse(gesture.handle(KeyEvent.KEY_RELEASED, 1050))
        assertTrue(gesture.handle(KeyEvent.KEY_PRESSED, 1100))
        assertFalse(gesture.handle(KeyEvent.KEY_PRESSED, 1110))
    }

    @Test
    fun `held Shift and too short release do not match`() {
        val gesture = DoubleShiftGesture()
        assertFalse(gesture.handle(KeyEvent.KEY_PRESSED, 1000))
        assertFalse(gesture.handle(KeyEvent.KEY_PRESSED, 1100))
        assertFalse(gesture.handle(KeyEvent.KEY_RELEASED, 1150))
        assertFalse(gesture.handle(KeyEvent.KEY_PRESSED, 1199))
    }

    @Test
    fun `long hold and expired double tap do not match`() {
        val held = DoubleShiftGesture()
        assertFalse(held.handle(KeyEvent.KEY_PRESSED, 1000))
        assertFalse(held.handle(KeyEvent.KEY_RELEASED, 1500))
        assertFalse(held.handle(KeyEvent.KEY_PRESSED, 1550))
        val expired = DoubleShiftGesture()
        assertFalse(expired.handle(KeyEvent.KEY_PRESSED, 1000))
        assertFalse(expired.handle(KeyEvent.KEY_RELEASED, 1050))
        assertFalse(expired.handle(KeyEvent.KEY_PRESSED, 1550))
    }

    @Test
    fun `intervening key resets the gesture`() {
        val gesture = DoubleShiftGesture()
        assertFalse(gesture.handle(KeyEvent.KEY_PRESSED, 1000))
        assertFalse(gesture.handle(KeyEvent.KEY_RELEASED, 1050))
        gesture.reset()
        assertFalse(gesture.handle(KeyEvent.KEY_PRESSED, 1100))
    }
}
