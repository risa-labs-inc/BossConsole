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
 * Repo-wide pin of the git argv-safety invariant: every verb in
 * DesktopGitService that interpolates a caller-controlled ref or hash into
 * git's argv refuses unsafe spellings through the shared isSafeRefName
 * guard, and every path-taking verb keeps the path after a `--`
 * end-of-options separator.
 *
 * The guards are hand-copied per verb ("not currently plugin-reachable is a
 * property of the callers, not of this function", as createBranch's comment
 * puts it), so a copy that is later simplified away is a silent behaviour
 * change, not a compile error. GitProviderWritesToRepoTest pins the
 * checkout/createBranch/merge/rebase copies and GitDataProviderImplTest pins
 * the validator itself; this class pins the copies no other test reached:
 * cherry-pick, revert and the read-only diff/log family, whose comments
 * document the concrete harm (a `--output=<path>` ref would make
 * `git diff` truncate that path, turning a readOnly MCP tool into an
 * arbitrary file write).
 *
 * The corpus is adversarial per the threat model:
 * - LEADING DASH: git reads the value as an OPTION (`--no-commit` on
 *   cherry-pick would stage the pick without committing it).
 * - OPTION-LIKE VALUE: a write primitive on the diff verbs (see above).
 * - EMPTY / BLANK: refused by every write verb (they would reach git as an
 *   empty argv element). getLogForRef is the documented exception: empty
 *   means "log HEAD" and appends NO argv element, pinned from the safe
 *   side below alongside the refusal corpus.
 * - EMBEDDED `--`: inert by construction, because git's parser only reads a
 *   WHOLE `--` element as end-of-options - pinned from the safe side too: a
 *   legal branch named `feature/--upload-pack=evil` must check out normally.
 */
