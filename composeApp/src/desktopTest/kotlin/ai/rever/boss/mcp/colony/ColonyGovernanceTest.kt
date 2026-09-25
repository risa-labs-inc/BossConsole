package ai.rever.boss.mcp.colony

import ai.rever.boss.mcp.McpApprovalBus
import ai.rever.boss.mcp.McpMutatingToolCatalog
import ai.rever.boss.mcp.McpOperationLedger
import ai.rever.boss.mcp.McpPolicyAction
import ai.rever.boss.mcp.McpPolicyEngine
import ai.rever.boss.mcp.McpToolRegistryCore
import ai.rever.boss.mcp.sandbox.DefaultMcpRiskEvaluator
import ai.rever.boss.mcp.sandbox.McpRiskLevel
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BOSS Colony at the governance boundary.
 *
 * Every case here drives the tools through a real [McpToolRegistryCore] with a real ledger file, so
 * what is being checked is not the handler in isolation but the whole governed path: policy decides,
 * the approval bus suspends, the handler runs, and the ledger records. The negotiation cases then
 * read the ledger *back off disk* and rebuild the thread from it, because "reconstructable as a
 * thread from the ledger" is only a claim about the ledger if the ledger is the only thing consulted.
 */
class ColonyGovernanceTest {
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
        // The provider is a singleton, so a test must not leave its live threads behind for the next.
        ColonyToolProvider.store.clear()
    }

    private fun tempLedgerFile(): File {
        val dir = createTempDirectory("colony-governance-test").toFile()
        tempDirs.add(dir)
        return File(dir, "mcp-calls.jsonl")
    }

    /** A registry over a real ledger file and an explicit null-file policy engine (hermetic). */
    private fun registry(
        ledger: McpOperationLedger,
        bus: McpApprovalBus,
    ): McpToolRegistryCore =
        McpToolRegistryCore(
            disabledFile = null,
            policyEngine = McpPolicyEngine(policyFile = null),
            approvalBus = bus,
            ledger = ledger,
        ).also { it.registerProvider(ColonyToolProvider) }

    /**
     * Invoke a colony tool and approve the prompt it raises.
     *
     * Colony tools are mutating, so each one defaults to ASK. A test that wants to reach a handler has
     * to go through the approval the feature is meant to require, which is the point of driving these
     * through the registry rather than calling the handler directly.
     */
    private suspend fun invokeApproved(
        core: McpToolRegistryCore,
        bus: McpApprovalBus,
        tool: String,
        arguments: String,
    ): McpToolResult =
        coroutineScope {
            val pending = async { core.invoke(tool, arguments) }
            val request = bus.pendingList.first { it.isNotEmpty() }.first()
            assertEquals(tool, request.toolName)
            assertTrue(bus.approve(request.id, trustForSession = false), "the approval must land")
            pending.await()
        }

    private fun field(
        response: String,
        name: String,
    ): String =
        Json
            .parseToJsonElement(response)
            .jsonObject
            .getValue(name)
            .jsonPrimitive.content

    private fun proposeJson() =
        buildJsonObject {
            put("threadId", "thread-1")
            put("fromWorktreeId", "worktree-1")
            put("toWorktreeId", "worktree-2")
            put("task", "add a Shape parser")
            put("scope", "src/shapes")
        }.toString()

    private fun acceptJson(
        proposalId: String,
        from: String,
        to: String,
    ) = buildJsonObject {
        put("threadId", "thread-1")
        put("proposalId", proposalId)
        put("fromWorktreeId", from)
        put("toWorktreeId", to)
    }.toString()

    private fun counterJson(proposalId: String) =
        buildJsonObject {
            put("threadId", "thread-1")
            put("proposalId", proposalId)
            put("fromWorktreeId", "worktree-2")
            put("toWorktreeId", "worktree-1")
            put("alternativeScope", "src/shapes/parser")
        }.toString()

    @Test
    fun `a propose, counter and accept thread is reconstructable from the ledger`() =
        runBlocking {
            val ledger = McpOperationLedger(ledgerFile = tempLedgerFile())
            val bus = McpApprovalBus(defaultTimeoutMs = 5_000L)
            val core = registry(ledger, bus)

            val propose = invokeApproved(core, bus, ColonyTools.PROPOSE, proposeJson())
            assertFalse(propose.isError, propose.text)
            assertEquals(ColonyLifecycle.PROPOSED.name, field(propose.text, "state"))

            val counter = invokeApproved(core, bus, ColonyTools.COUNTER, counterJson(field(propose.text, "messageId")))
            assertFalse(counter.isError, counter.text)
            assertEquals(ColonyLifecycle.COUNTERED.name, field(counter.text, "state"))

            val accept =
                invokeApproved(
                    core,
                    bus,
                    ColonyTools.ACCEPT,
                    acceptJson(field(counter.text, "messageId"), from = "worktree-1", to = "worktree-2"),
                )
            assertFalse(accept.isError, accept.text)
            assertEquals(ColonyLifecycle.ACCEPTED.name, field(accept.text, "state"))

            // Read the durable file back rather than the in-memory ring buffer, so the assertion is
            // about what survives the process that wrote it.
            val records = ledger.readEntries().map { it.record }
            assertEquals(3, records.size)
            assertTrue(records.all { it.colonyThreadId == "thread-1" }, "every call is attributed to the thread")
            assertTrue(records.all { it.colonyMessageId != null }, "every call names the message it created")
            assertFalse(records.any { it.isError })

            val threads = ColonyLedgerReconstruction.threads(records)
            assertEquals(1, threads.size)
            val thread = threads.single()
            assertEquals("thread-1", thread.threadId)
            assertEquals(
                listOf(ColonyPerformative.PROPOSE, ColonyPerformative.COUNTER, ColonyPerformative.ACCEPT),
                thread.messages.map { it.performative },
            )
            assertEquals(ColonyLifecycle.ACCEPTED, thread.state)
            assertEquals(
                listOf("worktree-1", "worktree-2", "worktree-1"),
                thread.messages.map { it.fromWorktreeId },
            )
            assertEquals("src/shapes/parser", thread.messages[1].alternativeScope)
            // The load-bearing one: the ledger rebuilds the same messages, in the same order, as the
            // live store negotiated - without the live store's help.
            assertEquals(
                ColonyToolProvider.store.threads().map { live -> live.messages.map { it.messageId } },
                threads.map { rebuilt -> rebuilt.messages.map { it.messageId } },
            )
        }

    @Test
    fun `a refused transition is audited but creates no message`() =
        runBlocking {
            val ledger = McpOperationLedger(ledgerFile = tempLedgerFile())
            val bus = McpApprovalBus(defaultTimeoutMs = 5_000L)
            val core = registry(ledger, bus)

            val propose = invokeApproved(core, bus, ColonyTools.PROPOSE, proposeJson())
            val proposalId = field(propose.text, "messageId")
            // The proposal came from worktree-1, so worktree-2 is the one that answers it.
            val accept =
                invokeApproved(
                    core,
                    bus,
                    ColonyTools.ACCEPT,
                    acceptJson(proposalId, from = "worktree-2", to = "worktree-1"),
                )
            assertFalse(accept.isError, accept.text)

            // The thread is accepted, and nothing answers an acceptance.
            val again =
                invokeApproved(
                    core,
                    bus,
                    ColonyTools.ACCEPT,
                    acceptJson(proposalId, from = "worktree-2", to = "worktree-1"),
                )
            assertTrue(again.isError)
            assertTrue(again.text.contains("Illegal accept"), again.text)

            val records = ledger.readEntries().map { it.record }
            assertEquals(3, records.size, "a refused call is still audited")
            assertTrue(records.all { it.colonyThreadId == "thread-1" })
            assertTrue(records.last().isError)
            assertEquals(
                listOf(ColonyPerformative.PROPOSE, ColonyPerformative.ACCEPT),
                ColonyLedgerReconstruction
                    .threads(records)
                    .single()
                    .messages
                    .map { it.performative },
                "the refused call created no message",
            )
        }

    @Test
    fun `colony handoff resolves as ASK by default`() {
        val engine = McpPolicyEngine(policyFile = null)
        assertEquals(
            McpPolicyAction.ASK,
            engine.policyFor(ColonyTools.HANDOFF, providerId = ColonyToolProvider.providerId, declaredReadOnly = false),
        )
    }

    @Test
    fun `every mutating colony tool defaults to ASK and the brain read does not`() {
        val engine = McpPolicyEngine(policyFile = null)
        ColonyTools.all.forEach { tool ->
            val isRead = tool == ColonyTools.BRAIN_READ
            assertEquals(
                if (isRead) McpPolicyAction.ALLOW else McpPolicyAction.ASK,
                engine.policyFor(tool, providerId = ColonyToolProvider.providerId, declaredReadOnly = isRead),
                tool,
            )
        }
    }

    @Test
    fun `the colony tools the catalog governs are exactly the ones that mutate`() {
        assertEquals(
            ColonyTools.all - ColonyTools.BRAIN_READ,
            McpMutatingToolCatalog.KNOWN_MUTATING_TOOLS.filter { it.startsWith("colony_") }.toSet(),
        )

        // The name signal has to stand on its own: a tool that claimed `readOnly = true` must not be
        // upgraded out of the catalog, which is the fail-closed half of the mutating gate.
        assertTrue(
            ColonyTools.negotiating.all { McpMutatingToolCatalog.isMutating(it, declaredReadOnly = true) },
            "a dishonest readOnly claim must not upgrade a negotiation tool",
        )
        assertTrue(McpMutatingToolCatalog.isMutating(ColonyTools.BRAIN_WRITE, declaredReadOnly = true))
        assertFalse(McpMutatingToolCatalog.isMutating(ColonyTools.BRAIN_READ, declaredReadOnly = true))

        val evaluator = DefaultMcpRiskEvaluator()
        ColonyTools.all.forEach { tool ->
            val level = evaluator.evaluateRisk(tool, McpToolArgs(emptyMap())).level
            assertEquals(
                if (tool == ColonyTools.BRAIN_READ) McpRiskLevel.LOW else McpRiskLevel.HIGH,
                level,
                tool,
            )
        }
    }

    @Test
    fun `registering the colony provider leaves an existing provider's tools resolvable`() {
        val existing =
            object : McpToolProvider {
                override val providerId = "test-existing"

                override fun tools() =
                    listOf(
                        McpToolDefinition(
                            name = "test_existing_tool",
                            description = "an unrelated tool",
                            handler = McpToolHandler { McpToolResult("ok") },
                        ),
                    )
            }
        val core =
            McpToolRegistryCore(
                disabledFile = null,
                policyEngine = McpPolicyEngine(policyFile = null),
                ledger = McpOperationLedger(ledgerFile = null),
            )
        core.registerProvider(existing)
        core.registerProvider(ColonyToolProvider)

        val names =
            core.allTools.value
                .map { it.definition.name }
                .toSet()
        assertTrue("test_existing_tool" in names, "an unrelated provider keeps its tools")
        assertEquals(ColonyTools.all, names.filter { it.startsWith("colony_") }.toSet())
    }
}
