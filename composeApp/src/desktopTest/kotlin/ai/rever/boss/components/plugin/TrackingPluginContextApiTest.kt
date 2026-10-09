package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.sandbox.context.PluginApiRegistryLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class TrackingPluginContextApiTest {
    private interface ExampleApi

    private class ExampleImpl : ExampleApi

    private class RecordingContext :
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
}
