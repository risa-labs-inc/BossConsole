package ai.rever.boss.components.plugin.providers

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogLevel
import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
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

    @Test
    fun `default writer preserves the destination when atomic staging fails`() {
        // Regression for #1106: the DEFAULT lambda is now atomicWriteText, which stages to a
        // sibling tmp before promoting. If the sibling cannot be created, atomicWriteText throws
        // BEFORE the target is touched, so guardedWrite returns false with the original bytes
        // intact. The previous direct writeText() default would have truncated and rewritten the
        // existing file despite the unwritable directory - so this test passes against
        // atomicWriteText and FAILS against writeText. The distinction relies on POSIX
        // file-attribute semantics; on platforms without that view the test is skipped rather
        // than run vacuously.
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return
        }

        val parent = Files.createTempDirectory("boss-guarded-write-").toFile()
        val target = File(parent, "existing.txt")
        val original = "ORIGINAL ${System.nanoTime()}"
        target.writeText(original)
        // Strip the parent's write bit: existing entries keep their own perms, but no new
        // siblings (the atomic staging tmp) can be created. writeText would still succeed
        // against the existing file; atomicWriteText throws before touching the target.
        parent.setWritable(false)
        try {
            assertFalse(guardedWrite(target.absolutePath, "REPLACEMENT"))
            assertEquals(original, target.readText(), "the default writer must not have touched the destination")
        } finally {
            parent.setWritable(true)
            target.delete()
            parent.delete()
        }
    }

    @Test
    fun `default writer replaces via rename and leaves no temp files behind`() {
        // atomicWriteText stages to a unique sibling tmp, then moves it over the target via the
        // OS rename primitive. On POSIX the move replaces the inode; writeText modifies the file
        // in place and preserves the inode. Pin the replacement here so this test cannot pass
        // vacuously against a hypothetical writeText default - it must actually use rename.
        // The leftover check is independent of the inode check and stays enforced everywhere.
        val path = tempPath("rename")
        File(path).writeText("seed")
        try {
            val inodeBefore =
                if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                    Files.getAttribute(File(path).toPath(), "unix:ino")
                } else {
                    null
                }
            repeat(3) { guardedWrite(path, "write $it") }
            val dir = File(path).parentFile
            val name = File(path).name
            val strays =
                dir
                    .listFiles()
                    ?.filter { it.name != name && it.name.startsWith("$name.") && it.name.endsWith(".tmp") }
                    .orEmpty()
            assertTrue(strays.isEmpty(), "unexpected leftovers: ${strays.map { it.name }}")
            if (inodeBefore != null) {
                val inodeAfter = Files.getAttribute(File(path).toPath(), "unix:ino")
                assertTrue(
                    inodeBefore != inodeAfter,
                    "atomicWriteText should replace the inode; writeText preserves it",
                )
            }
        } finally {
            File(path).delete()
        }
    }
}
