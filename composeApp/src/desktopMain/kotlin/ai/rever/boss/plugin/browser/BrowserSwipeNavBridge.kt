package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.teamdev.jxbrowser.js.JsAccessible
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Which way a committed swipe was going. */
internal enum class SwipeNavDirection { BACK, FORWARD }

/**
 * Page-to-host bridge for the two-finger swipe gesture, published on `window.__bossSwipeNav`.
 *
 * ## Reachability
 *
 * Like every bridge here, this is callable by any JavaScript on the page and not only by the
 * script that was injected alongside it. That is fine, and deliberately so: the only thing it can
 * do is move the tab through its own session history, which the page can already do with
 * `history.back()`. There is no capability here a site did not have, so the design goal is
 * narrowness rather than unreachability - one method, one enum's worth of input, nothing returned.
 *
 * ## Threading
 *
 * [navigate] runs on a JxBrowser thread and must not block or throw into the page's JS thread.
 * It hands off to [onNavigate] and returns; the actual `goBack()`/`goForward()` happens elsewhere.
 * A synchronous round trip into `browser.navigation()` from here would park the renderer's JS
 * thread on the browser thread, which is the shape of a deadlock rather than a slow call.
 */
internal class BrowserSwipeNavBridge(
    private val onNavigate: (SwipeNavDirection) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val logger = BossLogger.forComponent("BrowserSwipeNavBridge")
    private val lastFailureLoggedAt = AtomicLong(0)

    @JsAccessible
    fun navigate(direction: String?) {
        try {
            val parsed = parseSwipeNavDirection(direction) ?: return
            onNavigate(parsed)
        } catch (e: LinkageError) {
            // A wiring break rather than bad input: this class or the api jar is not what the
            // caller was compiled against. Enumerated rather than caught as Throwable, matching
            // BrowserInteractionBridge - an OutOfMemoryError is not this boundary's to swallow.
            reportFailure(e)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // Never propagate into the page's JS thread: a throw here surfaces in the site's own
            // console and can break its JS. Error is deliberately NOT included, so a fatal process
            // condition still escapes.
            reportFailure(e)
        }
    }

    /**
     * Log at most one failure per minute.
     *
     * Silence was the wrong trade: a page nobody swipes on and a bridge that was never published
     * look identical from the outside, and the script swallows its own errors too - so a wiring
     * break would have no signal anywhere along the path. A hostile page can call this in a loop,
     * hence the limiter. The exception class only, never its message: page detail must not reach a
     * log line.
     */
    private fun reportFailure(error: Throwable) {
        val now = nowMs()
        val previous = lastFailureLoggedAt.get()
        if (previous != 0L && now - previous < FAILURE_LOG_INTERVAL_MS) return
        if (!lastFailureLoggedAt.compareAndSet(previous, now)) return
        logger.warn(
            LogCategory.BROWSER,
            "Swipe navigation bridge call failed",
            mapOf("error" to (error::class.simpleName ?: "Throwable")),
        )
    }

    private companion object {
        const val FAILURE_LOG_INTERVAL_MS = 60_000L
    }
}

/**
 * Read a direction out of whatever the page passed.
 *
 * Anything that is not exactly one of the two words the script sends is dropped rather than
 * guessed at. Pure, so the refusals are pinned by tests.
 */
internal fun parseSwipeNavDirection(raw: String?): SwipeNavDirection? =
    when (raw) {
        "back" -> SwipeNavDirection.BACK
        "forward" -> SwipeNavDirection.FORWARD
        else -> null
    }

/** A swipe that navigated: when, and which way. */
internal data class SwipeNavCommit(
    val atMs: Long,
    val direction: SwipeNavDirection,
)

