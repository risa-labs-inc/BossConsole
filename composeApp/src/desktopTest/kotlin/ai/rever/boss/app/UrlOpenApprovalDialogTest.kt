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

class UrlOpenApprovalDialogTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `approval displays bidi and invisible characters as escapes`() {
        assertEquals(
            "https://example.test/path\\u202e\\u200b\\u2066\\u000a\\u2028\\u2029\\u00ad\\ud804\\udcbdend",
            visibleUrlForApproval("https://example.test/path\u202e\u200b\u2066\n\u2028\u2029\u00ad\uD804\uDCBDend"),
        )
    }

    @Test
    fun `next identical request cannot be approved by an immediate second click`() {
        val queue = UrlOpenApprovalQueue()
        queue.enqueue(PendingUrlOpen("https://identical.example", "identical.example"))
        queue.enqueue(PendingUrlOpen("https://identical.example", "identical.example"))
        var opens = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            queue.current?.let { pending ->
                UrlOpenApprovalDialog(
                    request = pending,
                    pendingCount = queue.size,
                    onDismiss = { queue.consume(pending) },
                    onConfirm = { if (queue.consume(pending)) opens++ },
                )
            }
        }
        rule.onNodeWithText("Open link").assertIsNotEnabled()
        rule.mainClock.advanceTimeBy(600)
        rule.onNodeWithText("Open link").assertIsEnabled().performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText("Open link").assertIsNotEnabled().performClick()
        rule.runOnIdle { assertEquals(1, opens) }
        rule.mainClock.advanceTimeBy(200)
        rule.onNodeWithText("Open link").assertIsNotEnabled()
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithText("Open link").assertIsEnabled().performClick()
        rule.runOnIdle {
            assertEquals(2, opens)
            assertNull(queue.current)
        }
    }
}
