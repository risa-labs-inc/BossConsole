package ai.rever.boss.mcp

import ai.rever.boss.mcp.sandbox.DefaultMcpRiskEvaluator
import ai.rever.boss.mcp.sandbox.DefaultMcpSandboxPolicyGate
import ai.rever.boss.mcp.sandbox.DefaultMcpToolSandbox
import ai.rever.boss.mcp.sandbox.McpApprovalHandler
import ai.rever.boss.mcp.sandbox.McpRiskAssessment
import ai.rever.boss.mcp.sandbox.McpRiskEvaluator
import ai.rever.boss.mcp.sandbox.McpRiskLevel
import ai.rever.boss.mcp.sandbox.McpSandboxPolicyGate
import ai.rever.boss.mcp.sandbox.McpToolDecision
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Comprehensive unit tests for Milestone 1 of the Agent Tool Sandbox:
 * - Risk classification determinism
 * - Policy gate evaluation
 * - Approval handler integration
 * - Fail-closed behavior for unresolved approval requests
 * - Precedence: disabled tool & RBAC gating occur before sandbox evaluation
 * - Verification that denied handlers NEVER execute
 */
class McpToolSandboxTest {
    private fun provider(
        id: String,
        vararg defs: McpToolDefinition,
    ) = object : McpToolProvider {
        override val providerId = id

        override fun tools() = defs.toList()
    }

    private fun testTool(
        name: String,
        requiredPermissions: List<String> = emptyList(),
        requiresAdmin: Boolean = false,
        handler: McpToolHandler,
    ) = McpToolDefinition(name = name, description = "test tool $name", handler = handler)
        .apply {
            this.requiredPermissions = requiredPermissions
            this.requiresAdmin = requiresAdmin
        }

    // ---------------------------------------------------------------------
    // Risk Evaluator Tests
    // ---------------------------------------------------------------------

    @Test
    fun `risk classification is deterministic for read-only tools`() {
        val evaluator = DefaultMcpRiskEvaluator()
        val emptyArgs = McpToolArgs(emptyMap(), "{}")

        val assessment = evaluator.evaluateRisk("codebase_read", emptyArgs)
        assertEquals(McpRiskLevel.LOW, assessment.level)
        assertTrue(assessment.reason.contains("Safe read-only"))
    }

    @Test
    fun `risk classification defaults to LOW for unknown tools`() {
        val evaluator = DefaultMcpRiskEvaluator()
        val emptyArgs = McpToolArgs(emptyMap(), "{}")

        val assessment = evaluator.evaluateRisk("custom_unknown_tool", emptyArgs)
        assertEquals(McpRiskLevel.LOW, assessment.level)
        assertTrue(assessment.reason.contains("Unclassified tool"))
    }

    @Test
    fun `risk classification evaluates secret_get as CRITICAL`() {
        val evaluator = DefaultMcpRiskEvaluator()
        val emptyArgs = McpToolArgs(emptyMap(), "{}")

        val assessment = evaluator.evaluateRisk("secret_get", emptyArgs)
        assertEquals(McpRiskLevel.CRITICAL, assessment.level)
        assertTrue(assessment.reason.contains("secret credentials"))
    }

    @Test
    fun `risk classification evaluates destructive Docker and K8s operations as CRITICAL`() {
        val evaluator = DefaultMcpRiskEvaluator()
        val emptyArgs = McpToolArgs(emptyMap(), "{}")

        assertEquals(McpRiskLevel.CRITICAL, evaluator.evaluateRisk("docker_rm", emptyArgs).level)
        assertEquals(McpRiskLevel.CRITICAL, evaluator.evaluateRisk("k8s_delete", emptyArgs).level)
        assertEquals(McpRiskLevel.CRITICAL, evaluator.evaluateRisk("helm_uninstall", emptyArgs).level)
        assertEquals(McpRiskLevel.HIGH, evaluator.evaluateRisk("k8s_exec", emptyArgs).level)
    }

