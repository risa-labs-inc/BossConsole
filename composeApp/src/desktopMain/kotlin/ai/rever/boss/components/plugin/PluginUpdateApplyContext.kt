package ai.rever.boss.components.plugin

import ai.rever.boss.updater.UpdateSettings

/** Activation is chosen again after download, before any running classloader is touched. */
internal class PluginUpdateApplyContext(
    val pluginId: String,
    val manager: DynamicPluginManager,
    private val automatic: Boolean,
    var deferred: Boolean,
    private val previousPath: String?,
    private val prepareArtifact: () -> Result<Unit> = { Result.success(Unit) },
) {
    suspend fun unload(id: String): Result<Unit> {
        val decision =
            if (automatic) {
                automaticActivationAfterDownload(
                    automaticPluginUpdatePlan(id, manager),
                    UpdateSettings.isPluginAutomaticUpdateEnabled(pluginId),
                )
            } else {
                Result.success(deferred)
            }
        return decision.fold(
            onFailure = { Result.failure(it) },
            onSuccess = { stage ->
                deferred = stage
                prepareArtifact().fold(
                    onFailure = { Result.failure(it) },
                    onSuccess = {
                        if (deferred) Result.success(Unit) else manager.uninstallPlugin(id, force = true).map { }
                    },
                )
            },
        )
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

internal class PluginViewsBusyException : IllegalStateException("Waiting for plugin views to close")

internal fun automaticActivationAfterDownload(
    plan: AutomaticPluginUpdatePlan,
    enabled: Boolean = true,
): Result<Boolean> =
    when {
        !enabled -> Result.failure(IllegalStateException("Automatic plugin updates were disabled"))
        plan == AutomaticPluginUpdatePlan.WAIT -> Result.failure(PluginViewsBusyException())
        plan == AutomaticPluginUpdatePlan.STAGE -> Result.success(true)
        else -> Result.success(false)
    }
