package ai.rever.boss.health

import ai.rever.boss.components.plugin.PluginHealthSnapshot
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.util.concurrent.ConcurrentHashMap

/**
 * What [WorkspaceHealthSources.pluginSnapshots] could read.
 *
 * [snapshots] holds one entry per window that answered, and [failedSources] counts the windows whose
 * source threw. The two together are what lets the report say "these findings are real, but they do
 * not cover every window" instead of dropping either half.
 */
internal data class PluginSnapshotRead(
    val snapshots: List<PluginHealthSnapshot>,
    val failedSources: Int = 0,
)

/**
 * Where the app-wide health report finds each window's plugin health.
 *
 * Every window owns its own `DynamicPluginManager`, created with that window's `DefaultPlugin`,
 * and nothing else enumerates them. A window registers a source when its `DefaultPlugin` is
 * created and unregisters it before that plugin is disposed.
 */
internal object WorkspaceHealthSources {
    private val pluginSources = ConcurrentHashMap<String, () -> PluginHealthSnapshot>()
    private val logger = BossLogger.forComponent("WorkspaceHealth")

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
        if (pluginSources.remove(windowId, source)) HealthSourceWarnings.recovered(sourceKey(windowId))
    }

    /**
     * One snapshot per registered window that could be read.
     *
     * Each window source is contained on its own, because a window whose manager is mid-disposal
     * must not hide a watchdog-stopped plugin in another window. A source that throws is logged and
     * counted in [PluginSnapshotRead.failedSources]; every other window's snapshot is kept.
     * LinkageError is caught alongside Exception for the same reason the collector catches it: a
     * class missing from one window's plugin path must not take the whole query down.
     */
    @Suppress("TooGenericExceptionCaught")
    fun pluginSnapshots(): PluginSnapshotRead {
        val snapshots = mutableListOf<PluginHealthSnapshot>()
        var failedSources = 0
        for ((windowId, source) in pluginSources) {
            try {
                snapshots += source()
                HealthSourceWarnings.recovered(sourceKey(windowId))
            } catch (e: Exception) {
                failedSources++
                logUnreadable(windowId, e)
            } catch (e: LinkageError) {
                failedSources++
                logUnreadable(windowId, e)
            }
        }
        return PluginSnapshotRead(snapshots, failedSources)
    }

    /** For tests. */
    fun clear() {
        pluginSources.clear()
        HealthSourceWarnings.clear()
    }

    /** Logged once per failing window until it reads cleanly again; see [HealthSourceWarnings]. */
    private fun logUnreadable(
        windowId: String,
        error: Throwable,
    ) {
        if (!HealthSourceWarnings.failed(sourceKey(windowId))) return
        logger.warn(
            LogCategory.SYSTEM,
            "Plugin health source could not be read for one window",
            mapOf("windowId" to windowId, "error" to (error.message ?: error::class.simpleName)),
        )
    }
}

private fun sourceKey(windowId: String) = "plugins:$windowId"
