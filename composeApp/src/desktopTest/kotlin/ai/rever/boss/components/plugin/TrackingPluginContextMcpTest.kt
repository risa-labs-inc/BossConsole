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
 * Also pins #926's registration scoping: the provider id a plugin hands over is
 * namespaced with the plugin's own id before it reaches the delegate, because
 * the id is the ONLY key the shared registry has - passed through raw, a
 * plugin could re-register (silently replacing) or unregister the host's
 * "boss-workspace" tools, or any other plugin's.
 *
 * Uses a fake delegate context instead of the process-wide
 * McpToolRegistryImpl so the test can observe exactly which providerIds were
 * registered and unregistered without touching live singleton state.
 */
class TrackingPluginContextMcpTest {
    /** Fake delegate that records MCP provider register/unregister calls. */
    private class RecordingContext : PluginContext {
        override val panelRegistry = PanelRegistry()
        override val tabRegistry = TabRegistry()
        override val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val registered = mutableListOf<String>()
        val unregistered = mutableListOf<String>()
        val toolNames = mutableListOf<String>()

        override fun registerMcpToolProvider(provider: McpToolProvider) {
            registered += provider.providerId
            toolNames += provider.tools().map { it.name }
        }

        override fun unregisterMcpToolProvider(providerId: String) {
            unregistered += providerId
        }
    }

    private fun provider(id: String) =
        object : McpToolProvider {
            override val providerId = id

            override fun tools() =
                listOf(
                    McpToolDefinition(
                        name = "tool_of_$id",
                        description = "test",
                        handler = McpToolHandler { McpToolResult("ok") },
                    ),
                )
        }

    @Test
    fun `unregisterAll unregisters every MCP tool provider the plugin registered`() {
        val delegate = RecordingContext()
        val tracking =
            TrackingPluginContext(
                pluginId = "test.plugin",
                delegate = delegate,
                tracker = PluginRegistrationTracker(),
            )

        tracking.registerMcpToolProvider(provider("test.plugin"))
        tracking.registerMcpToolProvider(provider("test.plugin.extra"))
        // Every id the delegate sees is namespaced with the plugin's own id (#926) - the
        // registry key decides who a later unregister or same-id re-registration takes out.
        assertEquals(
            listOf("test.plugin::test.plugin", "test.plugin::test.plugin.extra"),
            delegate.registered,
        )

        tracking.unregisterAll()

        assertEquals(
            setOf("test.plugin::test.plugin", "test.plugin::test.plugin.extra"),
            delegate.unregistered.toSet(),
            "every registered MCP provider must be unregistered on plugin teardown",
        )
    }

    @Test
    fun `unregisterAll clears the tracker so a second call does not re-unregister`() {
        val delegate = RecordingContext()
        val tracking =
            TrackingPluginContext(
                pluginId = "test.plugin",
                delegate = delegate,
                tracker = PluginRegistrationTracker(),
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

        assertTrue("plugin.a::plugin.a" in delegate.unregistered)
        assertTrue(
            "plugin.b::plugin.b" !in delegate.unregistered,
            "plugin B's provider must survive plugin A's teardown",
        )
    }

    @Test
    fun `a provider registered under the host id boss-workspace is namespaced not passed through raw`() {
        val delegate = RecordingContext()
        val tracking =
            TrackingPluginContext(
                pluginId = "hostile.plugin",
                delegate = delegate,
                tracker = PluginRegistrationTracker(),
            )

        tracking.registerMcpToolProvider(provider("boss-workspace"))

        // The raw host id never reaches the delegate: re-registering or unregistering
        // "boss-workspace" through the registry can only hit the plugin's own entry (#926).
        assertTrue("boss-workspace" !in delegate.registered)
        assertEquals(listOf("hostile.plugin::boss-workspace"), delegate.registered)
        // The provider's tools still flow through the wrapper, unchanged by name.
        assertEquals(listOf("tool_of_boss-workspace"), delegate.toolNames)
    }

    @Test
    fun `unregister re-keys into the plugin's own namespace so host and rival providers survive`() {
        val delegate = RecordingContext()
        val tracker = PluginRegistrationTracker()
        val hostile = TrackingPluginContext("hostile.plugin", delegate, tracker)
        val rival = TrackingPluginContext("rival.plugin", delegate, tracker)

        rival.registerMcpToolProvider(provider("rival.tools"))
        hostile.registerMcpToolProvider(provider("hostile.tools"))

        // The plugin aims an unregister at the host's provider id and at a rival's raw id.
        hostile.unregisterMcpToolProvider("boss-workspace")
        hostile.unregisterMcpToolProvider("rival.tools")

        assertEquals(
            listOf("hostile.plugin::boss-workspace", "hostile.plugin::rival.tools"),
            delegate.unregistered,
            "an unregister must only ever land inside the plugin's own namespace (#926)",
        )
        assertTrue("boss-workspace" !in delegate.unregistered)
        assertTrue("rival.plugin::rival.tools" !in delegate.unregistered)

        // A plugin unregistering its OWN raw id still works - it is re-keyed to its namespace.
        hostile.unregisterMcpToolProvider("hostile.tools")
        assertTrue("hostile.plugin::hostile.tools" in delegate.unregistered)
    }

    @Test
    fun `two plugins registering the same raw id land on distinct scoped ids`() {
        val delegate = RecordingContext()
        val tracker = PluginRegistrationTracker()
        val pluginA = TrackingPluginContext("plugin.a", delegate, tracker)
        val pluginB = TrackingPluginContext("plugin.b", delegate, tracker)

        pluginA.registerMcpToolProvider(provider("shared"))
        pluginB.registerMcpToolProvider(provider("shared"))

        assertEquals(
            listOf("plugin.a::shared", "plugin.b::shared"),
            delegate.registered,
            "the same raw id from two plugins must not replace each other in the shared registry",
        )

        pluginA.unregisterAll()

        assertTrue("plugin.a::shared" in delegate.unregistered)
        assertTrue("plugin.b::shared" !in delegate.unregistered)
    }
}
