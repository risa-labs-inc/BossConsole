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

    /** The desktop entry that means BOSS, as `xdg-settings` reports it. */
    private const val DESKTOP_FILE_NAME = "boss.desktop"

    private val DESKTOP_FILE_PATH =
        File(
            System.getProperty("user.home"),
            ".local/share/applications/$DESKTOP_FILE_NAME",
        )

    /**
     * Check if BOSS is currently the default browser on Linux
     *
     * Uses xdg-settings to query default web browser
     */
    suspend fun isDefaultBrowser(): Result<Boolean> = browserHandlerState().map { it.isOurs }

    /**
     * Who owns the browser role right now, as the three-way answer the Settings
     * cards share.
     *
     * As on Windows, [DefaultHandlerState.OurEngine] cannot occur: the engine
     * that stole the role on macOS is an `.app` bundle Launch Services indexes,
     * and nothing writes a desktop entry for it here. A name that is not
     * `boss.desktop` is another browser.
     */

    /**
     * What an `xdg-settings` answer means, separated from the process call that
     * produces it so the mapping can be tested.
     *
     * `xdg-settings` prints the desktop entry's file name; some environments echo
     * it with different case, hence the case-insensitive comparison.
     */
    internal fun stateForDesktopEntry(desktopEntry: String?): DefaultHandlerState =
        when {
            desktopEntry == null -> DefaultHandlerState.Other(null)
            desktopEntry.equals(DESKTOP_FILE_NAME, ignoreCase = true) -> DefaultHandlerState.Ours
            else -> DefaultHandlerState.Other(desktopEntry)
        }

    internal suspend fun browserHandlerState(): Result<DefaultHandlerState> =
        withContext(Dispatchers.IO) {
            try {
                val defaultBrowser = getDefaultWebBrowser()

                logger.debug(LogCategory.BROWSER, "Linux default browser check", mapOf("defaultBrowser" to (defaultBrowser ?: "none")))

                Result.success(stateForDesktopEntry(defaultBrowser))
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
    suspend fun setAsDefaultBrowser(): Result<Boolean> =
        withContext(Dispatchers.IO) {
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
                        Exception("Failed to create .desktop file"),
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
     * Writes (or rewrites) `~/.local/share/applications/boss.desktop` and
     * refreshes the desktop database.
     *
     * Exposed for the Default Apps screen, which claims a file-type category with
     * `xdg-mime default`. That only associates a MIME type the desktop entry
     * already declares in its `MimeType=` line, so a claim made without this
     * having run records an association no file can ever match - it looks like it
     * worked and does nothing.
     *
     * @return true when the entry is in place.
     */
    fun ensureDesktopEntry(): Boolean {
        val created = createDesktopFile()
        if (created) updateDesktopDatabase()
        return created
    }

    /**
     * Get the current default web browser
     */
    private fun getDefaultWebBrowser(): String? =
        try {
            val process =
                ProcessBuilder("xdg-settings", "get", "default-web-browser")
                    .redirectErrorStream(true)
                    .start()

            val output =
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
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

            // Every MIME type any file-type category claims, plus the three
            // scheme handlers. Declared here because `xdg-mime default` will only
            // associate a type the desktop entry already lists - see
            // ensureDesktopEntry.
            //
            // Read from the shared table rather than hardcoded, so a category
            // added to boss-file-types.json reaches Linux without a second edit.
            // Falls back to the browser-only list if the table failed to load, so
            // a resource problem cannot cost the user their default browser.
            val mimeTypes =
                buildList {
                    add("x-scheme-handler/http")
                    add("x-scheme-handler/https")
                    add("x-scheme-handler/boss")
                    addAll(
                        ai.rever.boss.filetypes.LinuxFileTypeHandler
                            .allMimeTypes(),
                    )
                    add("text/html")
                    add("application/xhtml+xml")
                }.distinct().joinToString(";", postfix = ";")

            // Exec= must quote the app path: the Desktop Entry Specification requires
            // paths with whitespace (or any other shell-special character) to be
            // wrapped in double quotes, and the XDG desktop parser splits on
            // whitespace otherwise. A BOSS install under "/opt/BOSS with space/"
            // would otherwise produce a malformed Exec line and clicking the
            // resulting .desktop entry would launch nothing.
            val escapedAppPath = appPath.replace("\\", "\\\\").replace("\"", "\\\"")
            val quotedAppPath = "\"$escapedAppPath\""

            val desktopContent =
                """
                [Desktop Entry]
                Version=1.0
                Type=Application
                Name=BOSS Console
                Comment=Business Operating System + Simulation - Intelligent service automation platform
                Exec=$quotedAppPath %U
                Icon=$iconPath
                Terminal=false
                Categories=Network;WebBrowser;Development;TextEditor;
                MimeType=$mimeTypes
                StartupNotify=true
                StartupWMClass=BOSS
                """.trimIndent()

            // Ensure directory exists
            DESKTOP_FILE_PATH.parentFile.mkdirs()

            // Write .desktop file atomically: this is the user's MIME-association
            // state for the entire desktop session, and a crash mid-write would
            // leave an unparseable .desktop file until the next install. Use the
            // shared atomic-write helper rather than File.writeText.
            DESKTOP_FILE_PATH.atomicWriteText(desktopContent)

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
    private fun getApplicationPath(): String? =
        try {
            val jarPath =
                LinuxDefaultBrowserHandler::class.java.protectionDomain.codeSource.location
                    .toURI()
                    .path

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
                    val possiblePaths =
                        listOf(
                            workingDir.resolve("composeApp/build/compose/binaries/main/app/BOSS/bin/BOSS"),
                            workingDir.resolve("build/compose/binaries/main/app/BOSS/bin/BOSS"),
                        )
                    possiblePaths.firstOrNull { it.exists() }?.absolutePath
                        ?: "boss" // Fallback to assuming it's in PATH
                }
            }
        } catch (e: Exception) {
            logger.error(LogCategory.BROWSER, "Error determining application path", error = e)
            null
        }

    /**
     * Get the path to the application icon
     */
    private fun getIconPath(): String =
        try {
            // Look for icon in standard locations
            val workingDir = File(System.getProperty("user.dir"))
            val possiblePaths =
                listOf(
                    workingDir.resolve("composeApp/src/desktopMain/resources/boss_icon.png"),
                    File("/usr/share/icons/hicolor/256x256/apps/boss.png"),
                    File("/usr/share/pixmaps/boss.png"),
                )

            possiblePaths.firstOrNull { it.exists() }?.absolutePath ?: "boss"
        } catch (e: Exception) {
            logger.debug(
                LogCategory.BROWSER,
                "Icon path lookup failed - using default icon name",
                mapOf("error" to e.toString()),
            )
            "boss"
        }

    /**
     * Update desktop database to recognize new .desktop file
     */
    private fun updateDesktopDatabase() {
        try {
            val process =
                ProcessBuilder(
                    "update-desktop-database",
                    DESKTOP_FILE_PATH.parentFile.absolutePath,
                ).redirectErrorStream(true).start()

            process.waitFor()

            if (process.exitValue() == 0) {
                logger.debug(LogCategory.BROWSER, "Updated desktop database")
            } else {
                logger.debug(LogCategory.BROWSER, "update-desktop-database may have failed (non-critical)")
            }
        } catch (e: Exception) {
            logger.debug(
                LogCategory.BROWSER,
                "update-desktop-database not available or failed",
                mapOf("error" to e.toString()),
            )
        }
    }

    /**
     * Set BOSS as default browser using xdg-settings
     */
    private fun setDefaultViaXdgSettings(): Boolean =
        try {
            val process =
                ProcessBuilder(
                    "xdg-settings",
                    "set",
                    "default-web-browser",
                    DESKTOP_FILE_NAME,
                ).redirectErrorStream(true).start()

            val output =
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
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

    /**
     * Set MIME type associations using xdg-mime
     */
    private fun setMimeTypeAssociations() {
        val mimeTypes =
            listOf(
                "x-scheme-handler/http",
                "x-scheme-handler/https",
                "x-scheme-handler/boss",
            )

        mimeTypes.forEach { mimeType ->
            try {
                val process =
                    ProcessBuilder(
                        "xdg-mime",
                        "default",
                        DESKTOP_FILE_NAME,
                        mimeType,
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
    fun isXdgSettingsAvailable(): Boolean =
        try {
            val process =
                ProcessBuilder("which", "xdg-settings")
                    .redirectErrorStream(true)
                    .start()

            process.waitFor()
            process.exitValue() == 0
        } catch (e: Exception) {
            logger.debug(
                LogCategory.BROWSER,
                "which xdg-settings failed - assuming unavailable",
                mapOf("error" to e.toString()),
            )
            false
        }
}
