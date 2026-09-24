package ai.rever.boss.git

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ai.rever.boss.plugin.git.GitOperationResult.Error as GitError

/**
 * Service-layer argv safety for [GitService.cloneRepository] (#1099): the clone
 * URL reaches git's argv, so it is validated here rather than left to the
 * clone dialog's prefix filter - a property of one caller, exactly the property
 * the ref guards in this file refuse to trust.
 *
 * The validator is unit-tested directly. The refusal is tested end-to-end by
 * handing cloneRepository an `ext::` remote-helper URL whose payload must
 * never run. The argv shape is asserted on [GitService.buildCloneCommand] and,
 * like [GitCloneProgressLogTest], on the service source, so a future edit
 * cannot spell the command list inline again.
 */
class GitCloneUrlSafetyTest {
    @Test
    fun `validator accepts the four URL forms the clone dialog accepts`() {
        assertTrue(GitService.isSafeCloneUrl("https://github.com/risa-labs-inc/BossConsole.git"))
        assertTrue(GitService.isSafeCloneUrl("http://example.invalid/repo.git"))
        assertTrue(GitService.isSafeCloneUrl("git@github.com:risa-labs-inc/BossConsole.git"))
        assertTrue(GitService.isSafeCloneUrl("ssh://git@github.com/risa-labs-inc/BossConsole.git"))
    }

    @Test
    fun `validator accepts the explicit local paths the lifecycle tests clone from`() {
        assertTrue(GitService.isSafeCloneUrl("/srv/git/repo.git"))
        assertTrue(GitService.isSafeCloneUrl("relative/repo"))
        assertTrue(GitService.isSafeCloneUrl("C:\\repos\\repo.git"))
    }

    @Test
    fun `validator refuses remote-helper URLs git would execute`() {
        assertFalse(GitService.isSafeCloneUrl("ext::sh -c touch /tmp/pwned"))
        assertFalse(GitService.isSafeCloneUrl("ext::/usr/bin/git-evil"))
        assertFalse(GitService.isSafeCloneUrl("fd::17"))
        assertFalse(GitService.isSafeCloneUrl("helper::address"))
    }

    @Test
    fun `validator refuses option-shaped and off-allow-list URLs`() {
        assertFalse(GitService.isSafeCloneUrl("--upload-pack=touch /tmp/pwned"))
        assertFalse(GitService.isSafeCloneUrl("-u"))
        assertFalse(GitService.isSafeCloneUrl("-oProxyCommand=touch /tmp/pwned"))
        assertFalse(GitService.isSafeCloneUrl("file:///srv/git/repo.git"))
        assertFalse(GitService.isSafeCloneUrl("git://example.invalid/repo.git"))
        assertFalse(GitService.isSafeCloneUrl(" ssh://example.invalid/repo.git"))
    }

    @Test
    fun `validator refuses control characters and over-long URLs`() {
        assertFalse(GitService.isSafeCloneUrl("https://example.invalid/repo.git\nforged log line"))
        assertFalse(GitService.isSafeCloneUrl("https://example.invalid/repo.git\r"))
        assertFalse(GitService.isSafeCloneUrl("/srv/git/repo\u0000.git"))
        assertFalse(GitService.isSafeCloneUrl("https://example.invalid/repo\u007F.git"))
        assertFalse(GitService.isSafeCloneUrl("https://example.invalid/" + "a".repeat(4096)))
        assertTrue(GitService.isSafeCloneUrl("/srv/git/my repo.git"))
        assertTrue(GitService.isSafeCloneUrl("./ext::sh -c x"))
    }

    @Test
    fun `validator refuses blank URLs`() {
        assertFalse(GitService.isSafeCloneUrl(""))
        assertFalse(GitService.isSafeCloneUrl("   "))
    }

    @Test
    fun `clone refuses a remote-helper URL before git runs or the target exists`(
        @TempDir tempDirectory: Path,
    ) = runBlocking {
        val marker = tempDirectory.resolve("pwned-marker").toFile()
        val target = tempDirectory.resolve("must-not-exist").toFile()
        val injection = "ext::sh -c touch ${marker.absolutePath}"

        val result = GitService.cloneRepository(injection, target.absolutePath) {}

        assertTrue(result is GitError, "ext:: must be refused, got: $result")
        assertEquals("Refused an unsafe clone URL", result.message)
        assertFalse(target.exists(), "a refused clone must not create the target directory")
        assertFalse(marker.exists(), "the ext:: payload executed")
    }

    @Test
    fun `clone argv carries the end-of-options separator before both positionals`() {
        assertEquals(
            listOf(
                "git",
                "clone",
                "--progress",
                "--",
                "https://example.invalid/repo.git",
                "/tmp/target",
            ),
            GitService.buildCloneCommand("https://example.invalid/repo.git", "/tmp/target"),
        )
    }

    @Test
    fun `the clone process is still built from the guarded command list`() {
        val service = source("composeApp/src/desktopMain/kotlin/ai/rever/boss/git/DesktopGitService.kt")
        assertTrue(
            service.contains("val cloneCommand = buildCloneCommand(repositoryUrl, targetDirectory)"),
            "clone no longer builds its argv through buildCloneCommand",
        )
        assertTrue(
            service.contains("ProcessBuilder(cloneCommand)"),
            "the clone ProcessBuilder no longer takes the guarded command list",
        )
        assertFalse(
            Regex("""ProcessBuilder\(\s*"git",\s*"clone"""").containsMatchIn(service),
            "the clone argv is spelled inline again",
        )
    }

    private fun source(relative: String): String {
        val root =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "composeApp/build.gradle.kts").isFile }
                ?: error("could not locate the repository root")
        val file = File(root, relative)
        assertTrue(file.isFile, "missing source file: $relative")
        return file.readText()
    }
}
