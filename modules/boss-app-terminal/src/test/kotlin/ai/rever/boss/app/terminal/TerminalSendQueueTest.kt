package ai.rever.boss.app.terminal

import com.google.rpc.RetryInfo
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.protobuf.StatusProto
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalSendQueueTest {
    @Test
    fun `non-positive input queue timeout is rejected`() {
        listOf(0L, -1L).forEach { timeout ->
            assertFailsWith<IllegalArgumentException> {
                TerminalSession(
                    "fixture",
                    "/fixture",
                    listOf("fixture"),
                    BlockedInputProcess(),
                    80,
                    24,
                    inputQueueTimeoutMillis = timeout,
                )
            }
        }
    }

    @Test
    fun `concurrent writers queue and shed with retry-after instead of spinning`() {
        val process = BlockedInputProcess()
        val queueMillis = 300L
        val session =
            TerminalSession(
                "fixture",
                "/fixture",
                listOf("fixture"),
                process,
                80,
                24,
                inputQueueTimeoutMillis = queueMillis,
            )
        // A writer parked inside the child pipe holds the input lock for the whole test.
        val holder = CompletableFuture.runAsync { runBlocking { session.send(byteArrayOf(0)) } }
        try {
            assertTrue(process.writeStarted.await(5, TimeUnit.SECONDS))

            val writers = 6
            val finished = CountDownLatch(writers)
            val failures = ConcurrentLinkedQueue<StatusRuntimeException>()
            val waits = ConcurrentLinkedQueue<Long>()
            repeat(writers) {
                CompletableFuture.runAsync {
                    val started = System.nanoTime()
                    try {
                        runBlocking { session.send(byteArrayOf(1)) }
                    } catch (failure: StatusRuntimeException) {
                        waits += TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                        failures += failure
                    } finally {
                        finished.countDown()
                    }
                }
            }
            assertTrue(finished.await(30, TimeUnit.SECONDS))
            assertEquals(writers, failures.size)
            failures.forEach { failure ->
                assertEquals(Status.Code.RESOURCE_EXHAUSTED, failure.status.code)
                val retryDelay =
                    checkNotNull(StatusProto.fromThrowable(failure))
                        .detailsList
                        .filter { it.`is`(RetryInfo::class.java) }
                        .map { it.unpack(RetryInfo::class.java).retryDelay }
                        .single()
                assertTrue(retryDelay.seconds > 0 || retryDelay.nanos > 0)
            }
            // Every shed caller parked on the bound instead of failing into an instant retry.
            waits.forEach { wait -> assertTrue(wait >= queueMillis / 2, "shed after ${wait}ms") }
        } finally {
            process.releaseWrite.countDown()
            holder.get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `a writer queued behind a slow pipe proceeds when it drains`() =
        runBlocking {
            val process = BlockedInputProcess()
            val session = TerminalSession("fixture", "/fixture", listOf("fixture"), process, 80, 24)
            val holder = CompletableFuture.runAsync { runBlocking { session.send(byteArrayOf(0)) } }
            try {
                assertTrue(process.writeStarted.await(5, TimeUnit.SECONDS))
                val queued = launch { session.send(byteArrayOf(1)) }
                // Still parked on the lock; an instant RESOURCE_EXHAUSTED would finish the job.
                delay(200)
                assertTrue(queued.isActive)
                process.releaseWrite.countDown()
                queued.join()
                holder.get(5, TimeUnit.SECONDS)
                // Queued writers drain in arrival order after the holder releases the pipe.
                assertEquals(listOf(0.toByte(), 1.toByte()), process.writes.toList())
            } finally {
                process.releaseWrite.countDown()
            }
        }

    @Test
    fun `a queued writer suspends without pinning its dispatcher thread`() =
        runBlocking {
            val process = BlockedInputProcess()
            val session = TerminalSession("fixture", "/fixture", listOf("fixture"), process, 80, 24)
            val holder = CompletableFuture.runAsync { runBlocking { session.send(byteArrayOf(0)) } }
            val executor = Executors.newSingleThreadExecutor()
            try {
                assertTrue(process.writeStarted.await(5, TimeUnit.SECONDS))
                val dispatcher = executor.asCoroutineDispatcher()
                val queued = launch(dispatcher) { session.send(byteArrayOf(1)) }
                delay(200)
                assertTrue(queued.isActive)
                // The queue wait suspends, so the single dispatcher thread still runs
                // other coroutines; a parked tryLock would deadlock this probe.
                val probe = async(dispatcher) { true }
                assertTrue(withTimeout(5_000) { probe.await() })
                queued.cancelAndJoin()
            } finally {
                executor.shutdown()
                process.releaseWrite.countDown()
                holder.get(5, TimeUnit.SECONDS)
            }
        }

    @Test
    fun `cancelling a queued writer abandons its write`() =
        runBlocking {
            val process = BlockedInputProcess()
            val session = TerminalSession("fixture", "/fixture", listOf("fixture"), process, 80, 24)
            val holder = CompletableFuture.runAsync { runBlocking { session.send(byteArrayOf(0)) } }
            try {
                assertTrue(process.writeStarted.await(5, TimeUnit.SECONDS))
                val queued = launch { session.send(byteArrayOf(1)) }
                delay(200)
                assertTrue(queued.isActive)
                // The caller gave up while the write was still queued; once the pipe
                // drains nothing may send those bytes on a dead call's behalf.
                queued.cancelAndJoin()
                process.releaseWrite.countDown()
                holder.get(5, TimeUnit.SECONDS)
                assertEquals(listOf(0.toByte()), process.writes.toList())
            } finally {
                process.releaseWrite.countDown()
            }
        }

    @Test
    fun `cancelling a queued closeStdin abandons closing input`(): Unit =
        runBlocking {
            val process = BlockedInputProcess()
            // inputWriteTimeoutMillis = 60_000 ensures slow runners cannot reach discardInput (5s default)
            // while the holder write is held, which would close stdin for reasons unrelated to cancellation.
            val session =
                TerminalSession(
                    "fixture",
                    "/fixture",
                    listOf("fixture"),
                    process,
                    80,
                    24,
                    inputWriteTimeoutMillis = 60_000,
                )
            val holder = CompletableFuture.runAsync { runBlocking { session.send(byteArrayOf(0)) } }
            try {
                assertTrue(process.writeStarted.await(5, TimeUnit.SECONDS))
                val queued = launch { session.closeStdin() }
                delay(200)
                assertTrue(queued.isActive)
                // The caller gave up while closeStdin was queued on the input lock; once the pipe
                // drains nothing may deliver EOF on a dead call's behalf.
                queued.cancelAndJoin()
                process.releaseWrite.countDown()
                holder.get(5, TimeUnit.SECONDS)
                assertFalse(process.stdinClosed.get(), "stdin must not be closed after cancellation")
            } finally {
                process.releaseWrite.countDown()
            }
        }

    @Test
    fun `cancelling a caller during uncontended acquireInputLock releases the mutex`(): Unit =
        runBlocking {
            val process = BlockedInputProcess()
            val session =
                TerminalSession(
                    "fixture",
                    "/fixture",
                    listOf("fixture"),
                    process,
                    80,
                    24,
                    inputWriteTimeoutMillis = 60_000,
                )
            // An uncontended caller cancelled around acquireInputLock must not leak the ticket lock (#1778).
            val job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    cancel()
                    session.send(byteArrayOf(42))
                }
            job.join()
            assertTrue(job.isCancelled)

            // If the ticket lock had been leaked, the subsequent send would time out with RESOURCE_EXHAUSTED.
            // With the fix, the mutex was returned on CancellationException and the subsequent send succeeds.
            val subsequent = launch(Dispatchers.IO) { session.send(byteArrayOf(99)) }
            assertTrue(process.writeStarted.await(5, TimeUnit.SECONDS), "Subsequent send must acquire the input lock")
            process.releaseWrite.countDown()
            subsequent.join()
            assertTrue(process.writes.contains(99.toByte()), "Subsequent write must be delivered")
        }

    private class BlockedInputProcess : Process() {
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val writes = ConcurrentLinkedQueue<Byte>()
        val stdinClosed = AtomicBoolean(false)
        private val stdin =
            object : OutputStream() {
                override fun write(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) {
                    writes += bytes[offset]
                    writeStarted.countDown()
                    try {
                        releaseWrite.await()
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("interrupted", interrupted)
                    }
                }

                override fun write(byte: Int) = write(byteArrayOf(byte.toByte()), 0, 1)

                override fun close() {
                    stdinClosed.set(true)
                }
            }

        override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())

        override fun getOutputStream(): OutputStream = stdin

        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())

        override fun waitFor(): Int = 0

        override fun exitValue(): Int = 0

        override fun isAlive() = true

        override fun destroy() = Unit
    }
}
