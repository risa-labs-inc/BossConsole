package ai.rever.boss.app.terminal

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression for the bug where `TerminalSession.startPump` busy-spins on every
 * quiet child with `Thread.sleep(10)` - 100 wakeups per second per idle session,
 * and the OS scheduler gives us those wakeups whether we have data or not. See
 * issue #1312.
 *
 * The helper extracted for the fix - `drainWhileAlive` - uses a blocking read
 * instead, so a quiet child parks the pump thread on the pipe until the first
 * byte arrives or the process closes its end of the pipe. The test exercises
 * the helper directly with a child that never produces output and asserts the
 * pump thread burned a small fraction of the wall-clock budget, the way a real
 * idle terminal would.
 */
class DrainWhileAliveQuietTest {
    @Test
    fun `drainWhileAlive does not busy-spin on a quiet child`() {
        val process = quietProcess()
        try {
            val readCalls = AtomicLong(0)
            val t0 = System.nanoTime()
            val thread =
                Thread {
                    drainWhileAlive(
                        input = process.inputStream,
                        buffer = ByteArray(4096),
                        onChunk = { _, _ -> },
                        onReadAttempt = { readCalls.incrementAndGet() },
                        isAlive = { process.isAlive },
                    )
                }
            thread.start()
            Thread.sleep(WATCH_MS)
            // Stop the helper by destroying the process: the read returns -1 (EOF),
            // the loop exits, and the test can read the iteration counter.
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
            thread.join(2_000)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)

            // A busy-spin with Thread.sleep(10) would yield ~ WATCH_MS / 10 loop
            // iterations: ~50 at 500 ms. A blocking read yields a single read - the
            // one that wakes for EOF. Allow a small slack for the JVM scheduler.
            assertTrue(
                readCalls.get() < 20,
                "drainWhileAlive should not busy-spin; observed ${readCalls.get()} read attempts in ${WATCH_MS}ms",
            )
            assertTrue(
                elapsedMs >= WATCH_MS,
                "the pump must actually wait, not return immediately (elapsed=${elapsedMs}ms)",
            )
        } finally {
            process.destroyForcibly()
        }
    }

    private fun quietProcess(): Process {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val command =
            if (isWindows) {
                // `pause` prints "Press any key to continue..." and waits for stdin,
                // which never arrives - a quiet child without further resources.
                listOf("cmd", "/c", "pause")
            } else {
                listOf("sleep", "60")
            }
        return ProcessBuilder(command).start()
    }

    companion object {
        private const val WATCH_MS = 500L
    }
}
