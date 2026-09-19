package ai.rever.boss.keymap

import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.presets.KeymapPresets
import ai.rever.boss.utils.QUARANTINE_KEEP
import ai.rever.boss.utils.quarantineCorruptFile
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A keymap file that does not parse used to be left where it was while defaults were used in memory,
 * and the next save - the user touches one shortcut, or picks a preset - wrote those defaults over
 * the only copy of what the user had. One truncated write was enough to lose every customisation.
 */
class KeymapSettingsRecoveryTest {
    private val tempDirs = mutableListOf<File>()
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun settingsFile(): File {
        val dir = createTempDirectory("keymap-recovery").toFile()
        tempDirs.add(dir)
        return File(dir, "keymap-settings.json")
    }

    private fun siblings(file: File): List<String> =
        file.parentFile
            .list()
            .orEmpty()
            .sorted()

    @Test
    fun `a missing file reads as missing`() {
        assertIs<KeymapFileRead.Missing>(readKeymapFile(settingsFile(), json))
    }

    @Test
    fun `a valid keymap loads and nothing is set aside`() {
        val file = settingsFile()
        file.writeText(json.encodeToString(KeymapSettings.serializer(), KeymapPresets.getBOSSDefault()))

        val read = readKeymapFile(file, json)

        assertEquals(KeymapPresets.getBOSSDefault(), assertIs<KeymapFileRead.Loaded>(read).settings)
        assertEquals(listOf(file.name), siblings(file))
    }

    @Test
    fun `a corrupt keymap is moved aside with its bytes intact`() {
        val file = settingsFile()
        val original = "{ this is not a keymap"
        file.writeText(original)

        val read = assertIs<KeymapFileRead.Corrupt>(readKeymapFile(file, json))

        val kept = assertNotNull(read.quarantinedTo, "the corrupt file must be kept somewhere")
        assertFalse(file.exists(), "the corrupt file must no longer sit where the next save would overwrite it")
        assertEquals(original, kept.readText())
    }

    @Test
    fun `a keymap cut off mid-write - the shape a crash leaves - is preserved`() {
        val file = settingsFile()
        val full = json.encodeToString(KeymapSettings.serializer(), KeymapPresets.getBOSSDefault())
        val truncated = full.take(full.length / 2)
        file.writeText(truncated)

        val read = assertIs<KeymapFileRead.Corrupt>(readKeymapFile(file, json))

        assertEquals(truncated, assertNotNull(read.quarantinedTo).readText())
    }

    @Test
    fun `an empty file - what a truncate-then-crash leaves - is corrupt, not a keymap`() {
        val file = settingsFile()
        file.writeText("")

        assertIs<KeymapFileRead.Corrupt>(readKeymapFile(file, json))
    }

    @Test
    fun `a file that cannot be read is left exactly where it is`() {
        // A directory where the file should be: readText throws IOException, which says nothing about
        // the contents. Moving it would take a possibly good keymap away over a transient error.
        val file = settingsFile()
        assertTrue(file.mkdir())

        assertIs<KeymapFileRead.Unreadable>(readKeymapFile(file, json))

        assertTrue(file.isDirectory)
        assertEquals(listOf(file.name), siblings(file))
    }

    @Test
    fun `only the newest few set-aside copies are kept`() {
        val file = settingsFile()
        repeat(QUARANTINE_KEEP + 3) { round ->
            file.writeText("corrupt $round")
            assertNotNull(file.quarantineCorruptFile(now = 1_000L + round))
        }

        val kept = siblings(file)

        assertEquals(QUARANTINE_KEEP, kept.size, kept.toString())
        val newest = (QUARANTINE_KEEP + 2 downTo 3).map { "corrupt $it" }
        val contents = kept.map { File(file.parentFile, it).readText() }.sortedDescending()
        assertEquals(newest, contents, "the newest copies survive, the oldest are pruned")
    }

    @Test
    fun `quarantining nothing is a no-op`() {
        val missing = settingsFile()

        assertNull(missing.quarantineCorruptFile())
        assertEquals(emptyList(), siblings(missing))
    }
}
