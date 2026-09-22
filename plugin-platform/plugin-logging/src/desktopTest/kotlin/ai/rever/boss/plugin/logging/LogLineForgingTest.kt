package ai.rever.boss.plugin.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One log record is one line.
 *
 * A record is a line, and everything that reads the log file (tail, grep, a log viewer, whatever
 * ships it off the machine) treats it as one. The message, the component, the data values and the
 * exception message all carry text the app did not write: a URL, a file name, a plugin id, a
 * server's error body. A newline in any of them starts a second line that looks exactly like a
 * record the logger wrote, so someone who controls one string controls what the log claims
 * happened. Terminal escapes in the same strings act on whoever `cat`s the file.
 *
 * Control characters are built from code points on purpose: a formatter that "helpfully" turns an
 * escape in a string literal into the raw character would put invisible bytes in this source.
 */
class LogLineForgingTest {
    private val esc = 27.toChar()
    private val bell = 7.toChar()
    private val nel = 0x85.toChar()
    private val rlo = 0x202e.toChar()
    private val lineSeparator = 0x2028.toChar()
    private val paragraphSeparator = 0x2029.toChar()
    private val verticalTab = 0x0b.toChar()
    private val formFeed = 0x0c.toChar()
    private val smile = String(Character.toChars(0x1F600))

    private val forged = "\n2026-01-01 00:00:00.000 [ERROR] [AUTH] Auth: root signed in"

    private fun entry(
        message: String = "ordinary message",
        component: String = "Comp",
        data: Map<String, Any?>? = null,
        error: Throwable? = null,
    ) = LogEntry(
        timestamp = 0L,
        level = LogLevel.INFO,
        category = LogCategory.GENERAL,
        component = component,
        message = message,
        data = data,
        error = error,
    )

    private fun records(text: String): List<String> = text.removeSuffix("\n").split("\n")

    @Test
    fun `a newline in the message cannot start a second record`() {
        val line = BossLogger.renderFileLine(entry(message = "opened$forged"))
        assertEquals(1, records(line).size, line)
        assertTrue(line.endsWith("\n"))
        assertTrue(line.contains("root signed in"), "the text is kept, escaped, not dropped")
    }

    @Test
    fun `a newline in the component name cannot start a second record`() {
        val line = BossLogger.renderFileLine(entry(component = "Evil$forged"))
        assertEquals(1, records(line).size, line)
    }

    @Test
    fun `a newline in a data value cannot start a second record`() {
        val line = BossLogger.renderFileLine(entry(data = mapOf("url" to "https://example.test/$forged")))
        assertEquals(1, records(line).size, line)
    }

    @Test
    fun `a newline in a data key cannot start a second record`() {
        val line = BossLogger.renderFileLine(entry(data = mapOf("k$forged" to "v")))
        assertEquals(1, records(line).size, line)
    }

    @Test
    fun `a newline in the exception message stays inside the exception block`() {
        val line = BossLogger.renderFileLine(entry(error = IllegalStateException("boom$forged")))
        val extra = records(line).drop(1)
        assertTrue(extra.isNotEmpty(), "the exception block is still written")
        assertTrue(
            extra.all { it.startsWith("  ") },
            "every continuation line is indented exception detail, none is a forged record:\n$line",
        )
    }

    @Test
    fun `carriage returns and unicode line breaks are neutralised too`() {
        val forbidden = setOf('\r', '\n', nel, lineSeparator, paragraphSeparator, verticalTab, formFeed)
        val breakers = listOf("\r\n") + forbidden.map { it.toString() }
        for (breaker in breakers) {
            val line = BossLogger.renderFileLine(entry(message = "a${breaker}b"))
            assertFalse(line.removeSuffix("\n").any { it in forbidden }, line)
        }
    }

    @Test
    fun `terminal escapes and bidi overrides do not reach the file or the console`() {
        val hostile = "a$esc[2J$esc]0;title${bell}b${rlo}c"
        val rendered =
            listOf(
                BossLogger.renderFileLine(entry(message = hostile)),
                BossLogger.renderConsoleMessage(entry(message = hostile)),
            )
        for (text in rendered) {
            val body = text.removeSuffix("\n")
            assertFalse(body.any { it.code < 0x20 && it != '\t' }, "no C0 control survives: $body")
            assertFalse(body.contains(rlo), "no bidi override survives: $body")
            assertTrue(body.contains("\\u001b"), "the escape is shown, not lost: $body")
        }
    }

    @Test
    fun `the console message is one line as well`() {
        val text = BossLogger.renderConsoleMessage(entry(message = "x$forged", data = mapOf("k" to "v$forged")))
        assertFalse(text.contains('\n'), text)
    }

    @Test
    fun `ordinary text is written exactly as before`() {
        val text = "Signed in as user@example.test - naive cafe, emoji $smile, tab\there"
        val e = entry(message = text, data = mapOf("n" to 3))
        val file = BossLogger.renderFileLine(e)
        assertTrue(file.endsWith(" [INFO ] [GENERAL] Comp: $text | {n=3}\n"), file)
        assertEquals("[GENERAL] Comp: $text | {n=3}", BossLogger.renderConsoleMessage(e))
    }

    @Test
    fun `the recorded entry itself is not altered`() {
        val e = entry(message = "opened$forged")
        BossLogger.renderFileLine(e)
        assertEquals("opened$forged", e.message)
    }
}
