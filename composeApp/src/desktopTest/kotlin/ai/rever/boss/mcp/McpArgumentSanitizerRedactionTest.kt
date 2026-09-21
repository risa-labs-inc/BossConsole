package ai.rever.boss.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class McpArgumentSanitizerRedactionTest {
    @Test
    fun `authorization bearer header is fully redacted`() {
        val sanitized = McpArgumentSanitizer.sanitizeMessage("curl -H 'Authorization: Bearer abc123def456'")
        assertFalse(
            sanitized.contains("abc123def456"),
            "Bearer token must not leak in plaintext, got: $sanitized",
        )
        assertEquals("curl -H '[REDACTED]'", sanitized)
    }

    @Test
    fun `authorization basic and token headers are fully redacted`() {
        val basicSanitized = McpArgumentSanitizer.sanitizeMessage("curl -H 'Authorization: Basic abc123def456'")
        assertFalse(basicSanitized.contains("abc123def456"), "Basic token must be redacted")

        val tokenSanitized = McpArgumentSanitizer.sanitizeMessage("curl -H 'Authorization: Token abc123def456'")
        assertFalse(tokenSanitized.contains("abc123def456"), "Token key must be redacted")
    }

    @Test
    fun `standalone bearer token is redacted`() {
        val sanitized = McpArgumentSanitizer.sanitizeMessage("curl -H 'X-Header: Bearer abc123def456'")
        assertFalse(sanitized.contains("abc123def456"), "Standalone Bearer token must be redacted")
        assertEquals("curl -H 'X-Header: Bearer [REDACTED]'", sanitized)
    }

    @Test
    fun `sensitive assignments and tokens remain redacted`() {
        val flagSanitized = McpArgumentSanitizer.sanitizeMessage("psql --password=hunter2")
        assertFalse(flagSanitized.contains("hunter2"), "Password must be redacted")
        assertEquals("psql --[REDACTED]", flagSanitized)

        val slackSanitized = McpArgumentSanitizer.sanitizeMessage("curl -d token=xyz123 https://slack.com/api/x")
        assertFalse(slackSanitized.contains("xyz123"), "Slack token must be redacted")
        assertEquals("curl -d [REDACTED] https://slack.com/api/x", slackSanitized)
    }
}
