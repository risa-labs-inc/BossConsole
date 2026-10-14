package ai.rever.boss.git

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val SHORT_TIMEOUT_MILLIS = 100L
private const val NORMAL_TIMEOUT_MILLIS = 5_000L
private const val CANCELLATION_TIMEOUT_MILLIS = 30_000L
private const val READER_START_TIMEOUT_SECONDS = 5L
private const val TEST_TIMEOUT_SECONDS = 15L

@Timeout(TEST_TIMEOUT_SECONDS)
class GitCloneProcessTest {
    @Test
    fun `silent output times out and releases the process reader and partial destination`(
        @TempDir tempDirectory: File,
    ) = runBlocking {
        val output = SilentInputStream()
        val process = ControlledProcess(processOutput = output)
        val cleanupCalls = AtomicInteger()
        val partialClone =
            File(tempDirectory, "partial-clone").apply {
                assertTrue(mkdirs())
                File(this, "partial-file").writeText("partial")
            }

        assertFailsWith<TimeoutCancellationException> {
            runCloneProcess(
                process = process,
                timeoutMillis = SHORT_TIMEOUT_MILLIS,
                onOutputLine = {},
                onCancellation = {
                    cleanupCalls.incrementAndGet()
                    partialClone.deleteRecursively()
                },
            )
        }

        assertFalse(process.isAlive, "the timed-out process must be terminated")
        assertTrue(process.wasForciblyDestroyed, "timeout must force process termination")
        assertTrue(output.wasClosed, "timeout must close the process output stream")
        assertTrue(output.readFinished, "the blocked output reader must finish")
        assertEquals(1, cleanupCalls.get(), "partial-clone cleanup must run exactly once")
        assertFalse(partialClone.exists(), "the partial clone destination must be removed")
    }

    @Test
    fun `caller cancellation terminates a silent process and propagates cancellation`() =
        runBlocking {
            val output = SilentInputStream()
            val process = ControlledProcess(processOutput = output)
            val cleanupCalls = AtomicInteger()

            val operation =
                async(Dispatchers.Default) {
                    runCloneProcess(
                        process = process,
                        timeoutMillis = CANCELLATION_TIMEOUT_MILLIS,
                        onOutputLine = {},
                        onCancellation = {
                            cleanupCalls.incrementAndGet()
                        },
                    )
                }

            assertTrue(
                output.awaitReaderStarted(),
                "the output reader did not enter the silent read",
            )

            operation.cancel()

            assertFailsWith<CancellationException> {
                operation.await()
            }

            assertFalse(process.isAlive, "the cancelled process must be terminated")
            assertTrue(process.wasForciblyDestroyed, "cancellation must force process termination")
            assertTrue(output.wasClosed, "cancellation must close the process output stream")
            assertTrue(output.readFinished, "the blocked output reader must finish")
            assertEquals(1, cleanupCalls.get(), "partial-clone cleanup must run exactly once")
        }

    @Test
    fun `successful process streams every line and returns zero`() =
        runBlocking {
            val process =
                ControlledProcess(
                    processOutput =
                        ByteArrayInputStream(
                            "Cloning repository...\nReceiving objects: 100%\n"
                                .toByteArray(Charsets.UTF_8),
                        ),
                    exitCode = 0,
                    initiallyExited = true,
                )
            val lines = mutableListOf<String>()
            val cleanupCalls = AtomicInteger()

            val exitCode =
                runCloneProcess(
                    process = process,
                    timeoutMillis = NORMAL_TIMEOUT_MILLIS,
                    onOutputLine = lines::add,
                    onCancellation = {
                        cleanupCalls.incrementAndGet()
                    },
                )

            assertEquals(0, exitCode)
            assertEquals(
                listOf("Cloning repository...", "Receiving objects: 100%"),
                lines,
            )
            assertFalse(process.wasForciblyDestroyed)
            assertEquals(0, cleanupCalls.get())
        }

