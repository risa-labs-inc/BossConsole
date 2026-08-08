package ai.rever.boss.plugin.browser

import ai.rever.boss.components.plugin.providers.publishSystemEvent
import ai.rever.boss.plugin.api.BrowserEvent
import ai.rever.boss.plugin.api.BrowserEventType
import ai.rever.boss.plugin.api.BrowserInteractionEvent
import ai.rever.boss.plugin.api.BrowserInteractionType
import ai.rever.boss.plugin.api.BrowserNavigationType

/**
 * Publishes browser activity onto the application event bus so analytics consumers can
 * see which sites BOSS is used with and how they are used.
 *
 * **This is the privacy boundary for browser telemetry.** A full URL is passed *in* and
 * only a registrable domain goes *out* — the path, query string, fragment, and page title
 * are discarded here and never reach [BrowserEvent], the event bus, or any plugin.
 * Reducing at the source is deliberate: it means no downstream consumer can leak page-level
 * detail even by accident, because the detail was never handed to it.
 *
 * In-page interactions ([interaction]) get the same treatment one layer further in. The
 * injected collector is written to read only structural attributes, and everything it
 * sends is re-validated here against [sanitizeToken] / [sanitizeFieldName] / [sanitizePath]
 * before an event exists. Two independent passes, because the page is hostile territory:
 * a site controls its own DOM and can name an input whatever it likes.
 *
 * BOSS is used in healthcare contexts. Widening this to emit a URL, path, title, element
 * text, or input value is a privacy decision, not a refactor — see the analytics plugin's
 * `CLAUDE.md`.
 */
internal object BrowserAnalytics {
    /**
     * Record a successfully-loaded page view for [authority] (a host, optionally with a
     * port, as produced by `suggestableHost`).
     *
     * Silently does nothing for hosts that aren't meaningful sites (loopback, bare IPs,
     * single-label intranet names) — see [registrableDomain].
     */
    fun pageViewed(
        authority: String,
        navigationType: BrowserNavigationType? = null,
        pageIndexInVisit: Int? = null,
        windowId: String? = null,
    ) {
        val domain = registrableDomain(authority) ?: return
        publishSystemEvent(
            BrowserEvent(
                browserEventType = BrowserEventType.PAGE_VIEWED,
                domain = domain,
                windowId = windowId,
                navigationType = navigationType,
                pageIndexInVisit = pageIndexInVisit,
            ),
        )
    }

    /**
     * Record the end of a page visit: [dwellMs] wall-clock, of which [activeMs] was spent
     * focused. Negative or absurd durations are dropped rather than reported — a clock
     * change or a resume-from-sleep can produce either, and a bogus multi-day dwell would
     * quietly poison every engagement average built on top of it.
     */
    fun pageLeft(
        authority: String,
        dwellMs: Long,
        activeMs: Long,
        windowId: String? = null,
    ) {
        val domain = registrableDomain(authority) ?: return
        if (dwellMs < 0 || activeMs < 0 || dwellMs > MAX_REPORTABLE_DWELL_MS) return
        publishSystemEvent(
            BrowserEvent(
                browserEventType = BrowserEventType.PAGE_LEFT,
                domain = domain,
                windowId = windowId,
                dwellMs = dwellMs,
                // Active time cannot exceed wall-clock time; clamp rather than drop, since
                // a small overshoot is just accounting drift between the two counters.
                activeMs = minOf(activeMs, dwellMs),
            ),
        )
    }

    /** Record a browser tab lifecycle change. [authority] may be blank for a new empty tab. */
    fun tabEvent(
        type: BrowserEventType,
        authority: String?,
        windowId: String? = null,
    ) {
        val domain = authority?.let { registrableDomain(it) } ?: BLANK_TAB_DOMAIN
        publishSystemEvent(BrowserEvent(browserEventType = type, domain = domain, windowId = windowId))
    }

