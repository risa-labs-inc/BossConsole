package ai.rever.boss.components.plugin.panels.left_top

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for directoryHasChildren / scanDirectory edge cases:
 * empty dir, filtered-only dir (e.g. only node_modules), symlink, and
 * malformed path input (InvalidPathException must not escape as uncaught).
 */
class DesktopFileScannerTest {
    private fun tempDir(): File =
        Files.createTempDirectory("scanner-test").toFile().apply {
            deleteOnExit()
        }

    @Test
    fun `directoryHasChildren returns false for a non-existent path`() {
        val missing = File(tempDir(), "does-not-exist").absolutePath
        assertFalse(directoryHasChildren(missing))
    }

    @Test
    fun `directoryHasChildren returns false for an empty directory`() {
        val dir = tempDir()
        assertFalse(directoryHasChildren(dir.absolutePath))
    }

    @Test
    fun `directoryHasChildren returns true when a visible file is present`() {
        val dir = tempDir()
        File(dir, "readme.txt").writeText("hi")
        assertTrue(directoryHasChildren(dir.absolutePath))
    }

    @Test
    fun `directoryHasChildren returns false when only hidden entries are present`() {
        val dir = tempDir()
        File(dir, ".hidden").writeText("secret")
        assertFalse(directoryHasChildren(dir.absolutePath))
    }

    @Test
    fun `directoryHasChildren returns false when only a filtered directory name is present`() {
        val dir = tempDir()
        File(dir, "node_modules").mkdir()
        assertFalse(directoryHasChildren(dir.absolutePath))
    }

    @Test
    fun `directoryHasChildren does not throw on a malformed path`() {
        // A NUL byte is illegal on every platform's filesystem and makes Paths.get
        // throw InvalidPathException. That must be caught, not propagated, since
        // arbitrary strings can reach this function from plugins.
        val nul = 0.toChar()
        val malformed = "/tmp/bad" + nul + "path"
        assertFalse(directoryHasChildren(malformed))
    }

    @Test
    fun `directoryHasChildren follows a symlink to a non-empty directory`() {
        val real = tempDir()
        File(real, "file.txt").writeText("hi")
        val linkDir = tempDir()
        assumeSymlinksSupported(linkDir.toPath())
        val link = File(linkDir, "link")
        Files.createSymbolicLink(link.toPath(), real.toPath())
        assertTrue(directoryHasChildren(link.absolutePath))
    }

    @Test
    fun `scanDirectory reports hasChildren false for a directory containing only node_modules`() {
        val dir = tempDir()
        File(dir, "node_modules").mkdir()
        val node = scanDirectory(dir.absolutePath)
        assertEquals(false, node?.hasChildren)
        assertTrue(node?.children.orEmpty().isEmpty())
    }

    @Test
    fun `scanDirectory reports hasChildren true for a directory with a visible file`() {
        val dir = tempDir()
        File(dir, "readme.txt").writeText("hi")
        val node = scanDirectory(dir.absolutePath)
        assertEquals(true, node?.hasChildren)
    }

    // ---- showHidden overloads (api 1.0.66 opt-in) ----

    @Test
    fun `scanDirectory with showHidden includes hidden entries in children`() {
        val dir = tempDir()
        File(dir, ".env").writeText("secret")
        File(dir, "readme.txt").writeText("hi")

        val withHidden = scanDirectory(dir.absolutePath, showHidden = true)
        assertEquals(listOf(".env", "readme.txt"), withHidden?.children?.map { it.name })

        val withoutHidden = scanDirectory(dir.absolutePath, showHidden = false)
        assertEquals(listOf("readme.txt"), withoutHidden?.children?.map { it.name })
    }

    @Test
    fun `directoryHasChildren with showHidden counts a directory containing only hidden entries`() {
        val dir = tempDir()
        File(dir, ".hidden").writeText("secret")
        assertTrue(directoryHasChildren(dir.absolutePath, showHidden = true))
        assertFalse(directoryHasChildren(dir.absolutePath, showHidden = false))
    }

    @Test
    fun `scanDirectoryWithDepth threads showHidden through recursion`() =
        runBlocking {
            val dir = tempDir()
            val hiddenDir = File(dir, ".config").apply { mkdir() }
            File(hiddenDir, "settings.json").writeText("{}")
            val visibleDir = File(dir, "src").apply { mkdir() }
            File(visibleDir, ".dotfile").writeText("x")
            File(visibleDir, "main.kt").writeText("fun main() {}")

            val withHidden = scanDirectoryWithDepth(dir.absolutePath, maxDepth = 2, startDepth = 0, showHidden = true)
            val config = withHidden?.children?.firstOrNull { it.name == ".config" }
            assertEquals(listOf("settings.json"), config?.children?.map { it.name })
            val src = withHidden?.children?.firstOrNull { it.name == "src" }
            // nested hidden entry included: the flag propagated into recursion
            assertEquals(listOf(".dotfile", "main.kt"), src?.children?.map { it.name })

            val withoutHidden = scanDirectoryWithDepth(dir.absolutePath, maxDepth = 2, startDepth = 0, showHidden = false)
            assertEquals(listOf("src"), withoutHidden?.children?.map { it.name })
            assertEquals(
                listOf("main.kt"),
                withoutHidden
                    ?.children
                    ?.first()
                    ?.children
                    ?.map { it.name },
            )
        }

    @Test
    fun `build and node_modules stay excluded even with showHidden`() {
        val dir = tempDir()
        File(dir, "build").mkdir()
        File(dir, "node_modules").mkdir()
        File(dir, ".github").mkdir()

        val node = scanDirectory(dir.absolutePath, showHidden = true)
        assertEquals(listOf(".github"), node?.children?.map { it.name })

        val onlySkipped = tempDir()
        File(onlySkipped, "build").mkdir()
        assertFalse(directoryHasChildren(onlySkipped.absolutePath, showHidden = true))
    }

    private fun assumeSymlinksSupported(root: Path) {
        val probe = root.resolve("symlink-support-probe")
        try {
            // The target need not exist. A dangling target avoids leaving a directory cycle
            // behind if cleanup itself fails after the privilege probe succeeds.
            Files.createSymbolicLink(probe, root.resolve("symlink-probe-target"))
        } catch (_: UnsupportedOperationException) {
            assumeTrue(false, "This filesystem does not support symbolic links")
            return
        } catch (e: IOException) {
            // Standard Windows users need Developer Mode or SeCreateSymbolicLinkPrivilege.
            // On POSIX, an IOException indicates a real fixture/environment failure rather than
            // a missing privilege, so surface it instead of silently skipping.
            if (!System.getProperty("os.name").startsWith("Windows")) throw e
            assumeTrue(false, "Symbolic link creation is not permitted on this Windows host")
            return
        }
        // Do not misclassify a cleanup failure as lack of symlink support.
        Files.delete(probe)
    }
}
