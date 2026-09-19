package ai.rever.boss.plugin.browser

import com.teamdev.jxbrowser.engine.RenderingMode

/**
 * Recovery for a committed document whose view never starts producing frames.
 *
 * **The symptom, measured rather than inferred.** Clicking Google's "AI Mode" tab from a results
 * page lands on `…&udm=50&aep=1`, and the pane goes blank and stays blank. The document itself is
 * completely healthy: the DOM is complete, layout is correct, `setTimeout` and `setInterval` keep
 * firing, microtasks resolve, and JS evaluates normally. What never happens is a frame -
 * `requestAnimationFrame` does not fire once, and painting a fresh background colour into `body`
 * changes nothing on screen. Measured on the click path, 0 of 5 navigations painted (raf=0 after
 * 3s) against 5 of 5 for the same queries without the AI Mode click (raf around 420).
 *
 * **What it is not.** Reproduced with the GPU fully out of the picture - a build reporting
 * `Canvas/Compositing/Rasterization: Software only` and `Skia Graphite: Disabled` blanks
 * identically - so it is not raster, not Graphite and not the hardware path. It is not CSS either:
 * forcing every element opaque leaves the pane blank, and elements at effective opacity 1 (the
 * "AI Mode / All / Images" nav row) are just as unpainted as the animated ones. Response headers
 * are byte-identical to the variant that works, so no process swap is involved, and BOSS logs
 * nothing at all across the navigation.
 *
 * **Why re-attaching is the fix.** Switching to another tab and back restores frame production
 * immediately and permanently (raf 0 before, 850 after). Nothing about the document changes across
 * that switch - the surface is even retained under HARDWARE_ACCELERATED. What changes is that the
 * view leaves and re-enters composition, so the native view is attached again. That makes this a
 * presentation-side stall, and re-attaching is the same repair the user already performs by hand.
 *
 * The underlying fault is inside the JxBrowser/Chromium widget rather than in BOSS, so this is
 * deliberately a recovery and not a cure: it detects a document that committed but never drew, and
 * performs the tab-switch repair on the user's behalf.
 *
 * **Known limit: only a new document is covered.** The beacon is armed once per document and
 * latches at [BEACON_PAINTED] for that document's life, so it answers "has this document ever
 * painted", not "is it painting now". Two consequences, both deliberate. Same-document
 * navigations (pushState, fragment) are skipped entirely at the call site, because probing one
 * would read the flag the original load left behind and clear a legitimate ineffective run on
 * stale evidence. And a stall that *begins* after a document has already painted is invisible -
 * if Google ever serves the AI Mode transition client-side rather than as a fresh commit, this
 * stops firing, silently. Catching that would need a liveness beacon (a rolling rAF timestamp
 * compared against wall time) rather than a latch, which is a different and more expensive
 * design than the one the measured failure called for.
 *
 * **Known and accepted: the page can influence the verdict.** The flag lives on `window`, so a
 * page could pin it to `"0"` (forcing a re-attach per navigation, costing flicker) or to `"1"`
 * (suppressing the repair, leaving itself blank). This is a *wider* exposure than the one
 * AGENTS.md now records for `window.__bossInteraction`, which is no longer left reachable by
 * page scripts - this flag is, and nothing here defends against it. The worst case either way is
 * cosmetic, so it is written down rather than fixed. The re-attach cap below bounds the noisy
 * direction.
 *
 * **Not injected at document start, though the reason has changed.** This used to say the
 * dispatcher was unusable because `ensureCoBrowseInjectCallback` claimed the browser's single
 * `InjectJsCallback` slot with a direct `browser.set`. That is no longer true: co-browse and the
 * page-event script both register through [BrowserInjectDispatcher], and
 * `InjectJsCallbackOwnershipTest` keeps it that way, so there is no conflict left to stay out of.
 *
 * What remains is simply that nobody has moved this one. Arming lazily on the first probe works and
 * costs one extra round-trip (spelled out in [ARM_DELAY_MS]); registering at document start would
 * make the *first* reading after a navigation meaningful, which is the one case this currently
 * cannot answer. It is an available improvement, not a constraint - do not read this paragraph as
 * an argument against the dispatcher.
 */
