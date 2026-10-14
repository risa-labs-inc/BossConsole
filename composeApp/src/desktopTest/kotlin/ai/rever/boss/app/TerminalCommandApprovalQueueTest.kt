package ai.rever.boss.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TerminalCommandApprovalQueueTest {
    @Test
    fun `flood refuses tail without replacing displayed or queued commands`() {
        val queue = TerminalCommandApprovalQueue()
        val accepted = List(TerminalCommandApprovalQueue.MAX_PENDING) { PendingTerminalCommand("echo $it", null) }
        accepted.forEach { assertTrue(queue.enqueue(it)) }
        repeat(1000) { assertFalse(queue.enqueue(PendingTerminalCommand("overflow", null))) }
        accepted.forEach {
            assertSame(it, queue.current)
            assertTrue(queue.consume(it))
        }
        assertNull(queue.current)
        assertTrue(queue.enqueue(PendingTerminalCommand("after drain", null)))
    }

    @Test
    fun `arrival while prompt is open preserves its command and working directory`() {
        val queue = TerminalCommandApprovalQueue()
        val first = PendingTerminalCommand("echo first", "/first")
        val second = PendingTerminalCommand("echo second", "/second")
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
    fun `confirm followed by dismiss cannot discard an identical next command`() {
        val queue = TerminalCommandApprovalQueue()
        val first = PendingTerminalCommand("echo same", null)
        val second = PendingTerminalCommand("echo same", null)
        queue.enqueue(first)
        queue.enqueue(second)
        assertTrue(queue.consume(first)) // confirmation consumes before executing
        assertFalse(queue.consume(first)) // ConfirmationDialog then calls onDismiss
        assertSame(second, queue.current)
        assertFalse(queue.consume(first)) // stale double-click cannot execute again
        assertTrue(queue.consume(second))
        assertNull(queue.current)
    }

    @Test
    fun `cancel advances to the next request without approving it`() {
        val queue = TerminalCommandApprovalQueue()
        val first = PendingTerminalCommand("echo cancelled", null)
        val second = PendingTerminalCommand("echo pending", null)
        queue.enqueue(first)
        queue.enqueue(second)
        assertTrue(queue.consume(first)) // cancellation has no execution callback
        assertSame(second, queue.current)
        assertFalse(queue.consume(first))
    }
}
