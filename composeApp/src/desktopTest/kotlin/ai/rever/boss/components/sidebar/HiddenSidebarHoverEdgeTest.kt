package ai.rever.boss.components.sidebar

import ai.rever.boss.components.overlays.OverlayConfig
import ai.rever.boss.components.window_panel.components.main_window_panels.TabBarLayout
import ai.rever.boss.components.window_panel.components.main_window_panels.TabBarRevealState
import ai.rever.boss.components.window_panel.components.main_window_panels.rememberTabBarRevealState
import ai.rever.boss.plugin.ui.LocalHeavyweightOverlays
import ai.rever.boss.window.LocalWindowFullscreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HiddenSidebarHoverEdgeTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var reveal: TabBarRevealState

    // Since #1828 the reveal is driven by the native cursor tracker (TrackTabBarRevealPointer,
    // whose geometry TabBarRevealPointerTest pins): reaching the window's left edge sets
    // pointerAtEdge for a frame, and the 100dp retention margin keeps pointerInRevealArea true.
    // These tests feed those two signals directly, since the tracker polls AWT's real cursor.

    @Test
    fun edgeRevealOverlaysWithoutTakingContentWidth() {
        render(focused = true, enabled = true)
        rule.onNodeWithTag("content").assertWidthIsEqualTo(320.dp)
        reachEdge()
        rule.runOnIdle { assertTrue(reveal.drawerVisible) }
        rule.onNodeWithTag("content").assertWidthIsEqualTo(320.dp)
        rule.runOnIdle { reveal.pointerInRevealArea = false }
        rule.mainClock.advanceTimeBy(400)
        rule.runOnIdle { assertFalse(reveal.drawerVisible) }
    }

    @Test
    fun heavyweightHostWithoutNativeCursorUsesAnInTreeEdge() {
        render(focused = true, enabled = true, heavyweight = true, integratedPanel = true)
        rule.onNodeWithTag("window").performMouseInput {
            enter(Offset(with(rule.density) { 0.5.dp.toPx() }, 100f))
        }
        rule.mainClock.advanceTimeBy(16)
        rule.runOnIdle { assertTrue(reveal.drawerVisible) }
        rule.onNodeWithTag("content").assertWidthIsEqualTo(320.dp)
    }

    @Test
    fun nativeCursorRevealNeedsNoInvisibleEdgeWindow() {
        val previousRenderer = OverlayConfig.heavyweightCorner
        var windows = 0
        try {
            OverlayConfig.heavyweightCorner = { _, _, _, _, _, _ -> windows++ }
            render(focused = true, enabled = true, heavyweight = true)
            reachEdge()
            rule.runOnIdle {
                assertTrue(reveal.drawerVisible)
                assertEquals(0, windows)
            }
            rule.onNodeWithTag("content").assertWidthIsEqualTo(320.dp)
        } finally {
            OverlayConfig.heavyweightCorner = previousRenderer
        }
    }

    @Test
    fun stationaryPointerInRetentionMarginKeepsIntegratedSidebarOpen() {
        render(focused = true, enabled = true, integratedPanel = true)
        reachEdge()
        rule.runOnIdle { assertTrue(reveal.drawerVisible) }
        // Remain still for several close periods: the one-frame edge signal is gone, and the
        // retention margin alone must hold the drawer rather than let it flicker away.
        repeat(12) {
            rule.mainClock.advanceTimeBy(100)
            rule.runOnIdle { assertTrue(reveal.drawerVisible) }
        }
        rule.runOnIdle { reveal.pointerInRevealArea = false }
        rule.mainClock.advanceTimeBy(400)
        rule.runOnIdle { assertFalse(reveal.drawerVisible) }
    }

    @Test
    fun hoveringFortyDpFromEdgeNoLongerReveals() {
        // BossTerm's 44dp hover strip used to reveal here; activation is now the window edge only.
        render(focused = true, enabled = true, integratedPanel = true)
        rule.onNodeWithTag("window").performMouseInput {
            enter(Offset(with(rule.density) { 40.dp.toPx() }, 100f))
        }
        rule.mainClock.advanceTimeBy(500)
        rule.runOnIdle { assertFalse(reveal.drawerVisible) }
    }

    @Test
    fun hoveringBeyondFortyFourDpDoesNotReveal() {
        render(focused = true, enabled = true)
        rule.onNodeWithTag("window").performMouseInput {
            enter(Offset(with(rule.density) { 48.dp.toPx() }, 100f))
        }
        rule.mainClock.advanceTimeBy(500)
        rule.runOnIdle { assertFalse(reveal.drawerVisible) }
    }

    @Test
    fun inactiveWindowDoesNotReveal() {
        render(focused = false, enabled = true)
        enterEdge()
        rule.runOnIdle { assertFalse(reveal.drawerVisible) }
    }

    @Test
    fun disabledHoverPreferenceDoesNotReveal() {
        render(focused = true, enabled = false)
        enterEdge()
        rule.runOnIdle { assertFalse(reveal.drawerVisible) }
    }

    @Test
    fun fullscreenDoesNotRevealFromEdge() {
        render(focused = true, enabled = true, fullscreen = true)
        enterEdge()
        rule.runOnIdle { assertFalse(reveal.drawerVisible) }
    }

    private fun render(
        focused: Boolean,
        enabled: Boolean,
        integratedPanel: Boolean = false,
        fullscreen: Boolean = false,
        heavyweight: Boolean = false,
    ) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(
                LocalHeavyweightOverlays provides heavyweight,
                LocalWindowFullscreen provides fullscreen,
                LocalWindowInfo provides
                    object : WindowInfo {
                        override val isWindowFocused = focused
                    },
            ) {
                reveal = rememberTabBarRevealState(railShown = true, narrow = false, hoverExpand = enabled)
                Box(Modifier.size(320.dp, 200.dp).testTag("window")) {
                    Box(Modifier.fillMaxSize().testTag("content"))
                    if (integratedPanel && reveal.drawerVisible) {
                        Box(
                            Modifier.width(200.dp).fillMaxHeight().then(
                                windowSidebarModifier(
                                    TabBarLayout(true, 200.dp, false, false, true),
                                    reveal,
                                    0.dp,
                                    true,
                                ),
                            ),
                        )
                    }
                    HiddenSidebarHoverEdge(reveal, enabled)
                }
            }
        }
    }

    /** The tracker's view of a pointer reaching the edge: one frame at it, then inside the margin. */
    private fun reachEdge() {
        rule.runOnIdle {
            reveal.pointerAtEdge = true
            reveal.pointerInRevealArea = true
        }
        rule.mainClock.advanceTimeBy(16)
        rule.runOnIdle { reveal.pointerAtEdge = false }
        rule.mainClock.advanceTimeBy(16)
    }

    private fun enterEdge() {
        rule.onNodeWithTag("window").performMouseInput { enter(Offset(4f, 100f)) }
        rule.mainClock.advanceTimeBy(200)
    }
}
