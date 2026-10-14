package ai.rever.boss.git

import ai.rever.boss.plugin.api.GitOperationResultData
import ai.rever.boss.window.WindowGitState
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for the checkout flag-injection gate (#919): a remote
 * branch whose name strips to a `-`-prefixed value (e.g. `origin/-f`) must be
 * refused instead of reaching `git checkout <stripped> --`, where git parses
 * it as `--force` and silently discards uncommitted work.
 */
class GitCheckoutInjectionTest {
    private fun git(
        dir: File,
        vararg args: String,
    ): String {
        val p = ProcessBuilder(listOf("git", *args)).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
    }

    private fun repo(dir: File): File {
        git(dir, "init", "-q")
        git(dir, "config", "user.email", "t@example.com")
        git(dir, "config", "user.name", "Test")
        git(dir, "config", "commit.gpgsign", "false")
        git(dir, "config", "core.autocrlf", "false")
        File(dir, "tracked.txt").writeText("one\n")
        git(dir, "add", ".")
        git(dir, "commit", "-q", "-m", "init")
        return dir
    }

    private fun provider(dir: File) = GitDataProviderImpl(WindowGitState("w"), { "w" }) { dir.absolutePath }

    @Test
    fun aRemoteBranchStrippingToADashFlagIsRefusedAndNoWorkIsLost(
        @TempDir tmp: File,
    ) = runTest {
        val dir = repo(tmp)
        // Plant a remote branch literally named `-f` via update-ref on the
        // refs/remotes namespace, so the branch picker lists `origin/-f`.
        git(dir, "update-ref", "refs/remotes/origin/-f", "HEAD")

        // Uncommitted work that `git checkout -f --` would discard.
        File(dir, "tracked.txt").writeText("PRECIOUS UNCOMMITTED\n")

        val result = provider(dir).checkout("origin/-f")

        // The gate refuses the post-strip name; the work survives untouched.
        assertTrue(result is GitOperationResultData.Error, "expected a refusal, got $result")
        assertEquals("PRECIOUS UNCOMMITTED\n", File(dir, "tracked.txt").readText())

        // Control: a safe slashed name still checks out and carries the
        // uncommitted change over, exactly as git does without -f.
        git(dir, "branch", "feature/safe")
        val safe = provider(dir).checkout("feature/safe")
        assertTrue(safe is GitOperationResultData.Success, "safe name should not be refused: $safe")
        assertEquals("PRECIOUS UNCOMMITTED\n", File(dir, "tracked.txt").readText())
    }

    @Test
    fun aRemoteNameThatStripsToEmptyIsRefused(
        @TempDir tmp: File,
    ) = runTest {
        val dir = repo(tmp)
        File(dir, "tracked.txt").writeText("PRECIOUS UNCOMMITTED\n")

        val result = provider(dir).checkout("origin/")

        assertTrue(result is GitOperationResultData.Error, "expected a refusal, got $result")
        assertEquals("PRECIOUS UNCOMMITTED\n", File(dir, "tracked.txt").readText())
    }

    @Test
    fun aRefusalLeavesHeadAndTheWorktreeUntouched(
        @TempDir tmp: File,
    ) = runTest {
        val dir = repo(tmp)
        git(dir, "update-ref", "refs/remotes/origin/-f", "HEAD")
        File(dir, "tracked.txt").writeText("PRECIOUS UNCOMMITTED\n")
        val headBefore = git(dir, "rev-parse", "HEAD").trim()

        val result = provider(dir).checkout("origin/-f")

        assertTrue(result is GitOperationResultData.Error)
        assertEquals(headBefore, git(dir, "rev-parse", "HEAD").trim(), "refusal must not move HEAD")
        assertEquals("PRECIOUS UNCOMMITTED\n", File(dir, "tracked.txt").readText())
    }

    @Test
    fun aLocalBranchWhoseWholeNameIsADashFlagIsRefused(
        @TempDir tmp: File,
    ) = runTest {
        val dir = repo(tmp)
        // The gate must also refuse the name as it ARRIVES when it is itself
        // a flag-shaped branch (no slash, no strip needed).
        git(dir, "update-ref", "refs/heads/--force", "HEAD")
        File(dir, "tracked.txt").writeText("PRECIOUS UNCOMMITTED\n")

        val result = provider(dir).checkout("--force")

        assertTrue(result is GitOperationResultData.Error, "expected a refusal, got $result")
        assertEquals("PRECIOUS UNCOMMITTED\n", File(dir, "tracked.txt").readText())
    }
}
