package ai.rever.boss.mcp

import ai.rever.boss.mcp.sandbox.DefaultMcpRiskEvaluator
import ai.rever.boss.mcp.sandbox.McpRiskLevel
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

/** Risk classification and argument preservation; shared governance tests cover approval delivery. */
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
    fun `infrastructure creates and credential mutations require approval`() {
        val evaluator = DefaultMcpRiskEvaluator()
        for (name in listOf("docker_build", "docker_compose_up", "k8s_apply", "helm_install", "secret_delete")) {
            val assessment = evaluator.evaluateRisk(name, McpToolArgs(emptyMap()))
            assertTrue(assessment.level == McpRiskLevel.HIGH || assessment.level == McpRiskLevel.CRITICAL)
        }
    }

    @Test
    fun `risk classification is deterministic for read-only tools`() {
        val evaluator = DefaultMcpRiskEvaluator()
        val emptyArgs = McpToolArgs(emptyMap(), "{}")

        val assessment = evaluator.evaluateRisk("codebase_read", emptyArgs)
        assertEquals(McpRiskLevel.LOW, assessment.level)
        assertTrue(assessment.reason.contains("Read-only"))
        assertTrue(assessment.reason.contains("sensitive"))
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
        assertEquals(McpRiskLevel.HIGH, safeAssessment.level)

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

    @Test
    fun `shell prefixes never bypass approval`() {
        val evaluator = DefaultMcpRiskEvaluator()
        val commands =
            listOf(
                "echo ok; touch /tmp/x",
                "ls $(touch /tmp/x)",
                "git diff --output=/tmp/x",
                "cat > /tmp/x",
                "pwd",
            )
        for (command in commands) {
            val args = McpToolArgs(mapOf("command" to command), "{}")
            assertEquals(McpRiskLevel.HIGH, evaluator.evaluateRisk("run_command", args).level, command)
        }
        assertEquals(McpRiskLevel.HIGH, evaluator.evaluateRisk("project_replace", McpToolArgs(emptyMap(), "{}")).level)
    }
}
