package ai.rever.boss.mastery

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubmitFeedbackTest {
    @Test
    fun `submit feedback creates new checkpoint with feedback`() = runBlocking {
        val store = InMemoryExecutionStore()
        val resolver = object : CapabilityResolver {
            override suspend fun invoke(pluginId: String, action: String, input: Map<String, String>) = emptyMap<String, String>()
            override fun getAvailableCapabilities() = emptyList<CapabilityInfo>()
        }
        val executor = MasteryExecutor(resolver, store)
        val service = ai.rever.boss.mastery.orchestrator.MasteryServiceImpl(executor, store)

        val mastery = MasteryDefinition(id = "m", name = "m", description = "", nodes = emptyList(), edges = emptyList())
        service.createMastery(mastery.toProto())

        val execId = java.util.UUID.randomUUID().toString()
        store.createExecution(MasteryExecution(execId, mastery.id, emptyMap(), "completed"))
        val now = System.currentTimeMillis()
        val cp = NodeCheckpoint(java.util.UUID.randomUUID().toString(), execId, "node1", 1, emptyMap(), mapOf("k" to "v"), now - 1000, now)
        store.saveCheckpoint(cp)

        val ok = runBlocking { service.submitNodeFeedback(execId, "node1", "Please re-check") }
        assertTrue(ok)

        val cps = store.getCheckpoints(execId)
        val feedbackCps = cps.filter { it.feedback != null }
        assertEquals(1, feedbackCps.size)
        assertEquals("Please re-check", feedbackCps[0].feedback)
    }
}
