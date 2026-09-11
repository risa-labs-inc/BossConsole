package ai.rever.boss.utils

import ai.rever.boss.services.supabase.models.SecretEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [WebsiteMatchingUtil.calculateMatchScore] and [WebsiteMatchingUtil.getDisplayName]
 * against the over-broad matching BossConsole#460 reports.
 *
 * The scorer now recognizes exactly two shapes - exact registrable-domain equality and a real
 * subdomain boundary - matching what a password manager keys on. Two earlier tiers are gone
 * rather than patched: an unanchored `contains` check (a secret for `apple.com` matched the
 * unrelated site `snapple.com`), and a token-overlap "partial" fallback that matched any shared
 * word after splitting on `.`/`-`/`_`. Excluding bare TLD labels from that fallback closed the
 * "every .com secret matches every .com site" case, but it still matched two different
 * registrable domains sharing a brand-ish label (`apple.com` vs `apple.org`, `google.com` vs
 * `google-workspace.com`) - the same wrong-site-credential problem, just narrower. Precision
 * wins over recall on a credentials surface: a missed suggestion is an inconvenience, a wrong
 * one hands the user someone else's password.
 */
class WebsiteMatchingUtilTest {
    private fun secret(website: String) =
        SecretEntry(
            id = "1",
            website = website,
            username = "user",
            password = "pass",
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z",
        )

    // ---- An unanchored substring must not match ----

    @Test
    fun `a secret for apple does not match a site that merely contains the word as a substring`() {
        val score = WebsiteMatchingUtil.calculateMatchScore("apple.com", "snapple.com")
        assertEquals(0.0f, score.score)
        assertEquals("no_match", score.reason)
    }

    @Test
    fun `a secret for apple does not match a hyphenated site that contains it as a whole word`() {
        // Would have scored 0.5 "partial" under the old token-overlap fallback (shared word
        // "apple") - still the wrong-site-credential problem the unanchored contains check had,
        // just reached a different way.
        val score = WebsiteMatchingUtil.calculateMatchScore("apple.com", "login-apple.com")
        assertEquals(0.0f, score.score)
        assertEquals("no_match", score.reason)
    }

    // ---- A bare TLD label must not count as a shared token ----

    @Test
    fun `a dot-com secret does not match an unrelated dot-com site`() {
        val score = WebsiteMatchingUtil.calculateMatchScore("google.com", "example.com")
        assertEquals(0.0f, score.score)
        assertEquals("no_match", score.reason)
    }

    @Test
    fun `a dot-org secret does not match an unrelated dot-org site`() {
        val score = WebsiteMatchingUtil.calculateMatchScore("wikipedia.org", "example.org")
        assertEquals(0.0f, score.score)
    }

    // ---- Two different registrable domains sharing a brand label must not match ----

    @Test
    fun `apple dot com and apple dot org are different registrable domains and do not match`() {
        val score = WebsiteMatchingUtil.calculateMatchScore("apple.com", "apple.org")
        assertEquals(0.0f, score.score)
        assertEquals("no_match", score.reason)
    }

    @Test
    fun `google dot com and google-workspace dot com are different registrable domains and do not match`() {
        // The exact case the old "partial" tier's own docstring cited as intentional - a shared
        // brand word is not the same domain, and this surface must not offer one for the other.
        val score = WebsiteMatchingUtil.calculateMatchScore("google.com", "google-workspace.com")
        assertEquals(0.0f, score.score)
        assertEquals("no_match", score.reason)
    }

    // ---- Legitimate matches must survive the fix ----

    @Test
    fun `exact match still scores 1_0`() {
        val score = WebsiteMatchingUtil.calculateMatchScore("google.com", "google.com")
        assertEquals(1.0f, score.score)
        assertEquals("exact", score.reason)
    }

    @Test
    fun `a subdomain still matches its parent domain`() {
        val score = WebsiteMatchingUtil.calculateMatchScore("google.com", "login.google.com")
        assertEquals(0.9f, score.score)
        assertEquals("subdomain", score.reason)
    }

    @Test
    fun `a www-prefixed secret still exact-matches the bare domain`() {
        // matchSecretsForDomain strips a leading www. from the page domain, and
        // extractMainDomain does the same for the secret's stored website - a secret saved as
        // "www.google.com" must still resolve to an exact match on "google.com", not merely
        // a subdomain match.
        val matches =
            WebsiteMatchingUtil.matchSecretsForDomain(
                domain = "google.com",
                secrets = listOf(secret("www.google.com")),
            )
        assertEquals(1, matches.size)
        assertEquals("exact", matches.single().matchReason)
    }

    @Test
    fun `www normalization does not turn a shared-TLD pair into a match`() {
        val matches =
            WebsiteMatchingUtil.matchSecretsForDomain(
                domain = "www.example.com",
                secrets = listOf(secret("www.google.com")),
            )
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `two entirely unrelated domains score zero`() {
        val score = WebsiteMatchingUtil.calculateMatchScore("github.com", "example.net")
        assertEquals(0.0f, score.score)
        assertTrue(score.reason == "no_match")
    }

    @Test
    fun `blank sides do not vacuously exact-match each other`() {
        assertEquals(0.0f, WebsiteMatchingUtil.calculateMatchScore("", "").score)
        assertEquals(0.0f, WebsiteMatchingUtil.calculateMatchScore("", "example.com").score)
    }

    // ---- End to end: the actual suggestion path a user sees ----

    @Test
    fun `matchSecretsForDomain offers the exact-match secret and nothing else`() {
        val matches =
            WebsiteMatchingUtil.matchSecretsForDomain(
                domain = "google.com",
                secrets =
                    listOf(
                        secret("google.com"),
                        secret("apple.org"),
                        secret("google-workspace.com"),
                    ),
            )
        assertEquals(1, matches.size)
        assertEquals("google.com", matches.single().secret.website)
        assertEquals("exact", matches.single().matchReason)
    }

    @Test
    fun `matchSecretsForDomain offers nothing for a site with no related saved secret`() {
        val matches =
            WebsiteMatchingUtil.matchSecretsForDomain(
                domain = "snapple.com",
                secrets = listOf(secret("apple.com"), secret("apple.org")),
            )
        assertTrue(matches.isEmpty())
    }

    // ---- getDisplayName must not duplicate a digit-initial word ----

    @Test
    fun `a digit-initial domain name is not duplicated`() {
        assertEquals("1password", WebsiteMatchingUtil.getDisplayName("1password.com"))
        assertEquals("9gag", WebsiteMatchingUtil.getDisplayName("9gag.com"))
    }

    @Test
    fun `known brand display names are unaffected`() {
        assertEquals("GitHub", WebsiteMatchingUtil.getDisplayName("github.com"))
        assertEquals("Google", WebsiteMatchingUtil.getDisplayName("google.com"))
    }

    @Test
    fun `hyphenated generic domains are still title-cased per word`() {
        assertEquals("Example Site", WebsiteMatchingUtil.getDisplayName("example-site.com"))
    }
}
