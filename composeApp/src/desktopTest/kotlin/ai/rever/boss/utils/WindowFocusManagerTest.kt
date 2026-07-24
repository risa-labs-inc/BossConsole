package ai.rever.boss.utils

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowFocusManagerTest {
    @Test
    fun `registration snapshots an already focused window`() {
        val tracker = AwtWindowFocusTracker()

        tracker.snapshotRegistration("window-a", isFocused = false)
        assertFalse(tracker.isFocused("window-a"))

        tracker.snapshotRegistration("window-a", isFocused = true)
        assertTrue(tracker.isFocused("window-a"))
    }

    @Test
    fun `late loss from previous window does not clear newer focus gain`() {
        val tracker = AwtWindowFocusTracker()
        val windowAListener = tracker.createListener("window-a")
        val windowBListener = tracker.createListener("window-b")

        windowAListener.windowGainedFocus(null)
        windowBListener.windowGainedFocus(null)
        windowAListener.windowLostFocus(null)

        assertFalse(tracker.isFocused("window-a"))
        assertTrue(tracker.isFocused("window-b"))
    }

    @Test
    fun `losing the focused window clears the snapshot`() {
        val tracker = AwtWindowFocusTracker()
        val listener = tracker.createListener("window-a")

        listener.windowGainedFocus(null)
        listener.windowLostFocus(null)

        assertFalse(tracker.isFocused("window-a"))
    }

    @Test
    fun `unregister clears only the matching focused window`() {
        val tracker = AwtWindowFocusTracker()
        val listener = tracker.createListener("window-b")

        listener.windowGainedFocus(null)
        tracker.onUnregistered("window-a")
        assertTrue(tracker.isFocused("window-b"))

        tracker.onUnregistered("window-b")
        assertFalse(tracker.isFocused("window-b"))
    }
}
