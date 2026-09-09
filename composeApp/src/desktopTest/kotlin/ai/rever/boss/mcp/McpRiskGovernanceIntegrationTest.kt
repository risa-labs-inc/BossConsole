package ai.rever.boss.mcp

import ai.rever.boss.mcp.sandbox.McpRiskLevel
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpRiskGovernanceIntegrationTest {
    private fun provider(
        name: String,
        handler: McpToolHandler,
    ) = object : McpToolProvider {
        override val providerId = "risk-test"

        override fun tools() = listOf(McpToolDefinition(name = name, description = "test", handler = handler))
    }

    @Test
    fun `risk only tool uses one governed approval and one audit entry`() =
        runBlocking {
            val core = McpToolRegistryCore(disabledFile = null)
            var executed = 0
            core.registerProvider(
                provider("secret_create") {
                    executed++
                    McpToolResult("ok")
                },
            )
            val call = async { core.invoke("secret_create", "{}") }
            val request =
                core.approvalBus.pendingList
                    .first { it.isNotEmpty() }
                    .single()
            assertEquals(McpRiskLevel.HIGH, request.riskAssessment?.level)
            assertEquals(0, executed)
            core.approvalBus.approve(request.id)
            assertFalse(call.await().isError)
            assertEquals(1, executed)
            assertTrue(
                core.approvalBus.pendingList.value
                    .isEmpty(),
            )
            assertEquals(
                McpApprovalDisposition.APPROVED_ONCE,
                core.ledger.recentOperations.value
                    .single()
                    .approvalDisposition,
            )
        }

    @Test
    fun `explicit policy session trust and restrictive defaults retain precedence`() {
        val policy = McpPolicyEngine()
        assertEquals(McpPolicyAction.ASK, policy.policyFor("secret_create"))
        policy.trustForSession("secret_create")
        assertEquals(McpPolicyAction.ALLOW, policy.policyFor("secret_create"))
        policy.setToolPolicy("secret_create", McpPolicyAction.DENY)
        assertEquals(McpPolicyAction.DENY, policy.policyFor("secret_create"))
        policy.clearSessionTrusts()
        policy.setToolPolicy("secret_create", McpPolicyAction.ALLOW)
        assertEquals(McpPolicyAction.ALLOW, policy.policyFor("secret_create"))
        assertEquals(McpPolicyAction.ALLOW, policy.policyFor("unknown_tool"))
    }

    @Test
    fun `new definition cannot inherit pending risk approval`() =
        runBlocking {
            val core = McpToolRegistryCore(disabledFile = null)
            var executed = false
            core.registerProvider(
                provider("secret_create") {
                    executed = true
                    McpToolResult("original")
                },
            )
            val call = async { core.invoke("secret_create", "{}") }
            val request =
                core.approvalBus.pendingList
                    .first { it.isNotEmpty() }
                    .single()
            core.registerProvider(
                provider("secret_create") {
                    executed = true
                    McpToolResult("replacement")
                },
            )
            core.approvalBus.approve(request.id)
            assertTrue(call.await().isError)
            assertFalse(executed)
            assertEquals(
                McpApprovalDisposition.POLICY_DENIED,
                core.ledger.recentOperations.value
                    .single()
                    .approvalDisposition,
            )
        }
}
