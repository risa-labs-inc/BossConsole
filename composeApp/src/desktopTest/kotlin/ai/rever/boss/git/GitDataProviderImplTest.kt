package ai.rever.boss.git

import ai.rever.boss.components.workspaces.CommandProcessor
import ai.rever.boss.plugin.api.GitOperationResultData
import ai.rever.boss.window.WindowGitState
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Window-scoped git reads short-circuit to empty when
 * WindowGitState.projectPath is unset. The top bar was the only place that
 * used to set it, so a project picked through a panel's own picker (the
 * codebase panel) saw an empty git view forever. GitDataProviderImpl now
 * bootstraps the path from the window's selected project; these tests pin
 * that bootstrap.
 */
class GitDataProviderImplTest {
    private val temp = File(System.getProperty("java.io.tmpdir"), "boss-git-provider-${hashCode()}")

    @AfterTest
    fun cleanup() {
        temp.deleteRecursively()
    }

    private fun git(vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", temp.absolutePath) + args).start()
        process.waitFor()
        assertEquals(0, process.exitValue(), process.errorStream.bufferedReader().readText())
    }

    /** A real, dirty repository: one commit, then a work-tree change. */
    private fun dirtyRepo(): File {
        temp.mkdirs()
        git("init", "-q")
        git("config", "user.email", "test@boss.local")
        git("config", "user.name", "Test")
        File(temp, "f.txt").writeText("one\n")
        git("add", "f.txt")
        git("commit", "-q", "-m", "init")
        File(temp, "f.txt").appendText("two\n")
        return temp
    }

    @Test
    fun refreshStatusBootstrapsMissingProjectPathFromWindowProject() =
        runTest {
            val repo = dirtyRepo()
            val state = WindowGitState("test-window")
            assertNull(state.projectPath.value)

            val provider = GitDataProviderImpl(state, { "test-window" }) { repo.absolutePath }
            provider.refreshStatus()

            assertEquals(repo.absolutePath, state.projectPath.value)
            assertTrue(
                state.fileStatus.value.isNotEmpty(),
                "the status read must actually run for the bootstrapped path",
            )
        }

    @Test
    fun refreshStatusReportsARealCheckoutAsARepository() =
        runTest {
            val repo = dirtyRepo()
            val state = WindowGitState("test-window")
            assertFalse(state.isGitRepository.value)

            val provider = GitDataProviderImpl(state, { "test-window" }) { repo.absolutePath }
            provider.refreshStatus()

            // Only GitService.refreshForWindow writes these; nothing on the
            // status/log path used to call it, so a panel gating its UI on
            // isGitRepository showed "no repository" for a valid checkout.
            assertTrue(state.isGitRepository.value, "a checkout with commits must report as a repository")
            assertNotNull(state.currentBranch.value, "the branch name must be resolved too")
            assertTrue(state.fileStatus.value.isNotEmpty(), "and the file status must still be read")
        }

    @Test
    fun refreshStatusAlignsGitsProjectPathSoFileDiffsResolve() =
        runTest {
            val repo = dirtyRepo()
            val state = WindowGitState("test-window")

            val provider = GitDataProviderImpl(state, { "test-window" }) { repo.absolutePath }
            provider.refreshStatus()

            // getFileDiff / getCommitDiff / getRefDiff read git's global project
            // path, which only the top bar used to set. Left null, every diff tab
            // opened on "No changes to show" while the panel listed the changes.
            assertEquals(repo.absolutePath, GitService.getCurrentProjectPath())
            assertTrue(
                GitService.getFileDiff("f.txt", staged = false).isNotEmpty(),
                "a dirty tracked file must produce a diff once the path resolves",
            )
        }

    @Test
    fun refreshStatusReportsANonRepositoryAsSuch() =
        runTest {
            val plain =
                java.nio.file.Files
                    .createTempDirectory("not-a-repo")
                    .toFile()
            val state = WindowGitState("test-window")

            val provider = GitDataProviderImpl(state, { "test-window" }) { plain.absolutePath }
            try {
                provider.refreshStatus()

                assertFalse(state.isGitRepository.value)
            } finally {
                provider.dispose()
                plain.deleteRecursively()
            }
        }

