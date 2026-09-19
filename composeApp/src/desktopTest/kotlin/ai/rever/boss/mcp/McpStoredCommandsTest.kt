package ai.rever.boss.mcp

import ai.rever.boss.mcp.sandbox.McpRiskLevel
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The [McpStoredCommandSource] seam at the registry boundary, with a fake source: what the
 * operator is shown, what forces a prompt, what the handler receives, and what the agent cannot
 * do. The one real source, `WorkspaceMcpToolProvider`, is tested against real Space files in
 * `WorkspaceMcpToolProviderTest`.
 */
class McpStoredCommandsTest {
    /** A provider whose `apply` tool would run [commands], and which remembers what it received. */
    private class StoredProvider(
        private val commands: () -> List<String>,
    ) : McpToolProvider,
        McpStoredCommandSource {
        override val providerId = "stored"
        var received: McpToolArgs? = null
        var asked = 0

        override fun tools() =
            listOf(
                McpToolDefinition(name = "apply", description = "apply a saved thing") { args ->
                    received = args
                    McpToolResult("applied")
                },
            )

        override suspend fun storedCommandsFor(
            toolName: String,
            args: McpToolArgs,
        ): List<String> {
            asked += 1
            return commands()
        }
    }

    private class Harness(
        commands: () -> List<String>,
    ) {
        val policyEngine = McpPolicyEngine(policyFile = null)
        val approvalBus = McpApprovalBus(defaultTimeoutMs = 5_000L)
        val ledger = McpOperationLedger(ledgerFile = null)
        val core =
            McpToolRegistryCore(
                disabledFile = null,
                policyEngine = policyEngine,
                approvalBus = approvalBus,
                ledger = ledger,
            )
        val provider = StoredProvider(commands)
        val seen = mutableListOf<McpApprovalRequest>()

        init {
            core.registerProvider(provider)
        }

        fun CoroutineScope.operator(
            approve: Boolean = true,
            trustForSession: Boolean = false,
            persistPolicy: Boolean = false,
            trustProvider: Boolean = false,
        ): Job =
            launch {
                while (true) {
                    val req = approvalBus.pendingList.first { it.isNotEmpty() }.first()
                    seen.add(req)
                    if (approve) {
                        approvalBus.approve(req.id, trustForSession, persistPolicy, trustProvider)
                    } else {
                        approvalBus.deny(req.id, "no")
                    }
                    approvalBus.pendingList.first { list -> list.none { it.id == req.id } }
                }
            }
    }

    @Test
    fun `a tool ALLOW rule does not cover stored commands, the operator is asked and shown them`() =
        runBlocking {
            val h = Harness { listOf("echo one", "echo two") }
            h.policyEngine.setToolPolicy("apply", McpPolicyAction.ALLOW)
            val op = with(h) { operator() }
            val result = h.core.invoke("apply", """{"id":"x"}""")
            op.cancel()
            assertFalse(result.isError, result.text)
            assertEquals(listOf(listOf("echo one", "echo two")), h.seen.map { it.storedCommands })
            assertEquals(listOf("echo one", "echo two"), h.provider.received?.approvedStoredCommands())
            val record =
                h.ledger.recentOperations.value
                    .single()
            assertEquals(McpPolicyAction.ASK, record.policyApplied)
            assertEquals(McpApprovalDisposition.APPROVED_ONCE, record.approvalDisposition)
            assertEquals("[echo one, echo two]", record.sanitizedArgs[APPROVED_STORED_COMMANDS_KEY])
        }

    @Test
    fun `session trust for the tool does not cover stored commands either`() =
        runBlocking {
            val h = Harness { listOf("echo one") }
            // Earn session trust on a call with no stored commands... there are always commands
            // here, so grant it on the tool directly through an approval and check the next call asks again.
            val op = with(h) { operator(trustForSession = true) }
            h.core.invoke("apply", """{"id":"x"}""")
            h.core.invoke("apply", """{"id":"x"}""")
            op.cancel()
            assertEquals(2, h.seen.size, "the second call asked again despite session trust")
        }

    @Test
    fun `a call with no stored commands runs under its tool rule and receives no approval key`() =
        runBlocking {
            val h = Harness { emptyList() }
            h.policyEngine.setToolPolicy("apply", McpPolicyAction.ALLOW)
            val result = h.core.invoke("apply", """{"id":"x"}""")
            assertFalse(result.isError)
            assertTrue(h.seen.isEmpty())
            assertNull(h.provider.received?.approvedStoredCommands())
            assertNull(
                h.ledger.recentOperations.value
                    .single()
                    .sanitizedArgs[APPROVED_STORED_COMMANDS_KEY],
            )
        }

