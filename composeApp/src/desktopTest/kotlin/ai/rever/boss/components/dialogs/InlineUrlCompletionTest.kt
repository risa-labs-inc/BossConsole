package ai.rever.boss.components.dialogs

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The prefix rules behind the URL field's inline completion.
 *
 * Worth pinning here rather than through the dialog because every one of these is a case
 * that made the field unusable in an earlier shape of it: completing into a search URL
 * while the user was still typing a query, completing a whole PR path in exchange for
 * three characters, and rewriting the case of what the user had already typed.
 */
class InlineUrlCompletionTest {
    private fun history(vararg urls: String) = urls.map { UrlSuggestion(url = it, title = "") }

    private val githubCompletion = UrlCompletion("github.com", "https://github.com/")

    /** The completion's displayed text - what the ghost draws. */
    private fun display(
        typed: String,
        suggestions: List<UrlSuggestion>,
    ) = inlineUrlCompletion(typed, suggestions)?.display

    @Test
    fun `completes a prefix to the host, not to the deepest page`() {
        val suggestions =
            history(
                "https://github.com/risa-labs-inc/BossConsole/pulls",
                "https://github.com/",
            )

        assertEquals("github.com", display("git", suggestions))
    }

    @Test
    fun `completes the path once the host is typed out`() {
        val suggestions = history("https://github.com/risa-labs-inc/BossConsole/pulls")

        assertEquals(
            "github.com/risa-labs-inc/BossConsole/pulls",
            display("github.com/risa", suggestions),
        )
    }

    @Test
    fun `matches through the stored spelling's scheme and www`() {
        // The entry is stored as the browser committed it; the user types neither prefix.
        assertEquals("youtube.com", display("you", history("https://www.youtube.com/")))
    }

    @Test
    fun `keeps the case the user typed`() {
        assertEquals("GitHub.com", display("GitHub", history("https://github.com/")))
    }

    @Test
    fun `offers nothing for a search`() {
        val suggestions = history("https://github.com/risa-labs-inc/BossConsole")

        // Whitespace means this is a query, and completing it would eat what is still being typed.
        assertNull(display("git commit", suggestions))
        assertNull(display("", suggestions))
        assertNull(display("   ", suggestions))
    }

    @Test
    fun `never completes towards a search suggestion`() {
        val searchRow =
            listOf(
                UrlSuggestion(
                    url = "https://www.google.com/search?q=git",
                    title = "Search Google for \"git\"",
                    isSearchSuggestion = true,
                ),
            )

        assertNull(display("git", searchRow))
    }

    @Test
    fun `offers nothing once the address is fully typed`() {
        // Equal, not longer: a completion of zero characters would leave an empty selection
        // and make the next keystroke's behaviour depend on it.
        assertNull(display("github.com", history("https://github.com/")))
    }

    @Test
    fun `follows the ranked order of the suggestions`() {
        // Both hosts match "g"; the list is already ranked, so the first one wins.
        val suggestions = history("https://gmail.com/", "https://github.com/")

        assertEquals("gmail.com", display("g", suggestions))
    }

    @Test
    fun `ghost text draws the tail and keeps the cursor inside the value`() {
        val transformed =
            ghostTextTransformation(
                UrlCompletion("github.com", "https://github.com/"),
                Color.Gray,
            ).filter(AnnotatedString("git"))

        assertEquals("github.com", transformed.text.text)
        assertEquals(3, transformed.offsetMapping.originalToTransformed(3))
        // An offset inside the ghost maps back to the end of the value: the cursor must never
        // land in text the field does not actually contain.
        assertEquals(3, transformed.offsetMapping.transformedToOriginal(10))
    }

