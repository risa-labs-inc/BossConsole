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
        assertEquals("availity.com", BrowserAnalytics.registrableDomain("portal.availity.com"))
        assertEquals("availity.com", BrowserAnalytics.registrableDomain("a.b.c.availity.com"))
        assertEquals("availity.com", BrowserAnalytics.registrableDomain("availity.com"))
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
        assertEquals("availity.com", BrowserAnalytics.registrableDomain("PORTAL.Availity.COM"))
        assertEquals("availity.com", BrowserAnalytics.registrableDomain("availity.com."))
    }

    @Test
    fun `a whole url handed in is still reduced to just the domain`() {
        // Callers pass an authority, but this is the privacy boundary — it must hold even
        // when misused. Before hardening, "com/auth?patient=12345678" became the last label
        // and the query string was returned verbatim.
        assertEquals(
            "availity.com",
            BrowserAnalytics.registrableDomain("https://portal.availity.com/auth?patient=12345678"),
        )
        assertEquals("availity.com", BrowserAnalytics.registrableDomain("portal.availity.com/auth"))
        assertEquals("availity.com", BrowserAnalytics.registrableDomain("availity.com#frag"))
    }

    @Test
    fun `strips credentials embedded in an authority`() {
        assertEquals("availity.com", BrowserAnalytics.registrableDomain("user:pw@portal.availity.com"))
    }

    @Test
    fun `no reduction ever carries a path query or fragment`() {
        val inputs =
            listOf(
                "https://portal.availity.com/auth?patient=12345678",
                "portal.availity.com/a/b/c",
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
        assertNull(BrowserAnalytics.sanitizeToken("Patient Smith, John", 32))
        assertNull(BrowserAnalytics.sanitizeToken("button#patient-4417", 32))
        assertNull(BrowserAnalytics.sanitizeToken("mrn: 88421", 32))
        assertNull(BrowserAnalytics.sanitizeToken("", 32))
        assertNull(BrowserAnalytics.sanitizeToken("   ", 32))
        assertNull(BrowserAnalytics.sanitizeToken(null, 32))
        assertNull(BrowserAnalytics.sanitizeToken("a".repeat(33), 32))
    }

    @Test
    fun `structural tokens are ASCII-only, not merely lowercase`() {
        // Char.isLowerCase()/isDigit() are Unicode-aware, so a charset check written with
        // them accepts a 32-character run of any script — free text in every locale but
        // English, wearing a tag's name. These are the exact strings that slipped through.
        assertNull(BrowserAnalytics.sanitizeToken("пациентиванов", 32))
        assertNull(BrowserAnalytics.sanitizeToken("患者情報", 32))
        assertNull(BrowserAnalytics.sanitizeToken("٤٤١٧٨٨٢", 32))
        assertNull(BrowserAnalytics.sanitizeToken("mrn٤٤١٧", 32))
        // The real vocabulary still passes.
        assertEquals("button", BrowserAnalytics.sanitizeToken("button", 32))
    }

    @Test
    fun `field names are ASCII-only, so non-latin digits cannot evade redaction`() {
        // The filter used Unicode isLetterOrDigit() while the redactor used \d, which is
        // ASCII-only in Java. Arabic-Indic digits therefore passed the filter AND the
        // redactor untouched. Both halves have to agree on an alphabet.
        assertEquals("mrn-", BrowserAnalytics.sanitizeFieldName("mrn-٤٤١٧٨٨٢"))
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
        assertEquals("select_patient_#", BrowserAnalytics.sanitizeFieldName("select_patient_4417"))
        assertEquals("mrn-#", BrowserAnalytics.sanitizeFieldName("mrn-4417882"))
        // Ordinary short numeric suffixes are schema, not data, and survive.
        assertEquals("address_line[2]", BrowserAnalytics.sanitizeFieldName("address_line[2]"))
        assertEquals("line1", BrowserAnalytics.sanitizeFieldName("line1"))
        assertEquals("col22", BrowserAnalytics.sanitizeFieldName("col22"))
    }

    @Test
    fun `field names keep the schema and lose the identifier`() {
        assertEquals("patientMrn", BrowserAnalytics.sanitizeFieldName("patientMrn"))
        assertEquals("address_line[2]", BrowserAnalytics.sanitizeFieldName("address_line[2]"))
        // A generated form can bake a record id into the field name; the name survives so
        // the field is still identifiable, the number does not.
        assertEquals("mrn-#", BrowserAnalytics.sanitizeFieldName("mrn-4417882"))
        assertEquals("dob", BrowserAnalytics.sanitizeFieldName("  dob  "))
    }

    @Test
    fun `field names drop unexpected characters and empties`() {
        // ACCEPTED RESIDUAL RISK, not an endorsement. Filtering rather than refusing means a
        // space is removed, which is what makes "John Smith" come out looking like a valid
        // field name — and the digit redaction does nothing for alphabetic PHI. A real
        // `name=` attribute essentially never contains a space, so refusing outright (as
        // sanitizeToken does) would drop it instead. Knowingly deferred; tracked privately in
        // boss-plugin-analytics#7. Do not read this assertion as "this output is desirable".
        assertEquals("JohnSmith", BrowserAnalytics.sanitizeFieldName("John Smith"))
        assertNull(BrowserAnalytics.sanitizeFieldName("   "))
        assertNull(BrowserAnalytics.sanitizeFieldName(null))
        assertNull(BrowserAnalytics.sanitizeFieldName("@@@"))
        assertEquals(64, BrowserAnalytics.sanitizeFieldName("n".repeat(200))?.length)
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
        assertNull(BrowserAnalytics.sanitizePath("form>div#patient-4417>button"))
        assertNull(BrowserAnalytics.sanitizePath("form>div.patient-name>button"))
        assertNull(BrowserAnalytics.sanitizePath("input[value='John Smith']"))
        assertNull(BrowserAnalytics.sanitizePath("form>div:2>"))
        assertNull(BrowserAnalytics.sanitizePath(""))
        assertNull(BrowserAnalytics.sanitizePath(null))
        assertNull(BrowserAnalytics.sanitizePath("a>".repeat(100)))
    }
}
