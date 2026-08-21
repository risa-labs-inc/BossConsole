package ai.rever.boss.plugin.browser

import ai.rever.boss.components.plugin.panels.right_top.resolveSingleByUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How a dynamic plugin's browser tab is resolved to one of the open browsers.
 *
 * `findBrowserForTab` needs a live `SplitViewState` and real JxBrowser handles, which is why it
 * has never had a test - so the selection was pulled out as [resolveSingleByUrl], following the
 * same split as `BrowserWindowOwnershipRegistry` beside `BrowserServiceImpl`. It lives in this
 * package rather than beside the function because `right_top` does not satisfy detekt's
 * PackageNaming; `internal` is module-wide, so the import is all that is needed.
 *
 * What this does NOT cover, stated so nobody reads more into it: the cached URL itself.
 * `BrowserHandleImpl.lastKnownUrl` is seeded from the creation config and updated from a
 * `NavigationFinished` callback, and neither can be exercised here - the class needs a JxBrowser
 * `Browser`, there is no mocking library on this source set, and that interface is far too wide
 * to stub by hand. That is the same reason no test constructs a handle today.
 */
class ResolveSingleByUrlTest {
    private data class Candidate(
        val name: String,
        val url: String,
    )

    private val a = Candidate("a", "https://example.com/one")
    private val b = Candidate("b", "https://example.com/two")

    @Test
    fun `the single match is returned and nothing is reported`() {
        var reported: Int? = null
        val picked =
            resolveSingleByUrl(
                candidates = listOf(a, b),
                matches = { it.url == "https://example.com/two" },
                onAmbiguous = { reported = it },
            )
        assertEquals(b, picked)
        assertNull(reported, "one match is not an ambiguity")
    }

    @Test
    fun `no match resolves to null rather than an arbitrary candidate`() {
        var reported: Int? = null
        val picked =
            resolveSingleByUrl(
                candidates = listOf(a, b),
                matches = { it.url == "https://example.com/absent" },
                onAmbiguous = { reported = it },
            )
        assertNull(picked, "a URL nothing is at must not resolve to some other tab's browser")
        assertNull(reported)
    }

    @Test
    fun `an empty candidate list is not an ambiguity`() {
        var reported: Int? = null
        val picked =
            resolveSingleByUrl(
                candidates = emptyList<Candidate>(),
                matches = { true },
                onAmbiguous = { reported = it },
            )
        assertNull(picked)
        assertNull(reported, "nothing to choose between is not a conflict to report")
    }

    // The next two cover one scenario - two browsers on one URL - but they are separate
    // detectors and so separate tests: dropping the report and reversing the pick are
    // different regressions, and a single test asserting both would go on passing for one of
    // them after an edit made for the other.

    @Test
    fun `two browsers on one URL still resolve to the first`() {
        // Deliberately unchanged: iteration order decides, because nothing here can tell which
        // tab owns which handle.
        val duplicate = Candidate("a-again", a.url)
        val picked =
            resolveSingleByUrl(
                candidates = listOf(a, duplicate, b),
                matches = { it.url == a.url },
                onAmbiguous = { },
            )
        assertEquals(a, picked, "the first match must still win, as before")
    }

    @Test
    fun `two browsers on one URL are reported with their count`() {
        // What actually changes: it stops being silent. A plugin driving the wrong tab used to
        // leave no trace at all.
        val duplicate = Candidate("a-again", a.url)
        var reported: Int? = null
        resolveSingleByUrl(
            candidates = listOf(a, duplicate, b),
            matches = { it.url == a.url },
            onAmbiguous = { reported = it },
        )
        assertEquals(2, reported, "the ambiguity must be reported with its count")
    }

    @Test
    fun `every candidate is offered to the predicate exactly once`() {
        // The predicate is what reads each handle's cached URL. Calling it twice per candidate
        // would double the cost this change exists to remove, and skipping candidates would
        // make a lookup depend on list order in a second, hidden way.
        val seen = mutableListOf<String>()
        resolveSingleByUrl(
            candidates = listOf(a, b),
            matches = {
                seen += it.name
                false
            },
            onAmbiguous = { },
        )
        assertEquals(listOf("a", "b"), seen)
    }

    @Test
    fun `the predicate is the only thing consulted`() {
        // Guards the property the whole fix rests on: selection asks each candidate one
        // question and nothing else. A future revision that fell back to a live URL read when
        // the cached one looked stale would reintroduce the per-handle IPC round trip, and
        // this test is where that shows up - the candidates here have no way to answer
        // anything but the predicate.
        var calls = 0
        val picked =
            resolveSingleByUrl(
                candidates = listOf(a, b),
                matches = {
                    calls++
                    it == b
                },
                onAmbiguous = { },
            )
        assertEquals(b, picked)
        assertTrue(calls == 2, "the predicate should have been asked once per candidate, was $calls")
    }
}
