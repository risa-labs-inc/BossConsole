package ai.rever.boss.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpActivityStoreTest {
    @Test
    fun `sequences start above zero increase and survive clear`() {
        val store = McpActivityStore()
        val first = append(store, "first")
        val second = append(store, "second")
        store.clear()
        val third = append(store, "third")

        assertTrue(first.sequence > 0)
        assertEquals(listOf(first.sequence, second.sequence, third.sequence), listOf(1L, 2L, 3L))
        assertEquals(listOf(third), store.events.value)
    }

    @Test
    fun `store retains oldest first and evicts only oldest at capacity`() {
        val store = McpActivityStore()
        repeat(101) { append(store, "tool-$it") }

        assertEquals(100, store.events.value.size)
        assertEquals(
            2L,
            store.events.value
                .first()
                .sequence,
        )
        assertEquals(
            101L,
            store.events.value
                .last()
                .sequence,
        )
        assertEquals(
            "tool-1",
            store.events.value
                .first()
                .toolName,
        )
    }

    @Test
    fun `concurrent append keeps unique sequences and no events below capacity`() {
        val store = McpActivityStore()
        val threads = (0 until 50).map { index -> Thread { append(store, "tool-$index") } }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        assertEquals(50, store.events.value.size)
        assertEquals(
            50,
            store.events.value
                .map { it.sequence }
                .toSet()
                .size,
        )
    }

    @Test
    fun `clear is safe while appenders run`() {
        val store = McpActivityStore()
        val appenders = (0 until 50).map { index -> Thread { append(store, "tool-$index") } }
        val clearer = Thread { repeat(20) { store.clear() } }
        appenders.forEach(Thread::start)
        clearer.start()
        appenders.forEach(Thread::join)
        clearer.join()

        assertTrue(store.events.value.size <= McpActivityStore.CAPACITY)
        assertEquals(
            store.events.value.size,
            store.events.value
                .map { it.sequence }
                .toSet()
                .size,
        )
    }

    private fun append(
        store: McpActivityStore,
        toolName: String,
    ): McpActivityEvent =
        store.append(
            completedAtEpochMs = 1L,
            durationMs = 2L,
            toolName = toolName,
            providerId = "provider",
            outcome = McpActivityOutcome.SUCCESS,
        )
}
