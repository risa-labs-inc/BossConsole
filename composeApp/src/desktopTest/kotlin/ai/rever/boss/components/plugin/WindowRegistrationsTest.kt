package ai.rever.boss.components.plugin

import ai.rever.boss.components.plugin.WindowRegistrations.Outcome
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The arbitration rules of [WindowRegistrations], against a recording registry.
 *
 * [PluginRegistrationsAcrossWindowsTest] covers the same behaviour through two real `DefaultPlugin`
 * windows and the real registries; this pins the ordering cases a pair of windows cannot reach.
 */
class WindowRegistrationsTest {
    /** A registry that serves one value per id, like the real ones, and records every call. */
    private class RecordingRegistry(
        name: String = "recording",
    ) {
        val served = mutableMapOf<String, String>()
        val calls = mutableListOf<String>()
        val target =
            WindowRegistrations.Target<Pair<String, String>>(
                name = name,
                publish = { (id, value) ->
                    served[id] = value
                    calls += "publish $id=$value"
                },
                withdraw = { id ->
                    served.remove(id)
                    calls += "withdraw $id"
                },
            )
    }

    private val registry = RecordingRegistry()
    private val registrations = WindowRegistrations()
    private val window1 = WindowRegistrations.Owner()
    private val window2 = WindowRegistrations.Owner()
    private val window3 = WindowRegistrations.Owner()

    private fun register(
        window: WindowRegistrations.Owner,
        value: String,
        id: String = "tools",
    ) = registrations.register(registry.target, id, window, id to value)

    private fun unregister(
        window: WindowRegistrations.Owner,
        id: String = "tools",
    ) = registrations.unregister(registry.target, id, window)

    @Test
    fun `the newest registration is served`() {
        register(window1, "one")
        register(window2, "two")

        assertEquals("two", registry.served["tools"])
    }

    @Test
    fun `when the served window lets go, the previous window's registration is served again`() {
        register(window1, "one")
        register(window2, "two")

        assertEquals(Outcome.RESTORED_OTHER_WINDOW, unregister(window2))
        assertEquals("one", registry.served["tools"])
        assertEquals(listOf("publish tools=one", "publish tools=two", "publish tools=one"), registry.calls)
    }

    @Test
    fun `when an unserved window lets go, the registry is not touched`() {
        register(window1, "one")
        register(window2, "two")
        registry.calls.clear()

        assertEquals(Outcome.REMOVED_UNSERVED, unregister(window1))
        assertEquals("two", registry.served["tools"])
        assertEquals(emptyList(), registry.calls)
    }

    @Test
    fun `the last window to let go withdraws the id`() {
        register(window1, "one")
        register(window2, "two")
        unregister(window1)

        assertEquals(Outcome.WITHDRAWN, unregister(window2))
        assertEquals(null, registry.served["tools"])
    }

    @Test
    fun `a window with no registration changes nothing`() {
        register(window1, "one")
        registry.calls.clear()

        assertEquals(Outcome.NOT_REGISTERED_BY_WINDOW, unregister(window2))
        assertEquals("one", registry.served["tools"])
        assertEquals(emptyList(), registry.calls)
    }

    @Test
    fun `registering again from the same window replaces its entry and serves it`() {
        // A plugin reloaded in window 1 while window 2 also has it: the reload is the newest.
        register(window1, "one")
        register(window2, "two")
        register(window1, "one-reloaded")

        assertEquals("one-reloaded", registry.served["tools"])
        assertEquals(Outcome.RESTORED_OTHER_WINDOW, unregister(window1))
        assertEquals("two", registry.served["tools"])
        assertEquals(Outcome.WITHDRAWN, unregister(window2))
    }

    @Test
    fun `three windows closing out of order always serve the newest remaining registration`() {
        register(window1, "one")
        register(window2, "two")
        register(window3, "three")

        unregister(window2)
        assertEquals("three", registry.served["tools"])
        unregister(window3)
        assertEquals("one", registry.served["tools"])
        unregister(window1)
        assertEquals(null, registry.served["tools"])
    }

    @Test
    fun `when the served window lets go of several, the newest remaining one is served`() {
        register(window1, "one")
        register(window2, "two")
        register(window3, "three")

        assertEquals(Outcome.RESTORED_OTHER_WINDOW, unregister(window3))
        assertEquals("two", registry.served["tools"])
    }

    @Test
    fun `ids are arbitrated independently`() {
        register(window1, "one", id = "a")
        register(window2, "two", id = "b")

        assertEquals(Outcome.NOT_REGISTERED_BY_WINDOW, unregister(window2, id = "a"))
        assertEquals(Outcome.WITHDRAWN, unregister(window2, id = "b"))
        assertEquals(mapOf("a" to "one"), registry.served)
    }