    @Test
    fun `nonzero process exit is returned without cancellation cleanup`() =
        runBlocking {
            val process =
                ControlledProcess(
                    processOutput =
                        ByteArrayInputStream(
                            "fatal: repository not found\n".toByteArray(Charsets.UTF_8),
                        ),
                    exitCode = 128,
                    initiallyExited = true,
                )
            val lines = mutableListOf<String>()
            val cleanupCalls = AtomicInteger()

            val exitCode =
                runCloneProcess(
                    process = process,
                    timeoutMillis = NORMAL_TIMEOUT_MILLIS,
                    onOutputLine = lines::add,
                    onCancellation = {
                        cleanupCalls.incrementAndGet()
                    },
                )

            assertEquals(128, exitCode)
            assertEquals(listOf("fatal: repository not found"), lines)
            assertFalse(process.wasForciblyDestroyed)
            assertEquals(0, cleanupCalls.get())
        }

    @Test
    fun `successful exit remains authoritative when an entered progress callback is late`() =
        runBlocking {
            val process =
                ControlledProcess(
                    processOutput =
                        ByteArrayInputStream(
                            "late progress\n".toByteArray(Charsets.UTF_8),
                        ),
                    exitCode = 0,
                    initiallyExited = true,
                )
            val callbackEntered = CountDownLatch(1)
            val neverReleased = CountDownLatch(1)
            val outputClosed = AtomicBoolean()
            val callbackSettled = AtomicBoolean()
            val outputFailure = AtomicReference<Throwable?>()

            val exitCode =
                runCloneProcess(
                    process = process,
                    timeoutMillis = NORMAL_TIMEOUT_MILLIS,
                    onOutputLine = {
                        callbackEntered.countDown()
                        try {
                            neverReleased.await()
                        } finally {
                            callbackSettled.set(true)
                            assertFalse(outputClosed.get(), "callback must settle before output closes")
                        }
                    },
                    onCancellation = {},
                    outputLifecycle =
                        CloneOutputLifecycle(
                            onClosed = {
                                outputClosed.set(true)
                            },
                            onFailure = outputFailure::set,
                        ),
                )

            assertEquals(0, exitCode, "the successful Git exit must remain authoritative")
            assertTrue(callbackEntered.count == 0L, "the progress callback was never entered")
            assertTrue(outputClosed.get(), "terminal completion must close progress publication")
            assertTrue(callbackSettled.get(), "callback must settle before the operation returns")
            assertTrue(
                outputFailure.get() is TimeoutCancellationException,
                "the delayed reader should be reported as an advisory output failure",
            )
            assertFalse(process.wasForciblyDestroyed, "a successful process must not be treated as failed")
        }

