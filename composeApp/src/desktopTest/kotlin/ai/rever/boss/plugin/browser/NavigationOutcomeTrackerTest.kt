package ai.rever.boss.plugin.browser

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins down the gate that keeps addresses that never loaded out of the URL history and
 * the dashboard's recent pages.
 *
 * The two sides of the gate see different spellings of the same address — the browser
 * engine reports the URL it committed (`https://youtube.como/`), the callers report what
 * the tab is showing, which for a typo is close to what the user typed — so the matching
 * has to survive scheme, `www.`, casing and trailing-slash differences without merging
 * genuinely different pages.
 */
class NavigationOutcomeTrackerTest {
    @BeforeTest
    @AfterTest
    fun resetTracker() {
        NavigationOutcomeTracker.clear()
    }

    @Test
    fun `a failed navigation is recognised from the url the user typed`() {
        NavigationOutcomeTracker.recordFailure("https://youtube.como/")

        assertTrue(NavigationOutcomeTracker.didFail("https://youtube.como/"))
        assertTrue(NavigationOutcomeTracker.didFail("youtube.como"))
        assertTrue(NavigationOutcomeTracker.didFail("http://www.youtube.como"))
    }

    @Test
    fun `a failure on one address says nothing about another`() {
        NavigationOutcomeTracker.recordFailure("https://youtube.como/")

        assertFalse(NavigationOutcomeTracker.didFail("https://youtube.com/"))
        assertFalse(NavigationOutcomeTracker.didFail("https://youtube.como.example.com/"))
    }

    @Test
    fun `a later success clears the failure`() {
        NavigationOutcomeTracker.recordFailure("https://flaky.example/")
        NavigationOutcomeTracker.recordSuccess("https://flaky.example/")

        assertFalse(NavigationOutcomeTracker.didFail("https://flaky.example/"))
    }

    @Test
    fun `paths distinguish pages on the same host`() {
        NavigationOutcomeTracker.recordFailure("https://example.com/gone")

        assertTrue(NavigationOutcomeTracker.didFail("https://example.com/gone"))
        assertFalse(NavigationOutcomeTracker.didFail("https://example.com/"))
        assertFalse(NavigationOutcomeTracker.didFail("https://example.com/other"))
    }

    @Test
    fun `the failure set stays bounded`() {
        repeat(400) { i -> NavigationOutcomeTracker.recordFailure("https://host$i.example/") }

        // The oldest entries are dropped; the recent ones — the only ones a title or load
        // callback can still arrive for — are kept.
        assertFalse(NavigationOutcomeTracker.didFail("https://host0.example/"))
        assertTrue(NavigationOutcomeTracker.didFail("https://host399.example/"))
    }

    @Test
    fun `an empty url is never treated as failed`() {
        NavigationOutcomeTracker.recordFailure("")
        NavigationOutcomeTracker.recordFailure("   ")

        assertFalse(NavigationOutcomeTracker.didFail(""))
    }

    @Test
    fun `canonical keys ignore cosmetic differences`() {
        val expected = "example.com/path"

        assertEquals(expected, canonicalUrlKey("https://example.com/path"))
        assertEquals(expected, canonicalUrlKey("http://EXAMPLE.com/path"))
        assertEquals(expected, canonicalUrlKey("https://www.example.com/path/"))
        assertEquals(expected, canonicalUrlKey("example.com/path#section"))
        assertEquals(expected, canonicalUrlKey("  https://example.com/path  "))
    }

    @Test
    fun `canonical keys keep what actually distinguishes a page`() {
        assertEquals("example.com/a?q=1", canonicalUrlKey("https://example.com/a?q=1"))
        assertEquals("example.com/Path", canonicalUrlKey("https://example.com/Path"))
        assertEquals("localhost:3000/app", canonicalUrlKey("http://localhost:3000/app"))
        assertEquals("about:blank", canonicalUrlKey("about:blank"))
        assertEquals("", canonicalUrlKey("   "))
    }

    @Test
    fun `a host reached over both schemes is the same place`() {
        assertEquals(canonicalUrlKey("http://example.com"), canonicalUrlKey("https://example.com"))
    }

    @Test
    fun `page identity normalizes the same spellings as the outcome key`() {
        assertEquals(
            distinctPageKey("https://www.youtube.com/"),
            distinctPageKey("https://youtube.com"),
        )
    }

    @Test
    fun `page identity keeps hash-routed views apart`() {
        // Gmail serves #inbox and #sent from one path. Merging history entries on the
        // outcome key would collapse every view of it into a single suggestion.
        val inbox = "https://mail.google.com/mail/u/0/#inbox"
        val sent = "https://mail.google.com/mail/u/0/#sent"

        assertEquals(canonicalUrlKey(inbox), canonicalUrlKey(sent))
        assertNotEquals(distinctPageKey(inbox), distinctPageKey(sent))
    }

