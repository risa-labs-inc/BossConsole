package ai.rever.boss.plugin.browser

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the store itself rather than the pure helpers around it: the gate on what gets
 * recorded, how one page recorded under two spellings collapses, and the ordering the URL
 * bar actually sees.
 *
 * The memoized URL fact table inside `UrlHistoryManager.kt` is process-global and is NOT
 * reset between tests. That is safe because it is keyed by the URL string and derived from
 * nothing else, so an entry left by another test can only be re-derived to the same value -
 * do not read it as per-store state.
 *
 * [UrlHistoryManager.historyFile] is pointed at a scratch file so no test here reads or
 * writes the developer's own history. The teardown does re-read it, deliberately:
 * [UrlHistoryManager] is a process-global store, and leaving it holding a deleted scratch
 * file would strand every other test in the run.
 */
class UrlHistoryManagerTest {
    private lateinit var tempFile: File
    private lateinit var originalFile: File

    @BeforeTest
    fun useScratchFile() {
        originalFile = UrlHistoryManager.historyFile
        tempFile = File.createTempFile("browser-history", ".json").also { it.delete() }
        UrlHistoryManager.historyFile = tempFile
        UrlHistoryManager.loadHistory()
        NavigationOutcomeTracker.clear()
    }

    @AfterTest
    fun restore() {
        // Drain first. A background write still in flight when the pointer moves back is
        // how an earlier version of this test wrote an empty history over a real one —
        // the store now binds its target when the write is requested, and waiting here
        // means the test can't depend on that being true.
        runBlocking { UrlHistoryManager.awaitPendingWrites() }
        tempFile.delete()
        UrlHistoryManager.historyFile = originalFile
        UrlHistoryManager.loadHistory()
        NavigationOutcomeTracker.clear()
    }

    @Test
    fun `two spellings of one page are a single suggestion straight away`() {
        // Previously these occupied two slots until a restart merged them.
        UrlHistoryManager.addUrl("https://x.com", "X")
        UrlHistoryManager.addUrl("https://www.x.com/", "X")

        val suggestions = UrlHistoryManager.getSuggestions("x.com")

        assertEquals(1, suggestions.size)
        assertEquals(2, suggestions.single().visitCount)
    }

    @Test
    fun `the https spelling is the one we keep`() {
        UrlHistoryManager.addUrl("http://x.com/app", "App")
        UrlHistoryManager.addUrl("https://x.com/app", "App")

        assertEquals("https://x.com/app", UrlHistoryManager.getSuggestions("x.com").single().url)
    }

    @Test
    fun `a prefix of the host ranks above a match buried in a title`() {
        // The `www.` in a stored domain used to keep the obvious answer out of the
        // domain-prefix bucket, so typing "you" ranked an unrelated page first.
        UrlHistoryManager.addUrl("https://example.com/watch", "Youtube tips and tricks")
        UrlHistoryManager.addUrl("https://www.youtube.com/", "YouTube")

        assertEquals("https://www.youtube.com/", UrlHistoryManager.getSuggestions("you").first().url)
    }

    @Test
    fun `a host and port are kept apart`() {
        UrlHistoryManager.addUrl("http://localhost:3000/", "Dev")
        UrlHistoryManager.addUrl("http://localhost:8080/", "Docs")

        val domains = UrlHistoryManager.getSuggestions("localhost").map { it.domain }.toSet()

        assertEquals(setOf("localhost:3000", "localhost:8080"), domains)
    }

    @Test
    fun `visits outrank recency`() {
        // The old score was recency in all but name: a count needed to pass ~1750 before
        // it moved anything.
        UrlHistoryManager.addUrl("https://daily.example/", "Daily")
        repeat(4) { UrlHistoryManager.addUrl("https://daily.example/", "Daily") }
        UrlHistoryManager.addUrl("https://once.example/", "Once")

        val ranked = UrlHistoryManager.getSuggestions("example")

        assertEquals("https://daily.example/", ranked.first().url)
    }

    @Test
    fun `an address that failed to load is never recorded`() {
        NavigationOutcomeTracker.recordFailure("https://youtube.como/")

        UrlHistoryManager.addUrl("https://youtube.como/", "youtube.como")

        assertEquals(emptyList(), UrlHistoryManager.getSuggestions("youtube.como"))
    }

