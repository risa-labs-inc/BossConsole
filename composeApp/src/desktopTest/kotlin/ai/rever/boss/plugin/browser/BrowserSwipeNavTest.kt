package ai.rever.boss.plugin.browser

import ai.rever.boss.config.parseSwipeNavEnabled
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The host half of the two-finger swipe gesture.
 *
 * The gesture itself is JavaScript and is covered by running it - `scripts/test/test-swipe-nav.js`,
 * which the build workflow executes. What is left here is everything the page cannot decide: which
 * platforms get the feature, what the bridge accepts from a page that is free to call it with
 * anything, and how close together two swipes may navigate.
 */
class BrowserSwipeNavTest {
    // --- Where the feature is on -------------------------------------------------------------

    @Test
    fun `on by default on macOS`() {
        assertTrue(swipeNavEnabled(isMac = true, enabled = true))
    }

    @Test
    fun `off everywhere else`() {
        assertFalse(swipeNavEnabled(isMac = false, enabled = true))
    }

    @Test
    fun `the setting turns it off`() {
        assertFalse(swipeNavEnabled(isMac = true, enabled = false))
    }

    /**
     * The key was documented as an env-var on/off switch before it had a Settings row, so someone
     * has one of these exported. Dropping a spelling would silently change what their shell says.
     */
    @Test
    fun `every documented spelling still parses`() {
        for (value in listOf("false", "0", "no", "off", "FALSE", " off ")) {
            assertEquals(false, parseSwipeNavEnabled(value), value)
        }
        for (value in listOf("true", "1", "yes", "on", "TRUE")) {
            assertEquals(true, parseSwipeNavEnabled(value), value)
        }
    }

    /**
     * A typo must not silently remove a gesture whose only route back is finding the same typo, so
     * an unparseable value is "no opinion" and the caller keeps its default - never off.
     */
    @Test
    fun `an unparseable value is no opinion, not off`() {
        assertNull(parseSwipeNavEnabled("maybe"))
        assertNull(parseSwipeNavEnabled(""))
        assertNull(parseSwipeNavEnabled(null))
    }

    // --- What the bridge accepts from a page -------------------------------------------------

    @Test
    fun `the two directions the script sends`() {
        assertEquals(SwipeNavDirection.BACK, parseSwipeNavDirection("back"))
        assertEquals(SwipeNavDirection.FORWARD, parseSwipeNavDirection("forward"))
    }

    @Test
    fun `anything else is dropped rather than guessed at`() {
        for (value in listOf(null, "", "BACK", " back", "backward", "-1", "back;forward")) {
            assertNull(parseSwipeNavDirection(value), value.toString())
        }
    }

    @Test
    fun `a page calling the bridge directly still reaches the handler`() {
        // Reachability is by design - the page can already call history.back() - so what is
        // pinned here is that the reachable surface is exactly one verb with two answers.
        val seen = mutableListOf<SwipeNavDirection>()
        val bridge = BrowserSwipeNavBridge(onNavigate = { seen += it })
        bridge.navigate("back")
        bridge.navigate("sideways")
        bridge.navigate(null)
        bridge.navigate("forward")
        assertEquals(listOf(SwipeNavDirection.BACK, SwipeNavDirection.FORWARD), seen)
    }

    @Test
    fun `a throwing handler never escapes into the page`() {
        val bridge = BrowserSwipeNavBridge(onNavigate = { error("handler blew up") })
        bridge.navigate("back")
    }

    // --- How often a swipe may navigate ------------------------------------------------------

    @Test
    fun `the first swipe of the session navigates`() {
        assertTrue(shouldAcceptSwipeNav(nowMs = 1_000, previous = null, direction = SwipeNavDirection.BACK))
    }

