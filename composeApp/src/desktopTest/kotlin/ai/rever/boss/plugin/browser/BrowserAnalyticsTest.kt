package ai.rever.boss.plugin.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [BrowserAnalytics.registrableDomain] is the privacy boundary for browser telemetry:
 * whatever it returns is what leaves the device. These pin both halves of that contract —
 * that it reduces far enough, and that it refuses to report things that aren't sites.
 */
class BrowserAnalyticsTest {
    @Test
    fun `reduces a subdomain to the registrable domain`() {
        // The whole point: a subdomain often names a workflow, not just a vendor.
        assertEquals("acmecorp.com", BrowserAnalytics.registrableDomain("portal.acmecorp.com"))
        assertEquals("acmecorp.com", BrowserAnalytics.registrableDomain("a.b.c.acmecorp.com"))
        assertEquals("acmecorp.com", BrowserAnalytics.registrableDomain("acmecorp.com"))
    }

    @Test
    fun `strips the port`() {
        assertEquals("example.com", BrowserAnalytics.registrableDomain("example.com:8443"))
    }

    @Test
    fun `keeps three labels for multi-label public suffixes`() {
        // Without the suffix table these would all collapse to "co.uk" / "com.au",
        // merging every UK and Australian site into one bucket.
        assertEquals("bbc.co.uk", BrowserAnalytics.registrableDomain("www.bbc.co.uk"))
        assertEquals("bbc.co.uk", BrowserAnalytics.registrableDomain("news.bbc.co.uk"))
        assertEquals("telstra.com.au", BrowserAnalytics.registrableDomain("my.telstra.com.au"))
    }

    @Test
    fun `refuses loopback and dev servers`() {
        assertNull(BrowserAnalytics.registrableDomain("localhost"))
        assertNull(BrowserAnalytics.registrableDomain("localhost:3000"))
        assertNull(BrowserAnalytics.registrableDomain("app.localhost"))
    }

    @Test
    fun `refuses bare IP addresses`() {
        // An address is not a site, and a private one says nothing useful.
        assertNull(BrowserAnalytics.registrableDomain("127.0.0.1"))
        assertNull(BrowserAnalytics.registrableDomain("192.168.1.20:8080"))
        assertNull(BrowserAnalytics.registrableDomain("[::1]:3000"))
        assertNull(BrowserAnalytics.registrableDomain("[2001:db8::1]"))
    }

    @Test
    fun `refuses a short-form address, not only a four-label one`() {
        // Chromium canonicalises 127.1 to 127.0.0.1 before this ever sees it, but this
        // function is documented as holding under misuse - and a four-label-only check
        // answered "127.1" with the site 127.1 and "10.0.1" with the site 0.1.
        assertNull(BrowserAnalytics.registrableDomain("127.1"))
        assertNull(BrowserAnalytics.registrableDomain("10.0.1"))
        assertNull(BrowserAnalytics.registrableDomain("2130706433"))
        // A domain that merely starts with digits is still a domain.
        assertEquals("7eleven.com", BrowserAnalytics.registrableDomain("www.7eleven.com"))
    }

    @Test
    fun `the kill switch understands more than the exact string true`() {
        // Every one of these is documented behaviour that reading System.getenv inline left
        // untested. An operator who writes =1 and still gets full telemetry has no signal
        // at all, which is the wrong direction for a privacy control to fail in.
        for (off in listOf("true", "TRUE", " True ", "1", "yes", "on")) {
            assertEquals(false, BrowserAnalytics.telemetryEnabledFrom(env = off, property = null), off)
        }
        for (on in listOf(null, "", "  ", "false", "0", "no", "off", "maybe")) {
            assertEquals(true, BrowserAnalytics.telemetryEnabledFrom(env = on, property = null), "$on")
        }
    }

    @Test
    fun `the kill switch falls back to the system property`() {
        assertEquals(false, BrowserAnalytics.telemetryEnabledFrom(env = null, property = "true"))
        // An env var set to the empty string is non-null, so without the blank check
        // `BOSS_BROWSER_TELEMETRY_DISABLED=` - a common way to "unset" one in a launcher
        // script - silently shadowed the property and re-enabled telemetry.
        assertEquals(false, BrowserAnalytics.telemetryEnabledFrom(env = "", property = "true"))
        // An env var that says something still wins over the property.
        assertEquals(true, BrowserAnalytics.telemetryEnabledFrom(env = "false", property = "true"))
    }

