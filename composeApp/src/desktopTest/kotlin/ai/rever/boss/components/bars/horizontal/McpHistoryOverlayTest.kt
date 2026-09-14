package ai.rever.boss.components.bars.horizontal

import ai.rever.boss.mcp.McpApprovalDisposition
import ai.rever.boss.mcp.McpOperationRecord
import ai.rever.boss.mcp.McpPolicyAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class McpHistoryOverlayTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun fakeRecord(
        toolName: String,
        durationMs: Long,
        isError: Boolean = false,
    ) = McpOperationRecord(
        id = "id-$toolName-${System.nanoTime()}",
        timestamp = 0L,
        toolName = toolName,
        providerId = "test-provider",
        policyApplied = McpPolicyAction.ALLOW,
        approvalDisposition = McpApprovalDisposition.AUTO_ALLOWED,
        durationMs = durationMs,
        isError = isError,
        sanitizedArgs = emptyMap(),
    )

    @Test
    fun `shows empty state when no records`() {
        composeRule.setContent {
            McpHistoryOverlay(records = emptyList(), onDismiss = {})
        }

        composeRule.onNodeWithText("No MCP tool calls recorded yet this session.").assertExists()
    }

    @Test
    fun `shows tool name and stats header when records exist`() {
        composeRule.setContent {
            McpHistoryOverlay(
                records =
                    listOf(
                        fakeRecord("git_status", durationMs = 42L),
                        fakeRecord("read_file", durationMs = 10L, isError = true),
                    ),
                onDismiss = {},
            )
        }

        composeRule.onNodeWithText("MCP tool call history").assertExists()
        // Each tool name renders twice by design: once in the per-tool stats row,
        // once in its "Recent calls" list row.
        composeRule.onAllNodesWithText("git_status").assertCountEquals(2)
        composeRule.onAllNodesWithText("read_file").assertCountEquals(2)
        composeRule.onNodeWithText("Recent calls (2)").assertExists()
    }
}
