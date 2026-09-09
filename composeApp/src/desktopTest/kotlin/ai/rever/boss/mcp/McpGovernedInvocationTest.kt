package ai.rever.boss.mcp

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises policy precedence, real approval suspension and audit cancellation at the registry boundary. */
class McpGovernedInvocationTest {
    private fun provider(
        id: String,
        vararg defs: McpToolDefinition,
    ) = object : McpToolProvider {
        override val providerId = id

        override fun tools() = defs.toList()
    }

    private fun echoTool(
        name: String,
        requiredPermissions: List<String> = emptyList(),
        requiresAdmin: Boolean = false,
        handler: McpToolHandler = McpToolHandler { McpToolResult("ok:$name") },
    ) = McpToolDefinition(name = name, description = "test tool $name", handler = handler)
        .apply {
            this.requiredPermissions = requiredPermissions
            this.requiresAdmin = requiresAdmin
        }

    @Test
    fun `invoke respects DENY policy and records rejection in ledger`() =
        runBlocking {
            var handlerCalled = false
            val tool =
                echoTool(
                    name = "blocked_tool",
                    handler =
                        McpToolHandler {
                            handlerCalled = true
                            McpToolResult("ok")
                        },
                )
            val policyEngine = McpPolicyEngine(policyFile = null)
            policyEngine.setToolPolicy("blocked_tool", McpPolicyAction.DENY)
            val ledger = McpOperationLedger(ledgerFile = null)

            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    policyEngine = policyEngine,
                    ledger = ledger,
                )
            core.registerProvider(provider("p1", tool))

            val res = core.invoke("blocked_tool", "{}")
            assertTrue(res.isError)
            assertTrue(res.text.contains("rejected by policy"))
            assertFalse(handlerCalled, "Handler must never be called when policy is DENY")

            // Verified in ledger
            assertEquals(1L, ledger.totalCalls.value)
            assertEquals(1L, ledger.totalErrors.value)
            assertEquals(
                McpApprovalDisposition.POLICY_DENIED,
                ledger.recentOperations.value
                    .first()
                    .approvalDisposition,
            )
        }

    @Test
    fun `invoke with ASK policy suspends and succeeds when approved by operator`() =
        runBlocking {
            var handlerCalled = false
            val tool =
                echoTool(
                    name = "k8s_delete",
                    handler =
                        McpToolHandler {
                            handlerCalled = true
                            McpToolResult("pod deleted")
                        },
                )
            val approvalBus = McpApprovalBus(defaultTimeoutMs = 5000L)
            val ledger = McpOperationLedger(ledgerFile = null)

            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    approvalBus = approvalBus,
                    ledger = ledger,
                )
            core.registerProvider(provider("p1", tool))

            // Invoke in background coroutine (it will suspend waiting for approval)
            val deferredResult = async { core.invoke("k8s_delete", "{\"pod\":\"test-pod\"}") }

            // Receive request and approve
            val req = approvalBus.pendingList.first { it.isNotEmpty() }.first()
            assertEquals("k8s_delete", req.toolName)
            approvalBus.approve(req.id, trustForSession = false)

            val res = deferredResult.await()
            assertFalse(res.isError)
            assertEquals("pod deleted", res.text)
            assertTrue(handlerCalled)

            // Ledger record
            assertEquals(1L, ledger.totalCalls.value)
            assertEquals(
                McpApprovalDisposition.APPROVED_ONCE,
                ledger.recentOperations.value
                    .first()
                    .approvalDisposition,
            )
        }

    @Test
    fun `invoke with ASK policy returns error when operator denies`() =
        runBlocking {
            var handlerCalled = false
            val tool =
                echoTool(
                    name = "docker_rm",
                    handler =
                        McpToolHandler {
                            handlerCalled = true
                            McpToolResult("removed")
                        },
                )
            val approvalBus = McpApprovalBus(defaultTimeoutMs = 5000L)
            val ledger = McpOperationLedger(ledgerFile = null)

            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    approvalBus = approvalBus,
                    ledger = ledger,
                )
            core.registerProvider(provider("p1", tool))

            val deferredResult = async { core.invoke("docker_rm", "{}") }

            val req = approvalBus.pendingList.first { it.isNotEmpty() }.first()
            approvalBus.deny(req.id, "Container in use")

            val res = deferredResult.await()
            assertTrue(res.isError)
            assertTrue(res.text.contains("Container in use"))
            assertFalse(handlerCalled)

            assertEquals(1L, ledger.totalErrors.value)
            assertEquals(
                McpApprovalDisposition.DENIED_BY_OPERATOR,
                ledger.recentOperations.value
                    .first()
                    .approvalDisposition,
            )
        }

    @Test
    fun `invoke records cancellation in ledger and rethrows CancellationException`() =
        runBlocking {
            val tool =
                echoTool(
                    name = "slow_tool",
                    handler =
                        McpToolHandler {
                            throw CancellationException("Caller cancelled job")
                        },
                )
            val ledger = McpOperationLedger(ledgerFile = null)
            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    ledger = ledger,
                )
            core.registerProvider(provider("p1", tool))

            var thrown: CancellationException? = null
            try {
                core.invoke("slow_tool", "{}")
            } catch (e: CancellationException) {
                thrown = e
            }

            assertTrue(thrown != null, "CancellationException must be rethrown")
            assertEquals(1L, ledger.totalCalls.value)
            assertEquals(1L, ledger.totalErrors.value)
            val record = ledger.recentOperations.value.first()
            assertTrue(record.isError)
            assertEquals(McpApprovalDisposition.CANCELLED_IN_FLIGHT, record.approvalDisposition)
            assertEquals("Execution cancelled by caller", record.errorSnippet)
        }

    @Test
    fun `real job cancellation during approval records audit and removes request`() =
        runBlocking {
            val bus = McpApprovalBus()
            val ledger = McpOperationLedger()
            val core = McpToolRegistryCore(disabledFile = null, approvalBus = bus, ledger = ledger)
            core.registerProvider(provider("p", echoTool("run_command")))
            val job = async { core.invoke("run_command", "{}") }
            val request = bus.pendingList.first { it.isNotEmpty() }.first()
            job.cancel()
            job.join()
            assertTrue(bus.pendingList.value.isEmpty())
            assertFalse(bus.approve(request.id))
            assertEquals(
                McpApprovalDisposition.CANCELLED_AWAITING_APPROVAL,
                ledger.recentOperations.value
                    .single()
                    .approvalDisposition,
            )
        }

    @Test
    fun `new deny or kill switch while awaiting approval prevents execution`() =
        runBlocking {
            for (change in listOf("deny", "disable", "unload")) {
                val bus = McpApprovalBus()
                val policy = McpPolicyEngine()
                var called = false
                val core = McpToolRegistryCore(disabledFile = null, approvalBus = bus, policyEngine = policy)
                core.registerProvider(
                    provider(
                        "p",
                        echoTool(
                            "run_command",
                            handler =
                                McpToolHandler {
                                    called = true
                                    McpToolResult("ok")
                                },
                        ),
                    ),
                )
                val job = async { core.invoke("run_command", "{}") }
                val request = bus.pendingList.first { it.isNotEmpty() }.first()
                when (change) {
                    "deny" -> policy.setToolPolicy("run_command", McpPolicyAction.DENY)
                    "disable" -> core.setToolEnabled("run_command", false)
                    "unload" -> core.unregisterProvider("p")
                }
                bus.approve(request.id)
                assertTrue(job.await().isError, change)
                assertFalse(called, change)
            }
        }
}
