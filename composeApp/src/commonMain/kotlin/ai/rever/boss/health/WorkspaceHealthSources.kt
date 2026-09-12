package ai.rever.boss.health

import ai.rever.boss.components.plugin.PluginHealthSnapshot
import java.util.concurrent.ConcurrentHashMap

/**
 * Where the app-wide health report finds each window's plugin health.
 *
 * Every window owns its own `DynamicPluginManager`, created with that window's `DefaultPlugin`,
 * and nothing else enumerates them. A window registers a source when its `DefaultPlugin` is
 * created and unregisters it before that plugin is disposed.
 */
internal object WorkspaceHealthSources {
    private val pluginSources = ConcurrentHashMap<String, () -> PluginHealthSnapshot>()

    fun registerPlugins(
        windowId: String,
        source: () -> PluginHealthSnapshot,
    ) {
        pluginSources[windowId] = source
    }

    /**
     * Remove [source] only while it is still the one registered for [windowId], so an effect that
     * re-runs for the same window cannot remove the registration that replaced it.
     */
    fun unregisterPlugins(
        windowId: String,
        source: () -> PluginHealthSnapshot,
    ) {
        pluginSources.remove(windowId, source)
    }

    /** One snapshot per registered window. A source that throws propagates; the collector contains it. */
    fun pluginSnapshots(): List<PluginHealthSnapshot> = pluginSources.values.map { it() }

    /** For tests. */
    fun clear() {
        pluginSources.clear()
    }
}
