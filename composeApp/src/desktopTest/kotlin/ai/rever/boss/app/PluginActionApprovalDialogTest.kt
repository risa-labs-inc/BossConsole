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

class PluginActionApprovalDialogTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `next identical request cannot be approved by an immediate second click`() {
        val queue = PluginActionApprovalQueue()
        queue.enqueue(PendingPluginAction("my.plugin", "sync", emptyMap()))
        queue.enqueue(PendingPluginAction("my.plugin", "sync", emptyMap()))
        var dispatches = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            queue.current?.let { pending ->
                PluginActionApprovalDialog(
                    request = pending,
                    pendingCount = queue.size,
                    onDismiss = { queue.consume(pending) },
                    onConfirm = { if (queue.consume(pending)) dispatches++ },
                )
            }
        }
        rule.onNodeWithText("Run action").assertIsNotEnabled()
        rule.mainClock.advanceTimeBy(600)
        rule.onNodeWithText("Run action").assertIsEnabled().performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("Run action").assertIsNotEnabled().performClick()
        rule.runOnIdle { assertEquals(1, dispatches) }
        rule.mainClock.advanceTimeBy(200)
        rule.onNodeWithText("Run action").assertIsNotEnabled()
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithText("Run action").assertIsEnabled().performClick()
        rule.runOnIdle {
            assertEquals(2, dispatches)
            assertNull(queue.current)
        }
    }
}
