package ai.rever.boss.mcp.secrets

import ai.rever.boss.plugin.api.McpToolResult
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The scrubber's contract, protected forms and unprotected ones alike.
 *
 * The unprotected cases are tests, not just documentation: a reader of this file sees exactly
 * where the defense-in-depth step ends, which is the point of calling it that.
 */
class McpResultScrubberTest {
    private val id = "6f1d2c3e-4b5a-4c6d-8e7f-90a1b2c3d4e5"
    private val ref = SecretReference(id, SecretField.PASSWORD)
    private val token = ref.token

    @Test
    fun `the raw value is replaced everywhere it appears`() {
        val scrubber = McpResultScrubber(mapOf(ref to "hunter22!"))
        assertEquals("a $token b $token", scrubber.scrub("a hunter22! b hunter22!"))
    }

    @Test
    fun `json-escaped and url-encoded forms are replaced`() {
        val value = "p\"a s/s\\w?rd&x=1"
        val scrubber = McpResultScrubber(mapOf(ref to value))
        val echoed =
            listOf(
                McpResultScrubber.jsonEscape(value),
                URLEncoder.encode(value, Charsets.UTF_8),
                URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20"),
            ).joinToString("|")
        assertEquals("$token|$token|$token", scrubber.scrub(echoed))
    }

    @Test
    fun `an encoding that contains the raw form is replaced as one token`() {
        // The json form of a value with a quote contains the value's prefix; longest-first
        // replacement keeps the token whole instead of leaving an escaped quote behind.
        val value = "abcdefgh\"ij"
        val scrubber = McpResultScrubber(mapOf(ref to value))
        assertEquals(token, scrubber.scrub(McpResultScrubber.jsonEscape(value)))
    }

    @Test
    fun `overlapping values are replaced longest first`() {
        val long = SecretReference(id, SecretField.NOTES)
        val scrubber = McpResultScrubber(mapOf(ref to "abcdefgh", long to "abcdefghijkl"))
        assertEquals("${long.token} and $token", scrubber.scrub("abcdefghijkl and abcdefgh"))
    }

    @Test
    fun `values shorter than the floor are not scrubbed`() {
        val scrubber = McpResultScrubber(mapOf(ref to "1234567"))
        val text = "pin 1234567 here"
        assertSame(text, scrubber.scrub(text))
    }

    @Test
    fun `an empty value map is a no-op that returns the same result instance`() {
        val result = McpToolResult("unchanged")
        assertSame(result, McpResultScrubber(emptyMap()).apply(result))
    }

    @Test
    fun `an untouched result keeps its instance and its error flag`() {
        val result = McpToolResult("boom", isError = true)
        val scrubbed = McpResultScrubber(mapOf(ref to "hunter22!")).apply(result)
        assertSame(result, scrubbed)
        val changed =
            McpResultScrubber(mapOf(ref to "hunter22!")).apply(McpToolResult("boom hunter22!", isError = true))
        assertTrue(changed.isError)
        assertEquals("boom $token", changed.text)
    }

    @Test
    fun `unicode values are replaced whole`() {
        val value = "päss🔐ẃord!"
        val scrubber = McpResultScrubber(mapOf(ref to value))
        assertEquals("<$token>", scrubber.scrub("<$value>"))
    }

    @Test
    fun `not protected - a base64 encoding of the value`() {
        val value = "hunter22!hunter22!"
        val encoded = Base64.getEncoder().encodeToString(value.toByteArray())
        assertEquals(encoded, McpResultScrubber(mapOf(ref to value)).scrub(encoded))
    }

    @Test
    fun `not protected - a hash of the value`() {
        val value = "hunter22!hunter22!"
        val digest =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(digest, McpResultScrubber(mapOf(ref to value)).scrub(digest))
    }

    @Test
    fun `not protected - a case change of the value`() {
        val value = "HunterTwo!"
        assertEquals(value.lowercase(), McpResultScrubber(mapOf(ref to value)).scrub(value.lowercase()))
    }

    @Test
    fun `not protected - a double percent-encoding of the value`() {
        val value = "a b&c=d/e"
        val twice = URLEncoder.encode(URLEncoder.encode(value, Charsets.UTF_8), Charsets.UTF_8)
        val out = McpResultScrubber(mapOf(ref to value)).scrub(twice)
        assertFalse(out.contains(token))
    }

    @Test
    fun `json escaping matches what a json encoder writes`() {
        // The control characters are built by code point rather than written as escapes, because
        // the formatter rewrites a \u escape in a char or string literal as the raw byte.
        val input = "a\"b\\c\nd\te" + 0x01.toChar() + 0x0C.toChar()
        assertEquals("a\\\"b\\\\c\\nd\\te\\u0001\\f", McpResultScrubber.jsonEscape(input))
    }
}
