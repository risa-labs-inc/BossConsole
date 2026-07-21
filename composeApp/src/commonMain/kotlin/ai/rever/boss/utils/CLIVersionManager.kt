package ai.rever.boss.utils

/**
 * Cross-platform CLI version manager interface
 *
 * Checks installed CLI version against current app version and manages automatic updates.
 */
expect object CLIVersionManager {
    /**
     * Extract version from installed CLI script
     *
     * @return Version string (e.g., "8.13.4") or null if not found or cannot be parsed
     */
    suspend fun getInstalledCLIVersion(): String?

    /**
     * Check if installed CLI version matches current app version
     *
     * @return true if versions match, false otherwise
     */
    suspend fun isCLIVersionCurrent(): Boolean

    /**
     * Check if CLI update is needed
     *
     * @return true if CLI is installed but version is outdated
     */
    suspend fun needsCLIUpdate(): Boolean
}
