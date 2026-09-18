package ai.rever.boss.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalTextTest {
    private val esc = 27.toChar()
    private val bel = 7.toChar()
    private val csi = 0x9b.toChar()
    private val rlo = 0x202e.toChar()
    private val zwsp = 0x200b.toChar()
    private val bom = 0xfeff.toChar()

    @Test
    fun `ordinary text passes through untouched`() {
        val ordinary = "git_status (boss-workspace) - café 日本語 🚀 [ok]"
        assertEquals(ordinary, TerminalText.safe(ordinary))
    }

    @Test
    fun `safe writes escapes and line breaks out visibly`() {
        assertEquals("a\\nb\\r\\tc", TerminalText.safe("a\nb\r\tc"))
        assertEquals("\\u001b[2J\\u0007", TerminalText.safe("$esc[2J$bel"))
        assertEquals("\\u009b31m", TerminalText.safe("${csi}31m"), "the single-byte CSI is an ESC sequence too")
        assertEquals("\\u202etool", TerminalText.safe("${rlo}tool"), "a bidi override can reorder a name")
        assertEquals("\\u200bx\\ufeff", TerminalText.safe("${zwsp}x$bom"))
    }

    @Test
    fun `a multi-line value keeps its lines but every one is indented, so none can pass for a heading`() {
        val description = "Reads a file.\n• git_status (boss-workspace)\r\n    Runs git."

        val rendered = TerminalText.safeIndented(description, indent = "    ")

        assertEquals(3, rendered.lines().size)
        assertTrue(rendered.lines().all { it.startsWith("    ") }, rendered)
        val bulletLines = rendered.lines().filter { it.startsWith("•") }
        assertTrue(bulletLines.isEmpty(), "the injected bullet must not start a line:\n$rendered")
    }

    @Test
    fun `safeLines still neutralises controls inside a line`() {
        assertEquals(listOf("one", "\\u001b[31mtwo"), TerminalText.safeLines("one\n$esc[31mtwo"))
    }
}