    @Test
    fun `nothing is drawn without a completion, or once it stops matching`() {
        val none = ghostTextTransformation(null, Color.Gray).filter(AnnotatedString("git"))
        assertEquals("git", none.text.text)

        // A completion left over from an earlier keystroke must not be drawn against text it
        // no longer extends.
        val stale =
            ghostTextTransformation(githubCompletion, Color.Gray).filter(AnnotatedString("xyz"))
        assertEquals("xyz", stale.text.text)
    }

    @Test
    fun `a path completion keeps the stored spelling, not the typed case`() {
        val suggestions = history("https://github.com/risa-labs-inc/BossConsole/pulls")

        // Splicing the user's casing onto the stored tail produced
        // "github.com/risa-labs-inc/bossConsole/pulls" - an address in neither history nor
        // the field, and a 404 on any case-sensitive server. A path must match exactly.
        assertNull(display("github.com/risa-labs-inc/boss", suggestions))
        assertEquals(
            "github.com/risa-labs-inc/BossConsole/pulls",
            display("github.com/risa-labs-inc/Boss", suggestions),
        )
    }

    @Test
    fun `a host is never swapped for a different one, at any prefix`() {
        // One drive-by visit is all it takes to put a lookalike in history. Extending a
        // typed host by bare prefix would hand the user somebody else's domain, and Enter
        // would take it. Guarding only a host that "looks finished" left every prefix on
        // the way to it wide open, which is every host anyone ever types.
        val lookalike = history("https://paypal.com-login.evil.example/signin")

        assertNull(display("paypal.com", lookalike))
        assertNull(display("paypal.c", lookalike))
        assertNull(display("paypal.", lookalike))
        // Same rule when the lookalike merely adds a label.
        assertNull(display("github.com", history("https://github.com.evil.example/")))
        // And with no attacker at all: a different port is a different machine.
        assertNull(display("192.168.4.2", history("http://192.168.4.20:8123/lovelace")))
    }

    @Test
    fun `a bare prefix can still reach an unfamiliar host`() {
        // The boundary of the host-swap rule, pinned so nobody reads the KDoc above it as a
        // broader promise than it makes. Once the typed text names a host, that host cannot
        // be swapped - the cases above. BEFORE it does, there is no host to protect, and a
        // prefix with no dot in it completes to whatever history ranked first.
        //
        // Chrome gates this on `typed_count` - whether the user ever typed that URL. There is
        // no equivalent to gate on here: `visitCount` counts `addUrl` calls, and the only
        // caller is the browser plugin's TITLE listener, so a page that assigns
        // `document.title` twice raises its own count. A gate on it would read like
        // protection and provide none.
        val lookalike = history("https://paypal.com-login.evil.example/signin")

        assertEquals("paypal.com-login.evil.example", display("paypal", lookalike))
        assertEquals("paypal.com-login.evil.example", display("pay", lookalike))
        // And the moment a host IS named, the rule takes over.
        assertNull(display("paypal.", lookalike))
    }

    @Test
    fun `a scheme we would not navigate to is never completed`() {
        // `rankMatches` keeps only entries with an http(s) host; this gated on a scheme being
        // PRESENT instead. `canonicalUrlKey` keeps a `file:` scheme, so `canonicalAuthority`
        // read `file:` off the entry and typing "fil" ghosted `file:` targeting `file://`.
        assertNull(display("fil", history("file:///Users/me/notes.html")))
        assertNull(display("jav", history("javascript:alert(1)")))
    }

    @Test
    fun `a typed host still completes its own paths`() {
        // The rule is about the HOST changing, not about refusing to help.
        val suggestions = history("https://github.com/risa-labs-inc/BossConsole/pulls")

        assertEquals(
            "github.com/risa-labs-inc/BossConsole/pulls",
            display("github.com/", suggestions),
        )
    }

