package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.plugin.sandbox.ui.PluginUiMountRegistry

internal enum class AutomaticPluginUpdatePlan { WAIT, STAGE, RELOAD }

/** Missing view counts fail closed; automatic updates must never reset live plugin views. */
internal fun automaticPluginUpdatePlan(
    pluginId: String,
    manager: DynamicPluginManager,
): AutomaticPluginUpdatePlan {
    val count =
        DependentRestartCoordinator.instanceCount
            ?.invoke(pluginId)
            ?.let { if (PluginUiMountRegistry.isMounted(pluginId)) maxOf(1, it) else it }
    val active = DynamicPluginManager.activeManagers()
    return automaticPluginUpdatePlan(
        openViews = count,
        needsRestart = HotReloadPolicy.requiresRestartInsteadOfHotReload(pluginId),
        hasDependents = active.any { it.dependentsOf(pluginId).isNotEmpty() },
        multipleWindows = active.size > 1,
        disabled = manager.getPluginInfo(pluginId)?.state != PluginState.LOADED,
    )
}

internal fun automaticPluginUpdatePlan(
    openViews: Int?,
    needsRestart: Boolean,
    hasDependents: Boolean,
    multipleWindows: Boolean,
    disabled: Boolean,
): AutomaticPluginUpdatePlan =
    when {
        openViews == null || openViews > 0 -> AutomaticPluginUpdatePlan.WAIT
        needsRestart || hasDependents || multipleWindows || disabled -> AutomaticPluginUpdatePlan.STAGE
        else -> AutomaticPluginUpdatePlan.RELOAD
    }

internal fun initialPluginUpdatePlan(
    pluginId: String,
    manager: DynamicPluginManager,
    automatic: Boolean,
): Result<Boolean> =
    when {
        !automatic -> {
            Result.success(
                manager.getPluginInfo(pluginId)?.state == PluginState.LOADED &&
                    HotReloadPolicy.requiresRestartInsteadOfHotReload(pluginId),
            )
        }

        !ai.rever.boss.updater.UpdateSettings
            .isPluginAutomaticUpdateEnabled(pluginId) -> {
            Result.failure(IllegalStateException("Automatic plugin updates are disabled"))
        }

        else -> {
            when (automaticPluginUpdatePlan(pluginId, manager)) {
                AutomaticPluginUpdatePlan.WAIT -> {
                    Result.failure(IllegalStateException("Waiting for plugin views to close"))
                }

                AutomaticPluginUpdatePlan.STAGE -> {
                    Result.success(true)
                }

                AutomaticPluginUpdatePlan.RELOAD -> {
                    Result.success(false)
                }
            }
        }
    }
