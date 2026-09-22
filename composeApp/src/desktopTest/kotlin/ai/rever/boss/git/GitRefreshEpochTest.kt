package ai.rever.boss.git

import ai.rever.boss.window.WindowGitState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for the epoch-checked global git state (BossConsole#813,
 * production scope from the #814 review).
 *
 * The production cross-window race: a refresh that started before a project
 * switch and finished after it repointed the shared GLOBAL
 * `currentProjectPath` at a project the user had already left. The fix: a
 * monotonic project epoch - a window captures it at refresh entry, and
 * publishes the global seed ONLY if the epoch is unchanged at publish time;
 * every real project change bumps it (a redundant align is a no-op).
 *
 * Scope: SWITCH-vs-refresh. Two concurrent REFRESHES with different paths
 * are first-publisher-wins - each publish that assigns bumps the epoch, so
 * the other refresh's captured epoch is already stale by the time it
 * publishes (and a window states a claim by aligning, not by refreshing).
 * The racing test pins the per-window states, not the global winner.
 *
 * Uses real `git` in a temp directory (the repo's established git-test
 * pattern) and deterministic in-process interleaving - no coroutine-scheduling
 * luck, per the review's test requirements.
 */
class GitRefreshEpochTest {
    @TempDir
    lateinit var tempDir: File

    @AfterEach
    fun resetGlobal() {
        // The tests repoint the process-global currentProjectPath at temp repos;
        // reset it after every test so a later suite in the same JVM does not
        // read a deleted directory as "the" project.
        GitService.clearCurrentProjectPathForTests()
    }

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

            // The singleton probes `git --version` at init on a REAL-time
            // background scope; until it lands, refreshForWindow
            // short-circuits before reading any repo. Await it so the branch
            // assertions below are deterministic instead of racing that probe.
            // Real-time context: runTest's virtual clock would otherwise
            // time out a wait on real-dispatcher work.
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { GitService.isGitAvailable.first { it } }
            }

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
            // And each window's OWN state stayed its own - including the
            // branch its refresh actually read from its own repo (the
            // cross-contamination the test is named for).
            assertEquals(repoA.absolutePath, windowA.projectPath.value)
            assertEquals(repoB.absolutePath, windowB.projectPath.value)
            assertEquals("alpha-branch", windowA.currentBranch.value)
            assertEquals("beta-branch", windowB.currentBranch.value)
        }

    @Test
    fun `a refresh that started before a switch cannot repoint the global afterwards`() =
        runTest {
            val repoA = repo("gamma", "gamma-branch")
            val repoB = repo("delta", "delta-branch")

            val windowA = WindowGitState("win-a")

            // Capture the pre-switch epoch the way refreshForWindow does at
            // entry, then run the switch (align) BEFORE the refresh publishes.
            val staleEpoch = GitService.projectEpochForTests()

            // The switch: the user leaves A and opens B.
            GitService.alignCurrentProjectPath(repoB.absolutePath)
            assertEquals(repoB.absolutePath, GitService.getCurrentProjectPath())

            // The stale refresh for A now publishes - the epoch moved, so its
            // global seed is refused and B stays the pointed project.
            GitService.refreshForWindowWithEpoch(repoA.absolutePath, windowA, staleEpoch)

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
    fun `a redundant align does not invalidate an in-flight refresh`() =
        runTest {
            val repoA = repo("eta", "eta-branch")
            val repoB = repo("theta", "theta-branch")
            val windowA = WindowGitState("win-a")

            GitService.alignCurrentProjectPath(repoA.absolutePath)
            val epochAfterAlign = GitService.projectEpochForTests()

            // The align is called at the head of ~24 provider verbs (status
            // polls included), so it must NOT bump the epoch when the path is
            // already aligned - otherwise the counter is a call counter, and
            // a poll would invalidate every other window's in-flight refresh.
            GitService.alignCurrentProjectPath(repoA.absolutePath)
            assertEquals(
                epochAfterAlign,
                GitService.projectEpochForTests(),
                "a redundant align must not bump the epoch",
            )

            // The in-flight refresh that captured the epoch before the
            // redundant align survives it and still publishes - publishing a
            // DIFFERENT path than the aligned one, so this assertion proves
            // the publish actually landed (against the aligned path it would
            // be a no-op assignment).
            GitService.refreshForWindowWithEpoch(repoB.absolutePath, windowA, epochAfterAlign)
            assertEquals(repoB.absolutePath, GitService.getCurrentProjectPath())
        }

    @Test
    fun `a switch to a path a refresh already seeded still invalidates a stale refresh`() =
        runTest {
            // The ordering the top bar produces: the project-change effect
            // calls refreshForWindow for the new project BEFORE the provider's
            // align runs. With the publish not bumping the epoch, that later
            // align would be a no-op (path already aligned) and a stale
            // in-flight refresh for the LEFT project could still publish.
            val repoQ = repo("iota", "iota-branch")
            val repoP = repo("kappa", "kappa-branch")
            val windowB = WindowGitState("win-b") // in flight for Q
            val windowA = WindowGitState("win-a") // switching to P

            GitService.alignCurrentProjectPath(repoQ.absolutePath)
            val staleEpoch = GitService.projectEpochForTests()

            // Window A's refresh for the newly switched project publishes P.
            GitService.refreshForWindowWithEpoch(repoP.absolutePath, windowA, staleEpoch)
            assertEquals(repoP.absolutePath, GitService.getCurrentProjectPath())

            // The switch verb for the same path: a no-op (already aligned),
            // so it must NOT be what invalidates the stale refresh.
            GitService.alignCurrentProjectPath(repoP.absolutePath)

            // Window B's refresh, which captured the pre-switch epoch while
            // the user was still on Q: its publish must be dropped, and the
            // global must stay P.
            GitService.refreshForWindowWithEpoch(repoQ.absolutePath, windowB, staleEpoch)
            assertEquals(
                repoP.absolutePath,
                GitService.getCurrentProjectPath(),
                "a stale refresh for the left project must not repoint the global back",
            )
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
