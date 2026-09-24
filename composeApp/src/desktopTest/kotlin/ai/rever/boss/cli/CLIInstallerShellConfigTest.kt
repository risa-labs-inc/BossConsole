package ai.rever.boss.cli

import ai.rever.boss.utils.CLIInstaller
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [CLIInstaller]'s shell-config writer. The desktopMain test home is
 * redirected by the build, so each test gets its own fresh directory and the
 * `user.home` system property points at it for the duration of this run.
 *
 * The bug these pin: [CLIInstaller] was reading each candidate shell rc with
 * `File.readText` and writing it back with `File.writeText`, both of which
 * follow symlinks. A `~/.zshrc` that happened to point at, say, a notes file
 * would have that notes file overwritten with a shell config on the next CLI
 * install. The fix refuses to write through a symlink.
 */
class CLIInstallerShellConfigTest {
    private lateinit var homeDir: java.io.File
    private lateinit var realZshrcTarget: java.io.File

    @BeforeTest
    fun setUp() {
        homeDir = java.io.File(System.getProperty("user.home"))
        homeDir.mkdirs()
        // A real file that the symlink will point at. Anything the test would
        // NOT want overwritten lives here.
        realZshrcTarget = java.io.File(homeDir, "real-zshrc-target.md")
        realZshrcTarget.writeText("# my notes\ndo not overwrite me\n")
    }

    @AfterTest
    fun tearDown() {
        // Best-effort cleanup; the build redirects user.home, so the test
        // directory is wiped after the run regardless.
        runCatching {
            java.io.File(homeDir, ".zshrc").delete()
            java.io.File(homeDir, ".bashrc").delete()
            realZshrcTarget.delete()
        }
    }

    /**
     * Pins the bug. Before the fix, the symlink target was overwritten with
     * a shell config that included the BOSS PATH export.
     */
    @Test
    fun `refuses to write through a symlinked shell rc and leaves the target intact`() {
        val zshrc = java.io.File(homeDir, ".zshrc")
        Files.createSymbolicLink(zshrc.toPath(), realZshrcTarget.toPath())

        val result = CLIInstaller.testUpdateShellConfigForHome(homeDir)

        // The install reported failure rather than silently overwriting.
        assertFalse(result.success, "install should not have claimed success on a symlinked rc")
        assertEquals("symlink", result.skippedReason)
        // The real file is byte-identical: nothing on disk was rewritten.
        assertEquals(
            "# my notes\ndo not overwrite me\n",
            realZshrcTarget.readText(),
            "symlink target must not be overwritten",
        )
        // The symlink itself still exists and still points at the same target.
        assertTrue(zshrc.exists())
        assertEquals(
            realZshrcTarget.toPath().toAbsolutePath(),
            Files.readSymbolicLink(zshrc.toPath()).toAbsolutePath(),
        )
    }

    /**
     * Sanity check: a real (non-symlinked) rc still gets the PATH export
     * appended, so the test is exercising the same path and the symlink
     * refusal is not a blanket "never write" gate.
     */
    @Test
    fun `writes a real shell rc and appends the PATH export`() {
        val zshrc = java.io.File(homeDir, ".zshrc")
        runCatching { zshrc.delete() }
        java.io.File(homeDir, ".zshrc").writeText("# my shell config\n")

        val result = CLIInstaller.testUpdateShellConfigForHome(homeDir)

        assertTrue(result.success, "real rc must be writable: ${result.skippedReason}")
        assertEquals(false, result.alreadyConfigured)
        val updated = java.io.File(homeDir, ".zshrc").readText()
        assertTrue(updated.contains("# my shell config"), "original content must survive")
        assertTrue(
            updated.contains("export PATH=") && updated.contains(".local/bin"),
            "PATH export must be appended",
        )
    }

    /**
     * The "already configured" check should still short-circuit on a real rc
     * so a second install is a no-op, and the symlink guard must not
     * interfere with that.
     */
    @Test
    fun `a real rc that already has the PATH export reports alreadyConfigured`() {
        java.io.File(homeDir, ".zshrc").writeText(
            "export PATH=\"\$HOME/.local/bin:\$PATH\"\n",
        )

        val result = CLIInstaller.testUpdateShellConfigForHome(homeDir)

        assertTrue(result.success)
        assertEquals(true, result.alreadyConfigured)
    }

    /**
     * Regression for the "symlink refuses the whole install" bug. A symlinked
     * `.zshrc` (the dotfile-manager case) must not block a perfectly writable
     * `.bashrc` further down the candidate list - the loop must `continue`,
     * not `return`, on a symlink. Before the fix, the symlink was returned as
     * the result and `.bashrc` was never considered.
     */
    @Test
    fun `a symlinked zshrc does not block a real bashrc further down the candidate list`() {
        val zshrc = java.io.File(homeDir, ".zshrc")
        Files.createSymbolicLink(zshrc.toPath(), realZshrcTarget.toPath())
        val bashrc = java.io.File(homeDir, ".bashrc")
        bashrc.writeText("# my bash config\n")

        val result = CLIInstaller.testUpdateShellConfigForHome(homeDir)

        assertTrue(
            result.success,
            "bashrc must have been written despite zshrc being a symlink: ${result.skippedReason}",
        )
        assertEquals(false, result.alreadyConfigured)
        // result.configPath uses the candidate-list template ("$homeDir/.bashrc"), so
        // path separators may differ from bashrc.absolutePath on Windows. Compare
        // by file name to assert "bashrc was the one that won".
        assertTrue(
            result.configPath?.endsWith(".bashrc") == true,
            "the writable candidate is .bashrc, got: ${result.configPath}",
        )
        // The symlink target is untouched (the symlink itself was never written to).
        assertEquals(
            "# my notes\ndo not overwrite me\n",
            realZshrcTarget.readText(),
            "symlink target must not be overwritten",
        )
        // .bashrc now carries the PATH export and its original line.
        val updated = bashrc.readText()
        assertTrue(updated.contains("# my bash config"), "original bashrc content must survive")
        assertTrue(
            updated.contains("export PATH=") && updated.contains(".local/bin"),
            "PATH export must be appended to bashrc",
        )
    }
}
