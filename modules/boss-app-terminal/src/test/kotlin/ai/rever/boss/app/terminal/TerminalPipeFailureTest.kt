package ai.rever.boss.app.terminal

import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalPipeFailureTest {
    @Test
    fun `broken output pipe does not kill the process or prematurely release admission`() {
        val process = BrokenPipeProcess()
        val stopped = CompletableFuture<Unit>()
        val session = TerminalSession("fixture", "/fixture", listOf("fixture"), process, 80, 24)
        session.startPump { stopped.complete(Unit) }
        assertTrue(process.readAttempt.await(5, TimeUnit.SECONDS))
        assertFailsWith<TimeoutException> { stopped.get(100, TimeUnit.MILLISECONDS) }
        assertFalse(process.killed)
        assertFalse(process.inputClosed)
        assertTrue(session.active)
        process.exit.complete(process)
        stopped.get(5, TimeUnit.SECONDS)
        assertFalse(session.active)
        assertTrue(process.inputClosed)
    }

    @Test
    fun `a stalled input write aborts within its bound closes the pipe and fails fast afterwards`() {
        val process = StalledInputProcess()
        val session =
            TerminalSession(
                "fixture",
                "/fixture",
                listOf("fixture"),
                process,
                80,
                24,
                inputWriteTimeoutMillis = 100,
            )
        try {
            val startedAt = System.nanoTime()
            val failure = assertFailsWith<StatusRuntimeException> { runBlocking { session.send(ByteArray(8)) } }
            // The bounded write returns promptly instead of hanging on an unread pipe forever.
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 5_000)
            assertEquals(Status.Code.ABORTED, failure.status.code)
            assertTrue(process.inputClosed.await(5, TimeUnit.SECONDS))
            val retry = assertFailsWith<StatusRuntimeException> { runBlocking { session.send(ByteArray(8)) } }
            assertEquals(Status.Code.FAILED_PRECONDITION, retry.status.code)
        } finally {
            process.unblock.countDown()
        }
    }

    @Test
    fun `input to a dead process is an error not a hang`() {
        val process = StalledInputProcess()
        process.exit.complete(process)
        val session = TerminalSession("fixture", "/fixture", listOf("fixture"), process, 80, 24)
        val failure = assertFailsWith<StatusRuntimeException> { runBlocking { session.send(ByteArray(1)) } }
        assertEquals(Status.Code.FAILED_PRECONDITION, failure.status.code)
    }

    private class BrokenPipeProcess : Process() {
        val readAttempt = CountDownLatch(1)
        val exit = CompletableFuture<Process>()

        @Volatile var killed = false

        @Volatile var inputClosed = false
        private val input =
            object : InputStream() {
                override fun available(): Int {
                    readAttempt.countDown()
                    throw IOException("simulated Windows broken pipe")
                }

                override fun read(): Int = -1

                override fun close() {
                    inputClosed = true
                }
            }

        override fun getInputStream() = input

        override fun getOutputStream() = ByteArrayOutputStream()

        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())

        override fun waitFor(): Int {
            exit.join()
            return 0
        }

        override fun exitValue(): Int = if (exit.isDone) 0 else throw IllegalThreadStateException()

        override fun isAlive() = !exit.isDone

        override fun onExit() = exit

        override fun destroy() {
            killed = true
            exit.complete(this)
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
    }

    private class StalledInputProcess : Process() {
        val unblock = CountDownLatch(1)
        val inputClosed = CountDownLatch(1)
        val exit = CompletableFuture<Process>()

        override fun getOutputStream(): OutputStream =
            object : OutputStream() {
                // Simulates a pipe nobody reads: the write never completes on its own.
                override fun write(b: Int) {
                    try {
                        unblock.await(10, TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }

                override fun close() {
                    inputClosed.countDown()
                }
            }

        override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())

        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())

        override fun waitFor(): Int {
            exit.join()
            return 0
        }

        override fun exitValue(): Int = if (exit.isDone) 0 else throw IllegalThreadStateException()

        override fun isAlive(): Boolean = !exit.isDone

        override fun onExit(): CompletableFuture<Process> = exit

        override fun destroy() {
            exit.complete(this)
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
    }
}
