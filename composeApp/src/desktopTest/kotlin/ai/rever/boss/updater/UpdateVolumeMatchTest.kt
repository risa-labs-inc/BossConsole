package ai.rever.boss.updater

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for Issue #922: the macOS updater installed whatever
 * /Volumes/BOSS* volume the glob found first - a user's own BOSS-named drive,
 * or an older BOSS DMG left mounted - instead of the DMG it had just attached.
 *
 * The volume is now identified from what `hdiutil attach` itself reported, on
 * both sides of the flow: the in-app verification mount, through
 * [mountedBossDmgVolume], and the helper script [UpdateScriptGenerator]
 * generates.
 */
class UpdateVolumeMatchTest {
    @TempDir
    lateinit var tempDir: Path

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    @Test
    fun `hdiutil attach output yields the reported mount point`() {
        val output = "/dev/disk2s1\tApple_HFS\t/Volumes/BOSS\n"

        assertEquals("/Volumes/BOSS", dmgMountPointFromHdiutilOutput(output))
    }

    @Test
    fun `volume names containing spaces survive the tab delimited parse`() {
        // macOS appends " 1" when a volume of the same name is already mounted -
        // exactly the situation in which the old first-match glob went wrong.
        val output = "/dev/disk3s1\tApple_HFS\t/Volumes/BOSS 1\n"

        assertEquals("/Volumes/BOSS 1", dmgMountPointFromHdiutilOutput(output))
    }

    @Test
    fun `the last volumes line wins for multi partition images`() {
        val output = "/dev/disk2s1\tApple_HFS\t/Volumes/Setup\n/dev/disk2s2\tApple_HFS\t/Volumes/BOSS\n"

        assertEquals("/Volumes/BOSS", dmgMountPointFromHdiutilOutput(output))
    }

    @Test
    fun `hdiutil output without a volumes line yields null`() {
        assertNull(dmgMountPointFromHdiutilOutput("hdiutil: attach: no mountable systems\n"))
    }

    @Test
    fun `the hdiutil reported mount point wins over unrelated BOSS named volumes`() {
        // The user's own BOSS-named drive is listed first AND holds an old
        // BOSS.app; the attach output alone must decide, because it names the
        // volume this exact attach mounted.
        val userDrive = volumeDirectory("BOSS")
        val mountedDmg = File("/Volumes/BOSS 1")

        val volume =
            mountedBossDmgVolume(
                hdiutilAttachOutput = "/dev/disk3s1\tApple_HFS\t/Volumes/BOSS 1\n",
                candidateVolumes = listOf(userDrive, mountedDmg),
                appBundleIn = { candidate -> candidate },
            )

        assertEquals(mountedDmg, volume)
    }

    @Test
    fun `the fallback skips BOSS named volumes that hold no BOSS app bundle`() {
        val impostorDrive = volumeDirectory("BOSS")
        val mountedDmg = volumeDirectory("BOSS 1")

        val volume =
            mountedBossDmgVolume(
                hdiutilAttachOutput = null,
                candidateVolumes = listOf(impostorDrive, mountedDmg),
                appBundleIn = { candidate -> candidate.takeIf { it == mountedDmg } },
            )

        assertEquals(mountedDmg, volume)
    }

    @Test
    fun `the fallback prefers the most recently mounted volume holding a bundle`() {
        // Two BOSS DMGs mounted: this update's, attached seconds ago, and last
        // year's the user never ejected. Most recent mount wins.
        val olderImage = volumeDirectory("BOSS")
        val freshImage = volumeDirectory("BOSS 1")
        olderImage.setLastModified(1_000L)
        freshImage.setLastModified(2_000L)

        val volume =
            mountedBossDmgVolume(
                hdiutilAttachOutput = null,
                candidateVolumes = listOf(olderImage, freshImage),
                appBundleIn = { candidate -> candidate },
            )

        assertEquals(freshImage, volume)
    }

    @Test
    fun `no candidates and no output yields null`() {
        val volume =
            mountedBossDmgVolume(
                hdiutilAttachOutput = null,
                candidateVolumes = null,
                appBundleIn = { null },
            )

        assertNull(volume)
    }

    @Test
    fun `macOS script takes the mount point from hdiutil output instead of a Volumes glob`() {
        assumeTrue(!isWindows, "The macOS helper script is only generated on POSIX hosts")

        val scriptFile =
            UpdateScriptGenerator.generateMacOSUpdateScript(
                dmgPath = "/tmp/update.dmg",
                targetAppPath = "/Applications/BOSS.app",
                appPid = 12345,
            )

        try {
            val script = scriptFile.readText()

            // The regression itself: `ls -d /Volumes/BOSS* | head -n 1` answers
            // with whichever BOSS-named volume sorts first, not the DMG attached.
            assertFalse(
                script.contains("/Volumes/BOSS*"),
                "The script must not discover the DMG volume by globbing /Volumes/BOSS*",
            )
            // The volume is now whatever hdiutil reported for this attach...
            assertTrue(
                script.contains("MOUNT_OUTPUT=\$(hdiutil attach"),
                "The script must capture hdiutil attach's output to learn where the DMG mounted",
            )
            // ...parsed as the last tab-delimited field, so volume names that
            // contain spaces survive...
            assertTrue(
                script.contains("awk -F '\\t' '{print \$NF}'"),
                "The script must parse the mount point out of hdiutil's tab-delimited table",
            )
            // ...and a volume that cannot be identified fails closed instead of
            // installing from a guess.
            assertTrue(
                script.contains("[ ! -d \"\$VOLUME\" ]"),
                "The script must refuse to proceed when the DMG's volume cannot be identified",
            )
        } finally {
            scriptFile.delete()
        }
    }

    /** A real directory under the temp dir, since fallback candidates must exist. */
    private fun volumeDirectory(name: String): File = tempDir.resolve(name).toFile().apply { mkdirs() }
}
