package ai.rever.boss.components.overlays

import ai.rever.boss.components.buttons.BossTabButton
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class TabTooltipRefreshTest {
    @get:Rule val rule = createComposeRule()

    @Test fun titleUpdatesKeepTooltipVisibleUntilHoverExit() = checkLifetime(removeTab = false)

    @Test fun removingHoveredTabClosesUpdatedTooltip() = checkLifetime(removeTab = true)

    @Test fun verticalTabTitleUpdatesKeepTooltipVisible() = checkLifetime(removeTab = false, vertical = true)

    private fun checkLifetime(
        removeTab: Boolean,
        vertical: Boolean = false,
    ) {
        val previousMode = OverlayConfig.useHeavyweightPopups
        val previousShow = OverlayConfig.heavyweightTooltip
        val previousHide = OverlayConfig.hideHeavyweightTooltip
        val title = mutableStateOf("Working | BossProjects")
        val present = mutableStateOf(true)
        val shown = mutableListOf<String>()
        var hidden = 0
        try {
            resetOverlayFieldForTest("useHeavyweightOverlays")
            OverlayConfig.useHeavyweightPopups = true
            OverlayConfig.heavyweightTooltip = { shown.add(it) }
            OverlayConfig.hideHeavyweightTooltip = { hidden++ }
            rule.setContent {
                if (present.value) {
                    BossTabButton(
                        fileName = title.value,
                        vertical = vertical,
                        modifier = Modifier.size(220.dp, 36.dp).testTag("tab"),
                        onClick = {},
                    )
                }
            }
            rule.onNodeWithTag("tab").performMouseInput { enter(center) }
            rule.mainClock.advanceTimeBy(600)
            rule.runOnIdle { assertEquals(listOf(title.value), shown) }
            val updates = listOf("⠋ Working | BossProjects", "⠙ Working | BossProjects", "Done")
            for (next in updates) {
                rule.runOnIdle { title.value = next }
                rule.runOnIdle {
                    assertEquals(next, shown.last())
                    assertEquals(0, hidden, "Changing title must not hide a hovered tooltip")
                }
            }
            if (removeTab) {
                rule.runOnIdle { present.value = false }
            } else {
                rule.onNodeWithTag("tab").performMouseInput { exit() }
            }
            rule.runOnIdle { assertEquals(1, hidden) }
            if (!removeTab) {
                reenterWithUpdatedTitle(title, shown)
                rule.onNodeWithTag("tab").performMouseInput { exit() }
                rule.runOnIdle { assertEquals(2, hidden) }
            }
            rule.runOnIdle { present.value = false }
        } finally {
            resetOverlayFieldForTest("useHeavyweightOverlays")
            OverlayConfig.useHeavyweightPopups = previousMode
            OverlayConfig.heavyweightTooltip = previousShow
            OverlayConfig.hideHeavyweightTooltip = previousHide
        }
    }

    private fun reenterWithUpdatedTitle(
        title: MutableState<String>,
        shown: List<String>,
    ) {
        val showsBefore = shown.size
        rule.runOnIdle { title.value = "Updated while not hovered" }
        rule.runOnIdle { assertEquals(showsBefore, shown.size) }
        rule.onNodeWithTag("tab").performMouseInput { enter(center) }
        rule.mainClock.advanceTimeBy(600)
        rule.runOnIdle {
            assertEquals(title.value, shown.last())
            assertEquals(showsBefore + 1, shown.size)
        }
    }
}
