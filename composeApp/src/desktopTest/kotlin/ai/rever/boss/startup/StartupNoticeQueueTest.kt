package ai.rever.boss.startup

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StartupNoticeQueueTest {
    @Test
    fun `notice survives a slow cold start and is consumed by only one window`() =
        runTest {
            val queue = StartupNoticeQueue()
            queue.report("missing auth")
            delay(60_000)
            assertEquals("missing auth", queue.notices.first())
            assertNull(withTimeoutOrNull(100) { queue.notices.first() })
        }

    @Test
    fun `multiple diagnostics coalesce per source and are paced per batch`() =
        runTest {
            val queue = StartupNoticeQueue()
            queue.report("retrying auth", "auth")
            queue.report("auth restart cap", "auth")
            queue.report("missing editor", "editor")
            val received =
                queue.notices
                    .onEach {
                        queue.report("next batch", "auth")
                    }.take(2)
                    .toList()
            assertEquals(listOf("auth restart cap · missing editor", "next batch"), received)
            assertEquals(KERNEL_NOTICE_DURATION_MS, testScheduler.currentTime)
        }

    @Test
    fun `cancelled waiting window cannot lose diagnostic data`() =
        runTest {
            val queue = StartupNoticeQueue()
            val window = launch { queue.notices.first() }
            runCurrent()
            queue.report("retain this")
            window.cancel()
            window.join()
            assertEquals("retain this", queue.notices.first())
        }

    @Test
    fun `cancellation during delivery restores pending diagnostics`() =
        runTest {
            val queue = StartupNoticeQueue()
            queue.report("retain this")
            val window = launch { queue.notices.collect { kotlinx.coroutines.awaitCancellation() } }
            runCurrent()
            window.cancel()
            window.join()
            assertEquals("retain this", queue.notices.first())
        }

    @Test
    fun `operator remedies precede startup information`() =
        runTest {
            val queue = StartupNoticeQueue()
            queue.report("startup summary", STARTUP_NOTICE_SOURCE)
            queue.report("restart BOSS", "auth")
            assertEquals("restart BOSS · startup summary", queue.notices.first())
        }

    @Test
    fun `large failure batches display an actionable count`() {
        val batch = (1..8).associate { "service-$it" to "repeated lengthy failure" }
        assertEquals("8 services need attention. Check service logs for details.", renderKernelNotices(batch))
    }

    @Test
    fun `long unicode detail preserves the leading remedy for visual ellipsis`() {
        assertEquals(
            "Restart BOSS · " + "🚀".repeat(200),
            renderKernelNotices(mapOf(STARTUP_NOTICE_SOURCE to "🚀".repeat(200), "auth" to "Restart BOSS")),
        )
    }
}