    @Test
    fun `cancellation interrupts an entered callback before cleanup returns`() =
        runBlocking {
            val process = ControlledProcess(ByteArrayInputStream("progress\n".toByteArray()))
            val entered = CountDownLatch(1)
            val settled = AtomicBoolean()
            val cleanupCalls = AtomicInteger()
            val operation =
                async(Dispatchers.Default) {
                    runCloneProcess(
                        process,
                        CANCELLATION_TIMEOUT_MILLIS,
                        onOutputLine = {
                            entered.countDown()
                            try {
                                CountDownLatch(1).await()
                            } finally {
                                settled.set(true)
                            }
                        },
                        onCancellation = { cleanupCalls.incrementAndGet() },
                    )
                }
            assertTrue(entered.await(READER_START_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            operation.cancel()
            assertFailsWith<CancellationException> { operation.await() }
            assertTrue(settled.get(), "no entered callback may survive operation completion")
            assertFalse(process.isAlive)
            assertEquals(1, cleanupCalls.get())
        }

    @Test
    fun `process exit interrupts an entered callback and preserves success`() =
        runBlocking {
            val process = ControlledProcess(ByteArrayInputStream("progress\n".toByteArray()))
            val entered = CountDownLatch(1)
            val settled = AtomicBoolean()
            val operation =
                async(Dispatchers.Default) {
                    runCloneProcess(process, CANCELLATION_TIMEOUT_MILLIS, {
                        entered.countDown()
                        try {
                            CountDownLatch(1).await()
                        } finally {
                            settled.set(true)
                        }
                    }, {})
                }
            assertTrue(entered.await(READER_START_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            process.destroy() // ControlledProcess models a normal exit here.
            assertEquals(0, operation.await())
            assertTrue(settled.get())
            assertFalse(process.wasForciblyDestroyed)
        }

    @Test
    fun `progress callback failure is advisory and preserves a successful clone`() =
        runBlocking {
            val process = ControlledProcess(ByteArrayInputStream("progress\n".toByteArray()))
            val observed = CountDownLatch(1)
            val failures = AtomicInteger()
            val cleanupCalls = AtomicInteger()
            val operation =
                async(Dispatchers.Default) {
                    runCloneProcess(
                        process,
                        CANCELLATION_TIMEOUT_MILLIS,
                        onOutputLine = { throw IOException("progress callback failed") },
                        onCancellation = { cleanupCalls.incrementAndGet() },
                        outputLifecycle =
                            CloneOutputLifecycle(onFailure = {
                                failures.incrementAndGet()
                                observed.countDown()
                            }),
                    )
                }
            assertTrue(observed.await(READER_START_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(process.isAlive, "an advisory callback fault must not kill Git")
            process.destroy()
            assertEquals(0, operation.await())
            assertEquals(1, failures.get())
            assertEquals(0, cleanupCalls.get())
            assertFalse(process.wasForciblyDestroyed)
        }

    @Test
    fun `pipe failure after actual success preserves exit when notification is delayed`() =
        runBlocking {
            val process =
                ControlledProcess(
                    processOutput =
                        object : InputStream() {
                            override fun read(): Int = throw IOException("pipe closed after exit")
                        },
                    initiallyExited = true,
                    deferExitNotification = true,
                )
            val cleanups = AtomicInteger()
            val exitCode = runCloneProcess(process, NORMAL_TIMEOUT_MILLIS, {}, { cleanups.incrementAndGet() })
            assertEquals(0, exitCode)
            assertEquals(0, cleanups.get())
            assertFalse(process.wasForciblyDestroyed)
        }

    @Test
    fun `reader failure aborts through central cleanup`() =
        runBlocking {
            val process =
                ControlledProcess(
                    object : InputStream() {
                        override fun read(): Int = throw IOException("pipe failed")
                    },
                )
            val cleanups = AtomicInteger()
            assertFailsWith<IOException> {
                runCloneProcess(process, NORMAL_TIMEOUT_MILLIS, {}, { cleanups.incrementAndGet() })
            }
            assertFalse(process.isAlive)
            assertEquals(1, cleanups.get())
        }
}

private class ControlledProcess(
    private val processOutput: InputStream,
    private val exitCode: Int = 0,
    initiallyExited: Boolean = false,
    private val deferExitNotification: Boolean = false,
) : Process() {
    private val exitFuture = CompletableFuture<Process>()
    private val alive = AtomicBoolean(!initiallyExited)
    private val processInput = ByteArrayOutputStream()
    private val processError = ByteArrayInputStream(ByteArray(0))
    private val forciblyDestroyed = AtomicBoolean()

    val wasForciblyDestroyed: Boolean
        get() = forciblyDestroyed.get()

    init {
        if (initiallyExited) {
            exitFuture.complete(this)
        }
    }

    override fun getOutputStream(): OutputStream = processInput

    override fun getInputStream(): InputStream = processOutput

    override fun getErrorStream(): InputStream = processError

    override fun waitFor(): Int {
        exitFuture.get()
        return exitCode
    }

    override fun waitFor(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean =
        try {
            exitFuture.get(timeout, unit)
            true
        } catch (_: TimeoutException) {
            false
        }

    override fun exitValue(): Int {
        if (alive.get()) {
            throw IllegalThreadStateException("Process has not exited")
        }
        return exitCode
    }

    override fun destroy() {
        finish()
    }

    override fun destroyForcibly(): Process {
        forciblyDestroyed.set(true)
        finish()
        return this
    }

    override fun isAlive(): Boolean = alive.get()

    override fun onExit(): CompletableFuture<Process> = if (deferExitNotification) CompletableFuture() else exitFuture

    private fun finish() {
        alive.set(false)
        exitFuture.complete(this)
    }
}

private class SilentInputStream : InputStream() {
    private val readerStarted = CountDownLatch(1)
    private val closedSignal = CountDownLatch(1)
    private val readerFinished = CountDownLatch(1)

    @Volatile
    var wasClosed: Boolean = false
        private set

    val readFinished: Boolean
        get() = readerFinished.count == 0L

    override fun read(): Int {
        readerStarted.countDown()
        return try {
            closedSignal.await()
            -1
        } finally {
            readerFinished.countDown()
        }
    }

    override fun close() {
        wasClosed = true
        closedSignal.countDown()
    }

    fun awaitReaderStarted(): Boolean =
        readerStarted.await(
            READER_START_TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
        )
}
