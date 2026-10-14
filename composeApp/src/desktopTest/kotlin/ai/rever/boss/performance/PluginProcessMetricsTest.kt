package ai.rever.boss.performance

import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards [PluginProcessMetrics], which feeds the Performance panel's out-of-process plugin rows.
 *
 * The defect was one `ps` invocation for every platform. On Windows it produced no output, so every
 * plugin showed 0 bytes and 0 threads. On Linux, procps reads `-M` as a security-label column, so
 * every plugin showed exactly 1 thread.
 *
 * The OS name and the `ps` runner are injected, so the dispatch and parsing cases assert the same
 * thing on every runner. The last case measures this test JVM through the real host path on every
 * platform, macOS included now that its `ps -M` layout is measured rather than assumed.
 */
class PluginProcessMetricsTest {
    private val linuxStatus =
        """
        Name:	java
        State:	S (sleeping)
        Pid:	4242
        VmPeak:	 9876543 kB
        VmRSS:	  204800 kB
        RssAnon:	  150000 kB
        Threads:	42
        SigQ:	0/63446
        """.trimIndent()

    private val noMac: (List<Long>) -> Map<Long, OsProcessMetrics> = { fail("macOS query must not run") }
    private val noWindows: (List<Long>) -> Map<Long, OsProcessMetrics> = { fail("Windows query must not run") }
    private val noProc: (Long) -> String? = { fail("/proc must not be read") }

    @Test
    fun `a linux status file gives resident memory and the thread count`() {
        val metrics = PluginProcessMetrics.linuxMetrics(listOf(4242L)) { linuxStatus }

        assertEquals(mapOf(4242L to OsProcessMetrics(rssBytes = 204_800L * 1024, threadCount = 42)), metrics)
    }

    @Test
    fun `linux is read from proc status and never from ps`() {
        val metrics =
            PluginProcessMetrics.query(
                pids = listOf(4242L),
                osName = "Linux",
                readProcStatus = { pid -> if (pid == 4242L) linuxStatus else null },
                queryMac = noMac,
                queryWindows = noWindows,
            )

        assertEquals(42, metrics.getValue(4242L).threadCount)
    }

    @Test
    fun `a linux pid whose status file is gone is left out rather than reported as zero`() {
        val metrics = PluginProcessMetrics.linuxMetrics(listOf(1L, 2L)) { pid -> if (pid == 1L) linuxStatus else null }

        assertEquals(setOf(1L), metrics.keys)
    }

    @Test
    fun `windows is read natively and never from ps`() {
        val native = mapOf(7L to OsProcessMetrics(rssBytes = 1L, threadCount = 30))

        val metrics =
            PluginProcessMetrics.query(
                pids = listOf(7L),
                osName = "Windows 11",
                readProcStatus = noProc,
                queryMac = noMac,
                queryWindows = { native },
            )

        assertEquals(native, metrics)
    }

    @Test
    fun `macOS keeps its ps query`() {
        val fromPs = mapOf(9L to OsProcessMetrics(rssBytes = 2L, threadCount = 12))

        val metrics =
            PluginProcessMetrics.query(
                pids = listOf(9L),
                osName = "Mac OS X",
                readProcStatus = noProc,
                queryMac = { fromPs },
                queryWindows = noWindows,
            )

        assertEquals(fromPs, metrics)
    }

    @Test
    fun `an unrecognised OS reads nothing, and darwin is not taken for windows`() {
        for (os in listOf("Darwin", "FreeBSD", "")) {
            assertEquals(
                emptyMap(),
                PluginProcessMetrics.query(listOf(1L), os, noProc, noMac, noWindows),
                "os.name=\"$os\"",
            )
        }
    }

    @Test
    fun `no pids queries nothing`() {
        assertEquals(emptyMap(), PluginProcessMetrics.query(emptyList(), "Linux", noProc, noMac, noWindows))
    }

    /**
     * `ps -M -p 10881,10882` on macOS 26.6.2 (arm64), for two JVMs, with the thread rows shortened
     * from 28 per pid to 3 and trailing spaces removed. Column layout is unchanged: thread rows
     * repeat the pid and leave the user, terminal and command blank.
     */
    private val macPsM =
        """
        USER     PID   TT   %CPU STAT PRI     STIME     UTIME COMMAND
        runner 10881   ??    0.0 S    20T   0:00.01   0:00.01 java S.java
               10881         0.0 S    20T   0:00.01   0:00.00
               10881         0.0 S    20T   0:00.07   0:00.41
               10881         0.0 S    20T   0:00.00   0:00.00
        runner 10882   ??    0.0 S    20T   0:00.00   0:00.00 java S.java
               10882         0.0 S    20T   0:00.01   0:00.00
               10882         0.0 S    20T   0:00.07   0:00.40
               10882         0.0 S    20T   0:00.00   0:00.00
        """.trimIndent()

    @Test
    fun `macOS ps -M lines are counted per pid, skipping the header and blank lines`() {
        assertEquals(mapOf(10881L to 4, 10882L to 4), PluginProcessMetrics.parseMacPsThreads(macPsM))
        val firstPidThenBlankLines = macPsM.lines().take(5).joinToString("\n") + "\n\n"
        assertEquals(mapOf(10881L to 4), PluginProcessMetrics.parseMacPsThreads(firstPidThenBlankLines))
    }

