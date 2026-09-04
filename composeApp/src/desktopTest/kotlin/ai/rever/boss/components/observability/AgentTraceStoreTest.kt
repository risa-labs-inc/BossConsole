package ai.rever.boss.components.observability

import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentTraceStoreTest {

    @BeforeTest
    fun setup() {
        AgentTraceStore.clear()
    }

    @Test
    fun `startTrace creates a RUNNING event`() = runBlocking {
        val id = AgentTraceStore.startTrace("test.tool", "{\"key\":\"[REDACTED]\"}")
        
        val events = AgentTraceStore.events.value
        assertEquals(1, events.size)
        
        val event = events.first()
        assertEquals(id, event.id)
        assertEquals("test.tool", event.toolName)
        assertEquals(TraceStatus.RUNNING, event.status)
        assertEquals("{\"key\":\"[REDACTED]\"}", event.argumentsJson)
    }

    @Test
    fun `completeTrace transitions to SUCCESS and calculates duration`() = runBlocking {
        val id = AgentTraceStore.startTrace("test.tool", "{}")
        AgentTraceStore.completeTrace(id, McpToolResult("Success result"))
        
        val event = AgentTraceStore.events.value.first()
        assertEquals(TraceStatus.SUCCESS, event.status)
        assertEquals("Success result", event.resultJson)
        assertNotNull(event.completedAtMs)
        assertTrue(event.durationMs != null && event.durationMs!! >= 0)
    }

    @Test
    fun `completeTrace transitions to FAILURE on error result`() = runBlocking {
        val id = AgentTraceStore.startTrace("test.tool", "{}")
        AgentTraceStore.completeTrace(id, McpToolResult("Error result", isError = true))
        
        val event = AgentTraceStore.events.value.first()
        assertEquals(TraceStatus.FAILURE, event.status)
        assertEquals("Error result", event.errorMessage)
    }

    @Test
    fun `failTrace transitions to TIMEOUT on timeout flag`() = runBlocking {
        val id = AgentTraceStore.startTrace("test.tool", "{}")
        AgentTraceStore.failTrace(id, java.util.concurrent.TimeoutException(), isTimeout = true)
        
        val event = AgentTraceStore.events.value.first()
        assertEquals(TraceStatus.TIMEOUT, event.status)
        assertEquals("TimeoutException", event.errorMessage)
    }

    @Test
    fun `failTrace transitions to CANCELLED on isCancelled flag`() = runBlocking {
        val id = AgentTraceStore.startTrace("test.tool", "{}")
        AgentTraceStore.failTrace(id, kotlinx.coroutines.CancellationException(), isTimeout = false, isCancelled = true)
        
        val event = AgentTraceStore.events.value.first()
        assertEquals(TraceStatus.CANCELLED, event.status)
        assertEquals("CancellationException", event.errorMessage)
    }

    @Test
    fun `store truncates history at MAX_EVENTS`() = runBlocking {
        for (i in 1..501) {
            AgentTraceStore.startTrace("tool.$i", "{}")
        }
        
        val events = AgentTraceStore.events.value
        assertEquals(500, events.size)
        // Since we add at index 0, the oldest event (tool.1) should be removed
        assertEquals("tool.501", events.first().toolName)
        assertEquals("tool.2", events.last().toolName)
    }

    @Test
    fun `sanitization redacts sensitive payloads`() = runBlocking {
        val sensitiveArgs = "{\"token\":\"secret123\", \"public\":\"ok\"}"
        val id = AgentTraceStore.startTrace("test.tool", sensitiveArgs)
        
        val event = AgentTraceStore.events.value.first()
        assertTrue(event.argumentsJson.contains("[REDACTED]"))
        assertTrue(!event.argumentsJson.contains("secret123"))
        assertTrue(event.argumentsJson.contains("public"))
    }
}
