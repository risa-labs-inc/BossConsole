package ai.rever.boss.cli

import ai.rever.boss.plugin.launchpad.PluginValidator
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `boss plugin validate` is run by a developer on a plugin directory they did not write - that is
 * the point of validating it - and its output quotes that plugin's own files: a malformed
 * `plugin.json` is reported with the offending JSON in the message. Printed raw, a manifest can
 * therefore write escape sequences to the developer's terminal and start lines of its own.
 */
class PluginCommandTerminalSafetyTest {
    private val esc = 27.toChar()
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun assertNoTerminalControl(text: String) {
        val offenders = text.filter { (it.code < 0x20 && it != '\n') || it.code == 0x7f || it.code in 0x80..0x9f }
        val codes = offenders.map { it.code }
        assertTrue(offenders.isEmpty(), "control characters would reach the developer's terminal: $codes\n$text")
    }

    private fun pluginDirWithManifest(manifest: String): File {
        val dir = createTempDirectory("plugin-terminal-safety").toFile()
        tempDirs.add(dir)
        File(dir, "plugin.json").writeText(manifest)
        return dir
    }

    @Test
    fun `a check line neutralises its name and message`() {
        val line = formatCheckLine("[x]", "name$esc[2J", "message$esc]0;pwned\nsecond")

        assertNoTerminalControl(line)
        assertEquals(1, line.lines().size, "a message must not start a line of its own:\n$line")
        assertTrue(line.startsWith("[x] name"), line)
    }

    @Test
    fun `an error line neutralises the message`() {
        val line = formatErrorLine("boom$esc[31m\n[✓] Validation passed")

        assertNoTerminalControl(line)
        assertEquals(1, line.lines().size, line)
        assertTrue(line.startsWith("Error: boom"), line)
    }

    @Test
    fun `an error line for no message stays readable`() {
        assertEquals("Error: null", formatErrorLine(null))
    }

    @Test
    fun `validating a hostile manifest cannot reach the terminal through the printed checks`() {
        val dir = pluginDirWithManifest("{ \"id\": \"evil$esc[2J\", not json $esc]0;pwned\nforged line")

        val result = PluginValidator.validate(dir)
        val printed = result.checks.joinToString("\n") { formatCheckLine("[x]", it.name, it.message) }

        assertNoTerminalControl(printed)
        assertEquals(result.checks.size, printed.lines().size, "every check is exactly one printed line:\n$printed")
    }
}