    @Test
    fun `an approval key the agent sends is dropped before the handler sees it`() =
        runBlocking {
            val h = Harness { emptyList() }
            h.policyEngine.setToolPolicy("apply", McpPolicyAction.ALLOW)
            h.core.invoke("apply", """{"id":"x","approvedStartupCommands":["rm -rf /"]}""")
            val received = h.provider.received!!
            assertNull(received.approvedStoredCommands())
            assertFalse(received.raw.contains("rm -rf"), received.raw)
            assertEquals("x", received.string("id"))
        }

    @Test
    fun `durable answers to a stored-command prompt are taken as one approval`() =
        runBlocking {
            val h = Harness { listOf("echo one") }
            val op = with(h) { operator(persistPolicy = true, trustProvider = true) }
            val result = h.core.invoke("apply", """{"id":"x"}""")
            op.cancel()
            assertFalse(result.isError, result.text)
            val record =
                h.ledger.recentOperations.value
                    .single()
            assertEquals(McpApprovalDisposition.APPROVED_ONCE, record.approvalDisposition)
            // Nothing durable was written: no tool rule, no provider rule, no session trust.
            assertNull(h.policyEngine.config.value.rules["apply"])
            assertNull(h.policyEngine.config.value.providerRules["stored"])
            assertTrue(
                h.policyEngine.sessionTrustedTools.value
                    .isEmpty(),
            )
        }

    @Test
    fun `the risk of the call is the risk of the worst stored command`() =
        runBlocking {
            val h = Harness { listOf("echo fine", "rm -rf build") }
            val op = with(h) { operator() }
            h.core.invoke("apply", """{"id":"x"}""")
            op.cancel()
            val risk = h.seen.single().riskAssessment!!
            assertEquals(McpRiskLevel.CRITICAL, risk.level)
            assertTrue(risk.reason.contains("2 stored startup command"), risk.reason)
        }

    @Test
    fun `a source that cannot answer refuses the call before any prompt`() =
        runBlocking {
            val h = Harness { error("file unreadable") }
            h.policyEngine.setToolPolicy("apply", McpPolicyAction.ALLOW)
            val result = h.core.invoke("apply", """{"id":"x"}""")
            assertTrue(result.isError)
            assertTrue(result.text.contains("could not determine"), result.text)
            assertTrue(h.seen.isEmpty())
            assertNull(h.provider.received)
            val record =
                h.ledger.recentOperations.value
                    .single()
            assertEquals(McpApprovalDisposition.POLICY_DENIED, record.approvalDisposition)
        }

    @Test
    fun `more stored commands than can be shown are refused before any prompt`() =
        runBlocking {
            val h = Harness { List(MAX_STORED_COMMANDS_PER_CALL + 1) { "echo $it" } }
            val result = h.core.invoke("apply", """{"id":"x"}""")
            assertTrue(result.isError)
            assertTrue(result.text.contains("at most $MAX_STORED_COMMANDS_PER_CALL"), result.text)
            assertTrue(h.seen.isEmpty())
            assertNull(h.provider.received)
        }

    @Test
    fun `a DENY-ed tool never consults its source`() =
        runBlocking {
            val h = Harness { listOf("echo one") }
            h.policyEngine.setToolPolicy("apply", McpPolicyAction.DENY)
            val result = h.core.invoke("apply", """{"id":"x"}""")
            assertTrue(result.isError)
            assertEquals(0, h.provider.asked)
            assertTrue(h.seen.isEmpty())
        }

    @Test
    fun `a denied prompt runs nothing and records the commands it was shown`() =
        runBlocking {
            val h = Harness { listOf("echo one") }
            val op = with(h) { operator(approve = false) }
            val result = h.core.invoke("apply", """{"id":"x"}""")
            op.cancel()
            assertTrue(result.isError)
            assertNull(h.provider.received)
            val record =
                h.ledger.recentOperations.value
                    .single()
            assertEquals(McpApprovalDisposition.DENIED_BY_OPERATOR, record.approvalDisposition)
            assertEquals("[echo one]", record.sanitizedArgs[APPROVED_STORED_COMMANDS_KEY])
        }

    @Test
    fun `stored commands are sanitized before the operator sees them`() =
        runBlocking {
            val h = Harness { listOf("export TOKEN=hunter2secret && ./run") }
            val op = with(h) { operator() }
            h.core.invoke("apply", """{"id":"x"}""")
            op.cancel()
            val shown =
                h.seen
                    .single()
                    .storedCommands
                    .single()
            assertFalse(shown.contains("hunter2secret"), shown)
            assertTrue(shown.contains("./run"), shown)
            // The handler, which runs it, gets the real command.
            assertEquals(listOf("export TOKEN=hunter2secret && ./run"), h.provider.received?.approvedStoredCommands())
        }
}
