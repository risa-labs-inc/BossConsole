package ai.rever.boss.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UrlOpenApprovalQueueTest {
    @Test
    fun `flood refuses tail without replacing displayed or queued URLs`() {
        val queue = UrlOpenApprovalQueue()
        val accepted = List(UrlOpenApprovalQueue.MAX_PENDING) { PendingUrlOpen("https://example.com/$it", "t") }
        accepted.forEach { assertTrue(queue.enqueue(it)) }
        repeat(1000) { assertFalse(queue.enqueue(PendingUrlOpen("https://overflow.example", "t"))) }
        accepted.forEach {
            assertSame(it, queue.current)
            assertTrue(queue.consume(it))
        }
        assertNull(queue.current)
        assertTrue(queue.enqueue(PendingUrlOpen("https://after.example", "t")))
    }

    @Test
    fun `arrival while prompt is open preserves its URL and title`() {
        val queue = UrlOpenApprovalQueue()
        val first = PendingUrlOpen("https://first.example", "first")
        val second = PendingUrlOpen("https://second.example", "second")
        queue.enqueue(first)
        queue.enqueue(second)
        assertSame(first, queue.current)
        assertFalse(queue.consume(second))
        assertTrue(queue.consume(first))
        assertSame(second, queue.current)
        assertTrue(queue.consume(second))
        assertNull(queue.current)
    }

    @Test
    fun `confirm followed by dismiss cannot discard an identical next URL`() {
        val queue = UrlOpenApprovalQueue()
        val first = PendingUrlOpen("https://same.example", "same")
        val second = PendingUrlOpen("https://same.example", "same")
        queue.enqueue(first)
        queue.enqueue(second)
        assertTrue(queue.consume(first)) // confirmation consumes before opening
        assertFalse(queue.consume(first)) // ConfirmationDialog then calls onDismiss
        assertSame(second, queue.current)
        assertFalse(queue.consume(first)) // stale double-click cannot open again
        assertTrue(queue.consume(second))
        assertNull(queue.current)
    }

    @Test
    fun `cancel advances to the next request without opening it`() {
        val queue = UrlOpenApprovalQueue()
        val first = PendingUrlOpen("https://cancelled.example", "t")
        val second = PendingUrlOpen("https://pending.example", "t")
        queue.enqueue(first)
        queue.enqueue(second)
        assertTrue(queue.consume(first)) // cancellation has no open callback
        assertSame(second, queue.current)
        assertFalse(queue.consume(first))
    }
}
