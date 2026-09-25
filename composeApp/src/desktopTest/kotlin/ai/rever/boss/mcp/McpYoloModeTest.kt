package ai.rever.boss.mcp

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
 * YOLO mode replaces the approval PROMPT and nothing else. These drive the real registry so the
 * claim "explicit denies still win" is checked where invocation is decided, not just asserted.
 */
class McpYoloModeTest {
    private class Fixture(
        yoloAvailable: Boolean = true,
    ) {
        val approvalBus = McpApprovalBus(defaultTimeoutMs = 5000L)
        val policyEngine = McpPolicyEngine(policyFile = null)
        val ledger = McpOperationLedger(ledgerFile = null)
        var calls = 0
        val core =
            McpToolRegistryCore(
                disabledFile = null,
                policyEngine = policyEngine,
                approvalBus = approvalBus,
                ledger = ledger,
                yoloAvailable = yoloAvailable,
            ).also { core ->
                core.registerProvider(
                    object : McpToolProvider {
                        override val providerId = "p1"

                        override fun tools() =
                            listOf(
                                // A catalog-mutating name, so the shipped default is ASK.
                                McpToolDefinition(
                                    name = "run_command",
                                    description = "test",
                                    handler =
                                        McpToolHandler {
                                            calls++
                                            McpToolResult("ok")
                                        },
                                ),
                            )
                    },
                )
            }

        fun lastDisposition() =
            ledger.recentOperations.value
                .first()
                .approvalDisposition
    }

    @Test
    fun `yolo runs an ASK tool without prompting and records it as yolo`() =
        runBlocking {
            val f = Fixture()
            f.core.setYoloMode(true)
            val result = f.core.invoke("run_command", """{"script":"rm -rf /tmp/x"}""")
            assertFalse(result.isError)
            assertEquals(1, f.calls)
            assertTrue(
                f.approvalBus.pendingList.value
                    .isEmpty(),
                "no prompt may be raised",
            )
            assertEquals(
                McpPolicyAction.ASK,
                f.ledger.recentOperations.value
                    .first()
                    .policyApplied,
            )
            assertEquals(McpApprovalDisposition.YOLO_ALLOWED, f.lastDisposition())
        }

    @Test
    fun `an explicit tool deny still wins over yolo`() =
        runBlocking {
            val f = Fixture()
            f.policyEngine.setToolPolicy("run_command", McpPolicyAction.DENY)
            f.core.setYoloMode(true)
            assertTrue(f.core.invoke("run_command", "{}").isError)
            assertEquals(0, f.calls)
            assertEquals(McpApprovalDisposition.POLICY_DENIED, f.lastDisposition())
        }

    @Test
    fun `a plugin-wide deny still wins over yolo`() =
        runBlocking {
            val f = Fixture()
            f.policyEngine.setProviderPolicy("p1", McpPolicyAction.DENY)
            f.core.setYoloMode(true)
            assertTrue(f.core.invoke("run_command", "{}").isError)
            assertEquals(0, f.calls)
        }

    @Test
    fun `yolo is off by default and turning it off restores the prompt`() =
        runBlocking {
            val f = Fixture()
            assertFalse(f.policyEngine.yoloMode.value)
            f.core.setYoloMode(true)
            f.core.setYoloMode(false)
            val call = async { f.core.invoke("run_command", "{}") }
            val req =
                f.approvalBus.pendingList
                    .first { it.isNotEmpty() }
                    .first()
            f.approvalBus.deny(req.id, "no", persistPolicy = false)
            assertTrue(call.await().isError)
            assertEquals(0, f.calls)
            assertEquals(McpApprovalDisposition.DENIED_BY_OPERATOR, f.lastDisposition())
        }

    @Test
    fun `switching yolo writes one ledger marker per real transition, not counted as a call`() =
        runBlocking {
            val f = Fixture()
            f.core.setYoloMode(true)
            f.core.setYoloMode(true) // no-op: no second marker
            f.core.setYoloMode(false)
            val markers =
                f.ledger.recentOperations.value
                    .map { it.approvalDisposition }
            assertEquals(listOf(McpApprovalDisposition.YOLO_DISABLED, McpApprovalDisposition.YOLO_ENABLED), markers)
            assertEquals(
                McpYoloMode.LEDGER_TOOL_NAME,
                f.ledger.recentOperations.value
                    .first()
                    .toolName,
            )
            assertEquals(0L, f.ledger.totalCalls.value)
        }

    @Test
    fun `a deployment that refuses yolo cannot turn it on`() =
        runBlocking {
            val f = Fixture(yoloAvailable = false)
            assertFalse(f.core.setYoloMode(true))
            assertFalse(f.policyEngine.yoloMode.value)
            assertTrue(
                f.ledger.recentOperations.value
                    .isEmpty(),
            )
            // Turning it off is never refused.
            assertTrue(f.core.setYoloMode(false))
        }

    @Test
    fun `the deployment switch accepts the usual truthy spellings and ignores a blank env`() {
        assertTrue(McpYoloGate.disabledFrom("true", null))
        assertTrue(McpYoloGate.disabledFrom(" ON ", null))
        assertTrue(McpYoloGate.disabledFrom("1", null))
        assertTrue(McpYoloGate.disabledFrom("", "yes"))
        assertFalse(McpYoloGate.disabledFrom(null, null))
        assertFalse(McpYoloGate.disabledFrom("false", "true"))
    }
}