    @Test
    fun `refuses a host containing non-ASCII characters`() {
        // Internationalised names arrive from the browser already punycoded, so a non-ASCII
        // host is not something it resolved. This guard is also what lets the IPv4 check be
        // ASCII: without it, "١٢٧.٠.٠.١" would dodge that check and be reported as the
        // "site" "٠.١" — the one place where tightening to ASCII would have loosened the
        // boundary rather than closed it.
        assertNull(BrowserAnalytics.registrableDomain("١٢٧.٠.٠.١"))
        assertNull(BrowserAnalytics.registrableDomain("пациент.example.com"))
        assertNull(BrowserAnalytics.registrableDomain("例え.jp"))
        // Punycode is ASCII, so genuine international sites still report.
        assertEquals("xn--80ak6aa92e.com", BrowserAnalytics.registrableDomain("xn--80ak6aa92e.com"))
    }

    @Test
    fun `refuses single-label intranet names and empty input`() {
        assertNull(BrowserAnalytics.registrableDomain("intranet"))
        assertNull(BrowserAnalytics.registrableDomain(""))
        assertNull(BrowserAnalytics.registrableDomain("   "))
    }

    @Test
    fun `normalizes case and a trailing root dot`() {
        assertEquals("acmecorp.com", BrowserAnalytics.registrableDomain("PORTAL.Acmecorp.COM"))
        assertEquals("acmecorp.com", BrowserAnalytics.registrableDomain("acmecorp.com."))
    }

    @Test
    fun `a whole url handed in is still reduced to just the domain`() {
        // Callers pass an authority, but this is the privacy boundary — it must hold even
        // when misused. Before hardening, "com/auth?account=12345678" became the last label
        // and the query string was returned verbatim.
        assertEquals(
            "acmecorp.com",
            BrowserAnalytics.registrableDomain("https://portal.acmecorp.com/auth?account=12345678"),
        )
        assertEquals("acmecorp.com", BrowserAnalytics.registrableDomain("portal.acmecorp.com/auth"))
        assertEquals("acmecorp.com", BrowserAnalytics.registrableDomain("acmecorp.com#frag"))
    }

    @Test
    fun `a url smuggled through the query cannot become the reported site`() {
        // substringAfter("://") took everything after the FIRST occurrence anywhere, so a
        // schemeless authority carrying a URL in its query resolved to that URL's host - the
        // exact smuggling this function's KDoc says cannot happen. A scheme is only a scheme
        // at position zero.
        assertEquals("acmecorp.com", BrowserAnalytics.registrableDomain("acmecorp.com/r?u=https://evil.com"))
        assertEquals("acmecorp.com", BrowserAnalytics.registrableDomain("acmecorp.com?next=http://evil.com"))
        // The well-formed form still works, which is what made the bug invisible.
        assertEquals("acmecorp.com", BrowserAnalytics.registrableDomain("https://portal.acmecorp.com/auth"))
    }

    @Test
    fun `a suffix absent from the table over-collapses rather than over-reports`() {
        // The table is deliberately not the full Public Suffix List, and the KDoc calls the
        // failure direction conservative. Pinning one case stops a later "let me add more
        // suffixes" edit from quietly inverting that.
        assertEquals("sch.uk", BrowserAnalytics.registrableDomain("portal.branchsite.sch.uk"))
    }

    @Test
    fun `strips credentials embedded in an authority`() {
        assertEquals("acmecorp.com", BrowserAnalytics.registrableDomain("user:pw@portal.acmecorp.com"))
    }

    @Test
    fun `no reduction ever carries a path query or fragment`() {
        val inputs =
            listOf(
                "https://portal.acmecorp.com/auth?account=12345678",
                "portal.acmecorp.com/a/b/c",
                "bbc.co.uk/news?id=99",
                "example.com:8443/x#y",
            )
        for (input in inputs) {
            val result = BrowserAnalytics.registrableDomain(input)
            assertEquals(
                null,
                result?.takeIf { it.any { c -> c == '/' || c == '?' || c == '#' || c == '@' } },
                "leaked page detail for $input -> $result",
            )
        }
    }

    // ============================================================
    // In-page interaction sanitizers — the second privacy boundary.
    // The injected collector is written never to read text, values, labels, or ids out of
    // the DOM. These pin the independent host-side check on what it does send, because a
    // site controls its own markup and can name things whatever it likes.
    // ============================================================

    @Test
    fun `structural tokens accept the html vocabulary`() {
        assertEquals("button", BrowserAnalytics.sanitizeToken("BUTTON", 32))
        assertEquals("input", BrowserAnalytics.sanitizeToken(" input ", 32))
        assertEquals("menuitem", BrowserAnalytics.sanitizeToken("menuitem", 32))
        assertEquals("my-widget", BrowserAnalytics.sanitizeToken("my-widget", 32))
    }

