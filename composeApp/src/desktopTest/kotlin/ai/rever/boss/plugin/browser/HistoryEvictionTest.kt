package ai.rever.boss.plugin.browser

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the destructive half of the history gating: which entries a failed navigation
 * retires, and — the part with real blast radius — when a name-resolution failure is
 * allowed to mean "this address does not exist" rather than "DNS is down right now".
 */
class HistoryEvictionTest {
    private val now = 1_000_000L

    private fun entry(
        url: String,
        lastVisited: Long,
        title: String = "A page",
    ) = UrlHistoryEntry(
        url = url,
        title = title,
        domain = url.substringAfter("://").substringBefore('/'),
        visitCount = 1,
        lastVisited = lastVisited,
    )

    @BeforeTest
    @AfterTest
    fun resetTracker() {
        NavigationOutcomeTracker.clear()
    }

    @Test
    fun `an address that is gone takes every spelling of itself with it`() {
        val entries =
            listOf(
                entry("https://youtube.como/", lastVisited = now - 500_000),
                entry("youtube.como", lastVisited = now - 900_000),
                entry("http://www.youtube.como", lastVisited = now - 100),
                entry("https://youtube.com/", lastVisited = now - 500_000),
            )

        val evicted = entriesToEvict(entries, "https://youtube.como/", recordedWithinMs = null, now = now)

        assertEquals(
            listOf("https://youtube.como/", "youtube.como", "http://www.youtube.como"),
            evicted.map { it.url },
        )
    }

    @Test
    fun `a windowed retraction only reaches what was just recorded`() {
        val entries =
            listOf(
                // The racing entry: recorded a moment ago by a title callback.
                entry("https://example.com/", lastVisited = now - 1_000),
                // Real history for the same address, from last week.
                entry("https://www.example.com/", lastVisited = now - 604_800_000),
            )

        val evicted = entriesToEvict(entries, "https://example.com/", recordedWithinMs = 5_000, now = now)

        assertEquals(listOf("https://example.com/"), evicted.map { it.url })
    }

    @Test
    fun `the retraction window includes its own boundary`() {
        val entries = listOf(entry("https://example.com/", lastVisited = now - 5_000))

        assertEquals(1, entriesToEvict(entries, "https://example.com/", 5_000, now).size)
        assertEquals(0, entriesToEvict(entries, "https://example.com/", 4_999, now).size)
    }

    @Test
    fun `a retraction spares an entry with visits behind it`() {
        // lastVisited is refreshed on every visit, so "touched in the last 5s" also
        // describes a site with hundreds of visits that was simply open a moment ago.
        // Only an entry the racing callback could have created is a candidate.
        val established =
            UrlHistoryEntry(
                url = "https://github.com/",
                title = "GitHub",
                domain = "github.com",
                visitCount = 300,
                lastVisited = now - 1_000,
            )
        val racing = entry("https://github.com/", lastVisited = now - 1_000).copy(visitCount = 1)

        assertEquals(0, entriesToEvict(listOf(established), "https://github.com/", 5_000, now).size)
        assertEquals(1, entriesToEvict(listOf(racing), "https://github.com/", 5_000, now).size)
    }

    @Test
    fun `an address that is gone takes even a well-visited entry`() {
        // The other half of the rule: once resolution is known to work and the address
        // still doesn't exist, visit count says nothing — those visits went to an error
        // page, which is how the typo accumulated them in the first place.
        val entries =
            listOf(entry("https://youtube.como/", lastVisited = now).copy(visitCount = 6))

        assertEquals(1, entriesToEvict(entries, "https://youtube.como/", null, now).size)
    }

    @Test
    fun `opening a new tab is not evidence that dns works`() {
        // about:blank commits as a normal main-frame navigation in this app, so counting
        // it would let opening a tab during an outage re-arm unconditional eviction.
        NavigationOutcomeTracker.recordSuccess("about:blank", now = now)
        assertFalse(NavigationOutcomeTracker.hasLoadedRecently(now))

        NavigationOutcomeTracker.recordSuccess("file:///Users/me/notes.html", now = now)
        assertFalse(NavigationOutcomeTracker.hasLoadedRecently(now))

        NavigationOutcomeTracker.recordSuccess("https://github.com/", now = now)
        assertTrue(NavigationOutcomeTracker.hasLoadedRecently(now))
    }

    @Test
    fun `only an error page reaches into stored history`() {
        // An uncommitted navigation — stop pressed, a link clicked mid-reload, a load that
        // became a download — produced no title and so no visit to undo. Retracting for it
        // would delete the entry for the page the user is still looking at.
        assertEquals(
            RetractionScope.LEAVE_ALONE,
            retractionScopeFor(isErrorPage = false, addressIsGone = false),
        )
        assertEquals(
            RetractionScope.RETRACT_RECENT,
            retractionScopeFor(isErrorPage = true, addressIsGone = false),
        )
        assertEquals(
            RetractionScope.EVICT_ALL,
            retractionScopeFor(isErrorPage = true, addressIsGone = true),
        )
    }

    @Test
    fun `an address known to exist is never evicted wholesale`() {
        // Split horizon: off the VPN, jira.internal.corp stops resolving while public DNS
        // is healthy, so the resolver-health check alone would approve deleting it.
        withTemporaryResolvedHostsStore {
            ResolvedHostsStore.recordLoaded("jira.internal.corp")

            assertTrue(ResolvedHostsStore.hasEverLoaded("jira.internal.corp"))
            assertTrue(ResolvedHostsStore.hasEverLoaded("JIRA.Internal.Corp"))
            // A typo has never served a page to anyone — that is what makes it safe to
            // forget, and what a title heuristic can't tell you, since Chromium titles its
            // error document with the failed host.
            assertFalse(ResolvedHostsStore.hasEverLoaded("youtube.como"))
        }
    }

    /** Points the store at a scratch file so the developer's own profile is untouched. */
    private fun withTemporaryResolvedHostsStore(block: () -> Unit) {
        val original = ResolvedHostsStore.storeFile
        val temp = File.createTempFile("resolved-hosts", ".json")
        temp.delete()
        try {
            ResolvedHostsStore.storeFile = temp
            ResolvedHostsStore.clear()
            block()
        } finally {
            temp.delete()
            ResolvedHostsStore.storeFile = original
            ResolvedHostsStore.clear()
        }
    }

    @Test
    fun `an unrelated address is never touched`() {
        val entries = listOf(entry("https://github.com/", lastVisited = now))

        assertEquals(0, entriesToEvict(entries, "https://gitlab.com/", null, now).size)
        assertEquals(0, entriesToEvict(entries, "", null, now).size)
    }

    @Test
    fun `a resolution failure is only trusted while something else is loading`() {
        // The scenario that must not delete history: the resolver stops answering, so
        // every address the user tries reports NAME_NOT_RESOLVED. Nothing has loaded, so
        // nothing about those failures says the addresses stopped existing.
        assertFalse(NavigationOutcomeTracker.hasLoadedRecently(now))

        NavigationOutcomeTracker.recordSuccess("https://github.com/", now = now - 1_000)
        assertTrue(NavigationOutcomeTracker.hasLoadedRecently(now))
    }

    @Test
    fun `evidence that resolution works goes stale`() {
        val window = NavigationOutcomeTracker.RESOLVER_HEALTH_WINDOW_MS
        NavigationOutcomeTracker.recordSuccess("https://github.com/", now = now - window)

        assertTrue(NavigationOutcomeTracker.hasLoadedRecently(now))
        assertFalse(NavigationOutcomeTracker.hasLoadedRecently(now + 1))
    }
}
