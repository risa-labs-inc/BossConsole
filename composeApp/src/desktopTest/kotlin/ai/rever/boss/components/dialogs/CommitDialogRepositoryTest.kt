package ai.rever.boss.components.dialogs

import ai.rever.boss.git.GitService
import ai.rever.boss.plugin.git.GitOperationResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommitDialogRepositoryTest {
    private fun git(
        dir: File,
        vararg args: String,
    ): String {
        val process = ProcessBuilder(listOf("git", *args)).directory(dir).redirectErrorStream(true).start()
        val output =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        assertEquals(0, process.waitFor(), output)
        return output
    }

    private fun repo(
        parent: File,
        name: String,
    ): File =
        File(parent, name).apply {
            mkdirs()
            git(this, "init", "-q")
            git(this, "config", "user.name", "Test")
            git(this, "config", "user.email", "test@example.test")
            git(this, "config", "commit.gpgsign", "false")
            val hooks = resolve(".git/empty-hooks").apply { mkdirs() }
            git(this, "config", "core.hooksPath", hooks.absolutePath)
            git(this, "config", "core.autocrlf", "false")
            resolve("file.txt").writeText("initial\n")
            git(this, "add", ".")
            git(this, "commit", "-qm", "initial $name")
            resolve("file.txt").writeText("changed\n")
        }

    private suspend fun withOtherRepo(
        other: File,
        action: suspend () -> Unit,
    ) {
        val previous = GitService.getCurrentProjectPath()
        GitService.alignCurrentProjectPath(other.absolutePath)
        try {
            action()
        } finally {
            if (previous == null) {
                GitService.clearCurrentProjectPathForTests()
            } else {
                GitService.alignCurrentProjectPath(previous)
            }
        }
    }

    @Test
    fun stagingAndCommitStayInTheDialogsRepository(
        @TempDir temp: File,
    ) = runTest {
        val own = repo(temp, "own")
        val other = repo(temp, "other")
        val dialog = CommitDialogRepository(own.absolutePath, null) { own.absolutePath }
        own.resolve("only-own.txt").writeText("own")
        other.resolve("only-other.txt").writeText("other")
        withOtherRepo(other) {
            assertEquals(setOf("file.txt", "only-own.txt"), dialog.status().map { it.path }.toSet())
            assertTrue(dialog.stage("file.txt") is GitOperationResult.Success)
            assertEquals("file.txt", git(own, "diff", "--cached", "--name-only"))
            assertEquals("", git(other, "diff", "--cached", "--name-only"))
            assertTrue(dialog.unstage("file.txt") is GitOperationResult.Success)
            assertEquals("", git(own, "diff", "--cached", "--name-only"))
            assertTrue(dialog.stageAll() is GitOperationResult.Success)
            assertTrue(dialog.unstageAll() is GitOperationResult.Success)
            assertEquals("", git(own, "diff", "--cached", "--name-only"))
            assertTrue(dialog.stageAll() is GitOperationResult.Success)
            assertTrue(dialog.commit("dialog commit", false) is GitOperationResult.Success)
            assertEquals("dialog commit", git(own, "log", "-1", "--format=%B"))
            assertEquals("initial other", git(other, "log", "-1", "--format=%B"))
            assertEquals("", git(other, "diff", "--cached", "--name-only"))
        }
    }

    @Test
    fun amendReadAndWriteUseTheSameRepository(
        @TempDir temp: File,
    ) = runTest {
        val own = repo(temp, "own")
        val other = repo(temp, "other")
        val dialog = CommitDialogRepository(own.absolutePath, null) { own.absolutePath }
        withOtherRepo(other) {
            assertEquals("initial own", dialog.lastCommitMessage())
            assertTrue(dialog.commit("amended own", true) is GitOperationResult.Success)
            assertEquals("amended own", git(own, "log", "-1", "--format=%B"))
            assertEquals("initial other", git(other, "log", "-1", "--format=%B"))
            assertEquals("1", git(own, "rev-list", "--count", "HEAD"))
        }
    }

    @Test
    fun missingOrChangedProjectRefusesCommands(
        @TempDir temp: File,
    ) = runTest {
        val own = repo(temp, "own")
        val other = repo(temp, "other")
        withOtherRepo(other) {
            for (dialog in listOf(
                CommitDialogRepository(null, null) { null },
                CommitDialogRepository(own.absolutePath, null) { other.absolutePath },
                CommitDialogRepository(own.absolutePath, null) { null },
            )) {
                assertTrue(dialog.stageAll() is GitOperationResult.Error)
                assertTrue(dialog.unstageAll() is GitOperationResult.Error)
                assertTrue(dialog.stage("file.txt") is GitOperationResult.Error)
                assertTrue(dialog.unstage("file.txt") is GitOperationResult.Error)
                assertTrue(dialog.commit("wrong", true) is GitOperationResult.Error)
                assertNull(dialog.lastCommitMessage())
                assertTrue(dialog.status().isEmpty())
            }
            assertEquals("", git(own, "diff", "--cached", "--name-only"))
            assertEquals("", git(other, "diff", "--cached", "--name-only"))
            assertEquals("initial other", git(other, "log", "-1", "--format=%B"))
        }
    }

    @Test
    fun signOffUsesTheDialogsGitIdentityAndPreservesOtherTrailers(
        @TempDir temp: File,
    ) = runTest {
        val own = repo(temp, "own")
        val other = repo(temp, "other")
        git(own, "config", "user.name", "Repository Maintainer")
        git(own, "config", "user.email", "maintainer@example.test")
        val dialog = CommitDialogRepository(own.absolutePath, null) { own.absolutePath }
        withOtherRepo(other) {
            assertTrue(dialog.stageAll() is GitOperationResult.Success)
            val message = "change\n\nSigned-off-by: Contributor <contributor@example.test>"
            assertTrue(dialog.commit(message, amend = false, signOff = true) is GitOperationResult.Success)
            assertEquals(
                "$message\nSigned-off-by: Repository Maintainer <maintainer@example.test>",
                git(own, "log", "-1", "--format=%B"),
            )
            assertEquals("initial other", git(other, "log", "-1", "--format=%B"))
        }
    }

    @Test
    fun repeatedAmendDoesNotDuplicateExistingSignOff(
        @TempDir temp: File,
    ) = runTest {
        val own = repo(temp, "own")
        val dialog = CommitDialogRepository(own.absolutePath, null) { own.absolutePath }
        val message = "amended\n\nSigned-off-by: Test <test@example.test>"
        repeat(2) {
            assertTrue(dialog.commit(message, amend = true, signOff = true) is GitOperationResult.Success)
            assertEquals(message, git(own, "log", "-1", "--format=%B"))
        }
        assertEquals("1", git(own, "rev-list", "--count", "HEAD"))
    }

    @Test
    fun missingGitIdentityLeavesStagedWorkAndAllowsRetry(
        @TempDir temp: File,
    ) = runTest {
        val own = repo(temp, "own")
        git(own, "config", "user.useConfigOnly", "true")
        git(own, "config", "user.email", "")
        git(own, "config", "user.name", "")
        val dialog = CommitDialogRepository(own.absolutePath, null) { own.absolutePath }
        assertTrue(dialog.stageAll() is GitOperationResult.Success)
        assertTrue(dialog.commit("draft", amend = false, signOff = true) is GitOperationResult.Error)
        assertEquals("initial own", git(own, "log", "-1", "--format=%B"))
        assertEquals("file.txt", git(own, "diff", "--cached", "--name-only"))
        git(own, "config", "user.email", "retry@example.test")
        git(own, "config", "user.name", "Test")
        assertTrue(dialog.commit("draft", amend = false, signOff = true) is GitOperationResult.Success)
        assertEquals("draft\n\nSigned-off-by: Test <retry@example.test>", git(own, "log", "-1", "--format=%B"))
    }

    @Test
    fun oldCommitJvmEntryPointRemainsAvailable() {
        GitService::class.java.getMethod(
            "commit",
            String::class.java,
            Boolean::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
            kotlin.coroutines.Continuation::class.java,
        )
        assertTrue(GitService::class.java.declaredMethods.any { it.name == "commit" + "$" + "default" })
    }
}