internal object BrowserFrameStall {
    /**
     * Arms a one-shot beacon and reports whether a frame has been served since it was armed.
     *
     * Returns `"1"` once rAF has run and `"0"` while it has not. The same call that reads is the
     * call that arms, so the first invocation on a document always returns `"0"` no matter how
     * healthy the page is. That is why [ARM_DELAY_MS] is named for arming and why the decision
     * needs two readings *after* it.
     *
     * The `typeof` guard makes this arm **once per document**: later calls report the existing
     * beacon rather than restarting it, which is what lets a second reading confirm the first.
     * A document replaced mid-probe (a redirect committing between calls) gets a fresh `window`
     * and therefore a fresh beacon, so a stale page's verdict can never carry over.
     */
    val BEACON_SCRIPT: String =
        """
        (function () {
          if (typeof window.__bossFrameBeacon === 'undefined') {
            window.__bossFrameBeacon = '0';
            requestAnimationFrame(function () { window.__bossFrameBeacon = '1'; });
          }
          return window.__bossFrameBeacon;
        })()
        """.trimIndent()

    /** The beacon's answer for "a frame has been served". */
    const val BEACON_PAINTED: String = "1"

    /** The beacon's answer for "armed, still nothing drawn". */
    const val BEACON_UNPAINTED: String = "0"

    /**
     * Whether a committed navigation is worth watching.
     *
     * Gated to HARDWARE_ACCELERATED because that is the mode where the browser is a real native
     * view whose attachment can go stale; under OFF_SCREEN the frames are exported as a bitmap and
     * this failure has never been observed. Gated to http(s) so `about:blank`, `chrome://` and the
     * dashboard's empty states - none of which a user would call blank - never arm a probe or
     * trigger a re-attach.
     */
    fun shouldWatch(
        url: String?,
        mode: RenderingMode,
    ): Boolean {
        if (mode != RenderingMode.HARDWARE_ACCELERATED) return false
        val trimmed = url?.trim().orEmpty()
        return trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
    }

    /**
     * Whether a beacon reading means "stalled". Only an explicit [BEACON_UNPAINTED] does.
     *
     * Null is NOT a stall, and that is the whole point of this function. Null is what a failed or
     * timed-out round-trip returns - a navigation that committed again mid-probe, a frame that
     * went away, a renderer too busy to answer - and treating those as stalls would re-attach the
     * view on ordinary pages, which costs a visible flicker for nothing. Any unrecognised value
     * (a page that overwrote the global with something else) is treated the same cautious way.
     */
    fun isStalled(beaconReading: String?): Boolean = beaconReading == BEACON_UNPAINTED

    /**
     * What a beacon reading says: true painting, false still blank, null unknown.
     *
     * Used for **every** read whose answer feeds the ineffective run - the post-repair
     * confirmation and the "did it paint unaided" reads - because all of them can be wrong in the
     * same way. [isStalled] stays the right predicate for the narrower question of whether to
     * touch the view at all, where an unknown must read as "leave it alone".
     *
     * **Three states, not two, and [isStalled] must not be reused here.** For the *decision* to
     * re-attach, null correctly means "leave it alone". For the *outcome*, `!isStalled(null)`
     * reads as success and credits a repair that was never observed to work - which is worst in
     * exactly the case the give-up cap exists for. A renderer wedged badly enough that probes stop
     * answering within [PROBE_TIMEOUT_MS] is a renderer re-attaching will not fix, yet every
     * post-repair read would come back null, the ineffective run would never leave zero, the cap
     * would never trip, and the tab would flicker once per [REATTACH_COOLDOWN_MS] forever while
     * the log claimed it was painting.
     */
    fun repairOutcome(beaconReading: String?): Boolean? =
        when {
            beaconReading == null -> null
            isStalled(beaconReading) -> false
            else -> true
        }

    /**
     * How long after a commit to arm the beacon.
     *
     * Named for arming rather than checking because the reading this call returns is always
     * [BEACON_UNPAINTED] and carries no information (see [BEACON_SCRIPT]). Delayed rather than
     * immediate so a page that paints promptly is usually already done by the first real reading.
     */
    const val ARM_DELAY_MS: Long = 700L

    /**
     * Gap between the arm and each subsequent reading.
     *
     * Two real readings, not one. rAF does not run while rendering is blocked, so a page still
     * fetching a render-blocking stylesheet or font on a poor connection can honestly have drawn
     * nothing one gap after arming; re-attaching then would yank a healthy page's view out of
     * composition mid-load. Both readings must say "no frame" before anything is done.
     */
    const val READ_GAP_MS: Long = 900L

