package ai.rever.boss.components.observability

import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentTraceRegressionTest {
    @BeforeTest
    fun reset() = AgentTraceStore.clear()

    @Test
    fun `JSON scalar values and array strings are sanitized`() {
        val secret = "ghp_abcdefghijklmnopqrstuvwxyz1234567890"
        val payloads =
            listOf(
                """{"value":"$secret"}""",
                """["$secret",{"nested":{"password":"short-secret"}}]""",
                """"$secret"""",
            )
        payloads.forEach { raw ->
            AgentTraceStore.startTrace("test", raw)
            val stored =
                AgentTraceStore.events.value
                    .first()
                    .argumentsJson
            assertFalse(stored.contains(secret), stored)
            assertFalse(stored.contains("short-secret"), stored)
        }
    }

    @Test
    fun `invalid JSON is omitted rather than exposing quoted credentials`() {
        AgentTraceStore.startTrace("test", """{"password":"short-secret","unfinished":""")
        val stored =
            AgentTraceStore.events.value
                .single()
                .argumentsJson
        assertFalse(stored.contains("short-secret"))
        assertTrue(stored.contains("omitted"))
    }

    @Test
    fun `oversized JSON text and whitespace stay bounded in every payload field`() {
        val payloads =
            listOf(
                " ".repeat(6000),
                "x".repeat(6000),
                """{"password":"short-secret","data":"${"x".repeat(6000)}"}""",
            )
        payloads.forEach { raw ->
            val id = AgentTraceStore.startTrace("test", raw)
            AgentTraceStore.completeTrace(id, McpToolResult(raw))
            var event = AgentTraceStore.events.value.first()
            assertTrue(event.argumentsJson.length <= 5000)
            assertTrue(event.resultJson!!.length <= 5000)
            assertFalse(event.argumentsJson.contains("short-secret"))
            AgentTraceStore.failTrace(id, IllegalStateException(raw))
            event = AgentTraceStore.events.value.first()
            assertTrue(event.errorMessage!!.length <= 5000)
        }
    }

    @Test
    fun `nested sensitive containers redact and safe scalar types survive`() {
        AgentTraceStore.startTrace("test", """{"password":{"value":"hidden"},"items":[1,true,null,{"count":2}]}""")
        assertEquals(
            """{"password":"[REDACTED]","items":[1,true,null,{"count":2}]}""",
            AgentTraceStore.events.value
                .single()
                .argumentsJson,
        )
    }

    @Test
    fun `large numeric values retain their JSON type unless the key is sensitive`() {
        AgentTraceStore.startTrace("test", """{"count":12345678901234567890,"password":12345678901234567890}""")
        assertEquals(
            """{"count":12345678901234567890,"password":"[REDACTED]"}""",
            AgentTraceStore.events.value
                .single()
                .argumentsJson,
        )
    }

    @Test
    fun `deep JSON and expanding redaction are bounded`() {
        listOf("[".repeat(100) + "0" + "]".repeat(100), (1..300).joinToString(",", "{", "}") { "\"key$it\":0" })
            .forEach { raw ->
                AgentTraceStore.startTrace("test", raw)
                val stored =
                    AgentTraceStore.events.value
                        .first()
                        .argumentsJson
                assertTrue(stored.length <= 5000)
                assertTrue(stored.contains("omitted"))
            }
    }

    @Test
    fun `concurrent start and completion do not lose retained events`() =
        runBlocking {
            coroutineScope {
                repeat(200) { index ->
                    launch(Dispatchers.Default) {
                        val id = AgentTraceStore.startTrace("tool$index", "{}")
                        AgentTraceStore.completeTrace(id, McpToolResult("ok"))
                    }
                }
            }
            val events = AgentTraceStore.events.value
            assertEquals(200, events.size)
            assertEquals(200, events.map { it.id }.toSet().size)
            assertTrue(events.all { it.status == TraceStatus.SUCCESS })
        }

    @Test
    fun `completion after clear or eviction never resurrects a trace`() {
        val cleared = AgentTraceStore.startTrace("cleared", "{}")
        AgentTraceStore.clear()
        AgentTraceStore.completeTrace(cleared, McpToolResult("ok"))
        assertTrue(AgentTraceStore.events.value.isEmpty())
        val evicted = AgentTraceStore.startTrace("evicted", "{}")
        repeat(500) { AgentTraceStore.startTrace("other", "{}") }
        AgentTraceStore.completeTrace(evicted, McpToolResult("ok"))
        assertEquals(500, AgentTraceStore.events.value.size)
        assertTrue(AgentTraceStore.events.value.none { it.id == evicted })
    }
}