/**
 * Whether a swipe arriving at [nowMs] should navigate, given the one that last did.
 *
 * Two windows, because two different things are being refused.
 *
 * [SWIPE_NAV_DEBOUNCE_MS] catches a double-fire *bug* in this bridge or its caller, in either
 * direction. The page cannot produce a real second gesture that fast: `swipe-nav.js`'s `decide()`
 * calls this at most once per gesture END, and two gesture ends are always at least the script's
 * own `GESTURE_GAP_MS` apart, because that quiet gap IS how the script tells one gesture from the
 * next. So the value belongs strictly below that floor and above a same-frame double-dispatch,
 * with enough headroom that host-side clock jitter cannot eat the difference.
 *
 * [SWIPE_NAV_REPEAT_MS] refuses a SAME-DIRECTION repeat, and it is the one guard against a paused
 * drag. The script segments gestures on 120ms of quiet, and 120ms of quiet with the fingers still
 * down is byte-identical to a lift: a slow deliberate drag that hesitates mid-swipe is two
 * gestures to the script and would navigate back twice. That guard cannot live in the page,
 * because the commit navigates the tab and the script's state dies with the document - the second
 * half of the drag lands in a freshly loaded script that has never heard of the first.
 *
 * The trade is deliberate and it is not symmetric. Two intentional same-direction swipes less
 * than [SWIPE_NAV_REPEAT_MS] apart are dropped, and the user swipes again. An unwanted extra step
 * back may not be undoable at all, because the forward entry does not have to survive the
 * intervening page's redirect. Dropping the recoverable one is the cheaper mistake.
 *
 * A REVERSAL is never held for the repeat window - only for the debounce. Swiping forward straight
 * after a back is how someone undoes a navigation they did not mean, and a momentum tail cannot
 * produce one (it runs the flick's own direction), so there is nothing to protect against there.
 *
 * The window incidentally rate-limits a hostile page looping `window.__bossSwipeNav.navigate()`,
 * at roughly 30 calls a second. That is not a boundary and is not sized as one - what holds is the
 * class KDoc's reasoning, that the page can already loop on `history.back()` with no bridge at all.
 *
 * Pure, so both windows are pinned by tests rather than by trying to swipe twice quickly by hand.
 */
internal fun shouldAcceptSwipeNav(
    nowMs: Long,
    previous: SwipeNavCommit?,
    direction: SwipeNavDirection,
): Boolean {
    if (previous == null) return true
    // The repeat window is the larger of the two - pinned in BrowserSwipeNavTest against the
    // script's own gesture gap, which sits between them - so a same-direction swipe clearing it has
    // cleared the debounce as well, and one window per case is the whole rule.
    val window = if (direction == previous.direction) SWIPE_NAV_REPEAT_MS else SWIPE_NAV_DEBOUNCE_MS
    return nowMs - previous.atMs > window
}

/**
 * Holds the last committed swipe for [shouldAcceptSwipeNav].
 *
 * The check and the record have to happen together. Two calls that genuinely raced would both read
 * the same `previous`, both pass, and both navigate - `@Volatile` on a plain field gives visibility
 * and not atomicity, so it would not have closed that. Today only one JxBrowser JS thread can get
 * here (the script is injected into `mainFrame()` alone), which makes this cheap insurance rather
 * than a fix for an observed race.
 *
 * The clock is [System.nanoTime] and not wall time on purpose: both windows are elapsed-time
 * questions, and an NTP step backwards on a wall clock would refuse every swipe until real time
 * caught up.
 */
internal class SwipeNavGate(
    private val nowMs: () -> Long = { System.nanoTime() / NANOS_PER_MS },
) {
    private val lastCommit = AtomicReference<SwipeNavCommit?>(null)

    /** True exactly once per accepted swipe; the loser of a race gets false. */
    fun accept(direction: SwipeNavDirection): Boolean {
        val now = nowMs()
        val previous = lastCommit.get()
        if (!shouldAcceptSwipeNav(now, previous, direction)) return false
        return lastCommit.compareAndSet(previous, SwipeNavCommit(now, direction))
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}

/** Any direction, for a double-dispatch bug. Two frames, well clear of the script's 120ms floor. */
internal const val SWIPE_NAV_DEBOUNCE_MS = 32L

/** Same direction, for a drag that hesitated past the script's gesture gap. */
internal const val SWIPE_NAV_REPEAT_MS = 400L
