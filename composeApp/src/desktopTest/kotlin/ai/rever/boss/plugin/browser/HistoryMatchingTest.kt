package ai.rever.boss.plugin.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the suggestion list matches, and in what order.
 *
 * Every case here is a real one from a 300-entry history file that the previous
 * `contains`-anywhere match got wrong. Pinned against [rankMatches] rather than
 * [UrlHistoryManager] so no test in this file can read the developer's own history.
 */
class HistoryMatchingTest {
    private val now = 1_800_000_000_000L

    private fun entry(
        url: String,
        title: String = "",
        visits: Int = 1,
        agoHours: Long = 1,
    ) = UrlHistoryEntry(
        url = url,
        title = title,
        domain = suggestableHost(url).orEmpty(),
        visitCount = visits,
        lastVisited = now - agoHours * 60 * 60 * 1000,
    )

    private fun rank(
        query: String,
        vararg entries: UrlHistoryEntry,
    ) = rankMatches(entries.toList(), query, limit = 10, now = now).map { it.url }

    @Test
    fun `a term matches only at the start of a word`() {
        assertTrue(startsWord("localhost:3000", "loc"))
        assertTrue(startsWord("risa-labs-inc/BossConsole", "labs"))
        // The rule that matters: not in the middle of one.
        assertFalse(startsWord("screenshot: password gate, block quota", "loc"))
    }

    @Test
    fun `a title match in the middle of a word ranks below a real one`() {
        val unrelated =
            entry("https://github.com/risa-labs-inc/BossConsole/pull/259", title = "block quota", visits = 6)
        val real = entry("https://localhost:3000/", title = "Dev")

        // "loc" is mid-word in "block", so the PR is answered - underneath, not on top of,
        // the localhost the user was reaching for. Six visits used to be enough to win.
        assertEquals(listOf(real.url, unrelated.url), rank("loc", unrelated, real))
    }

    @Test
    fun `camel case counts as a word start`() {
        // Otherwise typing the second half of a repository name finds nothing.
        assertTrue(startsWord("risa-labs-inc/BossConsole", "console"))
        // But an upper-to-lower step does not, or the OAuth case above comes back.
        assertFalse(startsWord("%2FLocalhost", "localhost"))
    }

    @Test
    fun `every term must match, in the address or the title`() {
        val pulls = entry("https://github.com/risa-labs-inc/BossConsole/pulls", title = "Pull requests")
        val issues = entry("https://github.com/risa-labs-inc/BossConsole/issues", title = "Issues")

        // Two words the user remembers, in either order, matching across both fields.
        assertEquals(listOf(pulls.url), rank("boss pulls", pulls, issues))
        assertEquals(listOf(pulls.url), rank("pulls boss", pulls, issues))
        assertEquals(emptyList(), rank("boss milestones", pulls, issues))
    }

    @Test
    fun `the completable host prefix ranks first`() {
        // Fewer visits, but it is the entry the field's ghost text completes to, so a list
        // that ranked it second would disagree with what the field already shows.
        val host = entry("https://github.com/", title = "GitHub", visits = 2)
        val deep = entry("https://github.com/risa-labs-inc/BossConsole/pulls", title = "Pull requests", visits = 37)

        assertEquals(listOf(host.url, deep.url), rank("github.com", deep, host))
    }

    @Test
    fun `an address match beats a title-only match`() {
        val titleOnly = entry("https://example.com/watch", title = "Youtube tips and tricks", visits = 50)
        val address = entry("https://www.youtube.com/", title = "YouTube", visits = 1)

        assertEquals(listOf(address.url, titleOnly.url), rank("youtube", titleOnly, address))
    }

    @Test
    fun `frecency breaks ties inside a tier`() {
        val daily = entry("https://daily.example/", title = "Daily", visits = 5)
        val once = entry("https://once.example/", title = "Once", visits = 1)

        assertEquals(listOf(daily.url, once.url), rank("example", once, daily))
    }

    @Test
    fun `a blank query matches nothing`() {
        val any = entry("https://github.com/")

        assertEquals(emptyList(), rank("", any))
        assertEquals(emptyList(), rank("   ", any))
    }

    @Test
    fun `matching ignores the scheme and www the entry was stored with`() {
        assertEquals(
            listOf("https://www.youtube.com/"),
            rank("youtube.com", entry("https://www.youtube.com/", title = "YouTube")),
        )
    }

