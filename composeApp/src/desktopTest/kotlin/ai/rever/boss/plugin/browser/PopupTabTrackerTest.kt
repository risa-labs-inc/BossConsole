package ai.rever.boss.plugin.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the two halves of the popup-burst fix: a `window.open` storm must not become an
 * unbounded tab flood, and a redirect burst must lose ALL the tabs it opened when the first
 * download lands - not just the newest, which is what the old single-close did.
 */
class PopupTabTrackerTest {
    @Test
    fun `tabs beyond the burst cap are refused`() {
        val tracker = PopupTabTracker(maxOpenTabs = 3)

        assertTrue(tracker.tryRecordOpened(nowMs = 0))
        assertTrue(tracker.tryRecordOpened(nowMs = 0))
        assertTrue(tracker.tryRecordOpened(nowMs = 0))
        assertFalse(tracker.tryRecordOpened(nowMs = 0), "tab 4 inside the window must be refused")
        assertFalse(tracker.tryRecordOpened(nowMs = 1_000))
    }

    @Test
    fun `a refused tab is not recorded, so it is never closable either`() {
        val tracker = PopupTabTracker(maxOpenTabs = 1)

        tracker.tryRecordOpened(nowMs = 0)
        assertFalse(tracker.tryRecordOpened(nowMs = 0))

        // Only the tab that actually opened can be taken back down by a download.
        assertEquals(1, tracker.drainRedirectTabs(nowMs = 0))
    }

    @Test
    fun `the cap lifts once the oldest opens age out of the window`() {
        val tracker = PopupTabTracker(trackWindowMs = 5_000, maxOpenTabs = 2)

        assertTrue(tracker.tryRecordOpened(nowMs = 0))
        assertTrue(tracker.tryRecordOpened(nowMs = 0))
        assertFalse(tracker.tryRecordOpened(nowMs = 4_999))
        assertFalse(tracker.tryRecordOpened(nowMs = 5_000), "an entry exactly 5s old still counts")
        assertTrue(tracker.tryRecordOpened(nowMs = 5_001))
    }

    @Test
    fun `a download drains every pending redirect tab, not just the newest`() {
        val tracker = PopupTabTracker()

        // The burst this card is about: several tabs open before the first download lands.
        repeat(4) { tracker.tryRecordOpened(nowMs = 100) }

        assertEquals(4, tracker.drainRedirectTabs(nowMs = 500), "all burst tabs are owed a close")
        assertEquals(0, tracker.drainRedirectTabs(nowMs = 600), "drained entries stay forgotten")
    }

    @Test
    fun `a tab older than the close window is left standing`() {
        val tracker = PopupTabTracker(closeWindowMs = 3_000)

        tracker.tryRecordOpened(nowMs = 0)
        tracker.tryRecordOpened(nowMs = 3_500)

        // Only the newer tab is plausibly the download's redirect shell.
        assertEquals(1, tracker.drainRedirectTabs(nowMs = 4_000))
    }

    @Test
    fun `drained tabs stop counting against the cap immediately`() {
        val tracker = PopupTabTracker(maxOpenTabs = 2)

        tracker.tryRecordOpened(nowMs = 0)
        tracker.tryRecordOpened(nowMs = 0)
        assertFalse(tracker.tryRecordOpened(nowMs = 100))

        // A download takes both back down, freeing the budget they held.
        tracker.drainRedirectTabs(nowMs = 200)
        assertTrue(tracker.tryRecordOpened(nowMs = 300))
    }

    @Test
    fun `entries expire out of the tracking window entirely`() {
        val tracker = PopupTabTracker(trackWindowMs = 5_000, closeWindowMs = 3_000)

        tracker.tryRecordOpened(nowMs = 0)

        // Past the close window but still inside the track window: not closable, still counted.
        assertEquals(0, tracker.drainRedirectTabs(nowMs = 4_000))
        assertEquals(1, tracker.pendingCount(nowMs = 4_000))
        // Past both: fully forgotten.
        assertEquals(0, tracker.pendingCount(nowMs = 6_000))
    }
}
