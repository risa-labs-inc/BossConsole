package ai.rever.boss.mcp

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpGovernanceReviewTest {
    @Test
    fun `approved execution cancellation differs from unanswered cancellation`() =
        runBlocking {
            val bus = McpApprovalBus()
            val ledger = McpOperationLedger()
            val entered = CompletableDeferred<Unit>()
            val core = McpToolRegistryCore(disabledFile = null, approvalBus = bus, ledger = ledger)
            core.registerProvider(
                object : McpToolProvider {
                    override val providerId = "p"

                    override fun tools() =
                        listOf(
                            McpToolDefinition(
                                name = "run_command",
                                description = "test",
                                handler =
                                    McpToolHandler {
                                        entered.complete(Unit)
                                        awaitCancellation()
                                    },
                            ),
                        )
                },
            )
            val call = async { core.invoke("run_command", "{}") }
            val request = bus.pendingList.first { it.isNotEmpty() }.first()
            bus.approve(request.id)
            entered.await()
            call.cancelAndJoin()
            assertEquals(
                McpApprovalDisposition.CANCELLED_IN_FLIGHT,
                ledger.recentOperations.value
                    .single()
                    .approvalDisposition,
            )
        }

    @Test
    fun `credential spans preserve surrounding command and depth is bounded`() {
        val command = "curl -H 'Bearer ghp_123456789abcdef' https://example.test/path"
        val safe = McpArgumentSanitizer.sanitizeMessage(command)
        assertFalse(safe.contains("ghp_123456789abcdef"))
        assertTrue(safe.contains("curl"))
        assertTrue(safe.contains("https://example.test/path"))
        var nested: Any? = "sentinel"
        repeat(1000) { nested = listOf(nested) }
        assertTrue(McpArgumentSanitizer.sanitize(mapOf("data" to nested)).toString().contains("too deeply nested"))
    }

    @Test
    fun `fault notifier never runs during construction or breaks enforcement`() {
        val directory = Files.createTempDirectory("mcp-policy-review").toFile()
        try {
            val file = directory.resolve("policy.json")
            file.writeText("invalid")
            var notified = false
            val engine =
                McpPolicyEngine(file) {
                    notified = true
                    error("UI unavailable")
                }
            assertFalse(notified)
            assertEquals(McpPolicyAction.DENY, engine.policyFor("run_command"))
            file.delete()
            file.mkdir()
            file.resolve("child").writeText("blocks replacement")
            engine.setToolPolicy("run_command", McpPolicyAction.ALLOW)
            assertTrue(notified)
            assertEquals(McpPolicyAction.DENY, engine.policyFor("run_command"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `one window owns prompt and teardown releases caller`() =
        runBlocking {
            val bus = McpApprovalBus()
            val shown = mutableListOf<McpApprovalRequest>()
            val firstWindow = launch { bus.consumeApprovals { if (it != null) shown.add(it) } }
            val secondWindow = launch { bus.consumeApprovals { if (it != null) shown.add(it) } }
            val call = async { bus.requestApproval("run_command", "p", emptyMap()) }
            bus.pendingList.first { it.isNotEmpty() }
            yield()
            assertEquals(1, shown.size)
            firstWindow.cancelAndJoin()
            secondWindow.cancelAndJoin()
            assertTrue(call.await() is McpApprovalDecision.Denied)
            assertTrue(bus.pendingList.value.isEmpty())
        }
}
