package ai.rever.boss.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * An `Authorization: <scheme> <credential>` header must be masked whole.
 *
 * Before the auth-scheme alternative, `sensitiveAssignment`'s `[^\s&,;}]+` stopped at the first
 * space, so the value it captured was the scheme word itself and the credential survived as
 * `[REDACTED] <token>`. Sanitized arguments reach the approval dialog AND `McpOperationLedger`,
 * which appends them to disk, so a surviving credential is written out in plaintext.
 */
class McpArgumentSanitizerAuthSchemeTest {
    @Suppress("MaxLineLength")
    private fun command(command: String): String = assertNotNull(McpArgumentSanitizer.sanitize(mapOf("command" to command))["command"])

    @Test
    fun `bearer credential in an Authorization header is masked, not just its label`() {
        val out = command("curl -H 'Authorization: Bearer abc123def456' https://api.internal")
        assertFalse(out.contains("abc123def456"), "credential survived sanitisation: $out")
        assertTrue(out.contains("[REDACTED]"), out)
        assertTrue(out.contains("https://api.internal"), out)
    }

    @Test
    fun `basic token and negotiate schemes are masked too`() {
        assertFalse(command("curl -H 'Authorization: Basic YWRtaW46aHVudGVyMg=='").contains("YWRtaW46aHVudGVyMg=="))
        assertFalse(command("curl -H 'Authorization: Token TokenSchemeSecret123'").contains("TokenSchemeSecret123"))
        assertFalse(command("curl -H 'Authorization: Negotiate YIIZk3YGKw'").contains("YIIZk3YGKw"))
        assertFalse(command("curl -H 'Authorization: Digest DigestSecret123'").contains("DigestSecret123"))
        assertFalse(command("curl -H 'Authorization: NTLM NtlmSecret123'").contains("NtlmSecret123"))
    }

    @Test
    fun `a standalone bearer header without a sensitive key still uses the bearer rule`() {
        // "X-Auth" is not in the sensitive-keyword set, so this must still be caught by [bearer].
        val out = command("curl -H 'X-Auth: Bearer standalone987'")
        assertFalse(out.contains("standalone987"), "standalone bearer survived: $out")
        assertTrue(out.contains("Bearer [REDACTED]"), "expected the bearer rule to fire: $out")
    }

    @Test
    fun `existing assignment redaction is unchanged`() {
        assertEquals("psql --[REDACTED]", command("psql --password=hunter2"))
        val githubToken = "ghp_AbCdEfGhIjKlMnOpQrStUvWxYz0123"
        assertFalse(command("export GH=$githubToken").contains(githubToken))
        assertFalse(command("curl -d token=xoxb-2444-55667788-aBcDeFgHiJkLmNoP https://x").contains("xoxb-2444"))
        assertFalse(command("curl -H 'X: eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abc'").contains("eyJzdWIiOiIxIn0"))
    }

    @Test
    fun `ordinary sensitive assignments still use their original value boundary`() {
        assertEquals("note [REDACTED] settings remain", command("note secret: Basic settings remain"))
    }

    @Test
    fun `authorization label does not consume command text on the next line`() {
        val raw = "authorization:\nrm -rf /important/data"
        assertEquals(raw, command(raw))
    }

    @Test
    fun `sensitive assignment nested in authorization is fully masked`() {
        assertEquals("[REDACTED]", command("authorization: invalid token: abc123"))
        assertEquals("[REDACTED]", command("authorization: none password : hunter2"))
    }

    @Test
    fun `authorization value without a scheme is masked`() {
        assertEquals("curl -H '[REDACTED] https://x", command("curl -H 'Authorization: abc123' https://x"))
    }

    @Test
    fun `a compound scheme chain is masked whole`() {
        // The generic scheme group iterates once per scheme word before consuming the credential.
        val out = command("curl -H 'Authorization: Bearer Token abc123secret'")
        assertFalse(out.contains("abc123secret"), "compound scheme leaked: $out")
        assertTrue(out.contains("[REDACTED]"), out)
    }

    @Test
    fun `a quoted credential is masked for both quoting styles`() {
        // Assert on each HALF, not the whole string: the bare class used to take `'abc` and leave
        // ` def'`, which a contains("abc def") check passes while the credential is still readable.
        val single = command("""curl -H "Authorization: Bearer 'abc def'" """)
        assertFalse(single.contains("abc"), "single-quoted credential leaked: $single")
        assertFalse(single.contains("def"), "single-quoted credential tail leaked: $single")
        assertTrue(single.contains("[REDACTED]"), single)

        val double = command("""curl -H 'Authorization: Bearer "abc def"' """)
        assertFalse(double.contains("abc"), "double-quoted credential leaked: $double")
        assertFalse(double.contains("def"), "double-quoted credential tail leaked: $double")
        assertTrue(double.contains("[REDACTED]"), double)
    }
}
