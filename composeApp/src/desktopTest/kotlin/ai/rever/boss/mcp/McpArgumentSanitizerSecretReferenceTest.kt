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
    fun `every field a reference can name is kept alone and redacted when glued, whatever the enum grows to`() {
        // Iterates the enum the parser resolves against, so a field added there is covered here
        // without anyone remembering this test: the sanitizer's own pattern is built from the same
        // entries, and this pins that it keeps being.
        // Unmasked, a field would read `[REDACTED]}}` alone and leak `hunter2` glued after it.
        for (field in SecretField.entries) {
            val reference = "{{secret:$id.${field.wireName}}}"
            val sanitize = McpArgumentSanitizer::sanitizeMessage
            assertEquals("TOKEN=$reference", sanitize("TOKEN=$reference"), field.name)
            assertEquals("[REDACTED]", sanitize("TOKEN=${reference}hunter2"), field.name)
            assertEquals("[REDACTED]", sanitize("TOKEN=hunter2$reference"), field.name)
        }
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
    fun `the doc's examples of a rule taking a reference along with its value hold`() {
        // docs/MCP_SECRET_REFERENCES.md ("What the ledger records") gives these as examples of a
        // reference recorded as [REDACTED]. That paragraph was wrong twice by claiming a closed
        // set; this keeps the examples it does give true. A rule that learns to step around a
        // mask fails here, and the doc should lose that example with it.
        val reference = "{{secret:$id}}"
        val redacted =
            listOf(
                """{"token":"$reference"}""",
                "deploy --token $reference",
                "curl -u deploy:$reference https://registry.example",
                "curl --cookie $reference https://registry.example",
                "Authorization: Bearer $reference",
                "TOKEN=$reference{{secret:$id.username}}",
                "TOKEN=${reference}hunter2",
            )
        for (text in redacted) {
            val out = McpArgumentSanitizer.sanitizeMessage(text)
            assertTrue(out.contains("[REDACTED]") && !out.contains(reference), "$text -> $out")
        }
        // And the ones it gives as staying legible: back to back after a key that is not
        // sensitive, nothing runs into the mask.
        for (text in listOf("TOKEN=$reference", "deploy --token=$reference", "A=$reference{{secret:$id.username}}")) {
            assertEquals(text, McpArgumentSanitizer.sanitizeMessage(text))
        }
    }

    @Test
    fun `plaintext glued after a reference is redacted with it`() {
        val out = McpArgumentSanitizer.sanitizeMessage("TOKEN={{secret:$id}}hunter2 next")
        assertFalse(out.contains("hunter2"), out)
        assertTrue(out.endsWith(" next"), out)
    }

    @Test
    fun `plaintext glued before a reference is redacted with it`() {
        val out = McpArgumentSanitizer.sanitizeMessage("TOKEN=hunter2{{secret:$id}}")
        assertFalse(out.contains("hunter2"), out)
    }

    @Test
    fun `a malformed reference holding a pasted value is redacted`() {
        val out = McpArgumentSanitizer.sanitizeMessage("Malformed secret reference {{secret:hunter2}}: not an id")
        assertFalse(out.contains("hunter2"), out)
    }

    @Test
    fun `input cannot name a mask to make a reference appear`() {
        // One real reference, plus a forged mask naming it: only the real one may come back.
        val out = McpArgumentSanitizer.sanitizeMessage("a \uE0000\uE001 b {{secret:$id}}")
        assertEquals(1, Regex(Regex.escape("{{secret:$id}}")).findAll(out).count(), out)
    }

    @Test
    fun `several references each come back in place`() {
        val other = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        val text = "A={{secret:$id}} B={{secret:$other.username}} C={{secret:$id.notes}}"
        assertEquals(text, McpArgumentSanitizer.sanitizeMessage(text))
    }
}
