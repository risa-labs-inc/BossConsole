package ai.rever.boss.utils.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Tests for LogSanitizer utility functions.
 */
class LogSanitizerTest {

    // =========================================================================
    // maskEmail Tests
    // =========================================================================

    @Test
    fun `maskEmail handles standard email format`() {
        assertEquals("u***@e***.com", LogSanitizer.maskEmail("user@example.com"))
    }

    @Test
    fun `maskEmail handles single char local part`() {
        // Single char local part becomes "*"
        assertEquals("*@e***.com", LogSanitizer.maskEmail("a@example.com"))
        assertEquals("*@e***.com", LogSanitizer.maskEmail("x@example.com"))
    }

    @Test
    fun `maskEmail handles two char local part`() {
        // Two char local part shows first char + stars
        assertEquals("a*@e***.com", LogSanitizer.maskEmail("ab@example.com"))
    }

    @Test
    fun `maskEmail handles subdomain`() {
        assertEquals("u***@m***.example.com", LogSanitizer.maskEmail("user@mail.example.com"))
    }

    @Test
    fun `maskEmail handles null input`() {
        assertEquals("[empty]", LogSanitizer.maskEmail(null))
    }

    @Test
    fun `maskEmail handles blank input`() {
        assertEquals("[empty]", LogSanitizer.maskEmail(""))
        assertEquals("[empty]", LogSanitizer.maskEmail("   "))
    }

    @Test
    fun `maskEmail handles invalid email without at symbol`() {
        assertEquals("[invalid-email]", LogSanitizer.maskEmail("notanemail"))
    }

    @Test
    fun `maskEmail handles multiple at symbols`() {
        assertEquals("[invalid-email]", LogSanitizer.maskEmail("user@domain@extra.com"))
    }

    // =========================================================================
    // maskToken Tests
    // =========================================================================

    @Test
    fun `maskToken preserves first and last 3 chars`() {
        assertEquals("abc...xyz", LogSanitizer.maskToken("abc123456789xyz"))
    }

    @Test
    fun `maskToken handles short tokens`() {
        assertEquals("***", LogSanitizer.maskToken("short"))
        assertEquals("***", LogSanitizer.maskToken("123456"))
    }

    @Test
    fun `maskToken handles exactly 7 chars`() {
        assertEquals("abc...ghi", LogSanitizer.maskToken("abcdefghi"))
    }

    @Test
    fun `maskToken handles null input`() {
        assertEquals("[empty]", LogSanitizer.maskToken(null))
    }

    @Test
    fun `maskToken handles blank input`() {
        assertEquals("[empty]", LogSanitizer.maskToken(""))
    }

    // =========================================================================
    // maskUriParams Tests
    // =========================================================================

    @Test
    fun `maskUriParams redacts token parameter`() {
        val result = LogSanitizer.maskUriParams("boss://auth?token=abc123&type=signup")
        assertEquals("boss://auth?token=[REDACTED]&type=signup", result)
    }

    @Test
    fun `maskUriParams redacts access_token parameter`() {
        val result = LogSanitizer.maskUriParams("https://api.example.com?access_token=secret123")
        assertEquals("https://api.example.com?access_token=[REDACTED]", result)
    }

    @Test
    fun `maskUriParams redacts refresh_token parameter`() {
        val result = LogSanitizer.maskUriParams("https://api.example.com?refresh_token=secret456")
        assertEquals("https://api.example.com?refresh_token=[REDACTED]", result)
    }

    @Test
    fun `maskUriParams redacts multiple sensitive parameters`() {
        val result = LogSanitizer.maskUriParams("boss://auth?token=abc&access_token=def&type=login")
        assertEquals("boss://auth?token=[REDACTED]&access_token=[REDACTED]&type=login", result)
    }

    @Test
    fun `maskUriParams preserves non-sensitive parameters`() {
        val result = LogSanitizer.maskUriParams("boss://auth?type=signup&redirect=home")
        assertEquals("boss://auth?type=signup&redirect=home", result)
    }

    @Test
    fun `maskUriParams handles fragment parameters`() {
        val result = LogSanitizer.maskUriParams("boss://auth#access_token=secret123&type=implicit")
        assertEquals("boss://auth#access_token=[REDACTED]&type=implicit", result)
    }

    @Test
    fun `maskUriParams handles case insensitive param names`() {
        val result = LogSanitizer.maskUriParams("boss://auth?TOKEN=abc&Access_Token=def")
        assertEquals("boss://auth?TOKEN=[REDACTED]&Access_Token=[REDACTED]", result)
    }

    @Test
    fun `maskUriParams redacts api_key parameter`() {
        val result = LogSanitizer.maskUriParams("https://api.example.com?api_key=sk_123456")
        assertEquals("https://api.example.com?api_key=[REDACTED]", result)
    }

    @Test
    fun `maskUriParams handles null input`() {
        assertEquals("[empty]", LogSanitizer.maskUriParams(null))
    }

    @Test
    fun `maskUriParams handles blank input`() {
        assertEquals("[empty]", LogSanitizer.maskUriParams(""))
    }

    @Test
    fun `maskUriParams handles uri without params`() {
        val result = LogSanitizer.maskUriParams("boss://auth/verify")
        assertEquals("boss://auth/verify", result)
    }

    // =========================================================================
    // looksLikeSecret Tests
    // =========================================================================

    @Test
    fun `looksLikeSecret detects JWT tokens`() {
        // JWT tokens start with "eyJ"
        assertTrue(LogSanitizer.looksLikeSecret("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"))
    }

