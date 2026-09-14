package ai.rever.boss.plugin.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [BrowserRouteTemplate.template] is the second privacy boundary for browser telemetry —
 * the first being `registrableDomain`. Whatever it returns is what leaves the device about
 * *where in a site* the user was.
 *
 * The tests are grouped by the question they answer, and the second group is the important
 * one: a route is only safe because an unrecognised segment fails closed. Every assertion
 * there is a disclosure that would otherwise ship.
 */
class BrowserRouteTemplateTest {
    private fun route(raw: String?) = BrowserRouteTemplate.template(raw)

    // ---- shape classification -------------------------------------------------------

    @Test
    fun `numeric ids become placeholders and structural words survive`() {
        val r = route("/claims/8837261/detail")
        assertEquals("claims>:num>detail", r?.value)
        assertFalse(r!!.known, "a placeholder means the route is not fully recognised")
    }

    @Test
    fun `a fully recognised route reports known`() {
        val r = route("/settings/billing")
        assertEquals("settings>billing", r?.value)
        assertTrue(r!!.known)
    }

    @Test
    fun `classifies uuids, dates and hashes by shape`() {
        assertEquals("orders>:uuid", route("/orders/3f2504e0-4f89-41d3-9a0c-0305e82c3301")?.value)
        assertEquals("reports>:date", route("/reports/2026-09-10")?.value)
        assertEquals("files>:hex", route("/files/a1b2c3d4e5f6")?.value)
    }

    @Test
    fun `a root path is countable rather than absent`() {
        assertEquals("root", route("/")?.value)
        assertEquals("root", route("https://example.com")?.value)
        assertTrue(route("/")!!.known)
    }

    @Test
    fun `accepts a whole url as readily as a path`() {
        // Callers hold a URL; making them split it first is where a query string gets
        // carried along by accident.
        assertEquals("claims>:num>detail", route("https://portal.acmecorp.com/claims/8837261/detail")?.value)
    }

    // ---- failing closed: the assertions that matter ---------------------------------

    @Test
    fun `an unrecognised word is a placeholder, not a literal`() {
        // The whole design. `john-smith` is not an identifier by any regex — it is just a
        // word — so a deny-list approach ships it. Only a closed vocabulary catches it.
        assertEquals("accounts>:word", route("/accounts/john-smith")?.value)
        assertEquals(":word>:word", route("/acme-corp/quarterly-revenue")?.value)
    }

    @Test
    fun `identifier-bearing segments never survive`() {
        assertEquals("users>:slug", route("/users/jane.doe@example.com")?.value)
        assertEquals("account>:slug", route("/account/ref4417882")?.value)
        assertEquals(":word>:num", route("/ledger/4417")?.value)
    }

    @Test
    fun `the query string and fragment are cut before anything else`() {
        // The most identifier-dense part of a URL, and nothing here is meant to look at it.
        assertEquals("search", route("/search?account=123&ref=8837261")?.value)
        assertEquals("login", route("/login#access_token=secret")?.value)
    }

    @Test
    fun `a deep path is truncated and marked unknown`() {
        val r = route("/a/b/c/d/e/f/g/h/i/j/k/l")
        assertEquals(8, r!!.value.split(">").size, "capped at the segment budget")
        assertFalse(r.known, "truncation means the route is not the whole story")
    }

    @Test
    fun `an over-long route is refused rather than trimmed`() {
        // Refuse-not-trim, as everywhere else in this file's neighbour: a route that needed
        // cleaning was not built from the classifier's own output.
        // Hard to reach on purpose: placeholders compress, and literals are bounded by the
        // vocabulary — so only a maximum-depth path of the longest route words exceeds the
        // budget. Pinned because that makes the cap look like dead code otherwise.
        assertNull(route("/" + "getting-started/".repeat(8)))
    }

    @Test
    fun `blank input yields nothing`() {
        assertNull(route(null))
        assertNull(route("   "))
    }

    @Test
    fun `the emitted route never contains a slash`() {
        // Load-bearing, not cosmetic: the downstream scrubber drops any value containing a
        // slash followed by two characters, so a `/`-joined route would arrive as an absent
        // property — delivered, and empty. Pinned so nobody "simplifies" the separator.
        val samples =
            listOf("/claims/8837261/detail", "/settings/billing", "/accounts/john-smith", "/")
        for (s in samples) {
            val value = route(s)?.value ?: continue
            assertFalse(value.contains('/'), "route for $s must not contain a slash: $value")
        }
    }

    // ---- selection buckets ----------------------------------------------------------

    @Test
    fun `selection lengths floor into buckets`() {
        assertEquals(0, selectionCharBucket(12))
        assertEquals(25, selectionCharBucket(25))
        assertEquals(25, selectionCharBucket(99))
        assertEquals(100, selectionCharBucket(100))
        assertEquals(500, selectionCharBucket(100_000))
    }

    @Test
    fun `no selection is absent rather than zero`() {
        // Absent and "shorter than 25 characters" are different answers.
        assertNull(selectionCharBucket(null))
        assertNull(selectionCharBucket(0))
        assertNull(selectionCharBucket(-5))
    }
}
