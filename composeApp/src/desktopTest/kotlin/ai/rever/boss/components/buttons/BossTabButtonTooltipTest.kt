package ai.rever.boss.components.buttons

import ai.rever.boss.components.overlays.OverlayConfig
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Regression test for issue #751:
 * "Tab-title tooltip flickers when an activity indicator updates the title"
 *
 * Verifies that updating the tab's [fileName] during hover updates the native tooltip content
 * without triggering an intermediate hide/dismiss callback.
 */
class BossTabButtonTooltipTest {
    @get:Rule
    val rule = createComposeRule()

    @After
    fun tearDown() {
        OverlayConfig.useHeavyweightPopups = false
        OverlayConfig.heavyweightTooltip = null
        OverlayConfig.hideHeavyweightTooltip = null
    }

    @Test
    fun `title update during hover updates tooltip without dismissing or flickering`() {
        val shownTitles = mutableListOf<String>()
        var hideCount = 0

        OverlayConfig.useHeavyweightPopups = true
        OverlayConfig.heavyweightTooltip = { text -> shownTitles += text }
        OverlayConfig.hideHeavyweightTooltip = { hideCount++ }

        var title by mutableStateOf("Initial Title")

        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.size(300.dp, 100.dp)) {
                BossTabButton(
                    fileName = title,
                    modifier = Modifier.testTag("tab-button"),
                    onClick = {},
                )
            }
        }
        rule.waitForIdle()

        // 1. Move pointer over the tab button
        rule.onNodeWithTag("tab-button").performMouseInput {
            moveTo(center)
        }
        rule.waitForIdle()

        // Hover delay is 500ms; advance past it
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()

        assertEquals(
            listOf("Initial Title"),
            shownTitles,
            "Tooltip must be shown with the initial title after hover delay.",
        )
        assertEquals(0, hideCount, "Tooltip must not be hidden while hovering.")

        // 2. Tab title updates (e.g. spinner/activity indicator frame update)
        title = "⠋ Initial Title"
        rule.waitForIdle()

        assertEquals(
            listOf("Initial Title", "⠋ Initial Title"),
            shownTitles,
            "Tooltip text must update when tab title changes.",
        )
        assertEquals(
            0,
            hideCount,
            "Updating title while hovering must NOT invoke hide callback (which causes flicker).",
        )

        // 3. Tab title updates again
        title = "⠙ Initial Title"
        rule.waitForIdle()

        assertEquals(
            listOf("Initial Title", "⠋ Initial Title", "⠙ Initial Title"),
            shownTitles,
            "Tooltip text must update on subsequent title changes.",
        )
        assertEquals(
            0,
            hideCount,
            "Subsequent title updates must still NOT invoke hide callback.",
        )

        // 4. Pointer leaves the tab button -> tooltip must be dismissed
        rule.onNodeWithTag("tab-button").performMouseInput {
            moveTo(Offset(-100f, -100f))
        }
        rule.waitForIdle()

        assertEquals(
            1,
            hideCount,
            "Leaving the tab must invoke hide callback once.",
        )
    }
}
