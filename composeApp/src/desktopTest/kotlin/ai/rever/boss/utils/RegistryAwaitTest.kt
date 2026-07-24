package ai.rever.boss.utils

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [awaitRegistryCondition] — the bounded wait used by workspace
 * restore and panel-open handling to survive the startup race where dynamic
 * plugins register their tab types / panels after consumers first look them up.
 */
class RegistryAwaitTest {
    /** Minimal stand-in for TabRegistry/PanelRegistry's listener contract. */
    private class FakeRegistry {
        private val listeners = mutableListOf<() -> Unit>()

        fun addListener(listener: () -> Unit) {
            listeners.add(listener)
        }

        fun removeListener(listener: () -> Unit) {
            listeners.remove(listener)
        }

        fun notifyChange() {
            listeners.forEach { it() }
        }

        val listenerCount get() = listeners.size
    }

    @Test
    fun `returns immediately when condition already holds`() =
        runBlocking {
            val registry = FakeRegistry()
            val result =
                awaitRegistryCondition(
                    registry::addListener,
                    registry::removeListener,
                    timeoutMs = 10_000,
                ) { true }
            assertTrue(result)
            assertEquals(0, registry.listenerCount, "no listener should linger")
        }

    @Test
    fun `completes when a registry change satisfies the condition`() =
        runBlocking {
            val registry = FakeRegistry()
            var registered = false

            val waiter =
                async {
                    awaitRegistryCondition(
                        registry::addListener,
                        registry::removeListener,
                        timeoutMs = 10_000,
                    ) { registered }
                }
            // Let the waiter subscribe before firing the change.
            while (registry.listenerCount == 0) yield()

            registered = true
            registry.notifyChange()

            assertTrue(waiter.await())
            assertEquals(0, registry.listenerCount, "listener must be removed after completion")
        }

    @Test
    fun `notifications that do not satisfy the condition keep waiting until one does`() =
        runBlocking {
            val registry = FakeRegistry()
            var registrationsSeen = 0

            val waiter =
                async {
                    awaitRegistryCondition(
                        registry::addListener,
                        registry::removeListener,
                        timeoutMs = 10_000,
                    ) { registrationsSeen >= 2 }
                }
            while (registry.listenerCount == 0) yield()

            registrationsSeen = 1
            registry.notifyChange()
            assertFalse(waiter.isCompleted)

            registrationsSeen = 2
            registry.notifyChange()
            assertTrue(waiter.await())
        }

    @Test
    fun `returns false on timeout and removes the listener`() =
        runBlocking {
            val registry = FakeRegistry()
            val result =
                awaitRegistryCondition(
                    registry::addListener,
                    registry::removeListener,
                    timeoutMs = 50,
                ) { false }
            assertFalse(result)
            assertEquals(0, registry.listenerCount, "listener must be removed after timeout")
        }
}
