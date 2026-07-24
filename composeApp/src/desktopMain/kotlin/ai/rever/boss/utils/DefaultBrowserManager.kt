package ai.rever.boss.utils

/**
 * Desktop implementation of DefaultBrowserManager
 *
 * Delegates to platform-specific handlers:
 * - MacOSDefaultBrowserHandler for macOS
 * - WindowsDefaultBrowserHandler for Windows
 * - LinuxDefaultBrowserHandler for Linux
 */
actual object DefaultBrowserManager {
    private val isMacOS = System.getProperty("os.name").lowercase().contains("mac")
    private val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    private val isLinux = !isMacOS && !isWindows

    /**
     * Check if BOSS is currently the default browser
     *
     * @return Result containing true if BOSS is default, false otherwise
     */
    actual suspend fun isDefaultBrowser(): Result<Boolean> =
        when {
            isMacOS -> MacOSDefaultBrowserHandler.isDefaultBrowser()
            isWindows -> WindowsDefaultBrowserHandler.isDefaultBrowser()
            isLinux -> LinuxDefaultBrowserHandler.isDefaultBrowser()
            else -> Result.failure(Exception("Unsupported platform: ${System.getProperty("os.name")}"))
        }

    /**
     * Set BOSS as the default system browser
     *
     * Platform-specific behavior:
     * - macOS: Attempts to set programmatically, may open System Preferences
     * - Windows: Opens Settings app for user to manually select (Windows 10+ requirement)
     * - Linux: Sets default via xdg-settings and xdg-mime
     *
     * @return Result with true if set programmatically, false if user action required
     */
    actual suspend fun setAsDefaultBrowser(): Result<Boolean> =
        when {
            isMacOS -> MacOSDefaultBrowserHandler.setAsDefaultBrowser()
            isWindows -> WindowsDefaultBrowserHandler.setAsDefaultBrowser()
            isLinux -> LinuxDefaultBrowserHandler.setAsDefaultBrowser()
            else -> Result.failure(Exception("Unsupported platform: ${System.getProperty("os.name")}"))
        }

    /**
     * Check if setting default browser is supported on this platform
     *
     * @return true if supported (all desktop platforms)
     */
    actual fun isSupported(): Boolean = isMacOS || isWindows || isLinux

    /**
     * Get platform-specific instructions for setting default browser
     *
     * @return User-friendly instructions
     */
    actual fun getInstructions(): String =
        when {
            isMacOS -> {
                """
                BOSS will attempt to register as your default browser automatically.

                If automatic registration fails, you can set it manually:
                - macOS Ventura and later: System Settings > Desktop & Dock > Default web browser
                - macOS Monterey and earlier: System Preferences > General > Default web browser
                """.trimIndent()
            }

            isWindows -> {
                """
                Windows requires manual selection of default browser for security.

                Steps:
                1. Click "Set as Default Browser" below
                2. Windows Settings will open
                3. Scroll down to "Web browser"
                4. Select "BOSS Console" from the list

                Note: BOSS will be registered as a browser candidate automatically.
                """.trimIndent()
            }

            isLinux -> {
                """
                BOSS will be registered using XDG standards (xdg-settings).

                If automatic registration fails, you can set it manually:
                1. Open your desktop environment's settings
                2. Find "Default Applications" or similar
                3. Select BOSS Console as the default web browser

                You may need to log out and back in for changes to take effect.
                """.trimIndent()
            }

            else -> {
                "Default browser setting is not supported on this platform."
            }
        }

    /**
     * Get the current platform name
     */
    fun getPlatformName(): String =
        when {
            isMacOS -> "macOS"
            isWindows -> "Windows"
            isLinux -> "Linux"
            else -> System.getProperty("os.name")
        }

    /**
     * Check if this is the first time setting up default browser
     *
     * Useful for showing setup dialogs or instructions
     */
    fun isFirstTimeSetup(): Boolean =
        when {
            isWindows -> {
                !WindowsDefaultBrowserHandler.isBrowserCandidateRegistered()
            }

            isLinux -> {
                val desktopFile =
                    java.io.File(
                        System.getProperty("user.home"),
                        ".local/share/applications/boss.desktop",
                    )
                !desktopFile.exists()
            }

            else -> {
                false
            }
        }
}
