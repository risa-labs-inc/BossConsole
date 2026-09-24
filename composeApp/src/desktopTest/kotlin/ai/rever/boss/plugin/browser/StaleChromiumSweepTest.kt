package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.SingleInstanceManager
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The startup stale-Chromium sweep, and the cross-instance kill it used to
 * commit.
 *
 * The sweep selected any Chromium executable under the shared BOSS dirs that
 * was older than five seconds and not a direct child of this JVM. A second
 * launch that reached it - a restart that won the single-instance race while
 * the old process was still alive, or any path where the claim check failed
 * open - killed the running instance's renderer tree, and a parentless
 * `chrome_crashpad` qualified with no directory scope at all, whatever it
 * belonged to.
 *
 * The predicate is exercised with two modelled instances on distinct data
 * roots; the ownership walk is exercised with real processes, because the
 * behaviour under test is what the OS reports about parents and orphans.
 */
class StaleChromiumSweepTest {
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    // Two modelled instances on distinct data roots - the installed-vs-dev
    // split this bug was reported under, or any two data-dir layouts.
    private val instanceBMarkers =
        listOf(
            "/data/b/.boss/jxbrowser-chromium",
            "/data/b/.boss/boss-chromium",
            "/data/b/.boss/browser-profile",
        )

    private fun candidate(
        command: String,
        commandLine: String = command,
        parentPid: Long = 4242,
        ageMs: Long = 60_000,
        hasForeignOwner: Boolean = false,
    ) = FluckEngine.isStaleChromiumCandidate(
        process =
            FluckEngine.ChromiumProcessSnapshot(
                command = command,
                commandLine = commandLine,
                parentPid = parentPid,
                startTimeMs = 10_000,
            ),
        nowMs = 10_000 + ageMs,
        currentPid = 999,
        instanceMarkers = instanceBMarkers,
        hasForeignOwner = { hasForeignOwner },
    )

    @Test
    fun `a sweep selects only its own instance's stale processes`() {
        // Instance A's tree: paths a B sweep must never match.
        assertFalse(
            candidate(
                command = "/data/a/.boss/boss-chromium/chrome",
                commandLine = "/data/a/.boss/boss-chromium/chrome --user-data-dir=/data/a/.boss/browser-profile",
            ),
        )
        // A stale process from B's own previous session is what the sweep exists for.
        assertTrue(
            candidate(
                command = "/data/b/.boss/boss-chromium/chrome",
                commandLine = "/data/b/.boss/boss-chromium/chrome --user-data-dir=/data/b/.boss/browser-profile",
            ),
        )
        // Temp-profile stragglers share the browser-profile prefix.
        assertTrue(
            candidate(
                command = "/data/b/.boss/boss-chromium/chrome",
                commandLine = "/data/b/.boss/boss-chromium/chrome --user-data-dir=/data/b/.boss/browser-profile-171",
            ),
        )
    }

    @Test
    fun `a live foreign tree is never selected`() {
        // Same dirs as the sweeper: a same-mode second instance path-matches
        // the first, so only the ownership check tells "stale" from "someone
        // else's live browser".
        assertFalse(
            candidate(
                command = "/data/b/.boss/boss-chromium/chrome",
                commandLine =
                    "/data/b/.boss/boss-chromium/chrome --type=renderer" +
                        " --user-data-dir=/data/b/.boss/browser-profile",
                hasForeignOwner = true,
            ),
        )
    }

    @Test
    fun `a foreign crashpad orphan is out of scope`() {
        // The old clause killed any parentless chrome_crashpad on the box -
        // including the other instance's, and stock Chrome's.
        assertFalse(
            candidate(
                command = "/opt/google/chrome/chrome_crashpad_handler",
                commandLine = "/opt/google/chrome/chrome_crashpad_handler --no-periodic-tasks",
                parentPid = -1,
            ),
        )
        // Ours still qualifies: the binary lives under this instance's engine dir.
        assertTrue(
            candidate(
                command = "/data/b/.boss/boss-chromium/helpers/chrome_crashpad_handler",
                commandLine =
                    "/data/b/.boss/boss-chromium/helpers/chrome_crashpad_handler" +
                        " --database=/data/b/.boss/browser-profile/Crashpad",
                parentPid = 1,
            ),
        )
    }

