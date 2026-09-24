package ai.rever.boss.plugin.packs

import ai.rever.boss.mcp.McpApprovalBus
import ai.rever.boss.mcp.McpApprovalDisposition
import ai.rever.boss.mcp.McpArgumentSanitizer
import ai.rever.boss.mcp.McpMutatingToolCatalog
import ai.rever.boss.mcp.McpOperationLedger
import ai.rever.boss.mcp.McpPolicyAction
import ai.rever.boss.mcp.McpPolicyEngine
import ai.rever.boss.mcp.McpToolRegistryCore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pack tools through the real MCP registry, policy engine and approval bus.
 *
 * This is the claim the design rests on: a pack is applied inside governance, not beside it. The
 * operator must be asked before `pack_apply` does anything, must see the pack itself when asked, and
 * must be able to refuse it, while planning stays free to call.
 */
class PluginPackGovernanceTest {
    private val published = StoreListing.Published(latest = "2.0.0", versions = setOf("2.0.0"))
    private val packArgs =
        """{"pack":"team","plugins":["com.example.terminal","com.example.codebase@2.0.0?"],""" +
            """"allow_tools":["run_tests"]}"""

    private class Harness(
        val effects: FakePackEffects,
    ) {
        val bus = McpApprovalBus(defaultTimeoutMs = 10_000L)
        val ledger = McpOperationLedger(ledgerFile = null)
        val policy = McpPolicyEngine(policyFile = null)
        val jobs = PluginPackJobs(PluginPackApplier(effects))
        val core =
            McpToolRegistryCore(disabledFile = null, policyEngine = policy, approvalBus = bus, ledger = ledger).also {
                it.registerProvider(PluginPackMcpToolProvider(effects, jobs))
            }
    }

    private fun harness() =
        Harness(
            FakePackEffects(
                store = mutableMapOf("com.example.terminal" to published, "com.example.codebase" to published),
            ),
        )

    @Test
    fun `pack_apply is classified mutating by both signals, and the read tools are not`() {
        val effects = FakePackEffects()
        val tools = PluginPackMcpToolProvider(effects, PluginPackJobs(PluginPackApplier(effects))).tools()
        val byName = tools.associateBy { it.name }

        assertEquals(PluginPackParser.PACK_TOOL_NAMES, byName.keys)
        assertFalse(byName.getValue("pack_apply").readOnly)
        assertTrue(McpMutatingToolCatalog.isMutating("pack_apply"), "the name alone must classify it")
        assertFalse(McpMutatingToolCatalog.isMutating("pack_plan", byName.getValue("pack_plan").readOnly))
        assertFalse(McpMutatingToolCatalog.isMutating("pack_status", byName.getValue("pack_status").readOnly))
    }

    @Test
    fun `pack_apply waits for the operator, who is shown every plugin and rule, and nothing runs before approval`() =
        runBlocking<Unit> {
            val h = harness()

            val call = async { h.core.invoke("pack_apply", packArgs) }
            val request =
                withTimeout(5_000) {
                    h.bus.pendingList
                        .first { it.isNotEmpty() }
                        .first()
                }

            assertEquals("pack_apply", request.toolName)
            assertEquals(PluginPackParser.PACK_PROVIDER_ID, request.providerId)
            val shown = McpArgumentSanitizer.sanitize(request.arguments)
            assertTrue(shown.getValue("plugins").contains("com.example.terminal"), shown.toString())
            assertTrue(shown.getValue("plugins").contains("com.example.codebase@2.0.0?"), shown.toString())
            assertTrue(shown.getValue("allow_tools").contains("run_tests"), shown.toString())
            assertTrue(h.effects.calls.isEmpty(), "nothing may be installed while approval is pending")
            assertEquals(null, h.jobs.status(null), "no job may start while approval is pending")

            h.bus.approve(request.id, trustForSession = false)
            val started = Json.parseToJsonElement(call.await().text) as JsonObject
            val finished = awaitJob(h.jobs, started.getValue("job").jsonPrimitive.content)

            assertEquals(PackApplyStatus.APPLIED, finished.result?.status)
            assertEquals(
                listOf(
                    "install com.example.terminal 2.0.0 latest=true",
                    "install com.example.codebase 2.0.0 latest=true",
                    "addRule TOOL run_tests ALLOW",
                ),
                h.effects.calls,
            )
        }

