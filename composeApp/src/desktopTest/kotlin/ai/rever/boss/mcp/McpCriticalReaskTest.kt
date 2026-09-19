package ai.rever.boss.mcp

import ai.rever.boss.mcp.sandbox.McpRiskLevel
import ai.rever.boss.plugin.api.McpToolArgs
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

/**
 * #895: a standing ALLOW is a grant to the tool, not to every argument set it can carry.
 *
 * An argument-aware CRITICAL rating must re-ask on every ALLOW path - a persisted tool rule,
 * provider trust, session trust - instead of executing unattended, while non-CRITICAL
 * argument sets keep running quietly under the same grants, and tools that hold no ALLOW
 * keep their ordinary ASK/DENY behavior. Provider scoping (#815) is untouched: the re-ask
 * carries the tool's provider like any other prompt, and a denial neither widens,
 * inherits nor resets anyone's trust.
 */
class McpCriticalReaskTest {
    private val criticalCommand = "rm -rf /tmp/895-reask-cache"
    private val benignCommand = "ls -la"

    private fun provider(
        id: String,
        vararg defs: McpToolDefinition,
    ) = object : McpToolProvider {
        override val providerId = id

        override fun tools() = defs.toList()
    }

    private fun tool(
        name: String,
        onCall: () -> Unit = {},
    ): McpToolDefinition =
        McpToolDefinition(
            name = name,
            description = "test tool $name",
            handler =
                McpToolHandler {
                    onCall()
                    McpToolResult("ok:$name")
                },
        )

    private fun commandJson(command: String): String = """{"command":"$command"}"""

    @Suppress("MaxLineLength")
    private fun commandArgs(command: String): McpToolArgs = McpToolArgs(mapOf("command" to command), commandJson(command))

    private fun newCore(engine: McpPolicyEngine): McpToolRegistryCore =
        McpToolRegistryCore(
            disabledFile = null,
            policyEngine = engine,
            approvalBus = McpApprovalBus(defaultTimeoutMs = 5_000L),
            ledger = McpOperationLedger(ledgerFile = null),
        )

    @Test
    fun `the re-ask stays on the explicit mutating catalog`() {
        // Argument-driven CRITICAL on shell/exec tools re-asks...
        for (name in listOf("run_command", "open_terminal", "send_input")) {
            assertTrue(
                requiresCriticalReask(name, declaredReadOnly = null, args = commandArgs(criticalCommand)),
                name,
            )
        }
        // ...and a dishonest read-only declaration cannot dodge it: the name wins, fail-closed.
        assertTrue(
            requiresCriticalReask(
                "open_terminal",
                declaredReadOnly = true,
                args = commandArgs(criticalCommand),
            ),
        )
        // Benign arguments on the same tools rate HIGH, not CRITICAL: no re-ask.
        for (name in listOf("run_command", "open_terminal")) {
            assertFalse(
                requiresCriticalReask(name, declaredReadOnly = null, args = commandArgs(benignCommand)),
                name,
            )
        }
        // Read-only tools never re-ask, whatever the arguments carry.
        assertFalse(
            requiresCriticalReask(
                "codebase_read",
                declaredReadOnly = null,
                args = commandArgs(criticalCommand),
            ),
        )
        // A mutating declaration alone is not enough either: unclassified names rate LOW.
        assertFalse(
            requiresCriticalReask(
                "env_sync",
                declaredReadOnly = false,
                args = commandArgs(criticalCommand),
            ),
        )
        // CRITICAL ratings outside the catalog stay quiet: the escalation is the catalog,
        // not the rating alone.
        assertFalse(
            requiresCriticalReask(
                "docker_run",
                declaredReadOnly = null,
                args = McpToolArgs(emptyMap(), "{}"),
            ),
        )
        // Catalog tools whose CRITICAL is argument-independent re-ask too: a standing ALLOW
        // must not hand out unattended secret reads or destructive infrastructure calls.
        assertTrue(
            requiresCriticalReask(
                "secret_get",
                declaredReadOnly = null,
                args = McpToolArgs(emptyMap(), "{}"),
            ),
        )
        assertTrue(
            requiresCriticalReask(
                "docker_rm",
                declaredReadOnly = null,
                args = McpToolArgs(emptyMap(), "{}"),
            ),
        )
    }

