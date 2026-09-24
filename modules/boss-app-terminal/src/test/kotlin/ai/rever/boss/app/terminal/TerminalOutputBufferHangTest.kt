package ai.rever.boss.app.terminal

import ai.rever.boss.ipc.proto.services.TerminalOutputChunk
import com.google.protobuf.ByteString
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression for the bug where `TerminalOutputBuffer.stream` hangs forever if
 * the pump exits without ever appending an exit chunk. See issue #1314.
 *
 * The pump's finally block calls `process.onExit().join()` which can throw if
 * the process has already been destroyed by `terminate()`/`destroyForcibly()`,
 * skipping the exit-chunk append. Every subscriber parked on `stream()` then
 * waits forever for `finished` to flip, holding the last chunk on screen.
 *
 * The fix is `TerminalOutputBuffer.close()`: it marks the buffer finished and
 * emits one last exit chunk so every collector sees the closure. The pump's
 * outer finally calls it in a `runCatching` so a throw on `onExit()` cannot
 * leak the buffer.
 */
class TerminalOutputBufferHangTest {
    @Test
    fun `stream completes after close even when no exit chunk was appended`() =
        runBlocking {
            val buffer = TerminalOutputBuffer()
            // Append a non-exit chunk: this is what the pump does on the happy path
            // before the exit chunk, and it is also what happens when the pump
            // exits via the IOException branch (which never appends the exit
            // chunk itself, only a "[Terminal output pipe closed]" notice).
            buffer.append(makeChunk("hello\n", isExit = false))

            val collected =
                withTimeoutOrNull(STREAM_TIMEOUT_MS) {
                    buffer.stream().toList()
                }
            // Bug: the stream is not finished, so first() / toList() blocks on the
            // bounded wait. The collector must time out.
            assertNull(
                collected,
                "stream() must not hang waiting for an exit chunk the pump never appended",
            )

            // Fix: `close()` should mark the buffer finished so the next collector
            // sees the closure immediately.
            buffer.close(exitCode = 137)
            val afterClose =
                withTimeoutOrNull(STREAM_TIMEOUT_MS) {
                    buffer.stream().toList()
                }
            assertNotNull(afterClose, "stream() must complete after close()")
            // The exit chunk carries the close's exit code so the UI shows the right
            // banner even when the pump never wrote one of its own.
            val lastChunk = afterClose.last()
            assertTrue(lastChunk.isExit, "the last chunk must be an exit chunk")
            assertEquals(137, lastChunk.exitCode)
        }

    @Test
    fun `close is safe to call multiple times`() {
        val buffer = TerminalOutputBuffer()
        buffer.append(makeChunk("a\n", isExit = false))
        buffer.close(exitCode = 0)
        // Second close must be a no-op, not throw, so the pump's outer finally
        // can call it without worrying whether the inner try already finished it.
        buffer.close(exitCode = 0)
    }

    @Test
    fun `stream still completes on an existing exit chunk after close`() =
        runBlocking {
            val buffer = TerminalOutputBuffer()
            buffer.append(makeChunk("bye\n", isExit = true))
            buffer.close(exitCode = 0)
            // Closing after a real exit chunk must not double-append: the stream
            // collector should see the original exit, not a second one.
            val chunks =
                withTimeout(STREAM_TIMEOUT_MS) {
                    buffer.stream().toList()
                }
            val exitChunks = chunks.filter { it.isExit }
            assertEquals(1, exitChunks.size, "close() must not synthesise a second exit chunk")
        }

    private fun makeChunk(
        text: String,
        isExit: Boolean,
    ): TerminalOutputChunk =
        TerminalOutputChunk
            .newBuilder()
            .setData(ByteString.copyFromUtf8(text))
            .setTimestamp(System.currentTimeMillis())
            .setIsExit(isExit)
            .build()

    @Test
    fun `stream collection throws TimeoutCancellationException after stream waits past deadline`() =
        runBlocking {
            val buffer = TerminalOutputBuffer()
            buffer.append(makeChunk("waiting\n", isExit = false))
            val timedOut =
                try {
                    withTimeout(STREAM_TIMEOUT_MS) { buffer.stream().toList() }
                    false
                } catch (_: TimeoutCancellationException) {
                    true
                }
            assertTrue(
                timedOut,
                "stream() must NOT hang silently: a consumer that does not pass a timeout " +
                    "gets stuck for the life of the collector",
            )
        }

    companion object {
        private const val STREAM_TIMEOUT_MS = 500L
    }
}
