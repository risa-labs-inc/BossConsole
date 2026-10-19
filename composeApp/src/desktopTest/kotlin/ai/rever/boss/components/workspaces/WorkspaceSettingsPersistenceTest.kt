package ai.rever.boss.components.workspaces

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
 * The persistence contract for ~/.boss/workspace-settings.json (BossConsole#925).
 *
 * [WorkspaceSettingsManager] persisted every write - the first-run default, the version
 * stamp a migration writes back, and every explicit save - with plain `File.writeText`, so
 * the file carried the process umask's world-readable mode and a crash mid-write could tear
 * it. These tests pin the `atomicWriteText` replacement (the BossConsole#868 owner-only and
 * atomic pattern) on all of those write paths: owner-only mode on POSIX, a reload that
 * reads the persisted bytes back, and no stray temp files beside the real settings.
 *
 * Hermetic through [WorkspaceSettingsManager.resetForTesting] - the same seam
 * [ai.rever.boss.run.RunConfigurationManager] uses - so no test here touches the
 * operator's real ~/.boss, and the singleton is restored before the next class runs.
 */
class WorkspaceSettingsPersistenceTest {
    private lateinit var tempDir: File
    private lateinit var tempFile: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("workspace-persist-test-").toFile()
        tempFile = File(tempDir, "workspace-settings.json")
        WorkspaceSettingsManager.resetForTesting(tempFile)
    }

    @AfterTest
    fun tearDown() {
        // Point the singleton back at the user's file and reload, so tests that run after
        // this class observe the same state the app would.
        WorkspaceSettingsManager.resetForTesting()
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
        assertEquals(expected, actual, "Workspace settings permissions must be owner-only (0600), got: $actual")
    }

    @Test
    fun `first run creates the default workspace settings owner-only`() {
        // resetForTesting hit the same first-run branch production startup does: a missing
        // file is created with the compiled-in default. That creation is one of the writes
        // that used to go through plain writeText.
        assertTrue(tempFile.exists(), "the default workspace settings file should have been created")
        assertOwnerOnly(tempFile)
    }

    @Test
    fun `saving a default workspace round-trips through the real write path`() =
        runBlocking {
            WorkspaceSettingsManager.setDefaultWorkspaceId("browser")

            assertTrue(tempFile.readText().contains("browser"), "the chosen workspace should be persisted")
            assertOwnerOnly(tempFile)

            // A reload through the same entry point startup uses must read the persisted
            // bytes, not the in-memory value the save just left behind.
            WorkspaceSettingsManager.resetForTesting(tempFile)
            assertEquals("browser", WorkspaceSettingsManager.currentSettings.value.defaultWorkspaceId)
            assertOwnerOnly(tempFile)
        }

    @Test
    fun `permissions stay owner-only across overwrites with no temp leftovers`() =
        runBlocking {
            WorkspaceSettingsManager.setDefaultWorkspaceId("browser")
            WorkspaceSettingsManager.setDefaultWorkspaceId("none")
            WorkspaceSettingsManager.setDefaultWorkspaceId("browser")

            assertOwnerOnly(tempFile)
            assertTrue(tempFile.readText().contains("browser"), "the last write must win on disk")

            // atomicWriteText writes a unique sibling temp file and moves it into place, so
            // a leak would accumulate next to the real settings rather than in the OS temp
            // dir.
            val strays = tempDir.listFiles()?.filter { it.name != tempFile.name }.orEmpty()
            assertTrue(strays.isEmpty(), "unexpected leftovers: ${strays.map { it.name }}")
        }
}
