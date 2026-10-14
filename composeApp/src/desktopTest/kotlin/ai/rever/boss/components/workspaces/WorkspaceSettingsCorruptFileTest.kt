package ai.rever.boss.components.workspaces

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Corrupt-file recovery for workspace-settings.json (BossConsole#925). #937 made the writes atomic and
 * owner-only; a file that is already corrupt - a torn write from before it, or a hand-edit - was
 * still re-read and re-failed on every launch, and the next save overwrote it with no copy kept.
 * It is now moved aside and replaced with a fresh default.
 *
 * Hermetic through [WorkspaceSettingsManager.resetForTesting], so nothing here touches the operator's ~/.boss.
 */
class WorkspaceSettingsCorruptFileTest {
    private lateinit var tempDir: File
    private lateinit var settingsFile: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("workspace-corrupt-test-").toFile()
        settingsFile = File(tempDir, "workspace-settings.json")
    }

    @AfterTest
    fun tearDown() {
        WorkspaceSettingsManager.resetForTesting()
        tempDir.deleteRecursively()
    }

    @Test
    fun `a corrupt workspace settings file is moved aside and replaced with a fresh default`() {
        assertSelfHeals("{ not valid json at all")
    }

    // The classic artifact of a crash mid-write under the old truncate-on-open writeText.
    @Test
    fun `an empty workspace settings file is moved aside and replaced with a fresh default`() {
        assertSelfHeals("")
    }

    // Valid JSON of the wrong shape, as a hand-edit can leave behind.
    @Test
    fun `a workspace settings file of the wrong shape is set aside and replaced with a fresh default`() {
        assertSelfHeals("[]")
    }

    // Only a decode failure may rename a file away. A read error (here: the path is a directory, so
    // readText throws an IOException) says nothing about the bytes, so nothing is moved.
    @Test
    fun `a read failure that is not a decode failure leaves the file in place`() {
        assertTrue(settingsFile.mkdirs())

        WorkspaceSettingsManager.resetForTesting(settingsFile)

        assertTrue(settingsFile.isDirectory, "the original path must be untouched")
        val corrupt = tempDir.listFiles { f -> f.name.startsWith("${settingsFile.name}.corrupt-") }.orEmpty()
        assertEquals(0, corrupt.size, "no aside may be created for a read error")
    }

    private fun assertSelfHeals(fixture: String) {
        settingsFile.writeText(fixture)

        WorkspaceSettingsManager.resetForTesting(settingsFile)

        assertEquals(
            WorkspaceSettings(settingsVersion = WorkspaceSettings.CURRENT_SETTINGS_VERSION),
            WorkspaceSettingsManager.currentSettings.value,
        )
        assertTrue(settingsFile.isFile, "a fresh file must be written back")
        assertTrue(settingsFile.readText() != fixture, "the corrupt content must not still be at the original path")
        val corrupt = tempDir.listFiles { f -> f.name.startsWith("${settingsFile.name}.corrupt-") }.orEmpty()
        assertEquals(1, corrupt.size, "the corrupt bytes must be kept, renamed aside")
        assertEquals(fixture, corrupt.single().readText())
    }
}
