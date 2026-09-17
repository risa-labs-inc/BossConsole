package ai.rever.boss.git

import ai.rever.boss.plugin.git.GitOperationResult
import ai.rever.boss.window.WindowGitState
import ai.rever.boss.window.WindowGitStateRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitStashWindowContextTest {
    private fun git(
        dir: File,
        vararg args: String,
    ): String {
        val process =
            ProcessBuilder(listOf("git", *args))
                .directory(dir)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "git ${args.joinToString(" ")} timed out")
        assertEquals(0, process.exitValue(), "git ${args.joinToString(" ")} failed:\n$output")
        return output
    }

    private fun repo(dir: File): File {
        git(dir, "init", "-q")
        git(dir, "config", "user.email", "stash-test@example.com")
        git(dir, "config", "user.name", "Stash Test")
        git(dir, "config", "commit.gpgsign", "false")
        git(dir, "config", "core.autocrlf", "false")
        git(dir, "config", "core.hooksPath", File(dir, "no-hooks").absolutePath)
        File(dir, "tracked.txt").writeText("base\n")
        git(dir, "add", "tracked.txt")
        git(dir, "commit", "-q", "-m", "initial")
        return dir
    }

    private suspend fun withRestoredSingleton(block: suspend () -> Unit) {
        val globalBefore = GitService.getCurrentProjectPath()
        try {
            block()
        } finally {
            GitService.stashRefreshBeforePublishForTests = null
            if (globalBefore == null) {
                GitService.clearCurrentProjectPathForTests()
            } else {
                GitService.alignCurrentProjectPath(globalBefore)
            }
        }
    }

    private suspend fun withRegisteredState(
        windowId: String,
        block: suspend (WindowGitState) -> Unit,
    ) {
        val state = WindowGitStateRegistry.register(windowId)
        try {
            block(state)
        } finally {
            WindowGitStateRegistry.unregister(windowId)
        }
    }

    @Test
    fun stashMutatesAndRefreshesOnlyTheInvokingWindowsRepository(
        @TempDir temp: File,
    ) = runTest {
        withRestoredSingleton {
            val repoA = repo(File(temp, "a").apply { mkdirs() })
            val repoB = repo(File(temp, "b").apply { mkdirs() })
            withRegisteredState("window-a-${temp.name}") { stateA ->
                stateA.setProjectPath(repoA.absolutePath)
                val stateB = WindowGitState("window-b").apply { setProjectPath(repoB.absolutePath) }
                File(repoA, "tracked.txt").writeText("A dirty\n")
                File(repoA, "untracked.txt").writeText("A untracked\n")
                File(repoB, "tracked.txt").writeText("B dirty\n")
                GitService.refreshStashListForWindow(stateB)
                GitService.alignCurrentProjectPath(repoB.absolutePath)

                val result =
                    GitService.stash(
                        message = "window A stash",
                        includeUntracked = true,
                        projectPath = repoA.absolutePath,
                        windowGitState = stateA,
                    )

                assertTrue(result is GitOperationResult.Success, "stash reported $result")
                val aStatus = git(repoA, "status", "--porcelain=v1", "--untracked-files=all")
                assertTrue(
                    aStatus.lines().none { it.endsWith(" tracked.txt") || it.endsWith(" untracked.txt") },
                    "A changes were not stashed:\n$aStatus",
                )
                assertEquals("base\n", File(repoA, "tracked.txt").readText())
                assertTrue(!File(repoA, "untracked.txt").exists(), "includeUntracked was not preserved")
                assertEquals(" M tracked.txt\n", git(repoB, "status", "--porcelain=v1"), "B was mutated")
                assertTrue(git(repoA, "stash", "list").contains("window A stash"))
                assertTrue(git(repoB, "stash", "list").isBlank(), "B gained A's stash")
                assertEquals(
                    "window A stash",
                    stateA.stashList.value
                        .single()
                        .message,
                )
                assertTrue(stateA.fileStatus.value.isEmpty(), "A status was not refreshed")
                assertTrue(stateB.stashList.value.isEmpty(), "B window state was published")
            }
        }
    }

    @Test
    fun popUsesTheCapturedRepositoryAndSelectedNonzeroIndex(
        @TempDir temp: File,
    ) = runTest {
        withRestoredSingleton {
            val repoA = repo(File(temp, "a").apply { mkdirs() })
            val repoB = repo(File(temp, "b").apply { mkdirs() })
            withRegisteredState("window-a-${temp.name}") { stateA ->
                stateA.setProjectPath(repoA.absolutePath)
                File(repoA, "tracked.txt").writeText("A older\n")
                git(repoA, "stash", "push", "-m", "A older")
                File(repoA, "tracked.txt").writeText("A newest\n")
                git(repoA, "stash", "push", "-m", "A newest")
                File(repoB, "tracked.txt").writeText("B stash\n")
                git(repoB, "stash", "push", "-m", "B must remain")
                val bStashBefore = git(repoB, "stash", "list", "--format=%H")
                GitService.alignCurrentProjectPath(repoB.absolutePath)

                val result =
                    GitService.stashPop(
                        index = 1,
                        projectPath = repoA.absolutePath,
                        windowGitState = stateA,
                    )

                assertTrue(result is GitOperationResult.Success, "pop reported $result")
                assertEquals("A older\n", File(repoA, "tracked.txt").readText())
                assertEquals(listOf("A newest"), stateA.stashList.value.map { it.message })
                assertEquals(bStashBefore, git(repoB, "stash", "list", "--format=%H"), "B stash changed")
                assertTrue(git(repoB, "status", "--porcelain=v1").isBlank(), "B worktree changed")
            }
        }
    }

    @Test
    fun missingInvokingProjectNeverFallsBackToTheGlobalRepository(
        @TempDir temp: File,
    ) = runTest {
        withRestoredSingleton {
            val repoB = repo(temp)
            File(repoB, "tracked.txt").writeText("B dirty\n")
            GitService.alignCurrentProjectPath(repoB.absolutePath)
            val state = WindowGitState("window-a")

            val result = GitService.stash(projectPath = "  ", windowGitState = state)

            assertTrue(result is GitOperationResult.Error, "blank context reported $result")
            assertEquals(" M tracked.txt\n", git(repoB, "status", "--porcelain=v1"))
            assertTrue(git(repoB, "stash", "list").isBlank())
        }
    }

    @Test
    fun switchedWindowDoesNotReceiveCapturedProjectsRefresh(
        @TempDir temp: File,
    ) = runTest {
        withRestoredSingleton {
            val repoA = repo(File(temp, "a").apply { mkdirs() })
            val repoB = repo(File(temp, "b").apply { mkdirs() })
            withRegisteredState("window-a-${temp.name}") { state ->
                state.setProjectPath(repoB.absolutePath)
                File(repoA, "tracked.txt").writeText("A dirty\n")
                state.updateStashList(listOf(GitStashInfo(index = 7, message = "B sentinel", branch = null)))

                val result =
                    GitService.stash(
                        projectPath = repoA.absolutePath,
                        windowGitState = state,
                    )

                assertTrue(result is GitOperationResult.Success, "captured mutation reported $result")
                assertTrue(git(repoA, "status", "--porcelain=v1").isBlank(), "captured A was not stashed")
                assertEquals(
                    "B sentinel",
                    state.stashList.value
                        .single()
                        .message,
                    "stale A stash was published into B",
                )
                assertEquals(repoB.absolutePath, state.projectPath.value)
            }
        }
    }

    @Test
    fun closedWindowDoesNotReceiveCapturedProjectsRefresh(
        @TempDir temp: File,
    ) = runTest {
        withRestoredSingleton {
            val repoA = repo(temp)
            val windowId = "closed-window-${temp.name}"
            val state = WindowGitStateRegistry.getOrCreate(windowId).apply { setProjectPath(repoA.absolutePath) }
            File(repoA, "tracked.txt").writeText("A dirty\n")
            state.updateStashList(listOf(GitStashInfo(index = 7, message = "closed sentinel", branch = null)))
            WindowGitStateRegistry.unregister(windowId)

            val result = GitService.stash(projectPath = repoA.absolutePath, windowGitState = state)

            assertTrue(result is GitOperationResult.Success, "captured mutation reported $result")
            assertTrue(git(repoA, "status", "--porcelain=v1").isBlank(), "captured A was not stashed")
            assertEquals(
                "closed sentinel",
                state.stashList.value
                    .single()
                    .message,
                "closed window received stale refresh",
            )
        }
    }

    @Test
    fun projectSwitchAwayAndBackBetweenRefreshAndPublicationRejectsBothResults(
        @TempDir temp: File,
    ) = runTest {
        withRestoredSingleton {
            val repoA = repo(File(temp, "a").apply { mkdirs() })
            val repoB = repo(File(temp, "b").apply { mkdirs() })
            withRegisteredState("switch-race-${temp.name}") { state ->
                state.setProjectPath(repoA.absolutePath)
                state.updateStashList(listOf(GitStashInfo(index = 7, message = "B sentinel", branch = null)))
                File(repoA, "tracked.txt").writeText("A dirty\n")
                GitService.stashRefreshBeforePublishForTests = {
                    state.setProjectPath(repoB.absolutePath)
                    state.setProjectPath(repoA.absolutePath)
                }

                val result = GitService.stash(projectPath = repoA.absolutePath, windowGitState = state)

                assertTrue(result is GitOperationResult.Success, "stash reported $result")
                assertEquals(repoA.absolutePath, state.projectPath.value)
                assertEquals(listOf("B sentinel"), state.stashList.value.map { it.message })
            }
        }
    }

    @Test
    fun replacementBetweenRefreshValidationAndPublicationRejectsBothResults(
        @TempDir temp: File,
    ) = runTest {
        withRestoredSingleton {
            val repoA = repo(temp)
            val windowId = "replacement-race-${temp.name}"
            val oldState =
                WindowGitStateRegistry.register(windowId).apply {
                    setProjectPath(repoA.absolutePath)
                    updateStashList(listOf(GitStashInfo(index = 7, message = "old sentinel", branch = null)))
                }
            File(repoA, "tracked.txt").writeText("A dirty\n")
            lateinit var replacement: WindowGitState
            GitService.stashRefreshBeforePublishForTests = {
                replacement = WindowGitStateRegistry.register(windowId).apply { setProjectPath(repoA.absolutePath) }
            }
            try {
                val result = GitService.stash(projectPath = repoA.absolutePath, windowGitState = oldState)

                assertTrue(result is GitOperationResult.Success, "stash reported $result")
                assertEquals(listOf("old sentinel"), oldState.stashList.value.map { it.message })
                assertTrue(replacement.stashList.value.isEmpty())
            } finally {
                WindowGitStateRegistry.unregister(windowId)
            }
        }
    }

    @Test
    fun unregisterBetweenRefreshAndPublicationRejectsBothResults(
        @TempDir temp: File,
    ) = runTest {
        withRestoredSingleton {
            val repo = repo(temp)
            val windowId = "unregister-race-${temp.name}"
            val state =
                WindowGitStateRegistry.register(windowId).apply {
                    setProjectPath(repo.absolutePath)
                    updateStashList(listOf(GitStashInfo(index = 7, message = "closed sentinel", branch = null)))
                }
            File(repo, "tracked.txt").writeText("dirty\n")
            GitService.stashRefreshBeforePublishForTests = { WindowGitStateRegistry.unregister(windowId) }

            val result = GitService.stash(projectPath = repo.absolutePath, windowGitState = state)

            assertTrue(result is GitOperationResult.Success, "stash reported $result")
            assertEquals(listOf("closed sentinel"), state.stashList.value.map { it.message })
        }
    }

    @Test
    fun successfulStashRemainsSuccessfulWhenBestEffortRefreshThrows(
        @TempDir temp: File,
    ) = runTest {
        withRestoredSingleton {
            val repo = repo(temp)
            withRegisteredState("stash-refresh-failure-${temp.name}") { state ->
                state.setProjectPath(repo.absolutePath)
                File(repo, "tracked.txt").writeText("dirty\n")
                GitService.stashRefreshBeforePublishForTests = { error("refresh failed") }

                val result = GitService.stash(projectPath = repo.absolutePath, windowGitState = state)

                assertTrue(result is GitOperationResult.Success, "successful stash reported $result")
                assertTrue(git(repo, "status", "--porcelain=v1").isBlank())
            }
        }
    }

    @Test
    fun popKeepsCommandOutcomeWhenBestEffortRefreshThrows(
        @TempDir temp: File,
    ) = runTest {
        withRestoredSingleton {
            val repo = repo(temp)
            withRegisteredState("pop-refresh-failure-${temp.name}") { state ->
                state.setProjectPath(repo.absolutePath)
                File(repo, "tracked.txt").writeText("stashed\n")
                git(repo, "stash", "push", "-m", "to pop")
                GitService.stashRefreshBeforePublishForTests = { error("refresh failed") }

                val success = GitService.stashPop(index = 0, projectPath = repo.absolutePath, windowGitState = state)
                val failure = GitService.stashPop(index = 99, projectPath = repo.absolutePath, windowGitState = state)

                assertTrue(success is GitOperationResult.Success, "successful pop reported $success")
                assertTrue(failure is GitOperationResult.Error, "failed pop reported $failure")
            }
        }
    }
}
