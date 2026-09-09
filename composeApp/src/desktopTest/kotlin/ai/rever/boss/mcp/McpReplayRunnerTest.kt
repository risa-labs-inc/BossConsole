package ai.rever.boss.mcp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpReplayRunnerTest {
    private class QueuedDispatcher : CoroutineDispatcher() {
        val tasks = ArrayDeque<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            tasks.addLast(block)
        }

        fun drain() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    @Test
    fun `two clicks before dispatch reserve exactly one invocation`() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(dispatcher + Job())
        val runner = McpReplayRunner(scope)
        var calls = 0
        assertTrue(runner.start { calls++ })
        assertFalse(runner.start { calls++ })
        assertTrue(runner.running.value)
        dispatcher.drain()
        assertEquals(1, calls)
        assertFalse(runner.running.value)
        scope.cancel()
    }

    @Test
    fun `cancellation before dispatch releases replay reservation without executing`() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(dispatcher + Job())
        val runner = McpReplayRunner(scope)
        var calls = 0
        assertTrue(runner.start { calls++ })
        scope.cancel()
        dispatcher.drain()
        assertFalse(runner.running.value)
        assertEquals(0, calls)
    }
}
