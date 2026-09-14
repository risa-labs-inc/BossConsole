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
 * is the most-used site in the product. [setVisible] gates the active counter so the two
 * numbers can be told apart downstream.
 *
 * Every emission goes through [BrowserAnalytics], which is what reduces an authority to a
 * registrable domain — this class deliberately never publishes an event itself. The
 * callbacks are injectable so the accounting can be tested without an event bus.
 */
internal class BrowserVisitTracker(
    /**
     * Resolved per emission, not captured: a tab moves between windows, and a fixed id kept
     * attributing a moved tab's engagement to the window it left.
     */
    private val windowId: () -> String?,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val emitPageViewed: (String, BrowserNavigationType?, Int, String?, String?) -> Unit =
        BrowserAnalytics::pageViewed,
    private val emitPageLeft: (String, Long, Long, String?) -> Unit = BrowserAnalytics::pageLeft,
    private val emitTabEvent: (BrowserEventType, String?, String?) -> Unit = BrowserAnalytics::tabEvent,
) {
    private var currentAuthority: String? = null
    private var visitStartMs: Long = 0
    private var activeAccumMs: Long = 0

    /** Non-null exactly while the tab is visible; the instant the current active run began. */
    private var activeSinceMs: Long? = null

    /** How many compositions currently show this tab. See [setVisible]. */
    private var visibleSurfaces: Int = 0

    /** When the last surface went away, so an immediate reappearance is not a switch. */
    private var hiddenAtMs: Long? = null

    /** Registrable domain of the last page tracked, kept across visits to detect a run. */
    private var lastDomain: String? = null

    /**
     * Authority of the last page this tab was on, reportable or not.
     *
     * Distinct from [lastDomain], which is deliberately null for an unreportable host so the
     * depth run breaks. `TAB_CLOSED` needs to know the tab was *somewhere*.
     */
    private var lastAuthority: String? = null
    private var pageIndexInVisit: Int = 0

    /** One-shot hint from an explicit host navigation call, consumed by the next page view. */
    private var pendingNavigationType: BrowserNavigationType? = null

    /** When [pendingNavigationType] was set, so a hint nobody consumed can expire. */
    private var pendingNavigationAtMs: Long = 0

    private var finished = false

    /** The tab was created. [initialAuthority] is null for a new empty tab. */
    @Synchronized
    fun opened(initialAuthority: String? = null) {
        // Remembered so the first TAB_ACTIVATED - which fires at first composition, before
        // any page has finished loading - can say where the tab is rather than "blank".
        lastAuthority = initialAuthority
        emitTabEvent(BrowserEventType.TAB_OPENED, initialAuthority, windowId())
    }

    /**
     * Record that the *host* initiated the next navigation, and how.
     *
     * Only the explicit entry points (`loadUrl`, `loadUrlAndWait`, `goBack`, `goForward`,
     * `reload`, and the tab's own initial load) can say this truthfully. A navigation that
     * arrives without a hint came from the page itself, which is a link — so that, not
     * `OTHER`, is the fallback in [pageViewed].
     *
     * The hint expires. A navigation can be announced and then never land — a blocked
     * scheme, a `stop()`, a download rather than a page — and the hint would otherwise sit
     * here until *something* navigated, labelling the user's next link click `TYPED`. An
     * unconsumed hint is wrong far more often than it is merely late, so after
     * [PENDING_HINT_TTL_MS] it is discarded and the navigation reports what it looks like.
     */
    @Synchronized
    fun expect(type: BrowserNavigationType) {
        if (finished) return
        pendingNavigationType = type
        pendingNavigationAtMs = nowMs()
    }

    /** The pending hint if it is still fresh, clearing it either way. */
    private fun consumeNavigationHint(now: Long): BrowserNavigationType? {
        val pending = pendingNavigationType
        pendingNavigationType = null
        // `now - at` is negative if the clock jumped backwards; treat that as expired too.
        return pending?.takeIf { now - pendingNavigationAtMs in 0..PENDING_HINT_TTL_MS }
    }

    /**
     * A page finished loading in this tab. Closes out the previous visit and starts a new one.
     *
     * [authority] is a host (optionally with a port), matching what `suggestableHost`
     * produces — callers must apply the same "did it actually load" gate history uses, or
     * error pages get counted as visits.
     */
    @Synchronized
    fun pageViewed(
        authority: String,
        url: String? = null,
    ) {
        if (finished) return
        closeCurrentVisit()
        lastAuthority = authority

        val domain = BrowserAnalytics.registrableDomain(authority)
        if (domain == null) {
            // Not a reportable site (loopback, bare IP, intranet name). Report nothing, but
            // the tab still left the page it was on - which is exactly what
            // leftTrackablePage does, so this is the same case by another route. Re-entering
            // closeCurrentVisit is harmless: it returns immediately once the visit is closed.
            leftTrackablePage(authority)
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

        val type = consumeNavigationHint(visitStartMs) ?: BrowserNavigationType.LINK
        emitPageViewed(authority, type, pageIndexInVisit, windowId(), url)
    }

    /**
     * The tab navigated somewhere this feature does not report: an error page, `about:blank`,
     * a `file://` or `data:` URL, a download.
     *
     * The tab still **left** the page it was on, and that is the part the host cannot skip.
     * While these navigations were filtered out before reaching the tracker, the visit in
     * progress was never closed - so the previous page's dwell and active time kept accruing
     * for as long as the user sat on the error page and were then reported against that
     * previous domain, the depth run was never broken so returning to the site counted as one
     * hop deeper, and the `expect()` hint from the load that failed stayed pending and
     * relabelled the user's next link click. A mistyped host is common enough to bias both
     * numbers this feature exists to produce.
     *
     * [lastKnownAuthority] is remembered only so `TAB_CLOSED` can still say where the tab was;
     * nothing is reported as a page view.
     */
    @Synchronized
    fun leftTrackablePage(lastKnownAuthority: String? = null) {
        if (finished) return
        closeCurrentVisit()
        if (lastKnownAuthority != null) lastAuthority = lastKnownAuthority
        // Forget the site run, so the next real page starts a fresh depth count rather than
        // being counted as one hop deeper into whatever preceded the detour.
        lastDomain = null
        pageIndexInVisit = 0
        pendingNavigationType = null
    }

    /**
     * A surface showing this tab appeared ([visible] true) or went away (false). Drives the
     * active-time counter and the tab-switch signal.
     *
     * **Ref-counted, not a boolean.** The caller is a `DisposableEffect` per composition, and
     * a tab moving between windows tears down one composition while building another - two
     * independent effects, in either order. As a plain boolean the compose-then-dispose order
     * was silently destructive: the enter no-opped because the tab was already active, then
     * the leave cleared it, leaving a tab that is visible and focused marked inactive. It
     * accrued no active time until the user switched away and back, while dwell kept
     * climbing, so a moved tab read as "left open, never read" - the exact shape this class
     * exists to distinguish. Counting makes it order-independent.
     */
    @Synchronized
    fun setVisible(visible: Boolean) {
        if (finished) return
        val now = nowMs()
        if (visible) surfaceShown(now) else surfaceHidden(now)
    }

    private fun surfaceShown(now: Long) {
        visibleSurfaces++
        if (visibleSurfaces > 1 || activeSinceMs != null) return
        activeSinceMs = now
        // A move in the other order - old composition torn down, new one built - passes
        // through zero surfaces, so refcounting alone still reports a switch that never
        // happened. Those two callbacks land in the same frame, so a reappearance this soon
        // after vanishing is the same activation continuing, not the user coming back to the
        // tab. The active counter still stops and starts across the gap; it is a few
        // milliseconds and correct either way.
        val samePresence = hiddenAtMs?.let { now - it in 0..MOVE_GRACE_MS } == true
        if (samePresence) return
        // Falls back to the authority the tab was opened with: the first activation happens
        // at first composition, before any NavigationFinished, so reading only
        // currentAuthority reported nearly every tab's first TAB_ACTIVATED as a blank tab.
        emitTabEvent(BrowserEventType.TAB_ACTIVATED, currentAuthority ?: lastAuthority, windowId())
    }

    private fun surfaceHidden(now: Long) {
        visibleSurfaces = (visibleSurfaces - 1).coerceAtLeast(0)
        if (visibleSurfaces > 0) return
        hiddenAtMs = now
        activeSinceMs?.let { since ->
            activeAccumMs += (now - since).coerceAtLeast(0)
            activeSinceMs = null
        }
    }

    /**
     * The tab was closed or disposed. Flushes the visit in progress, then reports the close.
     *
     * Reports [lastAuthority], not [lastDomain]: the latter is null for a host we refuse to
     * report, which `tabEvent` would then map to "blank tab" - collapsing a tab closed on a
     * dev server into a tab that never loaded anything, which is exactly the conflation the
     * open side goes out of its way to avoid, and it would leave opens and closes unbalanced
     * per sentinel. Passing the authority also makes both ends symmetrical: `tabEvent`
     * receives an authority and does its own reduction, rather than one caller handing it
     * something already reduced and relying on that being idempotent.
     */
    @Synchronized
    fun closed() {
        if (finished) return
        closeCurrentVisit()
        finished = true
        emitTabEvent(BrowserEventType.TAB_CLOSED, lastAuthority, windowId())
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
        emitPageLeft(authority, dwellMs, activeAccumMs, windowId())
        activeAccumMs = 0
    }

    private companion object {
        /**
         * How long an announced-but-unlanded navigation may still claim the next page view.
         *
         * Long enough to cover a slow first byte on a bad connection, short enough that a
         * navigation which never happened cannot mislabel a click the user makes afterwards.
         */
        const val PENDING_HINT_TTL_MS = 60_000L

        /**
         * How quickly a tab must reappear for it to count as never having gone.
         *
         * Sized for one frame's worth of composition churn during a tab move, not for a user
         * glancing at another tab and back - which at any human speed is well past this and
         * is a real switch.
         */
        const val MOVE_GRACE_MS = 250L
    }
}
