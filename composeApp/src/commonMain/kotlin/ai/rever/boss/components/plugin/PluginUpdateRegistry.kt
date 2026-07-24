package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PanelId
import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A compatible newer version of an installed plugin, surfaced as a header badge.
 * (Only host-compatible updates land here; incompatible newer versions do not.)
 */
data class AvailablePluginUpdate(
    val pluginId: String,
    val displayName: String,
    val currentVersion: String,
    val newVersion: String,
)

/** Minimal reference to an installed plugin, used to drive update checks. */
data class InstalledPluginRef(
    val pluginId: String,
    val displayName: String,
    val version: String,
)

/** Outcome of an on-demand "check for updates" for a single plugin. */
sealed class UpdateCheckOutcome {
    data class Available(
        val displayName: String,
        val currentVersion: String,
        val newVersion: String,
    ) : UpdateCheckOutcome()

    data object UpToDate : UpdateCheckOutcome()

    /** A newer version exists but it requires a newer BOSS (IPC) than the host provides. */
    data class Incompatible(
        val advertisedLatest: String,
    ) : UpdateCheckOutcome()

    data class Error(
        val message: String,
    ) : UpdateCheckOutcome()
}

/**
 * Host-side, commonMain-visible registry of compatible plugin updates. Populated by the desktop
 * [PluginUpdateBridge] (which observes the desktopMain PluginUpdateManager). Sidebar plugin headers
 * observe this to show an "update available" badge.
 */
object PluginUpdateRegistry {
    private val _updates = MutableStateFlow<Map<String, AvailablePluginUpdate>>(emptyMap())
    val updates: StateFlow<Map<String, AvailablePluginUpdate>> = _updates.asStateFlow()

    // Atomic merge (not a blind replace) so a concurrent per-window checkOne() put can't be
    // clobbered by the startup refreshAll(). Merging is equivalent to replacing for the only
    // caller (refreshAll runs once at startup against an empty registry); entries are removed
    // explicitly via [clear] when a plugin is updated or found up to date.
    fun putAll(list: List<AvailablePluginUpdate>) {
        if (list.isEmpty()) return
        _updates.update { it + list.associateBy { u -> u.pluginId } }
    }

    // Atomic read-modify-write so concurrent put/clear (e.g. per-window checks) can't lose an update.
    fun put(update: AvailablePluginUpdate) {
        _updates.update { it + (update.pluginId to update) }
    }

    fun clear(pluginId: String) {
        _updates.update { it - pluginId }
    }
}

/**
 * Resolves a panel's owning pluginId. Provided by BossApp from the plugin
 * RegistrationTracker; defaults to null (no plugin / built-in panel).
 */
val LocalPanelPluginIdResolver = compositionLocalOf<(PanelId) -> String?> { { null } }
