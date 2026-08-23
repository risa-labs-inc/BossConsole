package ai.rever.boss.components.window_panel

import ai.rever.boss.components.window_panel.components.main_window_panels.BossVerticalTabStrip
import ai.rever.boss.components.window_panel.components.main_window_panels.NewTabRow
import ai.rever.boss.components.window_panel.components.main_window_panels.SectionBreak
import ai.rever.boss.components.window_panel.components.main_window_panels.SectionHeader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the Arc-shaped structure of the vertical tab bar against a real layout tree.
 *
 * Everything here is placement or hover behaviour, which is precisely what the pure tests in
 * `TabPinningTest` cannot see: the pinning arithmetic can be perfect while the New Tab row scrolls
 * away with the list or the section header's "+" never appears, and every other gate stays green.
 * So this measures a real composition rather than asserting on a formula.
 */
class VerticalTabBarLayoutTest {
    @get:Rule
    val rule = createComposeRule()

    /** Short enough that the whole list fits, so placement is about order, not scrolling. */
    private val tabCount = 3

    private fun strip(tabs: Int = tabCount) {
        rule.setContent {
            Column(modifier = Modifier.size(200.dp, 300.dp).testTag("bar")) {
                BossVerticalTabStrip(listState = rememberLazyListState()) {
                    // The order the real bar builds: New Tab first, then the tabs.
                    item { NewTabRow(onClick = {}) }
                    items((1..tabs).toList()) { n ->
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(32.dp)
                                    .background(Color.Gray)
                                    .testTag("tab-$n"),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `the new tab row sits above the tabs`() {
        strip()

        val row = rule.onNodeWithText("New Tab").getBoundsInRoot()
        val firstTab = rule.onNodeWithTag("tab-1").getBoundsInRoot()

        // Arc's placement: under the bar's header and its rule, BEFORE the first tab. Both
        // earlier attempts at this are what the assertion is really guarding - pinned to the
        // bar's floor, and tucked under the LAST tab.
        assertEquals(row.bottom.value, firstTab.top.value, 0.5f)
    }

    @Test
    fun `the new tab row does not move as tabs are added`() {
        strip(tabs = 1)
        val withOne =
            rule
                .onNodeWithText("New Tab")
                .getBoundsInRoot()
                .top.value

        strip(tabs = 3)
        val withThree =
            rule
                .onNodeWithText("New Tab")
                .getBoundsInRoot()
                .top.value

        // Its whole point at the top is that its position does not depend on how many tabs there
        // are. Below the last tab it moved with every open and close.
        assertEquals(withOne, withThree, 0.5f)
    }

    @Test
    fun `the new tab row is not anchored to the bottom of the bar`() {
        strip()

        val bar = rule.onNodeWithTag("bar").getBoundsInRoot()
        val row = rule.onNodeWithText("New Tab").getBoundsInRoot()

        assertTrue(
            row.bottom.value < bar.bottom.value - 100f,
            "New Tab row at ${row.bottom} is pinned to the bar floor at ${bar.bottom}",
        )
    }

    // --- section headers ---

    @Test
    fun `a section header shows its label and hides its plus until hovered`() {
        rule.setContent {
            Column(modifier = Modifier.size(200.dp, 100.dp)) {
                SectionHeader(label = "PINNED", onAdd = {}, addHint = "New pinned tab")
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("PINNED").assertIsDisplayed()
        // Resting state is quiet: the "+" is not in the tree at all until the pointer arrives.
        // assertDoesNotExist, not assertIsNotDisplayed - the difference is the point, since a
        // "+" that is composed but transparent would pass the weaker assertion.
        rule.onNodeWithContentDescription("New pinned tab").assertDoesNotExist()

        rule.onNodeWithText("PINNED").performMouseInput { moveTo(center) }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("New pinned tab").assertIsDisplayed()
    }

    @Test
    fun `clicking a section plus fires its own action`() {
        var added = 0
        rule.setContent {
            Column(modifier = Modifier.size(200.dp, 100.dp)) {
                SectionHeader(label = "PINNED", onAdd = { added++ }, addHint = "New pinned tab")
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("PINNED").performMouseInput { moveTo(center) }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("New pinned tab").performClick()
        rule.waitForIdle()

        assertEquals(1, added)
    }

    @Test
    fun `the section break carries the open header`() {
        rule.setContent {
            Column(modifier = Modifier.size(200.dp, 100.dp)) {
                SectionBreak(onAdd = {})
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("OPEN").assertIsDisplayed()
    }
}
