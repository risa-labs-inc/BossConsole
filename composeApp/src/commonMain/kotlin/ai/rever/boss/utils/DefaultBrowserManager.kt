package ai.rever.boss.utils

/**
 * Cross-platform interface for managing default browser settings
 *
 * Allows BOSS Console to be set as the system's default web browser
 * for handling http:// and https:// URLs.
 *
 * Platform-specific behavior:
 * - macOS: Uses NSWorkspace API to set default programmatically
 * - Windows: Opens Settings app for user to select (Windows 10+ limitation)
 * - Linux: Uses xdg-settings and xdg-mime to set default
 */
expect object DefaultBrowserManager {
    /**
     * Check if BOSS is currently the default browser
     *
     * @return Result containing true if BOSS is default, false otherwise
     */
    suspend fun isDefaultBrowser(): Result<Boolean>

    /**
     * Set BOSS as the default system browser
     *
     * Returns:
     * - Success(true): Successfully set as default (macOS, Linux)
     * - Success(false): User action required (Windows Settings opened)
     * - Failure: Error occurred during the process
     */
    suspend fun setAsDefaultBrowser(): Result<Boolean>

    /**
     * Check if setting default browser is supported on this platform
     *
     * @return true if the feature is supported, false otherwise
     */
    fun isSupported(): Boolean

    /**
     * Get user-friendly message about how to set default browser on this platform
     *
     * @return Platform-specific instructions
     */
    fun getInstructions(): String
}
