package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PluginLoaderDelegate
import androidx.compose.runtime.compositionLocalOf

/** A navigation request, never authorization to change plugin lifecycle. */
internal data class PluginRecoveryTarget(
    val pluginId: String,
    val reason: String? = null,
)

/** The initiating window's existing lifecycle delegate and Toolbox route. */
internal data class PluginRecoveryContext(
    val manager: DynamicPluginManager?,
    val delegate: PluginLoaderDelegate?,
    val openToolbox: () -> Boolean,
)

internal val LocalPluginRecoveryContext = compositionLocalOf<PluginRecoveryContext?> { null }

internal fun pluginSectionOffersRecovery(absence: PluginSectionAbsence): Boolean =
    when (absence) {
        PluginSectionAbsence.DISABLED,
        PluginSectionAbsence.FAILED,
        PluginSectionAbsence.INCOMPATIBLE,
        PluginSectionAbsence.NO_PANEL,
        -> true

        PluginSectionAbsence.NOT_INSTALLED,
        PluginSectionAbsence.NO_ACCESS,
        PluginSectionAbsence.STARTING,
        PluginSectionAbsence.UNKNOWN,
        -> false
    }

internal fun healthRowsForTarget(
    rows: List<PluginHealthRow>,
    target: PluginRecoveryTarget?,
): List<PluginHealthRow> = if (target == null) rows else rows.filter { it.pluginId == target.pluginId }