    @Test
    fun `a fragment does not change whether a navigation failed`() {
        NavigationOutcomeTracker.recordFailure("https://youtube.como/")

        // The address doesn't resolve, so no #section of it resolves either.
        assertTrue(NavigationOutcomeTracker.didFail("https://youtube.como/#watch"))
    }

    @Test
    fun `a failure stops counting once it is stale`() {
        val failedAt = 1_000_000L
        NavigationOutcomeTracker.recordFailure("https://example.com/", now = failedAt)

        val ttl = NavigationOutcomeTracker.FAILURE_TTL_MS
        assertTrue(NavigationOutcomeTracker.didFail("https://example.com/", now = failedAt + ttl))
        // Past the window the verdict is dropped: a redirect commits a different URL, so
        // nothing would ever clear the address the user actually asked for.
        assertFalse(
            NavigationOutcomeTracker.didFail("https://example.com/", now = failedAt + ttl + 1),
        )
    }

    @Test
    fun `a host with a port normalizes like any other host`() {
        // The typed form and the committed form differ in scheme, www and path casing;
        // all three have to land on one key or the gate silently misses.
        assertEquals(
            canonicalUrlKey("https://www.Example.com:8443/App"),
            canonicalUrlKey("example.com:8443/App"),
        )
    }

    @Test
    fun `paths keep their case`() {
        // Servers distinguish /Doc from /doc, and a file:// path may sit on a
        // case-sensitive filesystem — these keys drive deletion, so they must not merge.
        assertNotEquals(canonicalUrlKey("https://example.com/Doc"), canonicalUrlKey("https://example.com/doc"))
        assertNotEquals(
            canonicalUrlKey("file:///Users/me/Doc.html"),
            canonicalUrlKey("file:///users/me/doc.html"),
        )
    }

    @Test
    fun `an opaque body is preserved exactly`() {
        // data: payloads are base64 — lowercasing one changes what it decodes to.
        assertEquals("data:text/plain;base64,SGVsbG8", canonicalUrlKey("data:text/plain;base64,SGVsbG8"))
        assertEquals("about:blank", canonicalUrlKey("ABOUT:blank"))
    }

    @Test
    fun `a scheme is normalized but its authority still is too`() {
        assertEquals(canonicalUrlKey("CHROME://Settings"), canonicalUrlKey("chrome://settings"))
    }

    @Test
    fun `only http and https addresses are worth suggesting`() {
        assertEquals("example.com", suggestableHost("https://example.com/path"))
        assertEquals("example.com", suggestableHost("HTTP://Example.com"))
        assertNull(suggestableHost("file:///Users/me/notes.html"))
        assertNull(suggestableHost("about:blank"))
        assertNull(suggestableHost("data:text/plain,hi"))
        assertNull(suggestableHost("not a url at all"))
    }

    @Test
    fun `a navigation that loaded a page is the only kind worth recording`() {
        assertEquals(
            NavigationVerdict.LOADED,
            classifyNavigation(
                isMainFrame = true,
                hasUrl = true,
                isErrorPage = false,
                hasCommitted = true,
                hasNetworkError = false,
            ),
        )
    }

    @Test
    fun `error pages, uncommitted loads and network errors all count as failure`() {
        val errorPage =
            classifyNavigation(
                isMainFrame = true,
                hasUrl = true,
                isErrorPage = true,
                hasCommitted = true,
                hasNetworkError = false,
            )
        val neverCommitted =
            classifyNavigation(
                isMainFrame = true,
                hasUrl = true,
                isErrorPage = false,
                hasCommitted = false,
                hasNetworkError = false,
            )
        val netError =
            classifyNavigation(
                isMainFrame = true,
                hasUrl = true,
                isErrorPage = false,
                hasCommitted = true,
                hasNetworkError = true,
            )

        assertEquals(NavigationVerdict.FAILED, errorPage)
        assertEquals(NavigationVerdict.FAILED, neverCommitted)
        assertEquals(NavigationVerdict.FAILED, netError)
    }

    @Test
    fun `sub-frames and blank urls say nothing about the page`() {
        // An iframe that fails to load is not the page the user is on.
        val subFrame =
            classifyNavigation(
                isMainFrame = false,
                hasUrl = true,
                isErrorPage = true,
                hasCommitted = false,
                hasNetworkError = true,
            )
        val noUrl =
            classifyNavigation(
                isMainFrame = true,
                hasUrl = false,
                isErrorPage = false,
                hasCommitted = true,
                hasNetworkError = false,
            )

        assertEquals(NavigationVerdict.IGNORED, subFrame)
        assertEquals(NavigationVerdict.IGNORED, noUrl)
    }
}
