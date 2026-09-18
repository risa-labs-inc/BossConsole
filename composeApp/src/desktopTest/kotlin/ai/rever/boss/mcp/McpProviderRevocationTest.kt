package ai.rever.boss.mcp

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpProviderRevocationTest {
    @Test
    fun `provider reset invalidates captured calls and both persistent grant scopes`() {
        val engine = McpPolicyEngine(policyFile = null)
        engine.setProviderPolicy("p", McpPolicyAction.ALLOW)
        val captured = engine.revocationVersion("run_command", "p")
        val other = engine.revocationVersion("k8s_delete", "other")
        assertTrue(engine.revokeProviderPolicy("p"))

        assertFalse(engine.confirmInvocation("run_command", captured, true, providerId = "p"))
        assertFalse(
            engine.setToolPolicy(
                "run_command",
                McpPolicyAction.ALLOW,
                expectedRevocation = captured,
                providerId = "p",
            ),
        )
        assertFalse(
            engine.setProviderPolicy(
                "p",
                McpPolicyAction.ALLOW,
                expectedRevocation = captured,
                toolName = "run_command",
            ),
        )
        // No trust was granted on this path; nothing may leak in from the refused writes above.
        assertTrue(engine.sessionTrustedTools.value.isEmpty())
        assertFalse("p" in engine.config.value.providerRules)
        assertTrue(engine.confirmInvocation("k8s_delete", other, false, providerId = "other"))
        assertTrue(
            engine.confirmInvocation(
                "run_command",
                engine.revocationVersion("run_command", "p"),
                false,
                providerId = "p",
            ),
        )
    }

    @Test
    fun `queued provider approval cannot restore trust after provider reset`() =
        runBlocking {
            val engine = McpPolicyEngine(policyFile = null)
            engine.setProviderPolicy("p", McpPolicyAction.ALLOW)
            engine.setToolPolicy("run_command", McpPolicyAction.ASK)
            val bus = McpApprovalBus()
            val ledger = McpOperationLedger(ledgerFile = null)
            var ran = false
            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    policyEngine = engine,
                    approvalBus = bus,
                    ledger = ledger,
                )
            core.registerProvider(
                object : McpToolProvider {
                    override val providerId = "p"

                    override fun tools() =
                        listOf(
                            McpToolDefinition(
                                name = "run_command",
                                description = "test",
                                handler =
                                    McpToolHandler {
                                        ran = true
                                        McpToolResult("ok")
                                    },
                            ),
                        )
                },
            )
            val invocation = async { core.invoke("run_command", "{}") }
            val request = withTimeout(5000L) { bus.pendingList.first { it.isNotEmpty() }.first() }
            assertTrue(engine.revokeProviderPolicy("p"))
            bus.approve(request.id, trustProvider = true)
            assertTrue(invocation.await().isError)
            assertFalse(ran)
            assertFalse("p" in engine.config.value.providerRules)
            assertEquals(
                McpApprovalDisposition.POLICY_DENIED,
                ledger.recentOperations.value
                    .first()
                    .approvalDisposition,
            )
        }

    @Test
    fun `provider guards cannot silently ignore missing tool context`() {
        val engine = McpPolicyEngine(policyFile = null)
        engine.setProviderPolicy("p", McpPolicyAction.DENY)
        assertFalse(engine.setProviderPolicy("p", McpPolicyAction.ALLOW, preserveDeny = true))
        assertFalse(engine.setProviderPolicy("other", McpPolicyAction.ALLOW, expectedRevocation = 0L))
        assertEquals(McpPolicyAction.DENY, engine.policyFor("run_command", "p"))
    }
}
