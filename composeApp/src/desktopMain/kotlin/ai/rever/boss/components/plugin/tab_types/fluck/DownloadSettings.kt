package ai.rever.boss.components.plugin.tab_types.fluck

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

private val downloadSettingsLogger = BossLogger.forComponent("DownloadSettings")

/**
 * Desktop implementation for getting the default downloads directory.
 * Respects platform conventions:
 * - Windows: %USERPROFILE%\Downloads
 * - macOS: $HOME/Downloads
 * - Linux: XDG user-dirs.dirs or $HOME/Downloads
 */
actual fun getDefaultDownloadsDirectory(): String {
    val osName = System.getProperty("os.name").lowercase()
    val userHome = System.getProperty("user.home")

    return when {
        // Linux: Try to read XDG user directories config
        osName.contains("linux") -> {
            getLinuxDownloadsDirectory(userHome)
        }

        // Windows: Use %USERPROFILE%\Downloads
        osName.contains("windows") -> {
            File(userHome, "Downloads").absolutePath
        }

        // macOS and others: Use $HOME/Downloads
        else -> {
            File(userHome, "Downloads").absolutePath
        }
    }
}

/**
 * Attempts to read the Linux XDG user directories config to find Downloads folder.
 * Falls back to $HOME/Downloads if config not found or readable.
 */
private fun getLinuxDownloadsDirectory(userHome: String): String {
    try {
        // Read ~/.config/user-dirs.dirs
        val userDirsFile = Paths.get(userHome, ".config", "user-dirs.dirs")

        if (Files.exists(userDirsFile)) {
            val lines = Files.readAllLines(userDirsFile)

            // Look for XDG_DOWNLOAD_DIR="$HOME/Downloads" or similar
            for (line in lines) {
                if (line.startsWith("XDG_DOWNLOAD_DIR=")) {
                    val path =
                        line
                            .substringAfter("=")
                            .trim()
                            .removeSurrounding("\"")
                            .replace("\$HOME", userHome)

                    if (Files.exists(Paths.get(path))) {
                        return path
                    }
                }
            }
        }
    } catch (e: Exception) {
        // Fall through to default
        downloadSettingsLogger.debug(LogCategory.BROWSER, "Could not read XDG user-dirs config", mapOf("error" to (e.message ?: "Unknown")))
    }

    // Fallback to $HOME/Downloads
    return File(userHome, "Downloads").absolutePath
}
