package ai.rever.boss.components.plugin.providers

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The in-process provider confines plugin reads and writes to the home folder. A Downloads
 * folder the user moved elsewhere (another drive on Windows, an absolute XDG dir on Linux) is
 * the one place outside home it must also admit, since it is the folder the provider itself
 * hands out; every other outside-home path must still be refused.
 *
 * "Outside home" is a temp directory: the test task points user.home at a build directory, so
 * nothing here touches the developer's real home, and the Downloads folder is injected rather
 * than read from the registry.
 */
class RelocatedDownloadsAccessTest {
    private val outside = Files.createTempDirectory("boss-relocated-downloads").toFile().canonicalFile
    private val downloads = File(outside, "Downloads").apply { mkdirs() }
    private val sibling = File(outside, "Elsewhere").apply { mkdirs() }
    private val provider = FileSystemDataProviderImpl { downloads.path }

    @AfterTest
    fun cleanUp() {
        outside.deleteRecursively()
    }

    private fun write(file: File) = runBlocking { provider.writeFile(file.path, "saved") }

    private fun read(path: String) = runBlocking { provider.readFile(path) }

    private fun assertRefused(
        result: Result<*>,
        what: String,
    ) {
        assertTrue(result.exceptionOrNull() is SecurityException, "$what must be refused, got $result")
    }

    /**
     * Every case here is about the Downloads branch of the check. If the temp directory were
     * inside home, the home branch would admit everything and each test would pass for the
     * wrong reason, so the premise gates all of them rather than being a test of its own.
     */
    @BeforeTest
    fun outsideHomeOrSkip() {
        val home = File(System.getProperty("user.home")).canonicalFile
        assumeFalse(outside.startsWith(home), "temp dir $outside is inside home $home; the tests prove nothing")
    }

    @Test
    fun `a plugin can save into a Downloads folder that lives outside home`() {
        val target = File(downloads, "recording.webm")

        assertTrue(write(target).isSuccess, "write into the resolved Downloads folder was refused")
        assertEquals("saved", target.readText())
    }

    @Test
    fun `a plugin can read back what it saved there`() {
        val target = File(downloads, "recording.webm").apply { writeText("saved") }

        assertEquals("saved", read(target.path).getOrThrow())
    }

    @Test
    fun `subfolders of the Downloads folder are admitted, and created as before`() {
        val target = File(File(downloads, "recordings"), "take-1.webm")

        assertTrue(write(target).isSuccess, "write into a Downloads subfolder was refused")
        assertTrue(target.isFile)
    }

    @Test
    fun `a sibling of the Downloads folder is still refused`() {
        val target = File(sibling, "note.txt")

        assertRefused(write(target), "a write next to Downloads")
        assertRefused(read(target.path), "a read next to Downloads")
        assertFalse(target.exists())
    }

    @Test
    fun `a folder that only shares the Downloads name as a prefix is refused`() {
        val lookalike = File(outside, "Downloads-other").apply { mkdirs() }

        assertRefused(write(File(lookalike, "note.txt")), "a write into Downloads-other")
    }

    @Test
    fun `dot-dot cannot climb out of the Downloads folder`() {
        val escape = listOf(downloads.path, "..", sibling.name, "note.txt").joinToString(File.separator)

        assertRefused(runBlocking { provider.writeFile(escape, "saved") }, "a write through ..")
        assertFalse(File(sibling, "note.txt").exists())
    }

    /** A symlink or, on Windows without the symlink privilege, a junction, which needs none. */
    private fun linkOut(
        link: File,
        target: File,
    ): Boolean =
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
            true
        } catch (_: IOException) {
            File.separatorChar == '\\' &&
                ProcessBuilder("cmd", "/c", "mklink", "/J", link.path, target.path)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor() == 0
        } catch (_: UnsupportedOperationException) {
            false
        }

    @Test
    fun `a symlink or junction inside the Downloads folder cannot lead out of it`() {
        val link = File(downloads, "link-out")
        assumeTrue(linkOut(link, sibling), "neither a symlink nor a junction can be created here")

        assertRefused(write(File(link, "note.txt")), "a write through a symlink")
        assertFalse(File(sibling, "note.txt").exists())
    }

    @Test
    fun `a dangling link inside the Downloads folder is refused, and nothing is created at its target`() {
        // Linked while the target exists (a junction needs that), then the target is removed.
        val target = File(outside, "not-yet").apply { mkdirs() }
        val link = File(downloads, "dangling")
        assumeTrue(linkOut(link, target), "neither a symlink nor a junction can be created here")
        assertTrue(target.delete(), "could not remove the link target to leave the link dangling")

        assertRefused(write(link), "a write through a dangling link")
        assertFalse(target.exists(), "the write went through the link to $target")
    }

    @Test
    fun `a Downloads folder that cannot be resolved refuses rather than failing with an I-O error`() {
        // A NUL character makes canonicalFile throw an IOException on every platform.
        val unresolvable = FileSystemDataProviderImpl { File(outside, "Down\u0000loads").path }

        assertRefused(runBlocking { unresolvable.writeFile(File(sibling, "note.txt").path, "saved") }, "a write")
    }

    @Test
    fun `a Downloads folder at a filesystem root admits nothing outside home`() {
        val rootProvider = FileSystemDataProviderImpl { outside.toPath().root.toString() }

        val result = runBlocking { rootProvider.writeFile(File(sibling, "note.txt").path, "saved") }

        assertRefused(result, "a write under a root")
    }

    @Test
    fun `delete stays confined to the home folder`() {
        // Delete is recursive, so admitting the Downloads root would let one call empty it.
        val target = File(downloads, "keep.txt").apply { writeText("kept") }

        assertRefused(runBlocking { provider.delete(target.path) }, "a delete in Downloads outside home")
        assertTrue(target.exists())
    }

    @Test
    fun `the home folder is admitted as before`() {
        val inHome = File(File(System.getProperty("user.home")), "relocated-downloads-test/note.txt")
        try {
            assertTrue(write(inHome).isSuccess, "a write inside home was refused")
        } finally {
            inHome.parentFile.deleteRecursively()
        }
    }
}