    @Test
    fun `addresses with no host to suggest are turned away`() {
        UrlHistoryManager.addUrl("about:blank", "New tab")
        UrlHistoryManager.addUrl("file:///Users/me/notes.html", "Notes")
        UrlHistoryManager.addUrl("data:text/plain,hi", "hi")

        assertEquals(emptyList(), UrlHistoryManager.getSuggestions("notes"))
        assertEquals(emptyList(), UrlHistoryManager.getSuggestions("blank"))
    }

    @Test
    fun `a deletion survives a reload`() {
        UrlHistoryManager.addUrl("https://gone.example/", "Gone")
        runBlocking { UrlHistoryManager.saveHistory() }

        UrlHistoryManager.deleteUrl("https://gone.example/")
        runBlocking { UrlHistoryManager.awaitPendingWrites() }
        UrlHistoryManager.loadHistory()

        assertEquals(emptyList(), UrlHistoryManager.getSuggestions("gone.example"))
    }

    @Test
    fun `a deletion finds the entry under any spelling of it`() {
        UrlHistoryManager.addUrl("https://www.spelling.example/", "Spelling")

        UrlHistoryManager.deleteUrl("spelling.example")

        assertEquals(emptyList(), UrlHistoryManager.getSuggestions("spelling"))
    }

    @Test
    fun `history written before shared host normalization is repaired on load`() {
        tempFile.writeText(
            """
            [
              {
                "url": "https://www.legacy.example/",
                "title": "Legacy",
                "domain": "www.legacy.example",
                "visitCount": 3,
                "lastVisited": 1
              }
            ]
            """.trimIndent(),
        )

        UrlHistoryManager.loadHistory()

        assertEquals("legacy.example", UrlHistoryManager.getSuggestions("legacy").single().domain)
    }

    @Test
    fun `an empty store answers nothing rather than everything`() {
        UrlHistoryManager.addUrl("https://example.com/", "Example")

        assertEquals(emptyList(), UrlHistoryManager.getSuggestions(""))
        assertEquals(emptyList(), UrlHistoryManager.getSuggestions("   "))
    }

    @Test
    fun `saving keeps the file parseable and the entries intact`() {
        UrlHistoryManager.addUrl("https://persisted.example/page", "Persisted")
        runBlocking { UrlHistoryManager.saveHistory() }

        assertTrue(tempFile.exists())
        UrlHistoryManager.loadHistory()

        val reloaded = UrlHistoryManager.getSuggestions("persisted").single()
        assertEquals("https://persisted.example/page", reloaded.url)
        assertEquals("Persisted", reloaded.title)
        assertNull(UrlHistoryManager.getSuggestions("nothing-here").firstOrNull())
    }

    @Test
    fun `a stored title is capped`() {
        // A page controls its own `document.title`, and every stored title is word-scanned
        // on every keystroke of every URL field.
        UrlHistoryManager.addUrl("https://example.com/", "t".repeat(5_000))

        val stored = UrlHistoryManager.getSuggestions("example.com").single()
        assertEquals(256, stored.title.length)
    }

    @Test
    fun `the in-memory store is capped, not just the file`() {
        // The 1000 cap used to apply only on the way to disk, so the map grew for the whole
        // life of the process - and the per-keystroke match cost grows with it.
        repeat(1_400) { UrlHistoryManager.addUrl("https://site$it.example/", "Site $it") }

        val held = UrlHistoryManager.getSuggestions("example", limit = 10_000)
        // Both bounds matter. `<= 1_200` alone passes if a prune only ever trims to the
        // slack; `< 1_400` alone passes if it trims by a single entry. Together they say
        // something actually cut back past the cap. `the prune waits for the slack` below
        // pins the exact shape.
        assertTrue(held.size <= 1_200, "expected the store to be pruned, held ${held.size}")
        assertTrue(held.size < 1_400, "expected a prune to have happened at all, held ${held.size}")
    }

