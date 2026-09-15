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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        resetOverlayFieldForTest("useHeavyweightOverlays")
        BossOverlayHost.useHeavyweightOverlays = false
        resetOverlayFieldForTest("popupRenderer")
        BossOverlayHost.popupRenderer = null
    }

    private fun captureAnchor(): IntRect? {
        var captured: IntRect? = null
        resetOverlayFieldForTest("useHeavyweightOverlays")
        BossOverlayHost.useHeavyweightOverlays = true
        resetOverlayFieldForTest("popupRenderer")
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
        resetOverlayFieldForTest("useHeavyweightOverlays")
        BossOverlayHost.useHeavyweightOverlays = false
        resetOverlayFieldForTest("popupRenderer")
        BossOverlayHost.popupRenderer = null
    }

    @Test
    fun `a sibling after the popup keeps its position`() {
        resetOverlayFieldForTest("useHeavyweightOverlays")
        BossOverlayHost.useHeavyweightOverlays = true
        resetOverlayFieldForTest("popupRenderer")
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

/** Plugin surfaces need a real window when their popup crosses into a sibling browser pane. */
class BossPopupPluginLayeringTest {
    @get:Rule
    val rule = createComposeRule()

    @After
    fun resetRegistry() {
        resetOverlayFieldForTest("useHeavyweightOverlays")
        BossOverlayHost.useHeavyweightOverlays = false
        resetOverlayFieldForTest("popupRenderer")
        BossOverlayHost.popupRenderer = null
    }

    @Test
    fun `an off-screen plugin popup escapes through the host renderer`() {
        var invoked = false
        resetOverlayFieldForTest("useHeavyweightOverlays")
        BossOverlayHost.useHeavyweightOverlays = false
        resetOverlayFieldForTest("popupRenderer")
        BossOverlayHost.popupRenderer = { _, _, _, _, _, _ -> invoked = true }

        rule.setContent {
            CompositionLocalProvider(
                LocalHeavyweightOverlays provides true,
                LocalPopupLayeringRequired provides true,
            ) {
                BossPopup(onDismissRequest = {}) { Text("plugin menu") }
            }
        }
        rule.waitForIdle()

        assertTrue(invoked, "plugin popup stayed in the Compose scene under OFF_SCREEN")
    }

    @Test
    fun `an off-screen host popup keeps the lightweight path`() {
        var invoked = false
        resetOverlayFieldForTest("useHeavyweightOverlays")
        BossOverlayHost.useHeavyweightOverlays = false
        resetOverlayFieldForTest("popupRenderer")
        BossOverlayHost.popupRenderer = { _, _, _, _, _, _ -> invoked = true }

        rule.setContent {
            CompositionLocalProvider(LocalHeavyweightOverlays provides true) {
                BossPopup(onDismissRequest = {}) { Text("host menu") }
            }
        }
        rule.waitForIdle()

        assertFalse(invoked, "ordinary OFF_SCREEN host popups must not pay for a native window")
    }

    @Test
    fun `a plugin popup in a secondary window stays with that window`() {
        var invoked = false
        resetOverlayFieldForTest("useHeavyweightOverlays")
        BossOverlayHost.useHeavyweightOverlays = false
        resetOverlayFieldForTest("popupRenderer")
        BossOverlayHost.popupRenderer = { _, _, _, _, _, _ -> invoked = true }

        rule.setContent {
            CompositionLocalProvider(
                LocalHeavyweightOverlays provides false,
                LocalPopupLayeringRequired provides true,
            ) {
                BossPopup(onDismissRequest = {}) { Text("secondary menu") }
            }
        }
        rule.waitForIdle()

        assertFalse(invoked, "a main-window renderer must not capture a secondary-window popup")
    }

    @Test
    fun `the plugin override preserves popup behavior parameters`() {
        var captured: Triple<BossPopupAnchoring, IntOffset, Boolean>? = null
        resetOverlayFieldForTest("useHeavyweightOverlays")
        BossOverlayHost.useHeavyweightOverlays = false
        resetOverlayFieldForTest("popupRenderer")
        BossOverlayHost.popupRenderer = { _, _, anchoring, offset, focusable, _ ->
            captured = Triple(anchoring, offset, focusable)
        }

        rule.setContent {
            CompositionLocalProvider(
                LocalHeavyweightOverlays provides true,
                LocalPopupLayeringRequired provides true,
            ) {
                BossPopup(
                    onDismissRequest = {},
                    offset = IntOffset(13, -7),
                    focusable = true,
                    anchoring = BossPopupAnchoring.Cursor,
                ) { Text("menu") }
            }
        }
        rule.waitForIdle()

        assertEquals(
            Triple(BossPopupAnchoring.Cursor, IntOffset(13, -7), true),
            captured,
        )
    }

    @Test
    fun `an anchored plugin popup keeps intrinsic control-relative placement`() {
        var captured: IntRect? = null
        resetOverlayFieldForTest("useHeavyweightOverlays")
        BossOverlayHost.useHeavyweightOverlays = false
        resetOverlayFieldForTest("popupRenderer")
        BossOverlayHost.popupRenderer = { _, anchor, _, _, _, _ -> captured = anchor }

        rule.setContent {
            CompositionLocalProvider(
                LocalHeavyweightOverlays provides true,
                LocalPopupLayeringRequired provides true,
            ) {
                Box(Modifier.size(width = 600.dp, height = 400.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.5f)
                            .align(Alignment.TopCenter)
                            .offset(y = 20.dp),
                    ) {
                        BossPopup(
                            onDismissRequest = {},
                            anchoring = BossPopupAnchoring.AnchorBounds,
                        ) { Text("anchored menu") }
                    }
                }
            }
        }
        rule.waitForIdle()

        assertEquals(IntRect(150, 20, 450, 20), captured)
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
        resetOverlayFieldForTest("useHeavyweightOverlays")
        BossOverlayHost.useHeavyweightOverlays = false
        resetOverlayFieldForTest("popupRenderer")
        BossOverlayHost.popupRenderer = null
    }

    @Test
    fun `an anchored popup is never placed at the origin, not even on the first frame`() {
        val seen = mutableListOf<IntRect>()
        resetOverlayFieldForTest("useHeavyweightOverlays")
        BossOverlayHost.useHeavyweightOverlays = true
        resetOverlayFieldForTest("popupRenderer")
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
        resetOverlayFieldForTest("useHeavyweightOverlays")
        BossOverlayHost.useHeavyweightOverlays = true
        resetOverlayFieldForTest("popupRenderer")
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