    /**
     * Record an in-page interaction. Every caller-supplied field is sanitized here; a field
     * that fails validation is dropped to null rather than rejecting the whole event, so
     * one odd attribute cannot cost the interaction signal.
     */
    @Suppress("LongParameterList")
    fun interaction(
        type: BrowserInteractionType,
        authority: String,
        elementTag: String? = null,
        elementRole: String? = null,
        inputType: String? = null,
        fieldName: String? = null,
        elementPath: String? = null,
        scrollDepthPercent: Int? = null,
        repeatCount: Int? = null,
        windowId: String? = null,
    ) {
        val domain = registrableDomain(authority) ?: return
        publishSystemEvent(
            BrowserInteractionEvent(
                interactionType = type,
                domain = domain,
                elementTag = sanitizeToken(elementTag, MAX_TAG_LENGTH),
                elementRole = sanitizeToken(elementRole, MAX_TAG_LENGTH),
                inputType = sanitizeToken(inputType, MAX_TAG_LENGTH),
                fieldName = sanitizeFieldName(fieldName),
                elementPath = sanitizePath(elementPath),
                scrollDepthPercent = scrollDepthPercent?.takeIf { it in 0..100 },
                repeatCount = repeatCount?.takeIf { it in 1..MAX_REPEAT_COUNT },
                windowId = windowId,
            ),
        )
    }

