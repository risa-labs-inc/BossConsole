package ai.rever.boss.plugin.browser

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PopupTabTrackerTest {
    @Test
    fun `tabs beyond the burst cap are refused`() {
        val tracker = PopupTabTracker(maxOpenTabs = 3)
        repeat(3) { assertTrue(tracker.tryRecordOpened(nowMs = 0)) }
        assertFalse(tracker.tryRecordOpened(nowMs = 0))
        assertFalse(tracker.tryRecordOpened(nowMs = 1_000))
    }

    @Test
    fun `only elapsed time releases the popup budget`() {
        val tracker = PopupTabTracker(trackWindowMs = 5_000, maxOpenTabs = 2)
        repeat(2) { assertTrue(tracker.tryRecordOpened(nowMs = 0)) }
        assertFalse(tracker.tryRecordOpened(nowMs = 3_000))
        assertFalse(tracker.tryRecordOpened(nowMs = 5_000))
        assertTrue(tracker.tryRecordOpened(nowMs = 5_001))
        assertTrue(tracker.tryRecordOpened(nowMs = 5_001))
        assertFalse(tracker.tryRecordOpened(nowMs = 5_001))
    }

    @Test
    fun `refused popups do not extend the admission window`() {
        val tracker = PopupTabTracker(trackWindowMs = 5_000, maxOpenTabs = 1)
        assertTrue(tracker.tryRecordOpened(nowMs = 0))
        assertFalse(tracker.tryRecordOpened(nowMs = 4_999))
        assertTrue(tracker.tryRecordOpened(nowMs = 5_001))
    }
}
