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

    /** Taller than the strip, so the list genuinely overflows and has somewhere to scroll. */
    private val tabCount = 30

    private fun strip(trailing: @Composable () -> Unit) {
        rule.setContent {
            Column(modifier = Modifier.size(200.dp, 300.dp).testTag("bar")) {
                BossVerticalTabStrip(
                    listState = rememberLazyListState(),
                    trailing = trailing,
                ) {
                    items((1..tabCount).toList()) { n ->
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
    fun `the new tab row sits below the scrolling list, not inside it`() {
        strip { NewTabRow(onClick = {}) }

        val row = rule.onNodeWithText("New Tab").getBoundsInRoot()
        val bar = rule.onNodeWithTag("bar").getBoundsInRoot()

        // Bottom-anchored: the row's lower edge is the bar's lower edge.
        assertEquals(bar.bottom.value, row.bottom.value, 0.5f)

        // And it is BELOW every tab the list is showing - which is the difference between a
        // trailing sibling and a last item, and the whole reason it cannot scroll away. With 30
        // tabs in a 300dp bar the list is overflowing, so a last-item placement would put this
        // off-screen entirely.
        val firstTab = rule.onNodeWithTag("tab-1").getBoundsInRoot()
        assertTrue(
            firstTab.bottom.value <= row.top.value,
            "New Tab row at ${row.top} overlaps the tab list ending at ${firstTab.bottom}",
        )
    }

    @Test
    fun `the new tab row survives scrolling the list`() {
        strip { NewTabRow(onClick = {}) }
        val before = rule.onNodeWithText("New Tab").getBoundsInRoot()

        rule.onNodeWithTag("tab-1").performMouseInput { moveTo(center) }
        rule.onNodeWithTag("bar").performMouseInput {
            // Far enough to move the list well past its first screenful.
            scroll(20f)
        }
        rule.waitForIdle()

        rule.onNodeWithText("New Tab").assertIsDisplayed()
        assertEquals(
            before.bottom.value,
            rule
                .onNodeWithText("New Tab")
                .getBoundsInRoot()
                .bottom.value,
            0.5f,
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
