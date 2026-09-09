package ai.rever.boss.git

import ai.rever.boss.plugin.api.GitOperationResultData
import ai.rever.boss.plugin.git.GitOperationResult
import ai.rever.boss.window.WindowGitState
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every write the codebase panel's GIT tab can perform, checked against real
 * `git` output rather than against the provider's own state.
 *
 * The panel drives `GitDataProvider`; if a button updates the UI but not the
 * index, nothing in the provider's flows would reveal it. These tests shell
 * out to git afterwards and read the porcelain status, so a UI-only mutation
 * fails here.
 */
class GitProviderWritesToRepoTest {
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
        // Windows CI runners default core.autocrlf=true, which would restore a
        // committed "one\n" as "one\r\n" and fail every content assertion
        // below for the wrong reason. Pin LF on this platform too.
        git(dir, "config", "core.autocrlf", "false")
        File(dir, "tracked.txt").writeText("one\n")
        File(dir, "other.txt").writeText("a\n")
        git(dir, "add", ".")
        git(dir, "commit", "-q", "-m", "init")
        return dir
    }

    private fun provider(dir: File): GitDataProviderImpl {
        val state = WindowGitState("w")
        return GitDataProviderImpl(state, { "w" }) { dir.absolutePath }
    }

    /** Porcelain status of one path, e.g. " M", "M ", "??", or "" when clean. */
    private fun statusOf(
        dir: File,
        path: String,
    ): String =
        git(dir, "status", "--porcelain=v1", "--untracked-files=all")
            .lines()
            .firstOrNull { it.length > 3 && it.substring(3).trim() == path }
            ?.take(2)
            ?: ""

    @Test
    fun stageMovesTheChangeIntoTheRealIndex(
        @TempDir tmp: File,
    ) = runTest {
        val dir = repo(tmp)
        File(dir, "tracked.txt").writeText("two\n")
        assertEquals(" M", statusOf(dir, "tracked.txt"), "precondition: unstaged modification")

        val result = provider(dir).stage("tracked.txt")

        assertTrue(result is GitOperationResultData.Success, "stage reported $result")
        assertEquals("M ", statusOf(dir, "tracked.txt"), "the index was not updated")
    }

    @Test
    fun unstageTakesItBackOutOfTheRealIndex(
        @TempDir tmp: File,
    ) = runTest {
        val dir = repo(tmp)
        File(dir, "tracked.txt").writeText("two\n")
        git(dir, "add", "tracked.txt")
        assertEquals("M ", statusOf(dir, "tracked.txt"))

        provider(dir).unstage("tracked.txt")

        assertEquals(" M", statusOf(dir, "tracked.txt"))
    }

    @Test
    fun discardRestoresTheFileOnDisk(
        @TempDir tmp: File,
    ) = runTest {
        val dir = repo(tmp)
        val file = File(dir, "tracked.txt")
        file.writeText("two\n")

        provider(dir).discardChanges("tracked.txt")

        assertEquals("one\n", file.readText(), "the working tree was not restored")
        assertEquals("", statusOf(dir, "tracked.txt"))
    }

    @Test
    fun stageAllAndUnstageAllCoverEveryChange(
        @TempDir tmp: File,
    ) = runTest {
        val dir = repo(tmp)
        File(dir, "tracked.txt").writeText("two\n")
        File(dir, "other.txt").writeText("b\n")
        val p = provider(dir)

        p.stageAll()
        assertEquals("M ", statusOf(dir, "tracked.txt"))
        assertEquals("M ", statusOf(dir, "other.txt"))

        p.unstageAll()
        assertEquals(" M", statusOf(dir, "tracked.txt"))
        assertEquals(" M", statusOf(dir, "other.txt"))
    }

    @Test
    fun commitCreatesARealCommitContainingTheStagedChange(
        @TempDir tmp: File,
    ) = runTest {
        val dir = repo(tmp)
        File(dir, "tracked.txt").writeText("two\n")
        git(dir, "add", "tracked.txt")

        val result = provider(dir).commit("panel commit")

        assertTrue(result is GitOperationResultData.Success, "commit reported $result")
        assertTrue(
            git(dir, "log", "-1", "--pretty=%s").trim() == "panel commit",
            "HEAD subject is ${git(dir, "log", "-1", "--pretty=%s").trim()}",
        )
        assertEquals("", statusOf(dir, "tracked.txt"), "the commit left the change uncommitted")
    }

    @Test
    fun anUntrackedFileCanBeStaged(
        @TempDir tmp: File,
    ) = runTest {
        val dir = repo(tmp)
        File(dir, "new.txt").writeText("fresh\n")
        assertEquals("??", statusOf(dir, "new.txt"))

        provider(dir).stage("new.txt")

        assertEquals("A ", statusOf(dir, "new.txt"))
    }

    @Test
    fun unstagingARenameRestoresBothSidesOfIt(
        @TempDir tmp: File,
    ) = runTest {
        val dir = repo(tmp)
        git(dir, "mv", "tracked.txt", "renamed.txt")
        // git reports this as one index entry: "R  tracked.txt -> renamed.txt"
        assertTrue(
            git(dir, "status", "--porcelain=v1").contains("tracked.txt -> renamed.txt"),
            "precondition: a staged rename",
        )

        provider(dir).unstage("renamed.txt")

        // Restoring only the new path leaves the deletion of the old one
        // staged, which surfaced as the ORIGINAL file reappearing under
        // STAGED after unstaging the renamed one.
        val status = git(dir, "status", "--porcelain=v1", "--untracked-files=all")
        assertTrue(
            status.lines().none { it.startsWith("D ") },
            "the deletion of the old path is still staged:\n$status",
        )
        assertTrue(
            status.lines().any { it.trim().startsWith("??") && it.contains("renamed.txt") },
            "the renamed file should be untracked after a full unstage:\n$status",
        )
    }

    @Test
    fun unstagingAPlainModificationStillTouchesOnlyThatPath(
        @TempDir tmp: File,
    ) = runTest {
        val dir = repo(tmp)
        File(dir, "tracked.txt").writeText("two\n")
        File(dir, "other.txt").writeText("b\n")
        git(dir, "add", ".")

        provider(dir).unstage("tracked.txt")

        assertEquals(" M", statusOf(dir, "tracked.txt"))
        assertEquals("M ", statusOf(dir, "other.txt"), "unstaging one file must not touch another")
    }

    @Test
    fun stageAllIsGitAddDashA_soItAlsoStagesUntrackedFiles(
        @TempDir tmp: File,
    ) = runTest {
        val dir = repo(tmp)
        File(dir, "tracked.txt").writeText("two\n")
        File(dir, "brand-new.txt").writeText("new\n")

        provider(dir).stageAll()

        // Pinned because it is easy to assume otherwise: this is `git add -A`.
        // The panel's CHANGES group therefore stages its own files one by one
        // rather than calling this, so the button beside an UNTRACKED group
        // does not quietly sweep that group in too.
        assertEquals("M ", statusOf(dir, "tracked.txt"))
        assertEquals("A ", statusOf(dir, "brand-new.txt"))
    }

    @Test
    fun discardRestoresFromTheIndexAndLeavesUntrackedFilesAlone(
        @TempDir tmp: File,
    ) = runTest {
        val dir = repo(tmp)
        File(dir, "tracked.txt").writeText("two\n")
        val untracked = File(dir, "fresh.txt").also { it.writeText("x\n") }

        val p = provider(dir)
        p.discardChanges("tracked.txt")
        p.discardChanges("fresh.txt")

        assertEquals("one\n", File(dir, "tracked.txt").readText())
        // `git restore` does not delete untracked files. VS Code offers a
        // separate delete for those, which is why the panel does not put a
        // discard action on an untracked row at all.
        assertTrue(untracked.exists(), "discard must not silently delete an untracked file")
    }

    @Test
    fun concurrentStagesAllLandRatherThanLosingToTheIndexLock(
        @TempDir tmp: File,
    ) = runTest {
        val dir = repo(tmp)
        val names = (1..8).map { "f$it.txt" }
        names.forEach { File(dir, it).writeText("x\n") }
        val p = provider(dir)

        // git holds .git/index.lock for a write and a second concurrent write
        // FAILS rather than queueing, so firing one `git add` per file at once
        // staged the first and silently dropped the rest.
        kotlinx.coroutines.coroutineScope {
            val jobs = names.map { name -> async { p.stage(name) } }
            jobs.forEach { it.await() }
        }

        val missed = names.filter { statusOf(dir, it) != "A " }
        assertTrue(missed.isEmpty(), "these never made it into the index: $missed")
    }

    @Test
    fun checkoutOfASlashedLocalBranchChecksOutThatBranchNotItsLastSegment(
        @TempDir tmp: File,
    ) = runTest {
        val dir = repo(tmp)
        val base = git(dir, "rev-parse", "--abbrev-ref", "HEAD").trim()
        git(dir, "branch", "feature/x")
        // A branch named like the stripped segment makes the old DWIM SUCCEED on
        // the wrong branch rather than fail loudly - the worst variant.
        git(dir, "branch", "x")

        val result = provider(dir).checkout("feature/x")

        assertTrue(result is GitOperationResultData.Success, "checkout reported $result")
        assertEquals(
            "feature/x",
            git(dir, "rev-parse", "--abbrev-ref", "HEAD").trim(),
            "the slashed LOCAL branch name was mangled (base was $base)",
        )
    }

    @Test
    fun aRemoteStyleNameStillDwimsToItsLocalTrackingBranch(
        @TempDir tmp: File,
    ) = runTest {
        val dir = repo(tmp)
        // Simulate a remote-tracking ref without a network: clone into a second
        // worktree-free local clone whose origin is the first repo.
        val cloneDir = File(tmp, "clone").apply { mkdirs() }
        git(dir, "branch", "side")
        git(cloneDir.parentFile, "clone", "-q", dir.absolutePath, cloneDir.absolutePath)
        git(cloneDir, "config", "user.email", "t@example.com")
        git(cloneDir, "config", "user.name", "Test")

        val result = provider(cloneDir).checkout("origin/side")

        assertTrue(result is GitOperationResultData.Success, "checkout reported $result")
        assertEquals(
            "side",
            git(cloneDir, "rev-parse", "--abbrev-ref", "HEAD").trim(),
            "origin/side should create and check out the local tracking branch",
        )
    }

    @Test
    fun aWriteHonoursItsProjectPathOverrideEvenWhenTheGlobalPointsAtAnotherRepo(
        @TempDir tmpA: File,
        @TempDir tmpB: File,
    ) = runTest {
        // The round-4 review scenario: window B's align lands between window A's
        // align and A's command, so a global-resolved `git restore` runs in B's
        // tree. The override travels with the call, so the interleaving cannot
        // redirect it - pinned here by pointing the global at the WRONG repo.
        val globalBefore = GitService.getCurrentProjectPath()
        try {
            val repoA = repo(tmpA)
            val repoB = repo(tmpB)
            File(repoA, "tracked.txt").writeText("A-dirty\n")
            File(repoB, "tracked.txt").writeText("B-dirty\n")
            GitService.alignCurrentProjectPath(repoB.absolutePath)

            val result =
                GitService.discardChanges(
                    "tracked.txt",
                    windowId = null,
                    projectPathOverride = repoA.absolutePath,
                )

            assertTrue(result is ai.rever.boss.plugin.git.GitOperationResult.Success, "discard reported $result")
            assertEquals("one\n", File(repoA, "tracked.txt").readText(), "the override's repo was not restored")
            assertEquals(
                "B-dirty\n",
                File(repoB, "tracked.txt").readText(),
                "the discard leaked into the repo the GLOBAL pointed at",
            )
        } finally {
            // This test steers the GitService singleton; a later test (or this
            // one under parallel execution) must not inherit a global pointing
            // at a deleted @TempDir.
            if (globalBefore == null) {
                GitService.clearCurrentProjectPathForTests()
            } else {
                GitService.alignCurrentProjectPath(globalBefore)
            }
        }
    }

    @Test
    fun unstageAllRefreshesTheStatusOfTheRepoThatWasWrittenNotTheGlobal(
        @TempDir tmpA: File,
        @TempDir tmpB: File,
    ) = runTest {
        // Finding 1 of the round-9 review: the verb's post-write refresh used
        // the NO-ARG getStatus(), which resolves the GLOBAL project - so with
        // two windows the write landed in A but the refresh ran in B, the repo
        // nobody asked about. Both repos dirty in a distinguishable way; the
        // global points at B.
        val globalBefore = GitService.getCurrentProjectPath()
        try {
            val repoA = repo(tmpA)
            val repoB = repo(tmpB)
            File(repoA, "tracked.txt").writeText("A-two\n")
            git(repoA, "add", "tracked.txt") // A: staged
            File(repoB, "tracked.txt").writeText("B-two\n")
            git(repoB, "add", "tracked.txt") // B: staged
            GitService.alignCurrentProjectPath(repoB.absolutePath)
            GitService.getStatus() // prime the global flow from B
            assertTrue(
                GitService.fileStatus.value
                    .first { it.path == "tracked.txt" }
                    .isStaged,
                "precondition: the global flow holds B's staged change",
            )

            provider(repoA).unstageAll()

            val globalEntry = GitService.fileStatus.value.first { it.path == "tracked.txt" }
            assertTrue(
                globalEntry.isUnstaged && !globalEntry.isStaged,
                "the refresh ran in the global's repo (B), not the written one (A): " +
                    "A's entry is ${globalEntry.isStaged}-staged, " +
                    "and B's index was left staged: ${git(repoB, "status", "--porcelain=v1", "--", "tracked.txt")}",
            )
        } finally {
            if (globalBefore == null) {
                GitService.clearCurrentProjectPathForTests()
            } else {
                GitService.alignCurrentProjectPath(globalBefore)
            }
        }
    }

    @Test
    fun discardChangesRefreshesTheStatusOfTheRepoThatWasWrittenNotTheGlobal(
        @TempDir tmpA: File,
        @TempDir tmpB: File,
    ) = runTest {
        // Same finding, same shape: after discarding in A the global flow must
        // hold A's (now clean) status, not B's still-dirty one.
        val globalBefore = GitService.getCurrentProjectPath()
        try {
            val repoA = repo(tmpA)
            val repoB = repo(tmpB)
            File(repoA, "tracked.txt").writeText("A-two\n") // A: unstaged change
            File(repoB, "tracked.txt").writeText("B-two\n")
            git(repoB, "add", "tracked.txt") // B: staged
            GitService.alignCurrentProjectPath(repoB.absolutePath)
            GitService.getStatus() // prime the global flow from B
            assertTrue(GitService.fileStatus.value.any { it.path == "tracked.txt" })

            provider(repoA).discardChanges("tracked.txt")

            assertTrue(
                GitService.fileStatus.value.none { it.path == "tracked.txt" },
                "the refresh must come from A (clean after discard), not B (still dirty): " +
                    "${GitService.fileStatus.value.map { it.path to (it.isStaged || it.isUnstaged) }}",
            )
        } finally {
            if (globalBefore == null) {
                GitService.clearCurrentProjectPathForTests()
            } else {
                GitService.alignCurrentProjectPath(globalBefore)
            }
        }
    }

    @Test
    fun commitRefreshesTheLogOfTheRepoThatWasWrittenNotTheGlobal(
        @TempDir tmpA: File,
        @TempDir tmpB: File,
    ) = runTest {
        // Same finding for commit's getLog(): the global log flow must end on
        // A's new commit, not on B's marker commit.
        val globalBefore = GitService.getCurrentProjectPath()
        try {
            val repoA = repo(tmpA)
            val repoB = repo(tmpB)
            File(repoA, "tracked.txt").writeText("A-two\n")
            git(repoA, "add", "tracked.txt")
            git(repoB, "commit", "-q", "--allow-empty", "-m", "B-marker")
            GitService.alignCurrentProjectPath(repoB.absolutePath)
            GitService.getLog() // prime the global log flow from B
            assertEquals(
                "B-marker",
                GitService.commitLog.value
                    .first()
                    .subject,
            )

            provider(repoA).commit("A-panel-commit")

            assertEquals(
                "A-panel-commit",
                GitService.commitLog.value
                    .first()
                    .subject,
                "the log refresh ran in the global's repo (B), not the committed one (A)",
            )
        } finally {
            if (globalBefore == null) {
                GitService.clearCurrentProjectPathForTests()
            } else {
                GitService.alignCurrentProjectPath(globalBefore)
            }
        }
    }

    @Test
    fun anUnsafeRefIsRefusedByEveryRefTakingVerb(
        @TempDir tmp: File,
    ) = runTest {
        // The guard is `isSafeRefName` in each verb, and each verb is its own
        // copy of that line - so each verb is pinned against the name that
        // would do the most damage if its copy were "simplified" away:
        // `main;reboot` is shell metacharacters in the terminal-verbs' command
        // string, and `-o/evil` is an OPTION to git in the argv verbs.
        val dir = repo(tmp)
        val baseBranch = git(dir, "rev-parse", "--abbrev-ref", "HEAD").trim()
        val globalBefore = GitService.getCurrentProjectPath()
        try {
            val p = provider(dir)
            // Option-shaped names are the argv danger: without the guard they
            // would land as OPTIONS (checkout --force, branch --force ...),
            // and git happily accepts them - a silent behaviour change, not
            // an error. `main;reboot` is the SHELL danger and is only illegal
            // for the terminal verbs: as a single argv element it is a legal
            // git refname, so the argv verbs may accept it.
            for (name in listOf("-o/evil", "--force", "-b")) {
                val checkout = p.checkout(name)
                assertTrue(checkout !is GitOperationResultData.Success, "checkout accepted $name")
                val created = GitService.createBranch(name, checkout = false, projectPathOverride = dir.absolutePath)
                assertTrue(created !is GitOperationResult.Success, "createBranch accepted $name")
                val merged = GitService.merge(name, projectPathOverride = dir.absolutePath)
                assertTrue(merged !is GitOperationResult.Success, "merge accepted $name")
                val rebased = GitService.rebase(name, projectPathOverride = dir.absolutePath)
                assertTrue(rebased !is GitOperationResult.Success, "rebase accepted $name")
            }
            // And nothing was created or checked out: the branch list is unchanged.
            assertEquals(
                git(dir, "branch", "--format=%(refname:short)"),
                "$baseBranch\n",
                "an unsafe ref must not leave a branch behind",
            )
        } finally {
            if (globalBefore == null) {
                GitService.clearCurrentProjectPathForTests()
            } else {
                GitService.alignCurrentProjectPath(globalBefore)
            }
        }
    }

    @Test
    fun anUntrackedFileGetsAnAllAddedDiffNotABlankTab(
        @TempDir tmp: File,
    ) = runTest {
        // The status panel lists untracked files individually
        // (statusReportsUntrackedFilesIndividuallyNotAsADirectory), but the old
        // getFileDiff ran `git diff -- <path>`, which emits nothing for a path
        // git does not track - the diff tab opened blank, reading as "no
        // changes". The fix diffs /dev/null against the file, so its rows
        // arrive all-added, like a staged new file's do.
        val dir = repo(tmp)
        File(dir, "fresh.txt").writeText("hello\nworld\n")
        val p = provider(dir)

        val diff = p.diffFile("fresh.txt", staged = false)

        assertTrue(diff.isNotEmpty(), "an untracked file must produce a diff: $diff")
        val lines =
            diff
                .flatMap { it.hunks }
                .flatMap { it.lines }
                .map { it.kind }
        assertTrue(lines.isNotEmpty(), "the diff has no lines")
        assertTrue(lines.all { it == ai.rever.boss.plugin.api.DiffLineKind.ADDED }, "every line must be added: $lines")
        assertTrue(diff.any { it.path == "fresh.txt" }, "the diff must be keyed on the file: $diff")
        // A clean TRACKED file must still show no diff - the blank there means
        // "no changes" and is correct.
        assertTrue(
            p.diffFile("tracked.txt", staged = false).isEmpty(),
            "a clean tracked file must still diff empty",
        )
    }

    @Test
    fun statusReportsUntrackedFilesIndividuallyNotAsADirectory(
        @TempDir tmp: File,
    ) = runTest {
        val dir = repo(tmp)
        File(dir, "nested/deep").mkdirs()
        File(dir, "nested/deep/a.txt").writeText("x\n")
        File(dir, "nested/b.txt").writeText("y\n")
        val state = WindowGitState("w")
        val p = GitDataProviderImpl(state, { "w" }) { dir.absolutePath }

        p.refreshStatus()

        val paths = state.fileStatus.value.map { it.path }
        // git's default collapses this to a single "nested/" row, which the
        // panel cannot expand, stage individually, or diff.
        assertTrue("nested/deep/a.txt" in paths, "untracked files not listed individually: $paths")
        assertTrue("nested/b.txt" in paths, "untracked files not listed individually: $paths")
    }
}
