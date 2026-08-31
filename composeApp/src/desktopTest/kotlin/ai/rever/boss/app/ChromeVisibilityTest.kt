package ai.rever.boss.app

import ai.rever.boss.focusmode.FocusModeEdge
import ai.rever.boss.window.WindowAppearanceSettings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [chromeVisibility], which folds the three independent reasons a bar can be missing.
 *
 * The reasons are not interchangeable and the failure mode of confusing them is documented in
 * `docs/release-notes/v9.4.13.md:47` - a predicate that read correctly, collapsed two of them, and
 * left Sign Out rendered nowhere. These tests exist so a fourth reason cannot be added by editing
 * five inlined conjunctions.
 */
class ChromeVisibilityTest {
    private val everythingOn =
        WindowAppearanceSettings(
            showTitleBar = true,
            showTopBar = true,
            showBottomBar = true,
            showLeftStrip = true,
            showRightStrip = true,
        )

    /** A reveal state with every edge shown, i.e. focus mode clearing nothing. */
    private fun revealAllShown(): FocusModeRevealState =
        FocusModeRevealState().apply {
            FocusModeEdge.entries.forEach { this[it].shown = true }
        }

    @Test
    fun `everything on and nothing cleared draws every bar`() {
        val chrome =
            chromeVisibility(
                appearance = everythingOn,
                reveal = revealAllShown(),
                capturedFullScreen = false,
                titleRowWanted = true,
            )
        assertTrue(chrome.topBar)
        assertTrue(chrome.leftStrip)
        assertTrue(chrome.rightStrip)
        assertTrue(chrome.bottomBar)
        assertTrue(chrome.titleRow)
    }

    @Test
    fun `captured full screen clears every bar, whatever the preferences say`() {
        // The point of the mode: a bar the user explicitly switched on is still not drawn.
        val chrome =
            chromeVisibility(
                appearance = everythingOn,
                reveal = revealAllShown(),
                capturedFullScreen = true,
                titleRowWanted = true,
            )
        assertFalse(chrome.topBar)
        assertFalse(chrome.leftStrip)
        assertFalse(chrome.rightStrip)
        assertFalse(chrome.bottomBar)
        assertFalse(
            chrome.titleRow,
            "The title row is asked for by the traffic-light rule, which does not know about this " +
                "mode. Capture has to outrank it or a full-width bar is drawn over a display the " +
                "user asked to hold nothing but content.",
        )
    }

    @Test
    fun `the standing preference alone hides a bar`() {
        val chrome =
            chromeVisibility(
                appearance = everythingOn.copy(showTopBar = false),
                reveal = revealAllShown(),
                capturedFullScreen = false,
                titleRowWanted = true,
            )
        assertFalse(chrome.topBar)
        assertTrue(chrome.bottomBar, "Only the bar that was switched off")
    }

    @Test
    fun `a hover-revealed bar comes back while the preference still allows it`() {
        // The two reasons are genuinely independent: focus mode clearing an edge is transient and
        // reversible by hover, the preference is not.
        val reveal = revealAllShown()
        reveal[FocusModeEdge.TOP].shown = false

        val cleared =
            chromeVisibility(
                appearance = everythingOn,
                reveal = reveal,
                capturedFullScreen = false,
                titleRowWanted = true,
            )
        assertFalse(cleared.topBar)

        reveal[FocusModeEdge.TOP].shown = true
        val revealed =
            chromeVisibility(
                appearance = everythingOn,
                reveal = reveal,
                capturedFullScreen = false,
                titleRowWanted = true,
            )
        assertTrue(revealed.topBar)
    }

    @Test
    fun `a bar switched off does not come back on hover`() {
        // Both must agree. A hover-reveal cannot override the standing choice, which is exactly the
        // asymmetry the release note warns about.
        val reveal = revealAllShown()
        reveal[FocusModeEdge.TOP].shown = true

        val chrome =
            chromeVisibility(
                appearance = everythingOn.copy(showTopBar = false),
                reveal = reveal,
                capturedFullScreen = false,
                titleRowWanted = true,
            )
        assertFalse(chrome.topBar)
    }

    @Test
    fun `the title row follows the traffic-light rule when not captured`() {
        val wanted =
            chromeVisibility(everythingOn, revealAllShown(), capturedFullScreen = false, titleRowWanted = true)
        val notWanted =
            chromeVisibility(everythingOn, revealAllShown(), capturedFullScreen = false, titleRowWanted = false)
        assertTrue(wanted.titleRow)
        assertFalse(notWanted.titleRow)
    }

    @Test
    fun `focus mode does not touch the title row`() {
        // The title row is above every edge focus mode clears, and holds the traffic lights. Only
        // capture takes it away.
        val reveal = revealAllShown()
        FocusModeEdge.entries.forEach { reveal[it].shown = false }
        val chrome =
            chromeVisibility(everythingOn, reveal, capturedFullScreen = false, titleRowWanted = true)
        assertTrue(chrome.titleRow)
    }
}
