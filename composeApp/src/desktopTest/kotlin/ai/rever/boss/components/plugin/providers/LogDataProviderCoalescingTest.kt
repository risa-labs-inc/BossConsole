package ai.rever.boss.components.plugin.providers

import ai.rever.boss.logging.DesktopLogCapture
import java.io.OutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That one captured line no longer costs one full rebuild of the log list.
 *
 * This is the change that stopped the freeze, and it had no test. `updateLogs` copies the whole
 * capture buffer, filters it and allocates a `LogEntryData` per entry - and it ran
 * synchronously from the log listener, which `DesktopLogCapture` invokes from inside
 * `PrintStream.write`. So every line rebuilt a list of up to 10,000 entries while holding the
 * `System.out` monitor, and every other thread in the process that wanted to log queued behind
 * it. One 30-frame stack trace is ~30 lines; a component logging a few of those a second was
 * enough to stall threads that had nothing to do with logging.
 *
 * Both the capture and the interval are constructor parameters purely so this is reachable:
 * against the `GlobalLogCapture` singleton and a hardcoded interval it was not testable at all.
 *
 * Real waits, because the consumer is timer-driven.
 */
class LogDataProviderCoalescingTest {
    /**
     * Redirects `System.out` to a null sink before the capture is constructed.
     *
     * [DesktopLogCapture] snapshots `System.out` when built, so this makes its tee write
     * nowhere: the thousands of lines below stay out of the Gradle test report, and the
     * measurement is not dominated by console throughput.
     */
    private fun withCapture(block: (DesktopLogCapture) -> Unit) {
        val savedOut = System.out
        val savedErr = System.err
        val sink = PrintStream(OutputStream.nullOutputStream(), true, Charsets.UTF_8)
        try {
            System.setOut(sink)
            System.setErr(sink)
            val capture = DesktopLogCapture()
            try {
                capture.start()
                block(capture)
            } finally {
                capture.stop()
            }
        } finally {
            System.setOut(savedOut)
            System.setErr(savedErr)
        }
    }

    @Test
    fun `a burst of captured lines produces far fewer rebuilds than lines`() {
        withCapture { capture ->
            val provider = LogDataProviderImpl(logCapture = capture, rebuildIntervalMs = 50L)
            try {
                // Counted at the source rather than sampled - see `rebuildCount`. The
                // construction-time rebuild is excluded so the bound is about the burst.
                val before = provider.rebuildCount

                val lines = 2_000
                repeat(lines) { println("burst $it") }
                // Let the consumer settle so the last coalesced rebuild is included.
                Thread.sleep(400)
                val rebuilds = provider.rebuildCount - before

                // The point of the change: rebuilds are bounded by elapsed time over the
                // interval, not by line count. Pre-fix this was one rebuild per line, so the
                // bound is stated against `lines` to stay meaningful if the burst size moves.
                assertTrue(
                    rebuilds < lines / 10,
                    "$lines captured lines caused $rebuilds rebuilds - the listener is " +
                        "rebuilding per line again",
                )
                // …and the control: it did rebuild. A provider whose consumer never ran would
                // pass the bound above on zero.
                assertTrue(rebuilds >= 1, "the provider never rebuilt at all, so the bound above proves nothing")

                // The coalescing must not lose the tail: whatever the last rebuild published
                // has to include the final line, or the panel silently trails the log.
                val published = provider.logs.value.map { it.message }
                assertTrue(
                    published.any { it.contains("burst ${lines - 1}") },
                    "the last captured line never reached the published list",
                )
            } finally {
                provider.dispose()
            }
        }
    }

    @Test
    fun `dispose stops rebuilding and stops listening`() {
        withCapture { capture ->
            val before = capture.listenerCount
            val provider = LogDataProviderImpl(logCapture = capture, rebuildIntervalMs = 20L)
            assertEquals(
                before + 1,
                capture.listenerCount,
                "setup: the provider must register a listener for its removal to mean anything",
            )
            println("before dispose")
            Thread.sleep(200)
            val settled = provider.logs.value
            assertTrue(
                settled.any { it.message.contains("before dispose") },
                "setup: the provider must be publishing before dispose is tested",
            )

            provider.dispose()

            // Both halves, asserted separately because one hides the other: a cancelled
            // consumer publishes nothing whether or not the listener is still attached, so
            // the `logs.value` check below passes on a leaked listener. Only the count sees
            // it - and a listener left on a process-wide singleton is the leak that
            // accumulates per window.
            assertEquals(
                before,
                capture.listenerCount,
                "dispose() left its listener registered on the process-wide capture",
            )

            repeat(500) { println("after dispose $it") }
            Thread.sleep(300)

            assertTrue(
                provider.logs.value === settled,
                "the provider published after dispose() - its consumer is still running",
            )
            // The capture itself is unaffected: dispose releases this provider, not the
            // process-wide buffer other windows may still be reading.
            assertTrue(
                capture.getLogs().any { it.message.contains("after dispose 499") },
                "dispose() must not stop the underlying capture",
            )
        }
    }

    @Test
    fun `a filter change republishes without waiting for another log line`() {
        withCapture { capture ->
            val provider = LogDataProviderImpl(logCapture = capture, rebuildIntervalMs = 20L)
            try {
                println("to stderr never")
                System.err.println("only on stderr")
                Thread.sleep(200)
                assertTrue(provider.logs.value.size >= 2, "setup: both lines should be published")

                // Routed through the same request channel as a log line, so the single
                // consumer owns every write to `logs`. It still has to land: a quiet app
                // produces no further lines to trigger it.
                provider.setFilter(ai.rever.boss.plugin.api.LogFilterData.STDERR)

                val deadline = System.currentTimeMillis() + 2_000
                var messages = provider.logs.value.map { it.message }
                while (System.currentTimeMillis() < deadline && messages.any { it.contains("to stderr never") }) {
                    Thread.sleep(5)
                    messages = provider.logs.value.map { it.message }
                }
                assertEquals(
                    emptyList(),
                    messages.filter { it.contains("to stderr never") },
                    "a filter change never republished, so the panel would show the old filter until the next log line",
                )
                assertTrue(
                    messages.any { it.contains("only on stderr") },
                    "the stderr filter dropped the stderr line too",
                )
            } finally {
                provider.dispose()
            }
        }
    }
}
