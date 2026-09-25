package ai.rever.boss.mcp

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class McpApprovalGateTest {
    @Test
    fun `requestApproval suspends and resumes when approved by operator`() =
        runBlocking {
            val bus = McpApprovalBus(defaultTimeoutMs = 5000L)

            val deferredDecision =
                async {
                    bus.requestApproval(
                        toolName = "k8s_delete",
                        providerId = "kubernetes",
                        arguments = mapOf("pod" to "web-api"),
                    )
                }

            // Wait for request to appear on channel
            val request = bus.pendingList.first { it.isNotEmpty() }.first()
            assertEquals("k8s_delete", request.toolName)
            assertEquals("kubernetes", request.providerId)
            assertEquals("web-api", request.arguments["pod"])

            // Operator approves once
            val approved = bus.approve(request.id, trustForSession = false)
            assertTrue(approved)

            val decision = deferredDecision.await()
            assertIs<McpApprovalDecision.Approved>(decision)
            assertEquals(false, decision.trustForSession)
        }

    @Test
    fun `requestApproval returns Denied when operator rejects`() =
        runBlocking {
            val bus = McpApprovalBus(defaultTimeoutMs = 5000L)

            val deferredDecision =
                async {
                    bus.requestApproval(
                        toolName = "docker_rm",
                        providerId = "docker",
                        arguments = mapOf("container" to "db"),
                    )
                }

            val request = bus.pendingList.first { it.isNotEmpty() }.first()
            val denied = bus.deny(request.id, "Cannot delete DB container in production")
            assertTrue(denied)

            val decision = deferredDecision.await()
            assertIs<McpApprovalDecision.Denied>(decision)
            assertEquals("Cannot delete DB container in production", decision.reason)
        }

    @Test
    fun `an approval request carries the tool description, policy, and remaining timeout to the dialog`(): Unit =
        runBlocking {
            val bus = McpApprovalBus(defaultTimeoutMs = 5_000L)

            val deferredDecision =
                async {
                    bus.requestApproval(
                        toolName = "k8s_delete",
                        providerId = "kubernetes",
                        arguments = emptyMap(),
                        toolDescription = "Delete a Kubernetes pod by name",
                        policy = McpPolicyAction.ASK,
                    )
                }

            val request = bus.pendingList.first { it.isNotEmpty() }.first()
            assertEquals("Delete a Kubernetes pod by name", request.toolDescription)
            assertEquals(McpPolicyAction.ASK, request.policy)
            assertTrue(
                request.remainingTimeoutMs() in 1..5_000L,
                "remaining timeout must reflect elapsed time, got ${request.remainingTimeoutMs()}",
            )

            bus.approve(request.id)
            deferredDecision.await()
        }

    @Test
    fun `requestApproval times out and fails closed if operator does not respond`(): Unit =
        runBlocking {
            // Fast timeout of 50ms for testing
            val bus = McpApprovalBus(defaultTimeoutMs = 50L)

            val decision =
                bus.requestApproval(
                    toolName = "secret_get",
                    providerId = "secret-manager",
                    arguments = mapOf("id" to "api_token"),
                    timeoutMs = 50L,
                )

            assertIs<McpApprovalDecision.Timeout>(decision)
        }

    @Test
    fun `approve after timeout returns false and does not reopen execution`() =
        runBlocking {
            val bus = McpApprovalBus(defaultTimeoutMs = 50L)

            val deferredDecision =
                async {
                    bus.requestApproval(
                        toolName = "secret_get",
                        providerId = "secret-manager",
                        arguments = mapOf("id" to "api_token"),
                        timeoutMs = 50L,
                    )
                }

            val request = bus.pendingList.first { it.isNotEmpty() }.first()
            val decision = deferredDecision.await()
            assertIs<McpApprovalDecision.Timeout>(decision)

            // Attempting to approve after timeout has elapsed returns false
            val lateApprove = bus.approve(request.id, trustForSession = false)
            assertFalse(lateApprove)
            assertTrue(bus.pendingList.value.isEmpty())
        }

    @Test
    fun `exceeding pending buffer capacity immediately returns Denied buffer full`(): Unit =
        runBlocking {
            val bus = McpApprovalBus(defaultTimeoutMs = 10_000L, maxPendingRequests = 2)

            val d1 = async { bus.requestApproval("tool_1", "p1", emptyMap()) }
            val d2 = async { bus.requestApproval("tool_2", "p1", emptyMap()) }

            // Wait for both to be pending
            delay(50)
            assertEquals(2, bus.pendingList.value.size)

            // Third request exceeds capacity (2)
            val overflowDecision = bus.requestApproval("tool_3", "p1", emptyMap())
            assertEquals(McpApprovalDecision.QueueFull, overflowDecision)

            // Clean up by approving d1 and d2
            val list = bus.pendingList.value
            bus.approve(list[0].id)
            bus.approve(list[1].id)
            d1.await()
            d2.await()
        }

    @Test
    fun `deny all rejects exactly the visible snapshot without persisting policy`(): Unit =
        runBlocking {
            val bus = McpApprovalBus(defaultTimeoutMs = 10_000L, maxPendingRequests = 4)
            val first = async { bus.requestApproval("tool_1", "provider-a", emptyMap()) }
            val second = async { bus.requestApproval("tool_2", "provider-b", emptyMap()) }
            bus.pendingList.first { it.size == 2 }

            assertEquals(2, bus.denyAllPending())
            assertEquals(
                "Operator rejected all pending actions",
                assertIs<McpApprovalDecision.Denied>(first.await()).reason,
            )
            assertEquals(
                "Operator rejected all pending actions",
                assertIs<McpApprovalDecision.Denied>(second.await()).reason,
            )
            assertTrue(bus.pendingList.value.isEmpty())

            val later = async { bus.requestApproval("tool_3", "provider-c", emptyMap()) }
            val laterRequest = bus.pendingList.first { it.size == 1 }.single()
            assertEquals("tool_3", laterRequest.toolName)
            assertTrue(bus.approve(laterRequest.id))
            assertIs<McpApprovalDecision.Approved>(later.await())
        }
}
