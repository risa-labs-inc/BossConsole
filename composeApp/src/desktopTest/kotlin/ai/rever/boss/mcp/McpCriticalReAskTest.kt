package ai.rever.boss.mcp

import ai.rever.boss.mcp.sandbox.DefaultMcpRiskEvaluator
import ai.rever.boss.mcp.sandbox.McpRiskLevel
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.runBlocking
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
 * Covers two layers:
 * 1. [McpPolicyEngine.policyFor] threads real [McpToolArgs] into the risk evaluator
 *    at the default-resolution step, so the default itself is argument-aware.
 * 2. [McpToolRegistryCore.invoke] re-evaluates risk with real args on ALLOW paths
 *    and escalates CRITICAL findings to ASK, so a standing ALLOW does not bypass
 *    human review for destructive commands.
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
    // Layer 1: policyFor is argument-aware at the default-resolution step
    // ---------------------------------------------------------------------

    @Test
    fun `policyFor with empty args evaluates run_command as HIGH (mutating default)`() {
        val engine = McpPolicyEngine(policyFile = null)
        // With no args, run_command evaluates as HIGH (shell tool, no destructive pattern) → ASK
        assertEquals(McpPolicyAction.ASK, engine.policyFor("run_command"))
    }

    @Test
    fun `policyFor with destructive args evaluates run_command as CRITICAL`() {
        val engine = McpPolicyEngine(policyFile = null)
        val destructiveArgs = McpToolArgs(mapOf("command" to "rm -rf /"), """{"command":"rm -rf /"}""")
        // With real args, the risk evaluator sees the destructive pattern → CRITICAL → ASK
        val risk = DefaultMcpRiskEvaluator().evaluateRisk("run_command", destructiveArgs)
        assertEquals(McpRiskLevel.CRITICAL, risk.level)
        // CRITICAL >= HIGH, so the mutating default (ASK) applies — same result, but now
        // the decision is argument-aware rather than blanket
        assertEquals(McpPolicyAction.ASK, engine.policyFor("run_command", args = destructiveArgs))
    }

    @Test
    fun `policyFor with benign args still evaluates run_command as HIGH`() {
        val engine = McpPolicyEngine(policyFile = null)
        val benignArgs = McpToolArgs(mapOf("command" to "ls -la"), """{"command":"ls -la"}""")
        val risk = DefaultMcpRiskEvaluator().evaluateRisk("run_command", benignArgs)
        assertEquals(McpRiskLevel.HIGH, risk.level)
        assertEquals(McpPolicyAction.ASK, engine.policyFor("run_command", args = benignArgs))
    }

    @Test
    fun `policyFor with empty args is backward compatible`() {
        val engine = McpPolicyEngine(policyFile = null)
        // No args parameter → uses empty map, same as before #895
        assertEquals(McpPolicyAction.ASK, engine.policyFor("run_command"))
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("git_status"))
    }

    // ---------------------------------------------------------------------
    // Layer 2: standing ALLOW escalates CRITICAL to ASK in invoke()
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

    @Test
    fun `standing ALLOW with CRITICAL args escalates to ASK and prompts operator`() =
        runBlocking {
            val policyFile = createTempPolicyFile()
            val engine = McpPolicyEngine(policyFile = policyFile)
            // Persist an ALLOW for run_command — this is the "Always Allow" standing grant
            engine.setToolPolicy("run_command", McpPolicyAction.ALLOW)
            assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command", "terminal-tab"))

            val core =
                McpToolRegistryCore(
                    disabledFile = tempDisabledFile(),
                    policyEngine = engine,
                )
            core.registerProvider(provider("terminal-tab", echoTool("run_command")))

            // A destructive command that the risk evaluator rates CRITICAL
            val result = core.invoke("run_command", """{"command":"rm -rf /"}""")

            // The call should NOT auto-allow: it should be blocked or prompted.
            // Without an operator to approve (no UI in test), the approval times out.
            assertTrue(result.isError, "CRITICAL command under standing ALLOW must not auto-execute")
            assertFalse(result.text.contains("ok:run_command"), "Handler must not run for escalated CRITICAL")
        }

    @Test
    fun `standing ALLOW with benign args still auto-allows`() =
        runBlocking {
            val policyFile = createTempPolicyFile()
            val engine = McpPolicyEngine(policyFile = policyFile)
            engine.setToolPolicy("run_command", McpPolicyAction.ALLOW)

            val core =
                McpToolRegistryCore(
                    disabledFile = tempDisabledFile(),
                    policyEngine = engine,
                )
            core.registerProvider(provider("terminal-tab", echoTool("run_command")))

            // A benign command that the risk evaluator rates HIGH (not CRITICAL)
            val result = core.invoke("run_command", """{"command":"ls -la"}""")

            // HIGH is not CRITICAL — standing ALLOW still applies
            assertFalse(result.isError, "Non-CRITICAL command under standing ALLOW should auto-execute")
            assertTrue(result.text.contains("ok:run_command"), "Handler should run for non-CRITICAL")
        }

    @Test
    fun `session trust with CRITICAL args escalates to ASK`() =
        runBlocking {
            val policyFile = createTempPolicyFile()
            val engine = McpPolicyEngine(policyFile = policyFile)
            // Grant session trust instead of a persisted rule
            engine.trustForSession("run_command", "terminal-tab")
            assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command", "terminal-tab"))

            val core =
                McpToolRegistryCore(
                    disabledFile = tempDisabledFile(),
                    policyEngine = engine,
                )
            core.registerProvider(provider("terminal-tab", echoTool("run_command")))

            val result = core.invoke("run_command", """{"command":"git push --force"}""")

            assertTrue(result.isError, "CRITICAL command under session trust must not auto-execute")
            assertFalse(result.text.contains("ok:run_command"), "Handler must not run for escalated CRITICAL")
        }

    @Test
    fun `provider trust with CRITICAL args escalates to ASK`() =
        runBlocking {
            val policyFile = createTempPolicyFile()
            val engine = McpPolicyEngine(policyFile = policyFile)
            // "Trust this plugin" — provider-wide ALLOW
            engine.setProviderPolicy("terminal-tab", McpPolicyAction.ALLOW)

            val core =
                McpToolRegistryCore(
                    disabledFile = tempDisabledFile(),
                    policyEngine = engine,
                )
            core.registerProvider(provider("terminal-tab", echoTool("run_command")))

            val result = core.invoke("run_command", """{"command":"mkfs.ext4 /dev/sda1"}""")

            assertTrue(result.isError, "CRITICAL command under provider trust must not auto-execute")
        }

    @Test
    fun `read-only tool with CRITICAL-severity args under read-only default still auto-allows`() =
        runBlocking {
            // secret_get is CRITICAL by name, so it gets the mutating default (ASK) —
            // it never reaches ALLOW through the read-only path. This test pins that
            // the escalation only fires on ALLOW, not on ASK.
            val engine = McpPolicyEngine(policyFile = null)

            // secret_get is CRITICAL → mutating default → ASK, never ALLOW
            assertEquals(McpPolicyAction.ASK, engine.policyFor("secret_get"))

            // git_status is read-only → ALLOW by default, no escalation needed
            assertEquals(McpPolicyAction.ALLOW, engine.policyFor("git_status"))
        }

    @Test
    fun `standing ALLOW on a CRITICAL-by-name tool still escalates`() =
        runBlocking {
            val policyFile = createTempPolicyFile()
            val engine = McpPolicyEngine(policyFile = policyFile)
            // Persist an ALLOW for secret_get (CRITICAL by name, not by args)
            engine.setToolPolicy("secret_get", McpPolicyAction.ALLOW)

            val core =
                McpToolRegistryCore(
                    disabledFile = tempDisabledFile(),
                    policyEngine = engine,
                )
            core.registerProvider(provider("terminal-tab", echoTool("secret_get")))

            // Even with no args, secret_get is CRITICAL → must escalate to ASK
            val result = core.invoke("secret_get", "{}")

            assertTrue(result.isError, "CRITICAL-by-name tool under standing ALLOW must escalate to ASK")
            assertFalse(result.text.contains("ok:secret_get"), "Handler must not run for escalated CRITICAL")
        }
}
