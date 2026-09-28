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

/**
 * Pins #804: mutating-vs-read classification is fail-closed over BOTH signals the host holds -
 * the name catalog and the provider's own `McpToolDefinition.readOnly` declaration - at the
 * catalog, the policy engine's risk default, and the end-to-end approval flow.
 *
 * The name signals stay final (a mutating name cannot be upgraded by a read claim), and the
 * declaration closes the gap the issue filed: a third-party tool named to dodge the suffix list
 * used to land in the lenient read class purely on its name.
 */
class McpDeclaredMutatingToolTest {
    private fun provider(
        id: String,
        vararg defs: McpToolDefinition,
    ) = object : McpToolProvider {
        override val providerId = id

        override fun tools() = defs.toList()
    }

    @Test
    fun `the catalog combines the name signals with the provider declaration fail-closed`() {
        // Innocent name, no declaration in hand: the pre-#804 name-only answer.
        assertFalse(McpMutatingToolCatalog.isMutating("data_fetch"))
        assertFalse(McpMutatingToolCatalog.isMutating("data_fetch", declaredReadOnly = true))
        // The provider declaring side effects is a mutating signal, name or no name.
        assertTrue(McpMutatingToolCatalog.isMutating("data_fetch", declaredReadOnly = false))
        assertTrue(McpMutatingToolCatalog.isMutating("env_sync", declaredReadOnly = false))
        // A mutating NAME stays mutating whatever the provider claims: the name
        // signals are final, so a dishonest readOnly=true cannot upgrade the class.
        assertTrue(McpMutatingToolCatalog.isMutating("k8s_delete", declaredReadOnly = true))
        assertTrue(McpMutatingToolCatalog.isMutating("env_sync_delete", declaredReadOnly = true))
    }

    @Test
    fun `resolveAction routes a declared-mutating tool to the mutating default`() {
        val config = McpToolPolicyConfig()
        assertEquals(
            config.defaultReadOnlyAction,
            McpMutatingToolCatalog.resolveAction("data_fetch", null, config, declaredReadOnly = true),
        )
        assertEquals(
            config.defaultMutatingAction,
            McpMutatingToolCatalog.resolveAction("data_fetch", null, config, declaredReadOnly = false),
        )
        // An explicit rule still wins over both signals.
        val ruled = config.copy(rules = mapOf("data_fetch" to McpPolicyAction.DENY))
        assertEquals(
            McpPolicyAction.DENY,
            McpMutatingToolCatalog.resolveAction("data_fetch", null, ruled, declaredReadOnly = false),
        )
    }

    @Test
    fun `the engine risk default asks for a declared-mutating tool with an innocent name`() {
        val engine = McpPolicyEngine(policyFile = null)
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("env_sync"))
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("env_sync", declaredReadOnly = true))
        assertEquals(McpPolicyAction.ASK, engine.policyFor("env_sync", declaredReadOnly = false))
    }

    @Test
    fun `a tool that declares itself mutating asks instead of auto-running`() =
        runBlocking {
            var handlerCalled = false
            val approvalBus = McpApprovalBus(defaultTimeoutMs = 5000L)
            val ledger = McpOperationLedger(ledgerFile = null)
            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    approvalBus = approvalBus,
                    ledger = ledger,
                )
            core.registerProvider(
                provider(
                    "p1",
                    McpToolDefinition(
                        name = "env_sync",
                        description = "sync environment variables",
                        readOnly = false,
                        handler =
                            McpToolHandler {
                                handlerCalled = true
                                McpToolResult("synced")
                            },
                    ),
                ),
            )

            val deferred = async { core.invoke("env_sync", "{}") }
            val request = withTimeout(5_000) { approvalBus.pendingList.first { it.isNotEmpty() } }.first()
            assertEquals("env_sync", request.toolName)
            // The captured declaration is what the approval dialog labels the request with.
            assertEquals(false, request.declaredReadOnly)
            approvalBus.approve(request.id)

            assertFalse(deferred.await().isError)
            assertTrue(handlerCalled)
            assertEquals(
                McpApprovalDisposition.APPROVED_ONCE,
                ledger.recentOperations.value
                    .first()
                    .approvalDisposition,
            )
        }

    @Test
    fun `a declared-read tool with an innocent name still runs without a prompt`() =
        runBlocking {
            var handlerCalled = false
            val approvalBus = McpApprovalBus(defaultTimeoutMs = 5000L)
            val ledger = McpOperationLedger(ledgerFile = null)
            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    approvalBus = approvalBus,
                    ledger = ledger,
                )
            core.registerProvider(
                provider(
                    "p1",
                    McpToolDefinition(
                        name = "env_sync",
                        description = "read environment variables",
                        readOnly = true,
                        handler =
                            McpToolHandler {
                                handlerCalled = true
                                McpToolResult("read")
                            },
                    ),
                ),
            )

            val res = core.invoke("env_sync", "{}")
            assertFalse(res.isError)
            assertTrue(handlerCalled)
            assertTrue(
                approvalBus.pendingList.value.isEmpty(),
                "a read-declared innocent name must never queue a prompt",
            )
        }

    @Test
    fun `a mutating name still asks even when the provider claims read-only`() =
        runBlocking {
            var handlerCalled = false
            val approvalBus = McpApprovalBus(defaultTimeoutMs = 5000L)
            val ledger = McpOperationLedger(ledgerFile = null)
            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    approvalBus = approvalBus,
                    ledger = ledger,
                )
            core.registerProvider(
                provider(
                    "p1",
                    McpToolDefinition(
                        name = "k8s_delete",
                        description = "delete a workload",
                        readOnly = true,
                        handler =
                            McpToolHandler {
                                handlerCalled = true
                                McpToolResult("deleted")
                            },
                    ),
                ),
            )

            val deferred = async { core.invoke("k8s_delete", "{}") }
            val request = withTimeout(5_000) { approvalBus.pendingList.first { it.isNotEmpty() } }.first()
            approvalBus.deny(request.id, "name wins over the claim")

            val res = deferred.await()
            assertTrue(res.isError)
            assertFalse(handlerCalled)
        }
}
