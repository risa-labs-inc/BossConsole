package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpApprovalRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class McpApprovalDialogCountdownTest {
    @Test
    fun `countdown rounds up so it never announces expiry early`() {
        val request =
            McpApprovalRequest(
                toolName = "run_command",
                providerId = "terminal",
                arguments = emptyMap(),
                timeoutMs = 45_000L,
                requestedAt = 1_000L,
            )

        assertEquals(45_000L, approvalMillisRemaining(request, nowMs = 1_000L))
        assertEquals("Expires in 0:45", approvalExpiryLabel(44_001L))
        assertEquals("Expires in 0:01", approvalExpiryLabel(1L))
        assertEquals("Approval expired", approvalExpiryLabel(0L))
    }

    @Test
    fun `countdown tolerates a future request timestamp and overflowing deadline`() {
        val clockMovedBack =
            McpApprovalRequest(
                toolName = "run_command",
                providerId = "terminal",
                arguments = emptyMap(),
                timeoutMs = 5_000L,
                requestedAt = 10_000L,
            )
        val saturated = clockMovedBack.copy(requestedAt = Long.MAX_VALUE - 1L, timeoutMs = 5_000L)

        assertEquals(5_000L, approvalMillisRemaining(clockMovedBack, nowMs = 9_000L))
        assertEquals(1L, approvalMillisRemaining(saturated, nowMs = Long.MAX_VALUE - 1L))
    }
}