    @Test
    fun refreshStatusReProbesAKnownNonRepositoryUntilItBecomesOne() =
        runTest {
            val plain =
                java.nio.file.Files
                    .createTempDirectory("not-a-repo")
                    .toFile()
            val state = WindowGitState("test-window")
            val provider = GitDataProviderImpl(state, { "test-window" }) { plain.absolutePath }
            try {
                provider.refreshStatus()
                assertFalse(state.isGitRepository.value)
                assertEquals(plain.absolutePath, state.projectPath.value)

                // A cached "not a repository" is not the same fact as a cached
                // "is a repository": `git init`, or a panel's first probe simply
                // losing a startup race, can both turn a real non-repo into a
                // real repo underneath an already-open panel. Unlike the
                // confirmed-repo case (which cannot spontaneously reverse), this
                // costs only the one `isGitRepo` command per re-check - the
                // four-command branch bundle still never runs until it is true -
                // so the panel's own Refresh (or the next status poll) must pick
                // it up rather than being stuck showing "not a Git repository"
                // for the rest of the session.
                ProcessBuilder("git", "init", "-q", plain.absolutePath).start().waitFor()
                provider.refreshStatus()

                assertTrue(
                    state.isGitRepository.value,
                    "a directory that became a repository must be re-probed and reported as one",
                )
            } finally {
                provider.dispose()
                plain.deleteRecursively()
            }
        }

    @Test
    fun refreshStatusStillProbesWhenThePathWasSetWithoutARefresh() =
        runTest {
            val repo = dirtyRepo()
            val state = WindowGitState("test-window")
            // Same path as the selected project, written without refreshForWindow.
            // Gating only on projectPath equality would skip the first probe and
            // leave isGitRepository false for a real checkout.
            state.setProjectPath(repo.absolutePath)
            assertFalse(state.isGitRepository.value)

            val provider = GitDataProviderImpl(state, { "test-window" }) { repo.absolutePath }
            provider.refreshStatus()

            assertTrue(
                state.isGitRepository.value,
                "a path written without a probe is still unknown and must be probed",
            )
        }

    @Test
    fun refreshStatusFollowsTheSelectedProjectWhenItChanges() =
        runTest {
            val repo = dirtyRepo()
            val state = WindowGitState("test-window")
            // A window bootstrapped on something that is not a repository - e.g. a
            // folder holding several sibling repos. Keeping that path once it was
            // set left every git view reporting "no repository" after the user
            // switched to a project that has one.
            state.setProjectPath("/definitely/not/a/repo")

            val provider = GitDataProviderImpl(state, { "test-window" }) { repo.absolutePath }
            provider.refreshStatus()

            assertEquals(repo.absolutePath, state.projectPath.value)
            assertTrue(
                state.fileStatus.value.isNotEmpty(),
                "the status read must run against the newly selected project",
            )
        }

    @Test
    fun refreshStatusKeepsAnExistingPathWhenNothingResolves() =
        runTest {
            dirtyRepo()
            val state = WindowGitState("test-window")
            state.setProjectPath("/some/path")

            // A blank provider result means "not resolved yet", not "no project".
            val provider = GitDataProviderImpl(state, { "test-window" }) { null }
            provider.refreshStatus()

            assertEquals("/some/path", state.projectPath.value)
        }

    @Test
    fun refreshStatusWithNoResolvablePathStaysNull() =
        runTest {
            val state = WindowGitState("test-window")
            val provider = GitDataProviderImpl(state, { "test-window" }, { null })

            provider.refreshStatus()

            assertNull(state.projectPath.value)
        }

    // ===== boss-plugin-api 1.0.87: branches + ref-scoped graph =====

    /** [dirtyRepo] plus a second branch with a commit of its own. */
    private fun branchedRepo(): File {
        val repo = dirtyRepo()
        git("checkout", "-q", "-b", "side")
        File(repo, "side.txt").writeText("side\n")
        git("add", "side.txt")
        git("commit", "-q", "-m", "side commit")
        git("checkout", "-q", "-")
        return repo
    }

