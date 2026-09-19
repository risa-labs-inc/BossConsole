package ai.rever.boss.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The #836 ordering leak, at the sensitive keywords the `authorizationHeader` rule does not
 * cover.
 *
 * `sensitiveAssignment` runs before the dedicated `bearer` rule, and its value used to stop at
 * the first space. On `token: Bearer abc123def456` it consumed the scheme word `Bearer` alone
 * and replaced it with `[REDACTED]`, leaving the credential sitting beside the label: the
 * `bearer` rule could never fire, because its `Bearer` had already been eaten. `Basic`,
 * `Token`, `Negotiate`, `Digest` and `NTLM` leaked the same way with no later rule to catch
 * them. The fix absorbs the scheme words together with the credential, so the value is masked
 * in one pass.
 *
 * A credential in a bare URI authority (no sensitive keyword in front of it) is #886's gap,
 * closed separately by `LogSanitizer.redactUrlUserInfo` in front of this pipeline.
 */
class McpArgumentSanitizerOrderLeakTest {
    @Suppress("MaxLineLength")
    private fun command(command: String): String = assertNotNull(McpArgumentSanitizer.sanitize(mapOf("command" to command))["command"])

    @Test
    fun `the issue 836 reproduction stays masked through the whole pipeline`() {
        val repro = "curl -H 'Authorization: Bearer abc123def456' https://api.internal"
        val once = command(repro)
        assertFalse(once.contains("abc123def456"), "credential survived the first pass: $once")
        assertTrue(once.contains("[REDACTED]"), once)
        assertTrue(
            once.contains("https://api.internal"),
            "the URL an operator must read was eaten: $once",
        )

        // The dialog and the ledger sanitize again; a second pass must not un-mask.
        val twice = McpArgumentSanitizer.sanitizeMessage(once)
        assertEquals(once, twice, "a second pass changed already-sanitized text: $twice")
        assertFalse(twice.contains("abc123def456"))
    }

    @Test
    fun `a scheme word is absorbed with the credential after a sensitive keyword`() {
        val leaks =
            listOf(
                "run --token: Bearer abc123def456" to "abc123def456",
                "curl -H 'X-Auth-Token: Bearer abc123def456' https://x" to "abc123def456",
                "deploy password=Basic YWRtaW46aHVudGVyMg==" to "YWRtaW46aHVudGVyMg==",
                "connect secret: Negotiate YIIZk3YGKw" to "YIIZk3YGKw",
                "auth credential: Token TokenSchemeSecret123" to "TokenSchemeSecret123",
                "upload api_key: Digest dGlnZXI6cGFzcw==" to "dGlnZXI6cGFzcw==",
                "backup api-key: NTLM TlRMTFNTZWNyZXQ=" to "TlRMTFNTZWNyZXQ=",
            )
        for ((raw, secret) in leaks) {
            val out = command(raw)
            assertFalse(out.contains(secret), "credential survived a scheme word: $raw -> $out")
            assertTrue(out.contains("[REDACTED]"), "nothing was masked in: $raw -> $out")
        }
    }

    @Test
    fun `a quoted credential behind a scheme word is masked whole`() {
        val single = command("run --token: Bearer 'abc def'")
        assertFalse(single.contains("abc"), "single-quoted credential leaked: $single")
        assertFalse(single.contains("def"), "single-quoted credential tail leaked: $single")

        val double = command("""run --token: Bearer "abc def" """)
        assertFalse(double.contains("abc"), "double-quoted credential leaked: $double")
        assertFalse(double.contains("def"), "double-quoted credential tail leaked: $double")
        assertTrue(single.contains("[REDACTED]") && double.contains("[REDACTED]"))
    }

    @Test
    fun `a bare scheme word is masked as the value itself`() {
        // Nothing follows the scheme, so the scheme word is the value and is masked as before.
        assertEquals("run --[REDACTED]", command("run --token: Bearer"))
    }

