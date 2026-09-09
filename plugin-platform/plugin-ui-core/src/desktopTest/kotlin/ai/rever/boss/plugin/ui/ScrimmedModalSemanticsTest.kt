package ai.rever.boss.plugin.ui

import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Pins the accessibility fixes for the heavyweight modal card (issue #143): the card is announced as
 * a dialog, and its click-swallow no longer exposes an action on the whole card.
 *
 * Tested through [ScrimmedModalContent] directly for the same reason [ModalInputArmingTest] is - the
 * heavyweight path is a real OS window a test scene cannot host, but the card's modifier chain is the
 * same either way.
 */
class ScrimmedModalSemanticsTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `card taps are swallowed while child buttons still receive taps`() {
        var dismissed = 0
        var pressed = 0
        rule.setContent {
            ScrimmedModalContent(true, { dismissed++ }) {
                androidx.compose.foundation.layout.Column {
                    Text("Card background")
                    Button(onClick = { pressed++ }) { Text("Action") }
                }
            }
        }
        rule.mainClock.advanceTimeBy(INPUT_ARM_DELAY_MS + 64)
        rule.onNodeWithText("Card background").performTouchInput { click() }
        assertEquals(0, dismissed)
        rule.onNodeWithText("Action").performTouchInput { click() }
        assertEquals(1, pressed)
        assertEquals(0, dismissed)
    }

    @Test
    fun `a scrim tap uses the current dismiss callback after recomposition`() {
        val generation = mutableStateOf(0)
        val dismissed = mutableListOf<Int>()
        rule.setContent {
            val current = generation.value
            ScrimmedModalContent(true, { dismissed += current }) { Text("Dialog body") }
        }
        rule.mainClock.advanceTimeBy(INPUT_ARM_DELAY_MS + 64)
        rule.onRoot().performTouchInput { click(Offset(1f, 1f)) }
        rule.runOnIdle { generation.value = 1 }
        rule.waitForIdle()
        rule.onRoot().performTouchInput { click(Offset(1f, 1f)) }
        assertEquals(listOf(0, 1), dismissed)
    }

    @Test
    fun `the modal card carries dialog semantics and no click action`() {
        rule.setContent {
            ScrimmedModalContent(dismissOnClickOutside = true, onDismissRequest = {}) {
                Text("Dialog body")
            }
        }

        val dialogNode =
            rule.onNode(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog),
                useUnmergedTree = true,
            )
        // The heavyweight card declares dialog(): the lightweight Dialog path announces one and this
        // path did not.
        dialogNode.assertExists("the modal card should carry dialog() semantics")
        // The click-swallow used clickable(onClick = {}), which gave the card an OnClick action.
        // The bare detectTapGestures swallow adds no semantics.
        dialogNode.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }
}
