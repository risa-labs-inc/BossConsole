package ai.rever.boss.utils.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun `looksLikeSecret detects the remaining GitHub token prefixes`() {
        // ghs_ (server-to-server) and github_pat_ (fine-grained) round out the
        // ghp_/gho_ pair above. Both are short enough here that the length rule
        // does not reach them, so this pins the structural rule specifically.
        assertTrue(LogSanitizer.looksLikeSecret("ghs_0123456789ab"))
        assertTrue(LogSanitizer.looksLikeSecret("github_pat_01234567"))
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
        val input =
            mapOf(
                "username" to "john",
                "token" to "secret123",
                "password" to "hunter2",
            )
        val result = LogSanitizer.sanitizeMap(input)

        assertEquals("john", result["username"])
        assertEquals("[REDACTED]", result["token"])
        assertEquals("[REDACTED]", result["password"])
    }

    @Test
    fun `sanitizeMap handles nested sensitive key names`() {
        val input =
            mapOf(
                "access_token" to "secret",
                "refresh_token" to "secret",
                "api_key" to "secret",
                "credential_id" to "cred123",
            )
        val result = LogSanitizer.sanitizeMap(input)

        assertEquals("[REDACTED]", result["access_token"])
        assertEquals("[REDACTED]", result["refresh_token"])
        assertEquals("[REDACTED]", result["api_key"])
        assertEquals("[REDACTED]", result["credential_id"])
    }

    @Test
    fun `sanitizeMap masks values that look like secrets`() {
        val input =
            mapOf(
                "data" to "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload.signature",
            )
        val result = LogSanitizer.sanitizeMap(input)

        // Should be masked because it looks like a JWT
        assertTrue((result["data"] as String).contains("..."))
    }

    @Test
    fun `sanitizeMap masks a short value with a credential shape`() {
        // Under an unremarkable key, so the key list does not decide it, and
        // short enough that the length rule does not either.
        val result = LogSanitizer.sanitizeMap(mapOf("detail" to "ghs_0123456789ab"))

        assertEquals("ghs...9ab", result["detail"])
    }

    @Test
    fun `sanitizeMap preserves non-sensitive values`() {
        val input =
            mapOf(
                "status" to "success",
                "count" to 42,
                "enabled" to true,
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

    // =========================================================================
    // sanitizeExceptionMessage Tests
    //
    // These messages travel: CrashHandler puts them into a crash report, and a
    // crash report is filed where anyone can read it. So the bar is two-sided —
    // no credential survives, and everything that makes the message diagnosable
    // does.
    // =========================================================================

    @Test
    fun `sanitizeExceptionMessage masks a bare GitHub token`() {
        val result = LogSanitizer.sanitizeExceptionMessage("token=ghp_AbCdEfGhIjKlMnOpQrStUvWxYz012345 rejected")

        assertEquals("token=ghp...345 rejected", result)
    }

    @Test
    fun `sanitizeExceptionMessage masks a JWT under a config key`() {
        val result =
            LogSanitizer.sanitizeExceptionMessage(
                "SUPABASE_ANON_KEY=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.body.sig invalid",
            )

        assertEquals("SUPABASE_ANON_KEY=eyJ...sig invalid", result)
    }

    @Test
    fun `sanitizeExceptionMessage masks a JWT written in prose`() {
        // No name=value pair and no URL around it, so only the structural rule
        // can catch this one.
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"

        val result = LogSanitizer.sanitizeExceptionMessage("Bearer $jwt was refused")

        assertEquals("Bearer eyJ...R8U was refused", result)
    }

    @Test
    fun `sanitizeExceptionMessage masks fine-grained and server GitHub tokens`() {
        assertEquals(
            "GITHUB_TOKEN=git...NOP rejected",
            LogSanitizer.sanitizeExceptionMessage("GITHUB_TOKEN=github_pat_11ABCDEFG0abcdefghijKLMNOP rejected"),
        )
        assertEquals(
            "credential ghs...hij was refused",
            LogSanitizer.sanitizeExceptionMessage("credential ghs_0123456789abcdefghij was refused"),
        )
    }

    @Test
    fun `sanitizeExceptionMessage masks a hyphenated vendor key`() {
        val result =
            LogSanitizer.sanitizeExceptionMessage("OPENAI_API_KEY=sk-proj-AbCdEfGhIjKlMnOpQrStUvWx not accepted")

        assertEquals("OPENAI_API_KEY=sk-...vWx not accepted", result)
    }

    @Test
    fun `sanitizeExceptionMessage masks a camelCase assignment`() {
        val result = LogSanitizer.sanitizeExceptionMessage("accessToken=abcdefghijklmnopqrst expired")

        assertEquals("accessToken=abc...rst expired", result)
    }

    @Test
    fun `sanitizeExceptionMessage keeps a token carried in a URL redacted`() {
        // The path rule runs first and its `(?:/[^\s:]+)+` swallows everything
        // after the scheme's colon, which is what removes a token from a query
        // string or a fragment. Pinned here because the later passes must not
        // disturb that ordering.
        assertEquals(
            "Request to https:[PATH] failed",
            LogSanitizer.sanitizeExceptionMessage(
                "Request to https://api.example.com/auth/v1/verify?access_token=eyJhbGciOiJIUzI1NiJ9.abc.def failed",
            ),
        )
        assertEquals(
            "Deep link boss:[PATH] rejected",
            LogSanitizer.sanitizeExceptionMessage(
                "Deep link boss://auth/verify#access_token=eyJhbGciOiJIUzI1NiJ9.abc.def&type=magiclink rejected",
            ),
        )
    }

    @Test
    fun `sanitizeExceptionMessage redacts a bare private hostname`() {
        val result = LogSanitizer.sanitizeExceptionMessage("java.net.UnknownHostException: internal.service.local")

        assertEquals("java.net.UnknownHostException: [HOST]", result)
    }

    @Test
    fun `sanitizeExceptionMessage redacts a private hostname but preserves its port`() {
        val result = LogSanitizer.sanitizeExceptionMessage("Connect to proxy.corp.internal:3128 failed")

        assertEquals("Connect to [HOST]:3128 failed", result)
    }

    @Test
    fun `sanitizeExceptionMessage preserves ordinary dotted diagnostics`() {
        val message = "com.example.Client reported foo.bar in Version 1.2.3."

        assertEquals(message, LogSanitizer.sanitizeExceptionMessage(message))
    }

    @Test
    fun `sanitizeExceptionMessage preserves an ordinary long identifier`() {
        // Well over the 20-character length that marks a map *value* as
        // sensitive. In message text a long run is normally just a name, and
        // masking it would cost the only clue the message carries.
        val message = "Unresolved dependency applicationPreferencesRepositoryFactory for task_manager_configuration"

        assertEquals(message, LogSanitizer.sanitizeExceptionMessage(message))
    }

    @Test
    fun `sanitizeExceptionMessage preserves config pairs that name no secret`() {
        val message = "KEYBOARD_LAYOUT=us and LOG_LEVEL=DEBUG and BOSS_WINDOW_MODE=maximized"

        assertEquals(message, LogSanitizer.sanitizeExceptionMessage(message))
    }

    @Test
    fun `sanitizeExceptionMessage preserves a null value under a sensitive name`() {
        // "token=null" is the whole diagnosis; "token=***" throws it away.
        val message = "session token=null while refreshing"

        assertEquals(message, LogSanitizer.sanitizeExceptionMessage(message))
    }

    @Test
    fun `sanitizeExceptionMessage keeps masking paths and emails`() {
        assertEquals(
            "Could not read [PATH] for [EMAIL]",
            LogSanitizer.sanitizeExceptionMessage(
                "Could not read /Users/someone/.boss/config.json for someone@example.com",
            ),
        )
    }

    @Test
    fun `sanitizeExceptionMessage handles null and blank`() {
        assertEquals("[no message]", LogSanitizer.sanitizeExceptionMessage(null))
        assertEquals("[no message]", LogSanitizer.sanitizeExceptionMessage(""))
    }

    // =========================================================================
    // sanitizeStackTrace Tests
    // =========================================================================

    @Test
    fun `sanitizeStackTrace leaves a realistic Kotlin trace intact`() {
        val trace =
            listOf(
                "java.lang.IllegalStateException: Plugin registry was not initialized",
                "\tat ai.rever.boss.plugin.loader.DynamicPluginLoader.loadPlugin(DynamicPluginLoader.kt:214)",
                "\tat ai.rever.boss.plugin.repository.PluginRepositoryCoordinator\$installFromStore\$2" +
                    ".invokeSuspend(PluginRepositoryCoordinator.kt:487)",
                "\tat kotlinx.coroutines.internal.ScopeCoroutine.afterResume(Scopes.kt:33)",
                "\tat kotlinx.coroutines.scheduling.CoroutineScheduler\$Worker.executeTask(CoroutineScheduler.kt:806)",
                "\tat java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)",
                "Caused by: java.lang.NullPointerException: activeWorkspaceConfigurationProvider was null",
            ).joinToString("\n")

        assertEquals(trace, LogSanitizer.sanitizeStackTrace(trace))
    }

    @Test
    fun `sanitizeStackTrace masks a credential quoted into a Caused by line`() {
        val trace =
            listOf(
                "java.lang.IllegalArgumentException: bad config",
                "\tat ai.rever.boss.services.auth.SessionManager.refreshSession(SessionManager.kt:142)",
                "Caused by: java.io.IOException: " +
                    "SUPABASE_ANON_KEY=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.body.sig invalid",
            ).joinToString("\n")

        val result = LogSanitizer.sanitizeStackTrace(trace)

        assertTrue(result.contains("SUPABASE_ANON_KEY=eyJ...sig invalid"))
        // The frame either side of it is untouched.
        assertTrue(result.contains("ai.rever.boss.services.auth.SessionManager.refreshSession(SessionManager.kt:142)"))
        assertFalse(result.contains("IkpXVCJ9"))
    }

    @Test
    fun `sanitizeStackTrace redacts a private hostname in a Caused by line`() {
        val trace = "Caused by: java.net.UnknownHostException: internal.service.local"

        assertEquals("Caused by: java.net.UnknownHostException: [HOST]", LogSanitizer.sanitizeStackTrace(trace))
    }

    @Test
    fun `sanitizeStackTrace treats a JDK module prefix as a path`() {
        // Pre-existing behaviour of the path rule, unrelated to credential
        // masking: the `/` in a module-qualified frame starts a path match, so
        // the frame reads as `java.base[PATH]`. Recorded so the effect is known
        // rather than discovered, and so a future change to the path rule shows
        // up here.
        val frame = "\tat java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)"

        assertEquals("\tat java.base[PATH]:1136)", LogSanitizer.sanitizeStackTrace(frame))
    }

    @Test
    fun `sanitizeStackTrace handles null and blank`() {
        assertEquals("[no stack trace]", LogSanitizer.sanitizeStackTrace(null))
        assertEquals("[no stack trace]", LogSanitizer.sanitizeStackTrace(""))
    }
}