    @Test
    fun `an operator who denies pack_apply leaves everything untouched`() =
        runBlocking<Unit> {
            val h = harness()

            val call = async { h.core.invoke("pack_apply", packArgs) }
            val request =
                withTimeout(5_000) {
                    h.bus.pendingList
                        .first { it.isNotEmpty() }
                        .first()
                }
            h.bus.deny(request.id, "not this pack")

            assertTrue(call.await().isError)
            delay(100)
            assertTrue(h.effects.calls.isEmpty())
            assertEquals(null, h.jobs.status(null))
            assertEquals(
                McpApprovalDisposition.DENIED_BY_OPERATOR,
                h.ledger.recentOperations.value
                    .first()
                    .approvalDisposition,
            )
        }

    @Test
    fun `an operator DENY on pack_apply refuses it without asking`() =
        runBlocking<Unit> {
            val h = harness()
            h.policy.setToolPolicy("pack_apply", McpPolicyAction.DENY)

            val result = h.core.invoke("pack_apply", packArgs)

            assertTrue(result.isError)
            assertTrue(
                h.bus.pendingList.value
                    .isEmpty(),
            )
            assertTrue(h.effects.calls.isEmpty())
        }

    @Test
    fun `pack_plan runs without approval and changes nothing`() =
        runBlocking<Unit> {
            val h = harness()

            val result = withTimeout(5_000) { h.core.invoke("pack_plan", packArgs) }

            assertFalse(result.isError, result.text)
            val plan = Json.parseToJsonElement(result.text) as JsonObject
            assertEquals(JsonPrimitive("team"), plan["pack"])
            assertTrue(h.effects.calls.isEmpty())
            assertTrue(
                h.bus.pendingList.value
                    .isEmpty(),
            )
        }

    @Test
    fun `an invalid pack is refused with every problem and starts no job`() =
        runBlocking<Unit> {
            val h = harness()
            h.policy.setToolPolicy("pack_apply", McpPolicyAction.ALLOW)

            val result = h.core.invoke("pack_apply", """{"pack":"team","allow_tools":["pack_apply"],"extra":true}""")

            assertTrue(result.isError)
            assertTrue("invalid_pack" in result.text, result.text)
            assertTrue("pack tools themselves" in result.text, result.text)
            assertTrue("extra" in result.text, result.text)
            assertEquals(null, h.jobs.status(null))
        }

    @Test
    fun `a second apply while one runs is refused with the running job's id`() =
        runBlocking<Unit> {
            val gate = CompletableDeferred<Unit>()
            val h = harness()
            h.policy.setToolPolicy("pack_apply", McpPolicyAction.ALLOW)
            h.effects.beforeSnapshot = { gate.await() }

            val first = Json.parseToJsonElement(h.core.invoke("pack_apply", packArgs).text) as JsonObject
            val second = h.core.invoke("pack_apply", packArgs)
            gate.complete(Unit)

            val runningId = first.getValue("job").jsonPrimitive.content
            assertTrue(second.isError)
            assertTrue(runningId in second.text, second.text)
            awaitJob(h.jobs, runningId)
        }

    private suspend fun awaitJob(
        jobs: PluginPackJobs,
        id: String,
    ): PackJob =
        withTimeout(10_000) {
            while (true) {
                val job = checkNotNull(jobs.status(id))
                if (job.state != PackJobState.RUNNING) return@withTimeout job
                delay(20)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
}
