package ai.rever.boss.plugin.browser

import ai.rever.boss.config.SwipeNavStyle
import ai.rever.boss.config.parseSwipeNavStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertTrue(swipeNavEnabled(isMac = true, style = SwipeNavStyle.CHEVRON))
        assertTrue(swipeNavEnabled(isMac = true, style = SwipeNavStyle.SLIDE))
    }

    @Test
    fun `off everywhere else, whatever the style says`() {
        assertFalse(swipeNavEnabled(isMac = false, style = SwipeNavStyle.CHEVRON))
        assertFalse(swipeNavEnabled(isMac = false, style = SwipeNavStyle.SLIDE))
    }

    @Test
    fun `the off style turns it off`() {
        assertFalse(swipeNavEnabled(isMac = true, style = SwipeNavStyle.OFF))
    }

    @Test
    fun `every style spelling the setting writes round-trips`() {
        for (style in SwipeNavStyle.entries) {
            assertEquals(style, parseSwipeNavStyle(style.settingValue), style.name)
        }
    }

    /**
     * The key shipped as an on/off switch before it grew a third state, so someone has `true` or
     * `false` exported. Dropping those spellings would silently change what their shell says.
     */
    @Test
    fun `legacy boolean spellings still parse`() {
        for (value in listOf("false", "0", "no", "off", "FALSE", " off ")) {
            assertEquals(SwipeNavStyle.OFF, parseSwipeNavStyle(value), value)
        }
        for (value in listOf("true", "1", "yes", "on", "TRUE")) {
            assertEquals(SwipeNavStyle.CHEVRON, parseSwipeNavStyle(value), value)
        }
    }

    /**
     * A typo must not silently remove a gesture whose only route back is finding the same typo, so
     * an unparseable value is "no opinion" and the caller keeps its default - never off.
     */
    @Test
    fun `an unparseable value is no opinion, not off`() {
        assertNull(parseSwipeNavStyle("maybe"))
        assertNull(parseSwipeNavStyle(""))
        assertNull(parseSwipeNavStyle(null))
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
        assertTrue(shouldAcceptSwipeNav(nowMs = 1_000, lastNavigationMs = 0))
    }

    @Test
    fun `a second swipe inside the window is refused`() {
        assertFalse(shouldAcceptSwipeNav(nowMs = 1_000 + SWIPE_NAV_DEBOUNCE_MS, lastNavigationMs = 1_000))
        assertFalse(shouldAcceptSwipeNav(nowMs = 1_100, lastNavigationMs = 1_000))
    }

    @Test
    fun `and accepted once the window has passed`() {
        assertTrue(shouldAcceptSwipeNav(nowMs = 1_001 + SWIPE_NAV_DEBOUNCE_MS, lastNavigationMs = 1_000))
    }

    /**
     * Longer than the 100ms the aux mouse buttons use, and deliberately so: a click is discrete,
     * a swipe is one continuous finger movement whose tail can look like a second gesture.
     */
    @Test
    fun `the window is longer than the mouse-button one`() {
        assertTrue(SWIPE_NAV_DEBOUNCE_MS > 100)
    }

    // --- What the host pushes into the page --------------------------------------------------

    @Test
    fun `state is pushed as booleans on the property the script reads`() {
        assertEquals(
            "window.${BrowserSwipeNavScript.STATE_PROPERTY} = { back: true, forward: false };",
            BrowserSwipeNavScript.stateUpdate(canGoBack = true, canGoForward = false),
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
}
