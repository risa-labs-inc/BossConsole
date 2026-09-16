package ai.rever.boss.app.terminal

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
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
}