    @Test
    fun `a uri credential behind a keyword or scheme is masked whole`() {
        // A URI has no spaces, so the widened value takes the whole authority in one pass;
        // before the fix the scheme word was eaten and the full URI - password included - leaked.
        val underKey = command("psql 'secret: postgres://admin:hunter2@prod-db.internal/app'")
        assertFalse(underKey.contains("hunter2"), "uri password survived: $underKey")
        assertFalse(underKey.contains("admin:"), "uri userinfo survived: $underKey")
        assertTrue(underKey.contains("[REDACTED]"), underKey)

        val behindScheme = command("connect --token=Bearer postgres://svc:S3cr3t@cache:6379/0")
        assertFalse(behindScheme.contains("S3cr3t"), "scheme then uri leaked: $behindScheme")
        assertFalse(behindScheme.contains("svc:"), "uri userinfo survived: $behindScheme")
    }

    @Test
    fun `a uri scheme is not mistaken for an auth scheme`() {
        // No sensitive keyword, no auth scheme, no known shape: #886's surface. Its fix prepends
        // LogSanitizer.redactUrlUserInfo to this pipeline; whatever lands there keeps the host
        // and path readable, and the scheme absorption here must never eat a URI scheme.
        val out = command("psql postgres://admin:hunter2@prod-db.internal/app")
        assertTrue(out.contains("prod-db.internal"), "host and path must stay readable: $out")
        assertTrue(out.contains("postgres://"), "the uri scheme is not an auth scheme: $out")
    }

    @Test
    fun `several credentials in one string are each masked`() {
        val out =
            command(
                "run --token: Bearer abc123def456 --secret: xyz789 " +
                    "-H 'Authorization: Basic YWRtaW46aHVudGVyMg==' ghp_AbCdEfGh1234",
            )
        assertFalse(out.contains("abc123def456"), "bearer credential survived: $out")
        assertFalse(out.contains("xyz789"), "second credential survived: $out")
        assertFalse(out.contains("YWRtaW46aHVudGVyMg=="), "basic credential survived: $out")
        assertFalse(out.contains("ghp_AbCdEfGh1234"), "shape credential survived: $out")
        assertTrue(out.contains("[REDACTED]"), out)
    }

    @Test
    fun `mask-like literals do not confuse the order`() {
        // A literal *** glued to a credential must be masked with it, not split from it.
        val glued = command("call Bearer abc***def456")
        assertFalse(glued.contains("abc"), "credential fragment before a mask survived: $glued")
        assertFalse(glued.contains("def456"), "credential fragment after a mask survived: $glued")

        val assigned = command("run --token: ***abc123def456")
        assertFalse(assigned.contains("abc123def456"), "mask-glued credential survived: $assigned")

        // A value that is already only a mask collapses to one [REDACTED], not nested masks.
        assertEquals("run --[REDACTED]", command("run --token: ***"))
    }

    @Test
    fun `a second pass does not unmask or double-mask`() {
        val keywordScheme = command("run --token: Bearer abc123def456")
        assertEquals(keywordScheme, McpArgumentSanitizer.sanitizeMessage(keywordScheme))

        val standalone = McpArgumentSanitizer.sanitizeMessage("call Bearer abc123def456")
        assertEquals(standalone, McpArgumentSanitizer.sanitizeMessage(standalone))
        assertTrue(standalone.contains("Bearer [REDACTED]"), standalone)
    }

    @Test
    fun `prose without credentials is unchanged`() {
        assertEquals(
            "psql --host=prod-db.internal --port=5432 reports",
            command("psql --host=prod-db.internal --port=5432 reports"),
        )
        assertEquals(
            "fluent in Basic vocabulary, no keyword in sight",
            command("fluent in Basic vocabulary, no keyword in sight"),
        )
        assertEquals("hint [REDACTED] value list", command("hint password: plain value list"))
        assertEquals("echo the negotiation continues", command("echo the negotiation continues"))
        assertEquals(
            "git clone https://github.com/org/private.git",
            command("git clone https://github.com/org/private.git"),
        )
    }
}
