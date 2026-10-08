package ai.rever.boss.settings

import ai.rever.boss.components.workspaces.WorkspaceSettingsManager
import ai.rever.boss.html.HtmlFileOpenMode
import ai.rever.boss.html.HtmlFileSettings
import ai.rever.boss.html.HtmlFileSettingsStore
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.backupCorrupt
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsPersistenceHardeningTest {
    private lateinit var tempDir: File
    private lateinit var testFile: File

    @BeforeTest
    fun setUp() {
        tempDir =
            File.createTempFile("hardening-test-", "").apply {
                delete()
                mkdirs()
            }
        testFile = File(tempDir, "test-settings.json")
    }

    @AfterTest
    fun cleanUp() {
        try {
            WorkspaceSettingsManager.resetForTesting(null)
        } catch (_: Exception) {
            // Best effort reset
        }
        tempDir.setWritable(true)
        tempDir.deleteRecursively()
    }

    @Test
    fun `corrupt file is quarantined with timestamped backup and original path can be cleanly rewritten`() {
        testFile.writeText("{ invalid json: [corrupted")

        val quarantined = testFile.backupCorrupt()
        assertNotNull(quarantined, "Corrupt file should produce a quarantine backup")
        assertFalse(testFile.exists(), "Original file should have been moved aside")
        assertTrue(quarantined.exists(), "Quarantined backup should exist")
        assertTrue(quarantined.name.startsWith("test-settings.json.corrupt-"))
        assertEquals("{ invalid json: [corrupted", quarantined.readText())

        // Subsequent atomic write creates a clean valid file without overwriting the quarantine
        testFile.atomicWriteText("""{"valid": true}""")
        assertTrue(testFile.exists(), "New file should be written at original path")
        assertEquals("""{"valid": true}""", testFile.readText())

        // The quarantined file is untouched and preserved
        assertTrue(quarantined.exists(), "Quarantined file must remain intact")
        assertEquals("{ invalid json: [corrupted", quarantined.readText())
    }

    @Test
    fun `HtmlFileSettingsStore quarantines corrupt file and recovers with defaults`(): Unit =
        runBlocking {
            testFile.writeText("{ completely corrupted content")
            var failureNotified: Exception? = null

            val store =
                HtmlFileSettingsStore(
                    file = testFile,
                    onFailure = { failureNotified = it },
                )

            val settings = store.awaitSettings()

            // Defaults returned on corruption
            assertEquals(HtmlFileSettings(), settings)
            assertNotNull(failureNotified)

            // The corrupt file was quarantined
            assertFalse(testFile.exists(), "Corrupt settings file should have been moved")
            val corruptBackups =
                tempDir
                    .listFiles()
                    ?.filter {
                        it.name.startsWith("test-settings.json.corrupt-")
                    }.orEmpty()
            assertEquals(1, corruptBackups.size)
            assertEquals("{ completely corrupted content", corruptBackups.first().readText())

            // Subsequent update writes clean valid settings atomically
            store.update(HtmlFileSettings(openMode = HtmlFileOpenMode.BROWSER))
            assertTrue(testFile.exists())
            assertEquals(HtmlFileOpenMode.BROWSER, store.awaitSettings().openMode)

            // Backup is still safely preserved
            assertTrue(corruptBackups.first().exists())
        }

    @Test
    fun `HtmlFileSettingsStore read error does not quarantine settings file`(): Unit =
        runBlocking {
            val validContent = """{"openMode":"BROWSER"}"""
            testFile.writeText(validContent)
            val readDenied = testFile.setReadable(false)
            if (!readDenied) {
                // File.setReadable(false) is a no-op on Windows/some filesystems.
                // Skip read-deny test where not supported.
                return@runBlocking
            }

            try {
                var failureNotified: Exception? = null
                val store =
                    HtmlFileSettingsStore(
                        file = testFile,
                        onFailure = { failureNotified = it },
                    )

                val settings = store.awaitSettings()
                assertEquals(HtmlFileSettings(), settings)
                assertNotNull(failureNotified, "Read error must be notified to onFailure")

                val corruptBackups =
                    tempDir
                        .listFiles()
                        ?.filter { it.name.contains(".corrupt-") }
                        .orEmpty()
                assertTrue(
                    corruptBackups.isEmpty(),
                    "IO read error must NOT trigger quarantine: $corruptBackups",
                )
            } finally {
                testFile.setReadable(true)
            }
            assertTrue(testFile.exists(), "Original file must remain in place")
            assertEquals(validContent, testFile.readText())
        }

    @Test
    fun `WorkspaceSettingsManager preserves valid file when post-parse write fails`() {
        val wsFile = File(tempDir, "workspace-settings.json")
        val validContent = """{"defaultWorkspaceId":"saved-ws","settingsVersion":0}"""
        wsFile.writeText(validContent)

        try {
            WorkspaceSettingsManager.resetForTesting(wsFile)

            // In-memory settings reflect the value
            assertEquals("saved-ws", WorkspaceSettingsManager.currentSettings.value.defaultWorkspaceId)

            // The file itself must NOT have been quarantined
            val corruptBackups =
                tempDir
                    .listFiles()
                    ?.filter { it.name.contains(".corrupt-") }
                    .orEmpty()
            assertTrue(
                corruptBackups.isEmpty(),
                "Valid settings must not be quarantined: $corruptBackups",
            )
            assertTrue(wsFile.exists(), "Original settings file must remain intact")
        } finally {
            WorkspaceSettingsManager.resetForTesting(null)
        }
    }

    @Test
    fun `WorkspaceSettingsManager quarantines corrupt file on load`() {
        val wsFile = File(tempDir, "workspace-settings.json")
        wsFile.writeText("{ malformed json content")

        try {
            WorkspaceSettingsManager.resetForTesting(wsFile)

            val corruptBackups =
                tempDir
                    .listFiles()
                    ?.filter { it.name.startsWith("workspace-settings.json.corrupt-") }
                    .orEmpty()
            assertEquals(1, corruptBackups.size, "Corrupt file must be quarantined")
            assertEquals("{ malformed json content", corruptBackups.first().readText())
        } finally {
            WorkspaceSettingsManager.resetForTesting(null)
        }
    }

    @Test
    fun `empty file clears path without creating quarantine backup`() {
        testFile.writeText("")
        val result = testFile.backupCorrupt()
        assertNull(result)
        assertFalse(testFile.exists(), "0-byte file must be deleted to clear the path")
    }
}
