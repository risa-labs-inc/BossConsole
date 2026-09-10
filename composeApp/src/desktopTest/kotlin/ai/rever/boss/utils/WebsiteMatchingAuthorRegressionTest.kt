package ai.rever.boss.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class WebsiteMatchingAuthorRegressionTest {
    @Test
    fun `test calculateMatchScore PASS cases`() {
        // exact match
        assertEquals(1.0f, WebsiteMatchingUtil.calculateMatchScore("apple.com", "apple.com").score)

        // dot-boundary match
        assertEquals(0.9f, WebsiteMatchingUtil.calculateMatchScore("login.apple.com", "apple.com").score)
        assertEquals(0.9f, WebsiteMatchingUtil.calculateMatchScore("accounts.apple.com", "apple.com").score)
        // Test both directions.
        assertEquals(0.9f, WebsiteMatchingUtil.calculateMatchScore("apple.com", "login.apple.com").score)
        assertEquals(0.9f, WebsiteMatchingUtil.calculateMatchScore("sub.example.com", "example.com").score)

        // multi-part TLD correct matches
        assertEquals(0.9f, WebsiteMatchingUtil.calculateMatchScore("auth.google.com.mx", "google.com.mx").score)
        assertEquals(0.9f, WebsiteMatchingUtil.calculateMatchScore("google.com.mx", "auth.google.com.mx").score)
    }

    @Test
    fun `test calculateMatchScore MUST FAIL cases`() {
        // substring but no dot boundary
        assertEquals(0.0f, WebsiteMatchingUtil.calculateMatchScore("snapple.com", "apple.com").score)
        assertEquals(0.0f, WebsiteMatchingUtil.calculateMatchScore("apple.com", "snapple.com").score)
        assertEquals(0.0f, WebsiteMatchingUtil.calculateMatchScore("login-apple.com", "apple.com").score)

        // completely different domains
        assertEquals(0.0f, WebsiteMatchingUtil.calculateMatchScore("example.com", "google.com").score)
        assertEquals(0.0f, WebsiteMatchingUtil.calculateMatchScore("google.com", "example.com").score)
        assertEquals(0.0f, WebsiteMatchingUtil.calculateMatchScore("foo.com", "bar.com").score)

        // attacker injecting domain prefix
        assertEquals(
            0.0f,
            WebsiteMatchingUtil.calculateMatchScore("attacker.example.com.evil.com", "example.com").score,
        )
        assertEquals(
            0.0f,
            WebsiteMatchingUtil.calculateMatchScore("example.com", "attacker.example.com.evil.com").score,
        )

        // multi-part TLD isolation
        assertEquals(0.0f, WebsiteMatchingUtil.calculateMatchScore("google.com.mx", "apple.com.mx").score)
    }

    @Test
    fun `test extractMainDomain extracts full host correctly`() {
        assertEquals("login.apple.com", WebsiteMatchingUtil.extractMainDomain("https://login.apple.com"))
        assertEquals("192.168.1.1", WebsiteMatchingUtil.extractMainDomain("http://192.168.1.1"))
        assertEquals("apple.com", WebsiteMatchingUtil.extractMainDomain("https://www.apple.com"))
        assertEquals("auth.google.com.mx", WebsiteMatchingUtil.extractMainDomain("https://auth.google.com.mx"))
        assertEquals("localhost", WebsiteMatchingUtil.extractMainDomain("http://localhost:8080"))
    }

    @Test
    fun `test getDisplayName transforms first character correctly`() {
        assertEquals("1password", WebsiteMatchingUtil.getDisplayName("1password.com"))
        assertEquals("9gag", WebsiteMatchingUtil.getDisplayName("9gag.com"))
        assertEquals("Example Site", WebsiteMatchingUtil.getDisplayName("example-site.com"))
    }
}
