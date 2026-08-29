package ai.rever.boss.plugin.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The queue's whole job is to stay aligned: entry N must be claimed by popup N. A shift of one
 * hands a tab the URL of a *previous* link, which is the same wrong-destination bug the popup
 * handler was changed to remove - so the alignment cases matter more than the storage.
 */
class PopupTargetQueueTest {
    @Test
    fun `a create with no usable target still holds its place in the queue`() {
        val queue = PopupTargetQueue()

        // window.open('') records nothing usable, then a cmd+click records a real link.
        queue.record("", nowMs = 0)
        queue.record("https://example.com/b", nowMs = 0)

        // Popup 1 is the window.open, and must NOT be handed link B.
        assertNull(queue.claim(nowMs = 0), "the empty create was skipped, shifting every later popup by one")
        assertEquals("https://example.com/b", queue.claim(nowMs = 0))
    }

    @Test
    fun `about blank is filtered at claim rather than at record`() {
        val queue = PopupTargetQueue()
        queue.record("about:blank", nowMs = 0)
        queue.record("https://example.com/second", nowMs = 0)

        assertNull(queue.claim(nowMs = 0))
        assertEquals("https://example.com/second", queue.claim(nowMs = 0))
    }

    @Test
    fun `targets are claimed in the order they were created`() {
        val queue = PopupTargetQueue()
        queue.record("https://example.com/1", nowMs = 0)
        queue.record("https://example.com/2", nowMs = 0)

        assertEquals("https://example.com/1", queue.claim(nowMs = 0))
        assertEquals("https://example.com/2", queue.claim(nowMs = 0))
        assertNull(queue.claim(nowMs = 0))
    }

    @Test
    fun `an orphaned create expires instead of mispairing with a later popup`() {
        val queue = PopupTargetQueue(ttlMs = 2_000)

        // A popup whose navigation resolved to a download is destroyed before it is ever shown,
        // so this create never gets an open.
        queue.record("https://example.com/orphan.zip", nowMs = 0)
        queue.record("https://example.com/later", nowMs = 5_000)

        assertEquals("https://example.com/later", queue.claim(nowMs = 5_000))
    }

    @Test
    fun `a create still within the ttl is claimable`() {
        val queue = PopupTargetQueue(ttlMs = 2_000)
        queue.record("https://example.com/fresh", nowMs = 1_000)

        assertEquals("https://example.com/fresh", queue.claim(nowMs = 2_500))
    }

    @Test
    fun `the queue is bounded, dropping the oldest`() {
        val queue = PopupTargetQueue(maxEntries = 2)
        queue.record("https://example.com/1", nowMs = 0)
        queue.record("https://example.com/2", nowMs = 0)
        queue.record("https://example.com/3", nowMs = 0)

        assertEquals(2, queue.pending())
        assertEquals("https://example.com/2", queue.claim(nowMs = 0))
        assertEquals("https://example.com/3", queue.claim(nowMs = 0))
    }

    @Test
    fun `claiming from an empty queue is not an error`() {
        assertNull(PopupTargetQueue().claim(nowMs = 0))
    }
}
