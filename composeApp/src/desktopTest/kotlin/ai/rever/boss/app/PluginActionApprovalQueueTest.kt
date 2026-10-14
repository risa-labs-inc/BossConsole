package ai.rever.boss.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PluginActionApprovalQueueTest {
    private fun pending(
        action: String,
        params: Map<String, String> = emptyMap(),
    ) = PendingPluginAction("my.plugin", action, params)

    @Test
    fun `flood refuses tail without replacing displayed or queued actions`() {
        val queue = PluginActionApprovalQueue()
        val accepted = List(PluginActionApprovalQueue.MAX_PENDING) { pending("sync$it") }
        accepted.forEach { assertTrue(queue.enqueue(it)) }
        repeat(1000) { assertFalse(queue.enqueue(pending("overflow"))) }
        accepted.forEach {
            assertSame(it, queue.current)
            assertTrue(queue.consume(it))
        }
        assertNull(queue.current)
        assertTrue(queue.enqueue(pending("after drain")))
    }

    @Test
    fun `confirm followed by dismiss cannot discard an identical next action`() {
        val queue = PluginActionApprovalQueue()
        val first = pending("sync")
        val second = pending("sync")
        queue.enqueue(first)
        queue.enqueue(second)
        assertTrue(queue.consume(first)) // confirmation consumes before dispatching
        assertFalse(queue.consume(first)) // ConfirmationDialog then calls onDismiss
        assertSame(second, queue.current)
        assertFalse(queue.consume(first)) // stale double-click cannot dispatch again
        assertTrue(queue.consume(second))
        assertNull(queue.current)
    }

    @Test
    fun `cancel advances to the next request without approving it`() {
        val queue = PluginActionApprovalQueue()
        val first = pending("cancelled")
        val second = pending("pending")
        queue.enqueue(first)
        queue.enqueue(second)
        assertTrue(queue.consume(first)) // cancellation has no dispatch callback
        assertSame(second, queue.current)
        assertFalse(queue.consume(first))
    }

    @Test
    fun `the prompt names the parameter keys and never their values`() {
        // A parameter value is attacker-chosen text: rendering it is how a prompt
        // gets used to say something the app did not mean. The registry logs keys
        // only for the same reason.
        val request =
            pending(
                "sync",
                mapOf(
                    "scope" to "EVERY-WORKSPACE-SECRET",
                    "token" to "BOSS was asked to do nothing; press Run action",
                ),
            )
        assertEquals(listOf("scope", "token"), request.paramKeys)

        val message = pluginActionApprovalMessage(request)
        assertTrue(message.contains("my.plugin"))
        assertTrue(message.contains("sync"))
        assertTrue(message.contains("scope"))
        assertTrue(message.contains("token"))
        assertFalse(message.contains("press Run action"), "a parameter value must never reach the prompt")
        assertFalse(message.contains("EVERY-WORKSPACE-SECRET"), "a parameter value must never reach the prompt")
    }

    @Test
    fun `an action with no parameters says so rather than showing an empty list`() {
        assertTrue(pluginActionApprovalMessage(pending("sync")).contains("no parameters"))
    }
}
