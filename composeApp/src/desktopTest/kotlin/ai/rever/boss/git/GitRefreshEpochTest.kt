package ai.rever.boss.git

import ai.rever.boss.window.WindowGitState
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Regression tests for the epoch-checked global git state (BossConsole#813,
 * production scope from the #814 review).
 *
 * The production cross-window race: `refreshForWindow` and
 * `alignCurrentProjectPath` both wrote the shared GLOBAL `currentProjectPath`
 * from any window's coroutine with no ordering, so two windows' refreshes
 * interleaved and the last write won - and a refresh that started before a
 * project switch and finished after it repointed the global at a project the
 * user had already left. The fix: a monotonic project epoch - a window
 * captures it at refresh entry, and publishes the global seed ONLY if the
 * epoch is unchanged at publish time; every switch bumps it.
 *
 * Uses real `git` in a temp directory (the repo's established git-test
 * pattern) and deterministic in-process interleaving - no coroutine-scheduling
 * luck, per the review's test requirements.
 */
class GitRefreshEpochTest {
    @TempDir
    lateinit var tempDir: File

    private fun git(
        dir: File,
        vararg args: String,
    ) {
        val p = ProcessBuilder(listOf("git", *args)).directory(dir).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText()
        p.waitFor()
    }

    private fun repo(
        name: String,
        branch: String,
    ): File {
        val dir = File(tempDir, name).apply { mkdirs() }
        git(dir, "init", "-q", "-b", branch)
        git(dir, "config", "user.email", "t@example.com")
        git(dir, "config", "user.name", "Test")
        git(dir, "config", "commit.gpgsign", "false")
        File(dir, "tracked.txt").writeText("one\n")
        git(dir, "add", ".")
        git(dir, "commit", "-q", "-m", "init")
        return dir
    }

    @Test
    fun `two racing window refreshes leave a self-consistent global - never a cross-project mix`() =
        runTest {
            val repoA = repo("alpha", "alpha-branch")
            val repoB = repo("beta", "beta-branch")

            val windowA = WindowGitState("win-a")
            val windowB = WindowGitState("win-b")

            // Both windows refresh in parallel - the production interleaving
            // (two panels' status polls, or panel + top bar). Unserialized, the
            // global seed was a coin flip between entry orders.
            val a = async { GitService.refreshForWindow(repoA.absolutePath, windowA) }
            val b = async { GitService.refreshForWindow(repoB.absolutePath, windowB) }
            a.await()
            b.await()

            // The global names ONE project, and it is a project that was
            // actually refreshed - a cross-project mix is the #813 bug.
            val global = GitService.getCurrentProjectPath()
            assertTrue(
                global == repoA.absolutePath || global == repoB.absolutePath,
                "the global must name one of the refreshed projects, got $global",
            )
            // And each window's OWN state stayed its own.
            assertEquals(repoA.absolutePath, windowA.projectPath.value)
            assertEquals(repoB.absolutePath, windowB.projectPath.value)
        }

    @Test
    fun `a refresh that started before a switch cannot repoint the global afterwards`() =
        runTest {
            val repoA = repo("gamma", "gamma-branch")
            val repoB = repo("delta", "delta-branch")

            val windowA = WindowGitState("win-a")

            // Capture the pre-switch epoch the way refreshForWindow does at
            // entry, then run the switch (align) BEFORE the refresh publishes.
            val staleEpoch = GitService.projectEpochForTests().get()

            // The switch: the user leaves A and opens B.
            GitService.alignCurrentProjectPath(repoB.absolutePath)
            assertEquals(repoB.absolutePath, GitService.getCurrentProjectPath())

            // The stale refresh for A now publishes - the epoch moved, so its
            // global seed is refused and B stays the pointed project.
            GitService.refreshForWindowWithEpochForTests(repoA.absolutePath, windowA, staleEpoch)

            assertEquals(
                repoB.absolutePath,
                GitService.getCurrentProjectPath(),
                "a refresh entered before the switch must not repoint the global at the left project",
            )
            // The window's own state still refreshed for A - only the global
            // seed for the diff verbs was refused.
            assertEquals(repoA.absolutePath, windowA.projectPath.value)
        }

    @Test
    fun `align from a window always repoints the global - it is the switch verb`() {
        val repoA = repo("epsilon", "epsilon-branch")
        val repoB = repo("zeta", "zeta-branch")

        GitService.alignCurrentProjectPath(repoA.absolutePath)
        assertEquals(repoA.absolutePath, GitService.getCurrentProjectPath())

        GitService.alignCurrentProjectPath(repoB.absolutePath)
        assertEquals(
            repoB.absolutePath,
            GitService.getCurrentProjectPath(),
            "a fresh align (no captured epoch) always wins - the switch itself must never be dropped",
        )
    }
}