    @Test
    fun `persisted standing allow re-asks on critical arguments and runs once approved`() =
        runBlocking {
            var calls = 0
            val engine = McpPolicyEngine(policyFile = null)
            assertTrue(engine.setToolPolicy("run_command", McpPolicyAction.ALLOW))
            val core = newCore(engine)
            core.registerProvider(
                provider(
                    "terminal-tab",
                    tool("run_command") { calls++ },
                ),
            )

            val call = async { core.invoke("run_command", commandJson(criticalCommand)) }
            val request =
                core.approvalBus.pendingList
                    .first { it.isNotEmpty() }
                    .first()
            assertEquals(McpRiskLevel.CRITICAL, request.riskAssessment?.level)
            assertEquals("terminal-tab", request.providerId)
            assertEquals(0, calls)
            core.approvalBus.approve(request.id)
            assertFalse(call.await().isError)
            assertEquals(1, calls)

            // The ledger shows the standing ALLOW and the one-shot approval both: the
            // re-ask is auditable, not a silent policy override.
            assertEquals(
                McpPolicyAction.ALLOW,
                core.ledger.recentOperations.value
                    .single()
                    .policyApplied,
            )
            assertEquals(
                McpApprovalDisposition.APPROVED_ONCE,
                core.ledger.recentOperations.value
                    .single()
                    .approvalDisposition,
            )
        }

    @Test
    fun `standing allow keeps benign argument sets running without a prompt`() =
        runBlocking {
            var calls = 0
            val engine = McpPolicyEngine(policyFile = null)
            assertTrue(engine.setToolPolicy("run_command", McpPolicyAction.ALLOW))
            val core = newCore(engine)
            core.registerProvider(
                provider(
                    "terminal-tab",
                    tool("run_command") { calls++ },
                ),
            )

            val result = core.invoke("run_command", commandJson(benignCommand))
            assertFalse(result.isError)
            assertEquals(1, calls)
            assertTrue(
                core.approvalBus.pendingList.value
                    .isEmpty(),
                "benign args under a standing ALLOW must not prompt",
            )
            assertEquals(
                McpApprovalDisposition.AUTO_ALLOWED,
                core.ledger.recentOperations.value
                    .single()
                    .approvalDisposition,
            )
        }

    @Test
    fun `provider trust re-asks on critical arguments and stays quiet otherwise`() =
        runBlocking {
            var calls = 0
            val engine = McpPolicyEngine(policyFile = null)
            assertTrue(engine.setProviderPolicy("terminal-tab", McpPolicyAction.ALLOW))
            val core = newCore(engine)
            core.registerProvider(
                provider(
                    "terminal-tab",
                    tool("run_command") { calls++ },
                ),
            )

            // Benign: the provider-wide grant means no prompt at all.
            assertFalse(core.invoke("run_command", commandJson(benignCommand)).isError)
            assertTrue(
                core.approvalBus.pendingList.value
                    .isEmpty(),
            )
            assertEquals(1, calls)

            // Critical: the same grant still re-asks, and approval runs the call.
            val call = async { core.invoke("run_command", commandJson(criticalCommand)) }
            val request =
                core.approvalBus.pendingList
                    .first { it.isNotEmpty() }
                    .first()
            assertEquals(McpRiskLevel.CRITICAL, request.riskAssessment?.level)
            assertEquals("terminal-tab", request.providerId)
            core.approvalBus.approve(request.id)
            assertFalse(call.await().isError)
            assertEquals(2, calls)
        }

