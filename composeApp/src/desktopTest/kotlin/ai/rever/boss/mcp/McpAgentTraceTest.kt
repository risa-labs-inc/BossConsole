package ai.rever.boss.mcp

import ai.rever.boss.components.observability.AgentTraceStore
import ai.rever.boss.components.observability.TraceStatus
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class McpAgentTraceTest {
    @BeforeTest
    fun reset() = AgentTraceStore.clear()

    private fun core(
        timeout: Long = 5000,
        handler: McpToolHandler,
    ): McpToolRegistryCore =
        McpToolRegistryCore(disabledFile = null, invokeTimeoutMs = timeout).apply {
            registerProvider(
                object : McpToolProvider {
                    override val providerId = "trace-test"

                    override fun tools() =
                        listOf(
                            McpToolDefinition(name = "trace", description = "test", handler = handler),
                        )
                },
            )
        }

    @Test
    fun `invocation returns original result while storing sanitized success or failure`() =
        runBlocking {
            listOf(false, true).forEach { error ->
                AgentTraceStore.clear()
                val original = McpToolResult("""{"password":"short-secret"}""", isError = error)
                val core = core { original }
                assertSame(original, core.invoke("trace", "{}"))
                val trace = AgentTraceStore.events.value.single()
                assertEquals(if (error) TraceStatus.FAILURE else TraceStatus.SUCCESS, trace.status)
                assertEquals("""{"password":"[REDACTED]"}""", if (error) trace.errorMessage else trace.resultJson)
            }
        }

    @Test
    fun `handler exception and deadline are traced without changing error contract`() =
        runBlocking {
            val failing = core { throw IllegalStateException("broken") }
            assertTrue(failing.invoke("trace", "{}").isError)
            assertEquals(
                TraceStatus.FAILURE,
                AgentTraceStore.events.value
                    .single()
                    .status,
            )
            AgentTraceStore.clear()
            val slow = core(timeout = 50) { awaitCancellation() }
            assertTrue(slow.invoke("trace", "{}").isError)
            assertEquals(
                TraceStatus.TIMEOUT,
                AgentTraceStore.events.value
                    .single()
                    .status,
            )
        }

    @Test
    fun `caller cancellation propagates and marks running trace cancelled`() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val core =
                core {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            val call = async { core.invoke("trace", "{}") }
            entered.await()
            assertEquals(
                TraceStatus.RUNNING,
                AgentTraceStore.events.value
                    .single()
                    .status,
            )
            call.cancelAndJoin()
            assertTrue(call.isCancelled)
            assertEquals(
                TraceStatus.CANCELLED,
                AgentTraceStore.events.value
                    .single()
                    .status,
            )
        }

    @Test
    fun `rejected tool is not recorded as an executed trace`() =
        runBlocking {
            val core = core { error("must not run") }
            core.setToolEnabled("trace", false)
            assertTrue(core.invoke("trace", "{}").isError)
            assertTrue(core.invoke("unknown", "{}").isError)
            assertTrue(AgentTraceStore.events.value.isEmpty())
        }
}
