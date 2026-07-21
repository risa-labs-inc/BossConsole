package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.TabRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins down the mechanism that makes a disabled/unloaded plugin's
 * `mcp__boss__*` tools disappear: [TrackingPluginContext] records each MCP
 * tool provider registration in [PluginRegistrationTracker], and
 * [TrackingPluginContext.unregisterAll] (called on every disable/unload path
 * in DynamicPluginManager) unregisters them all through the delegate.
 *
 * Uses a fake delegate context instead of the process-wide
 * McpToolRegistryImpl so the test can observe exactly which providerIds were
 * unregistered without touching live singleton state.
 */
class TrackingPluginContextMcpTest {

    /** Fake delegate that records MCP provider register/unregister calls. */
    private class RecordingContext : PluginContext {
        override val panelRegistry = PanelRegistry()
        override val tabRegistry = TabRegistry()
        override val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val registered = mutableListOf<String>()
        val unregistered = mutableListOf<String>()

        override fun registerMcpToolProvider(provider: McpToolProvider) {
            registered += provider.providerId
        }

        override fun unregisterMcpToolProvider(providerId: String) {
            unregistered += providerId
        }
    }

    private fun provider(id: String) = object : McpToolProvider {
        override val providerId = id
        override fun tools() = listOf(
            McpToolDefinition(
                name = "tool_of_$id",
                description = "test",
                handler = McpToolHandler { McpToolResult("ok") },
            )
        )
    }

    @Test
    fun `unregisterAll unregisters every MCP tool provider the plugin registered`() {
        val delegate = RecordingContext()
        val tracker = PluginRegistrationTracker()
        val tracking = TrackingPluginContext(
            pluginId = "test.plugin",
            delegate = delegate,
            tracker = tracker,
        )

        tracking.registerMcpToolProvider(provider("test.plugin"))
        tracking.registerMcpToolProvider(provider("test.plugin.extra"))
        assertEquals(listOf("test.plugin", "test.plugin.extra"), delegate.registered)

        tracking.unregisterAll()

        assertEquals(
            setOf("test.plugin", "test.plugin.extra"),
            delegate.unregistered.toSet(),
            "every registered MCP provider must be unregistered on plugin teardown",
        )
    }

    @Test
    fun `unregisterAll clears the tracker so a second call does not re-unregister`() {
        val delegate = RecordingContext()
        val tracker = PluginRegistrationTracker()
        val tracking = TrackingPluginContext(
            pluginId = "test.plugin",
            delegate = delegate,
            tracker = tracker,
        )

        tracking.registerMcpToolProvider(provider("test.plugin"))
        tracking.unregisterAll()
        tracking.unregisterAll()

        assertEquals(1, delegate.unregistered.size, "tracker must be cleared after teardown")
    }

    @Test
    fun `tracker isolates plugins - unregisterAll only tears down its own plugin's providers`() {
        val delegate = RecordingContext()
        val tracker = PluginRegistrationTracker()
        val pluginA = TrackingPluginContext("plugin.a", delegate, tracker)
        val pluginB = TrackingPluginContext("plugin.b", delegate, tracker)

        pluginA.registerMcpToolProvider(provider("plugin.a"))
        pluginB.registerMcpToolProvider(provider("plugin.b"))

        pluginA.unregisterAll()

        assertTrue("plugin.a" in delegate.unregistered)
        assertTrue("plugin.b" !in delegate.unregistered, "plugin B's provider must survive plugin A's teardown")
    }
}
