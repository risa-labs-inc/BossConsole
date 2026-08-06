package ai.rever.boss.plugin.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a [BossPopup] actually reports as its anchor, measured through real Compose layout.
 *
 * This exists because three separate placement bugs shipped past compile, 1561 unit tests, detekt and
 * ktlint, and were caught only by looking at the screen. Every one of them lived in the layout
 * boundary rather than in arithmetic: a clipped rect that collapsed to the origin, a `fillMaxWidth()`
 * that was reparented into the overlay window, and a coordinate traversal that did not reach the
 * caller. Pure-function tests cannot see any of that, so this reproduces the exact structure the
 * browser plugin uses - a half-width, top-centred, y-offset anchor Box - and asserts on the values the
 * renderer is handed.
 */
class BossPopupAnchorLayoutTest {
    @get:Rule
    val rule = createComposeRule()

    @After
    fun resetRegistry() {
        BossOverlayHost.useHeavyweightOverlays = false
        BossOverlayHost.popupRenderer = null
    }

    private fun captureAnchor(): IntRect? {
        var captured: IntRect? = null
        BossOverlayHost.useHeavyweightOverlays = true
        BossOverlayHost.popupRenderer = { _, anchorInWindow, _, _, _, _ -> captured = anchorInWindow }
        rule.setContent {
            CompositionLocalProvider(LocalHeavyweightOverlays provides true) {
                // The browser plugin's structure: a half-width anchor, centred, pushed below the
                // toolbar. The popup content is deliberately non-trivial so a wrong parent shows up.
                Box(Modifier.size(width = 1000.dp, height = 800.dp)) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth(0.5f)
                                .align(Alignment.TopCenter)
                                .offset(y = 38.dp),
                    ) {
                        BossPopup(
                            onDismissRequest = {},
                            focusable = false,
                            anchoring = BossPopupAnchoring.AnchorBounds,
                        ) {
                            Text("suggestion")
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        return captured
    }

    @Test
    fun `the anchor carries the caller's width, which is what the content is sized to`() {
        val anchor = requireNotNull(captureAnchor()) { "renderer was never invoked" }
        // Zero width is the failure that let the list stretch edge to edge: with no width to apply,
        // the content inherited the overlay window's and was then clamped to x = 0.
        assertEquals(500, anchor.width, "anchor width should be half of the 1000dp parent, got $anchor")
    }

    @Test
    fun `the anchor carries the caller's position`() {
        val anchor = requireNotNull(captureAnchor()) { "renderer was never invoked" }
        assertEquals(250, anchor.left, "a half-width TopCenter box starts a quarter in, got $anchor")
        assertEquals(38, anchor.top, "the y offset is where the list opens, got $anchor")
    }
}

/**
 * That the anchor probe is genuinely layout-neutral.
 *
 * It was not: the width was adopted with `fillMaxWidth()`, which sets `minWidth = maxWidth` and so
 * claims the caller's full width. That starves later siblings in a `Row`, adds a phantom gap in a
 * `Column` with `Arrangement.spacedBy`, and - because the lightweight `Popup` is nested inside the
 * probe - changes anchoring even on OFF_SCREEN installs that never route heavyweight. `BossPopup` is
 * documented as a drop-in for `Popup`, which emits a genuine 0x0 node, so this is part of the
 * contract rather than a detail.
 */
class BossPopupLayoutNeutralityTest {
    @get:Rule
    val rule = createComposeRule()

    @After
    fun resetRegistry() {
        BossOverlayHost.useHeavyweightOverlays = false
        BossOverlayHost.popupRenderer = null
    }

    @Test
    fun `a sibling after the popup keeps its position`() {
        BossOverlayHost.useHeavyweightOverlays = true
        BossOverlayHost.popupRenderer = { _, _, _, _, _, _ -> }
        rule.setContent {
            CompositionLocalProvider(LocalHeavyweightOverlays provides true) {
                Row(Modifier.size(width = 1000.dp, height = 100.dp)) {
                    BossPopup(onDismissRequest = {}, anchoring = BossPopupAnchoring.AnchorBounds) {
                        Text("popup")
                    }
                    Text("sibling", modifier = Modifier.testTag("sibling"))
                }
            }
        }
        rule.waitForIdle()
        // A probe claiming the full width would push this off the right edge entirely.
        rule.onNodeWithTag("sibling").assertIsDisplayed()
        val left = rule.onNodeWithTag("sibling").getUnclippedBoundsInRoot().left
        assertTrue(left.value < 50f, "sibling should start at the row's left edge, was at $left")
    }
}

/**
 * That an anchored popup is never handed an unmeasured anchor.
 *
 * Reported from a live session: the suggestion list flashed in the top-left corner at full width for
 * a couple of hundred milliseconds before snapping into place. The renderer was invoked on the first
 * composition, before `onGloballyPositioned` had run, so it was placed at the window origin with no
 * width - and the overlay window's own creation latency made that visible rather than a single frame.
 *
 * Asserts on EVERY invocation, not just the last: a single bad first call is exactly the bug, and
 * checking the final value would pass while the flash remained.
 */
class BossPopupFirstFrameTest {
    @get:Rule
    val rule = createComposeRule()

    @After
    fun resetRegistry() {
        BossOverlayHost.useHeavyweightOverlays = false
        BossOverlayHost.popupRenderer = null
    }

    @Test
    fun `an anchored popup is never placed at the origin, not even on the first frame`() {
        val seen = mutableListOf<IntRect>()
        BossOverlayHost.useHeavyweightOverlays = true
        BossOverlayHost.popupRenderer = { _, anchorInWindow, _, _, _, _ -> seen += anchorInWindow }
        rule.setContent {
            CompositionLocalProvider(LocalHeavyweightOverlays provides true) {
                Box(Modifier.size(width = 1000.dp, height = 800.dp)) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth(0.5f)
                                .align(Alignment.TopCenter)
                                .offset(y = 38.dp),
                    ) {
                        BossPopup(
                            onDismissRequest = {},
                            focusable = false,
                            anchoring = BossPopupAnchoring.AnchorBounds,
                        ) {
                            Text("suggestion")
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        assertTrue(seen.isNotEmpty(), "renderer should eventually be invoked")
        assertTrue(
            seen.none { it == IntRect.Zero },
            "an unmeasured anchor reached the renderer, which is the top-left flash: $seen",
        )
    }

    @Test
    fun `cursor anchoring is not delayed, since it never reads the anchor`() {
        var invoked = false
        BossOverlayHost.useHeavyweightOverlays = true
        BossOverlayHost.popupRenderer = { _, _, _, _, _, _ -> invoked = true }
        rule.setContent {
            CompositionLocalProvider(LocalHeavyweightOverlays provides true) {
                Box(Modifier.size(200.dp)) {
                    BossPopup(onDismissRequest = {}, anchoring = BossPopupAnchoring.Cursor) {
                        Text("menu")
                    }
                }
            }
        }
        rule.waitForIdle()
        assertTrue(invoked, "a cursor-anchored menu must not wait for a measurement it never uses")
    }
}