    @Test
    fun `accepting navigates to the address history stored, not the canonical one`() {
        // The display is canonical so it lines up with what the user types; the target has
        // to be what history recorded, or `processUrlInput` re-derives a scheme of its own.
        val intranet = inlineUrlCompletion("192", history("http://192.168.4.20:8123/lovelace"))
        assertEquals("192.168.4.20:8123", intranet?.display)
        assertEquals("http://192.168.4.20:8123", intranet?.target)

        // `www.` is stripped for display and kept for navigation - dropping it opens a host
        // the certificate may not cover.
        val www = inlineUrlCompletion("exa", history("https://www.example.com/x"))
        assertEquals("example.com", www?.display)
        assertEquals("https://www.example.com", www?.target)
    }

    @Test
    fun `a candidate with a query string is not offered`() {
        // A stored OAuth URL is hundreds of characters of dead state parameters, and its
        // tail would be longer than the field.
        val oauth = history("https://accounts.google.com/o/oauth2/v2/auth?client_id=x&state=y")

        assertNull(display("accounts.google.com/o", oauth))
    }

    @Test
    fun `a query string disqualifies the address, not the host it is on`() {
        // Both reasons for refusing it - a tail longer than the field, and replaying an
        // expired request - are about the full address. Applied to the entry instead, the
        // sole `accounts.google.com` visit being an OAuth URL meant typing "acc" offered
        // nothing at all.
        val oauth = history("https://accounts.google.com/o/oauth2/v2/auth?client_id=x&state=y")

        assertEquals("accounts.google.com", display("acc", oauth))
    }

    @Test
    fun `userinfo in a stored URL never reaches the field`() {
        // `canonicalUrlKey` keeps a `user@`, while `java.net.URL` reads the host as what
        // follows it - so a single drive-by visit to this would put `github.com@…` one Tab
        // away from a typed "git", and Enter would open evil.example.
        val spoof = history("https://github.com@evil.example/")

        assertNull(display("git", spoof))
        assertNull(display("github.com", spoof))
    }

    @Test
    fun `a typed scheme still completes`() {
        // `https://git` carries a `:`, which used to read as "a host has been named" and
        // turned the ghost off entirely - while the dropdown, which canonicalises a pasted
        // query, found the entry. The two halves disagreed on the easiest input to compare.
        val suggestions = history("https://github.com/")

        assertEquals("https://github.com", display("https://git", suggestions))
        assertEquals("http://github.com", display("http://git", suggestions))
        // And the security rule survives the normalization: a scheme does not reopen the
        // host once the typed text names one.
        assertNull(display("https://paypal.com", history("https://paypal.com-login.evil.example/")))
    }

    @Test
    fun `a typed scheme completes a path under the typed host`() {
        val suggestions = history("https://github.com/risa-labs-inc/BossConsole/pulls")

        assertEquals(
            "https://github.com/risa-labs-inc/BossConsole/pulls",
            display("https://github.com/risa", suggestions),
        )
    }

    @Test
    fun `the navigation guard uses the same rule that built the display`() {
        // `urlCompletionTarget` exists to keep the drawn text and the opened address
        // together, so it cannot certify a string the completion rules would have refused:
        // the path is case-SENSITIVE on both sides.
        val deep = UrlCompletion("github.com/BossConsole/pulls", "https://github.com/BossConsole/pulls")

        assertEquals(deep, urlCompletionTarget(deep, "github.com/Boss"))
        assertNull(urlCompletionTarget(deep, "github.com/boss"))
        // The host stays case-insensitive, which is what a host is.
        assertEquals(deep, urlCompletionTarget(deep, "GitHub.com/Boss"))
    }

