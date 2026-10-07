package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CancellationException

internal suspend fun activateAutomaticPluginUpdate(
    pluginId: String,
    path: String,
    previousPath: String?,
    manager: DynamicPluginManager,
): Result<Unit> =
    applyAutomaticPluginUpdateWithRollback(
        install = {
            manager.installPlugin(path).mapCatching { info ->
                check(info.state == PluginState.LOADED) { "Updated plugin could not be enabled" }
            }
        },
        restore = {
            checkNotNull(previousPath) { "Previous plugin artifact is unavailable" }
            if (manager.getPluginInfo(pluginId) != null) {
                manager.uninstallPlugin(pluginId, force = true).getOrThrow()
            }
            manager.installPlugin(previousPath).mapCatching { info ->
                check(info.state == PluginState.LOADED) { "Previous plugin could not be restored" }
            }
        },
    )

/** Return the original failure after restoring the prior version; success never runs rollback. */
@Suppress("TooGenericExceptionCaught") // Plugin registration/restoration may throw arbitrary plugin exceptions.
internal suspend fun applyAutomaticPluginUpdateWithRollback(
    install: suspend () -> Result<Unit>,
    restore: suspend () -> Result<Unit>,
): Result<Unit> {
    val result =
        try {
            install()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    if (result.isSuccess) return result
    val rollback =
        try {
            restore()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    rollback.exceptionOrNull()?.let { error ->
        result.exceptionOrNull()?.addSuppressed(error)
        BossLogger.forComponent("AutomaticPluginUpdater").error(
            LogCategory.SYSTEM,
            "Failed to restore the previous plugin version",
            error = error,
        )
    }
    return result
}