class GitArgvSafetyTest {
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
        // Windows CI runners default core.autocrlf=true, which rewrites file
        // content between the write and the assertion below for no reason.
        git(dir, "config", "core.autocrlf", "false")
        File(dir, "tracked.txt").writeText("one\n")
        git(dir, "add", ".")
        git(dir, "commit", "-q", "-m", "init")
        return dir
    }

    private fun provider(dir: File): GitDataProviderImpl {
        val state = WindowGitState("w")
        return GitDataProviderImpl(state, { "w" }) { dir.absolutePath }
    }

    @Test
    fun `cherry-pick and revert refuse a flag-shaped or empty hash and move nothing`(
        @TempDir tmp: File,
    ) = runTest {
        val dir = repo(tmp)
        val headBefore = git(dir, "rev-parse", "HEAD").trim()

        val p = provider(dir)
        for (hash in listOf("--no-commit", "-x", "", "   ")) {
            val picked = p.cherryPick(hash)
            assertTrue(picked !is GitOperationResultData.Success, "cherryPick accepted '$hash'")
            val reverted = p.revert(hash)
            assertTrue(reverted !is GitOperationResultData.Success, "revert accepted '$hash'")
        }

        assertEquals(headBefore, git(dir, "rev-parse", "HEAD").trim(), "a refused hash must not move HEAD")
    }

    @Test
    fun `cherry-pick and revert still act on a real hash so the refusals above are not vacuous`(
        @TempDir tmp: File,
    ) = runTest {
        val dir = repo(tmp)
        File(dir, "z.txt").writeText("z\n")
        git(dir, "add", "z.txt")
        git(dir, "commit", "-q", "-m", "add-z")
        val real = git(dir, "rev-parse", "HEAD").trim()
        git(dir, "checkout", "-q", "-b", "side", "HEAD~1")

        val p = provider(dir)
        val picked = p.cherryPick(real)
        assertTrue(picked is GitOperationResultData.Success, "cherryPick of a real hash failed: $picked")
        val reverted = p.revert(real)
        assertTrue(reverted is GitOperationResultData.Success, "revert of a real hash failed: $reverted")
    }

    @Test
    fun `getCommitDiff refuses an option-shaped hash instead of truncating a file through it`(
        @TempDir tmp: File,
    ) = runTest {
        val dir = repo(tmp)
        File(dir, "z.txt").writeText("z\n")
        git(dir, "add", "z.txt")
        git(dir, "commit", "-q", "-m", "add-z")
        val marker = File(tmp, "pwned-marker").apply { writeText("sentinel\n") }
        val real = git(dir, "rev-parse", "HEAD").trim()

        assertTrue(
            GitService.getCommitDiff(real, null, dir.absolutePath).isNotEmpty(),
            "control: a real hash must yield its diff",
        )

        val diff = GitService.getCommitDiff("--output=${marker.absolutePath}", null, dir.absolutePath)
        assertTrue(diff.isEmpty(), "getCommitDiff accepted an option-shaped hash: $diff")
        assertEquals("sentinel\n", marker.readText(), "the --output value reached git as an option")
    }

    @Test
    fun `getRefDiff refuses option-shaped refs instead of writing a diff through them`(
        @TempDir tmp: File,
    ) = runTest {
        val dir = repo(tmp)
        File(dir, "z.txt").writeText("z\n")
        git(dir, "add", "z.txt")
        git(dir, "commit", "-q", "-m", "add-z")
        val marker = File(tmp, "pwned-marker").apply { writeText("sentinel\n") }
        val head = git(dir, "rev-parse", "HEAD").trim()

        assertTrue(
            GitService.getRefDiff("HEAD~1", head, null, dir.absolutePath).isNotEmpty(),
            "control: real refs must yield their diff",
        )

        for (from in listOf("--output=${marker.absolutePath}", "-S", "", "   ")) {
            val diff = GitService.getRefDiff(from, head, null, dir.absolutePath)
            assertTrue(diff.isEmpty(), "getRefDiff accepted '$from'")
        }
        assertTrue(
            GitService.getRefDiff(head, "--output=${marker.absolutePath}", null, dir.absolutePath).isEmpty(),
            "getRefDiff accepted an option-shaped toRef",
        )
        assertEquals("sentinel\n", marker.readText(), "an option-shaped ref reached git as an option")
    }

    @Test
    fun `getLogForRef refuses an unsafe ref instead of handing it to git log`(
        @TempDir tmp: File,
    ) = runTest {
        val dir = repo(tmp)
        val state = WindowGitState("argv-safety").also { it.setProjectPath(dir.absolutePath) }

        assertTrue(
            GitService.getLogForRef(state, "HEAD", 10).isNotEmpty(),
            "control: a real ref must yield its log",
        )

        // Empty and blank are the documented log-HEAD flow: the ref is
        // trimmed and, when nothing remains, NO argv element is appended,
        // so nothing can become an option. Pin that contract from the safe
        // side rather than refusing it.
        for (headish in listOf("", "   ")) {
            assertTrue(
                GitService.getLogForRef(state, headish, 10).isNotEmpty(),
                "blank ref '$headish' is the log-HEAD flow and must keep working",
            )
        }

        for (ref in listOf("-p", "--reverse", "--")) {
            val log = GitService.getLogForRef(state, ref, 10)
            assertTrue(log.isEmpty(), "getLogForRef accepted '$ref'")
        }
    }

    @Test
    fun `a branch name embedding a double dash stays one argv element and checks out`(
        @TempDir tmp: File,
    ) = runTest {
        val dir = repo(tmp)
        // Legal refname: git accepts a '-'-prefixed component, so anyone who
        // can push can plant it. It must reach git as ONE argv element: git
        // checks the branch out and never parses '--upload-pack=...' out of
        // the middle of the name.
        val planted = "feature/--upload-pack=evil"
        git(dir, "update-ref", "refs/heads/$planted", "HEAD")

        val result = provider(dir).checkout(planted)

        assertTrue(result is GitOperationResultData.Success, "checkout of the planted name failed: $result")
        assertEquals(planted, git(dir, "rev-parse", "--abbrev-ref", "HEAD").trim())
    }

    @Test
    fun `the service still guards these verbs and keeps paths after the separator`() {
        val service = source()
        // The ref guards this class pins behaviourally, each still present:
        // cherry-pick, revert and getCommitDiff share the commitHash spelling.
        val commitHashGuardCopies = service.split("if (!isSafeRefName(commitHash))").size - 1
        assertTrue(commitHashGuardCopies >= 3, "a commitHash guard copy was removed")
        assertTrue(service.contains("if (!isSafeRefName(fromRef) || !isSafeRefName(toRef))"))
        assertTrue(service.contains("if (target.isNotEmpty() && !isSafeRefName(target))"))
        // The `--` end-of-options separator before every caller-controlled path:
        val pathCallSites =
            listOf(
                "\"add\", \"--\", filePath",
                "\"restore\", \"--staged\", \"--\", *paths.toTypedArray()",
                "\"restore\", \"--\", filePath",
                "\"ls-files\", \"--\", filePath",
                "listOf(\"--no-index\", \"--\", \"/dev/null\", filePath)",
                "listOf(\"--\", filePath)",
            )
        for (fragment in pathCallSites) {
            assertTrue(service.contains(fragment), "a path call site lost its -- separator: $fragment")
        }
        // The diff family terminates the revision list before any pathspec.
        assertTrue(
            service.contains("listOf(context, \"--format=\", \"--find-renames\", commitHash, \"--\")"),
            "getCommitDiff lost the revision-list terminator",
        )
        assertTrue(
            service.contains("listOf(context, fromRef, toRef, \"--\")"),
            "getRefDiff lost the revision-list terminator",
        )
    }

    private fun source(): String {
        val root =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "composeApp/build.gradle.kts").isFile }
                ?: error("could not locate the repository root")
        val file = File(root, "composeApp/src/desktopMain/kotlin/ai/rever/boss/git/DesktopGitService.kt")
        assertTrue(file.isFile, "missing DesktopGitService.kt")
        return file.readText()
    }
}
