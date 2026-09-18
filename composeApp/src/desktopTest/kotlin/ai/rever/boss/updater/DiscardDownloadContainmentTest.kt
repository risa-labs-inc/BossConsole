package ai.rever.boss.updater

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `discardDownload` takes a path out of update state and deletes it, so its
 * containment check is the whole safety of the method: without it, a path that
 * travelled through [UpdateState] is an arbitrary-file delete primitive running
 * as the user.
 *
 * Same shape as the installer's own `validateDownloadFile` guard, which this
 * mirrors deliberately - the two must agree on what the staging directory is.
 */
class DiscardDownloadContainmentTest {
    @TempDir
    lateinit var outside: File

    private val service = UpdateService()

    /** A real file inside the staging directory the installer validates against. */
    private fun stagedFile(name: String): File {
        val dir = createRestrictedDir(defaultStagingDir())
        return File(dir, name).apply { writeText("installer bytes") }
    }

    @Test
    fun `a staged artifact is deleted`() {
        val staged = stagedFile("BOSS-9.9.9-test.dmg")

        service.discardDownload(staged.absolutePath)

        assertFalse(staged.exists(), "a download the user declined should not keep occupying the disk")
    }

    @Test
    fun `a file outside the staging directory is refused`() {
        val victim = File(outside, "important.txt").apply { writeText("not an update") }

        service.discardDownload(victim.absolutePath)

        assertTrue(victim.exists(), "the path arrives from UI state; deleting it unchecked deletes anything")
    }

    @Test
    fun `a traversal out of the staging directory is refused`() {
        val victim = File(outside, "important.txt").apply { writeText("not an update") }
        val traversal = File(defaultStagingDir(), "../../${outside.name}/${victim.name}")

        service.discardDownload(traversal.absolutePath)

        assertTrue(victim.exists(), "a string prefix check would have passed this")
    }

    @Test
    fun `a symlink inside staging pointing out is refused`() {
        val victim = File(outside, "important.txt").apply { writeText("not an update") }
        val dir = createRestrictedDir(defaultStagingDir())
        assumeSymlinksSupported(dir.toPath())
        val link = File(dir, "link-to-victim.dmg")
        link.delete()
        Files.createSymbolicLink(link.toPath(), victim.toPath())

        service.discardDownload(link.absolutePath)

        // toRealPath() resolves the link before comparing, so the target is what is
        // checked - the reason the guard resolves both sides rather than comparing
        // the paths it was given.
        assertTrue(victim.exists(), "following a link out of staging would delete an arbitrary file")
        link.delete()
    }

    @Test
    fun `the staging directory itself is refused`() {
        val dir = createRestrictedDir(defaultStagingDir())

        service.discardDownload(dir.absolutePath)

        assertTrue(dir.isDirectory, "an empty asset name resolves to the directory; deleting it is not a cleanup")
    }

    @Test
    fun `an absent path is a no-op`() {
        // Absence is the postcondition, so a path that is already gone is success
        // rather than something to report.
        service.discardDownload(File(defaultStagingDir(), "never-existed.dmg").absolutePath)
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
            // On POSIX, an IOException indicates a real fixture/environment failure that this
            // security-sensitive test must surface rather than silently skip.
            if (!System.getProperty("os.name").startsWith("Windows")) throw e
            assumeTrue(false, "Symbolic link creation is not permitted on this Windows host")
            return
        }
        // Do not misclassify a cleanup failure as lack of symlink support.
        Files.delete(probe)
    }
}
