package ai.rever.boss.plugin.updater

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Information about an available plugin update.
 */
@Serializable
data class UpdateInfo(
    /**
     * Plugin ID.
     */
    @SerialName("pluginId")
    val pluginId: String,
    /**
     * Plugin display name.
     */
    @SerialName("displayName")
    val displayName: String,
    /**
     * Currently installed version.
     */
    @SerialName("currentVersion")
    val currentVersion: String,
    /**
     * Available new version.
     */
    @SerialName("newVersion")
    val newVersion: String,
    /**
     * Changelog for the new version.
     */
    @SerialName("changelog")
    val changelog: String = "",
    /**
     * Size of the update in bytes.
     */
    @SerialName("size")
    val size: Long = 0,
    /**
     * Whether this is a critical/security update.
     */
    @SerialName("critical")
    val critical: Boolean = false,
    /**
     * Release date of the new version.
     */
    @SerialName("releaseDate")
    val releaseDate: Long = 0,
    /**
     * Download URL for the new version.
     */
    @SerialName("downloadUrl")
    val downloadUrl: String = "",
    /**
     * Whether this update requires a restart.
     */
    @SerialName("requiresRestart")
    val requiresRestart: Boolean = false,
)

/**
 * A newer version of an installed plugin exists in the repository but the
 * running host cannot load it — either its `minIpcVersion` exceeds the host's
 * IPC contract, or its `minBossVersion` exceeds the host application version.
 * Surfaced instead of an installable [UpdateInfo] so the UI can tell the user
 * "an update exists but requires a host upgrade" rather than silently
 * installing a plugin that would fail at load.
 *
 * Exactly one pair of (requiredIpcVersion/hostIpcVersion,
 * requiredBossVersion/hostBossVersion) is populated — the one naming the
 * rejection reason; the other pair is "".
 */
@Serializable
data class IncompatibleNotice(
    @SerialName("pluginId")
    val pluginId: String,
    @SerialName("displayName")
    val displayName: String,
    @SerialName("currentVersion")
    val currentVersion: String,
    /** The newer version that exists but the host can't load. */
    @SerialName("advertisedLatest")
    val advertisedLatest: String,
    /** IPC contract version that [advertisedLatest] requires. */
    @SerialName("requiredIpcVersion")
    val requiredIpcVersion: String = "",
    /** The host's current IPC contract version. */
    @SerialName("hostIpcVersion")
    val hostIpcVersion: String = "",
    /** Minimum BOSS host version that [advertisedLatest] requires. */
    @SerialName("requiredBossVersion")
    val requiredBossVersion: String = "",
    /** The running host application version. */
    @SerialName("hostBossVersion")
    val hostBossVersion: String = "",
    /** Minimum boss-plugin-api (runtime API layer) version that [advertisedLatest] requires. */
    @SerialName("requiredApiVersion")
    val requiredApiVersion: String = "",
    /** The installed runtime API layer version. */
    @SerialName("hostApiVersion")
    val hostApiVersion: String = "",
)

/**
 * State of an update operation.
 */
sealed class UpdateState {
    /**
     * No update in progress.
     */
    data object Idle : UpdateState()

    /**
     * Checking for updates.
     */
    data object Checking : UpdateState()

    /**
     * Downloading update.
     */
    data class Downloading(
        val pluginId: String,
        val progress: Float,
    ) : UpdateState()

    /**
     * Installing update.
     */
    data class Installing(
        val pluginId: String,
    ) : UpdateState()

    /**
     * Update completed.
     */
    data class Completed(
        val pluginId: String,
        val newVersion: String,
    ) : UpdateState()

    /**
     * Update failed.
     */
    data class Failed(
        val pluginId: String,
        val error: String,
        val exception: Throwable? = null,
    ) : UpdateState()

    /**
     * A newer version was found but is incompatible with the host's IPC
     * contract, so it was not installed.
     */
    data class HostIncompatible(
        val pluginId: String,
        val newVersion: String,
        val requiredIpcVersion: String,
        val hostIpcVersion: String,
    ) : UpdateState()
}

/**
 * Result of checking for updates.
 */
data class UpdateCheckResult(
    /**
     * Available updates.
     */
    val availableUpdates: List<UpdateInfo>,
    /**
     * Plugins that failed to check.
     */
    val failedChecks: Map<String, String>,
    /**
     * Newer versions that exist but the host can't load (IPC-incompatible).
     * These are reported, never auto-installed.
     */
    val incompatibleNotices: List<IncompatibleNotice> = emptyList(),
    /**
     * Timestamp when the check was performed.
     */
    val checkedAt: Long = System.currentTimeMillis(),
) {
    val hasUpdates: Boolean get() = availableUpdates.isNotEmpty()
    val hasCriticalUpdates: Boolean get() = availableUpdates.any { it.critical }
}

/**
 * Configuration for the update checker.
 */
data class UpdateCheckerConfig(
    /**
     * Interval between automatic update checks in milliseconds.
     * Set to 0 to disable automatic checks.
     */
    val checkIntervalMs: Long = 24 * 60 * 60 * 1000, // 24 hours
    /**
     * Whether to check for updates on startup.
     */
    val checkOnStartup: Boolean = true,
    /**
     * Whether to notify for non-critical updates.
     */
    val notifyNonCritical: Boolean = true,
    /**
     * Whether to auto-download updates (but not install).
     */
    val autoDownload: Boolean = false,
    /**
     * Whether to include pre-release versions.
     */
    val includePrerelease: Boolean = false,
)