    @Test
    fun `matching still finds an entry after the store has pruned past the slack`() {
        // The prune and the matcher are tested apart; this is the interaction that makes the
        // cap load-bearing. It also drives the memoized fact table past its own bound, which
        // now retains what the store holds rather than emptying itself.
        UrlHistoryManager.addUrl("https://needle.example/page", "Needle")
        repeat(1_400) { UrlHistoryManager.addUrl("https://filler$it.example/", "Filler $it") }
        // Kept fresh so frecency does not evict the entry the assertion depends on.
        UrlHistoryManager.addUrl("https://needle.example/page", "Needle")

        val found = UrlHistoryManager.getSuggestions("needle.example")
        assertEquals("https://needle.example/page", found.firstOrNull()?.url)
    }

    @Test
    fun `a negative limit returns nothing instead of throwing`() {
        // `rankMatches` ends in `take`, which rejects a negative count, and this function is
        // what `DesktopUrlHistoryProvider` calls - so the limit arrives from plugin code
        // through `PluginContext.urlHistoryProvider`. Floored at the function that throws
        // rather than at any one call site.
        UrlHistoryManager.addUrl("https://example.com/", "Example")

        assertEquals(emptyList(), UrlHistoryManager.getSuggestions("example", limit = -1))
        assertEquals(emptyList(), UrlHistoryManager.getSuggestions("example", limit = 0))
        assertEquals(1, UrlHistoryManager.getSuggestions("example", limit = 1).size)
    }

    @Test
    fun `a title from an older file is only scanned up to the cap`() {
        // `addUrl` caps what it STORES, but a file written before that cap existed still
        // holds whatever the page put in its `document.title` - and matching is the loop the
        // cap exists to bound. Capping in `loadHistory` instead would be destructive: that
        // map is what `saveHistory` writes back.
        val title = "x".repeat(300) + " needle"
        tempFile.writeText(
            """
            [{"url": "https://example.com/", "title": "$title",
              "domain": "example.com", "visitCount": 1, "lastVisited": $NOW}]
            """.trimIndent(),
        )
        UrlHistoryManager.loadHistory()

        // "needle" starts at index 301, past the 256-character cap, so it is not scanned.
        assertEquals(emptyList(), UrlHistoryManager.getSuggestions("needle"))
        // The suggestion is capped too, not just the scan: `maxLines = 1` truncates what a
        // dropdown row draws, never what Compose measures.
        assertEquals(
            256,
            UrlHistoryManager
                .getSuggestions("example.com")
                .single()
                .title.length,
        )
        // And the store itself is untouched, which is why the cap is not applied on load:
        // a save writes back what a load read.
        runBlocking { UrlHistoryManager.saveHistory() }
        assertTrue(tempFile.readText().contains(title), "a save must not rewrite the stored title")
    }

    @Test
    fun `the prune waits for the slack and then cuts back to the cap`() {
        // The test above only shows that SOMETHING prunes. What makes it affordable is the
        // shape: pruning is a sort, so it must not run on every visit past the cap, and when
        // it does run it has to cut all the way back - otherwise the next visit sorts again.
        fun store(size: Int) =
            (1..size)
                .associate { i ->
                    // Keyed the way the store keys itself, or `retainAll` drops everything.
                    distinctPageKey("https://site$i.example/") to
                        UrlHistoryEntry(
                            url = "https://site$i.example/",
                            title = "Site $i",
                            domain = "site$i.example",
                            visitCount = i,
                            lastVisited = NOW,
                        )
                }.toMutableMap()

        val atSlack = store(1_200)
        pruneIfOversized(atSlack, NOW)
        assertEquals(1_200, atSlack.size, "the slack is what keeps pruning amortised")

        val pastSlack = store(1_201)
        pruneIfOversized(pastSlack, NOW)
        assertEquals(1_000, pastSlack.size, "a prune has to cut to the cap, not to the slack")
        // What survives is the best-ranked tail, not an arbitrary 1000.
        assertTrue(distinctPageKey("https://site1201.example/") in pastSlack)
        assertTrue(distinctPageKey("https://site1.example/") !in pastSlack)
    }

    private companion object {
        /** A fixed clock, so ranking inside a prune does not depend on when the test runs. */
        const val NOW = 1_800_000_000_000L
    }
}
