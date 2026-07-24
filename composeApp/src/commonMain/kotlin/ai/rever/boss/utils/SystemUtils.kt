package ai.rever.boss.utils

expect object SystemUtils {
    fun getUserHome(): String

    fun getCurrentDirectory(): String

    fun getDefaultProjectPath(): String

    /**
     * Returns true if the current platform is macOS.
     * Used for platform-aware keyboard shortcut handling.
     */
    val isMacOS: Boolean

    /**
     * Returns true if the current platform is Windows.
     */
    val isWindows: Boolean

    /**
     * Returns true if the current platform is Linux.
     */
    val isLinux: Boolean
}