    @Test
    fun `risk classification inspects shell command arguments for destructive patterns`() {
        val evaluator = DefaultMcpRiskEvaluator()

        val safeCmdArgs = McpToolArgs(mapOf("command" to "ls -la"), """{"command":"ls -la"}""")
        val safeAssessment = evaluator.evaluateRisk("run_command", safeCmdArgs)
        assertEquals(McpRiskLevel.MEDIUM, safeAssessment.level)

        val destructiveCmdArgs =
            McpToolArgs(mapOf("command" to "rm -rf /tmp/test"), """{"command":"rm -rf /tmp/test"}""")
        val destructiveAssessment = evaluator.evaluateRisk("run_command", destructiveCmdArgs)
        assertEquals(McpRiskLevel.CRITICAL, destructiveAssessment.level)
        assertTrue(destructiveAssessment.reason.contains("destructive command pattern"))
    }

    // ---------------------------------------------------------------------
    // Sandbox Execution & Policy Gate Integration Tests
    // ---------------------------------------------------------------------

    @Test
    fun `allowed invocation reaches handler`() =
        runBlocking {
            var executed = false
            val core = McpToolRegistryCore(disabledFile = null)
            core.registerProvider(
                provider(
                    "p1",
                    testTool("codebase_read") {
                        executed = true
                        McpToolResult("read ok")
                    },
                ),
            )

            val result = core.invoke("codebase_read", "{}")

            assertTrue(executed, "Allowed tool handler must be executed")
            assertFalse(result.isError)
            assertEquals("read ok", result.text)
        }

    @Test
    fun `DENY policy decision prevents handler execution and returns isError true`() =
        runBlocking {
            var executed = false
            // Custom policy gate that denies all invocations
            val denyPolicyGate =
                McpSandboxPolicyGate { _, _, _ -> McpToolDecision.DENY }
            val sandbox = DefaultMcpToolSandbox(policyGate = denyPolicyGate)
            val core = McpToolRegistryCore(disabledFile = null, sandbox = sandbox)

            core.registerProvider(
                provider(
                    "p1",
                    testTool("any_tool") {
                        executed = true
                        McpToolResult("should not run")
                    },
                ),
            )

            val result = core.invoke("any_tool", "{}")

            assertFalse(executed, "Denied tool handler MUST NOT execute")
            assertTrue(result.isError)
            assertTrue(result.text.contains("Sandbox denied invocation"))
        }

    @Test
    fun `REQUIRE_APPROVAL without approval handler prevents execution (fail-closed)`() =
        runBlocking {
            var executed = false
            // Default policy gate maps HIGH/CRITICAL (e.g. secret_get) to REQUIRE_APPROVAL
            val sandbox = DefaultMcpToolSandbox(approvalHandler = null)
            val core = McpToolRegistryCore(disabledFile = null, sandbox = sandbox)

            core.registerProvider(
                provider(
                    "p1",
                    testTool("secret_get") {
                        executed = true
                        McpToolResult("secret value")
                    },
                ),
            )

            val result = core.invoke("secret_get", "{}")

            assertFalse(executed, "Handler MUST NOT execute when REQUIRE_APPROVAL has no approval handler")
            assertTrue(result.isError)
            assertTrue(result.text.contains("requires human approval"))
        }

    @Test
    fun `REQUIRE_APPROVAL with approval handler that grants approval executes handler`() =
        runBlocking {
            var executed = false
            val autoApproveHandler = McpApprovalHandler { _, _, _ -> true }
            val sandbox = DefaultMcpToolSandbox(approvalHandler = autoApproveHandler)
            val core = McpToolRegistryCore(disabledFile = null, sandbox = sandbox)

            core.registerProvider(
                provider(
                    "p1",
                    testTool("secret_get") {
                        executed = true
                        McpToolResult("secret value")
                    },
                ),
            )

            val result = core.invoke("secret_get", "{}")

            assertTrue(executed, "Handler MUST execute when human approval is granted")
            assertFalse(result.isError)
            assertEquals("secret value", result.text)
        }

