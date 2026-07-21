package ai.rever.boss.updater

/**
 * Platform-specific update settings
 *
 * Provides access to update configuration that persists across app restarts.
 * Actual implementations handle platform-specific storage (e.g., File I/O on desktop).
 */
expect object UpdateSettings {
    /**
     * Whether automatic update checks are enabled
     */
    var autoCheckEnabled: Boolean

    /**
     * Interval between automatic update checks in hours
     */
    var checkIntervalHours: Long

    /**
     * Whether to include pre-release versions (alpha, beta, RC) in update checks.
     * When false (default), only stable releases are shown.
     * When true, or when the current version is a pre-release, pre-releases are included.
     */
    var includePreReleases: Boolean

    /**
     * The version string the user last dismissed an update prompt for.
     * Automatic checks won't re-surface this exact version; any different
     * available version (normally a newer release, but also a server-side
     * rollback) or a manual/forced check will prompt again. Null when nothing
     * is dismissed.
     */
    var lastDismissedVersion: String?
}

/**
 * Platform-specific settings manager for persisting update preferences
 */
expect object UpdateSettingsManager {
    /**
     * Save current settings to persistent storage
     */
    suspend fun saveSettings()
}
