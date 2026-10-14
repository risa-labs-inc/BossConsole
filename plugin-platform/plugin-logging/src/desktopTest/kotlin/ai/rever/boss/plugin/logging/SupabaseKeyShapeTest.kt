package ai.rever.boss.plugin.logging

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [LogSanitizer] masks Supabase's current API key shapes in free text.
 *
 * Supabase replaced the legacy JWT anon and service_role keys with opaque `sb_publishable_...`
 * and `sb_secret_...` strings. The credential-shape pattern recognised a JWT, a GitHub token and
 * a vendor `sk_`/`pk_` key, so the new shapes passed through every free-text path untouched -
 * `sanitizeExceptionMessage`, `sanitizeLogMessage` and `sanitizeStackTrace`, which is where a
 * client library prints the key it was rejected for.
 *
 * `sb_secret_` is the service_role replacement, so it bypasses row-level security entirely; these
 * lines reach the Console capture that `console_tail` and `console_search` serve and that every
 * plugin reads through `PluginContext.logDataProvider`.
 *
 * BossConsole#870 closed the JWT case for the realtime client and recorded the `sb_*` shapes as
 * follow-up scope. This is that follow-up.
 *
 * Not covered: the Supabase personal access token `sbp_`, which authorises the management API.
 * It is still masked in a map position (length) and in `name=value` position, but not free-standing
 * in message text - recorded here so the scope of "the shapes this product issues" is explicit.
 */
class SupabaseKeyShapeTest {
    private val secret = "sb_secret_A1b2C3d4E5f6G7h8J9k0L1m2"
    private val publishable = "sb_publishable_Zx9Yw8Vu7Ts6Rq5Pn4Mk3Jh2"

    @Test
    fun `a supabase secret key is masked in an exception message`() {
        val masked = LogSanitizer.sanitizeExceptionMessage("connect failed: apikey $secret rejected")
        assertFalse(masked.contains(secret), masked)
    }

    @Test
    fun `a supabase publishable key is masked in an exception message`() {
        val masked = LogSanitizer.sanitizeExceptionMessage("using $publishable for realtime")
        assertFalse(masked.contains(publishable), masked)
    }

    @Test
    fun `a supabase key is masked in a stack trace`() {
        val masked = LogSanitizer.sanitizeStackTrace("at Realtime.connect($secret)\n\tat Foo.bar(Foo.kt:1)")
        assertFalse(masked.contains(secret), masked)
    }

    /**
     * The shapes that were already masked stay masked. Widening a security pattern is the change
     * most likely to break the alternation around it, and each of these is a separate branch.
     */
    @Test
    fun `the shapes that were already recognised are still masked`() {
        val cases =
            mapOf(
                "jwt" to "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abc123",
                "github" to "ghp_A1b2C3d4E5f6G7h8J9k0",
                "github pat" to "github_pat_A1b2C3d4E5f6G7h8",
                "vendor sk" to "sk_live_A1b2C3d4E5f6G7h8",
                "vendor pk" to "pk-test_A1b2C3d4E5f6G7h8",
            )
        cases.forEach { (name, value) ->
            val masked = LogSanitizer.sanitizeExceptionMessage("token $value rejected")
            assertFalse(masked.contains(value), "$name: $masked")
        }
    }

    /**
     * `sb_` alone is not a credential. The pattern names the two published prefixes rather than
     * masking anything that starts with those two letters, because over-masking a diagnostic is
     * the failure this file's own KDoc warns about for [LogSanitizer.looksLikeSecret].
     */
    @Test
    fun `an ordinary word beginning with sb is left alone`() {
        val text = "sb_config_reloaded and sbom_generated and sb_ alone"
        assertTrue(LogSanitizer.sanitizeExceptionMessage(text).contains("sb_config_reloaded"))
        assertTrue(LogSanitizer.sanitizeExceptionMessage(text).contains("sbom_generated"))
    }
}