    @Test
    fun `a second swipe inside the debounce is refused whichever way it goes`() {
        for (direction in SwipeNavDirection.entries) {
            assertFalse(
                shouldAcceptSwipeNav(1_000 + SWIPE_NAV_DEBOUNCE_MS, back(1_000), direction),
                direction.name,
            )
            assertFalse(
                shouldAcceptSwipeNav(1_000 + SWIPE_NAV_DEBOUNCE_MS / 2, back(1_000), direction),
                direction.name,
            )
        }
    }

    /**
     * The reversal is how someone undoes a navigation they did not mean, so it waits out the
     * double-dispatch window and nothing more.
     */
    @Test
    fun `a reversal is held only for the debounce`() {
        assertTrue(
            shouldAcceptSwipeNav(1_001 + SWIPE_NAV_DEBOUNCE_MS, back(1_000), SwipeNavDirection.FORWARD),
        )
    }

    /**
     * The paused-drag case, and the reason [SWIPE_NAV_REPEAT_MS] exists: `swipe-nav.js` segments
     * gestures on `GESTURE_GAP_MS` of quiet, which a drag that hesitates mid-swipe produces with
     * the fingers still down. `scripts/test/test-swipe-nav.js` pins that the script really does
     * emit two commits there - it has no way not to - so this is where the second one dies.
     */
    @Test
    fun `a same-direction repeat inside the repeat window is refused`() {
        assertFalse(shouldAcceptSwipeNav(1_000 + GESTURE_GAP_MS_FLOOR, back(1_000), SwipeNavDirection.BACK))
        assertFalse(shouldAcceptSwipeNav(1_000 + SWIPE_NAV_REPEAT_MS, back(1_000), SwipeNavDirection.BACK))
    }

    @Test
    fun `and accepted once the repeat window has passed`() {
        assertTrue(shouldAcceptSwipeNav(1_001 + SWIPE_NAV_REPEAT_MS, back(1_000), SwipeNavDirection.BACK))
    }

    /**
     * Both windows are bounded against `swipe-nav.js`'s own `GESTURE_GAP_MS`, read OUT of the
     * script rather than restated here. The whole argument is a cross-language coupling to a
     * constant in another file: hard-coded, changing the gap would leave this green while quietly
     * falsifying its own reasoning.
     *
     * The debounce sits strictly below the gap - that is the minimum possible distance between two
     * gesture ends, so anything at or above it starts refusing real swipes - and at or above one
     * frame, which is the shape a bridge-level double-dispatch takes. The repeat window sits
     * strictly above it, because a hesitation the script mistook for a lift is by definition longer
     * than the gap.
     */
    @Test
    fun `both windows are bounded against the script's gesture gap`() {
        val match = GESTURE_GAP_MS_IN_SCRIPT.find(BrowserSwipeNavScript.source)
        assertNotNull(match, "GESTURE_GAP_MS not found in swipe-nav.js")
        val gapMs = match.groupValues[1].toLong()
        assertTrue(
            SWIPE_NAV_DEBOUNCE_MS < gapMs,
            "$SWIPE_NAV_DEBOUNCE_MS must stay under the script's ${gapMs}ms gesture gap, or it can " +
                "reject a genuinely separate swipe",
        )
        assertTrue(
            SWIPE_NAV_DEBOUNCE_MS >= ONE_FRAME_MS,
            "$SWIPE_NAV_DEBOUNCE_MS must still cover a same-frame double-dispatch",
        )
        assertTrue(
            SWIPE_NAV_REPEAT_MS > gapMs,
            "$SWIPE_NAV_REPEAT_MS must exceed the script's ${gapMs}ms gesture gap, or it guards " +
                "nothing the script did not already merge",
        )
        // The value the other tests use for "one gesture gap later" has to be the real one.
        assertEquals(gapMs, GESTURE_GAP_MS_FLOOR)
    }

    // --- The gate that holds that state ------------------------------------------------------

