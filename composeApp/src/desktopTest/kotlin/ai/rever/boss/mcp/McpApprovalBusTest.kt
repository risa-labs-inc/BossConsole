package ai.rever.boss.mcp

import ai.rever.boss.mcp.sandbox.DefaultMcpApprovalBus
import ai.rever.boss.mcp.sandbox.McpApprovalRequest
import ai.rever.boss.mcp.sandbox.McpRiskAssessment
import ai.rever.boss.mcp.sandbox.McpRiskLevel
import ai.rever.boss.plugin.api.McpToolArgs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Focused unit test suite for [DefaultMcpApprovalBus] and approval queue coordination.
 */
class McpApprovalBusTest {
    private val dummyArgs = McpToolArgs(emptyMap())
    private val highAssessment =
        McpRiskAssessment(
            level = McpRiskLevel.HIGH,
            reason = "Test high risk reason",
        )

    @Test
    fun `approval resolves true when user approves request`() =
        runBlocking {
            val bus = DefaultMcpApprovalBus(timeoutMs = 5000L)

            val deferredResult =
                async {
                    bus.requestApproval("test_tool", dummyArgs, highAssessment)
                }

            val request = bus.approvalRequests.first()
            assertEquals("test_tool", request.toolName)
            assertEquals(McpRiskLevel.HIGH, request.riskLevel)
            assertEquals("Test high risk reason", request.reason)

            // User approves
            assertTrue(request.answer.complete(true))

            val approved = deferredResult.await()
            assertTrue(approved, "requestApproval MUST return true when user approves")
        }

    @Test
    fun `denial resolves false when user denies request`() =
        runBlocking {
            val bus = DefaultMcpApprovalBus(timeoutMs = 5000L)

            val deferredResult =
                async {
                    bus.requestApproval("test_tool", dummyArgs, highAssessment)
                }

            val request = bus.approvalRequests.first()

            // User denies
            assertTrue(request.answer.complete(false))

            val approved = deferredResult.await()
            assertFalse(approved, "requestApproval MUST return false when user denies")
        }

    @Test
    fun `unanswered request times out and resolves to false`() =
        runBlocking {
            // Short timeout for test speed
            val bus = DefaultMcpApprovalBus(timeoutMs = 100L)

            val requestJob =
                async {
                    bus.requestApproval("test_tool", dummyArgs, highAssessment)
                }

            val request = bus.approvalRequests.first()

            // Do not complete answer - allow timeout to fire
            val approved = requestJob.await()

            assertFalse(approved, "Timed out approval request MUST resolve to false (fail-closed)")
            assertTrue(request.answer.isCompleted)
            assertFalse(request.answer.getCompleted(), "Timed out request answer MUST complete with false")
        }

    @Test
    fun `caller cancellation completes request answer with false to clean up UI`() =
        runBlocking {
            val bus = DefaultMcpApprovalBus(timeoutMs = 5000L)

            val callingJob =
                launch {
                    bus.requestApproval("test_tool", dummyArgs, highAssessment)
                }

            val request = bus.approvalRequests.first()

            // Cancel calling job while suspended
            callingJob.cancel()
            callingJob.join()

            assertTrue(request.answer.isCompleted, "Request answer MUST be completed upon cancellation")
            assertFalse(request.answer.getCompleted(), "Cancelled request answer MUST be false (fail closed)")
        }

    @Test
    fun `multiple concurrent requests are queued and processed sequentially`() =
        runBlocking {
            val bus = DefaultMcpApprovalBus(timeoutMs = 5000L)

            val req1Deferred = async { bus.requestApproval("tool_1", dummyArgs, highAssessment) }
            val req2Deferred = async { bus.requestApproval("tool_2", dummyArgs, highAssessment) }

            val collectedRequests = mutableListOf<McpApprovalRequest>()
            val collectorJob =
                launch {
                    bus.approvalRequests.collect { req ->
                        collectedRequests.add(req)
                        if (req.toolName == "tool_1") {
                            req.answer.complete(true)
                        } else if (req.toolName == "tool_2") {
                            req.answer.complete(false)
                        }
                    }
                }

            val res1 = req1Deferred.await()
            val res2 = req2Deferred.await()

            assertTrue(res1, "tool_1 should be approved")
            assertFalse(res2, "tool_2 should be denied")

            assertEquals(2, collectedRequests.size)
            assertEquals("tool_1", collectedRequests[0].toolName)
            assertEquals("tool_2", collectedRequests[1].toolName)

            collectorJob.cancel()
        }

    @Test
    fun `double completion cannot alter first decision`() =
        runBlocking {
            val request =
                McpApprovalRequest(
                    toolName = "test_tool",
                    riskLevel = McpRiskLevel.HIGH,
                    reason = "Testing double completion",
                )

            assertTrue(request.answer.complete(true), "First completion should succeed")
            assertFalse(request.answer.complete(false), "Second completion MUST be rejected")
            assertTrue(request.answer.getCompleted(), "Completed decision MUST remain true")
        }
}