    @Test
    fun `a typed scheme with a trailing slash does not double the slash`() {
        // `canonicalUrlKey` trims a trailing slash as well as stripping the scheme, so
        // subtracting the canonical LENGTH from the typed text spliced the candidate in one
        // character early: `https://github.com/` completed to `https://github.com//risa-...`.
        // Cosmetic in the ghost, permanent once Tab wrote it into the field.
        //
        // Every earlier typed-scheme case ended without a slash and every trailing-slash case
        // had no scheme - which is exactly the pair where the canonical form IS the typed
        // text with a prefix removed, so nothing caught it.
        val deep = history("https://github.com/risa-labs-inc/BossConsole/pulls")

        assertEquals("https://github.com/risa-labs-inc/BossConsole/pulls", display("https://github.com/", deep))
        assertEquals(
            "https://www.github.com/risa-labs-inc/BossConsole/pulls",
            display("https://www.github.com/", deep),
        )
        assertEquals(
            "https://github.com/risa-labs-inc/BossConsole/pulls",
            display("https://github.com/risa-labs-inc/", deep),
        )
    }

    @Test
    fun `a one-character host after a scheme still completes`() {
        // The canonical part was LOCATED with `indexOf`, which finds the first occurrence -
        // and a short canonical form occurs inside the prefix that was stripped to make it.
        // `https://s` found the `s` of `https` at index 4, so the tail became `s://s`, and
        // since no canonical address contains `://` the ghost went dark on exactly the input
        // the typed-scheme rule exists for. Every earlier test used `https://git`, where the
        // first occurrence happens to be the right one.
        val sites = history("https://stackoverflow.com/", "https://www.wikipedia.org/")

        assertEquals("https://stackoverflow.com", display("https://s", sites))
        assertEquals("http://stackoverflow.com", display("http://s", sites))
        // The same trap one level in: `w` occurs in the `www.` that canonicalisation strips.
        assertEquals("https://www.wikipedia.org", display("https://www.w", sites))
        assertEquals("https://www.wikipedia.org", display("https://www.wi", sites))
    }

    @Test
    fun `an address too long to read is not offered`() {
        // The query-string rule argues from length as much as from replaying a request, and a
        // path-only URL reaches the same lengths without a `?` in it. A ghost longer than the
        // single-line field it is drawn in is not a proposal anyone can check before Tab.
        val long = history("https://example.com/" + "segment/".repeat(30))

        assertNull(display("example.com/", long))
        // A deep path that still fits is unaffected.
        assertEquals("example.com/a/b/c", display("example.com/a", history("https://example.com/a/b/c")))
    }

    @Test
    fun `a typed fragment completes nothing rather than splicing past it`() {
        // A canonical address never carries a fragment, so there is nothing under `#y` to
        // extend. Subtracting the canonical length spliced the candidate's tail in AFTER the
        // fragment, producing an address that exists nowhere.
        val deep = history("https://github.com/x/y")

        assertNull(display("https://github.com/x#y", deep))
    }

    @Test
    fun `a host completion drops a stored fragment from its target`() {
        // `storedAuthority` cut at the path and the query but not the fragment, and an entry
        // with no path has nothing else to cut at - so a completion DISPLAYING `example.com`
        // targeted `https://example.com#x`.
        val fragmentOnly = history("https://example.com#x")
        val completion = inlineUrlCompletion("exa", fragmentOnly)

        assertEquals("example.com", completion?.display)
        assertEquals("https://example.com", completion?.target)
    }

    @Test
    fun `a fragment stays on the target and off the display`() {
        // `canonicalUrlKey` strips the fragment, so the ghost draws the address without it
        // while the target keeps the spelling history recorded. Dropping it from the target
        // instead would open the wrong view of a hash-routed app - this is the third way
        // display and target differ, after the scheme and `www.`.
        val hashRouted = history("https://app.example.com/x#/settings")
        val completion = inlineUrlCompletion("app.example.com/", hashRouted)

        assertEquals("app.example.com/x", completion?.display)
        assertEquals("https://app.example.com/x#/settings", completion?.target)
    }

    @Test
    fun `candidates carrying invisible characters are refused`() {
        // A bidi override in a stored path can reorder the whole rendered line, so the
        // address the user reads is not the address Enter opens.
        val bidi = history("https://github.com/a\u202Eb")

        assertNull(display("github.com/a", bidi))
    }
}
