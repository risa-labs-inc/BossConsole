package ai.rever.boss.mcp

import java.util.concurrent.CyclicBarrier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class McpObserverSubscriptionsTest {
    @Test
    fun `unregister disables snapshots and a new registration receives future calls`() {
        val registry = McpObserverSubscriptions<() -> Unit>()
        var oldCalls = 0
        var newCalls = 0
        registry.register("id") { oldCalls++ }
        registry.register("id") { error("duplicate must not replace original") }
        val old = registry.snapshot()
        old.forEach { it.dispatch { callback -> callback() } }
        registry.unregister("id")
        registry.register("id") { newCalls++ }
        old.forEach { it.dispatch { callback -> callback() } }
        registry.snapshot().forEach { it.dispatch { callback -> callback() } }
        assertEquals(1, oldCalls)
        assertEquals(1, newCalls)
    }

    @Test
    fun `throwing callback and throwable getter do not prevent sibling delivery`() {
        val registry = McpObserverSubscriptions<() -> Unit>()
        registry.register("bad") {
            throw object : RuntimeException() {
                override val message: String get() = error("must not read plugin exception properties")
            }
        }
        var calls = 0
        registry.register("good") { calls++ }
        registry.snapshot().forEach { it.dispatch { callback -> callback() } }
        assertEquals(1, calls)
    }

    @Test
    fun `callbacks can remove each other without holding subscription locks`() {
        val registry = McpObserverSubscriptions<() -> Unit>()
        val barrier = CyclicBarrier(2)
        registry.register("a") {
            barrier.await()
            registry.unregister("b")
        }
        registry.register("b") {
            barrier.await()
            registry.unregister("a")
        }
        val workers =
            registry.snapshot().map { subscription ->
                Thread { subscription.dispatch { it() } }.apply { isDaemon = true }
            }
        workers.forEach(Thread::start)
        workers.forEach { it.join(2_000) }
        assertFalse(workers.any(Thread::isAlive), "Plugin callbacks must never run under subscription locks")
        assertEquals(0, registry.snapshot().size)
    }

    @Test
    fun `callback can unregister itself without delivering a later event`() {
        val registry = McpObserverSubscriptions<() -> Unit>()
        var calls = 0
        registry.register("id") {
            calls++
            registry.unregister("id")
        }
        val snapshot = registry.snapshot()
        repeat(2) { snapshot.forEach { it.dispatch { callback -> callback() } } }
        assertEquals(1, calls)
    }
}
