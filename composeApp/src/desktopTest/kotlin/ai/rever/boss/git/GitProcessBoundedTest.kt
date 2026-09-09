package ai.rever.boss.git

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The subprocess harness under every git command in the app.
 *
 * [GitService.runProcessBounded] exists to survive exactly two situations, and
 * both once froze the whole app because the caller holds the process-wide
 * gitCommandLock: a child that fills one pipe while the reader drains the other
 * sequentially (deadlock, no timeout can help), and a child that never exits
 * (the wait must be bounded and the kill must actually end it). Pinned with a
 * real child process; skipped on Windows, where the shell used to fake the
 * child does not exist.
 */
class GitProcessBoundedTest {
    private fun onWindows() = System.getProperty("os.name").startsWith("Windows")

    @Test
    fun `a child flooding stderr past the pipe buffer cannot deadlock the drain`() {
        if (onWindows()) return
        // ~1.6 MB of stderr - far past the ~64KB pipe buffer that blocks the
        // child once nothing drains it. A sequential read of stdout-to-EOF
        // never returns here, because the child never exits.
        val process =
            ProcessBuilder(
                "/bin/sh",
                "-c",
                "i=0; while [ \$i -lt 40000 ]; do " +
                    "echo 0123456789012345678901234567890123456789 1>&2; i=\$((i+1)); done; echo done-out",
            ).start()

        val result = GitService.runProcessBounded(process, 60L, arrayOf("test-flood"))

        assertEquals(0, result.exitCode, "child failed: ${result.error.take(200)}")
        assertTrue("done-out" in result.output, "stdout was lost while stderr flooded")
        assertTrue(result.error.length > 1_000_000, "stderr was not drained: ${result.error.length} chars")
    }

    @Test
    fun `a hung child is killed at the bound instead of holding the lock forever`() {
        if (onWindows()) return
        val process = ProcessBuilder("/bin/sh", "-c", "sleep 60").start()
        val startedAt = System.currentTimeMillis()

        val result = GitService.runProcessBounded(process, 1L, arrayOf("test-hang"))

        val elapsedMs = System.currentTimeMillis() - startedAt
        assertEquals(124, result.exitCode, "a timeout must be reported as the timeout exit code")
        assertTrue(result.error.startsWith("Timed out"), "error should say it timed out: ${result.error}")
        // 1s bound + 5s drain grace, with slack for a loaded CI worker.
        assertTrue(elapsedMs < 30_000, "the kill did not end the wait: ${elapsedMs}ms")
        assertTrue(!process.isAlive, "the child must actually be dead")
    }

    @Test
    fun `a stderr flood past the cap does not mark a complete stdout truncated`() {
        if (onWindows()) return
        // [truncated] is what parseDiffSafely refuses on, so the flag must mean
        // "bytes were dropped from THIS stream". With one shared flag a stderr
        // flood refused a perfectly complete diff as "too large to render".
        // capChars=10_000 makes the overflow cheap to produce; the production
        // call sites keep the 32M-char default.
        val process =
            ProcessBuilder(
                "/bin/sh",
                "-c",
                "i=0; while [ \$i -lt 3000 ]; do " +
                    "echo 0123456789012345678901234567890123456789 1>&2; i=\$((i+1)); done; echo done-out",
            ).start()

        val result = GitService.runProcessBounded(process, 60L, arrayOf("test-flood"), null, capChars = 10_000)

        assertEquals(0, result.exitCode, "child failed: ${result.error.take(200)}")
        assertTrue(result.output.trim() == "done-out", "the small stdout must arrive complete: '${result.output}'")
        // errDropped has no field on the result (nothing reads it); the
        // observable half of the wiring is that the stderr BUFFER keeps the
        // first capChars - 3000 x 40 = 120k, well past the 10k cap.
        assertTrue(result.error.length >= 10_000, "stderr was not drained: ${result.error.length} chars")
        assertTrue(
            !result.truncated,
            "a stderr overflow must not set the stdout truncation flag",
        )
    }

    @Test
    fun `a timeout of an index-write in a repo with a stranded index lock says so`(
        @TempDir tmp: File,
    ) {
        if (onWindows()) return
        // The reason index-writing commands get the long bound: a kill mid-write
        // strands .git/index.lock and git's later "File exists" error never
        // explains it. When the lock is there after a kill, the error must.
        File(tmp, ".git").mkdirs()
        File(tmp, ".git/index.lock").writeText("")
        val process = ProcessBuilder("/bin/sh", "-c", "sleep 60").start()

        // "commit" is in INDEX_WRITE_SUBCOMMANDS, the gate the hint sits behind.
        val result = GitService.runProcessBounded(process, 1L, arrayOf("commit"), tmp.absolutePath)

        assertTrue(
            "index.lock" in result.error,
            "the timeout error should mention the stranded lock: ${result.error}",
        )
    }

    @Test
    fun `a timeout of a read-only command does not blame the index lock`(
        @TempDir tmp: File,
    ) {
        if (onWindows()) return
        // A lock that some OTHER process legitimately holds, with a command that
        // never takes one: the old code told the user to delete it, which is
        // advice to break a live repo. The hint must stay silent.
        File(tmp, ".git").mkdirs()
        File(tmp, ".git/index.lock").writeText("")
        val process = ProcessBuilder("/bin/sh", "-c", "sleep 60").start()

        val result = GitService.runProcessBounded(process, 1L, arrayOf("diff"), tmp.absolutePath)

        assertTrue(
            "index.lock" !in result.error,
            "a non-index-write timeout must not advise deleting the lock: ${result.error}",
        )
    }
}
