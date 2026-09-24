package ai.rever.boss.plugin.browser

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The persistence contract for ~/.boss/browser-zoom-settings.json (BossConsole#925).
 *
 * [BrowserZoomSettingsManager] persisted with plain `File.writeText` on both save paths, so
 * the per-domain zoom file was created world-readable under a default umask and a crash
 * mid-write could tear it. The per-domain zoom history is browsing history - which sites
 * the operator reads at which magnification - which makes it exactly the kind of state the
 * BossConsole#868 owner-only and atomic pattern exists for. These tests pin that
 * replacement on both save paths: owner-only mode on POSIX, a reload that reads the
 * persisted bytes back, and no stray temp files beside the real settings.
 *
 * Hermetic through [BrowserZoomSettingsManager.resetForTesting] - the same seam
 * [ai.rever.boss.run.RunConfigurationManager] uses - so no test here touches the
 * operator's real ~/.boss, and the singleton is restored before the next class runs.
 */
class BrowserZoomSettingsPersistenceTest {
    private lateinit var tempDir: File
    private lateinit var tempFile: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("browser-zoom-persist-test-").toFile()
        tempFile = File(tempDir, "browser-zoom-settings.json")
        BrowserZoomSettingsManager.resetForTesting(tempFile)
    }

    @AfterTest
    fun tearDown() {
        // Point the singleton back at the user's file and reload, so tests that run after
        // this class observe the same state the app would.
        BrowserZoomSettingsManager.resetForTesting()
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
        assertEquals(expected, actual, "Zoom settings permissions must be owner-only (0600), got: $actual")
    }

    @Test
    fun `saveSettingsSync persists a domain zoom and reload reads it back`() {
        BrowserZoomSettingsManager.setZoomForDomain("www.Example.com", 1.5)
        BrowserZoomSettingsManager.saveSettingsSync()

        assertTrue(tempFile.exists(), "the zoom settings file should have been created")
        assertTrue(tempFile.readText().contains("example.com"), "the normalized domain should be persisted")
        assertOwnerOnly(tempFile)

        // A reload through the same entry point startup uses must read the persisted bytes,
        // not whatever the previous in-memory state happened to be.
        BrowserZoomSettingsManager.resetForTesting(tempFile)
        assertEquals(1.5, BrowserZoomSettingsManager.getZoomForDomain("example.com"))
    }

    @Test
    fun `suspend saveSettings persists owner-only too`() =
        runBlocking {
            BrowserZoomSettingsManager.setZoomForDomain("docs.example.org", 2.0)
            BrowserZoomSettingsManager.saveSettings()

            assertTrue(tempFile.readText().contains("docs.example.org"))
            assertOwnerOnly(tempFile)
        }

    @Test
    fun `clearing a domain zoom and resaving keeps the file owner-only with no temp leftovers`() =
        runBlocking {
            BrowserZoomSettingsManager.setZoomForDomain("example.com", 1.5)
            BrowserZoomSettingsManager.saveSettings()
            BrowserZoomSettingsManager.clearDomainZoom("example.com")
            BrowserZoomSettingsManager.saveSettings()

            assertOwnerOnly(tempFile)
            assertFalse(tempFile.readText().contains("example.com"), "a cleared domain should not persist")

            val strays = tempDir.listFiles()?.filter { it.name != tempFile.name }.orEmpty()
            assertTrue(strays.isEmpty(), "unexpected leftovers: ${strays.map { it.name }}")
        }
}
