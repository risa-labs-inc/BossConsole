package ai.rever.boss.app

import ai.rever.boss.components.window_panel.components.main_window_panels.VerticalTabBarDrawer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The chevron drawer's click-catcher must leave the retained rail interactive.
 *
 * SplitView composes the rail (WindowBarRow) BEFORE the drawer, so the drawer's dismissal
 * catcher - a fillMaxSize Box - sits on top of the rail. Pressing a retained quick action must
 * fire the action, not dismiss the drawer. The rail must also keep its hover (its Swing labels
 * are driven by it); the hit-region exclusion that keeps the rail pressable is the same one,
 * but hover itself is not asserted here. Pressing the part of the panel the drawer does not
 * cover still dismisses.
 *
 * The catcher is mode-independent code, so what this test pins holds in both rendering modes;
 * the panel-region press additionally depends on the lightweight layout, which is the path
 * composed in a test (`OverlayConfig.heavyweightCorner` is never set there, so
 * `overlayCornerIsHeavyweight()` is false) - and under HARDWARE the catcher can be occluded by
 * the browser's native surface entirely (see the chevron comment in `WindowVerticalTabBar`).
 * In this test the drawer's stub content has no pointer input, so presses inside it fall
 * through to the catcher; that pre-existing behaviour is deliberately not pinned here.
 */
class VerticalTabBarDrawerCatcherTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the retained rail stays interactive under the chevron drawer's click-catcher`() {
        var dismissals = 0
        var railClicks = 0
        rule.setContent {
            Box(modifier = Modifier.size(PANEL_SIZE)) {
                // Composed first, exactly as SplitView composes WindowBarRow before RevealedBar.
                // Clickable with its own counter so the test pins that the rail action FIRES,
                // not only that nothing dismissed.
                Box(
                    modifier =
                        Modifier
                            .width(RAIL_WIDTH)
                            .fillMaxHeight()
                            .testTag(RAIL_TAG)
                            .clickable { railClicks++ },
                )
                VerticalTabBarDrawer(
                    visible = true,
                    hoverSource = remember { MutableInteractionSource() },
                    hoverEnabled = false,
                    width = PANEL_SIZE,
                    railWidth = RAIL_WIDTH,
                    // Exists only to satisfy the parameter's null guard; nothing on this
                    // path reads its dimensions.
                    panelRegion = IntRect(0, 0, 300, 400),
                    onDismissOutside = { dismissals++ },
                ) {
                    Box(modifier = Modifier.size(DRAWER_WIDTH, DRAWER_HEIGHT))
                }
                // Fills the panel area to the right of the drawer - x in
                // [RAIL_WIDTH + DRAWER_WIDTH, PANEL_SIZE]: inside the catcher's hit region,
                // outside the drawer's content.
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = RAIL_WIDTH + DRAWER_WIDTH)
                            .width(PANEL_SIZE - RAIL_WIDTH - DRAWER_WIDTH)
                            .fillMaxHeight()
                            .testTag(PANEL_RIGHT_TAG),
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithTag(RAIL_TAG).performClick()
        assertEquals(
            1,
            railClicks,
            "pressing the retained rail must fire the rail's own action",
        )
        assertEquals(
            0,
            dismissals,
            "pressing the retained rail must not count as an outside click",
        )

        rule.onNodeWithTag(PANEL_RIGHT_TAG).performClick()
        assertEquals(
            1,
            dismissals,
            "pressing the part of the panel the drawer does not cover still dismisses the drawer",
        )
    }

    private companion object {
        val RAIL_WIDTH = 36.dp
        val PANEL_SIZE = 220.dp
        val DRAWER_WIDTH = 100.dp
        val DRAWER_HEIGHT = 200.dp
        const val RAIL_TAG = "retained-rail"
        const val PANEL_RIGHT_TAG = "panel-right-of-drawer"
    }
}
