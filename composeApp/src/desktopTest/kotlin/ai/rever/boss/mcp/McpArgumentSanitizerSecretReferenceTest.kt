package ai.rever.boss.mcp

import ai.rever.boss.mcp.secrets.SecretField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A `{{secret:<id>}}` reference must survive sanitization (it is inert and it is the one fact the
 * ledger exists to show for such a call), while a real `secret: value` assignment must still be
 * redacted exactly as before.
 */
class McpArgumentSanitizerSecretReferenceTest {
    private val id = "6f1d2c3e-4b5a-4c6d-8e7f-90a1b2c3d4e5"

    @Test
    fun `a secret reference stays legible in sanitized arguments`() {
        val content = "TOKEN={{secret:$id}}\nUSER={{secret:$id.username}}"
        val out = McpArgumentSanitizer.sanitize(mapOf("content" to content))
        assertEquals("TOKEN={{secret:$id}}\nUSER={{secret:$id.username}}", out["content"])
    }

    @Test
    fun `a secret assignment that is not a reference is still redacted`() {
        val out = McpArgumentSanitizer.sanitizeMessage("secret: hunter2 and secret=hunter3 and {{secret:$id}}")
        assertFalse(out.contains("hunter2"), out)
        assertFalse(out.contains("hunter3"), out)
        assertTrue(out.contains("{{secret:$id}}"), out)
    }

    @Test
    fun `a malformed reference cannot exempt plaintext from assignment redaction`() {
        val out = McpArgumentSanitizer.sanitizeMessage("TOKEN={{secret:ghp_realtokenhere}}")
        assertFalse(out.contains("ghp_realtokenhere"), out)
    }

    @Test
    fun `template-shaped sensitive assignments remain redacted`() {
        val out = McpArgumentSanitizer.sanitizeMessage("{{password: hunter2}} {{TOKEN=hunter3}}")
        assertFalse(out.contains("hunter2"), out)
        assertFalse(out.contains("hunter3"), out)
    }

    @Test
    fun `a sensitive key name still redacts the whole value, reference or not`() {
        // Key-name redaction is unchanged: a key called "token" is redacted regardless of content.
        val out = McpArgumentSanitizer.sanitize(mapOf("token" to "{{secret:$id}}"))
        assertEquals("[REDACTED]", out["token"])
    }

    @Test
    fun `a malformed reference with trailing characters is fully redacted`() {
        val out = McpArgumentSanitizer.sanitizeMessage("note={{secret:plaincredential}}tail")
        assertFalse(out.contains("plaincredential"), out)
        assertTrue(out.contains("{{secret:[REDACTED]}}tail"), out)
    }

    @Test
    fun `unterminated malformed references are redacted`() {
        val unterminated =
            listOf(
                "{{secret:hunter2}",
                "{{secret:hunter2}tail",
                "{{secret:{hunter2}}}",
                "pre{{secret:hunter2}post",
            )
        for (candidate in unterminated) {
            val out = McpArgumentSanitizer.sanitizeMessage("arg=$candidate")
            assertFalse(out.contains("hunter2"), "Failed for $candidate: $out")
        }
    }

    @Test
    fun `adjacent valid secret references remain legible`() {
        val other = "00000000-0000-4000-8000-000000000001"
        val third = "22222222-2222-4222-8222-222222222222"
        val out =
            McpArgumentSanitizer.sanitizeMessage(
                "TOKEN={{secret:$id.password}}{{secret:$other.username}}{{secret:$third}}",
            )
        assertEquals("TOKEN={{secret:$id.password}}{{secret:$other.username}}{{secret:$third}}", out)

        val uppercaseField = McpArgumentSanitizer.sanitizeMessage("TOKEN={{secret:$id.PASSWORD}}")
        assertFalse(uppercaseField.contains("PASSWORD"), uppercaseField)
    }

    @Test
    fun `sanitizer wireName alternations match SecretField entries exactly`() {
        val expected =
            SecretField.entries
                .joinToString("|") { it.wireName }
        assertEquals(expected, McpArgumentSanitizer.validSecretReferenceFields)
    }

    @Test
    fun `malformed references with nested and brace-led bodies are redacted`() {
        val candidates =
            listOf(
                "{{secret:{hunter2",
                "{{secret:{{hunter2}}}}",
                "{{SECRET:{{hunter2}}}}",
                "{{secret:abc{def",
            )
        for (candidate in candidates) {
            val out = McpArgumentSanitizer.sanitizeMessage("arg=$candidate")
            assertFalse(out.contains("hunter2"), "Failed for $candidate: $out")
            assertFalse(out.contains("def"), "Failed for $candidate: $out")
            assertTrue(out.contains("{{secret:[REDACTED]}}"), "Expected redacted marker for $candidate: $out")
        }
    }

    @Test
    fun `valid reference following malformed reference remains legible`() {
        val out = McpArgumentSanitizer.sanitizeMessage("{{secret:bad}}{{secret:$id}}")
        assertEquals("{{secret:[REDACTED]}}{{secret:$id}}", out)
    }

    @Test
    fun `a malformed reference with no keyword prefix is redacted`() {
        val out = McpArgumentSanitizer.sanitizeMessage("note={{secret:hunter2}} and {{secret:hunter3}}")
        assertFalse(out.contains("hunter2"), out)
        assertFalse(out.contains("hunter3"), out)
        assertTrue(out.contains("{{secret:[REDACTED]}}"), out)
    }

    @Test
    fun `case-insensitive valid reference prefix is preserved while malformed is redacted`() {
        val out = McpArgumentSanitizer.sanitizeMessage("note={{SECRET:$id}} and {{SECRET:hunter2}}")
        assertTrue(out.contains("{{SECRET:$id}}"), out)
        assertFalse(out.contains("hunter2"), out)
        assertTrue(out.contains("{{secret:[REDACTED]}}"), out)
    }
}