    @Test
    fun `a structural token carrying anything but a tag is refused whole`() {
        // Refused, not cleaned: a "tag" needing repair was never a tag, and salvaging a
        // prefix out of it is how page content would arrive wearing a tag's name.
        assertNull(BrowserAnalytics.sanitizeToken("Account Smith, John", 32))
        assertNull(BrowserAnalytics.sanitizeToken("button#account-4417", 32))
        assertNull(BrowserAnalytics.sanitizeToken("ref: 88421", 32))
        assertNull(BrowserAnalytics.sanitizeToken("", 32))
        assertNull(BrowserAnalytics.sanitizeToken("   ", 32))
        assertNull(BrowserAnalytics.sanitizeToken(null, 32))
        assertNull(BrowserAnalytics.sanitizeToken("a".repeat(33), 32))
    }

    @Test
    fun `an identifier is redacted whichever field it arrives in`() {
        // The digit redaction used to apply to fieldName alone, so the same record number
        // walked straight through a different slot: a custom element <row-4417882> is
        // lowercase, matches the token charset and is short, so it was emitted verbatim. A
        // tag and an ARIA role are as author-chosen as a name= attribute.
        assertEquals("row-#", BrowserAnalytics.sanitizeToken("row-4417882", 32))
        assertEquals("ref-#", BrowserAnalytics.sanitizeToken("REF-4417882", 32))
        assertEquals("account_ref_#", BrowserAnalytics.sanitizeFieldName("account_ref_4417882"))
        // Real structural vocabulary is untouched, including short numeric suffixes.
        assertEquals("button", BrowserAnalytics.sanitizeToken("button", 32))
        assertEquals("h2", BrowserAnalytics.sanitizeToken("h2", 32))
    }

    @Test
    fun `a path redacts its tag segments but keeps the sibling ordinals`() {
        // The ordinals after ':' are structure, not identity - a path with them redacted
        // would stop distinguishing the third row from the fortieth, which is the whole
        // point of recording a path at all.
        assertEquals(
            "table>tbody>tr:12>row-#",
            BrowserAnalytics.sanitizePath("table>tbody>tr:12>row-4417882"),
        )
        assertEquals("form>div:2>button:1", BrowserAnalytics.sanitizePath("form>div:2>button:1"))
    }

    @Test
    fun `structural tokens are ASCII-only, not merely lowercase`() {
        // Char.isLowerCase()/isDigit() are Unicode-aware, so a charset check written with
        // them accepts a 32-character run of any script — free text in every locale but
        // English, wearing a tag's name. These are the exact strings that slipped through.
        assertNull(BrowserAnalytics.sanitizeToken("пациентиванов", 32))
        assertNull(BrowserAnalytics.sanitizeToken("患者情報", 32))
        assertNull(BrowserAnalytics.sanitizeToken("٤٤١٧٨٨٢", 32))
        assertNull(BrowserAnalytics.sanitizeToken("ref٤٤١٧", 32))
        // The real vocabulary still passes.
        assertEquals("button", BrowserAnalytics.sanitizeToken("button", 32))
    }

    @Test
    fun `field names are ASCII-only, so non-latin digits cannot evade redaction`() {
        // The filter used Unicode isLetterOrDigit() while the redactor used \d, which is
        // ASCII-only in Java. Arabic-Indic digits therefore passed the filter AND the
        // redactor untouched. Both halves have to agree on an alphabet.
        assertEquals("ref-", BrowserAnalytics.sanitizeFieldName("ref-٤٤١٧٨٨٢"))
        assertNull(BrowserAnalytics.sanitizeFieldName("пациент"))
        assertEquals("dob", BrowserAnalytics.sanitizeFieldName("dobыф"))
    }

    @Test
    fun `element paths are ASCII-only`() {
        // PATH_SHAPE was already correct — explicit ranges and \d are ASCII in Java — but
        // pin it so a future "simplification" to \w or isLetter() is caught here.
        assertNull(BrowserAnalytics.sanitizePath("form>пациент:2>button:1"))
        assertNull(BrowserAnalytics.sanitizePath("form>div:٢>button:1"))
    }

    @Test
    fun `a short record id in a field name is redacted`() {
        // Four digits is the common shape for a record baked into a generated form, and is
        // exactly what BrowserInteractionScript's KDoc names as what must not escape.
        assertEquals("select_account_#", BrowserAnalytics.sanitizeFieldName("select_account_4417"))
        assertEquals("ref-#", BrowserAnalytics.sanitizeFieldName("ref-4417882"))
        // Ordinary short numeric suffixes are schema, not data, and survive.
        assertEquals("address_line[2]", BrowserAnalytics.sanitizeFieldName("address_line[2]"))
        assertEquals("line1", BrowserAnalytics.sanitizeFieldName("line1"))
        assertEquals("col22", BrowserAnalytics.sanitizeFieldName("col22"))
    }

