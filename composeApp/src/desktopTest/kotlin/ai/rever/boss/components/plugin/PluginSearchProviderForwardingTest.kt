package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginSearchResult
import ai.rever.boss.plugin.api.SearchProvider
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.sandbox.InProcessPluginSandbox
import ai.rever.boss.plugin.sandbox.context.SandboxedPanelRegistry
import ai.rever.boss.plugin.sandbox.context.SandboxedPluginContext
import ai.rever.boss.plugin.sandbox.context.SandboxedTabRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A plugin's [SearchProvider] must reach the host through the context a dynamic plugin is actually
 * given. `DynamicPluginManager` hands `register()` a [TrackingPluginContext] around a
 * [SandboxedPluginContext] around the window's `DefaultPlugin`, and only `DefaultPlugin` put the
 * provider into `SearchRegistryImpl`. Neither wrapper overrode `registerSearchProvider`, so the call
 * reached the `PluginContext` default, which does nothing: the bookmarks plugin's provider was never
 * registered, and `GlobalSearchService`, whose only bookmark source is that registry, had none.
 *
 * The chain here is the production one with `DefaultPlugin` replaced by a recording host.
 */
class PluginSearchProviderForwardingTest {
    /** Stands in for `DefaultPlugin`: records what reaches the host. */
    private class RecordingHost : PluginContext {
        override val panelRegistry = PanelRegistry()
        override val tabRegistry = TabRegistry()
        override val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val registered = mutableListOf<SearchProvider>()
        val unregistered = mutableListOf<String>()

        override fun registerSearchProvider(provider: SearchProvider) {
            registered += provider
        }

        override fun unregisterSearchProvider(providerId: String) {
            unregistered += providerId
        }
    }

    private fun provider(id: String) =
        object : SearchProvider {
            override val providerId = id
            override val displayName = id

            override suspend fun search(
                query: String,
                limit: Int,
            ): List<PluginSearchResult> = emptyList()
        }

    private fun pluginContext(
        pluginId: String,
        host: RecordingHost,
        tracker: PluginRegistrationTracker = PluginRegistrationTracker(),
    ): TrackingPluginContext {
        val sandbox = InProcessPluginSandbox(pluginId)
        val sandboxed =
            SandboxedPluginContext(
                _sandbox = sandbox,
                delegate = host,
                sandboxedPanelRegistry = SandboxedPanelRegistry(sandbox, host.panelRegistry),
                sandboxedTabRegistry = SandboxedTabRegistry(sandbox, host.tabRegistry),
            )
        return TrackingPluginContext(pluginId = pluginId, delegate = sandboxed, tracker = tracker)
    }

    @Test
    fun `a plugin's search provider reaches the host through both wrappers`() {
        val host = RecordingHost()
        val bookmarks = provider("bookmarks")

        pluginContext("plugin.bookmarks", host).registerSearchProvider(bookmarks)

        assertEquals(1, host.registered.size, "the provider never reached the host")
        assertSame(bookmarks, host.registered.single())
    }

    @Test
    fun `a plugin unregistering its search provider reaches the host`() {
        val host = RecordingHost()
        val context = pluginContext("plugin.bookmarks", host)

        context.registerSearchProvider(provider("bookmarks"))
        context.unregisterSearchProvider("bookmarks")

        assertEquals(listOf("bookmarks"), host.unregistered)
    }

    @Test
    fun `unregisterAll removes the search provider of a disabled or unloaded plugin`() {
        // Every disable/unload path in DynamicPluginManager calls unregisterAll. Without this the
        // registry would keep a provider whose classloader is closed.
        val host = RecordingHost()
        val context = pluginContext("plugin.bookmarks", host)

        context.registerSearchProvider(provider("bookmarks"))
        context.unregisterAll()

        assertEquals(listOf("bookmarks"), host.unregistered)
    }

    @Test
    fun `one plugin's teardown leaves another plugin's search provider registered`() {
        val host = RecordingHost()
        val tracker = PluginRegistrationTracker()
        val pluginA = pluginContext("plugin.a", host, tracker)
        val pluginB = pluginContext("plugin.b", host, tracker)

        pluginA.registerSearchProvider(provider("search.a"))
        pluginB.registerSearchProvider(provider("search.b"))
        pluginA.unregisterAll()

        assertEquals(listOf("search.a"), host.unregistered)
        assertTrue("search.b" !in host.unregistered, "plugin B's provider must survive plugin A's teardown")
    }
}
