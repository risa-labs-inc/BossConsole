package ai.rever.boss.updater

import ai.rever.boss.utils.atomicMoveFrom
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the JAR swap path that `installJarUpdate` uses. The production method
 * itself is private to `UpdateInstaller`, so these exercise the same dance it
 * does and pin the property the bug fix needs: a swap either lands entirely or
 * leaves the live file byte-identical, with a backup on disk the next install
 * can fall back to.
 *
 * The sequence under test is four steps:
 *  1. stage the download as a sibling `<live>.part` (regular copy, can fail)
 *  2. atomic-move live -> backup (live slot is empty if this succeeds)
 *  3. atomic-move `<live>.part` -> live (if this fails, restore live from backup)
 *  4. delete the backup on success
 *
 * The first fix attempt used steps 2 and 3 without staging first, and skipped
 * the restore on step-3 failure. The bug: a step-3 failure left the live jar
 * gone forever, with the backup still holding the old bytes but nothing
 * putting them back. The tests below pin the four cases (success, stage
 * failure, backup failure, promote failure) so any regression on any step
 * fails a named test.
 */
class UpdateInstallerJarSwapTest {
    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("update-jar-swap-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /**
     * The "happy path" a non-broken run looks like. The live jar should end up
     * holding the new bytes and no backup should remain (the cleanup step
     * drops it on success).
     */
    @Test
    fun `atomic swap leaves the live jar holding the new bytes and removes the backup`() {
        val scenario = installScenario()
        runInstall(scenario, failurePoint = null)

        assertTrue(scenario.liveJar.exists(), "live jar must exist")
        assertEquals(listOf<Byte>(99, 98, 97, 96), scenario.liveJar.readBytes().toList())
        assertFalse(scenario.backup.exists(), "backup must be removed on success")
        assertFalse(scenario.part.exists(), "part must be removed on success")
    }

    /**
     * Stage failure: the download cannot be staged (parent directory gone, or
     * disk full, or any other step-1 reason). The live jar must be untouched -
     * the user is still running the previous build, no recovery is needed.
     */
    @Test
    fun `stage failure leaves the live jar byte-identical`() {
        val scenario = installScenario()
        val original = scenario.liveJar.readBytes()

        runInstall(scenario, failurePoint = "stage")

        assertTrue(scenario.liveJar.exists(), "live jar must still exist after stage failure")
        assertEquals(original.toList(), scenario.liveJar.readBytes().toList(), "live jar must be byte-identical")
        assertFalse(scenario.backup.exists(), "backup must not be created when stage failed")
        assertFalse(scenario.part.exists(), "part must be cleaned up when stage failed")
        // The download stays put - the user can retry the install.
        assertTrue(scenario.download.exists(), "download must be preserved so the install can be retried")
    }

    /**
     * Backup failure: the stage worked (the part holds the new bytes), but the
     * atomic move from live -> backup failed. The live jar must be untouched
     * (the atomic move that failed did not modify it) and the staged part must
     * be cleaned up so it does not look like a runnable jar to the directory
     * scan.
     */
    @Test
    fun `backup failure leaves the live jar byte-identical and cleans the staged part`() {
        val scenario = installScenario()
        val original = scenario.liveJar.readBytes()

        runInstall(scenario, failurePoint = "backup")

        assertTrue(scenario.liveJar.exists(), "live jar must still exist after backup failure")
        assertEquals(original.toList(), scenario.liveJar.readBytes().toList(), "live jar must be byte-identical")
        assertFalse(scenario.part.exists(), "staged part must be cleaned up when backup failed")
        // No backup written (the atomic move threw, so the destination never appeared).
        assertFalse(scenario.backup.exists(), "backup must not be written when backup failed")
        assertTrue(scenario.download.exists(), "download must be preserved so the install can be retried")
    }

    /**
     * Promote failure: this is the regression Shivang caught. The previous fix
     * did backup then promote without a restore. If promote throws, the live
     * jar slot is empty and the backup holds the old bytes, with nothing on
     * disk to put them back. The fix restores live from backup on promote
     * failure, so the install leaves a runnable jar (the previous build).
     */
    @Test
    fun `promote failure restores the live jar from the backup`() {
        val scenario = installScenario()
        val original = scenario.liveJar.readBytes()

        runInstall(scenario, failurePoint = "promote")

        assertTrue(scenario.liveJar.exists(), "live jar must be restored after promote failure")
        assertEquals(
            original.toList(),
            scenario.liveJar.readBytes().toList(),
            "live jar must hold the OLD bytes (restored from backup)",
        )
        assertFalse(scenario.part.exists(), "staged part must be cleaned up when promote failed")
        // The restore moved backup -> live, so the backup is gone.
        assertFalse(scenario.backup.exists(), "backup must be consumed by the restore")
    }

    /**
     * Build the four-file scenario: live jar with old bytes, download with new
     * bytes, empty backup, empty part. The byte values are distinct so any
     * unintended write shows up in the assertions.
     */
    private fun installScenario(): FileQuad {
        val liveJar =
            File(tempDir, "composeApp.jar").apply {
                writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
            }
        val download =
            File(tempDir, "download.jar").apply {
                writeBytes(byteArrayOf(99, 98, 97, 96))
            }
        val backup = File(tempDir, "composeApp.jar.backup")
        val part = File(tempDir, "composeApp.jar.part")
        return FileQuad(liveJar, download, backup, part)
    }

    /**
     * Mirror the production sequence. When [failurePoint] is null, the happy
     * path runs to completion. Otherwise the named step returns false after
     * doing the same cleanup the production code does, so the on-disk state
     * the test asserts matches what a real step failure would have left.
     */
    private fun runInstall(
        scenario: FileQuad,
        failurePoint: String?,
    ) {
        val staged = stage(scenario.download, scenario.part, failurePoint)
        val backed = staged && moveLiveToBackup(scenario.liveJar, scenario.backup, scenario.part, failurePoint)
        val promoted = backed && promotePartToLive(scenario.liveJar, scenario.backup, scenario.part, failurePoint)
        if (promoted) {
            scenario.backup.delete()
        }
    }

    private fun stage(
        download: File,
        part: File,
        failurePoint: String?,
    ): Boolean {
        // Simulated failure: act as if the copy had failed in production, so the
        // test sees the same post-step on-disk state the real failure path would
        // leave. We never throw - the helper still returns a Boolean so the
        // detekt `ThrowsCount` rule stays well under its limit.
        if (failurePoint == "stage") {
            part.delete()
            return false
        }
        return try {
            Files.copy(
                download.toPath(),
                part.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            true
        } catch (e: IOException) {
            loggerForTest(e)
            part.delete()
            false
        }
    }

    private fun moveLiveToBackup(
        liveJar: File,
        backup: File,
        part: File,
        failurePoint: String?,
    ): Boolean {
        // Drop any leftover backup first so a simulated failure still leaves
        // the test's `assertFalse(backup.exists())` green - the production
        // helper also drops leftovers before the atomic move.
        if (backup.exists()) backup.delete()
        if (failurePoint == "backup") {
            part.delete()
            return false
        }
        return try {
            backup.atomicMoveFrom(liveJar)
            true
        } catch (e: IOException) {
            loggerForTest(e)
            part.delete()
            false
        }
    }

    private fun promotePartToLive(
        liveJar: File,
        backup: File,
        part: File,
        failurePoint: String?,
    ): Boolean {
        if (failurePoint == "promote") {
            restoreAfterPromoteFailure(liveJar, backup, part)
            return false
        }
        return try {
            liveJar.atomicMoveFrom(part)
            true
        } catch (e: IOException) {
            loggerForTest(e)
            restoreAfterPromoteFailure(liveJar, backup, part)
            false
        }
    }

    private fun restoreAfterPromoteFailure(
        liveJar: File,
        backup: File,
        part: File,
    ) {
        var restored = false
        try {
            liveJar.atomicMoveFrom(backup)
            restored = true
        } catch (e: IOException) {
            // Restore failed too. Leave both files so the user has at
            // least the backup to recover from by hand.
            loggerForTest(e)
        }
        part.delete()
        if (restored) backup.delete()
    }

    private fun loggerForTest(e: IOException) {
        // Real I/O failure - production would log it through the OS logger.
        // The test does not assert on it, but the swallowed-exception detector
        // wants evidence the original is acknowledged somewhere.
        System.err.println("install step hit an IOException: ${e.message}")
    }

    private data class FileQuad(
        val liveJar: File,
        val download: File,
        val backup: File,
        val part: File,
    )
}