    @Test
    fun branchesListsEveryLocalBranchAndMarksTheCurrentOne() =
        runTest {
            val repo = branchedRepo()
            val state = WindowGitState("test-window")
            val provider = GitDataProviderImpl(state, { "test-window" }) { repo.absolutePath }

            val branches = provider.branches()

            // `%(HEAD)` emits a SPACE for a non-current branch, and the parser used
            // to drop only the "*" - so every branch but the checked-out one came
            // back as "side ", a name neither `git log` nor `git checkout` resolves.
            assertTrue(branches.any { it.name == "side" }, "the side branch must be listed: $branches")
            assertTrue(branches.none { it.name != it.name.trim() }, "no name carries the marker column: $branches")
            assertEquals(
                1,
                branches.count { it.isCurrent },
                "exactly one branch is checked out: $branches",
            )
            assertFalse(
                branches.first { it.isCurrent }.name == "side",
                "the repo was switched back off side before listing",
            )
        }

    @Test
    fun logGraphForAnotherBranchShowsThatBranchesTipNotHeads() =
        runTest {
            val repo = branchedRepo()
            val state = WindowGitState("test-window")
            val provider = GitDataProviderImpl(state, { "test-window" }) { repo.absolutePath }

            val head = provider.logGraph(50)
            val side = provider.logGraphFor("side", 50)

            assertEquals("init", head.first().subject, "HEAD has only the initial commit")
            assertEquals("side commit", side.first().subject, "side's tip is its own commit")
            assertTrue(side.size > head.size, "side is one commit ahead: $side vs $head")
        }

    @Test
    fun logGraphForDoesNotOverwriteTheWindowsHeadCommitLog() =
        runTest {
            val repo = branchedRepo()
            val state = WindowGitState("test-window")
            val provider = GitDataProviderImpl(state, { "test-window" }) { repo.absolutePath }

            provider.logGraph(50)
            val headLog = state.commitLog.value.map { it.subject }
            provider.logGraphFor("side", 50)

            // WindowGitState.commitLog is HEAD's history - the top bar and the
            // git-log panel read it. Filling it with another branch's commits
            // would silently mis-label them.
            assertEquals(headLog, state.commitLog.value.map { it.subject })
        }

    @Test
    fun logGraphForABlankRefFallsBackToHead() =
        runTest {
            val repo = branchedRepo()
            val state = WindowGitState("test-window")
            val provider = GitDataProviderImpl(state, { "test-window" }) { repo.absolutePath }

            assertEquals(
                provider.logGraph(50).map { it.hash },
                provider.logGraphFor(null, 50).map { it.hash },
            )
        }

    @Test
    fun logGraphForRefusesRefsThatWouldReadAsGitOptions() =
        runTest {
            val repo = branchedRepo()
            val state = WindowGitState("test-window")
            val provider = GitDataProviderImpl(state, { "test-window" }) { repo.absolutePath }

            // The picker only ever feeds names git produced, but the value crosses
            // the plugin API boundary, so it is validated rather than trusted.
            assertTrue(provider.logGraphFor("--upload-pack=touch /tmp/pwn", 50).isEmpty())
            assertTrue(provider.logGraphFor("-n1", 50).isEmpty())
        }

    // ===== boss-plugin-api 1.0.87: the remote verbs, against a real remote =====

    /**
     * [dirtyRepo] wired to a bare repository standing in for `origin`.
     *
     * A local bare repo is a real remote as far as git is concerned - the same
     * refspec plumbing, the same fast-forward rules - and it exercises
     * fetch/pull/push end to end without a network or credentials.
     */
    private fun repoWithRemote(): Pair<File, File> {
        val repo = dirtyRepo()
        val bare =
            java.nio.file.Files
                .createTempDirectory("boss-git-origin")
                .toFile()
        ProcessBuilder("git", "init", "-q", "--bare", bare.absolutePath).start().waitFor()
        git("remote", "add", "origin", bare.absolutePath)
        return repo to bare
    }

    private fun provider(repo: File): Pair<GitDataProviderImpl, WindowGitState> {
        val state = WindowGitState("test-window")
        return GitDataProviderImpl(state, { "test-window" }, { repo.absolutePath }) to state
    }

    @Test
    fun pushPublishesTheCurrentBranchToTheRemote() =
        runTest {
            val (repo, bare) = repoWithRemote()
            val (provider, _) = provider(repo)

            val result = provider.push()

            assertTrue(result is GitOperationResultData.Success, "push said: $result")
            // The bare repo now has the commit - proof the push actually landed
            // rather than the exit code merely being zero.
            val branches =
                ProcessBuilder("git", "-C", bare.absolutePath, "branch", "--format=%(refname:short)")
                    .start()
                    .inputStream
                    .bufferedReader()
                    .readText()
            assertTrue(branches.isNotBlank(), "the remote has no branches after a successful push")
            bare.deleteRecursively()
        }

