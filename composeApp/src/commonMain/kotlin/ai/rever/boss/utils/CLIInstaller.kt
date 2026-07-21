package ai.rever.boss.utils

/**
 * Result of CLI installation operation
 */
data class CLIInstallResult(
    val success: Boolean,
    val installPath: String? = null,
    val shellConfigPath: String? = null,
    val message: String,
    val requiresRestart: Boolean = true
)

/**
 * Cross-platform CLI installer interface
 *
 * Installs the BOSS CLI scripts to the appropriate location and updates PATH configuration.
 */
expect object CLIInstaller {
    /**
     * Install the BOSS CLI script to the system
     *
     * On macOS/Linux: Installs to ~/.local/bin/boss and updates shell configuration
     * On Windows: Installs to %USERPROFILE%\bin\boss.bat and updates PATH environment variable
     *
     * @return Result containing success status, paths, and user-facing message
     */
    suspend fun installCLI(): CLIInstallResult

    /**
     * Check if CLI is already installed
     *
     * @return true if the CLI script exists in the expected location
     */
    fun isInstalled(): Boolean
}
