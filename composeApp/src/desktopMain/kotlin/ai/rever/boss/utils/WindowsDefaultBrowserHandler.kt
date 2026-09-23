package ai.rever.boss.utils

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Windows-specific handler for default browser functionality
 *
 * Handles registry operations to:
 * - Register BOSS as a browser candidate
 * - Check if BOSS is the default browser
 * - Guide user to set BOSS as default (Windows 10+ requires manual selection)
 *
 * Note: Windows 10+ does not allow programmatic default browser changes
 * due to security restrictions. We open Windows Settings for user to select.
 */
object WindowsDefaultBrowserHandler {
    private val logger = BossLogger.forComponent("WindowsDefaultBrowserHandler")

    // Registry paths
    private const val START_MENU_KEY = "HKEY_CURRENT_USER\\SOFTWARE\\Clients\\StartMenuInternet\\BOSS"
    private const val REGISTERED_APPS = "HKEY_CURRENT_USER\\SOFTWARE\\RegisteredApplications"
    private const val URL_ASSOCIATIONS =
        "HKEY_CURRENT_USER\\SOFTWARE\\Microsoft\\Windows\\Shell\\Associations\\UrlAssociations"

    /** The ProgId `registerAsBrowserCandidate` writes, and so the one that means BOSS. */
    private const val BROWSER_PROG_ID = "BOSS"

    /** The schemes "default browser" means. Both must point at BOSS for it to be true. */
    private val BROWSER_SCHEMES = listOf("http", "https")

    /**
     * Check if BOSS is currently the default browser on Windows
     *
     * Queries registry UserChoice keys for http/https associations
     */
    suspend fun isDefaultBrowser(): Result<Boolean> = browserHandlerState().map { it.isOurs }

    /**
     * Who owns the browser role right now, as the three-way answer the Settings
     * cards share.
     *
     * [DefaultHandlerState.OurEngine] is unreachable here, and that is a fact
     * about Windows rather than an omission: the second "BOSS" that stole the
     * role on macOS is the branded Chromium **app bundle**, which Launch Services
     * lists because it is an `.app` declaring `CFBundleURLTypes`. Nothing
     * registers the engine under `StartMenuInternet` on Windows, so a ProgId that
     * is not BOSS's is another vendor's browser. The state is still the shared
     * type so the card has one story to tell on all three platforms.
     */

    /**
     * What a `UserChoice` ProgId means, separated from the `reg query` that reads
     * it so the mapping can be tested at all.
     *
     * Case-insensitive because the registry preserves whatever case wrote the
     * value, and a case-sensitive comparison would report BOSS as another vendor.
     */
    internal fun stateForProgId(progId: String?): DefaultHandlerState =
        when {
            progId == null -> DefaultHandlerState.Other(null)
            progId.equals(BROWSER_PROG_ID, ignoreCase = true) -> DefaultHandlerState.Ours
            else -> DefaultHandlerState.Other(progId)
        }

    internal suspend fun browserHandlerState(): Result<DefaultHandlerState> =
        withContext(Dispatchers.IO) {
            try {
                val states = BROWSER_SCHEMES.associateWith { schemeState(it) }

                logger.debug(
                    LogCategory.BROWSER,
                    "Windows default browser check",
                    states.mapValues { (_, state) -> state.toString() },
                )

                Result.success(DefaultHandlerState.reduce(states.values))
            } catch (e: Exception) {
                logger.error(LogCategory.BROWSER, "Error checking default browser on Windows", error = e)
                Result.failure(e)
            }
        }

    /**
     * Set BOSS as the default browser on Windows
     *
     * Registers BOSS as a browser candidate and opens Windows Settings
     * for user to manually select BOSS as default (Windows 10+ requirement)
     */
    suspend fun setAsDefaultBrowser(): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                // First register BOSS as a browser candidate
                val registered = registerAsBrowserCandidate()

                if (!registered) {
                    return@withContext Result.failure(
                        Exception("Failed to register BOSS as browser candidate"),
                    )
                }

                // Open Windows Settings for user to select default browser
                openDefaultAppsSettings()

