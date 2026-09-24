package ai.rever.boss.keymap

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The persistence contract for ~/.boss/keymap-settings.json (BossConsole#925).
 *
 * The manager persisted every write with plain `File.writeText`, which both truncates the
 * target in place - a crash mid-write leaves a torn keymap - and creates the file with the
 * process umask's mode, typically world-readable 0644 on a default Linux install. A keymap
 * is a record of what its owner does all day, so it is state worth keeping to owner-only.
 * These tests pin the `atomicWriteText` replacement (the BossConsole#868 owner-only and
 * atomic pattern): whole-file replacement, owner-only mode on POSIX, and no stray temp
 * files next to the settings file its own docs describe as hand-editable.
 *
 * Hermetic through [KeymapSettingsManager.resetForTesting] - the same seam
 * [ai.rever.boss.run.RunConfigurationManager] uses - so no test here reads or writes the
 * operator's real ~/.boss, and the singleton is restored before the next class runs.
 */
class KeymapSettingsPersistenceTest {
    private lateinit var tempDir: File
    private lateinit var tempFile: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("keymap-persist-test-").toFile()
        tempFile = File(tempDir, "keymap-settings.json")
        KeymapSettingsManager.resetForTesting(tempFile)
    }

    @AfterTest
    fun tearDown() {
        // Point the singleton back at the user's file and reload, so tests that run after
        // this class observe the same state the app would.
        KeymapSettingsManager.resetForTesting()
        tempDir.deleteRecursively()
    }

    /**
     * POSIX owner-only is the security property #925 is about; on Windows there is no POSIX
     * permission view and the check is skipped, exactly as in AtomicFileWriteTest.
     */
    private fun assertOwnerOnly(file: File) {
        val path = file.toPath()
        if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) == null) return

        val expected = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        val actual = Files.getPosixFilePermissions(path)
        assertEquals(expected, actual, "Keymap file permissions must be owner-only (0600), got: $actual")
    }

    @Test
    fun `first run creates the default keymap owner-only`() {
        // resetForTesting hit the same first-run branch production startup does: a missing
        // file is created with the default keymap. That creation is one of the writes that
        // used to go through plain writeText.
        assertTrue(tempFile.exists(), "the default keymap file should have been created")
        assertOwnerOnly(tempFile)
    }

    @Test
    fun `saving settings round-trips through the real write path`() =
        runBlocking {
            val customized = KeymapSettingsManager.currentSettings.value.copy(presetName = "Emacs")
            KeymapSettingsManager.updateSettings(customized)

            assertTrue(tempFile.readText().contains("Emacs"), "the saved file should carry the chosen preset")

            // A reload through the same entry point startup uses must read the persisted
            // bytes back, not a cached in-memory value.
            KeymapSettingsManager.resetForTesting(tempFile)
            assertEquals("Emacs", KeymapSettingsManager.currentSettings.value.presetName)
            assertOwnerOnly(tempFile)
        }

    @Test
    fun `permissions stay owner-only across overwrites with no temp leftovers`() =
        runBlocking {
            repeat(3) { index ->
                val customized =
                    KeymapSettingsManager.currentSettings.value.copy(customized = index % 2 == 1)
                KeymapSettingsManager.updateSettings(customized)
            }

            assertOwnerOnly(tempFile)

            // atomicWriteText writes a unique sibling temp file and moves it into place, so
            // a leak would accumulate next to the real settings rather than in the OS temp
            // dir - and a leftover is exactly what hand-editing the file could trip over.
            val strays = tempDir.listFiles()?.filter { it.name != tempFile.name }.orEmpty()
            assertTrue(strays.isEmpty(), "unexpected leftovers: ${strays.map { it.name }}")
        }
}
