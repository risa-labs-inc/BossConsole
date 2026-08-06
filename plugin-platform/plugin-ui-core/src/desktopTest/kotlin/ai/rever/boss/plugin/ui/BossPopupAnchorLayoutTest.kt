package ai.rever.boss.plugin.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

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
