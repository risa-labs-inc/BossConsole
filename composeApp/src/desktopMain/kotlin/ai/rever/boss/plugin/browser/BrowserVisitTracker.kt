package ai.rever.boss.plugin.browser

import ai.rever.boss.plugin.api.BrowserEventType
import ai.rever.boss.plugin.api.BrowserNavigationType

/**
 * Per-tab engagement state: how long each page visit lasted, how much of that the user was
 * actually there for, and how deep into a site they went.
 *
 * One instance per [BrowserHandleImpl], created and finished with the tab, so there is no
 * registry keyed by tab id to leak when a tab dies badly.
 *
 * **Wall-clock time is not engagement.** A tab left open on a portal overnight reports a
 * fourteen-hour dwell and near-zero active time; averaging the former would say that portal
 * is the most-used site in the product. [setFocused] gates the active counter so the two
 * numbers can be told apart downstream.
 *
 * Every emission goes through [BrowserAnalytics], which is what reduces an authority to a
 * registrable domain — this class deliberately never publishes an event itself. The
 * callbacks are injectable so the accounting can be tested without an event bus.
 */
internal class BrowserVisitTracker(
    private val windowId: String?,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val emitPageViewed: (String, BrowserNavigationType?, Int, String?) -> Unit =
        BrowserAnalytics::pageViewed,
    private val emitPageLeft: (String, Long, Long, String?) -> Unit = BrowserAnalytics::pageLeft,
    private val emitTabEvent: (BrowserEventType, String?, String?) -> Unit = BrowserAnalytics::tabEvent,
) {
    private var currentAuthority: String? = null
    private var visitStartMs: Long = 0
    private var activeAccumMs: Long = 0

    /** Non-null exactly while the tab is focused; the instant the current active run began. */
    private var activeSinceMs: Long? = null

    /** Registrable domain of the last page tracked, kept across visits to detect a run. */
    private var lastDomain: String? = null
    private var pageIndexInVisit: Int = 0

    /** One-shot hint from an explicit host navigation call, consumed by the next page view. */
    private var pendingNavigationType: BrowserNavigationType? = null

    private var finished = false

    /** The tab was created. [initialAuthority] is null for a new empty tab. */
    @Synchronized
    fun opened(initialAuthority: String? = null) {
        emitTabEvent(BrowserEventType.TAB_OPENED, initialAuthority, windowId)
    }

    /**
     * Record that the *host* initiated the next navigation, and how.
     *
     * Only the four explicit entry points (`loadUrl`, `goBack`, `goForward`, `reload`) can
     * say this truthfully. A navigation that arrives without a hint came from the page
     * itself, which is a link — so that, not `OTHER`, is the fallback in [pageViewed].
     */
    @Synchronized
    fun expect(type: BrowserNavigationType) {
        pendingNavigationType = type
    }

    /**
     * A page finished loading in this tab. Closes out the previous visit and starts a new one.
     *
     * [authority] is a host (optionally with a port), matching what `suggestableHost`
     * produces — callers must apply the same "did it actually load" gate history uses, or
     * error pages get counted as visits.
     */
    @Synchronized
    fun pageViewed(authority: String) {
        if (finished) return
        closeCurrentVisit()

        val domain = BrowserAnalytics.registrableDomain(authority)
        if (domain == null) {
            // Not a reportable site (loopback, bare IP, intranet name). Track nothing, and
            // break the run: the next real page starts a fresh depth count rather than
            // being counted as one hop deeper into whatever preceded the dev server.
            lastDomain = null
            pageIndexInVisit = 0
            pendingNavigationType = null
            return
        }

        pageIndexInVisit = if (domain == lastDomain) pageIndexInVisit + 1 else 1
        lastDomain = domain
        currentAuthority = authority
        visitStartMs = nowMs()
        activeAccumMs = 0
        // A page that loads in a background tab is not being read; only start the active
        // counter if this tab already had focus.
        if (activeSinceMs != null) activeSinceMs = visitStartMs

        val type = pendingNavigationType ?: BrowserNavigationType.LINK
        pendingNavigationType = null
        emitPageViewed(authority, type, pageIndexInVisit, windowId)
    }

    /** The tab gained or lost focus. Drives the active-time counter and tab-switch signal. */
    @Synchronized
    fun setFocused(focused: Boolean) {
        if (finished) return
        val now = nowMs()
        if (focused) {
            if (activeSinceMs == null) {
                activeSinceMs = now
                emitTabEvent(BrowserEventType.TAB_ACTIVATED, currentAuthority, windowId)
            }
        } else {
            activeSinceMs?.let { since ->
                activeAccumMs += (now - since).coerceAtLeast(0)
                activeSinceMs = null
            }
        }
    }

    /** The tab was closed or disposed. Flushes the visit in progress, then reports the close. */
    @Synchronized
    fun closed() {
        if (finished) return
        closeCurrentVisit()
        finished = true
        emitTabEvent(BrowserEventType.TAB_CLOSED, lastDomain, windowId)
    }

    /**
     * Emit `PAGE_LEFT` for the visit in progress, if any.
     *
     * Leaves [lastDomain] alone: it is what the *next* [pageViewed] compares against to
     * decide whether the user is still moving around the same site.
     *
     * Note this publishes while holding this object's monitor (its callers are all
     * `@Synchronized`). That is safe only because the emit path is non-blocking — the event
     * bus is a `MutableSharedFlow` published with `tryEmit`. Swapping in a suspending or
     * synchronous-dispatch publisher would make a subscriber's work run under this lock,
     * with a browser navigation callback waiting behind it.
     */
    private fun closeCurrentVisit() {
        val authority = currentAuthority ?: return
        val now = nowMs()
        // Fold any open active run in without clearing focus — the tab may well still be
        // focused, it is only this page that is ending.
        activeSinceMs?.let { since ->
            activeAccumMs += (now - since).coerceAtLeast(0)
            activeSinceMs = now
        }
        val dwellMs = (now - visitStartMs).coerceAtLeast(0)
        currentAuthority = null
        emitPageLeft(authority, dwellMs, activeAccumMs, windowId)
        activeAccumMs = 0
    }
}
