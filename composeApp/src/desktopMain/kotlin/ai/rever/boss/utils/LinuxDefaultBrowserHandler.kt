package ai.rever.boss.utils

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Linux-specific handler for default browser functionality
 *
 * Uses XDG standards to:
 * - Create .desktop file for BOSS
 * - Register BOSS as default browser via xdg-settings and xdg-mime
 * - Check if BOSS is the current default browser
 */
object LinuxDefaultBrowserHandler {
    private val logger = BossLogger.forComponent("LinuxDefaultBrowserHandler")
    private val DESKTOP_FILE_PATH = File(
        System.getProperty("user.home"),
        ".local/share/applications/boss.desktop"
    )

    /**
     * Check if BOSS is currently the default browser on Linux
     *
     * Uses xdg-settings to query default web browser
     */
    suspend fun isDefaultBrowser(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val defaultBrowser = getDefaultWebBrowser()

            logger.debug(LogCategory.BROWSER, "Linux default browser check", mapOf("defaultBrowser" to (defaultBrowser ?: "none")))

            // BOSS is default if xdg-settings returns "boss.desktop"
            val isDefault = defaultBrowser == "boss.desktop"

            Result.success(isDefault)
        } catch (e: Exception) {
            logger.error(LogCategory.BROWSER, "Error checking default browser on Linux", error = e)
            Result.failure(e)
        }
    }

    /**
     * Set BOSS as the default browser on Linux
     *
     * Creates .desktop file and uses xdg-settings/xdg-mime to set as default
     */
    suspend fun setAsDefaultBrowser(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            // First check if already default
            val checkResult = isDefaultBrowser()
            if (checkResult.isSuccess && checkResult.getOrNull() == true) {
                logger.debug(LogCategory.BROWSER, "BOSS is already the default browser")
                return@withContext Result.success(true)
            }

            // Create .desktop file
            val desktopCreated = createDesktopFile()
            if (!desktopCreated) {
                return@withContext Result.failure(
                    Exception("Failed to create .desktop file")
                )
            }

            // Update desktop database
            updateDesktopDatabase()

            // Set as default using xdg-settings
            val xdgResult = setDefaultViaXdgSettings()

            // Also set MIME type associations
            setMimeTypeAssociations()

            if (xdgResult) {
                logger.info(LogCategory.BROWSER, "Successfully set BOSS as default browser on Linux")
                Result.success(true)
            } else {
                logger.warn(LogCategory.BROWSER, "xdg-settings may have failed, but MIME types are set")
                Result.success(true)
            }
        } catch (e: Exception) {
            logger.error(LogCategory.BROWSER, "Error setting default browser on Linux", error = e)
            Result.failure(e)
        }
    }

    /**
     * Get the current default web browser
     */
    private fun getDefaultWebBrowser(): String? {
        return try {
            val process = ProcessBuilder("xdg-settings", "get", "default-web-browser")
                .redirectErrorStream(true)
                .start()

            val output = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.readText().trim()
            }

            process.waitFor()

            if (process.exitValue() == 0 && output.isNotBlank()) {
                output
            } else {
                null
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Error getting default web browser", error = e)
            null
        }
    }

    /**
     * Create .desktop file for BOSS
     */
    private fun createDesktopFile(): Boolean {
        return try {
            val appPath = getApplicationPath()
            if (appPath.isNullOrEmpty()) {
                logger.warn(LogCategory.BROWSER, "Could not determine application path")
                return false
            }

            val iconPath = getIconPath()

            val desktopContent = """
                [Desktop Entry]
                Version=1.0
                Type=Application
                Name=BOSS Console
                Comment=Business Operating System + Simulation - Intelligent service automation platform
                Exec=$appPath %u
                Icon=$iconPath
                Terminal=false
                Categories=Network;WebBrowser;
                MimeType=x-scheme-handler/http;x-scheme-handler/https;x-scheme-handler/boss;text/html;application/xhtml+xml;
                StartupNotify=true
                StartupWMClass=BOSS
            """.trimIndent()

            // Ensure directory exists
            DESKTOP_FILE_PATH.parentFile.mkdirs()

            // Write .desktop file
            DESKTOP_FILE_PATH.writeText(desktopContent)

            // Make executable
            DESKTOP_FILE_PATH.setExecutable(true, false)

            logger.info(LogCategory.BROWSER, "Created .desktop file", mapOf("path" to DESKTOP_FILE_PATH.absolutePath))
            true
        } catch (e: Exception) {
            logger.error(LogCategory.BROWSER, "Error creating .desktop file", error = e)
            false
        }
    }

    /**
     * Get the path to the running application
     */
    private fun getApplicationPath(): String? {
        return try {
            val jarPath = LinuxDefaultBrowserHandler::class.java.protectionDomain.codeSource.location.toURI().path

            when {
                jarPath.endsWith(".jar") -> {
                    // Running from JAR - look for launcher script or use java -jar
                    val jarFile = File(jarPath)
                    val launcherScript = jarFile.parentFile.resolve("boss")
                    if (launcherScript.exists()) {
                        launcherScript.absolutePath
                    } else {
                        // Fallback to java command with jar
                        "java -jar \"${jarFile.absolutePath}\""
                    }
                }
                else -> {
                    // Development environment - look for packaged executable
                    val workingDir = File(System.getProperty("user.dir"))
                    val possiblePaths = listOf(
                        workingDir.resolve("composeApp/build/compose/binaries/main/app/BOSS/bin/BOSS"),
                        workingDir.resolve("build/compose/binaries/main/app/BOSS/bin/BOSS")
                    )
                    possiblePaths.firstOrNull { it.exists() }?.absolutePath
                        ?: "boss" // Fallback to assuming it's in PATH
                }
            }
        } catch (e: Exception) {
            logger.error(LogCategory.BROWSER, "Error determining application path", error = e)
            null
        }
    }

    /**
     * Get the path to the application icon
     */
    private fun getIconPath(): String {
        return try {
            // Look for icon in standard locations
            val workingDir = File(System.getProperty("user.dir"))
            val possiblePaths = listOf(
                workingDir.resolve("composeApp/src/desktopMain/resources/boss_icon.png"),
                File("/usr/share/icons/hicolor/256x256/apps/boss.png"),
                File("/usr/share/pixmaps/boss.png")
            )

            possiblePaths.firstOrNull { it.exists() }?.absolutePath ?: "boss"
        } catch (e: Exception) {
            logger.debug(LogCategory.BROWSER, "Icon path lookup failed - using default icon name", mapOf("error" to e.toString()))
            "boss"
        }
    }

    /**
     * Update desktop database to recognize new .desktop file
     */
    private fun updateDesktopDatabase() {
        try {
            val process = ProcessBuilder(
                "update-desktop-database",
                DESKTOP_FILE_PATH.parentFile.absolutePath
            ).redirectErrorStream(true).start()

            process.waitFor()

            if (process.exitValue() == 0) {
                logger.debug(LogCategory.BROWSER, "Updated desktop database")
            } else {
                logger.debug(LogCategory.BROWSER, "update-desktop-database may have failed (non-critical)")
            }
        } catch (e: Exception) {
            logger.debug(LogCategory.BROWSER, "update-desktop-database not available or failed", mapOf("error" to e.toString()))
        }
    }

    /**
     * Set BOSS as default browser using xdg-settings
     */
    private fun setDefaultViaXdgSettings(): Boolean {
        return try {
            val process = ProcessBuilder(
                "xdg-settings",
                "set",
                "default-web-browser",
                "boss.desktop"
            ).redirectErrorStream(true).start()

            val output = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.readText()
            }

            process.waitFor()

            if (process.exitValue() == 0) {
                logger.debug(LogCategory.BROWSER, "Set default browser via xdg-settings")
                true
            } else {
                logger.warn(LogCategory.BROWSER, "xdg-settings failed", mapOf("output" to output))
                false
            }
        } catch (e: Exception) {
            logger.error(LogCategory.BROWSER, "Error running xdg-settings", error = e)
            false
        }
    }

    /**
     * Set MIME type associations using xdg-mime
     */
    private fun setMimeTypeAssociations() {
        val mimeTypes = listOf(
            "x-scheme-handler/http",
            "x-scheme-handler/https",
            "x-scheme-handler/boss"
        )

        mimeTypes.forEach { mimeType ->
            try {
                val process = ProcessBuilder(
                    "xdg-mime",
                    "default",
                    "boss.desktop",
                    mimeType
                ).redirectErrorStream(true).start()

                process.waitFor()

                if (process.exitValue() == 0) {
                    logger.debug(LogCategory.BROWSER, "Set MIME association", mapOf("mimeType" to mimeType))
                } else {
                    logger.warn(LogCategory.BROWSER, "Failed to set MIME association", mapOf("mimeType" to mimeType))
                }
            } catch (e: Exception) {
                logger.error(LogCategory.BROWSER, "Error setting MIME association", mapOf("mimeType" to mimeType), e)
            }
        }
    }

    /**
     * Check if xdg-settings command is available
     */
    fun isXdgSettingsAvailable(): Boolean {
        return try {
            val process = ProcessBuilder("which", "xdg-settings")
                .redirectErrorStream(true)
                .start()

            process.waitFor()
            process.exitValue() == 0
        } catch (e: Exception) {
            logger.debug(LogCategory.BROWSER, "which xdg-settings failed - assuming unavailable", mapOf("error" to e.toString()))
            false
        }
    }
}