    @Test
    fun `REQUIRE_APPROVAL with approval handler that rejects approval prevents execution`() =
        runBlocking {
            var executed = false
            val rejectHandler = McpApprovalHandler { _, _, _ -> false }
            val sandbox = DefaultMcpToolSandbox(approvalHandler = rejectHandler)
            val core = McpToolRegistryCore(disabledFile = null, sandbox = sandbox)

            core.registerProvider(
                provider(
                    "p1",
                    testTool("secret_get") {
                        executed = true
                        McpToolResult("secret value")
                    },
                ),
            )

            val result = core.invoke("secret_get", "{}")

            assertFalse(executed, "Handler MUST NOT execute when human approval is rejected")
            assertTrue(result.isError)
        }

    // ---------------------------------------------------------------------
    // Precedence Tests (Disabled tools & RBAC check before Sandbox)
    // ---------------------------------------------------------------------

    @Test
    fun `disabled tool rejection occurs before sandbox logic`() =
        runBlocking {
            var sandboxEvaluated = false
            val defaultEvaluator = DefaultMcpRiskEvaluator()
            val trackingEvaluator =
                McpRiskEvaluator { toolName, args ->
                    sandboxEvaluated = true
                    defaultEvaluator.evaluateRisk(toolName, args)
                }
            val sandbox = DefaultMcpToolSandbox(riskEvaluator = trackingEvaluator)
            val core = McpToolRegistryCore(disabledFile = null, sandbox = sandbox)

            core.registerProvider(provider("p1", testTool("disabled_tool") { McpToolResult("ok") }))
            core.setToolEnabled("disabled_tool", enabled = false)

            val result = core.invoke("disabled_tool", "{}")

            assertTrue(result.isError)
            assertTrue(result.text.contains("Unknown or disabled MCP tool"))
            assertFalse(sandboxEvaluated, "Sandbox MUST NOT be evaluated for a disabled tool")
        }

    @Test
    fun `permission-denied tool rejection occurs before sandbox logic`() =
        runBlocking {
            var sandboxEvaluated = false
            val defaultEvaluator = DefaultMcpRiskEvaluator()
            val trackingEvaluator =
                McpRiskEvaluator { toolName, args ->
                    sandboxEvaluated = true
                    defaultEvaluator.evaluateRisk(toolName, args)
                }
            val sandbox = DefaultMcpToolSandbox(riskEvaluator = trackingEvaluator)
            val core = McpToolRegistryCore(disabledFile = null, sandbox = sandbox)

            core.registerProvider(
                provider(
                    "p1",
                    testTool("gated_tool", requiredPermissions = listOf("admin.perm")) {
                        McpToolResult("ok")
                    },
                ),
            )
            // updateAccess with no permissions
            core.updateAccess(isAdmin = false, permissions = emptySet())

            val result = core.invoke("gated_tool", "{}")

            assertTrue(result.isError)
            assertTrue(result.text.contains("Unknown or disabled MCP tool"))
            assertFalse(sandboxEvaluated, "Sandbox MUST NOT be evaluated for a permission-denied tool")
        }

    // ---------------------------------------------------------------------
    // Argument Parsing Preservation Test
    // ---------------------------------------------------------------------

    @Test
    fun `existing argument parsing behavior is preserved in sandbox`() =
        runBlocking {
            var capturedArgs: McpToolArgs? = null
            val core = McpToolRegistryCore(disabledFile = null)

            core.registerProvider(
                provider(
                    "p1",
                    testTool("codebase_read") { args ->
                        capturedArgs = args
                        McpToolResult("ok")
                    },
                ),
            )

            val result = core.invoke("codebase_read", """{"path":"/src/Main.kt","lines":100}""")

            assertFalse(result.isError)
            val args = requireNotNull(capturedArgs)
            assertEquals("/src/Main.kt", args.string("path"))
            assertEquals(100, args.int("lines"))
        }
}