    /**
     * How long to wait for a single beacon round-trip before giving up on it.
     *
     * `executeJavaScript` blocks and cannot be interrupted, so this bounds the *wait*, not the
     * call. A probe that times out reads as null, which [isStalled] treats as "not stalled" - the
     * safe direction, since the renderer this is interrogating is already suspect.
     */
    const val PROBE_TIMEOUT_MS: Long = 800L

    /**
     * How many re-attaches **in a row that failed to restore painting** a handle may perform
     * before it gives up.
     *
     * `EngineWedgeDetector` in this package carries a ceiling for the same reason: a repair with
     * none turns any condition that reliably reads [BEACON_UNPAINTED] into a flicker on every
     * navigation, which is worse than the blank it is fixing. Past it the handle degrades to the
     * pre-existing behaviour - the user switches tabs themselves - and the log says so once.
     *
     * **Consecutive-ineffective, deliberately, rather than a lifetime count.** A lifetime cap
     * sounds safer and measurably is not: AI Mode is a page people use repeatedly, and a run of
     * five click-throughs on one tab had the fourth and fifth abandoned while the repair was still
     * working - it had recovered the page 3 times out of 3. Keying on whether the re-attach
     * actually helped bounds only what warrants bounding (a false positive, or a stall this cannot
     * fix), and one success resets the count, so a tab that stays repairable stays repaired.
     */
    const val MAX_INEFFECTIVE_REATTACHES: Int = 3

    /**
     * Minimum spacing between two re-attaches on one handle.
     *
     * This is what bounds the *rate* of any flicker, and it does so whether or not the repair is
     * working - so it, rather than [MAX_INEFFECTIVE_REATTACHES], is the guard against an SPA
     * navigating in a tight loop.
     */
    const val REATTACH_COOLDOWN_MS: Long = 10_000L
}

/**
 * When a frame-stall repair is allowed to run, and when to stop trying.
 *
 * Split out of `BrowserHandleImpl` for the reason `EngineWedgeDetector` is: this is the part that
 * decides whether a user ever sees a flicker loop, it needs no JxBrowser view to exercise, and
 * left inline it was five `@Volatile` fields testable only by driving a real browser. Lock-guarded
 * rather than volatile because `attempts += 1` is a read-modify-write, and a superseded probe can
 * still be between [claim] and its next suspension point while a newer one runs.
 *
 * [nowMs] must come from a **monotonic** source. Wall-clock time is wrong for measuring a
 * cooldown: an NTP step backwards would stretch it and a step forwards would open it early.
 */