    @Test
    fun `macOS runs ps from bin, not from PATH, and combines both runs per pid`() {
        val commands = mutableListOf<List<String>>()

        val metrics =
            PluginProcessMetrics.macMetrics(listOf(10881L, 10882L)) { _, command ->
                commands += command
                if ("-M" in command) macPsM else "10881 102736\n10882  78544\n"
            }

        assertEquals(
            listOf(
                listOf("/bin/ps", "-o", "pid=,rss=", "-p", "10881,10882"),
                listOf("/bin/ps", "-M", "-p", "10881,10882"),
            ),
            commands,
        )
        assertEquals(
            mapOf(
                10881L to OsProcessMetrics(rssBytes = 102_736L * 1024, threadCount = 4),
                10882L to OsProcessMetrics(rssBytes = 78_544L * 1024, threadCount = 4),
            ),
            metrics,
        )
    }

    @Test
    fun `a macOS pid neither ps run reports is left out rather than reported as zero`() {
        val metrics =
            PluginProcessMetrics.macMetrics(listOf(10881L, 99L)) { _, command ->
                if ("-M" in command) macPsM else null
            }

        assertEquals(setOf(10881L), metrics.keys)
        assertEquals(OsProcessMetrics(rssBytes = null, threadCount = 4), metrics.getValue(10881L))
    }

    /**
     * A child that never exits is abandoned at the timeout and killed.
     *
     * The unbounded `waitFor()` this replaces blocked for the child's whole life, on the thread that
     * collects the Performance panel's snapshots.
     */
    @Test
    fun `a command that does not finish is abandoned at the timeout and killed`(
        @TempDir dir: Path,
    ) {
        val marker = "HangsForever${System.nanoTime()}"
        val source = dir.resolve("$marker.java")
        Files.writeString(
            source,
            "public class $marker { public static void main(String[] a) throws Exception { Thread.sleep(120_000); } }",
        )

        val started = System.nanoTime()
        var failed = false
        assertTimeoutPreemptively(Duration.ofSeconds(60)) {
            failed = BoundedCommand.run(listOf(javaBinary(), source.toString()), timeoutMillis = 2_000).isFailure
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(failed, "a hung command returned output")
        assertTrue(elapsedMs < 15_000, "abandoning the command took $elapsedMs ms")
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (liveChildrenRunning(marker) && System.nanoTime() < deadline) Thread.sleep(100)
        assertFalse(liveChildrenRunning(marker), "the timed-out child is still running")
    }

    /**
     * Output larger than any pipe buffer still arrives whole.
     *
     * `ps -M` output grows with thread count. Waiting before draining, the order that is safe for
     * [ProcessFootprint]'s one-line-per-pid queries, would leave this child blocked on a full pipe
     * until the timeout.
     */
    @Test
    fun `output larger than the pipe buffer is read whole within the timeout`(
        @TempDir dir: Path,
    ) {
        val bytes = 1_048_576
        val source = dir.resolve("Floods.java")
        Files.writeString(
            source,
            "public class Floods { public static void main(String[] a) { " +
                "System.out.print(\"x\".repeat($bytes)); System.out.flush(); } }",
        )

        val result = BoundedCommand.run(listOf(javaBinary(), source.toString()), timeoutMillis = 60_000)

        assertEquals(bytes, result.getOrThrow().length)
    }

    @Test
    fun `a command that cannot start is a failure, not an exception`() {
        assertTrue(BoundedCommand.run(listOf("boss-no-such-binary-${System.nanoTime()}"), 1_000).isFailure)
    }

    @Test
    fun `a failing source is logged once, and again only after it has recovered`() {
        val lines = mutableListOf<String>()
        val log = FailureLog { lines += it }

        log.failed("ps -M", IllegalStateException("timed out"))
        log.failed("ps -M", IllegalStateException("timed out"))
        log.failed("Windows working set", UnsatisfiedLinkError("psapi"))
        log.succeeded("ps -M")
        log.succeeded("ps -M")
        log.failed("ps -M", IllegalStateException("timed out again"))

        assertEquals(
            listOf("ps -M failed: timed out", "Windows working set failed: psapi", "ps -M failed: timed out again"),
            lines,
        )
    }

    @Test
    fun `a status file without a Threads field has no thread count`() {
        assertEquals(null, PluginProcessMetrics.parseProcThreads("Name:\tjava\nVmRSS:\t 1 kB\n"))
    }

    /**
     * The real host path, measured against this JVM.
     *
     * A running JVM always has several OS threads (main, GC, compiler, finalizer), so a count of 1
     * or 0 is never true. The old code reported 0 threads on Windows and 1 on Linux, so this fails
     * against it on both. It does not compare against the JVM's own thread count, because a thread
     * can exit between that read and the OS snapshot.
     */
    @Test
    fun `this JVM reports its own memory and more than one thread`() {
        val pid = ProcessHandle.current().pid()

        val metrics = assertNotNull(PluginProcessMetrics.query(listOf(pid))[pid], "no metrics for this JVM")

        val rss = assertNotNull(metrics.rssBytes, "resident memory unreadable")
        assertTrue(rss > 0, "resident memory was $rss")
        val threads = assertNotNull(metrics.threadCount, "thread count unreadable")
        assertTrue(threads > 1, "OS reported $threads threads for a running JVM")
    }

    private fun javaBinary(): String =
        ProcessHandle
            .current()
            .info()
            .command()
            .orElseThrow()

    private fun liveChildrenRunning(marker: String): Boolean =
        ProcessHandle.current().children().anyMatch { child ->
            child.isAlive &&
                child
                    .info()
                    .commandLine()
                    .orElse("")
                    .contains(marker)
        }
}
