package ai.rever.boss.settings

import ai.rever.boss.html.HtmlFileOpenMode
import ai.rever.boss.html.HtmlFileSettings
import ai.rever.boss.html.HtmlFileSettingsStore
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.backupCorrupt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SettingsPersistenceHardeningTest {
    private val tempDir: File =
        File.createTempFile("settings-persist-test-", "").let {
            it.delete()
            it.mkdirs()
            it
        }

    private val testFile = File(tempDir, "test-settings.json")

    @AfterTest
    fun cleanUp() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `corrupt file is quarantined to timestamped backup and atomic save succeeds`() {
        testFile.writeText("{ invalid json: [corrupted")
        assertTrue(testFile.exists())

        val quarantined = testFile.backupCorrupt()

        assertNotNull(quarantined, "Corrupt file should produce a quarantined backup")
        assertFalse(testFile.exists(), "Original file must be moved out of the way")
        assertTrue(quarantined.exists(), "Quarantined file must exist")
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
    fun `cancellation exception does not trigger quarantine`() {
        val validContent = """{"preserved": true}"""
        testFile.writeText(validContent)

        var quarantined: File? = null

        // Simulating the catch block in settings managers:
        // try { ... } catch (e: CancellationException) { throw e } catch (e: Exception) { backupCorrupt(...) }
        assertFailsWith<CancellationException> {
            try {
                throw CancellationException("Window closed during loading")
            } catch (e: CancellationException) {
                throw e
            } catch (_: IllegalStateException) {
                quarantined = testFile.backupCorrupt()
            }
        }

        // The file must NOT have been quarantined on job cancellation!
        assertTrue(testFile.exists(), "Valid settings file must not be touched on cancellation")
        assertEquals(validContent, testFile.readText())
        assertEquals(null, quarantined)
    }

    @Test
    fun `HtmlFileSettingsStore quarantines corrupt file and recovers with defaults`() =
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
    fun `empty file does not create quarantine backup`() {
        testFile.writeText("")
        val result = testFile.backupCorrupt()
        assertEquals(null, result)
        // Empty file remains or is handled by the caller
        assertTrue(testFile.exists())
    }
}
