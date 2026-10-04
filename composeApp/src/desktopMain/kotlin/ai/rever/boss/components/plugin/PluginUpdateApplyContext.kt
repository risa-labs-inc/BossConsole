package ai.rever.boss.components.plugin

import ai.rever.boss.updater.UpdateSettings

/** Activation is chosen again after download, before any running classloader is touched. */
internal class PluginUpdateApplyContext(
    val pluginId: String,
    val manager: DynamicPluginManager,
    private val automatic: Boolean,
    var deferred: Boolean,
    private val previousPath: String?,
) {
    var activationStarted: Boolean = false

    suspend fun unload(id: String): Result<Unit> {
        if (automatic && !UpdateSettings.isPluginAutomaticUpdateEnabled(pluginId)) {
            return Result.failure(IllegalStateException("Automatic plugin updates were disabled"))
        }
        if (automatic && automaticPluginUpdatePlan(id, manager) != AutomaticPluginUpdatePlan.RELOAD) {
            deferred = true
        }
        return if (deferred) Result.success(Unit) else manager.uninstallPlugin(id, force = true).map { }
    }

    suspend fun load(
        path: String,
        fallback: suspend () -> Result<Unit>,
    ): Result<Unit> =
        if (automatic && !deferred) {
            activateAutomaticPluginUpdate(pluginId, path, previousPath, manager)
        } else {
            fallback()
        }
}
