package ai.rever.boss.mcp

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpPreparedProviderRegistrationTest {
    @Test
    fun `invocation keeps preparation and handler from one registration`() {
        fun provider(label: String) =
            object : McpToolProvider, McpToolPreparer {
                override val providerId = "prepared-review"

                override fun tools() =
                    listOf(
                        McpToolDefinition(
                            name = "review_prepared_identity",
                            description = "Check matching preparation and handler generation",
                            readOnly = true,
                            handler =
                                McpToolHandler { args -> McpToolResult("$label:${args.executionObject<String>()}") },
                        ),
                    )

                override suspend fun prepareInvocation(
                    toolName: String,
                    args: McpToolArgs,
                ) = McpPreparationResult.Prepared(null, label)
            }

        val core =
            McpToolRegistryCore(
                disabledFile = null,
                policyEngine = McpPolicyEngine(policyFile = null),
                ledger = McpOperationLedger(ledgerFile = null),
            )
        core.registerProvider(provider("A"))
        val mutationLock =
            McpToolRegistryCore::class.java
                .getDeclaredField("mutationLock")
                .apply { isAccessible = true }
                .get(core)
        var result: McpToolResult? = null
        var replacement: Thread? = null
        try {
            synchronized(mutationLock) {
                val worker = Thread { core.registerProvider(provider("B")) }.apply { start() }
                replacement = worker
                val deadline = System.nanoTime() + 5_000_000_000L
                while (worker.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(5)
                assertEquals(
                    Thread.State.BLOCKED,
                    worker.state,
                    "Replacement must be waiting to publish its tool definitions",
                )
                result = runBlocking { core.invoke("review_prepared_identity", "{}") }
            }
        } finally {
            replacement?.join(5_000)
        }
        assertEquals("A:A", result?.text, "The active A handler must never receive B's prepared invocation")
        assertEquals("B:B", runBlocking { core.invoke("review_prepared_identity", "{}") }.text)
        core.unregisterProvider("prepared-review")
        assertTrue(runBlocking { core.invoke("review_prepared_identity", "{}") }.isError)
    }
}