    @Test
    fun fetchThenPullBringsTheRemoteCommitDown() =
        runTest {
            val (repo, bare) = repoWithRemote()
            val (provider, state) = provider(repo)
            assertTrue(provider.push() is GitOperationResultData.Success)

            // A second clone commits and pushes, so the first repo is genuinely behind.
            val other =
                java.nio.file.Files
                    .createTempDirectory("boss-git-other")
                    .toFile()

            fun other(vararg args: String) {
                ProcessBuilder(listOf("git", "-C", other.absolutePath) + args).start().waitFor()
            }
            ProcessBuilder("git", "clone", "-q", bare.absolutePath, other.absolutePath).start().waitFor()
            other("config", "user.email", "other@boss.local")
            other("config", "user.name", "Other")
            File(other, "remote.txt").writeText("from the other clone\n")
            other("add", "remote.txt")
            other("commit", "-q", "-m", "remote commit")
            other("push", "-q")

            val fetched = provider.fetch()
            assertTrue(fetched is GitOperationResultData.Success, "fetch said: $fetched")
            // The fetch moved the remote-tracking ref, which is what the graph's
            // decorations and the branch picker read.
            assertTrue(
                provider.branches().any { it.isRemote },
                "no remote-tracking branch after a fetch: ${provider.branches()}",
            )

            val pulled = provider.pull()
            assertTrue(pulled is GitOperationResultData.Success, "pull said: $pulled")
            assertTrue(File(repo, "remote.txt").isFile, "pull did not bring the other clone's file down")
            assertEquals(
                "remote commit",
                state.commitLog.value
                    .firstOrNull()
                    ?.subject,
                "pull must leave the window's HEAD log current",
            )

            other.deleteRecursively()
            bare.deleteRecursively()
        }

    @Test
    fun theRemoteVerbsReportNoProjectRatherThanActingOnTheWrongOne() =
        runTest {
            // Window-scoped by construction: no window path means no repository to
            // act on, never "whichever repo some other window refreshed last".
            val state = WindowGitState("test-window")
            val provider = GitDataProviderImpl(state, { "test-window" }, { null })

            assertTrue(provider.fetch() is GitOperationResultData.Error)
            assertTrue(provider.pull() is GitOperationResultData.Error)
            assertTrue(provider.push() is GitOperationResultData.Error)
        }

    @Test
    fun safeRefNameAcceptsRealRefsAndRejectsOptionsAndControlCharacters() {
        assertTrue(GitService.isSafeRefName("main"))
        assertTrue(GitService.isSafeRefName("feat/ide-tab-completion"))
        assertTrue(GitService.isSafeRefName("origin/release-9.5"))
        assertTrue(GitService.isSafeRefName("v1.0.90"))

        assertFalse(GitService.isSafeRefName(""))
        assertFalse(GitService.isSafeRefName("   "))
        assertFalse(GitService.isSafeRefName("--upload-pack=sh"))
        assertFalse(GitService.isSafeRefName("-n1"))
        assertFalse(GitService.isSafeRefName("has space"))
        assertFalse(GitService.isSafeRefName("has\nnewline"))
        assertFalse(GitService.isSafeRefName("a".repeat(256)))
    }

    @Test
    fun shellMetacharactersPassTheArgvGuardSoTheTerminalVerbsQuoteThem() {
        // All of these are names `git check-ref-format --branch` accepts
        // (no whitespace, no leading dash, no control chars), so the argv
        // guard passes them - which is exactly why mergeInTerminal /
        // rebaseInTerminal cannot lean on it: in a shell command string
        // they are live metacharacters. The quote round-trip pins the fix.
        val names =
            listOf("main;reboot", "x`id`", "y$(id)", "z&whoami", "a'b|sh")
        for (name in names) {
            assertTrue(GitService.isSafeRefName(name), "expected $name to pass the argv guard")
            if (!java.io.File("/bin/sh").exists()) continue // POSIX-shell round trip only
            val process =
                ProcessBuilder("/bin/sh", "-c", "printf '%s' ${CommandProcessor.quotePath(name)}")
                    .redirectErrorStream(true)
                    .start()
            val out = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor() == 0, "shell rejected the quoting for $name")
            assertEquals(name, out, "shell round trip for $name")
        }
    }
}
