package ai.rever.boss.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The shapes BossConsole#886 measured reaching the approval dialog and the ledger unmasked: a
 * credential in a URI authority, curl basic auth, an AWS access key id and a PEM private key.
 * None is an assignment, a sensitive key or a vendor-prefixed token, so the rules that existed
 * did not see them. The host, port and path of every URI stay readable: the operator approves a
 * command by reading where it goes.
 */
class McpArgumentSanitizerUriCredentialTest {
    @Suppress("MaxLineLength")
    private fun command(command: String): String = assertNotNull(McpArgumentSanitizer.sanitize(mapOf("command" to command))["command"])

    @Test
    fun `a password in a database uri authority is masked and the host survives`() {
        val cases =
            mapOf(
                "psql postgres://admin:hunter2@prod-db.example.invalid/app" to "hunter2",
                "mongosh mongodb+srv://svc:S3cr3tPass@cluster.example.invalid/db" to "S3cr3tPass",
                "redis-cli -u redis://default:r3disPass@cache.example.invalid:6379" to "r3disPass",
                "git clone https://nitin:hunter2@git.example.invalid/org/private.git" to "hunter2",
            )
        for ((input, secret) in cases) {
            val out = command(input)
            assertFalse(out.contains(secret), "credential survived: $out")
            assertTrue(out.contains("[REDACTED]@"), "userinfo should be replaced, not the URL: $out")
            assertTrue(out.contains("example.invalid"), "the host must stay readable: $out")
        }
    }

    @Test
    fun `curl basic auth is masked whether given as a flag value or with equals`() {
        assertEquals(
            "curl -u [REDACTED] https://api.example.invalid/health",
            command("curl -u admin:hunter2 https://api.example.invalid/health"),
        )
        assertEquals("curl --user=[REDACTED] https://x", command("curl --user=admin:hunter2 https://x"))
        assertEquals("curl -u [REDACTED] https://x", command("curl -u 'admin:hunter two' https://x"))
    }

    @Test
    fun `a dash u that is not basic auth is left alone`() {
        // Ordinary flags an operator needs to read; none carries a user:password value.
        assertEquals("git push -u origin main", command("git push -u origin main"))
        assertEquals("python -u script.py", command("python -u script.py"))
        assertEquals("curl -u admin https://x", command("curl -u admin https://x"))
        // A URL after -u is a URI flag; its userinfo is handled by the pass before, host intact.
        assertEquals(
            "redis-cli -u redis://[REDACTED]@cache.example.invalid:6379",
            command("redis-cli -u redis://default:r3disPass@cache.example.invalid:6379"),
        )
    }

    @Test
    fun `an aws access key id is masked and look-alikes are not`() {
        assertEquals("aws configure set key [REDACTED]", command("aws configure set key AKIAIOSFODNN7EXAMPLE"))
        assertFalse(command("export AWS_ACCESS_KEY_ID=ASIAIOSFODNN7EXAMPLE").contains("ASIAIOSFODNN7EXAMPLE"))
        // Too short, lower case, or embedded in a longer token: not the documented shape.
        assertEquals("echo AKIAIOSFODNN7", command("echo AKIAIOSFODNN7"))
        assertEquals("echo akiaiosfodnn7example", command("echo akiaiosfodnn7example"))
        assertEquals("echo XAKIAIOSFODNN7EXAMPLEX", command("echo XAKIAIOSFODNN7EXAMPLEX"))
    }

    @Test
    fun `a pem private key body is masked and the rest of the command survives`() {
        assertEquals(
            "echo '[REDACTED]' > id_rsa",
            command("echo '-----BEGIN RSA PRIVATE KEY-----MIIEowIBAAKCAQEA' > id_rsa"),
        )
        val block = "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBg\nkqhkiG9w0BAQEF\n-----END PRIVATE KEY-----"
        assertEquals("printf '[REDACTED]' > key.pem", command("printf '$block' > key.pem"))
        // A public key or certificate is not a credential.
        assertEquals(
            "cat '-----BEGIN PUBLIC KEY-----MFkw' > pub",
            command("cat '-----BEGIN PUBLIC KEY-----MFkw' > pub"),
        )
    }

    @Test
    fun `an at sign outside a uri authority is not a userinfo delimiter`() {
        assertEquals("git log --author=alice@example.com", command("git log --author=alice@example.com"))
        assertEquals("open https://example.com/@handle", command("open https://example.com/@handle"))
    }

    @Test
    fun `the existing rules still apply after the userinfo pass`() {
        assertEquals("psql --[REDACTED]", command("psql --password=hunter2"))
        assertFalse(command("curl -H 'Authorization: Bearer abc123def456' https://x").contains("abc123def456"))
    }
}