    @Test
    fun `looksLikeSecret detects long alphanumeric strings`() {
        assertTrue(LogSanitizer.looksLikeSecret("abcdefghijklmnopqrstuvwxyz"))
    }

    @Test
    fun `looksLikeSecret detects GitHub tokens`() {
        assertTrue(LogSanitizer.looksLikeSecret("ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"))
        assertTrue(LogSanitizer.looksLikeSecret("gho_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"))
    }

    @Test
    fun `looksLikeSecret detects Stripe-style keys`() {
        assertTrue(LogSanitizer.looksLikeSecret("sk_test_1234567890"))
        assertTrue(LogSanitizer.looksLikeSecret("pk_live_1234567890"))
    }

    @Test
    fun `looksLikeSecret returns false for short strings`() {
        assertFalse(LogSanitizer.looksLikeSecret("hello"))
        assertFalse(LogSanitizer.looksLikeSecret("abc123"))
    }

    @Test
    fun `looksLikeSecret returns false for null`() {
        assertFalse(LogSanitizer.looksLikeSecret(null))
    }

    @Test
    fun `looksLikeSecret returns false for blank`() {
        assertFalse(LogSanitizer.looksLikeSecret(""))
        assertFalse(LogSanitizer.looksLikeSecret("   "))
    }

    // =========================================================================
    // sanitizeMap Tests
    // =========================================================================

    @Test
    fun `sanitizeMap redacts known sensitive keys`() {
        val input = mapOf(
            "username" to "john",
            "token" to "secret123",
            "password" to "hunter2"
        )
        val result = LogSanitizer.sanitizeMap(input)

        assertEquals("john", result["username"])
        assertEquals("[REDACTED]", result["token"])
        assertEquals("[REDACTED]", result["password"])
    }

    @Test
    fun `sanitizeMap handles nested sensitive key names`() {
        val input = mapOf(
            "access_token" to "secret",
            "refresh_token" to "secret",
            "api_key" to "secret",
            "credential_id" to "cred123"
        )
        val result = LogSanitizer.sanitizeMap(input)

        assertEquals("[REDACTED]", result["access_token"])
        assertEquals("[REDACTED]", result["refresh_token"])
        assertEquals("[REDACTED]", result["api_key"])
        assertEquals("[REDACTED]", result["credential_id"])
    }

    @Test
    fun `sanitizeMap masks values that look like secrets`() {
        val input = mapOf(
            "data" to "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload.signature"
        )
        val result = LogSanitizer.sanitizeMap(input)

        // Should be masked because it looks like a JWT
        assertTrue((result["data"] as String).contains("..."))
    }

    @Test
    fun `sanitizeMap preserves non-sensitive values`() {
        val input = mapOf(
            "status" to "success",
            "count" to 42,
            "enabled" to true
        )
        val result = LogSanitizer.sanitizeMap(input)

        assertEquals("success", result["status"])
        assertEquals(42, result["count"])
        assertEquals(true, result["enabled"])
    }

    @Test
    fun `sanitizeMap handles null input`() {
        val result = LogSanitizer.sanitizeMap(null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `sanitizeMap handles empty map`() {
        val result = LogSanitizer.sanitizeMap(emptyMap())
        assertTrue(result.isEmpty())
    }

    // =========================================================================
    // maskCredentialId Tests
    // =========================================================================

    @Test
    fun `maskCredentialId shows length only`() {
        val result = LogSanitizer.maskCredentialId("abc123def456")
        assertEquals("[CREDENTIAL_ID:12chars]", result)
    }

    @Test
    fun `maskCredentialId handles null`() {
        assertEquals("[empty]", LogSanitizer.maskCredentialId(null))
    }

    // =========================================================================
    // maskUserId Tests
    // =========================================================================

    @Test
    fun `maskUserId shows first 4 chars`() {
        val result = LogSanitizer.maskUserId("user-12345-abcdef")
        assertEquals("user...", result)
    }

    @Test
    fun `maskUserId handles short ids`() {
        assertEquals("****", LogSanitizer.maskUserId("abc"))
    }

    @Test
    fun `maskUserId handles null`() {
        assertEquals("[empty]", LogSanitizer.maskUserId(null))
    }

    // =========================================================================
    // maskSessionId Tests
    // =========================================================================

    @Test
    fun `maskSessionId shows first 8 chars`() {
        val result = LogSanitizer.maskSessionId("session-1234567890-abcdef")
        assertEquals("session-...", result)
    }

    @Test
    fun `maskSessionId handles short ids`() {
        assertEquals("****", LogSanitizer.maskSessionId("short"))
    }

    // =========================================================================
    // describeUri Tests
    // =========================================================================

    @Test
    fun `describeUri shows scheme and host without params`() {
        val result = LogSanitizer.describeUri("https://example.com/path")
        assertEquals("https://example.com/path", result)
    }

    @Test
    fun `describeUri indicates query params present`() {
        val result = LogSanitizer.describeUri("https://example.com/path?token=secret")
        assertEquals("https://example.com/path (with query params)", result)
    }

    @Test
    fun `describeUri indicates fragment present`() {
        val result = LogSanitizer.describeUri("https://example.com/path#access_token=secret")
        assertEquals("https://example.com/path (with fragment)", result)
    }

    @Test
    fun `describeUri handles null`() {
        assertEquals("[empty]", LogSanitizer.describeUri(null))
    }
}
