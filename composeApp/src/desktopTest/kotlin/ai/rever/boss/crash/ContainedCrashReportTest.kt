package ai.rever.boss.crash

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [CrashHandler.recordContained], the path a contained render fault takes
 * to disk.
 *
 * It was the largest new piece here and had no coverage at all, which mattered
 * because its whole reason for existing is that the previous implementation
 * *looked* like it reported something and did not — it wrote to a slot with no
 * consumers. A test that a file actually appears is the difference between the
 * two.
 *
 * Runs against a temp directory, never the real data root: the write path now
 * sweeps old reports, so a test pointed at `~/.boss/crash-reports` would delete
 * the user's actual crash reports.
 *
 * Note this swaps a global on a shared singleton and deletes files, so it relies
 * on this module running tests in one fork — a future `maxParallelForks > 1` would
 * let a parallel class see this class's override, or lose its own. The directory
 * is captured when a write is *enqueued* rather than when it runs, which is what
 * stops a task that outlives `tearDown` from resolving the real data root.
 */
class ContainedCrashReportTest {
    /** Mirrors CrashHandler.CONTAINED_REPORT_RETENTION, which is private. */
    private val retention = 20

    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("contained-report-test").toFile()
        CrashHandler.containedReportDirOverride = dir
        CrashHandler.resetContainedStateForTest()
    }

    @AfterTest
    fun tearDown() {
        CrashHandler.containedReportDirOverride = null
        CrashHandler.resetContainedStateForTest()
        dir.deleteRecursively()
    }

    private fun reports(): List<File> = dir.listFiles()?.toList().orEmpty()

    /**
     * A fault with a signature of its own.
     *
     * [CrashSignature] deliberately ignores the message and hashes the exception
     * type plus stack frames, so varying only the text yields *one* signature —
     * thirty "distinct" faults built at a single line all dedupe to a single file.
     * That silently made the retention test vacuous (verified: it passed with the
     * sweep commented out). Stamping a synthetic frame is the only way to vary the
     * signature without thirty separate throw sites.
     */
    private fun uniqueThrowable(marker: String): IllegalStateException =
        IllegalStateException("contained-report-test $marker").apply {
            stackTrace =
                arrayOf(
                    StackTraceElement("ai.rever.boss.crash.SyntheticFault", marker, "SyntheticFault.kt", 1),
                )
        }

    @Test
    fun `a contained fault is written to disk`() {
        CrashHandler.recordContained(uniqueThrowable("write"))
        awaitFiles(1)

        val text = reports().single().readText()
        assertTrue(text.contains("contained render fault"), "the file should say what it is")
        assertTrue(text.contains("plugin:"), "pluginId is the most useful field on this path")
        assertTrue(text.contains("contained-report-test write"), "the original message should survive")
    }

    @Test
    fun `the same fault is not written twice`() {
        // A corrupt scene throws every frame; without the dedupe this path wrote
        // roughly sixty files a second of full stack traces.
        val boom = uniqueThrowable("dedupe")
        CrashHandler.recordContained(boom)
        awaitFiles(1)
        repeat(20) { CrashHandler.recordContained(boom) }
        drainWriter()

        assertEquals(1, reports().size, "a recurring fault must not keep writing files")
    }

    @Test
    fun `a benign exception is not recorded at all`() {
        CrashHandler.recordContained(java.util.concurrent.CancellationException("cancelled"))
        drainWriter()

        assertEquals(0, reports().size, "an ignorable throwable is not a crash")
    }

    @Test
    fun `the report is readable only by its owner`() {
        // Sanitized or not, these hold stack traces, plugin ids and host details.
        CrashHandler.recordContained(uniqueThrowable("perms"))
        awaitFiles(1)

        val file = reports().single()
        val perms = runCatching { Files.getPosixFilePermissions(file.toPath()) }.getOrNull()
        if (perms == null) return // non-POSIX filesystem; the File-flag fallback is best effort

        assertTrue(
            perms.none { it.name.startsWith("GROUP") || it.name.startsWith("OTHERS") },
            "contained reports must not be group- or world-accessible, found $perms",
        )
    }

    @Test
    fun `the directory does not grow without bound`() {
        // The session dedupe bounds one run; nothing bounded the directory across
        // runs, and no other code in the repo trims it.
        val distinctFaults = 30
        repeat(distinctFaults) { CrashHandler.recordContained(uniqueThrowable("retention-$it")) }
        drainWriter()

        // The drain marker above was itself a 31st write, and deleting it can take
        // the count one below the cap — so assert the bound, not an exact figure.
        val kept = reports().size
        assertTrue(kept > 0, "the sweep must not empty the directory it just wrote to")
        assertTrue(
            kept <= retention,
            "expected at most $retention reports kept, found $kept of $distinctFaults written",
        )
    }

    /** The write is handed to a background thread, so poll rather than sleep a fixed span. */
    private fun awaitFiles(count: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (reports().size >= count) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $count contained report(s); found ${reports().size}")
    }

    /**
     * Wait for the single-threaded writer to catch up.
     *
     * Asserting "no file appeared" needs the queue drained, not a guessed sleep —
     * on a loaded CI box a fixed 200ms would pass whether or not the code was
     * correct.
     */
    private fun drainWriter() {
        val marker = uniqueThrowable("drain")
        val text = marker.message!!
        CrashHandler.recordContained(marker)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            // readText via runCatching: the writer thread sweeps old reports while
            // we are listing, so a file can vanish between listFiles and the read.
            val written = reports().filter { runCatching { it.readText() }.getOrDefault("").contains(text) }
            if (written.isNotEmpty()) {
                written.forEach { it.delete() }
                return
            }
            Thread.sleep(20)
        }
        throw AssertionError("the contained-report writer never drained")
    }
}
