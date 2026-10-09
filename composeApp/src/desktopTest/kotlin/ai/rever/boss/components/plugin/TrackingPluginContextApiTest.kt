package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.sandbox.context.PluginApiRegistryLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TrackingPluginContextApiTest {
    private interface ExampleApi

    private class ExampleImpl : ExampleApi

    private open class RecordingContext :
        PluginContext,
        PluginApiRegistryLifecycle {
        override val panelRegistry = PanelRegistry()
        override val tabRegistry = TabRegistry()
        override val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val values = mutableMapOf<Class<*>, Any>()
        val removed = mutableListOf<Any>()

        override fun registerPluginAPI(api: Any) {
            api::class.java.interfaces.forEach { values[it] = api }
            values[api::class.java] = api
        }

        override fun unregisterPluginAPI(api: Any) {
            removed += api
            values.entries.removeIf { it.value === api }
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> getPluginAPI(apiClass: Class<T>): T? = values[apiClass] as? T
    }

    private class BlockingRecordingContext : RecordingContext() {
        val removalStarted = CountDownLatch(1)
        val allowRemoval = CountDownLatch(1)

        override fun unregisterPluginAPI(api: Any) {
            removalStarted.countDown()
            check(allowRemoval.await(5, TimeUnit.SECONDS)) { "test did not release API teardown" }
            super.unregisterPluginAPI(api)
        }
    }

    private class PlainContext : PluginContext {
        override val panelRegistry = PanelRegistry()
        override val tabRegistry = TabRegistry()
        override val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @Test
    fun `unregisterAll removes every API object registered by that plugin`() {
        val delegate = RecordingContext()
        val tracking = TrackingPluginContext("test.plugin", delegate, PluginRegistrationTracker())
        val api = ExampleImpl()
        tracking.registerPluginAPI(api)
        assertSame(api, delegate.getPluginAPI(ExampleApi::class.java))

        tracking.unregisterAll()

        assertEquals(listOf<Any>(api), delegate.removed)
        assertEquals(null, delegate.getPluginAPI(ExampleApi::class.java))
    }

    @Test
    fun `a second teardown does not unregister the same API twice`() {
        val delegate = RecordingContext()
        val tracking = TrackingPluginContext("test.plugin", delegate, PluginRegistrationTracker())
        val api = ExampleImpl()
        tracking.registerPluginAPI(api)

        tracking.unregisterAll()
        tracking.unregisterAll()

        assertEquals(listOf<Any>(api), delegate.removed)
    }

    @Test
    fun `registering the same object twice still tears it down once`() {
        val delegate = RecordingContext()
        val tracking = TrackingPluginContext("test.plugin", delegate, PluginRegistrationTracker())
        val api = ExampleImpl()
        tracking.registerPluginAPI(api)
        tracking.registerPluginAPI(api)

        tracking.unregisterAll()

        assertEquals(listOf<Any>(api), delegate.removed)
    }

    @Test
    fun `a delegate without lifecycle support fails visibly`() {
        val tracking = TrackingPluginContext("test.plugin", PlainContext(), PluginRegistrationTracker())
        tracking.registerPluginAPI(ExampleImpl())

        val error = assertFailsWith<IllegalStateException> { tracking.unregisterAll() }

        assertTrue(error.message.orEmpty().contains("PluginApiRegistryLifecycle"))
    }

    @Test
    fun `registration racing teardown cannot escape cleanup`() =
        runBlocking<Unit> {
            val delegate = BlockingRecordingContext()
            val tracking = TrackingPluginContext("test.plugin", delegate, PluginRegistrationTracker())
            val original = ExampleImpl()
            val late = ExampleImpl()
            tracking.registerPluginAPI(original)

            val teardown = async(Dispatchers.Default) { tracking.unregisterAll() }
            assertTrue(delegate.removalStarted.await(5, TimeUnit.SECONDS))
            val lateRegistration = async(Dispatchers.Default) { runCatching { tracking.registerPluginAPI(late) } }
            assertFalse(lateRegistration.isCompleted, "registration should wait for the in-flight teardown")

            delegate.allowRemoval.countDown()
            teardown.await()
            assertTrue(lateRegistration.await().isFailure)
            assertEquals(null, delegate.getPluginAPI(ExampleApi::class.java))
        }
}
