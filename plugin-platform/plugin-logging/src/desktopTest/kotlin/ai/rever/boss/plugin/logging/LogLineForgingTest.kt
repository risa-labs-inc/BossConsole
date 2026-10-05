package ai.rever.boss.plugin.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One log record is one line (or an indented exception block starting with a timestamp line).
 *
 * A record is line-oriented, and everything that reads the log file (tail, grep, a log viewer,
 * whatever ships it off the machine) treats it as such. The message, the component, data values,
 * and exception messages all carry text the app did not author: a URL, a file name, a plugin
 * surface id, or a server error body. A newline in any of them starts a second un-indented line
 * that looks exactly like a record the logger wrote (CWE-117), allowing an unprivileged caller to
 * forge kernel log records. Terminal escapes in the same strings act on whoever reads the console,
 * and bidi overrides change what a line appears to say without changing its bytes.
 *
 * Control characters are built from code points on purpose: a formatter that turns an escape in a
 * string literal into raw characters would put invisible bytes in this source.
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
    private val zeroWidthSpace = 0x200b.toChar()
    private val smile = String(Character.toChars(0x1F600))

    private val forged = "\n2026-01-01 00:00:00.000 [ERROR] [AUTH] Auth: root signed in"

    private fun entry(
        message: String = "ordinary message",
        component: String = "Comp",
        data: Map<String, Any?>? = null,
        error: Throwable? = null,
    ): LogEntry =
        LogEntry(
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
        val line = BossLogger.renderFileLine(entry(data = mapOf("surfaceId" to "my-surface$forged")))
        assertEquals(1, records(line).size, line)
    }

    @Test
    fun `a newline in a data key cannot start a second record`() {
        val line = BossLogger.renderFileLine(entry(data = mapOf("k$forged" to "v")))
        assertEquals(1, records(line).size, line)
    }

    @Test
    fun `a newline in the exception message stays inside the exception block in file output`() {
        val line = BossLogger.renderFileLine(entry(error = IllegalStateException("boom$forged")))
        val extra = records(line).drop(1)
        assertTrue(extra.isNotEmpty(), "the exception block is still written")
        assertTrue(
            extra.all { it.startsWith("  ") },
            "every continuation line is indented exception detail, none is a forged record:\n$line",
        )
    }

    @Test
    fun `a multi-line exception message is sanitized on the console path`() {
        val raw = IllegalStateException("surface-error$forged")
        val sanitized = BossLogger.sanitizeThrowable(raw)
        assertNotNull(sanitized)
        assertFalse(sanitized.toString().contains('\n'), "sanitized throwable toString() has no newline")
        assertFalse(sanitized.message!!.contains('\n'), "sanitized message has no newline")
        assertTrue(sanitized.message!!.contains("\\n2026-01-01"), "newline is escaped")
    }

    @Test
    fun `a multi-line cause is sanitized on the console path`() {
        val cause = IllegalArgumentException("cause-break$forged")
        val root = RuntimeException("outer", cause)
        val sanitized = BossLogger.sanitizeThrowable(root)
        assertNotNull(sanitized)
        val sanitizedCause = sanitized.cause
        assertNotNull(sanitizedCause)
        assertFalse(sanitizedCause.toString().contains('\n'), "sanitized cause toString() has no newline")
        assertFalse(sanitizedCause.message!!.contains('\n'), "sanitized cause message has no newline")
    }

    @Test
    fun `carriage returns and CRLF are escaped deterministically`() {
        assertEquals("alpha\\r\\nbeta", LogLineText.neutralize("alpha\r\nbeta"))
        assertEquals("alpha\\rbeta", LogLineText.neutralize("alpha\rbeta"))
        assertEquals("alpha\\nbeta", LogLineText.neutralize("alpha\nbeta"))

        val forbidden = setOf('\r', '\n', nel, lineSeparator, paragraphSeparator, verticalTab, formFeed)
        val breakers = listOf("\r\n") + forbidden.map { it.toString() }
        for (breaker in breakers) {
            val line = BossLogger.renderFileLine(entry(message = "a${breaker}b"))
            assertFalse(line.removeSuffix("\n").any { it in forbidden }, line)
        }
    }

    @Test
    fun `literal backslashes remain distinct from escaped controls`() {
        assertEquals("alpha\\\\n", LogLineText.neutralize("alpha\\n"))
        assertEquals("alpha\\n", LogLineText.neutralize("alpha" + 0x0a.toChar()))
        assertEquals("\\\\u0000", LogLineText.neutralize("\\u0000"))
    }

    @Test
    fun `hidden characters and zero-width spaces are escaped`() {
        val cases =
            mapOf(
                0x00 to "\\u0000",
                0x7f to "\\u007f",
                0x85 to "\\u0085",
                0x00ad to "\\u00ad",
                0x200b to "\\u200b",
                0x2028 to "\\u2028",
                0x2029 to "\\u2029",
                0x202e to "\\u202e",
                0xfeff to "\\ufeff",
            )
        cases.forEach { (codePoint, expected) ->
            assertEquals(expected, LogLineText.neutralize(String(Character.toChars(codePoint))))
        }
    }

    @Test
    fun `supplementary format characters are escaped without damaging visible emoji`() {
        val languageTag = String(Character.toChars(0xE0001))
        val tagSpace = String(Character.toChars(0xE0020))
        val input = "status $smile $languageTag tag $tagSpace end"

        val neutralized = LogLineText.neutralize(input)
        assertTrue(neutralized.contains(smile), "visible emoji is preserved untouched")
        assertFalse(neutralized.contains(languageTag), "language tag format character is escaped")
        assertFalse(neutralized.contains(tagSpace), "tag space format character is escaped")
        assertTrue(neutralized.contains("\\udb40\\udc01"), "supplementary character escaped as UTF-16 surrogate pair")
    }

    @Test
    fun `terminal escapes and bidi overrides do not reach the file or the console`() {
        val hostile = "a$esc[2J$esc]0;title${bell}b${rlo}c$zeroWidthSpace"
        val rendered =
            listOf(
                BossLogger.renderFileLine(entry(message = hostile)),
                BossLogger.renderConsoleMessage(entry(message = hostile)),
            )
        for (text in rendered) {
            val body = text.removeSuffix("\n")
            assertFalse(body.any { it.code < 0x20 && it != '\t' }, "no C0 control survives: $body")
            assertFalse(body.contains(rlo), "no bidi override survives: $body")
            assertFalse(body.contains(zeroWidthSpace), "no zero-width space survives: $body")
            assertTrue(body.contains("\\u001b"), "the escape is shown, not lost: $body")
        }
    }

    @Test
    fun `the console message is strictly one line`() {
        val text = BossLogger.renderConsoleMessage(entry(message = "x$forged", data = mapOf("k" to "v$forged")))
        assertFalse(text.contains('\n'), text)
        assertFalse(text.contains('\r'), text)
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

    @Test
    fun `null exception message renders without crashing`() {
        val e = entry(error = NullPointerException(null as String?))
        val fileLine = BossLogger.renderFileLine(e)
        assertTrue(fileLine.contains("Exception: null"), fileLine)

        val sanitized = BossLogger.sanitizeThrowable(NullPointerException(null as String?))
        assertNotNull(sanitized)
        assertNull(sanitized.message)
        assertEquals("java.lang.NullPointerException", sanitized.toString())
    }
}
