package ai.rever.boss.utils

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Desktop implementation of CLI version manager
 *
 * Checks installed CLI version against current app version and triggers
 * silent updates when needed to keep CLI scripts synchronized.
 */
actual object CLIVersionManager {
    private val logger = BossLogger.forComponent("CLIVersionManager")

    private val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    private val homeDir = System.getProperty("user.home")

    /**
     * Get the installation path for CLI script
     */
    private fun getInstallPath(): String =
        if (isWindows) {
            "$homeDir\\bin\\boss.bat"
        } else {
            "$homeDir/.local/bin/boss"
        }

    /**
     * Extract version from installed CLI script
     *
     * Parses the version header comment in the format:
     * - bash: # Version: 8.13.4
     * - batch: REM Version: 8.13.4
     * - PowerShell: Version: 8.13.4 (in SYNOPSIS block)
     *
     * @return Version string (e.g., "8.13.4") or null if not found or cannot be parsed
     */
    actual suspend fun getInstalledCLIVersion(): String? =
        withContext(Dispatchers.IO) {
            val scriptPath = getInstallPath()
            val scriptFile = File(scriptPath)

            if (!scriptFile.exists()) {
                logger.debug(LogCategory.SYSTEM, "CLI version check: Script not found", mapOf("path" to scriptPath))
                return@withContext null
            }

            try {
                scriptFile.readLines().take(20).forEach { line ->
                    // Match version in comment header
                    // Examples:
                    //   # Version: 8.13.4
                    //   REM Version: 8.13.4
                    //   Version: 8.13.4
                    val versionMatch =
                        Regex("Version:\\s*([0-9]+\\.[0-9]+\\.[0-9]+)")
                            .find(line)

                    if (versionMatch != null) {
                        val version = versionMatch.groupValues[1]
                        logger.debug(LogCategory.SYSTEM, "CLI version check: Found installed version", mapOf("version" to version))
                        return@withContext version
                    }
                }

                logger.debug(LogCategory.SYSTEM, "CLI version check: No version found in script header")
                null
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "CLI version check failed", error = e)
                null
            }
        }

    /**
     * Check if installed CLI version matches current app version
     *
     * @return true if versions match, false otherwise
     */
    actual suspend fun isCLIVersionCurrent(): Boolean {
        val installedVersion = getInstalledCLIVersion() ?: return false
        val currentVersion = AppVersion.CURRENT.toString()

        val isCurrent = installedVersion == currentVersion
        logger.debug(
            LogCategory.SYSTEM,
            "CLI version check",
            mapOf(
                "installed" to installedVersion,
                "current" to currentVersion,
                "upToDate" to isCurrent,
            ),
        )

        return isCurrent
    }

    /**
     * Check if CLI update is needed
     *
     * @return true if CLI is installed but version is outdated
     */
    actual suspend fun needsCLIUpdate(): Boolean {
        // Only update if CLI is installed - don't auto-install if user hasn't installed
        if (!CLIInstaller.isInstalled()) {
            logger.debug(LogCategory.SYSTEM, "CLI version check: CLI not installed, skipping update")
            return false
        }

        val needsUpdate = !isCLIVersionCurrent()
        if (needsUpdate) {
            val installedVersion = getInstalledCLIVersion() ?: "unknown"
            logger.info(
                LogCategory.SYSTEM,
                "CLI version check: Update needed",
                mapOf(
                    "from" to installedVersion,
                    "to" to AppVersion.CURRENT.toString(),
                ),
            )
        }

        return needsUpdate
    }
}
