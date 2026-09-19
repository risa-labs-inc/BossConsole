package ai.rever.boss.utils

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [loadSettingsWithBackup] keeps a last-known-good `.bak` and restores from it on a decode error,
 * rather than discarding the caller's settings on the first bad parse.
 *
 * A trivial "decode" (parse an Int, throwing on anything else) stands in for JSON decoding so the
 * backup/restore behaviour is tested independently of any serializer.
 */
class SettingsFileBackupTest {
    @TempDir
    lateinit var dir: File

    private fun file() = File(dir, "settings.json")

    private fun File.backup() = settingsBackupFile()

    private val decodeInt: (String) -> Int = { it.trim().toInt() }

    @Test
    fun `a missing file returns null and creates no backup`() {
        val f = file()
        assertNull(f.loadSettingsWithBackup(decodeInt))
        assertTrue(!f.backup().exists())
    }

    @Test
    fun `a clean file decodes and is promoted to the backup`() {
        val f = file().apply { writeText("42") }

        val value = f.loadSettingsWithBackup(decodeInt)

        assertEquals(42, value)
        assertTrue(f.backup().exists(), "a clean load must refresh the backup")
        assertEquals("42", f.backup().readText())
    }

    @Test
    fun `a corrupt file is restored from a good backup and the primary is repaired`() {
        val f = file().apply { writeText("not-an-int") }
        f.backup().writeText("7")
        var restoredCalled = false

        val value = f.loadSettingsWithBackup(decodeInt, onRestoredFromBackup = { restoredCalled = true })

        assertEquals(7, value, "the value comes from the backup")
        assertTrue(restoredCalled)
        assertEquals("7", f.readText(), "the primary file is rewritten from the backup")
    }

    @Test
    fun `a corrupt file with no backup returns null and reports the corruption`() {
        val f = file().apply { writeText("garbage") }
        var reported: Exception? = null

        val value = f.loadSettingsWithBackup(decodeInt, onCorruptPrimary = { reported = it })

        assertNull(value, "with no backup the caller falls back to its own defaults")
        assertTrue(reported is NumberFormatException)
    }

    @Test
    fun `a corrupt file with a corrupt backup returns null`() {
        val f = file().apply { writeText("garbage") }
        f.backup().writeText("also-garbage")

        assertNull(f.loadSettingsWithBackup(decodeInt))
    }
}
