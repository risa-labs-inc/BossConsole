package ai.rever.boss.window

import java.awt.event.KeyEvent

/** Tracks the double-Shift search gesture separately from shortcut dispatch and window routing. */
internal class DoubleShiftGesture {
    private var lastPressTime: Long = 0
    private var lastReleaseTime: Long = 0
    private var pressCount: Int = 0

    fun reset() {
        pressCount = 0
        lastPressTime = 0
        lastReleaseTime = 0
    }

    /** Returns true on the second press after a short, clean release. */
    fun handle(
        eventId: Int,
        now: Long,
    ): Boolean {
        when (eventId) {
            KeyEvent.KEY_PRESSED -> {
                val gap = now - lastReleaseTime
                if (gap in MIN_RELEASE_MS until DOUBLE_SHIFT_THRESHOLD_MS && pressCount == 1) {
                    reset()
                    return true
                }
                pressCount = 1
                lastPressTime = now
            }

            KeyEvent.KEY_RELEASED -> {
                if (pressCount == 1 && now - lastPressTime < DOUBLE_SHIFT_THRESHOLD_MS) {
                    lastReleaseTime = now
                } else {
                    pressCount = 0
                }
            }
        }
        return false
    }

    private companion object {
        // Match the existing gesture timing: a 50ms clean release within a 500ms double tap.
        const val MIN_RELEASE_MS = 50
        const val DOUBLE_SHIFT_THRESHOLD_MS = 500
    }
}