                // Return false to indicate user action is required
                logger.info(LogCategory.BROWSER, "Windows Settings opened - user must manually select BOSS as default")
                Result.success(false)
            } catch (e: Exception) {
                logger.error(LogCategory.BROWSER, "Error setting default browser on Windows", error = e)
                Result.failure(e)
            }
        }

    /**
     * Who owns [scheme], blocking.
     *
     * Not suspending, because `WindowsFileTypeHandler.statusOf` is not either and
     * needs this same answer: the `web-links` category is schemes with no
     * extensions, so reading it through the extension path reported
     * [DefaultHandlerState.Other] on every machine, including one where BOSS did
     * hold http and https. Callers are responsible for being on IO - both are.
     */
    internal fun schemeState(scheme: String): DefaultHandlerState = stateForProgId(getDefaultBrowserProgId(scheme))

    /**
     * Get the current default browser ProgId for a scheme
     */
    private fun getDefaultBrowserProgId(scheme: String): String? =
        try {
            // Built from the scheme rather than matched against two constants: the
            // key shape is the same for every scheme Windows records here, and the
            // `else -> return null` that used to sit here reported "nobody owns
            // this" for any third scheme the type resource might list - which reads
            // as a fact about the machine rather than a gap in this function.
            val keyPath = "$URL_ASSOCIATIONS\\$scheme\\UserChoice"

            val process = Runtime.getRuntime().exec("""reg query "$keyPath" /v ProgId""")
            val output =
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    reader.readText()
                }

            process.waitFor()

            // Parse output: "    ProgId    REG_SZ    ChromeHTML"
            val regex = """ProgId\s+REG_SZ\s+(.+)""".toRegex()
            regex
                .find(output)
                ?.groupValues
                ?.get(1)
                ?.trim()
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Error querying default browser", mapOf("scheme" to scheme), e)
            null
        }

    /**
     * Register BOSS as a browser candidate in Windows Registry
     *
     * Creates necessary registry keys for Windows to recognize BOSS as a browser
     */

    /**
     * Registers BOSS under `StartMenuInternet` so Windows lists it as a browser
     * at all. `internal` because claiming the `web-links` category from
     * `Settings > Default Apps` has to do the same thing - the alternative was a
     * second copy of these registry writes.
     */
    internal fun registerAsBrowserCandidate(): Boolean {
        try {
            val appPath = getApplicationPath()
            if (appPath.isNullOrEmpty()) {
                logger.warn(LogCategory.BROWSER, "Could not determine application path for browser registration")
                return false
            }

            logger.info(LogCategory.BROWSER, "Registering BOSS as browser candidate", mapOf("path" to appPath))

            // Create registry entries
            val commands =
                listOf(
                    // Create main key
                    """reg add "$START_MENU_KEY" /ve /d "BOSS Console" /f""",
                    // Set icon
                    """reg add "$START_MENU_KEY\DefaultIcon" /ve /d "$appPath,0" /f""",
                    // Set command to open the app
                    """reg add "$START_MENU_KEY\shell\open\command" /ve /d "\"$appPath\" \"%1\"" /f""",
                    // Install info
                    """reg add "$START_MENU_KEY\InstallInfo" /v IconsVisible /t REG_DWORD /d 1 /f""",
                    """reg add "$START_MENU_KEY\InstallInfo" /v ShowIconsCommand /d "$appPath" /f""",
                    """reg add "$START_MENU_KEY\InstallInfo" /v HideIconsCommand /d "$appPath" /f""",
                    """reg add "$START_MENU_KEY\InstallInfo" /v ReinstallCommand /d "$appPath" /f""",
                    // Capabilities
                    """reg add "$START_MENU_KEY\Capabilities" /v ApplicationName /d "BOSS Console" /f""",
                    """reg add "$START_MENU_KEY\Capabilities" /v ApplicationIcon /d "$appPath,0" /f""",
                    """reg add "$START_MENU_KEY\Capabilities" /v ApplicationDescription /d "Business Operating System + Simulation - Intelligent service automation platform" /f""",
                    // URL Associations
                    """reg add "$START_MENU_KEY\Capabilities\URLAssociations" /v http /d "BOSS" /f""",
                    """reg add "$START_MENU_KEY\Capabilities\URLAssociations" /v https /d "BOSS" /f""",
                    """reg add "$START_MENU_KEY\Capabilities\URLAssociations" /v ftp /d "BOSS" /f""",
                    // File Associations
                    """reg add "$START_MENU_KEY\Capabilities\FileAssociations" /v .htm /d "BOSS" /f""",
                    """reg add "$START_MENU_KEY\Capabilities\FileAssociations" /v .html /d "BOSS" /f""",
                    // Register in RegisteredApplications
                    """reg add "$REGISTERED_APPS" /v BOSS /d "SOFTWARE\\Clients\\StartMenuInternet\\BOSS\\Capabilities" /f""",
                )

            var allSucceeded = true
            commands.forEachIndexed { index, command ->
                try {
                    val process = Runtime.getRuntime().exec(command)
                    val exitCode = process.waitFor()

                    if (exitCode == 0) {
                        logger.trace(LogCategory.BROWSER, "Registry command succeeded", mapOf("index" to index))
                    } else {
                        logger.warn(LogCategory.BROWSER, "Registry command failed", mapOf("index" to index))

                        // Fail fast on critical keys (first 3: main key, DefaultIcon, shell command)
                        if (index < 3) {
                            logger.error(LogCategory.BROWSER, "Critical registry key failed, aborting registration")
                            return false
                        }
                        allSucceeded = false
                    }
                } catch (e: Exception) {
                    logger.error(LogCategory.BROWSER, "Error executing registry command", error = e)

                    // Fail fast on critical keys
                    if (index < 3) {
                        logger.error(LogCategory.BROWSER, "Critical registry key failed with exception, aborting registration")
                        return false
                    }
                    allSucceeded = false
                }
            }

            if (allSucceeded) {
                logger.info(LogCategory.BROWSER, "BOSS successfully registered as browser candidate")
            }

            return allSucceeded
        } catch (e: Exception) {
            logger.error(LogCategory.BROWSER, "Failed to register as browser candidate", error = e)
            return false
        }
    }

    /**
     * Get the path to the running application executable
     */
    private fun getApplicationPath(): String? =
        try {
            // Try to get the path from the running JAR/EXE
            val jarFile = codeSourceFile(WindowsDefaultBrowserHandler::class.java.protectionDomain.codeSource.location)

            when {
                jarFile.name.endsWith(".jar") -> {
                    // Running from JAR - look for launcher executable
                    val launcherPath = jarFile.parentFile.resolve("BOSS.exe")
                    if (launcherPath.exists()) {
                        launcherPath.absolutePath
                    } else {
                        // Fallback to java command with jar
                        val javaHome = System.getProperty("java.home")
                        val javawExe = File(javaHome, "bin\\javaw.exe")
                        "\"${javawExe.absolutePath}\" -jar \"${jarFile.absolutePath}\""
                    }
                }

                jarFile.path.contains("BOSS.exe") -> {
                    // Already an executable
                    jarFile.absolutePath
                }

                else -> {
                    // Development environment - look for packaged executable
                    val workingDir = File(System.getProperty("user.dir"))
                    val possiblePaths =
                        listOf(
                            workingDir.resolve("composeApp\\build\\compose\\binaries\\main\\app\\BOSS\\BOSS.exe"),
                            workingDir.resolve("build\\compose\\binaries\\main\\app\\BOSS\\BOSS.exe"),
                        )
                    possiblePaths.firstOrNull { it.exists() }?.absolutePath
                }
            }
        } catch (e: Exception) {
            logger.error(LogCategory.BROWSER, "Error determining application path", error = e)
            null
        }

    /**
     * Open Windows Settings to Default Apps page
     *
     * Opens the settings page where user can select BOSS as default browser
     */
    private fun openDefaultAppsSettings() {
        try {
            // Open Windows Settings to Default Apps
            val process = Runtime.getRuntime().exec("cmd /c start ms-settings:defaultapps")
            process.waitFor()

            logger.debug(LogCategory.BROWSER, "Opened Windows Settings - Default Apps")
        } catch (e: Exception) {
            logger.error(LogCategory.BROWSER, "Error opening Windows Settings", error = e)
        }
    }

    /**
     * Check if BOSS is already registered as a browser candidate
     */
    fun isBrowserCandidateRegistered(): Boolean =
        try {
            val process = Runtime.getRuntime().exec("""reg query "$START_MENU_KEY" """)
            process.waitFor()
            process.exitValue() == 0
        } catch (e: Exception) {
            logger.debug(
                LogCategory.BROWSER,
                "reg query failed - treating browser candidate as unregistered",
                mapOf("error" to e.toString()),
            )
            false
        }
}
