package ai.rever.boss.plugin.packs

import ai.rever.boss.mcp.ApprovedArtifact
import ai.rever.boss.mcp.McpApprovalBus
import ai.rever.boss.mcp.McpApprovalDisposition
import ai.rever.boss.mcp.McpArgumentSanitizer
import ai.rever.boss.mcp.McpMutatingToolCatalog
import ai.rever.boss.mcp.McpOperationLedger
import ai.rever.boss.mcp.McpPolicyAction
import ai.rever.boss.mcp.McpPolicyEngine
import ai.rever.boss.mcp.McpToolRegistryCore
import ai.rever.boss.mcp.PreparedPackDisplayModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
            val display = request.displayModel as? PreparedPackDisplayModel
            assertNotNull(display)
            assertEquals("team", display.packId)
            assertFalse(request.allowStandingTrust, "Standing trust must not be allowed for pack_apply")
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
            h.effects.beforeInstall = { gate.await() }

            val firstCall = async { h.core.invoke("pack_apply", packArgs) }
            val request =
                withTimeout(5_000) {
                    h.bus.pendingList
                        .first { it.isNotEmpty() }
                        .first()
                }
            h.bus.approve(request.id, trustForSession = false)
            val first = Json.parseToJsonElement(firstCall.await().text) as JsonObject
            val runningId = first.getValue("job").jsonPrimitive.content

            val second = h.core.invoke("pack_apply", packArgs)
            gate.complete(Unit)

            assertTrue(second.isError)
            assertTrue(runningId in second.text, second.text)
            awaitJob(h.jobs, runningId)
        }

    private fun planChangeFixture(): FakePackEffects {
        val gatewayListing = StoreListing.Published(latest = "1.0.0", versions = setOf("1.0.0"), latestSha256 = "sha-gateway-1.0")
        val editorListing = StoreListing.Published(latest = "2.0.0", versions = setOf("2.0.0"), latestSha256 = "sha-editor-2.0")
        val telemetryListing = StoreListing.Published(latest = "1.0.0", versions = setOf("1.0.0"), latestSha256 = "sha-telemetry-1.0")

        val fakeEffects =
            FakePackEffects(
                store =
                    mutableMapOf(
                        "ai.rever.boss.gateway" to gatewayListing,
                        "ai.rever.boss.editor" to editorListing,
                        "ai.rever.boss.telemetry" to telemetryListing,
                    ),
            )
        fakeEffects.closures["ai.rever.boss.gateway"] =
            InstallClosure(
                order = listOf("ai.rever.boss.gateway"),
                alsoInstalls = emptyList(),
                unresolved = emptySet(),
                cyclic = false,
                truncated = false,
                artifacts = listOf(ApprovedArtifact("ai.rever.boss.gateway", "1.0.0", "sha-gateway-1.0")),
            )
        fakeEffects.closures["ai.rever.boss.editor"] =
            InstallClosure(
                order = listOf("ai.rever.boss.editor"),
                alsoInstalls = emptyList(),
                unresolved = emptySet(),
                cyclic = false,
                truncated = false,
                artifacts = listOf(ApprovedArtifact("ai.rever.boss.editor", "2.0.0", "sha-editor-2.0")),
            )
        return fakeEffects
    }

    @Test
    fun `plan change before execution starts reports plan_changed and performs zero mutations`() =
        runBlocking<Unit> {
            val fakeEffects = planChangeFixture()
            val h = Harness(fakeEffects)
            val devPackArgs = """{"pack":"dev","plugins":["ai.rever.boss.gateway@1.0.0","ai.rever.boss.editor@2.0.0"]}"""

            val call = async { h.core.invoke("pack_apply", devPackArgs) }
            val request =
                withTimeout(5_000) {
                    h.bus.pendingList
                        .first { it.isNotEmpty() }
                        .first()
                }

            val display = request.displayModel as? PreparedPackDisplayModel
            assertNotNull(display)
            assertEquals("dev", display.packId)
            assertEquals(2, display.plugins.size)

            // Before approval completes, change store metadata: gateway now requires telemetry!
            fakeEffects.closures["ai.rever.boss.gateway"] =
                InstallClosure(
                    order = listOf("ai.rever.boss.telemetry", "ai.rever.boss.gateway"),
                    alsoInstalls = listOf("ai.rever.boss.telemetry"),
                    unresolved = emptySet(),
                    cyclic = false,
                    truncated = false,
                    artifacts =
                        listOf(
                            ApprovedArtifact("ai.rever.boss.telemetry", "1.0.0", "sha-telemetry-1.0"),
                            ApprovedArtifact("ai.rever.boss.gateway", "1.0.0", "sha-gateway-1.0"),
                        ),
                )

            h.bus.approve(request.id, trustForSession = false)
            val started = Json.parseToJsonElement(call.await().text) as JsonObject
            val jobId = started.getValue("job").jsonPrimitive.content
            val finished = awaitJob(h.jobs, jobId)

            assertEquals(PackApplyStatus.PLAN_CHANGED, finished.result?.status)
            assertTrue(h.effects.calls.isEmpty(), "Zero installs and rule writes must occur when plan changed")
            assertEquals(
                "The pack plan changed before execution started. Apply again for a new preview.",
                finished.error,
            )
        }

    @Test
    fun `changed store hash before execution starts triggers plan_changed and zero mutations`() =
        runBlocking<Unit> {
            val h = harness()
            val call = async { h.core.invoke("pack_apply", packArgs) }
            val request =
                withTimeout(5_000) {
                    h.bus.pendingList
                        .first { it.isNotEmpty() }
                        .first()
                }

            // Change store hash while approval is pending
            h.effects.store["com.example.terminal"] =
                StoreListing.Published(
                    latest = "2.0.0",
                    versions = setOf("2.0.0"),
                    latestSha256 = "sha-tampered-after-approval",
                )

            h.bus.approve(request.id, trustForSession = false)
            val started = Json.parseToJsonElement(call.await().text) as JsonObject
            val finished = awaitJob(h.jobs, started.getValue("job").jsonPrimitive.content)

            assertEquals(PackApplyStatus.PLAN_CHANGED, finished.result?.status)
            assertTrue(h.effects.calls.isEmpty(), "No install calls when hash changed")
            assertEquals(
                "The pack plan changed before execution started. Apply again for a new preview.",
                finished.error,
            )
        }

    @Test
    fun `pack_apply rejects cyclic dependency closure before approval without mutations`() =
        runBlocking<Unit> {
            val h = harness()
            h.effects.closures["com.example.terminal"] =
                InstallClosure(
                    order = listOf("com.example.terminal"),
                    alsoInstalls = emptyList(),
                    unresolved = emptySet(),
                    cyclic = true,
                    truncated = false,
                )

            val result = h.core.invoke("pack_apply", packArgs)
            assertTrue(result.isError)
            assertTrue("dependency cycle detected" in result.text, result.text)
            assertTrue(
                h.bus.pendingList.value
                    .isEmpty(),
                "Cyclic closure must not request approval",
            )
            assertTrue(h.effects.calls.isEmpty())
            assertEquals(null, h.jobs.status(null))
        }

    @Test
    fun `pack_apply rejects truncated dependency closure before approval without mutations`() =
        runBlocking<Unit> {
            val h = harness()
            h.effects.closures["com.example.terminal"] =
                InstallClosure(
                    order = listOf("com.example.terminal"),
                    alsoInstalls = emptyList(),
                    unresolved = emptySet(),
                    cyclic = false,
                    truncated = true,
                )

            val result = h.core.invoke("pack_apply", packArgs)
            assertTrue(result.isError)
            assertTrue("was truncated" in result.text, result.text)
            assertTrue(
                h.bus.pendingList.value
                    .isEmpty(),
            )
            assertTrue(h.effects.calls.isEmpty())
            assertEquals(null, h.jobs.status(null))
        }

    @Test
    fun `pack_apply rejects unresolved dependencies before approval without mutations`() =
        runBlocking<Unit> {
            val h = harness()
            h.effects.closures["com.example.terminal"] =
                InstallClosure(
                    order = listOf("com.example.terminal"),
                    alsoInstalls = emptyList(),
                    unresolved = setOf("missing.plugin.dep"),
                    cyclic = false,
                    truncated = false,
                )

            val result = h.core.invoke("pack_apply", packArgs)
            assertTrue(result.isError)
            assertTrue("unresolved dependencies" in result.text, result.text)
            assertTrue("missing.plugin.dep" in result.text, result.text)
            assertTrue(
                h.bus.pendingList.value
                    .isEmpty(),
            )
            assertTrue(h.effects.calls.isEmpty())
            assertEquals(null, h.jobs.status(null))
        }

    @Test
    fun `pack_apply rejects closures too large to display fully`() =
        runBlocking<Unit> {
            val h = harness()
            val largeOrder = (1..26).map { "dep$it" }
            h.effects.closures["com.example.terminal"] =
                InstallClosure(
                    order = largeOrder,
                    alsoInstalls = largeOrder.dropLast(1),
                    unresolved = emptySet(),
                    cyclic = false,
                    truncated = false,
                )

            val result = h.core.invoke("pack_apply", packArgs)
            assertTrue(result.isError)
            assertTrue("too large to display" in result.text, result.text)
            assertTrue(
                h.bus.pendingList.value
                    .isEmpty(),
            )
            assertTrue(h.effects.calls.isEmpty())
            assertEquals(null, h.jobs.status(null))
        }

    @Test
    fun `a standing ALLOW on pack_apply does not bypass operator approval`() =
        runBlocking<Unit> {
            val h = harness()
            h.policy.setToolPolicy("pack_apply", McpPolicyAction.ALLOW)

            val call = async { h.core.invoke("pack_apply", packArgs) }
            val request =
                withTimeout(5_000) {
                    h.bus.pendingList
                        .first { it.isNotEmpty() }
                        .first()
                }

            assertEquals("pack_apply", request.toolName)
            assertFalse(request.allowStandingTrust, "Standing trust must not be allowed for pack_apply")
            assertTrue(h.effects.calls.isEmpty(), "Nothing may run before fresh approval even with standing ALLOW")

            h.bus.approve(request.id, trustForSession = true, persistPolicy = true)
            val started = Json.parseToJsonElement(call.await().text) as JsonObject
            val finished = awaitJob(h.jobs, started.getValue("job").jsonPrimitive.content)

            assertEquals(PackApplyStatus.APPLIED, finished.result?.status)
            assertEquals(3, h.effects.calls.size)
        }

    @Test
    fun `approval timeout for pack_apply starts no job and makes zero mutations`() =
        runBlocking<Unit> {
            val effects =
                FakePackEffects(
                    store = mutableMapOf("com.example.terminal" to published, "com.example.codebase" to published),
                )
            val bus = McpApprovalBus(defaultTimeoutMs = 50L)
            val ledger = McpOperationLedger(ledgerFile = null)
            val policy = McpPolicyEngine(policyFile = null)
            val jobs = PluginPackJobs(PluginPackApplier(effects))
            val core =
                McpToolRegistryCore(disabledFile = null, policyEngine = policy, approvalBus = bus, ledger = ledger).also {
                    it.registerProvider(PluginPackMcpToolProvider(effects, jobs))
                }

            val result = core.invoke("pack_apply", packArgs)
            assertTrue(result.isError)
            assertTrue("timeout" in result.text.lowercase() || "timed out" in result.text.lowercase(), result.text)
            assertTrue(effects.calls.isEmpty(), "Zero mutations on timeout")
            assertEquals(null, jobs.status(null))
        }

    @Test
    fun `cancellation while approval is pending leaves no job and makes zero mutations`() =
        runBlocking<Unit> {
            val h = harness()
            val callJob = launch { h.core.invoke("pack_apply", packArgs) }
            withTimeout(5_000) {
                h.bus.pendingList
                    .first { it.isNotEmpty() }
                    .first()
            }

            callJob.cancel()
            delay(50)

            assertTrue(h.effects.calls.isEmpty(), "No installs on cancellation")
            assertEquals(null, h.jobs.status(null), "No job created on cancellation")
        }

    @Test
    fun `cancellation during preparation leaves no job and makes zero mutations`() =
        runBlocking<Unit> {
            val gate = CompletableDeferred<Unit>()
            val h = harness()
            h.effects.beforeSnapshot = { gate.await() }

            val callJob = launch { h.core.invoke("pack_apply", packArgs) }
            delay(50)
            callJob.cancel()
            gate.complete(Unit)

            delay(50)
            assertTrue(h.effects.calls.isEmpty())
            assertEquals(null, h.jobs.status(null))
        }

    @Test
    fun `detached pack job continues running and reports status via pack_status after invocation completes`() =
        runBlocking<Unit> {
            val gate = CompletableDeferred<Unit>()
            val h = harness()
            h.effects.beforeInstall = { gate.await() }

            val call = async { h.core.invoke("pack_apply", packArgs) }
            val request =
                withTimeout(5_000) {
                    h.bus.pendingList
                        .first { it.isNotEmpty() }
                        .first()
                }
            h.bus.approve(request.id, trustForSession = false)
            val started = Json.parseToJsonElement(call.await().text) as JsonObject
            val jobId = started.getValue("job").jsonPrimitive.content

            val runningStatus = Json.parseToJsonElement(h.core.invoke("pack_status", """{"job":"$jobId"}""").text) as JsonObject
            assertEquals(JsonPrimitive("running"), runningStatus["state"])

            gate.complete(Unit)
            val finished = awaitJob(h.jobs, jobId)
            assertEquals(PackJobState.FINISHED, finished.state)
            assertEquals(PackApplyStatus.APPLIED, finished.result?.status)

            val finishedStatus = Json.parseToJsonElement(h.core.invoke("pack_status", """{"job":"$jobId"}""").text) as JsonObject
            assertEquals(JsonPrimitive("finished"), finishedStatus["state"])
            assertEquals(JsonPrimitive("applied"), finishedStatus["status"])
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