    @Test
    fun `the same id in two registries is arbitrated separately`() {
        // Plugins reuse one id across registries: the Toolbox registers its MCP provider and its
        // deep-link handler under its plugin id.
        val other = RecordingRegistry(name = "other")
        registrations.register(registry.target, "shared", window1, "shared" to "tools")
        registrations.register(other.target, "shared", window2, "shared" to "links")

        assertEquals(Outcome.NOT_REGISTERED_BY_WINDOW, registrations.unregister(registry.target, "shared", window2))
        assertEquals(mapOf("shared" to "tools"), registry.served)
        assertEquals(mapOf("shared" to "links"), other.served)
    }

    @Test
    fun `releasing a closed window drops everything it still held`() {
        register(window1, "one", id = "a")
        register(window2, "two", id = "a")
        register(window2, "two", id = "b")

        registrations.release(window2)

        assertEquals(mapOf("a" to "one"), registry.served)
        assertEquals(Outcome.NOT_REGISTERED_BY_WINDOW, unregister(window2, id = "a"))
        // And a released window's entry can never come back once the survivor lets go.
        assertEquals(Outcome.WITHDRAWN, unregister(window1, id = "a"))
    }

    @Test
    fun `releasing an unrelated window does not wait for blocked plugin publication`() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val target =
            WindowRegistrations.Target<String>(
                "blocking",
                publish = { value ->
                    if (value == "one") {
                        entered.countDown()
                        check(resume.await(10, TimeUnit.SECONDS))
                    }
                },
                withdraw = {},
            )
        // Previously owning an id must not keep a dependency on it after unregister.
        registrations.register(target, "blocked", window2, "previous")
        registrations.unregister(target, "blocked", window2)
        try {
            val publisher = executor.submit { registrations.register(target, "blocked", window1, "one") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val release = executor.submit { registrations.release(window2) }
            // The callback is still blocked. This window never owned that slot, so its disposal
            // must finish without waiting for another window's plugin to return.
            release.get(2, TimeUnit.SECONDS)
            resume.countDown()
            publisher.get(5, TimeUnit.SECONDS)
        } finally {
            resume.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `a released window cannot replace the survivor or introduce a new id`() {
        register(window1, "one")
        register(window2, "two")
        registrations.release(window2)
        register(window2, "late")
        register(window2, "late-new", id = "new")
        assertEquals(mapOf("tools" to "one"), registry.served)
        registrations.release(window1)
        assertEquals(emptyMap(), registry.served)
    }

    @Test
    fun `preparation runs once per admitted registration and never during restore`() {
        var preparations = 0
        val target =
            WindowRegistrations.Target(
                name = "prepared",
                publish = registry.target.publish,
                withdraw = registry.target.withdraw,
                prepare = { value: Pair<String, String> ->
                    preparations++
                    value.first to value.second.uppercase()
                },
            )

        registrations.register(target, "tools", window1, "tools" to "one")
        registrations.register(target, "tools", window2, "tools" to "two")
        assertEquals(2, preparations)

        registrations.unregister(target, "tools", window2)

        assertEquals(2, preparations, "restoring the survivor must reuse its prepared value")
        assertEquals("ONE", registry.served["tools"])
    }

    @Test
    fun `a released window is rejected before preparation`() {
        var preparations = 0
        val target =
            WindowRegistrations.Target(
                name = "prepared",
                publish = registry.target.publish,
                withdraw = registry.target.withdraw,
                prepare = { value: Pair<String, String> ->
                    preparations++
                    value
                },
            )
        registrations.release(window1)

        registrations.register(target, "tools", window1, "tools" to "late")

        assertEquals(0, preparations)
        assertEquals(emptyMap(), registry.served)
    }

    @Test
    fun `release fences new registrations while an admitted publication finishes`() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val target =
            WindowRegistrations.Target<Pair<String, String>>(
                "blocking",
                publish = { value ->
                    if (value.second == "two") {
                        entered.countDown()
                        check(resume.await(10, TimeUnit.SECONDS))
                    }
                    registry.target.publish(value)
                },
                withdraw = registry.target.withdraw,
            )
        registrations.register(target, "tools", window1, "tools" to "one")
        try {
            val publisher = executor.submit { registrations.register(target, "tools", window2, "tools" to "two") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val release = executor.submit { registrations.release(window2) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!window2.released && System.nanoTime() < deadline) Thread.yield()
            assertTrue(window2.released)
            registrations.register(target, "new", window2, "new" to "late")
            resume.countDown()
            publisher.get(5, TimeUnit.SECONDS)
            release.get(5, TimeUnit.SECONDS)
            assertEquals(mapOf("tools" to "one"), registry.served)
        } finally {
            resume.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
