package ai.rever.boss.mcp

import ai.rever.boss.mcp.sandbox.McpRiskLevel
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A saved "Always Allow" on a shell tool means "don't ask for routine calls", not "run anything"
 * (#1577). A routine command still runs with no prompt; a command the evaluator rates CRITICAL -
 * destructive wording - reaches the operator as an approval request, whatever the saved rule says.
 *
 * Asserted on the approval request itself (who raised it, and the risk it carries), not on error
 * text, so a regression that skipped the prompt but failed some other way would still show.
 */
class McpDestructiveShellAllowTest {
    private var handlerRuns = 0

    private val approvalBus = McpApprovalBus(defaultTimeoutMs = 5_000L)
    private val ledger = McpOperationLedger(ledgerFile = null)
    private val policyEngine = McpPolicyEngine(policyFile = null)

    /** A core holding [toolNames]; [allowEachTool] saves an ALLOW rule on each, as "Always Allow" does. */
    private fun core(
        vararg toolNames: String,
        allowEachTool: Boolean = true,
    ): McpToolRegistryCore {
        val tools =
            toolNames.map { name ->
                McpToolDefinition(
                    name = name,
                    description = "test tool $name",
                    handler =
                        McpToolHandler {
                            handlerRuns++
                            McpToolResult("ran $name")
                        },
                )
            }
        val core =
            McpToolRegistryCore(
                disabledFile = null,
                policyEngine = policyEngine,
                approvalBus = approvalBus,
                ledger = ledger,
            )
        core.registerProvider(
            object : McpToolProvider {
                override val providerId = "p1"

                override fun tools() = tools
            },
        )
        if (allowEachTool) tools.forEach { policyEngine.setToolPolicy(it.name, McpPolicyAction.ALLOW) }
        return core
    }

    private fun command(text: String) = """{"command":"$text"}"""

    /**
     * The one approval request the call under test raised. Bounded, so a regression that never
     * raises the prompt fails here with a reason instead of hanging the suite.
     */
    private suspend fun awaitPrompt(): McpApprovalRequest =
        withTimeoutOrNull(5_000L) { approvalBus.pendingList.first { it.isNotEmpty() }.single() }
            ?: fail("no approval prompt was raised - a saved ALLOW ran a destructive command unasked")

    @Test
    fun `a routine command under a saved ALLOW still runs without asking`() =
        runBlocking {
            val result = core("run_command").invoke("run_command", command("git status"))

            assertFalse(result.isError, result.text)
            assertEquals(1, handlerRuns)
            assertTrue(approvalBus.pendingList.value.isEmpty(), "a routine call must not raise a prompt")
            assertEquals(
                McpApprovalDisposition.AUTO_ALLOWED,
                ledger.recentOperations.value
                    .first()
                    .approvalDisposition,
            )
        }

    @Test
    fun `a destructive command under a saved ALLOW asks the operator, and runs once approved`() =
        runBlocking {
            val core = core("run_command")
            val pending = async { core.invoke("run_command", command("rm -fr /srv/app")) }

            val request = awaitPrompt()
            assertEquals("run_command", request.toolName)
            assertEquals(McpRiskLevel.CRITICAL, request.riskAssessment?.level)
            assertEquals(McpPolicyAction.ASK, request.policy)
            assertEquals(0, handlerRuns, "nothing may run while the prompt is open")

            approvalBus.approve(request.id, trustForSession = false)
            assertFalse(pending.await().isError)
            assertEquals(1, handlerRuns)
            assertEquals(
                McpPolicyAction.ASK,
                ledger.recentOperations.value
                    .first()
                    .policyApplied,
            )
        }

    @Test
    fun `a destructive command the operator denies never runs`() =
        runBlocking {
            val core = core("run_command")
            val pending = async { core.invoke("run_command", command("git push origin main --force")) }

            val request = awaitPrompt()
            approvalBus.deny(request.id)

            assertTrue(pending.await().isError)
            assertEquals(0, handlerRuns)
        }

    // "Trust This Plugin" saves the ALLOW on the provider rather than the tool; it must not be a
    // way around the prompt either.
    @Test
    fun `a provider-wide ALLOW still asks before a destructive shell command`() =
        runBlocking {
            val core = core("run_command", allowEachTool = false)
            policyEngine.setProviderPolicy("p1", McpPolicyAction.ALLOW)
            val pending = async { core.invoke("run_command", command("rm -rf /")) }

            val request = awaitPrompt()
            assertEquals(McpRiskLevel.CRITICAL, request.riskAssessment?.level)
            approvalBus.deny(request.id)
            assertTrue(pending.await().isError)
            assertEquals(0, handlerRuns)
        }

    // Non-shell tools rate by name alone, and that rating was already weighed when the rule was
    // saved, so a saved ALLOW keeps meaning what it did - even for a CRITICAL name.
    @Test
    fun `a saved ALLOW on a non-shell tool is unchanged, even for a critical one`() =
        runBlocking {
            val core = core("docker_rm")
            val pending = async { core.invoke("docker_rm", """{"container":"web"}""") }
            // Give a wrongly raised prompt time to appear before asserting there is none.
            delay(200)

            assertTrue(approvalBus.pendingList.value.isEmpty())
            assertFalse(pending.await().isError)
            assertEquals(1, handlerRuns)
        }
}