    @Test
    fun `field names keep the schema and lose the identifier`() {
        assertEquals("accountRef", BrowserAnalytics.sanitizeFieldName("accountRef"))
        assertEquals("address_line[2]", BrowserAnalytics.sanitizeFieldName("address_line[2]"))
        // A generated form can bake a record id into the field name; the name survives so
        // the field is still identifiable, the number does not.
        assertEquals("ref-#", BrowserAnalytics.sanitizeFieldName("ref-4417882"))
        assertEquals("dob", BrowserAnalytics.sanitizeFieldName("  dob  "))
    }

    @Test
    fun `field names drop unexpected characters and empties`() {
        assertNull(BrowserAnalytics.sanitizeFieldName("   "))
        assertNull(BrowserAnalytics.sanitizeFieldName(null))
        assertNull(BrowserAnalytics.sanitizeFieldName("@@@"))
        assertEquals(64, BrowserAnalytics.sanitizeFieldName("n".repeat(200))?.length)
    }

    @Test
    fun `any separator refuses a two-token field name, not just whitespace`() {
        // Refusing only on whitespace was too narrow: deleting the stray character welds the
        // tokens together exactly the same way, so "Smith,John" became "SmithJohn" while
        // "John Smith" was refused - the same disclosure, two answers. The bridge is reachable
        // from any page script by design, so this is not only about well-formed name=.
        assertNull(BrowserAnalytics.sanitizeFieldName("Smith,John"))
        assertNull(BrowserAnalytics.sanitizeFieldName("John+Smith"))
        assertNull(BrowserAnalytics.sanitizeFieldName("John/Smith"))
        assertNull(BrowserAnalytics.sanitizeFieldName("John(Smith)"))
        assertNull(BrowserAnalytics.sanitizeFieldName("John:Smith"))
        // U+200B is not Char.isWhitespace, so the whitespace-only rule never saw it.
        assertNull(BrowserAnalytics.sanitizeFieldName("John​Smith"))
    }

    @Test
    fun `ASP dot NET WebForms names survive the separator rule`() {
        // `$` is in the alphabet on purpose: WebForms builds every name with it, so a flat
        // refusal would drop a whole platform's field names rather than a leak.
        assertEquals(
            "ctl00${'$'}ContentPlaceHolder1${'$'}txtAccount",
            BrowserAnalytics.sanitizeFieldName("ctl00${'$'}ContentPlaceHolder1${'$'}txtAccount"),
        )
    }

    @Test
    fun `a field name containing whitespace is refused, not compacted`() {
        // This was the one shape most likely to be a person's name, and the only sanitizer
        // that let it through: filtering a space out of "John Smith" yields "JohnSmith",
        // which is indistinguishable from a real camelCase field name, and the digit
        // redaction does nothing for alphabetic PII. A `name=` attribute is a form-encoding
        // key and essentially never contains whitespace, so refusing costs nothing real.
        assertNull(BrowserAnalytics.sanitizeFieldName("John Smith"))
        assertNull(BrowserAnalytics.sanitizeFieldName("Account: John Smith"))
        assertNull(BrowserAnalytics.sanitizeFieldName("date\tof\tbirth"))
        assertNull(BrowserAnalytics.sanitizeFieldName("first\nlast"))
        // Surrounding whitespace is markup formatting, not content, and is still trimmed.
        assertEquals("dob", BrowserAnalytics.sanitizeFieldName("  dob  "))
    }

    @Test
    fun `element paths accept tags and sibling positions only`() {
        assertEquals("form>div:2>button:1", BrowserAnalytics.sanitizePath("form>div:2>button:1"))
        assertEquals("button", BrowserAnalytics.sanitizePath("BUTTON"))
        assertEquals("main>ul>li:12>a:1", BrowserAnalytics.sanitizePath("main>ul>li:12>a:1"))
    }

    @Test
    fun `an element path containing a selector is refused`() {
        // The only way to get a '#', '.', or quote into a path is to have included an id,
        // class, or attribute selector — precisely the identifying detail excluded by design.
        assertNull(BrowserAnalytics.sanitizePath("form>div#account-4417>button"))
        assertNull(BrowserAnalytics.sanitizePath("form>div.account-name>button"))
        assertNull(BrowserAnalytics.sanitizePath("input[value='John Smith']"))
        assertNull(BrowserAnalytics.sanitizePath("form>div:2>"))
        assertNull(BrowserAnalytics.sanitizePath(""))
        assertNull(BrowserAnalytics.sanitizePath(null))
        assertNull(BrowserAnalytics.sanitizePath("a>".repeat(100)))
    }
}