    @Test
    fun `own children and young processes stay protected`() {
        assertFalse(
            candidate(
                command = "/data/b/.boss/boss-chromium/chrome",
                parentPid = 999,
            ),
        )
        assertFalse(
            candidate(
                command = "/data/b/.boss/boss-chromium/chrome",
                ageMs = 1_000,
            ),
        )
        // And a non-Chromium executable is never in scope, however stale.
        assertFalse(candidate(command = "/usr/bin/sleep"))
    }

    @Test
    fun `the sweep gate is the single-instance claim`() {
        // killStaleChromiumProcesses runs only while this process owns the
        // claim; the claim itself is SingleInstanceManager's published
        // descriptor, exercised here end to end.
        val runtimeDir = Files.createTempDirectory("boss-si-sweep")
        SingleInstanceManager.runtimeDirOverride = runtimeDir.toFile()
        try {
            assertFalse(SingleInstanceManager.isInstanceOwner)
            assertTrue(SingleInstanceManager.acquireLock())
            assertTrue(SingleInstanceManager.isInstanceOwner)
        } finally {
            SingleInstanceManager.release()
            SingleInstanceManager.runtimeDirOverride = null
        }
        assertFalse(SingleInstanceManager.isInstanceOwner)
    }

    @Test
    fun `a live owner pins a process tree, a dead one releases it`() {
        // Reparenting to pid 1 is POSIX behaviour; on Windows an orphan keeps
        // its recorded parent id and there is no /bin/sh to build the tree.
        assumeTrue(!isWindows, "orphan reparenting is POSIX behaviour")
        val currentPid = ProcessHandle.current().pid()

        // A child whose parent is alive and is not this JVM stands in for a
        // second instance's Chromium: the tree is pinned.
        val holder = ProcessBuilder("/bin/sh", "-c", "sleep 60 & wait").start()
        try {
            val foreignChild = waitForChild(holder.toHandle())
            assertTrue(
                FluckEngine.hasLiveForeignOwner(foreignChild, currentPid),
                "a process with a live non-Chromium parent must read as foreign-owned",
            )
        } finally {
            holder.destroyForcibly()
        }

        // An orphan stands in for a dead instance's leftover: released.
        // `echo $!` prints the child's pid, so no child-scan race can leave the
        // test asserting on an empty process list.
        val spawner = ProcessBuilder("/bin/sh", "-c", "sleep 60 & echo $!").start()
        val orphanPid =
            spawner.inputStream
                .bufferedReader()
                .readLine()
                .trim()
                .toLong()
        spawner.waitFor(5, TimeUnit.SECONDS)
        val orphan =
            ProcessHandle.of(orphanPid).orElseThrow {
                AssertionError("orphaned test process $orphanPid not found - nothing to assert on")
            }
        try {
            val deadline = System.currentTimeMillis() + 5_000
            var parentPid = orphan.parent().map { it.pid() }.orElse(-1L)
            while (parentPid > 1L && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
                parentPid = orphan.parent().map { it.pid() }.orElse(-1L)
            }
            assertTrue(parentPid <= 1L, "orphan never reparented to pid 1 - the test would prove nothing")
            assertFalse(
                FluckEngine.hasLiveForeignOwner(orphan, currentPid),
                "a process reparented to pid 1 must read as stale",
            )
        } finally {
            orphan.destroyForcibly()
        }
    }

    /**
     * The shell forks its child asynchronously, so it is not there the instant
     * `start()` returns. Polls rather than sleeping a fixed amount, since a
     * short sleep that is occasionally too short would make the test assert on
     * a process that does not exist.
     */
    private fun waitForChild(parent: ProcessHandle): ProcessHandle {
        val deadline = System.currentTimeMillis() + 5_000
        var found = parent.children().toList()
        while (found.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            found = parent.children().toList()
        }
        assertTrue(found.isNotEmpty(), "the harness spawned no child - the test would prove nothing")
        return found.first()
    }
}
