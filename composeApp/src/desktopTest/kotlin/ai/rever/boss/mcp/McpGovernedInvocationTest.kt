package ai.rever.boss.mcp

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    @Test
    fun `approving with persistPolicy writes an ALLOW rule that survives past this one call`() =
        runBlocking {
            val approvalBus = McpApprovalBus(defaultTimeoutMs = 5000L)
            val policyEngine = McpPolicyEngine(policyFile = null)
            val ledger = McpOperationLedger(ledgerFile = null)
            var callCount = 0
            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    policyEngine = policyEngine,
                    approvalBus = approvalBus,
                    ledger = ledger,
                )
            core.registerProvider(
                provider(
                    "p1",
                    echoTool(
                        "helm_uninstall",
                        handler =
                            McpToolHandler {
                                callCount++
                                McpToolResult("ok")
                            },
                    ),
                ),
            )

            // First call: the tool has no configured rule, so DefaultMcpRiskEvaluator's
            // mutating-tool default routes it to ASK.
            val first = async { core.invoke("helm_uninstall", "{}") }
            val req = approvalBus.pendingList.first { it.isNotEmpty() }.first()
            approvalBus.approve(req.id, trustForSession = false, persistPolicy = true)
            assertFalse(first.await().isError)
            assertEquals(1, callCount)
            assertEquals(McpPolicyAction.ALLOW, policyEngine.policyFor("helm_uninstall"))
            assertEquals(
                McpApprovalDisposition.PERSISTENTLY_ALLOWED,
                ledger.recentOperations.value
                    .first()
                    .approvalDisposition,
            )

            // Second call: the persisted rule means it never suspends for approval again.
            val second = core.invoke("helm_uninstall", "{}")
            assertFalse(second.isError)
            assertEquals(2, callCount)
            assertEquals(
                McpApprovalDisposition.AUTO_ALLOWED,
                ledger.recentOperations.value
                    .first()
                    .approvalDisposition,
            )
        }

    @Test
    fun `denying with persistPolicy writes a DENY rule that survives past this one call`() =
        runBlocking {
            val approvalBus = McpApprovalBus(defaultTimeoutMs = 5000L)
            val policyEngine = McpPolicyEngine(policyFile = null)
            val ledger = McpOperationLedger(ledgerFile = null)
            var callCount = 0
            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    policyEngine = policyEngine,
                    approvalBus = approvalBus,
                    ledger = ledger,
                )
            core.registerProvider(
                provider(
                    "p1",
                    echoTool(
                        "docker_rm",
                        handler =
                            McpToolHandler {
                                callCount++
                                McpToolResult("ok")
                            },
                    ),
                ),
            )

            val first = async { core.invoke("docker_rm", "{}") }
            val req = approvalBus.pendingList.first { it.isNotEmpty() }.first()
            approvalBus.deny(req.id, "not today", persistPolicy = true)
            assertTrue(first.await().isError)
            assertEquals(0, callCount)
            assertEquals(McpPolicyAction.DENY, policyEngine.policyFor("docker_rm"))
            assertEquals(
                McpApprovalDisposition.PERSISTENTLY_DENIED,
                ledger.recentOperations.value
                    .first()
                    .approvalDisposition,
            )

            // Second call: refused by policy before the handler is ever reached, no new prompt.
            val second = core.invoke("docker_rm", "{}")
            assertTrue(second.isError)
            assertEquals(0, callCount)
            assertEquals(
                McpApprovalDisposition.POLICY_DENIED,
                ledger.recentOperations.value
                    .first()
                    .approvalDisposition,
            )
        }

    @Test
    fun `approving one tool with trustProvider lets a sibling tool from the same provider through without asking`() =
        runBlocking {
            var firstCalled = false
            var secondCalled = false
            val policyEngine = McpPolicyEngine(policyFile = null)
            val approvalBus = McpApprovalBus(defaultTimeoutMs = 5000L)
            val ledger = McpOperationLedger(ledgerFile = null)
            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    policyEngine = policyEngine,
                    approvalBus = approvalBus,
                    ledger = ledger,
                )
            core.registerProvider(
                provider(
                    "terminal-tab",
                    echoTool(
                        "run_command",
                        handler =
                            McpToolHandler {
                                firstCalled = true
                                McpToolResult("ran")
                            },
                    ),
                    echoTool(
                        "k8s_delete",
                        handler =
                            McpToolHandler {
                                secondCalled = true
                                McpToolResult("deleted")
                            },
                    ),
                ),
            )

            // First call: ASK, approve with "Trust This Plugin".
            val firstResult = async { core.invoke("run_command", "{}") }
            val firstRequest = approvalBus.pendingList.first { it.isNotEmpty() }.first()
            assertEquals("terminal-tab", firstRequest.providerId)
            approvalBus.approve(firstRequest.id, trustForSession = false, trustProvider = true)
            assertFalse(firstResult.await().isError)
            assertTrue(firstCalled)
            assertEquals(
                McpApprovalDisposition.PROVIDER_TRUSTED,
                ledger.recentOperations.value
                    .first()
                    .approvalDisposition,
            )

            // Second call, a DIFFERENT tool from the SAME provider: no approval prompt at all -
            // the provider-wide rule the first call just persisted covers it.
            val secondResult = core.invoke("k8s_delete", "{}")
            assertFalse(secondResult.isError)
            assertTrue(secondCalled)
            assertTrue(approvalBus.pendingList.value.isEmpty(), "the second call must never have queued a prompt")

            // And it really did persist, not just live in this run's session trust: a fresh
            // engine reading the same policy would agree without ever calling trustForSession.
            assertEquals(McpPolicyAction.ALLOW, policyEngine.policyFor("k8s_delete", "terminal-tab"))
        }

    @Test
    fun `revoking provider trust makes its tools ask again`() =
        runBlocking {
            val policyEngine = McpPolicyEngine(policyFile = null)
            policyEngine.setProviderPolicy("terminal-tab", McpPolicyAction.ALLOW)
            val approvalBus = McpApprovalBus(defaultTimeoutMs = 5000L)
            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    policyEngine = policyEngine,
                    approvalBus = approvalBus,
                )
            core.registerProvider(provider("terminal-tab", echoTool("run_command")))

            // Trusted: runs immediately, no prompt.
            assertFalse(core.invoke("run_command", "{}").isError)
            assertTrue(approvalBus.pendingList.value.isEmpty())

            policyEngine.revokeProviderPolicy("terminal-tab")

            // Revoked: back to ASK, so this call suspends for a real prompt.
            val job = async { core.invoke("run_command", "{}") }
            val request = approvalBus.pendingList.first { it.isNotEmpty() }.first()
            approvalBus.deny(request.id)
            assertTrue(job.await().isError)
        }

    @Test
    fun `trustProvider on a dialog left stale by a revoke does not run the call or persist a grant`() =
        runBlocking {
            // BossConsole#542 review: a queued approval must not be able to persist a
            // provider-wide grant a reset already invalidated (AGENTS.md's governed-MCP
            // section - "each reset invalidates older authorizations before their final
            // approval boundary, including queued once/session/persistent grants").
            var called = false
            val policyEngine = McpPolicyEngine(policyFile = null)
            val approvalBus = McpApprovalBus(defaultTimeoutMs = 5000L)
            val ledger = McpOperationLedger(ledgerFile = null)
            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    policyEngine = policyEngine,
                    approvalBus = approvalBus,
                    ledger = ledger,
                )
            core.registerProvider(
                provider(
                    "terminal-tab",
                    echoTool(
                        "run_command",
                        handler =
                            McpToolHandler {
                                called = true
                                McpToolResult("ran")
                            },
                    ),
                ),
            )

            // The prompt opens and captures the current revocation.
            val call = async { core.invoke("run_command", "{}") }
            val request = approvalBus.pendingList.first { it.isNotEmpty() }.first()

            // The operator resets this tool from "Persisted MCP policies" while the dialog is
            // still open - bumps the revocation the pending request already captured.
            policyEngine.revokePersistedPolicy("run_command")

            // Clicking "Trust This Plugin" on the now-stale dialog must not run the call and
            // must not persist the provider-wide grant the reset was supposed to invalidate -
            // unlike a genuine disk-write failure (PROVIDER_TRUST_PERSIST_FAILED), which still
            // runs the call.
            approvalBus.approve(request.id, trustProvider = true)
            assertTrue(call.await().isError)
            assertFalse(called, "a call cancelled out from under a stale dialog must never execute")
            assertEquals(
                McpApprovalDisposition.POLICY_DENIED,
                ledger.recentOperations.value
                    .single()
                    .approvalDisposition,
            )
            assertNull(
                policyEngine.config.value.providerRules["terminal-tab"],
                "the reset must not have been overwritten by the stale approval",
            )
            assertEquals(McpPolicyAction.ASK, policyEngine.policyFor("run_command", "terminal-tab"))
        }

    // A registry wired to a policy file whose parent path is a file, so the atomic
    // write's directory creation fails - the same blocked-write setup the per-tool
    // failure case uses in McpPersistentApprovalTest.
    private class ProviderPolicyFailureFixture(
        providerToRegister: McpToolProvider,
    ) {
        val dir = Files.createTempDirectory("mcp-provider-policy-failure").toFile()
        private val parent = dir.resolve("parent")
        val policyEngine = McpPolicyEngine(parent.resolve("policy.json"))
        val approvalBus = McpApprovalBus(defaultTimeoutMs = 5000L)
        val ledger = McpOperationLedger(ledgerFile = null)
        val core =
            McpToolRegistryCore(
                disabledFile = null,
                policyEngine = policyEngine,
                approvalBus = approvalBus,
                ledger = ledger,
            )

        init {
            parent.writeText("blocks directory creation")
            core.registerProvider(providerToRegister)
        }

        fun close() = dir.deleteRecursively()
    }

    private fun failingProviderFixture(
        onCommand: () -> Unit,
        onDelete: () -> Unit,
    ): ProviderPolicyFailureFixture =
        ProviderPolicyFailureFixture(
            provider(
                "terminal-tab",
                echoTool(
                    "run_command",
                    handler =
                        McpToolHandler {
                            onCommand()
                            McpToolResult("ran")
                        },
                ),
                echoTool(
                    "k8s_delete",
                    handler =
                        McpToolHandler {
                            onDelete()
                            McpToolResult("deleted")
                        },
                ),
            ),
        )

    @Test
    fun `a failed provider persist still runs the approved call and records the fallback`() =
        runBlocking {
            var commandCalls = 0
            val fixture = failingProviderFixture(onCommand = { commandCalls++ }, onDelete = { })
            try {
                // Approve with "Trust This Plugin"; the provider-wide write cannot persist.
                val call = async { fixture.core.invoke("run_command", "{}") }
                val request =
                    fixture.approvalBus.pendingList
                        .first { it.isNotEmpty() }
                        .first()
                fixture.approvalBus.approve(request.id, trustProvider = true)

                // A failed persist must not block the already-approved call, and the
                // ledger records that the durable grant did not save.
                assertFalse(call.await().isError)
                assertEquals(1, commandCalls)
                assertTrue(fixture.policyEngine.fault.value is McpPolicyFault.ProviderPolicyPersistFailed)
                assertEquals(
                    McpApprovalDisposition.PROVIDER_TRUST_PERSIST_FAILED,
                    fixture.ledger.recentOperations.value
                        .single()
                        .approvalDisposition,
                )

                // The fallback is per-tool: this one tool is session-trusted, so it runs
                // again without a prompt.
                assertFalse(fixture.core.invoke("run_command", "{}").isError)
                assertEquals(2, commandCalls)
                assertTrue(
                    fixture.approvalBus.pendingList.value
                        .isEmpty(),
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `a failed provider persist does not cover the provider's other tools`() =
        runBlocking {
            var deleteCalls = 0
            val fixture = failingProviderFixture(onCommand = { }, onDelete = { deleteCalls++ })
            try {
                val call = async { fixture.core.invoke("run_command", "{}") }
                val request =
                    fixture.approvalBus.pendingList
                        .first { it.isNotEmpty() }
                        .first()
                fixture.approvalBus.approve(request.id, trustProvider = true)
                call.await()

                // No provider rule survived the failed write, so a sibling tool from the
                // same provider is not covered and still asks.
                assertEquals(0, deleteCalls)
                val siblingCall = async { fixture.core.invoke("k8s_delete", "{}") }
                val siblingRequest =
                    fixture.approvalBus.pendingList
                        .first { it.isNotEmpty() }
                        .first()
                fixture.approvalBus.deny(siblingRequest.id)
                assertTrue(siblingCall.await().isError)
                assertEquals(0, deleteCalls)
            } finally {
                fixture.close()
            }
        }
}
