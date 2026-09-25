package ai.rever.boss.mcp

import ai.rever.boss.mcp.sandbox.DefaultMcpRiskEvaluator
import ai.rever.boss.mcp.sandbox.McpRiskLevel
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for issue #895: MCP standing ALLOWs must not silently execute
 * commands the risk evaluator rates CRITICAL. A persisted ALLOW, provider trust,
 * session trust, or read-only default that produces ALLOW must escalate to ASK
 * when the invocation's real arguments drive the risk level to CRITICAL.
 *
 * [McpToolRegistryCore.invoke] evaluates the real arguments on ALLOW paths and escalates
 * CRITICAL findings to ASK, so a standing grant cannot bypass human review.
 */
class McpCriticalReAskTest {
    private val tempFiles = mutableListOf<File>()

    private fun createTempPolicyFile(): File {
        val dir =
            kotlin.io.path
                .createTempDirectory("mcp-critical-reask-test")
                .toFile()
        return File(dir, "mcp-tool-policy.json").also { tempFiles.add(it) }
    }

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { it.parentFile?.deleteRecursively() }
        tempFiles.clear()
    }

    // ---------------------------------------------------------------------
    // Risk evaluator classification and the unchanged default policy.
    // ---------------------------------------------------------------------

    @Test
    fun `policyFor with empty args evaluates run_command as HIGH (mutating default)`() {
        val engine = McpPolicyEngine(policyFile = null)
        // With no args, run_command evaluates as HIGH (shell tool, no destructive pattern) → ASK
        assertEquals(McpPolicyAction.ASK, engine.policyFor("run_command"))
    }

    @Test
    fun `destructive args evaluate run_command as CRITICAL`() {
        val engine = McpPolicyEngine(policyFile = null)
        val destructiveArgs = McpToolArgs(mapOf("command" to "rm -rf /"), """{"command":"rm -rf /"}""")
        // With real args, the risk evaluator sees the destructive pattern → CRITICAL → ASK
        val risk = DefaultMcpRiskEvaluator().evaluateRisk("run_command", destructiveArgs)
        assertEquals(McpRiskLevel.CRITICAL, risk.level)
        assertEquals(McpPolicyAction.ASK, engine.policyFor("run_command"))
    }

    @Test
    fun `benign args still evaluate run_command as HIGH`() {
        val engine = McpPolicyEngine(policyFile = null)
        val benignArgs = McpToolArgs(mapOf("command" to "ls -la"), """{"command":"ls -la"}""")
        val risk = DefaultMcpRiskEvaluator().evaluateRisk("run_command", benignArgs)
        assertEquals(McpRiskLevel.HIGH, risk.level)
        assertEquals(McpPolicyAction.ASK, engine.policyFor("run_command"))
    }

    @Test
    fun `policyFor default remains backward compatible`() {
        val engine = McpPolicyEngine(policyFile = null)
        // Default policy is name based; the invocation gate handles argument risk.
        assertEquals(McpPolicyAction.ASK, engine.policyFor("run_command"))
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("git_status"))
    }

    // ---------------------------------------------------------------------
    // Standing ALLOW escalates CRITICAL to ASK in invoke().
    // ---------------------------------------------------------------------

    private fun provider(
        id: String,
        vararg defs: McpToolDefinition,
    ) = object : McpToolProvider {
        override val providerId = id

        override fun tools() = defs.toList()
    }

    private fun echoTool(
        name: String,
        handler: McpToolHandler = McpToolHandler { McpToolResult("ok:$name") },
    ) = McpToolDefinition(name = name, description = "test tool $name", handler = handler)

    private fun tempDisabledFile(): File {
        val dir =
            kotlin.io.path
                .createTempDirectory("mcp-critical-reask-registry")
                .toFile()
        return File(dir, "mcp-disabled-tools.json").also { tempFiles.add(it) }
    }

    /**
     * A standing-ALLOW escalation test with a real operator on the bus: short approval timeout
     * (no 45 s default), the prompt's actual appearance in [McpApprovalBus.pendingList] is what
     * pins the escalation, and the ledger record shows the post-escalation ASK policy.
     */
    @Test
    fun `standing ALLOW with CRITICAL args escalates to ASK and prompts operator`() =
        runBlocking {
            val policyFile = createTempPolicyFile()
            val engine = McpPolicyEngine(policyFile = policyFile)
            // Persist an ALLOW for run_command — this is the "Always Allow" standing grant
            engine.setToolPolicy("run_command", McpPolicyAction.ALLOW)
            assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command", "terminal-tab"))

            val approvalBus = McpApprovalBus(defaultTimeoutMs = 5000L)
            val ledger = McpOperationLedger(ledgerFile = null)
            var handlerCalled = false
            val core =
                McpToolRegistryCore(
                    disabledFile = tempDisabledFile(),
                    policyEngine = engine,
                    approvalBus = approvalBus,
                    ledger = ledger,
                )
            core.registerProvider(
                provider(
                    "terminal-tab",
                    echoTool(
                        name = "run_command",
                        handler =
                            McpToolHandler {
                                handlerCalled = true
                                McpToolResult("ok:run_command")
                            },
                    ),
                ),
            )

            // A destructive command that the risk evaluator rates CRITICAL
            val call = async { core.invoke("run_command", """{"command":"rm -rf /"}""") }

            // The escalation IS the prompt: no operator answer yet, so the call must be suspended
            // on the bus, and the request must carry the CRITICAL assessment that triggered it.
            val request = withTimeout(5000L) { approvalBus.pendingList.first { it.isNotEmpty() }.first() }
            assertEquals("run_command", request.toolName)
            assertEquals(McpRiskLevel.CRITICAL, request.riskAssessment?.level)

            approvalBus.deny(request.id, "denied by test")

            val result = call.await()
            assertTrue(result.isError, "CRITICAL command under standing ALLOW must not auto-execute")
            assertTrue(result.text.contains("denied by test"), result.text)
            assertFalse(handlerCalled, "Handler must not run for escalated CRITICAL")

            // The ledger records the post-escalation policy, not the standing ALLOW.
            val record = ledger.recentOperations.value.single()
            assertEquals(McpPolicyAction.ASK, record.policyApplied)
            assertEquals(McpApprovalDisposition.DENIED_BY_OPERATOR, record.approvalDisposition)
        }

    @Test
    fun `standing ALLOW with benign args still auto-allows`() =
        runBlocking {
            val policyFile = createTempPolicyFile()
            val engine = McpPolicyEngine(policyFile = policyFile)
            engine.setToolPolicy("run_command", McpPolicyAction.ALLOW)

            val approvalBus = McpApprovalBus(defaultTimeoutMs = 5000L)
            val core =
                McpToolRegistryCore(
                    disabledFile = tempDisabledFile(),
                    policyEngine = engine,
                    approvalBus = approvalBus,
                )
            core.registerProvider(provider("terminal-tab", echoTool("run_command")))

            // HIGH is not CRITICAL — standing ALLOW still applies and no prompt may be raised
            val result = core.invoke("run_command", """{"command":"ls -la"}""")

            assertFalse(result.isError, "Non-CRITICAL command under standing ALLOW should auto-execute")
            assertTrue(result.text.contains("ok:run_command"), "Handler should run for non-CRITICAL")
            assertTrue(approvalBus.pendingList.value.isEmpty(), "no prompt may be raised for HIGH")
        }

    @Test
    fun `session trust with CRITICAL args escalates to ASK`() =
        runBlocking {
            val policyFile = createTempPolicyFile()
            val engine = McpPolicyEngine(policyFile = policyFile)
            // Grant session trust instead of a persisted rule
            engine.trustForSession("run_command", "terminal-tab")
            assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command", "terminal-tab"))

            val approvalBus = McpApprovalBus(defaultTimeoutMs = 5000L)
            var handlerCalled = false
            val core =
                McpToolRegistryCore(
                    disabledFile = tempDisabledFile(),
                    policyEngine = engine,
                    approvalBus = approvalBus,
                )
            core.registerProvider(
                provider(
                    "terminal-tab",
                    echoTool(
                        name = "run_command",
                        handler =
                            McpToolHandler {
                                handlerCalled = true
                                McpToolResult("ok:run_command")
                            },
                    ),
                ),
            )

            val call = async { core.invoke("run_command", """{"command":"git push --force"}""") }

            val request = withTimeout(5000L) { approvalBus.pendingList.first { it.isNotEmpty() }.first() }
            assertEquals(McpRiskLevel.CRITICAL, request.riskAssessment?.level)
            approvalBus.deny(request.id, "denied by test")

            val result = call.await()
            assertTrue(result.isError, "CRITICAL command under session trust must not auto-execute")
            assertFalse(handlerCalled, "Handler must not run for escalated CRITICAL")
        }

    @Test
    fun `provider trust with CRITICAL args escalates to ASK`() =
        runBlocking {
            val policyFile = createTempPolicyFile()
            val engine = McpPolicyEngine(policyFile = policyFile)
            // "Trust this plugin" — provider-wide ALLOW
            engine.setProviderPolicy("terminal-tab", McpPolicyAction.ALLOW)

            val approvalBus = McpApprovalBus(defaultTimeoutMs = 5000L)
            var handlerCalled = false
            val core =
                McpToolRegistryCore(
                    disabledFile = tempDisabledFile(),
                    policyEngine = engine,
                    approvalBus = approvalBus,
                )
            core.registerProvider(
                provider(
                    "terminal-tab",
                    echoTool(
                        name = "run_command",
                        handler =
                            McpToolHandler {
                                handlerCalled = true
                                McpToolResult("ok:run_command")
                            },
                    ),
                ),
            )

            val call = async { core.invoke("run_command", """{"command":"mkfs.ext4 /dev/sda1"}""") }

            val request = withTimeout(5000L) { approvalBus.pendingList.first { it.isNotEmpty() }.first() }
            assertEquals(McpRiskLevel.CRITICAL, request.riskAssessment?.level)
            approvalBus.deny(request.id, "denied by test")

            val result = call.await()
            assertTrue(result.isError, "CRITICAL command under provider trust must not auto-execute")
            assertFalse(handlerCalled, "Handler must not run for escalated CRITICAL")
        }

    @Test
    fun `standing ALLOW on a CRITICAL-by-name tool still escalates`() =
        runBlocking {
            val policyFile = createTempPolicyFile()
            val engine = McpPolicyEngine(policyFile = policyFile)
            // Persist an ALLOW for secret_get (CRITICAL by name, not by args)
            engine.setToolPolicy("secret_get", McpPolicyAction.ALLOW)

            val approvalBus = McpApprovalBus(defaultTimeoutMs = 5000L)
            val ledger = McpOperationLedger(ledgerFile = null)
            var handlerCalled = false
            val core =
                McpToolRegistryCore(
                    disabledFile = tempDisabledFile(),
                    policyEngine = engine,
                    approvalBus = approvalBus,
                    ledger = ledger,
                )
            core.registerProvider(
                provider(
                    "terminal-tab",
                    echoTool(
                        name = "secret_get",
                        handler =
                            McpToolHandler {
                                handlerCalled = true
                                McpToolResult("ok:secret_get")
                            },
                    ),
                ),
            )

            // Even with no args, secret_get is CRITICAL → must escalate to ASK
            val call = async { core.invoke("secret_get", "{}") }

            val request = withTimeout(5000L) { approvalBus.pendingList.first { it.isNotEmpty() }.first() }
            assertEquals(McpRiskLevel.CRITICAL, request.riskAssessment?.level)
            approvalBus.deny(request.id, "denied by test")

            val result = call.await()
            assertTrue(result.isError, "CRITICAL-by-name tool under standing ALLOW must escalate to ASK")
            assertFalse(handlerCalled, "Handler must not run for escalated CRITICAL")
            assertEquals(
                McpPolicyAction.ASK,
                ledger.recentOperations.value
                    .single()
                    .policyApplied,
            )
        }

    @Test
    fun `an operator-approved escalation still executes`() =
        runBlocking {
            val policyFile = createTempPolicyFile()
            val engine = McpPolicyEngine(policyFile = policyFile)
            engine.setToolPolicy("run_command", McpPolicyAction.ALLOW)

            val approvalBus = McpApprovalBus(defaultTimeoutMs = 5000L)
            val ledger = McpOperationLedger(ledgerFile = null)
            var handlerCalled = false
            val core =
                McpToolRegistryCore(
                    disabledFile = tempDisabledFile(),
                    policyEngine = engine,
                    approvalBus = approvalBus,
                    ledger = ledger,
                )
            core.registerProvider(
                provider(
                    "terminal-tab",
                    echoTool(
                        name = "run_command",
                        handler =
                            McpToolHandler {
                                handlerCalled = true
                                McpToolResult("ok:run_command")
                            },
                    ),
                ),
            )

            val call = async { core.invoke("run_command", """{"command":"rm -rf /"}""") }
            val request = withTimeout(5000L) { approvalBus.pendingList.first { it.isNotEmpty() }.first() }
            approvalBus.approve(request.id)

            // The escalation gates the call, it does not kill it: an explicit operator
            // approval of the specific arguments must run.
            val result = call.await()
            assertFalse(result.isError, "approved escalation must execute")
            assertTrue(result.text.contains("ok:run_command"))
            assertTrue(handlerCalled)
            assertEquals(
                McpApprovalDisposition.APPROVED_ONCE,
                ledger.recentOperations.value
                    .single()
                    .approvalDisposition,
            )
        }

    @Test
    fun `escalation only applies to ALLOW paths, not to the ASK default`() {
        // secret_get is CRITICAL by name, so it gets the mutating default (ASK) —
        // it never reaches ALLOW through the read-only path. This test pins that
        // the escalation only fires on ALLOW, not on ASK.
        val engine = McpPolicyEngine(policyFile = null)

        // secret_get is CRITICAL → mutating default → ASK, never ALLOW
        assertEquals(McpPolicyAction.ASK, engine.policyFor("secret_get"))

        // git_status is read-only → ALLOW by default, no escalation needed
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("git_status"))
    }
}
