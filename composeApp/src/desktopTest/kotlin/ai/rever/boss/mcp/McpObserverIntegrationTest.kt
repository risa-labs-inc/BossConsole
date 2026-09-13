package ai.rever.boss.mcp

import ai.rever.boss.plugin.api.McpExecutionOutcome
import ai.rever.boss.plugin.api.McpExecutionRequest
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolExecutionObserver
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpObserverIntegrationTest {
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
        requiresAdmin: Boolean = false,
        handler: McpToolHandler = McpToolHandler { McpToolResult("ok:$name") },
    ) = McpToolDefinition(name = name, description = "test tool $name", handler = handler)
        .apply {
            this.requiredPermissions = requiredPermissions
            this.requiresAdmin = requiresAdmin
        }

    @Test
    fun `observer receives safely sanitized nested JSON result payload`() =
        runBlocking {
            val core = McpToolRegistryCore(disabledFile = null)
            val rawNested =
                "{\"user\": {\"profile\": {\"name\": \"alice\", \"token\": \"sk-secret-value\"} }, " +
                    "\"metadata\": {\"nested\": {\"enabled\": true} } }"
            core.registerProvider(
                provider("p1", echoTool("test_tool", handler = McpToolHandler { McpToolResult(rawNested) })),
            )

            var capturedOutcome: McpExecutionOutcome? = null
            val observer =
                object : McpToolExecutionObserver {
                    override val observerId = "json-test"

                    override fun onExecutionStarted(request: McpExecutionRequest) = Unit

                    override fun onExecutionFinished(
                        request: McpExecutionRequest,
                        outcome: McpExecutionOutcome,
                    ) {
                        capturedOutcome = outcome
                    }
                }
            core.registerExecutionObserver(observer)

            core.invoke("test_tool", "{}")

            val success = capturedOutcome as? McpExecutionOutcome.Success
            assertNotNull(success)

            val sanitizedText = success.result.text
            assertTrue(sanitizedText.contains("user"))
            assertTrue(sanitizedText.contains("alice"))
            assertTrue(sanitizedText.contains("[REDACTED]"))
            assertFalse(sanitizedText.contains("sk-secret-value"))
        }

    @Test
    fun `observer receives safely sanitized scalar and array JSON results`() =
        runBlocking {
            val core = McpToolRegistryCore(disabledFile = null)
            val rawArray =
                "[{\"name\": \"a\", \"token\": \"sk-secret-a\"}, " +
                    "{\"name\": \"b\", \"token\": \"sk-secret-b\"}, 123, true, null]"
            core.registerProvider(
                provider("p1", echoTool("test_tool", handler = McpToolHandler { McpToolResult(rawArray) })),
            )

            var capturedOutcome: McpExecutionOutcome? = null
            val observer =
                object : McpToolExecutionObserver {
                    override val observerId = "json-array-test"

                    override fun onExecutionStarted(request: McpExecutionRequest) = Unit

                    override fun onExecutionFinished(
                        request: McpExecutionRequest,
                        outcome: McpExecutionOutcome,
                    ) {
                        capturedOutcome = outcome
                    }
                }
            core.registerExecutionObserver(observer)

            core.invoke("test_tool", "{}")

            val success = capturedOutcome as? McpExecutionOutcome.Success
            assertNotNull(success)

            val sanitizedText = success.result.text
            assertTrue(sanitizedText.contains("name"))
            assertFalse(sanitizedText.contains("sk-secret-a"))
            assertFalse(sanitizedText.contains("sk-secret-b"))
            assertTrue(sanitizedText.contains("[REDACTED]"))
            assertTrue(sanitizedText.contains("123"))
            assertTrue(sanitizedText.contains("true"))
            assertTrue(sanitizedText.contains("null"))
        }

    @Test
    fun `observer gracefully handles malformed JSON results`() =
        runBlocking {
            val core = McpToolRegistryCore(disabledFile = null)
            val rawMalformed = "{\"user\":{\"token\":\"sk-secret"
            core.registerProvider(
                provider("p1", echoTool("test_tool", handler = McpToolHandler { McpToolResult(rawMalformed) })),
            )

            var capturedOutcome: McpExecutionOutcome? = null
            val observer =
                object : McpToolExecutionObserver {
                    override val observerId = "json-malformed-test"

                    override fun onExecutionStarted(request: McpExecutionRequest) = Unit

                    override fun onExecutionFinished(
                        request: McpExecutionRequest,
                        outcome: McpExecutionOutcome,
                    ) {
                        capturedOutcome = outcome
                    }
                }
            core.registerExecutionObserver(observer)

            val finalResult = core.invoke("test_tool", "{}")

            val success = capturedOutcome as? McpExecutionOutcome.Success
            assertNotNull(success)

            val sanitizedText = success.result.text
            assertFalse(sanitizedText.contains("sk-secret"))
            assertTrue(sanitizedText.contains("[OMITTED:"))

            assertTrue(finalResult.text.contains("sk-secret"))
        }

    @Test
    fun `observer receives capped result before sanitization for oversized JSON`() =
        runBlocking {
            val core = McpToolRegistryCore(disabledFile = null)
            val padding = "a".repeat(150_000)
            val rawOversized = "{\"pad\": \"$padding\", \"token\": \"sk-secret-value-at-end\"}"
            core.registerProvider(
                provider("p1", echoTool("test_tool", handler = McpToolHandler { McpToolResult(rawOversized) })),
            )

            var capturedOutcome: McpExecutionOutcome? = null
            val observer =
                object : McpToolExecutionObserver {
                    override val observerId = "json-oversized-test"

                    override fun onExecutionStarted(request: McpExecutionRequest) = Unit

                    override fun onExecutionFinished(
                        request: McpExecutionRequest,
                        outcome: McpExecutionOutcome,
                    ) {
                        capturedOutcome = outcome
                    }
                }
            core.registerExecutionObserver(observer)

            core.invoke("test_tool", "{}")

            val success = capturedOutcome as? McpExecutionOutcome.Success
            assertNotNull(success)

            val sanitizedText = success.result.text
            assertTrue(sanitizedText.contains("[OMITTED:"))
            assertFalse(sanitizedText.contains("sk-secret-value-at-end"))
            assertTrue(sanitizedText.length <= McpObservationPreview.MAX_CHARS)
        }

    @Test
    fun `privacy regression test for arbitrary observer JSON payload`() =
        runBlocking {
            val core = McpToolRegistryCore(disabledFile = null)
            val rawJson =
                "{\n" +
                    "    \"user\": \"alice\",\n" +
                    "    \"nested\": {\n" +
                    "        \"secrets\": [\n" +
                    "            \"sk-test-secret\",\n" +
                    "            {\"password\": \"super-secret\"},\n" +
                    "            \"api_key=abc123\",\n" +
                    "            {\"token\": \"secret-token\"},\n" +
                    "            \"authorization=Bearer secret\"\n" +
                    "        ]\n" +
                    "    },\n" +
                    "    \"safe_value\": \"hello world\",\n" +
                    "    \"safe_boolean\": true,\n" +
                    "    \"safe_number\": 42\n" +
                    "}"

            core.registerProvider(
                provider("p1", echoTool("test_tool", handler = McpToolHandler { McpToolResult(rawJson) })),
            )

            var capturedOutcome: McpExecutionOutcome? = null
            val observer =
                object : McpToolExecutionObserver {
                    override val observerId = "privacy-test"

                    override fun onExecutionStarted(request: McpExecutionRequest) = Unit

                    override fun onExecutionFinished(
                        request: McpExecutionRequest,
                        outcome: McpExecutionOutcome,
                    ) {
                        capturedOutcome = outcome
                    }
                }
            core.registerExecutionObserver(observer)

            core.invoke("test_tool", "{}")

            val success = capturedOutcome as? McpExecutionOutcome.Success
            assertNotNull(success)

            val sanitizedText = success.result.text
            assertFalse(sanitizedText.contains("sk-test-secret"))
            assertFalse(sanitizedText.contains("super-secret"))
            assertFalse(sanitizedText.contains("abc123"))
            assertFalse(sanitizedText.contains("secret-token"))
            assertFalse(sanitizedText.contains("Bearer secret"))

            assertTrue(sanitizedText.contains("[REDACTED]"))

            assertTrue(sanitizedText.contains("alice"))
            assertTrue(sanitizedText.contains("hello world"))
            assertTrue(sanitizedText.contains("true"))
            assertTrue(sanitizedText.contains("42"))
        }

    @Test
    fun `observer registration ignores duplicates`() =
        kotlinx.coroutines.runBlocking {
            val registry = McpToolRegistryCore(disabledFile = null)
            var calls = 0
            val observer =
                object : ai.rever.boss.plugin.api.McpToolExecutionObserver {
                    override val observerId = "test.observer"

                    override fun onExecutionStarted(request: ai.rever.boss.plugin.api.McpExecutionRequest) {
                        calls++
                    }

                    override fun onExecutionFinished(
                        request: ai.rever.boss.plugin.api.McpExecutionRequest,
                        outcome: ai.rever.boss.plugin.api.McpExecutionOutcome,
                    ) {
                        calls++
                    }
                }

            registry.registerExecutionObserver(observer)
            registry.registerExecutionObserver(observer)

            registry.registerProvider(provider("p1", echoTool("tool1")))

            val result = registry.invoke("tool1", "{}")
            kotlin.test.assertTrue(result.text.contains("ok:tool1"))
            assertEquals(2, calls)
            registry.unregisterExecutionObserver("test.observer")
            registry.invoke("tool1", "{}")
            assertEquals(2, calls)
        }

    @Test
    fun `observer exceptions do not break tool execution`() =
        kotlinx.coroutines.runBlocking {
            val registry = McpToolRegistryCore(disabledFile = null)

            var startedCalled = false
            var finishedCalled = false

            val observer =
                object : ai.rever.boss.plugin.api.McpToolExecutionObserver {
                    override val observerId = "faulty.observer"

                    override fun onExecutionStarted(request: ai.rever.boss.plugin.api.McpExecutionRequest) {
                        startedCalled = true
                        error("Observer crashed on start")
                    }

                    override fun onExecutionFinished(
                        request: ai.rever.boss.plugin.api.McpExecutionRequest,
                        outcome: ai.rever.boss.plugin.api.McpExecutionOutcome,
                    ) {
                        finishedCalled = true
                        error("Observer crashed on finish")
                    }
                }

            registry.registerExecutionObserver(observer)

            registry.registerProvider(provider("p1", echoTool("tool1")))

            val result = registry.invoke("tool1", "{}")

            kotlin.test.assertTrue(result.text.contains("ok:tool1"))
            kotlin.test.assertTrue(startedCalled)
            kotlin.test.assertTrue(finishedCalled)
        }

    @Test
    fun `observer receives capped result and does not receive over-cap result`() =
        kotlinx.coroutines.runBlocking {
            val registry = McpToolRegistryCore(disabledFile = null, maxResultChars = 1_000)
            registry.registerProvider(
                provider("p1", echoTool("tool1", handler = McpToolHandler { McpToolResult("x".repeat(5_000)) })),
            )

            var observerResultText: String? = null

            val observer =
                object : ai.rever.boss.plugin.api.McpToolExecutionObserver {
                    override val observerId = "test.obs"

                    override fun onExecutionStarted(request: ai.rever.boss.plugin.api.McpExecutionRequest) = Unit

                    override fun onExecutionFinished(
                        request: ai.rever.boss.plugin.api.McpExecutionRequest,
                        outcome: ai.rever.boss.plugin.api.McpExecutionOutcome,
                    ) {
                        if (outcome is ai.rever.boss.plugin.api.McpExecutionOutcome.Success) {
                            observerResultText = outcome.result.text
                        }
                    }
                }
            registry.registerExecutionObserver(observer)
            registry.invoke("tool1", "{}")

            val result = observerResultText
            kotlin.test.assertNotNull(result)
            kotlin.test.assertTrue(result.length <= 1_000)
            kotlin.test.assertTrue(result.contains("BOSS host cap"))
        }

    @Test
    fun `sensitive MCP arguments are sanitized before observer delivery`() =
        kotlinx.coroutines.runBlocking {
            val registry = McpToolRegistryCore(disabledFile = null)
            registry.registerProvider(
                provider("p1", echoTool("tool1")),
            )

            var observerArgsText: String? = null
            val observer =
                object : ai.rever.boss.plugin.api.McpToolExecutionObserver {
                    override val observerId = "test.obs"

                    override fun onExecutionStarted(request: ai.rever.boss.plugin.api.McpExecutionRequest) {
                        observerArgsText = request.arguments
                    }

                    override fun onExecutionFinished(
                        request: ai.rever.boss.plugin.api.McpExecutionRequest,
                        outcome: ai.rever.boss.plugin.api.McpExecutionOutcome,
                    ) = Unit
                }
            registry.registerExecutionObserver(observer)

            val rawArgs = "{\"api_key\": \"sk-1234567890\", \"nested\": {\"password\": \"foo\"}, \"safe\": \"bar\"}"
            registry.invoke("tool1", rawArgs)

            val argsStr = observerArgsText
            kotlin.test.assertNotNull(argsStr)
            kotlin.test.assertTrue(argsStr.contains("[REDACTED]"))
            kotlin.test.assertFalse(argsStr.contains("sk-1234567890"))
            kotlin.test.assertFalse(argsStr.contains("foo"))
            kotlin.test.assertTrue(argsStr.contains("bar"))
        }

    @Test
    fun `sensitive exception messages are not exposed raw`() =
        kotlinx.coroutines.runBlocking {
            val registry = McpToolRegistryCore(disabledFile = null)
            registry.registerProvider(
                provider(
                    "p1",
                    echoTool("tool1", handler = McpToolHandler { error("Crashed with token sk-1234567890") }),
                ),
            )

            var outcomeErrorMsg: String? = null
            val observer =
                object : ai.rever.boss.plugin.api.McpToolExecutionObserver {
                    override val observerId = "test.obs"

                    override fun onExecutionStarted(request: ai.rever.boss.plugin.api.McpExecutionRequest) = Unit

                    override fun onExecutionFinished(
                        request: ai.rever.boss.plugin.api.McpExecutionRequest,
                        outcome: ai.rever.boss.plugin.api.McpExecutionOutcome,
                    ) {
                        if (outcome is ai.rever.boss.plugin.api.McpExecutionOutcome.Failure) {
                            outcomeErrorMsg = outcome.error.message
                        }
                    }
                }
            registry.registerExecutionObserver(observer)
            registry.invoke("tool1", "{}")

            val msg = outcomeErrorMsg
            kotlin.test.assertNotNull(msg)

            kotlin.test.assertFalse(msg.contains("sk-1234567890"))
        }

    @Test
    fun `observer receives correct timeout outcome`() =
        kotlinx.coroutines.runBlocking {
            val registry = McpToolRegistryCore(disabledFile = null, invokeTimeoutMs = 100)
            registry.registerProvider(
                provider(
                    "p1",
                    echoTool(
                        "tool1",
                        handler =
                            McpToolHandler {
                                kotlinx.coroutines.delay(500)
                                McpToolResult("done")
                            },
                    ),
                ),
            )

            var didTimeout = false
            val observer =
                object : ai.rever.boss.plugin.api.McpToolExecutionObserver {
                    override val observerId = "test.obs"

                    override fun onExecutionStarted(request: ai.rever.boss.plugin.api.McpExecutionRequest) = Unit

                    override fun onExecutionFinished(
                        request: ai.rever.boss.plugin.api.McpExecutionRequest,
                        outcome: ai.rever.boss.plugin.api.McpExecutionOutcome,
                    ) {
                        if (outcome is ai.rever.boss.plugin.api.McpExecutionOutcome.Timeout) {
                            didTimeout = true
                        }
                    }
                }
            registry.registerExecutionObserver(observer)
            registry.invoke("tool1", "{}")

            kotlin.test.assertTrue(didTimeout)
        }

    @Test
    fun `caller cancellation emits one cancelled outcome and preserves cancellation`() =
        runBlocking {
            val core = McpToolRegistryCore(disabledFile = null)
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            core.registerProvider(
                provider(
                    "p",
                    echoTool(
                        "waiting",
                        handler =
                            McpToolHandler {
                                entered.complete(Unit)
                                kotlinx.coroutines.awaitCancellation()
                            },
                    ),
                ),
            )
            val outcomes = mutableListOf<McpExecutionOutcome>()
            core.registerExecutionObserver(
                object : McpToolExecutionObserver {
                    override val observerId = "cancel"

                    override fun onExecutionStarted(request: McpExecutionRequest) = Unit

                    override fun onExecutionFinished(
                        request: McpExecutionRequest,
                        outcome: McpExecutionOutcome,
                    ) {
                        outcomes += outcome
                    }
                },
            )
            val call = async { core.invoke("waiting", "{}") }
            entered.await()
            call.cancel()
            call.join()
            assertTrue(call.isCancelled)
            assertEquals(1, outcomes.size)
            assertTrue(outcomes.single() is McpExecutionOutcome.Cancelled)
        }

    @Test
    fun `unregister during handler suppresses completion from the captured subscription`() =
        runBlocking {
            val core = McpToolRegistryCore(disabledFile = null)
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            core.registerProvider(
                provider(
                    "p",
                    echoTool(
                        "waiting",
                        handler =
                            McpToolHandler {
                                entered.complete(Unit)
                                release.await()
                                McpToolResult("done")
                            },
                    ),
                ),
            )
            var starts = 0
            var finishes = 0
            core.registerExecutionObserver(
                object : McpToolExecutionObserver {
                    override val observerId = "removal"

                    override fun onExecutionStarted(request: McpExecutionRequest) {
                        starts++
                    }

                    override fun onExecutionFinished(
                        request: McpExecutionRequest,
                        outcome: McpExecutionOutcome,
                    ) {
                        finishes++
                    }
                },
            )
            val call = async { core.invoke("waiting", "{}") }
            entered.await()
            core.unregisterExecutionObserver("removal")
            release.complete(Unit)
            assertEquals("done", call.await().text)
            assertEquals(1, starts)
            assertEquals(0, finishes)
        }
}
