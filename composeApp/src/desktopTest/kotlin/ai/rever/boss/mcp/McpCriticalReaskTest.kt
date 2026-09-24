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
 * A CRITICAL rating the invocation's ARGUMENTS raised - the bare tool rates lower - must
 * re-ask on every ALLOW path, a persisted tool rule, provider trust, session trust, instead
 * of executing unattended, while non-CRITICAL argument sets keep running quietly under the
 * same grants. Tools rated CRITICAL whatever their arguments carry - `secret_get`,
 * `docker_rm`, the k8s/helm sets - keep their standing ALLOWs exactly as the policy dialogs
 * describe them: the operator granted the ALLOW against the baseline the dialog itself
 * displays. Provider scoping (#815) is untouched: the re-ask carries the tool's provider
 * like any other prompt, and a denial neither widens, inherits nor resets anyone's trust.
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
        // Catalog tools whose CRITICAL is argument-independent never re-ask: the bare name
        // already rates CRITICAL, so the operator's standing ALLOW was granted against
        // exactly that baseline and keeps governing every call, arguments or not.
        assertFalse(
            requiresCriticalReask(
                "secret_get",
                declaredReadOnly = null,
                args = McpToolArgs(emptyMap(), "{}"),
            ),
        )
        assertFalse(
            requiresCriticalReask(
                "docker_rm",
                declaredReadOnly = null,
                args = McpToolArgs(mapOf("container" to "895-stale-cache", "force" to "true"), "{}"),
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

            // The ledger records the policy that actually governed the call - the escalated
            // ASK, not the pre-escalation grant - alongside the one-shot approval, so the
            // re-ask is auditable, not a silent policy override.
            assertEquals(
                McpPolicyAction.ASK,
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
    fun `an argument-independent critical tool keeps its standing allow that argument-raised tools lose`() =
        runBlocking {
            var dockerCalls = 0
            var shellCalls = 0
            val engine = McpPolicyEngine(policyFile = null)
            assertTrue(engine.setToolPolicy("docker_rm", McpPolicyAction.ALLOW))
            assertTrue(engine.setToolPolicy("run_command", McpPolicyAction.ALLOW))
            val core = newCore(engine)
            core.registerProvider(
                provider(
                    "infra-tab",
                    tool("docker_rm") { dockerCalls++ },
                    tool("run_command") { shellCalls++ },
                ),
            )

            // docker_rm rates CRITICAL on the bare name - the exact baseline the Always
            // Allow dialog displays - so the standing ALLOW governs: the call runs
            // unattended and no prompt ever queues, whatever the arguments carry.
            val removed = core.invoke("docker_rm", """{"container":"895-stale-cache","force":true}""")
            assertFalse(removed.isError)
            assertEquals(1, dockerCalls)
            assertTrue(
                core.approvalBus.pendingList.value
                    .isEmpty(),
            )

            // run_command rates HIGH bare; only these arguments raise it to CRITICAL, so
            // the same shape of grant re-asks and a denial executes nothing.
            val shell = async { core.invoke("run_command", commandJson(criticalCommand)) }
            val request =
                core.approvalBus.pendingList
                    .first { it.isNotEmpty() }
                    .first()
            assertEquals(McpRiskLevel.CRITICAL, request.riskAssessment?.level)
            core.approvalBus.deny(request.id)
            assertTrue(shell.await().isError)
            assertEquals(0, shellCalls)

            // The ledger tells the two apart: the re-asked call is recorded under the
            // escalated ASK that made it prompt, the unattended one under the ALLOW the
            // operator actually saved - the post-escalation policy in both cases.
            val operations = core.ledger.recentOperations.value
            assertEquals(McpPolicyAction.ASK, operations.first().policyApplied)
            assertEquals(
                McpApprovalDisposition.DENIED_BY_OPERATOR,
                operations.first().approvalDisposition,
            )
            assertEquals(McpPolicyAction.ALLOW, operations.last().policyApplied)
            assertEquals(
                McpApprovalDisposition.AUTO_ALLOWED,
                operations.last().approvalDisposition,
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
