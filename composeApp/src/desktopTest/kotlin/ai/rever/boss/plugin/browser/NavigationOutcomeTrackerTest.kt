package ai.rever.boss.plugin.browser

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
