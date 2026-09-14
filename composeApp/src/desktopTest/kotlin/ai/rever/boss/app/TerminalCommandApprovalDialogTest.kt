package ai.rever.boss.app

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TerminalCommandApprovalDialogTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `next identical request cannot be approved by an immediate second click`() {
        val queue = TerminalCommandApprovalQueue()
        queue.enqueue(PendingTerminalCommand("echo identical", null))
        queue.enqueue(PendingTerminalCommand("echo identical", null))
        var executions = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            queue.current?.let { pending ->
                TerminalCommandApprovalDialog(
                    request = pending,
                    pendingCount = queue.size,
                    onDismiss = { queue.consume(pending) },
                    onConfirm = { if (queue.consume(pending)) executions++ },
                )
            }
        }
        rule.onNodeWithText("Run command").assertIsNotEnabled()
        rule.mainClock.advanceTimeBy(600)
        rule.onNodeWithText("Run command").assertIsEnabled().performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("Run command").assertIsNotEnabled().performClick()
        rule.runOnIdle { assertEquals(1, executions) }
        rule.mainClock.advanceTimeBy(200)
        rule.onNodeWithText("Run command").assertIsNotEnabled()
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithText("Run command").assertIsEnabled().performClick()
        rule.runOnIdle {
            assertEquals(2, executions)
            assertNull(queue.current)
        }
    }
}