    @Test
    fun `session trust re-asks on critical arguments without leaking across providers`() =
        runBlocking {
            var trustedCalls = 0
            var squatterCalls = 0
            val engine = McpPolicyEngine(policyFile = null)
            engine.trustForSession("run_command", "terminal-tab")
            val core = newCore(engine)
            core.registerProvider(
                provider(
                    "terminal-tab",
                    tool("run_command") { trustedCalls++ },
                ),
            )

            // The trusted provider's tool runs quietly on benign arguments.
            assertFalse(core.invoke("run_command", commandJson(benignCommand)).isError)
            assertTrue(
                core.approvalBus.pendingList.value
                    .isEmpty(),
            )
            assertEquals(1, trustedCalls)

            // The same trust still re-asks on critical arguments - the re-ask carries the
            // provider the trust belongs to, and the denial executes nothing.
            val call = async { core.invoke("run_command", commandJson(criticalCommand)) }
            val request =
                core.approvalBus.pendingList
                    .first { it.isNotEmpty() }
                    .first()
            assertEquals("terminal-tab", request.providerId)
            core.approvalBus.deny(request.id)
            assertTrue(call.await().isError)
            assertEquals(1, trustedCalls)

            // A second provider's same-named tool never inherits the trust (#815): the
            // registry drops the duplicate while the first provider is registered, so the
            // squatter gets the name only once the trusted provider is gone - and even then
            // it asks, trusted or not.
            core.unregisterProvider("terminal-tab")
            core.registerProvider(
                provider(
                    "squatter-tab",
                    tool("run_command") { squatterCalls++ },
                ),
            )
            val squatter = async { core.invoke("run_command", commandJson(benignCommand)) }
            val squatterRequest =
                core.approvalBus.pendingList
                    .first { it.isNotEmpty() }
                    .first()
            assertEquals("squatter-tab", squatterRequest.providerId)
            core.approvalBus.deny(squatterRequest.id)
            assertTrue(squatter.await().isError)
            assertEquals(0, squatterCalls)
            assertEquals(
                McpPolicyAction.ALLOW,
                engine.policyFor("run_command", "terminal-tab"),
                "the denial must not have revoked the provider-scoped trust",
            )
        }

    @Test
    fun `tools without a standing allow keep their ordinary behavior`() =
        runBlocking {
            var calls = 0
            val engine = McpPolicyEngine(policyFile = null)
            val core = newCore(engine)
            core.registerProvider(
                provider(
                    "terminal-tab",
                    tool("run_command") { calls++ },
                    tool("codebase_write") { calls++ },
                    tool("codebase_read") { calls++ },
                ),
            )

            // run_command with no rule: the mutating default ASK, whatever the arguments say.
            val ask = async { core.invoke("run_command", commandJson(criticalCommand)) }
            val request =
                core.approvalBus.pendingList
                    .first { it.isNotEmpty() }
                    .first()
            assertEquals(McpRiskLevel.CRITICAL, request.riskAssessment?.level)
            core.approvalBus.deny(request.id)
            assertTrue(ask.await().isError)
            assertEquals(0, calls)

            // DENY is unchanged: rejected by policy without any prompt or execution.
            assertTrue(engine.setToolPolicy("codebase_write", McpPolicyAction.DENY))
            val denied = core.invoke("codebase_write", commandJson(criticalCommand))
            assertTrue(denied.isError)
            assertTrue(denied.text.contains("rejected by policy"))
            assertTrue(
                core.approvalBus.pendingList.value
                    .isEmpty(),
            )

            // The read-only default stays quiet even when the arguments look scary.
            val read = core.invoke("codebase_read", commandJson(criticalCommand))
            assertFalse(read.isError)
            assertEquals(1, calls)
        }

    @Test
    fun `denying the critical re-ask never executes and keeps the standing allow explicit`() =
        runBlocking {
            var calls = 0
            val engine = McpPolicyEngine(policyFile = null)
            assertTrue(engine.setToolPolicy("run_command", McpPolicyAction.ALLOW))
            val core = newCore(engine)
            core.registerProvider(
                provider(
                    "terminal-tab",
                    tool("run_command") { calls++ },
                ),
            )

            val first = async { core.invoke("run_command", commandJson(criticalCommand)) }
            val request =
                core.approvalBus.pendingList
                    .first { it.isNotEmpty() }
                    .first()
            core.approvalBus.deny(request.id, "not this argument set")
            val denied = first.await()
            assertTrue(denied.isError)
            assertTrue(denied.text.contains("rejected by operator"))
            assertEquals(0, calls)

            // The denial was about THIS call: the operator's standing ALLOW survives, and
            // the very next attempt re-asks instead of executing unattended.
            val second = async { core.invoke("run_command", commandJson(criticalCommand)) }
            val reask =
                core.approvalBus.pendingList
                    .first { it.isNotEmpty() }
                    .first()
            core.approvalBus.deny(reask.id)
            assertTrue(second.await().isError)
            assertEquals(0, calls)
            assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command"))
        }
}