    @Test
    fun `the gate records a commit and refuses the repeat that follows it`() {
        var now = 1_000L
        val gate = SwipeNavGate(nowMs = { now })
        assertTrue(gate.accept(SwipeNavDirection.BACK))
        now += GESTURE_GAP_MS_FLOOR
        assertFalse(gate.accept(SwipeNavDirection.BACK), "the second half of a paused drag")
        now += SWIPE_NAV_REPEAT_MS
        assertTrue(gate.accept(SwipeNavDirection.BACK), "a deliberate second swipe later on")
    }

    /**
     * A refused swipe must not push the window forward. Otherwise a page calling the bridge in a
     * loop would hold the window open indefinitely and a real swipe after it would be refused too.
     */
    @Test
    fun `a refused swipe does not restart the window`() {
        var now = 1_000L
        val gate = SwipeNavGate(nowMs = { now })
        assertTrue(gate.accept(SwipeNavDirection.BACK))
        for (step in 1..10) {
            now = 1_000L + step
            assertFalse(gate.accept(SwipeNavDirection.BACK))
        }
        now = 1_001L + SWIPE_NAV_REPEAT_MS
        assertTrue(gate.accept(SwipeNavDirection.BACK))
    }

    /**
     * The check and the record are one compare-and-set, so of two callers reading the same state
     * exactly one wins. `@Volatile` on a plain field gave visibility and not atomicity: both would
     * have passed the check and both would have navigated.
     */
    @Test
    fun `only one of two racing swipes is accepted`() {
        val gate = SwipeNavGate(nowMs = { 1_000L })
        val threads = 8
        val start = CountDownLatch(1)
        val accepted = AtomicInteger(0)
        val done = CountDownLatch(threads)
        repeat(threads) {
            Thread {
                start.await()
                if (gate.accept(SwipeNavDirection.BACK)) accepted.incrementAndGet()
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS), "racing threads never finished")
        assertEquals(1, accepted.get(), "exactly one swipe may win the race")
    }

    // --- What the host pushes into the page --------------------------------------------------

    @Test
    fun `state is pushed as booleans on the property the script reads`() {
        assertEquals(
            "window.${BrowserSwipeNavScript.STATE_PROPERTY} = " +
                "{ enabled: true, back: true, forward: false };",
            BrowserSwipeNavScript.stateUpdate(enabled = true, canGoBack = true, canGoForward = false),
        )
    }

    /**
     * The resource has to be on the classpath, not just on disk. A missing one logs and returns
     * empty, which would leave every page silently gestureless - the exact failure this whole
     * feature is being built to remove.
     */
    @Test
    fun `the gesture script is packaged`() {
        val source = BrowserSwipeNavScript.source
        assertTrue(source.isNotEmpty(), "swipe-nav.js missing from the classpath")
        assertTrue(
            source.contains(BrowserSwipeNavScript.BRIDGE_PROPERTY),
            "the packaged script does not name ${BrowserSwipeNavScript.BRIDGE_PROPERTY}",
        )
        assertTrue(
            source.contains(BrowserSwipeNavScript.STATE_PROPERTY),
            "the packaged script does not name ${BrowserSwipeNavScript.STATE_PROPERTY}",
        )
    }

    private companion object {
        // Anchored to the start of a line and tolerant of spacing, so a commented-out declaration
        // is not what gets read. It still fails loudly rather than wrongly if the shape changes:
        // no match is an assertion failure, not a silently skipped bound.
        val GESTURE_GAP_MS_IN_SCRIPT = Regex("""^\s*var GESTURE_GAP_MS\s*=\s*(\d+)""", RegexOption.MULTILINE)
        const val ONE_FRAME_MS = 16L

        // Kept honest against the script by `both windows are bounded against the script's gesture
        // gap`, so the cases below can say "one gesture gap later" without re-parsing the source.
        const val GESTURE_GAP_MS_FLOOR = 120L

        fun back(atMs: Long) = SwipeNavCommit(atMs, SwipeNavDirection.BACK)
    }
}
