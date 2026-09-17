package ai.rever.boss.performance

import org.junit.jupiter.api.Assumptions.assumeFalse
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * The OS name is injected, so the dispatch and parsing cases assert the same thing on every
 * runner. The last case measures this test JVM through the real host path. It fails against the
 * old code on Windows and Linux runners and is only a regression guard on macOS, whose path this
 * change does not touch, so it is skipped there.
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
     * The macOS parser's counting rule, which this change rewrites without changing.
     *
     * The fixture follows the column rule the parser documents (user and pid on a process's first
     * line, pid first on each further thread line). It is not captured from a macOS machine.
     */
    @Test
    fun `macOS ps -M lines are counted per pid, skipping the header and blank lines`() {
        val output =
            """
            USER   PID   TT  %CPU STAT PRI     STIME     UTIME COMMAND
            dev   4242 s000   0.0 S    31T   0:00.01   0:00.02 /usr/bin/java
                  4242        0.0 S    31T   0:00.00   0:00.00
                  4242        0.0 S    31T   0:00.00   0:00.00

            dev   5151 s001   0.0 S    31T   0:00.01   0:00.02 java
            """.trimIndent()

        assertEquals(mapOf(4242L to 3, 5151L to 1), PluginProcessMetrics.parseMacPsThreads(output))
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
        val hostOs = System.getProperty("os.name").orEmpty()
        assumeFalse(
            hostOs.lowercase().startsWith("mac"),
            "macOS keeps its existing ps query, which this change does not touch",
        )
        val pid = ProcessHandle.current().pid()

        val metrics = assertNotNull(PluginProcessMetrics.query(listOf(pid))[pid], "no metrics for this JVM")

        val rss = assertNotNull(metrics.rssBytes, "resident memory unreadable")
        assertTrue(rss > 0, "resident memory was $rss")
        val threads = assertNotNull(metrics.threadCount, "thread count unreadable")
        assertTrue(threads > 1, "OS reported $threads threads for a running JVM")
    }
}
