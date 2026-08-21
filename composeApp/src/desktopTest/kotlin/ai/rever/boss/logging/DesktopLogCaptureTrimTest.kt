package ai.rever.boss.logging

import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
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
    private fun withCapture(block: (DesktopLogCapture) -> Unit) {
        val capture = DesktopLogCapture()
        val savedOut = System.out
        val savedErr = System.err
        try {
            capture.start()
            block(capture)
        } finally {
            capture.stop()
            // stop() restores what it saw at construction; make the restore unconditional so a
            // failure inside block() cannot leave the suite without a usable stdout.
            System.setOut(savedOut)
            System.setErr(savedErr)
        }
    }

    /**
     * Compares capturing into a full buffer against capturing into an empty one.
     *
     * A **ratio**, not a wall-clock bound. The cost per line is dominated by writing through to
     * the real stdout, which varies by machine and swamps an absolute threshold: a first attempt
     * at this test asserted "30k lines in under 20s" and passed against the very bug it was
     * written for. What actually distinguishes the two implementations is whether the per-line
     * cost depends on how much is already buffered - which is exactly what the ratio measures,
     * and which cancels out the machine.
     *
     * Measured either side of the fix on the same machine: 6.5x with the `size()` trim
     * (120ms empty vs 780ms full), 0.57x with the counter (21ms vs 12ms - the full-buffer pass
     * comes out faster, being the more JIT-warmed of the two). 3.0 sits clear of both.
     */
    @Test
    fun `the cost of capturing a line does not grow with the buffer`() {
        withCapture { capture ->
            // Trim never fires: the buffer is filling toward the cap.
            val emptyBuffer = measureTimeMillis { repeat(5_000) { println("empty $it") } }

            repeat(10_000) { println("fill $it") }
            assertEquals(10_000, capture.getLogs().size, "buffer should hold exactly the cap")

            // Trim fires on every line now, against a full buffer.
            val fullBuffer = measureTimeMillis { repeat(5_000) { println("full $it") } }

            val ratio = fullBuffer.toDouble() / maxOf(emptyBuffer, 1)
            assertTrue(
                ratio < 3.0,
                "capturing into a full buffer cost ${ratio}x an empty one " +
                    "(${fullBuffer}ms vs ${emptyBuffer}ms) - the per-line trim scales with the buffer again",
            )
        }
    }

    @Test
    fun `the buffer holds the newest lines, not the oldest`() {
        withCapture { capture ->
            repeat(10_050) { println("seq $it") }
            val logs = capture.getLogs()
            assertEquals(10_000, logs.size)
            // Oldest 50 dropped, so the window is [50, 10049].
            assertTrue(logs.first().message.endsWith("seq 50"), "unexpected first line: ${logs.first().message}")
            assertTrue(logs.last().message.endsWith("seq 10049"), "unexpected last line: ${logs.last().message}")
        }
    }

    @Test
    fun `clear resets the count so a later burst is not over-trimmed`() {
        withCapture { capture ->
            // Fill past the cap so the tracked count is at its ceiling.
            repeat(12_000) { println("first $it") }
            assertEquals(10_000, capture.getLogs().size)

            capture.clear()
            assertEquals(0, capture.getLogs().size)

            // A count left overstated by clear() would trim these away as if the buffer were
            // still full, and the panel would show almost nothing after a Clear.
            repeat(100) { println("second $it") }
            assertEquals(100, capture.getLogs().size, "lines after clear() were trimmed against a stale count")
        }
    }
}
