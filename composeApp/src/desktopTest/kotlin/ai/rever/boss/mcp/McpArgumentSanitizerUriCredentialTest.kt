package ai.rever.boss.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Hosts here are RFC 2606 reserved (`.invalid`), deliberately. A fixture shaped like a real
 * provider host (`cluster0.mongodb.net`) trips GitHub secret scanning on every clone and fork of
 * this repository, even though the credential is fabricated.
 *
 * A credential inside a URI authority, or after a basic-auth flag, carries no sensitive key name,
 * no assignment and no recognisable prefix, so before [McpArgumentSanitizer.uriUserInfo] no rule
 * here saw it. Sanitized arguments reach the approval dialog AND [McpOperationLedger], which
 * appends them to disk, so a surviving credential is written out in plaintext.
 */
class McpArgumentSanitizerUriCredentialTest {
    @Suppress("MaxLineLength")
    private fun command(command: String): String = assertNotNull(McpArgumentSanitizer.sanitize(mapOf("command" to command))["command"])

    @Test
    fun `a password in a database URI is masked and the host stays readable`() {
        val out = command("psql postgres://admin:hunter2@prod-db.example.invalid/app")
        assertFalse(out.contains("hunter2"), "credential survived: $out")
        assertTrue(out.contains("prod-db.example.invalid"), "host should stay readable: $out")
        // The whole userinfo goes, user included, matching the logging path merged in #640.
        assertFalse(out.contains("admin"), "user should be masked with the password: $out")
    }

    @Test
    fun `every common URI scheme is covered`() {
        assertFalse(command("mongosh mongodb+srv://svc:S3cr3tPass@cluster.example.invalid/db").contains("S3cr3tPass"))
        assertFalse(command("redis-cli -u redis://default:r3disPass@cache.example.invalid:6379").contains("r3disPass"))
        assertFalse(command("git clone https://nitin:hunter2@git.example.invalid/org/private.git").contains("hunter2"))
        assertFalse(command("mysql mysql://root:rootPass@db.example.invalid:3306/app").contains("rootPass"))
    }

    @Test
    fun `a host and port with no credentials is untouched`() {
        // The trap: a naive `:.*@` reads `cache` as a user and `6379` as a password.
        val raw = "redis-cli -u redis://cache.example.invalid:6379"
        assertEquals(raw, command(raw))
    }

    @Test
    fun `an ordinary URL with no credentials is untouched`() {
        val raw = "curl https://api.example.invalid/v1/health"
        assertEquals(raw, command(raw))
        val withPath = "git clone https://git.example.invalid/org/public.git"
        assertEquals(withPath, command(withPath))
    }

    @Test
    fun `a later path segment is not mistaken for userinfo`() {
        val raw = "curl https://api.example.invalid/a:b@c/d"
        assertEquals(raw, command(raw))
    }

    @Test
    fun `a password containing an at sign is removed whole`() {
        // The LAST @ in the authority is the delimiter. Stopping at the first one leaves the
        // tail of the password (`ss@host`) in the ledger, which a regex here originally did.
        val out = command("psql postgres://user:p@ss@db.example.invalid/app")
        assertFalse(out.contains("p@ss"), "credential survived: $out")
        assertFalse(out.contains("ss@"), "credential tail survived: $out")
        assertTrue(out.contains("db.example.invalid"), "host should stay readable: $out")
    }

    @Test
    fun `basic auth flags are masked`() {
        val short = command("curl -u admin:hunter2 https://api.example.invalid/health")
        assertFalse(short.contains("hunter2"), "short flag leaked: $short")
        assertTrue(short.contains("https://api.example.invalid/health"), short)

        val long = command("curl --user admin:hunter2 https://api.example.invalid/health")
        assertFalse(long.contains("hunter2"), "long flag leaked: $long")
    }

    @Test
    fun `the redaction does not run past the end of a line`() {
        val out = command("psql postgres://admin:hunter2@db.example.invalid/app\nrm -rf /important/data")
        assertFalse(out.contains("hunter2"), "credential survived: $out")
        assertTrue(out.contains("rm -rf /important/data"), "next line was consumed: $out")
    }

    @Test
    fun `previously covered shapes still behave`() {
        assertEquals("psql --[REDACTED]", command("psql --password=hunter2"))
        assertFalse(command("curl -H 'Authorization: Bearer abc123def456'").contains("abc123def456"))
    }
}