    /**
     * A structural token (tag, ARIA role, input type). These come from a fixed HTML
     * vocabulary, so anything outside `[a-z0-9-]` is a page doing something unexpected and
     * is refused outright rather than trimmed — a value that needed cleaning was not a tag
     * name, and guessing what it *was* is how content leaks through.
     *
     * The range checks are **explicitly ASCII, not `Char.isLowerCase()`/`isDigit()`**. Those
     * delegate to `Character.*`, which is Unicode-aware: `isLowerCase` is true for Cyrillic,
     * Greek and Arabic-script letters, and `isDigit` covers the whole `Nd` category. Written
     * that way, this function called itself a structural-vocabulary check while accepting a
     * 32-character run of any script — free text in every locale but English.
     */
    internal fun sanitizeToken(
        raw: String?,
        maxLength: Int,
    ): String? =
        raw
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() && it.length <= maxLength }
            ?.takeIf { value -> value.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '-' } }

    /**
     * A form field's `name` attribute. Unlike a tag this is developer-chosen free text, so
     * it is cleaned rather than refused: unexpected characters are dropped and short digit
     * runs redacted, on the theory that a name is `patientMrn` (a schema label, safe) but
     * could be `mrn-4417882` (an identifier baked into a generated form, not safe).
     *
     * ASCII-only for the same reason as [sanitizeToken], and here the mismatch was worse:
     * filtering with the Unicode-aware `isLetterOrDigit()` while redacting with `\d`, which
     * is ASCII-only in Java unless `UNICODE_CHARACTER_CLASS` is set, let `mrn٤٤١٧٨٨٢` pass
     * the filter *and* the redactor untouched. Both halves must agree on an alphabet.
     */
    internal fun sanitizeFieldName(raw: String?): String? =
        raw
            ?.trim()
            ?.take(MAX_FIELD_NAME_LENGTH)
            ?.filter { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in FIELD_NAME_PUNCTUATION }
            ?.takeIf { it.isNotEmpty() }
            ?.let { DIGIT_RUN.replace(it, "#") }

    /**
     * A structural element path: tag names and sibling positions only, e.g.
     * `form>div:2>button:1`. Rejected wholesale if it contains anything else, because the
     * only way to build a path with a `#`, `.`, or quote in it is to have included an id,
     * class, or attribute selector — which is exactly what must not be here.
     */
    internal fun sanitizePath(raw: String?): String? =
        raw
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_PATH_LENGTH }
            ?.takeIf { PATH_SHAPE.matches(it) }

    /** Longest visit we are willing to call real; beyond this the clock is suspect. */
    private const val MAX_REPORTABLE_DWELL_MS = 12L * 60 * 60 * 1000
    private const val MAX_TAG_LENGTH = 32
    private const val MAX_FIELD_NAME_LENGTH = 64
    private const val MAX_PATH_LENGTH = 120
    private const val MAX_REPEAT_COUNT = 100

    /** Stand-in domain for a tab with no site loaded, so tab counts still balance. */
    internal const val BLANK_TAB_DOMAIN = "about:blank"

    /** Punctuation a form field name may keep — array/object syntax and word separators. */
    private val FIELD_NAME_PUNCTUATION = setOf('_', '-', '.', '[', ']')

    /**
     * Three digits, not five.
     *
     * Five only catches long generated ids. A record number in a form field is routinely
     * four (`select_patient_4417`), and `BrowserInteractionScript`'s own KDoc names exactly
     * that shape as what must not escape — so the threshold and the stated intent disagreed.
     * Three costs almost nothing: `address_line[2]`, `line1`, and `col2` are one or two
     * digits and survive intact.
     */
    private val DIGIT_RUN = Regex("""\d{3,}""")
    private val PATH_SHAPE = Regex("""[a-z0-9-]+(:\d+)?(>[a-z0-9-]+(:\d+)?)*""")

    /**
     * Reduce an authority to its registrable domain (eTLD+1), or null when there is
     * nothing worth reporting.
     *
     * `portal.availity.com:443` → `availity.com`, `bbc.co.uk` → `bbc.co.uk`.
     *
     * Collapsing subdomains is the point: a subdomain is often more identifying than the
     * site itself (`patient-portal.smallclinic.com` names a workflow, not just a vendor).
     *
     * This uses a small table of common multi-label suffixes rather than the full Public
     * Suffix List — pulling in a PSL dependency for telemetry isn't worth it. The failure
     * mode is conservative in the wrong direction for exotic suffixes (`example.pvt.k12.ma.us`
     * reduces to `ma.us`), which over-collapses rather than over-reports.
     */
    // Each `return null` is a distinct category of thing we refuse to report. Collapsing them
    // into one exit would obscure exactly the list a reader of a privacy boundary comes for.
    @Suppress("ReturnCount")
    internal fun registrableDomain(authority: String): String? {
        var trimmed = authority.trim().lowercase()

        // Callers are expected to pass an authority, but this function is the privacy
        // boundary — it must not depend on that. Drop any scheme and cut at the first
        // path/query/fragment delimiter, so handing it a whole URL can never smuggle a
        // path or query string out through the last label.
        trimmed = trimmed.substringAfter("://")
        trimmed = trimmed.takeWhile { it != '/' && it != '?' && it != '#' }
        // Credentials in an authority ("user:pw@host") are never reportable.
        trimmed = trimmed.substringAfterLast('@')

        // IPv6 literals arrive bracketed ("[::1]:3000"); never report an address.
        if (trimmed.startsWith("[")) return null

        val host = trimmed.substringBefore(':').removeSuffix(".")
        if (host.isEmpty()) return null
        if (host == "localhost" || host.endsWith(".localhost")) return null

        // Internationalised names reach a browser URL already punycoded (`xn--…`), so a host
        // with a non-ASCII character is not a name the browser resolved. Refuse it rather
        // than reason about it — and note this must come BEFORE the IPv4 check to be safe,
        // not after. Making that check ASCII-only for consistency with the sanitizers would
        // invert its meaning: `١٢٧.٠.٠.١` would stop being recognised as an address and be
        // reported as the "site" `٠.١`. Here, unlike in the sanitizers, the Unicode-aware
        // test is the one that refuses more, so the guard belongs upstream of it.
        if (host.any { it.code > 127 }) return null

        val labels = host.split('.').filter { it.isNotEmpty() }
        // Single-label hosts are intranet machine names, not sites.
        if (labels.size < 2) return null
        // An IPv4 literal is an address, not a site.
        if (labels.size == 4 && labels.all { l -> l.all { c -> c in '0'..'9' } }) return null

        val lastTwo = labels.takeLast(2).joinToString(".")
        return if (labels.size >= 3 && lastTwo in MULTI_LABEL_SUFFIXES) {
            labels.takeLast(3).joinToString(".")
        } else {
            lastTwo
        }
    }

    /**
     * Two-label public suffixes common enough to matter. Without these, `bbc.co.uk` would
     * reduce to the meaningless `co.uk` and every UK site would collapse together.
     */
    private val MULTI_LABEL_SUFFIXES =
        setOf(
            "co.uk",
            "org.uk",
            "ac.uk",
            "gov.uk",
            "net.uk",
            "me.uk",
            "com.au",
            "net.au",
            "org.au",
            "edu.au",
            "gov.au",
            "co.jp",
            "or.jp",
            "ne.jp",
            "ac.jp",
            "go.jp",
            "co.nz",
            "org.nz",
            "govt.nz",
            "co.za",
            "org.za",
            "co.in",
            "net.in",
            "org.in",
            "com.br",
            "com.mx",
            "com.ar",
            "com.sg",
            "com.tr",
            "com.cn",
            "com.hk",
            "com.tw",
            "com.my",
            "com.ph",
            "com.pk",
            "co.kr",
            "or.kr",
        )
}
