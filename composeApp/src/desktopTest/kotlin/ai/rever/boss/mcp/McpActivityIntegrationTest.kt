package ai.rever.boss.mcp

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpActivityIntegrationTest {
    private fun provider(
        id: String,
        vararg defs: McpToolDefinition,
    ) = object : McpToolProvider {
        override val providerId = id

        override fun tools() = defs.toList()
    }

    private fun echoTool(
        name: String,
        requiredPermissions: List<String> = emptyList(),
        handler: McpToolHandler = McpToolHandler { McpToolResult("ok:$name") },
    ) = McpToolDefinition(name = name, description = "test tool $name", handler = handler)
        .apply { this.requiredPermissions = requiredPermissions }

    @Test
    fun `activity preserves the host result cap and declared error status`() =
        runBlocking {
            val store = McpActivityStore()
            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    maxResultChars = 1_000,
                    activity = McpActivityTracker(store),
                )
            core.registerProvider(
                provider(
                    "p1",
                    echoTool("large", handler = McpToolHandler { McpToolResult("x".repeat(20_000), isError = true) }),
                ),
            )
            val result = core.invoke("large", "{}")
            assertTrue(result.isError)
            assertTrue(result.text.length <= 1_000)
            assertTrue(result.text.contains("BOSS host cap"))
            assertEquals(McpActivityOutcome.ERROR, assertSingleActivity(store).outcome)
        }

    @Test
    fun `successful invocation records one success with deterministic clocks`() =
        runBlocking {
            val store = McpActivityStore()
            var monotonicNs = 5_000_000L
            val core =
                McpToolRegistryCore(
                    disabledFile = null,
                    activity =
                        McpActivityTracker(
                            store = store,
                            wallClockMs = { 1_700_000_000_123L },
                            monotonicNowNs = { monotonicNs.also { monotonicNs += 12_000_000L } },
                        ),
                )
            core.registerProvider(provider("provider.exact", echoTool("safe_tool")))

            assertEquals("ok:safe_tool", core.invoke("safe_tool", "{}").text)
            assertEquals(
                McpActivityEvent(1, 1_700_000_000_123L, 12, "safe_tool", "provider.exact", McpActivityOutcome.SUCCESS),
                assertSingleActivity(store),
            )
        }

    @Test
    fun `declared and thrown errors record error without retaining sensitive values`() =
        runBlocking {
            val store = McpActivityStore()
            val core = McpToolRegistryCore(disabledFile = null, activity = McpActivityTracker(store))
            core.registerProvider(
                provider(
                    "p1",
                    echoTool("declared", handler = McpToolHandler { McpToolResult("RESULT_SENTINEL", isError = true) }),
                    echoTool("throws", handler = McpToolHandler { error("EXCEPTION_SENTINEL") }),
                ),
            )

            assertTrue(
                core.invoke("declared", "{\"secret\":\"ARGUMENT_SENTINEL\"}").isError,
            )
            assertTrue(core.invoke("throws", "{}").isError)

            assertEquals(
                listOf(McpActivityOutcome.ERROR, McpActivityOutcome.ERROR),
                store.events.value.map { it.outcome },
            )
            val retained = store.events.value.joinToString()
            assertFalse(retained.contains("ARGUMENT_SENTINEL"))
            assertFalse(retained.contains("RESULT_SENTINEL"))
            assertFalse(retained.contains("EXCEPTION_SENTINEL"))
        }

    @Test
    fun `timeout and caller cancellation each record their terminal outcome once`() =
        runBlocking {
            val timeoutStore = McpActivityStore()
            val timeoutCore =
                McpToolRegistryCore(
                    disabledFile = null,
                    invokeTimeoutMs = 10L,
                    activity = McpActivityTracker(timeoutStore),
                )
            timeoutCore.registerProvider(
                provider(
                    "p1",
                    echoTool(
                        "hang",
                        handler =
                            McpToolHandler {
                                delay(1_000)
                                McpToolResult("no")
                            },
                    ),
                ),
            )
            assertTrue(timeoutCore.invoke("hang", "{}").isError)
            assertEquals(McpActivityOutcome.TIMEOUT, assertSingleActivity(timeoutStore).outcome)

            val cancellationStore = McpActivityStore()
            val cancellationCore =
                McpToolRegistryCore(disabledFile = null, activity = McpActivityTracker(cancellationStore))
            cancellationCore.registerProvider(
                provider(
                    "p1",
                    echoTool(
                        "slow",
                        handler =
                            McpToolHandler {
                                delay(1_000)
                                McpToolResult("no")
                            },
                    ),
                ),
            )
            var cancelled = false
            try {
                coroutineScope {
                    val deferred = async { cancellationCore.invoke("slow", "{}") }
                    delay(10)
                    deferred.cancel()
                    deferred.await()
                }
            } catch (_: CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
            assertEquals(McpActivityOutcome.CANCELLED, assertSingleActivity(cancellationStore).outcome)
        }

    @Test
    fun `pre execution refusals do not appear as completed activity`() =
        runBlocking {
            val store = McpActivityStore()
            val core = McpToolRegistryCore(disabledFile = null, activity = McpActivityTracker(store))
            var calls = 0
            val handler =
                McpToolHandler {
                    calls++
                    McpToolResult("unexpected")
                }
            core.registerProvider(
                provider(
                    "p1",
                    echoTool("restricted", requiredPermissions = listOf("secret.read"), handler = handler),
                    echoTool("disabled", handler = handler),
                    echoTool("denied", handler = handler),
                ),
            )
            core.setToolEnabled("disabled", enabled = false)
            core.policyEngine.setToolPolicy("denied", McpPolicyAction.DENY)
            for (name in listOf("restricted", "disabled", "denied")) {
                assertTrue(core.invoke(name, "{}").isError)
            }
            assertEquals(0, calls)
            assertTrue(store.events.value.isEmpty())
        }

    @Test
    fun `lookup miss records no activity`() =
        runBlocking {
            val store = McpActivityStore()
            val core = McpToolRegistryCore(disabledFile = null, activity = McpActivityTracker(store))
            assertTrue(core.invoke("absent", "{}").isError)
            assertTrue(store.events.value.isEmpty())
        }

    private fun assertSingleActivity(store: McpActivityStore): McpActivityEvent =
        assertEquals(1, store.events.value.size).let { store.events.value.single() }
}
