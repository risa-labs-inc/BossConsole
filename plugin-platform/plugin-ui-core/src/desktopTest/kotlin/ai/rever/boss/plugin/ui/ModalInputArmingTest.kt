package ai.rever.boss.plugin.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * A freshly-opened heavyweight modal must not act on the click that opened it.
 *
 * The bug this pins came from a real gesture: cmd-clicking a link in a terminal opens the link
 * dialog, and because a heavyweight modal is a NEW WINDOW placed under the cursor, the release of
 * that same click landed on whichever option happened to sit beneath the pointer and chose it. The
 * user selected an option they never aimed at, and which one depended purely on where the link
 * happened to be on screen.
 *
 * Tested through [ScrimmedModalContent] directly, which is why it is `internal` rather than private:
 * the guard lives on the heavyweight path, and that path is a real OS window a Compose test scene
 * cannot host. Composing the scrim content on its own exercises the same modifier chain.
 *
 * `mainClock.autoAdvance = false` is load-bearing. The arming timer is a `LaunchedEffect` + `delay`,
 * so with the clock advancing automatically it would fire before the test could click and the first
 * assertion would pass vacuously.
 */
class ModalInputArmingTest {
    @get:Rule
    val rule = createComposeRule()

    private fun option(onClick: () -> Unit): @Composable () -> Unit =
        {
            Box(
                Modifier
                    .size(120.dp)
                    .clickable(onClick = onClick),
            ) { Text(OPTION) }
        }

    @Test
    fun `a click landing before the arming delay is swallowed`() {
        var chosen = 0
        var dismissed = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ScrimmedModalContent(
                dismissOnClickOutside = true,
                onDismissRequest = { dismissed++ },
                content = option { chosen++ },
            )
        }
        // One frame, so the tree exists, but nowhere near INPUT_ARM_DELAY_MS.
        rule.mainClock.advanceTimeByFrame()

        rule.onNodeWithText(OPTION).performClick()

        assertEquals(0, chosen, "the option fired from the click that opened the dialog")
        assertEquals(0, dismissed, "the scrim dismissed from the click that opened the dialog")
    }

    @Test
    fun `a click after the arming delay works normally`() {
        var chosen = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ScrimmedModalContent(
                dismissOnClickOutside = true,
                onDismissRequest = {},
                content = option { chosen++ },
            )
        }
        // Past the timer, which is the other half of the contract: the guard must open up on its own
        // for a dialog reached from the keyboard or a menu, where no pointer event ever arrives to arm
        // it and a permanently deaf dialog would be far worse than the bug it fixes.
        rule.mainClock.advanceTimeBy(INPUT_ARM_DELAY_MS + FRAME_SLACK_MS)

        rule.onNodeWithText(OPTION).performClick()

        assertEquals(1, chosen)
    }

    @Test
    fun `dismiss-on-click-outside is disabled independently of arming`() {
        // Guards the other branch of the same modifier chain: with dismissOnClickOutside off, no
        // amount of waiting should make the scrim dismissible.
        var dismissed = 0
        var chosen = 0
        rule.setContent {
            ScrimmedModalContent(
                dismissOnClickOutside = false,
                onDismissRequest = { dismissed++ },
                content = option { chosen++ },
            )
        }
        rule.mainClock.advanceTimeBy(INPUT_ARM_DELAY_MS + FRAME_SLACK_MS)

        rule.onNodeWithText(OPTION).performClick()

        assertEquals(1, chosen, "the card stopped receiving clicks")
        assertEquals(0, dismissed, "dismissOnClickOutside = false still dismissed")
    }

    private companion object {
        const val OPTION = "Existing Split"

        /** A couple of frames past the timer, so the arming recomposition has actually landed. */
        const val FRAME_SLACK_MS = 64L
    }
}