    @Test
    fun `an address prefix match ignores the stored path's casing`() {
        // `canonicalUrlKey` lowercases the authority and leaves the path alone, so a
        // case-sensitive tier dropped every mixed-case path the moment the user typed into
        // it - and the top row then disagreed with the ghost text, which is case-insensitive.
        val deep = entry("https://github.com/risa-labs-inc/BossConsole/pulls", title = "Pull requests")
        val titleOnly = entry("https://example.com/x", title = "boss console notes", visits = 99)

        assertEquals(listOf(deep.url), rank("github.com/risa-labs-inc/boss", titleOnly, deep))
    }

    @Test
    fun `word start boundaries`() {
        assertFalse(startsWord("", "loc"))
        assertFalse(startsWord("lo", "loc"))
        assertTrue(startsWord("loc", "loc"))
        // A digit-to-uppercase step is a word start, same as lower-to-uppercase.
        assertTrue(startsWord("api/v2Beta", "beta"))
    }

    @Test
    fun `pasting a full URL finds the entry`() {
        // The entries are matched in canonical form, so a pasted address - scheme, `www.`
        // and all - was one term longer than any address and matched nothing at all.
        val page = entry("https://www.youtube.com/watch?v=abc", title = "A video")

        assertEquals(listOf(page.url), rank("https://www.youtube.com/watch?v=abc", page))
        assertEquals(listOf(page.url), rank("https://youtube.com/watch", page))
    }

    @Test
    fun `a mid-word match is answered, but underneath every word-start match`() {
        val midWordOnly = entry("https://cdn.example.com/youtubedl", title = "yt-dlp", visits = 99)
        val wordStart = entry("https://tube.example.com/", title = "Tube", visits = 1)

        // "tube" is buried inside "youtubedl" and starts a word in "tube.example.com". Word
        // starts alone dropped the first entirely; ninety-nine visits do not lift it above
        // the second.
        assertEquals(listOf(wordStart.url, midWordOnly.url), rank("tube", midWordOnly, wordStart))
    }

    @Test
    fun `a row with no suggestable host is never answered`() {
        // The gate `addUrl` applies to new visits, applied here to what gets SUGGESTED. It
        // cannot be applied on load - `loadHistory` feeds the map `saveHistory` writes back -
        // so a `javascript:` or `file://` row in a legacy or tampered file has to be dropped
        // at match time instead. It is not something to offer as a completion the field fills
        // in and Enter opens.
        assertEquals(emptyList(), rank("alert", entry("javascript:alert(1)", title = "note", visits = 99)))
        assertEquals(emptyList(), rank("notes", entry("file:///Users/me/notes.html", title = "notes", visits = 99)))
    }

    @Test
    fun `a term may match the title alone while another matches the address alone`() {
        // The second clause of `wordStart` - per-term fallback to the title - is otherwise
        // only reached in cases where the whole query already matched the address, so an
        // implementation that ignored it entirely would still pass.
        val page = entry("https://github.com/risa-labs-inc/BossConsole", title = "Pull requests")

        assertEquals(listOf(page.url), rank("boss requests", page))
        // And a term matching NEITHER field still rejects the entry.
        assertEquals(emptyList(), rank("boss requests milestones", page))
    }

    @Test
    fun `a term past the scanned address cap is not answered`() {
        // The address is capped for the scan the same way the title is: a stored URL is at
        // least as attacker-influenceable, and an OAuth URL runs to thousands of characters
        // that `startsWord` walked per term per keystroke.
        val padded = entry("https://example.com/" + "a/".repeat(300) + "needle", title = "")

        assertEquals(emptyList(), rank("needle", padded))
        // The entry still matches on the part that is scanned.
        assertEquals(listOf(padded.url), rank("example.com", padded))
    }

    @Test
    fun `a userinfo URL is not suggested, the same way it is not completed`() {
        // `java.net.URL` reads the host as what follows the `@` while `canonicalUrlKey` keeps
        // the userinfo, so this passed the suggestable-host gate AND matched "git" at index
        // 0. The field's ghost text already refused it; the list beside it did not, and its
        // rows are clickable - so half the surface was hardened and half was not.
        val spoof = entry("https://github.com@evil.example/", title = "GitHub", visits = 99)

        assertEquals(emptyList(), rank("git", spoof))
        assertEquals(emptyList(), rank("github", spoof))
    }

    @Test
    fun `the mid-word fallback does not resurrect query-string noise above a real hit`() {
        val real = entry("https://localhost:3000/", title = "Dev")
        val oauth =
            entry(
                "https://claude.ai/oauth/authorize?redirect_uri=http%3A%2F%2Flocalhost%3A61673",
                title = "Claude",
                visits = 20,
            )

        // The OAuth URL only matches mid-word, so it sits below - which is the whole point
        // of the tier rather than of dropping it.
        assertEquals(listOf(real.url, oauth.url), rank("localhost", real, oauth))
    }
}