internal class FrameStallPolicy(
    private val maxIneffective: Int = BrowserFrameStall.MAX_INEFFECTIVE_REATTACHES,
    private val cooldownMs: Long = BrowserFrameStall.REATTACH_COOLDOWN_MS,
) {
    /** What [claim] decided, including whether this is the first refusal worth logging. */
    enum class Decision {
        REATTACH,
        COOLING_DOWN,

        /** Cap reached, and this is the first refusal - log it once. */
        GIVE_UP_NOW,

        /** Cap reached and already reported; stay silent. */
        GIVEN_UP,
    }

    private var attemptCount = 0
    private var ineffectiveRun = 0
    private var lastReattachAt: Long? = null
    private var gaveUpReported = false

    /** Total re-attaches performed, for the log. */
    @get:Synchronized val attempts: Int get() = attemptCount

    /** Re-attaches in a row that did not restore painting. */
    @get:Synchronized val ineffectiveInARow: Int get() = ineffectiveRun

    /** Ask for permission to re-attach, recording the attempt when granted. */
    @Synchronized
    fun claim(nowMs: Long): Decision {
        val last = lastReattachAt
        return when {
            ineffectiveRun >= maxIneffective && gaveUpReported -> {
                Decision.GIVEN_UP
            }

            ineffectiveRun >= maxIneffective -> {
                gaveUpReported = true
                Decision.GIVE_UP_NOW
            }

            last != null && nowMs - last < cooldownMs -> {
                Decision.COOLING_DOWN
            }

            else -> {
                lastReattachAt = nowMs
                attemptCount += 1
                Decision.REATTACH
            }
        }
    }

    /** What the caller should do about a stall it has just confirmed. */
    sealed interface Claim {
        /** Granted; the attempt is already recorded. */
        object Now : Claim

        /** Refused for now, but [waitMs] from now it would be granted. */
        data class After(
            val waitMs: Long,
        ) : Claim

        /** Refused for good. [firstRefusal] is true exactly once, for the log. */
        data class Refused(
            val firstRefusal: Boolean,
        ) : Claim
    }

    /**
     * [claim], answered in the form the caller actually needs.
     *
     * **One synchronized call, deliberately.** Asking `claim` and then `remainingCooldownMs`
     * separately is two decisions about a clock that moved in between, and it conflates two
     * different refusals: [claim] tests the give-up cap *before* the cooldown, so a retired tab
     * refuses while `lastReattachAt` is still recent, and a caller reading the leftover cooldown
     * would deferentially wait out a tab it has already abandoned - logging "leaving this tab
     * alone" and "deferred until the cooldown expires" about the same decision, then possibly
     * un-retiring it. Returning the reason with the wait makes that unrepresentable, and it
     * removes the boundary race where the remainder reaches 0 between the two reads and a
     * grantable repair is dropped.
     */
    @Synchronized
    fun claimOrDefer(nowMs: Long): Claim =
        when (claim(nowMs)) {
            Decision.REATTACH -> Claim.Now
            Decision.COOLING_DOWN -> Claim.After(remainingCooldownMs(nowMs))
            Decision.GIVE_UP_NOW -> Claim.Refused(firstRefusal = true)
            Decision.GIVEN_UP -> Claim.Refused(firstRefusal = false)
        }

    /**
     * How much of the cooldown is left at [nowMs], or 0 when a claim would be granted now.
     *
     * Exists so a stall arriving inside the cooldown can be **deferred rather than dropped**. The
     * cooldown bounds how often the view is rebuilt; it is not a decision that a blank page stays
     * blank. The decision point is `ARM_DELAY_MS + 2 * READ_GAP_MS` after a commit and the
     * cooldown is four times that, so two stalling commits close together used to leave the second
     * one blank with nothing logged - the same "no signal at all" this whole feature exists to
     * remove, and on the page it targets, which is one people iterate on. Waiting out the
     * remainder and claiming once respects the rate limit and still repairs the page.
     */
    @Synchronized
    fun remainingCooldownMs(nowMs: Long): Long {
        val last = lastReattachAt ?: return 0L
        return (cooldownMs - (nowMs - last)).coerceAtLeast(0L)
    }

    /**
     * Count a re-attach as ineffective **up front**, before its outcome is known.
     *
     * Pessimistic on purpose. The outcome is read a beat later, and everything that can end the
     * job in between - a fresh commit superseding it, the view leaving composition, a probe that
     * never answers - would otherwise leave the attempt unaccounted for. A tab that reliably reads
     * unpainted and re-navigates inside that window would then re-attach every cooldown forever
     * and never reach the cap, which is the one guard against a false positive. Counting first and
     * crediting only an observed recovery makes every path that skips the confirmation fail
     * towards giving up rather than towards flickering.
     */
    @Synchronized
    fun recordAttemptPending() {
        ineffectiveRun += 1
    }

    /**
     * Credit a re-attach that was **observed** to restore painting.
     *
     * Clears the run and the reported flag, so a handle that starts failing again later still gets
     * its one log line. Never call this for an unknown outcome: see
     * [BrowserFrameStall.repairOutcome].
     */
    @Synchronized
    fun recordRecovered() {
        ineffectiveRun = 0
        gaveUpReported = false
    }

    /**
     * A navigation that painted without help.
     *
     * "Consecutive" would otherwise be counted in re-attaches rather than in time: three
     * ineffective attempts spread over hours and dozens of healthy pages would retire the
     * watchdog for that tab exactly as if they had happened back to back on one page. Any page
     * that paints on its own is evidence the tab is fine, so it decays the run.
     */
    @Synchronized
    fun recordHealthyNavigation() {
        ineffectiveRun = 0
        gaveUpReported = false
    }

    /**
     * Whether this handle has stopped repairing.
     *
     * Exposed only so the caller can say in the log that it is still probing a retired tab - which
     * it must, since a reading is the only thing that can bring one back.
     */
    @get:Synchronized val hasGivenUp: Boolean get() = ineffectiveRun >= maxIneffective
}
