package ai.rever.boss.plugin.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [LogSanitizer.maskUriParams] when the fragment comes before the query.
 *
 * Every existing test puts the fragment last or omits it, which is the shape a deep
 * link has. A hash-routed web callback is the other way round, and that ordering hit an
 * end index computed by searching from 0 rather than from the start of the segment being
 * masked. The index landed before the segment, substring threw, and the caller's catch
 * replaced the whole URL with "[uri-mask-error]".
 *
 * Nothing leaked, so this is a diagnostic fault rather than a disclosure one: the URL was
 * lost from the log, not printed. The assertions are written against the masked output
 * rather than against the absence of a secret, so a version that went back to refusing to
 * mask would fail them. Three of the four fail against the previous implementation; the
 * fourth pins the orderings that already worked and passes against both, deliberately.
 */
class UriFragmentOrderTest {
    @Test
    fun `a hash-routed callback is masked rather than discarded`() {
        assertEquals(
            "https://app.example.com/#/reset?token=[REDACTED]",
            LogSanitizer.maskUriParams("https://app.example.com/#/reset?token=abc123"),
        )
    }

    @Test
    fun `every sensitive parameter after a fragment is still redacted`() {
        val masked =
            LogSanitizer.maskUriParams(
                "https://app.example.com/#/callback?access_token=secret123&code=xyz789&type=implicit",
            )

        assertTrue(masked.contains("access_token=[REDACTED]"), masked)
        assertTrue(masked.contains("code=[REDACTED]"), masked)
        assertTrue(masked.contains("type=implicit"), "a harmless parameter should survive: $masked")
        assertTrue(!masked.contains("secret123"), "the token must not reach the log: $masked")
        assertTrue(!masked.contains("xyz789"), "the code must not reach the log: $masked")
    }

    @Test
    fun `the failure placeholder is no longer produced for these URLs`() {
        val inputs =
            listOf(
                "https://app.example.com/#/reset?token=abc123",
                "https://x/#frag?code=secret",
                "boss://auth#/route?refresh_token=secret",
            )
        for (input in inputs) {
            assertTrue(
                LogSanitizer.maskUriParams(input) != "[uri-mask-error]",
                "sanitising $input still fails outright",
            )
        }
    }

    @Test
    fun `the ordinary orderings are unchanged`() {
        // Pinned here as well as in the existing suite, because the fix edits the index
        // both orderings share and a regression would be silent in the common case.
        assertEquals(
            "boss://auth?token=[REDACTED]&type=signup",
            LogSanitizer.maskUriParams("boss://auth?token=abc123&type=signup"),
        )
        assertEquals(
            "boss://auth#access_token=[REDACTED]",
            LogSanitizer.maskUriParams("boss://auth#access_token=secret123"),
        )
        assertEquals(
            "boss://auth?type=signup#access_token=[REDACTED]",
            LogSanitizer.maskUriParams("boss://auth?type=signup#access_token=secret123"),
        )
        assertEquals(
            "boss://auth/verify",
            LogSanitizer.maskUriParams("boss://auth/verify"),
        )
    }
}
