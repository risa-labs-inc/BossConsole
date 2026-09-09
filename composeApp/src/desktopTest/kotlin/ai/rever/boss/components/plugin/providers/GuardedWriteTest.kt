package ai.rever.boss.components.plugin.providers

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogLevel
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Failure containment inside the write operation, independent of upstream MCP failures. */
class GuardedWriteTest {
    private val tmpDir = System.getProperty("java.io.tmpdir")

    private fun tempPath(name: String) = File(tmpDir, "boss-guarded-write-$name-${System.nanoTime()}").absolutePath

    @Test
    fun `a stack overflow is reported as failure, not thrown`() {
        // Inject the error inside the write boundary; upstream errors cannot be caught here.
        val written =
            guardedWrite(tempPath("overflow"), "<svg>...</svg>") { _, _ ->
                throw StackOverflowError("recursion inside write")
            }

        assertFalse(written, "a StackOverflowError must be reported as a failed write, not escape the call")
    }

    @Test
    fun `heap exhaustion still propagates to the crash policy`() {
        assertFailsWith<OutOfMemoryError> {
            guardedWrite(tempPath("oom"), "x") { _, _ -> throw OutOfMemoryError("heap") }
        }
    }

    @Test
    fun `an ordinary IO failure still reports false`() {
        // The behaviour that already worked, pinned so widening the catch did not narrow it.
        val written = guardedWrite(tempPath("io"), "x") { _, _ -> throw IOException("read-only filesystem") }

        assertFalse(written)
    }

    @Test
    fun `an error this function has no business absorbing is not swallowed`() {
        // Deliberately NOT `catch (t: Throwable)`. A LinkageError means the JVM is in a state this
        // function cannot report its way out of, and turning it into `false` would hide a broken
        // classpath behind "write failed".
        assertFailsWith<NoClassDefFoundError>("a LinkageError should propagate, not be reported as a failed write") {
            guardedWrite(tempPath("linkage"), "x") { _, _ -> throw NoClassDefFoundError("something/Missing") }
        }
    }

    @Test
    fun `stack exhaustion in diagnostics does not retry the write`() {
        var writes = 0
        assertFalse(
            guardedWrite(
                tempPath("diagnostic-stack"),
                "x",
                reportFailure = { _, _, _ -> throw StackOverflowError("logger") },
            ) { _, _ ->
                writes++
                throw StackOverflowError("write")
            },
        )
        assertEquals(1, writes)
    }

    @Test
    fun `fatal diagnostic errors are not swallowed`() {
        for (failure in listOf(OutOfMemoryError("logger"), NoClassDefFoundError("logger"))) {
            val actual =
                kotlin.test.assertFails {
                    guardedWrite(
                        tempPath("diagnostic-fatal"),
                        "x",
                        reportFailure = { _, _, _ -> throw failure },
                    ) { _, _ -> throw StackOverflowError("write") }
                }
            kotlin.test.assertSame(failure, actual)
        }
    }

    @Test
    fun `diagnostics name the path and size without adding file contents`() {
        val path = tempPath("diagnostic-fields")
        val content = "private-editor-body-${System.nanoTime()}"
        val previousLevel = BossLogger.globalLevel
        try {
            BossLogger.setGlobalLevel(LogLevel.WARN)
            assertFalse(guardedWrite(path, content) { _, _ -> throw IOException("write failed") })
            val entry = BossLogger.getRecentLogs(limit = 100).last { it.data?.get("path") == path }
            assertEquals(content.length, entry.data?.get("chars"))
            assertEquals("IOException", entry.data?.get("error"))
            assertFalse(entry.message.contains(content))
            assertFalse(entry.data.toString().contains(content))
        } finally {
            BossLogger.setGlobalLevel(previousLevel)
        }
    }

    @Test
    fun `a successful write returns true and writes the content`() {
        val path = tempPath("ok-${System.nanoTime()}")
        try {
            assertTrue(guardedWrite(path, "hello"))
            assertEquals("hello", File(path).readText())
        } finally {
            File(path).delete()
        }
    }

    @Test
    fun `parent directories are created`() {
        val root = File(tmpDir, "boss-guarded-write-${System.nanoTime()}")
        val dir = File(root, "nested/deeper")
        val path = File(dir, "f.txt").absolutePath
        try {
            assertTrue(guardedWrite(path, "x"))
            assertTrue(File(path).isFile)
        } finally {
            root.deleteRecursively()
        }
    }
}
