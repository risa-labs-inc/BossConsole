package ai.rever.boss.logging

import java.io.OutputStream
import java.io.PrintStream
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the cost of capturing a line, and the bookkeeping that made it cheap.
 *
 * The capture buffer is a `ConcurrentLinkedQueue`, whose `size()` is O(n) by contract - it walks
 * the list. The trim that enforced the 10k cap called it once per captured line, and re-read it
 * on every iteration of its own loop, so a full buffer meant a 10,000-node walk per line. The tee
 * runs inside `PrintStream.write`, so that walk was paid while holding the `System.out` monitor
 * and every other thread in the process that wanted to log queued behind it. A component logging
 * stack traces a few times a second was enough to stall threads that had nothing to do with
 * logging, which is how one dead browser handle froze the whole app.
 *
 * The timing test is the one that would have caught it. The counter tests exist because replacing
 * `size()` with a tracked count introduces a way to be wrong that the old code could not be:
 * a count that drifts from the queue trims a buffer that is not full, silently discarding logs.
 *
 * These tests install and restore `System.out` / `System.err`, so they must not run in parallel
 * with anything asserting on stdout.
 */
class DesktopLogCaptureTrimTest {
    /**
     * Runs [block] against a started capture whose tee writes to a null sink.
     *
     * The redirect happens BEFORE the constructor on purpose: [DesktopLogCapture] snapshots
     * `System.out` when it is built, so this makes its `originalOut` the null sink and the tee
     * writes nowhere. Two things follow. The ~30,000 lines these tests produce stop going to
     * the real stdout and into the Gradle test report; and, more importantly, the per-line cost
     * of writing to a console - which by this file's own analysis is what swamps the signal -
     * comes out of both halves of the ratio measurement.
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
            // stop() restores what the capture saw at construction, which is the sink. Restore
            // unconditionally so a failure inside block() cannot leave the suite without a
            // usable stdout.
            System.setOut(savedOut)
            System.setErr(savedErr)
        }
    }

    /**
     * The test's own lines, ignoring anything else that reached the buffer.
     *
     * Exact counts over the whole buffer are brittle here: it captures global `System.out`, so
     * any other output in this JVM lands in it - and the capture itself contributes, since
     * `start()` logs "Log capture started" AFTER installing the tee and `clear()` logs at debug.
     * The latter is not hypothetical: an exact-count assertion after `clear()` fails outright
     * under `BOSS_LOG_LEVEL=DEBUG`.
     */
    private fun DesktopLogCapture.linesTagged(tag: String): List<String> =
        getLogs()
            .map { it.message }
            .filter { it.contains(tag) }

    /**
     * Compares capturing into a full buffer against capturing into an unfilled one.
     *
     * A **ratio**, not a wall-clock bound. A first attempt at this test asserted "30k lines in
     * under 20s" and passed against the very bug it was written for, because per-line cost
     * varies by machine and swamps an absolute threshold. What distinguishes the two
     * implementations is whether the per-line cost depends on how much is already buffered,
     * which is what the ratio measures and what cancels the machine out.
     *
     * Both sides write the same number of lines, in batches under the cap, so the baseline is
     * genuinely "the trim never fires" rather than "it fires for most of the run". Spread over
     * several fresh captures for that reason: one baseline long enough to time reliably would
     * itself fill the buffer and start trimming.
     *
     * Measured either side of the fix on one machine: **5.1x** with the `size()` trim
     * (5121ms full vs 1006ms baseline) against **0.9x** with the counter (9ms vs 10ms). Note
     * how much the *absolute* baseline moves - 1006ms to 10ms - because `while (size > cap)`
     * evaluates an O(n) walk once per line even while the buffer is still filling. That is
     * also why the ratio is 5x rather than the ~70x a full-buffer-only comparison shows: the
     * old code makes the control slow too, which flatters it here. 3.0 sits between the two
     * with headroom for a loaded machine on the ~10ms side.
     */
    @Test
    fun `the cost of capturing a line does not grow with the buffer`() {
        // Under the 10k cap, so nothing is trimmed while the baseline is being measured.
        val batch = 9_000
        val rounds = 5

        var baseline = 0L
        repeat(rounds) {
            withCapture {
                baseline += measureTimeMillis { repeat(batch) { println("empty $it") } }
            }
        }

        var fullBuffer = 0L
        withCapture { capture ->
            repeat(10_000) { println("fill $it") }
            assertEquals(10_000, capture.getLogs().size, "buffer should hold exactly the cap")
            // The same total line count as the baseline, every line of it trimming.
            repeat(rounds) {
                fullBuffer += measureTimeMillis { repeat(batch) { println("full $it") } }
            }
        }

        val ratio = fullBuffer.toDouble() / maxOf(baseline, 1)
        assertTrue(
            ratio < 3.0,
            "capturing $rounds x $batch lines into a full buffer cost ${ratio}x the same count " +
                "into an unfilled one (${fullBuffer}ms vs ${baseline}ms) - " +
                "the per-line trim scales with the buffer again",
        )
    }

    @Test
    fun `the buffer holds the newest lines, not the oldest`() {
        withCapture { capture ->
            repeat(10_050) { println("seq $it") }
            // The buffer holds the cap; OUR lines are fewer, because the capture's own
            // "Log capture started" got in ahead of them and was dropped from the front.
            assertEquals(10_000, capture.getLogs().size, "buffer should hold exactly the cap")
            val seq = capture.linesTagged("seq ")
            // Oldest dropped first, so whatever survives ends at the newest line.
            assertTrue(seq.last().endsWith("seq 10049"), "unexpected last line: ${seq.last()}")
            // …and the front was trimmed rather than the back: 10050 lines into a 10000 cap
            // cannot all be present.
            assertTrue(seq.size in 9_000..10_000, "unexpected surviving count: ${seq.size}")
            assertFalse(seq.any { it.endsWith("seq 0") }, "the oldest line survived a full buffer")
        }
    }

    @Test
    fun `clear resets the count so a later burst is not over-trimmed`() {
        withCapture { capture ->
            // Fill past the cap so the tracked count is at its ceiling.
            repeat(12_000) { println("first $it") }
            assertEquals(10_000, capture.getLogs().size)

            capture.clear()

            // A count left overstated by clear() would trim these away as if the buffer were
            // still full, and the panel would show almost nothing after a Clear. Counted by
            // tag, not by buffer size: clear() itself logs at debug, so an exact size here
            // fails under BOSS_LOG_LEVEL=DEBUG.
            repeat(100) { println("second $it") }
            assertEquals(
                100,
                capture.linesTagged("second ").size,
                "lines after clear() were trimmed against a stale count",
            )
            assertEquals(0, capture.linesTagged("first ").size, "clear() left earlier lines behind")
        }
    }
}
