package ai.rever.boss.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class WebsiteMatchingBoundaryRegressionTest {
    @Test
    fun `calculateMatchScore enforces domain boundaries and rejects substrings`() {
        // 1. Exact match
        var match = WebsiteMatchingUtil.calculateMatchScore("apple.com", "apple.com")
        assertEquals(1.0f, match.score)
        assertEquals("exact", match.reason)

        // 2. Legitimate subdomain match in both directions
        match = WebsiteMatchingUtil.calculateMatchScore("apple.com", "login.apple.com")
        assertEquals(0.9f, match.score)
        assertEquals("subdomain", match.reason)

        match = WebsiteMatchingUtil.calculateMatchScore("login.apple.com", "apple.com")
        assertEquals(0.9f, match.score)
        assertEquals("subdomain", match.reason)

        // 3. Substring collision rejection
        match = WebsiteMatchingUtil.calculateMatchScore("apple.com", "snapple.com")
        assertEquals(0.0f, match.score)
        assertEquals("no_match", match.reason)

        match = WebsiteMatchingUtil.calculateMatchScore("snapple.com", "apple.com")
        assertEquals(0.0f, match.score)
        assertEquals("no_match", match.reason)

        match = WebsiteMatchingUtil.calculateMatchScore("apple.com", "login-apple.com")
        assertEquals(0.0f, match.score)
        assertEquals("no_match", match.reason)

        // 4. Same-TLD rejection
        match = WebsiteMatchingUtil.calculateMatchScore("google.com", "example.com")
        assertEquals(0.0f, match.score)
        assertEquals("no_match", match.reason)

        // 5. Multi-part TLD/domain rejection
        match = WebsiteMatchingUtil.calculateMatchScore("google.co.uk", "example.co.uk")
        assertEquals(0.0f, match.score)
        assertEquals("no_match", match.reason)
    }

    @Test
    fun `getDisplayName formats domain names correctly`() {
        // Base case
        assertEquals("Google", WebsiteMatchingUtil.getDisplayName("google.com"))

        // 6. Digit-initial display names (regression test for the duplication bug)
        assertEquals("1password", WebsiteMatchingUtil.getDisplayName("1password.com"))
        assertEquals("9gag", WebsiteMatchingUtil.getDisplayName("9gag.com"))

        // 7. Existing hyphen/underscore formatting regression
        assertEquals("Example Site", WebsiteMatchingUtil.getDisplayName("example-site.com"))
        assertEquals("Some Example Site", WebsiteMatchingUtil.getDisplayName("some_example-site.com"))
    }
}
