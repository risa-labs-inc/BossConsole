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

        val activeObservers = mutableSetOf<String>()
        val registeredObservers = mutableListOf<String>()
        val unregisteredObservers = mutableListOf<String>()

        override fun registerMcpToolExecutionObserver(observer: ai.rever.boss.plugin.api.McpToolExecutionObserver) {
            registeredObservers += observer.observerId
            activeObservers += observer.observerId
        }

        override fun unregisterMcpToolExecutionObserver(observerId: String) {
            unregisteredObservers += observerId
            activeObservers -= observerId
        }

        override fun registerMcpToolProvider(provider: McpToolProvider) {
            registered += provider.providerId
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

    private fun observer(id: String) =
        object : ai.rever.boss.plugin.api.McpToolExecutionObserver {
            override val observerId = id

            override fun onExecutionStarted(request: ai.rever.boss.plugin.api.McpExecutionRequest) { /* no-op */ }

            override fun onExecutionFinished(
                request: ai.rever.boss.plugin.api.McpExecutionRequest,
                outcome: ai.rever.boss.plugin.api.McpExecutionOutcome,
            ) { /* no-op */ }
        }

    @Test
    fun `unregisterAll unregisters every MCP tool provider the plugin registered`() {
        val delegate = RecordingContext()
        val tracker = PluginRegistrationTracker()
        val tracking =
            TrackingPluginContext(
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
        val tracking =
            TrackingPluginContext(
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

    @Test
    fun `unregisterAll unregisters every MCP tool execution observer the plugin registered`() {
        val delegate = RecordingContext()
        val tracker = PluginRegistrationTracker()
        val tracking =
            TrackingPluginContext(
                pluginId = "test.plugin",
                delegate = delegate,
                tracker = tracker,
            )

        tracking.registerMcpToolExecutionObserver(observer("test.plugin.obs1"))
        tracking.registerMcpToolExecutionObserver(observer("test.plugin.obs2"))
        assertEquals(
            listOf("test.plugin.obs1", "test.plugin.obs2"),
            delegate.registeredObservers.map { it.substringAfter(':') },
        )

        tracking.unregisterAll()

        assertEquals(
            delegate.registeredObservers.toSet(),
            delegate.unregisteredObservers.toSet(),
            "every registered MCP execution observer must be unregistered on plugin teardown",
        )
    }

    @Test
    fun `explicitly unregistering an observer manually works and unregisterAll just repeats idempotently`() {
        val delegate = RecordingContext()
        val tracker = PluginRegistrationTracker()
        val tracking =
            TrackingPluginContext(
                pluginId = "test.plugin",
                delegate = delegate,
                tracker = tracker,
            )

        tracking.registerMcpToolExecutionObserver(observer("test.plugin.obs1"))
        tracking.unregisterMcpToolExecutionObserver("test.plugin.obs1")
        assertEquals(delegate.registeredObservers, delegate.unregisteredObservers)

        tracking.unregisterAll()
        assertEquals(delegate.registeredObservers + delegate.registeredObservers, delegate.unregisteredObservers)
    }

    @Test
    fun `equal observer ids in two plugins cannot unregister each other`() {
        val delegate = RecordingContext()
        val tracker = PluginRegistrationTracker()
        val a = TrackingPluginContext("a", delegate, tracker)
        val b = TrackingPluginContext("b", delegate, tracker)
        a.registerMcpToolExecutionObserver(observer("trace"))
        b.registerMcpToolExecutionObserver(observer("trace"))
        val first = delegate.registeredObservers.first()
        assertEquals(2, delegate.activeObservers.size)
        b.unregisterMcpToolExecutionObserver("trace")
        b.unregisterAll()
        assertEquals(setOf(first), delegate.activeObservers)
        a.unregisterAll()
        assertTrue(delegate.activeObservers.isEmpty())
    }
}
