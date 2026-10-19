package ai.rever.boss.utils

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for Issue #1254:
 * `CLIInstaller.updateWindowsPath` calls `process.waitFor()` with no timeout, which can cause
 * an IO thread to hang forever on a wedged `setx` command.
 *
 * Verifies that `updateWindowsPath` enforces a bounded wait, terminates hung processes
 * forcibly, drains output streams asynchronously, and falls back gracefully.
 */
class CLIInstallerWindowsPathTest {
    @Test
    fun `when binPath is already in PATH returns true without launching process`() {
        var processStarted = false
        val result =
            CLIInstaller.updateWindowsPath(
                binPath = "C:\\Users\\test\\bin",
                currentPathProvider = { "C:\\Windows\\system32;C:\\Users\\test\\bin;C:\\Windows" },
                processStarter = {
                    processStarted = true
                    FakeProcess(exitCode = 0)
                },
            )

        assertTrue(result)
        assertFalse(processStarted, "Process must not be launched when binPath is already in PATH")
    }

    @Test
    fun `when setx finishes with exit 0 within timeout returns true`() {
        var launchedCmd: List<String>? = null
        val result =
            CLIInstaller.updateWindowsPath(
                binPath = "C:\\Users\\test\\bin",
                currentPathProvider = { "C:\\Windows\\system32;C:\\Windows" },
                processStarter = { cmd ->
                    launchedCmd = cmd
                    FakeProcess(exitCode = 0, stdout = "SUCCESS: Specified value was saved.")
                },
            )

        assertTrue(result)
        assertEquals(listOf("cmd", "/c", "setx", "PATH", "C:\\Users\\test\\bin;%PATH%"), launchedCmd)
    }

    @Test
    fun `when setx exits with non-zero exit code returns false`() {
        val result =
            CLIInstaller.updateWindowsPath(
                binPath = "C:\\Users\\test\\bin",
                currentPathProvider = { "C:\\Windows\\system32" },
                processStarter = {
                    FakeProcess(exitCode = 1, stderr = "ERROR: Access is denied.")
                },
            )

        assertFalse(result)
    }

    @Test
    @Timeout(15, unit = TimeUnit.SECONDS)
    fun `when setx hangs past timeout process is forcibly destroyed and returns false`() {
        val fakeProc = FakeProcess(hang = true)
        val startedAt = System.currentTimeMillis()

        val result =
            CLIInstaller.updateWindowsPath(
                binPath = "C:\\Users\\test\\bin",
                timeoutSeconds = 1L,
                currentPathProvider = { "C:\\Windows\\system32" },
                processStarter = { fakeProc },
            )

        val elapsedMs = System.currentTimeMillis() - startedAt
        assertFalse(result, "Hung setx process must result in false return value")
        assertTrue(fakeProc.wasDestroyedForcibly.get(), "Process must be destroyed forcibly upon timeout")
        assertTrue(elapsedMs < 15_000, "Bounded wait must complete within timeout plus drain grace")
    }

    @Test
    fun `when process starter throws exception returns false gracefully`() {
        val result =
            CLIInstaller.updateWindowsPath(
                binPath = "C:\\Users\\test\\bin",
                currentPathProvider = { "C:\\Windows\\system32" },
                processStarter = { throw java.io.IOException("Cannot run program") },
            )

        assertFalse(result)
    }

    @Test
    @Timeout(15, unit = TimeUnit.SECONDS)
    fun `when stdout blocks until destroyForcibly bounded wait completes and returns false`() {
        val fakeProc = FakeProcess(hang = true, blockStdoutUntilDestroy = true)
        val startedAt = System.currentTimeMillis()

        val result =
            CLIInstaller.updateWindowsPath(
                binPath = "C:\\Users\\test\\bin",
                timeoutSeconds = 1L,
                currentPathProvider = { "C:\\Windows\\system32" },
                processStarter = { fakeProc },
            )

        val elapsedMs = System.currentTimeMillis() - startedAt
        assertFalse(result, "Process with blocking stdout must return false on timeout")
        assertTrue(fakeProc.wasDestroyedForcibly.get(), "Process must be destroyed forcibly upon timeout")
        assertTrue(elapsedMs < 15_000, "Bounded wait must complete within timeout plus drain grace")
    }

    @Test
    fun `when interrupted during execution process is destroyed and interrupt flag restored`() {
        val fakeProc = FakeProcess(hang = true, throwOnWaitFor = InterruptedException("test interrupt"))

        var interrupted = false
        val result =
            try {
                CLIInstaller.updateWindowsPath(
                    binPath = "C:\\Users\\test\\bin",
                    timeoutSeconds = 1L,
                    currentPathProvider = { "C:\\Windows\\system32" },
                    processStarter = { fakeProc },
                )
            } finally {
                interrupted = Thread.interrupted()
            }

        assertFalse(result)
        assertTrue(fakeProc.wasDestroyedForcibly.get(), "Process must be destroyed forcibly on interruption")
        assertTrue(interrupted, "Thread interrupt flag must be restored")
    }

    @Test
    fun `when timeoutSeconds is zero or negative throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            CLIInstaller.updateWindowsPath(
                binPath = "C:\\Users\\test\\bin",
                timeoutSeconds = 0L,
                currentPathProvider = { "C:\\Windows\\system32" },
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CLIInstaller.updateWindowsPath(
                binPath = "C:\\Users\\test\\bin",
                timeoutSeconds = -1L,
                currentPathProvider = { "C:\\Windows\\system32" },
            )
        }
    }

    private class FakeProcess(
        private val exitCode: Int = 0,
        hang: Boolean = false,
        stdout: String = "",
        stderr: String = "",
        blockStdoutUntilDestroy: Boolean = false,
        private val throwOnWaitFor: Exception? = null,
    ) : Process() {
        val wasDestroyedForcibly = AtomicBoolean(false)
        private val exitLatch = CountDownLatch(if (hang) 1 else 0)
        private val streamUnblockLatch = CountDownLatch(if (blockStdoutUntilDestroy) 1 else 0)
        private val inStream: InputStream =
            if (blockStdoutUntilDestroy) {
                object : InputStream() {
                    override fun read(): Int {
                        streamUnblockLatch.await()
                        return -1
                    }

                    override fun read(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ): Int {
                        streamUnblockLatch.await()
                        return -1
                    }
                }
            } else {
                ByteArrayInputStream(stdout.toByteArray(StandardCharsets.UTF_8))
            }
        private val errStream: InputStream = ByteArrayInputStream(stderr.toByteArray(StandardCharsets.UTF_8))
        private val outStream: OutputStream = ByteArrayOutputStream()

        override fun getOutputStream(): OutputStream = outStream

        override fun getInputStream(): InputStream = inStream

        override fun getErrorStream(): InputStream = errStream

        override fun isAlive(): Boolean = exitLatch.count > 0

        override fun waitFor(): Int {
            if (throwOnWaitFor != null) throw throwOnWaitFor
            exitLatch.await()
            return exitCode
        }

        override fun waitFor(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean {
            if (throwOnWaitFor != null) throw throwOnWaitFor
            return exitLatch.await(timeout, unit)
        }

        override fun exitValue(): Int {
            if (exitLatch.count > 0) throw IllegalThreadStateException("Process has not exited")
            return exitCode
        }

        override fun destroy() {
            destroyForcibly()
        }

        override fun destroyForcibly(): Process {
            wasDestroyedForcibly.set(true)
            streamUnblockLatch.countDown()
            exitLatch.countDown()
            return this
        }
    }
}
