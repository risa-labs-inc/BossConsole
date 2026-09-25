package ai.rever.boss.mcp

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
}
