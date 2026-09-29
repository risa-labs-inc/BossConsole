package ai.rever.boss.components.sidebar

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
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HiddenSidebarHoverEdgeTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var reveal: TabBarRevealState

    @Test
    fun hoveringEdgeRevealsWithoutTakingContentWidth() {
        render(focused = true, enabled = true)
        rule.onNodeWithTag("content").assertWidthIsEqualTo(320.dp)
        enterEdge()
        rule.runOnIdle { assertTrue(reveal.drawerVisible) }
        rule.onNodeWithTag("window").performMouseInput { moveTo(Offset(160f, 100f)) }
        rule.mainClock.advanceTimeBy(400)
        rule.runOnIdle { assertFalse(reveal.drawerVisible) }
    }

    @Test
    fun stationaryPointerInOuterGapKeepsIntegratedSidebarOpen() {
        render(focused = true, enabled = true, integratedPanel = true)
        rule.onNodeWithTag("window").performMouseInput { enter(Offset(2f, 100f)) }
        rule.mainClock.advanceTimeBy(200)
        rule.runOnIdle { assertTrue(reveal.drawerVisible) }
        // Remain still for several open/close periods: the removed edge target must hand off
        // to the full panel's margin, not continually disappear and reappear under the pointer.
        repeat(12) {
            rule.mainClock.advanceTimeBy(100)
            rule.runOnIdle { assertTrue(reveal.drawerVisible) }
        }
        rule.onNodeWithTag("window").performMouseInput { moveTo(Offset(280f, 100f)) }
        rule.mainClock.advanceTimeBy(400)
        rule.runOnIdle { assertFalse(reveal.drawerVisible) }
    }

    @Test
    fun hoveringFortyDpFromEdgeRevealsLikeBossTerm() {
        render(focused = true, enabled = true, integratedPanel = true)
        rule.onNodeWithTag("window").performMouseInput {
            enter(Offset(with(rule.density) { 40.dp.toPx() }, 100f))
        }
        rule.mainClock.advanceTimeBy(200)
        rule.runOnIdle { assertTrue(reveal.drawerVisible) }
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
    ) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(
                LocalHeavyweightOverlays provides false,
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
                    HiddenSidebarHoverEdge(reveal, IntRect(0, 0, 320, 200), enabled)
                }
            }
        }
    }

    private fun enterEdge() {
        rule.onNodeWithTag("window").performMouseInput { enter(Offset(4f, 100f)) }
        rule.mainClock.advanceTimeBy(200)
    }
}
