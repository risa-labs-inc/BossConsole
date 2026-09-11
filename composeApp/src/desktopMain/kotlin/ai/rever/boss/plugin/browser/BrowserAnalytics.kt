package ai.rever.boss.plugin.browser

import ai.rever.boss.components.plugin.providers.publishSystemEvent
import ai.rever.boss.plugin.api.ApplicationEvent
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
     * Host-side kill switch for browser telemetry, read once at startup.
     *
     * Consent for *sending* analytics lives in the analytics plugin, which gates egress for
     * every event source alike; this is a separate, blunter control for deployments that want
     * no browser telemetry produced at all, whatever a plugin later decides to do with it.
     *
     * It is enforced **here**, at the single point where every browser event is published,
     * rather than at the call sites. An operator who sets a variable named
     * `BOSS_BROWSER_TELEMETRY_DISABLED` means all of it — not just the in-page collector —
     * and gating each producer separately is how a later one gets added without the guard.
     * The page-side script is *additionally* not injected at all when this is off, so a
     * disabled deployment also runs no telemetry JavaScript in pages.
     *
     * `1`, `yes` and `on` disable it as surely as `true`, and a system property works as well
     * as an environment variable. Matching only the exact string `true` would hand an operator
     * who wrote `=1` a silent full-telemetry deployment, and silence is the wrong direction
     * for a privacy control to fail in. Same vocabulary as [FluckEngine.isTruthyFlag].
     *
     * A `var` purely so [BrowserAnalyticsTest] can assert the gate actually gates; nothing in
     * the app writes it, and the resolved value is what a deployment gets.
     */
    @Volatile
    internal var telemetryEnabled: Boolean =
        telemetryEnabledFrom(
            env = System.getenv(TELEMETRY_DISABLED_KEY),
            property = System.getProperty(TELEMETRY_DISABLED_PROPERTY),
        )

    /**
     * The kill switch's resolution rule, as a function of its two raw inputs so it can be
     * tested. Reading `System.getenv` inline left every documented behaviour here uncovered,
     * including a bug already hit once (see `isNotBlank` below).
     *
     * Read straight from the environment and system properties rather than through
     * `ConfigLoader`, deliberately: this is an operator-level control that must hold before
     * any settings exist, and it is never surfaced as a Settings row. (Note the asymmetry
     * `FluckEngine` warns about - `getenv` cannot see a system property - is handled here by
     * consulting both.)
     */
    internal fun telemetryEnabledFrom(
        env: String?,
        property: String?,
    ): Boolean {
        // `isNotBlank`, because an env var set to the empty string is still non-null: without
        // it, `BOSS_BROWSER_TELEMETRY_DISABLED=` (a common way to "unset" one in a launcher
        // script) silently shadowed `-Dboss.browser.telemetry.disabled=true`.
        val raw = env?.takeIf { it.isNotBlank() } ?: property
        return !FluckEngine.isTruthyFlag(raw)
    }

    private const val TELEMETRY_DISABLED_KEY = "BOSS_BROWSER_TELEMETRY_DISABLED"
    private const val TELEMETRY_DISABLED_PROPERTY = "boss.browser.telemetry.disabled"

    /** The one place a browser event reaches the bus, so the kill switch cannot be bypassed. */
    private fun publish(event: ApplicationEvent) {
        if (!telemetryEnabled) return
        // publishSystemEvent creates the bus when none exists. Because it has replay = 0, that
        // first event still has no subscriber and is discarded; later events can be observed.
        // Browser interactions are the highest-volume publisher on this bus, so this turns the
        // old null-check path into a real tryEmit even with no subscriber. That is intentional:
        // tryEmit discards immediately in that case, and the null check was never a throttle.
        // A consumer must still not read tab counts as exact.
        //
        // Nor page-view counts: pageLeft() drops a visit longer than MAX_REPORTABLE_DWELL_MS
        // rather than reporting a suspect number, so a PAGE_VIEWED for a tab left open
        // overnight has no matching PAGE_LEFT either. Both are deliberate; neither is exact.
        publishSystemEvent(event)
    }

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
        publish(
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
        publish(
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

    /**
     * Record a browser tab lifecycle change. [authority] may be null for a new empty tab.
     *
     * A tab on a host we refuse to report (loopback, a bare IP, an intranet name) is *not*
     * the same thing as a tab with nothing loaded, and reporting both as [BLANK_TAB_DOMAIN]
     * would make "new tabs opened" read high by however much a developer used a dev server.
     * [UNREPORTABLE_TAB_DOMAIN] keeps them countable and separate, at no privacy cost -
     * neither sentinel says anything about where the tab actually was.
     */
    fun tabEvent(
        type: BrowserEventType,
        authority: String?,
        windowId: String? = null,
    ) {
        val domain =
            when {
                authority.isNullOrBlank() -> BLANK_TAB_DOMAIN
                else -> registrableDomain(authority) ?: UNREPORTABLE_TAB_DOMAIN
            }
        publish(BrowserEvent(browserEventType = type, domain = domain, windowId = windowId))
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
        publish(
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
            // Redacted like a field name is. A tag and an ARIA role are as author-chosen as a
            // `name=` attribute, so the identifier this file refuses to emit through one field
            // was passing untouched through another: `<row-4417882>` is lowercase, matches the
            // charset and is short, so it was accepted verbatim. A real tag or role never
            // carries a three-digit run, so this costs no legitimate signal.
            ?.let { DIGIT_RUN.replace(it, "#") }

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
     *
     * **Anything outside the alphabet is a SEPARATOR, and more than one token refuses the
     * whole value.** Deleting stray characters is what made `"John Smith"` come out as the
     * plausible-looking field name `JohnSmith`, and the digit redaction does nothing for
     * alphabetic PHI — so the shape most likely to be a person's name was the one that
     * survived. Refusing only on whitespace was too narrow to fix that: deletion welds
     * `Smith,John`, `John+Smith`, `John/Smith` and a zero-width space (which is not
     * `Char.isWhitespace`) together exactly the same way, and they are the same disclosure.
     * Splitting on the complement of the alphabet catches the class rather than one member
     * of it, and a real `name=` attribute is a single form-encoding key.
     *
     * `$` is in the alphabet because ASP.NET WebForms builds names with it
     * (`ctl00${'$'}ContentPlaceHolder1${'$'}txtPatient`); a flat refusal would drop a whole
     * platform's field names. Leading and trailing whitespace is still just trimmed - markup
     * formatting, not content. The collector also only reads `name` off actual form controls,
     * so this is never asked about a `div`'s author-defined `name` property.
     *
     * **What remains, stated rather than implied.** A single token of alphabetic content
     * still survives, and `.` and `-` are inside the alphabet, so all of `patient_johnsmith`,
     * `John.Smith` and `Smith-Jones` come through intact - the dotted and hyphenated shapes
     * being rather more plausible as a literal person's name than the concatenated one. The
     * digit redaction does nothing for any of them. Narrowing the alphabet is not the answer,
     * since it would cost real names like `address-line`; catching these would mean refusing
     * unfamiliar names outright, which would cost the signal on every legitimate form.
     * Tracked privately in boss-plugin-analytics#7.
     */
    internal fun sanitizeFieldName(raw: String?): String? =
        raw
            ?.trim()
            ?.split(NOT_FIELD_NAME_CHARS)
            ?.filter { it.isNotEmpty() }
            // Exactly one token, or this was never a form-encoding key.
            ?.singleOrNull()
            ?.let { DIGIT_RUN.replace(it, "#") }
            // Truncated LAST, so the cap applies to what is emitted rather than to the raw
            // input. Cutting first also meant a digit run straddling the boundary could leave
            // a one- or two-digit tail that the redactor no longer recognised as a run.
            ?.take(MAX_FIELD_NAME_LENGTH)
            ?.takeIf { it.isNotEmpty() }

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
            // Redact digit runs inside the tag SEGMENTS, for the same reason [sanitizeToken]
            // does: a path is built from author-chosen tag names, so `table>tbody>tr>row-4417882`
            // carried the identifier the field-name sanitizer exists to remove. The sibling
            // ordinals after `:` are structure rather than identity and are left alone - which
            // is why this splits on the separator instead of running over the whole string.
            ?.split('>')
            ?.joinToString(">") { segment ->
                val tag = segment.substringBefore(':')
                val ordinal = segment.substringAfter(':', "")
                val redacted = DIGIT_RUN.replace(tag, "#")
                if (ordinal.isEmpty()) redacted else "$redacted:$ordinal"
            }

    /** Longest visit we are willing to call real; beyond this the clock is suspect. */
    private const val MAX_REPORTABLE_DWELL_MS = 12L * 60 * 60 * 1000
    private const val MAX_TAG_LENGTH = 32
    private const val MAX_FIELD_NAME_LENGTH = 64
    private const val MAX_PATH_LENGTH = 120
    private const val MAX_REPEAT_COUNT = 100

    /** Stand-in domain for a tab with no site loaded, so tab counts still balance. */
    internal const val BLANK_TAB_DOMAIN = "about:blank"

    /**
     * Stand-in for a tab on a host [registrableDomain] refuses; distinct from an empty tab.
     *
     * Narrower than it looks, and worth knowing before counting on the distinction: every
     * caller passes a `suggestableHost` result, which is already null for any scheme other
     * than http(s). So a tab on `file://`, `chrome://` or a `boss://` internal page reports
     * as [BLANK_TAB_DOMAIN], and this sentinel is only ever reached for an http(s) host that
     * `registrableDomain` itself refuses - loopback, a bare IP, a single-label intranet name.
     */
    internal const val UNREPORTABLE_TAB_DOMAIN = "unreportable"

    /**
     * The complement of a field name's alphabet: letters, digits, array/object syntax and
     * word separators, plus `$` for ASP.NET WebForms. ASCII ranges spelled out rather than
     * `\w`, which is ASCII-only in Java today but is exactly the kind of thing a later
     * "simplification" to `UNICODE_CHARACTER_CLASS` would silently widen.
     */
    private val NOT_FIELD_NAME_CHARS = Regex("""[^A-Za-z0-9_.\[\]$-]+""")

    /** A URL scheme, only where a scheme can actually be: at the very start. */
    private val LEADING_SCHEME = Regex("""^[a-z][a-z0-9+.-]*://""")

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
        // Anchored at the start, not `substringAfter("://")`. That took everything after the
        // FIRST occurrence wherever it appeared, so a schemeless authority carrying a URL in
        // its query - `availity.com/r?u=https://evil.com` - resolved to `evil.com`: the exact
        // smuggling this function's KDoc says cannot happen, and the case a test already
        // asserts for the well-formed form. A scheme is only a scheme at position zero.
        trimmed = trimmed.replaceFirst(LEADING_SCHEME, "")
        // Backslash counts as a path separator, because Chromium treats it as one: without it
        // `evil.com\@good.com` kept going to the credential strip below and reduced to
        // `good.com`. Under-reporting rather than over-reporting, so not a leak - but this
        // function claims to hold under misuse, and that claim should be true.
        trimmed = trimmed.takeWhile { it != '/' && it != '\\' && it != '?' && it != '#' }
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
        // An address is not a site. Any all-numeric host, not only a four-label one: Chromium
        // canonicalises `127.1` to `127.0.0.1` before this sees it, but this function is
        // documented as holding under misuse, and a two-label check answered `127.1` with
        // "the site 127.1" and `10.0.1` with "the site 0.1".
        if (labels.all { l -> l.all { c -> c in '0'..'9' } }) return null

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
            "co.il",
            "com.pl",
            "co.th",
            "com.co",
            // Private suffixes, where the failure mode is the expensive direction: without
            // them every project on a shared host collapses into one "site".
            "github.io",
            "web.app",
            "firebaseapp.com",
            "azurewebsites.net",
            "cloudfront.net",
        )
}
