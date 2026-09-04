package ai.rever.boss.updater

import ai.rever.boss.utils.AppVersion
import ai.rever.boss.utils.BOSS_MACOS_APP_BUNDLE_NAME
import ai.rever.boss.utils.BOSS_MACOS_BUNDLE_ID
import ai.rever.boss.utils.Version
import ai.rever.boss.utils.WindowsProtocolCleanup
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Paths
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private const val APP_TRANSLOCATION_PATH_SEGMENT = "/AppTranslocation/"
private const val MACOS_APP_BUNDLE_SUFFIX = ".app"
private const val MACOS_APPLICATIONS_DIRECTORY = "/Applications"
private const val SPOTLIGHT_LOOKUP_TIMEOUT_SECONDS = 5L

/** jpackage sets this on every packaged launch, to the launcher executable itself. */
private const val JPACKAGE_APP_PATH_PROPERTY = "jpackage.app-path"

/** Filename of the Windows launcher jpackage emits, as `WindowsProtocolHandler` also assumes. */
private const val WINDOWS_LAUNCHER_NAME = "BOSS.exe"

/**
 * How far up from the running code to look for the launcher. The jpackage layout puts
 * it two levels up (`<install>\app\*.jar` -> `<install>\BOSS.exe`); the slack covers a
 * jar nested deeper, and [WINDOWS_INSTALL_MARKER_DIRS] is what keeps it from wandering.
 */
private const val WINDOWS_LAUNCHER_SEARCH_DEPTH = 5

/**
 * Directories jpackage always puts beside the launcher, used to require that a candidate
 * directory actually looks like a BOSS install.
 *
 * Without this the walk could accept any `BOSS.exe` sitting in an ancestor directory, and
 * above `<install>` for a per-user install those ancestors are `%LOCALAPPDATA%`,
 * `C:\Users\<account>\AppData` and `C:\Users\<account>` - all user-writable, and the
 * result is handed straight to `start`. Reaching that needs `jpackage.app-path` absent or
 * stale *and* the real launcher gone, so it was never likely; "we could not find the
 * launcher" is still a much better outcome than launching whatever is named right three
 * directories up.
 */
private val WINDOWS_INSTALL_MARKER_DIRS = listOf("app", "runtime")

/** Staging directory (inside the platform temp directory) that downloads land in. */
internal const val UPDATE_STAGING_DIR_NAME = "boss-updates"

/** Resolve the download staging directory without creating it. */
internal fun defaultStagingDir(): File = File(System.getProperty("java.io.tmpdir"), UPDATE_STAGING_DIR_NAME)

/** Return the outermost complete `.app` path segment in [path]. */
internal fun macOSAppBundlePathIn(path: String): String? {
    val pathSegments = path.split('/')
    val bundleIndex =
        pathSegments.indexOfFirst {
            it.length > MACOS_APP_BUNDLE_SUFFIX.length &&
                it.endsWith(MACOS_APP_BUNDLE_SUFFIX)
        }
    if (bundleIndex < 0) return null

    return pathSegments.take(bundleIndex + 1).joinToString("/")
}

/** Extract the first app bundle represented in `java.library.path`. */
internal fun macOSAppBundlePathFromLibraryPath(libraryPath: String): String? =
    libraryPath
        .split(File.pathSeparatorChar)
        .firstNotNullOfOrNull(::macOSAppBundlePathIn)

/**
 * Resolve a macOS app bundle path without coupling the decision logic to the
 * filesystem or Spotlight. The injected functions keep App Translocation path
 * parsing and fallback ordering directly unit-testable.
 */
internal fun realAppPathFor(
    path: String,
    appExists: (String) -> Boolean,
    installedAppLookup: () -> String?,
): String {
    if (!path.contains(APP_TRANSLOCATION_PATH_SEGMENT)) return path

    val bundleName =
        macOSAppBundlePathIn(
            path.substringAfter(APP_TRANSLOCATION_PATH_SEGMENT),
        )?.substringAfterLast('/')
            ?: return path

    val applicationsPath = "$MACOS_APPLICATIONS_DIRECTORY/$bundleName"
    if (appExists(applicationsPath)) return applicationsPath

    return installedAppLookup()?.takeIf(appExists) ?: path
}

/**
 * Resolve the installed Windows launcher to relaunch after the MSI runs, without
 * touching the filesystem itself so the ordering stays unit-testable.
 *
 * Returns null when there is nothing to relaunch - a development run, or an install
 * shape neither method recognises. Null is not an error: the caller installs anyway
 * and only skips the relaunch, which is exactly the behaviour Windows had before.
 *
 * @param jpackageAppPath `jpackage.app-path`, the launcher path jpackage itself set.
 * @param codeSourcePath Absolute path of the running jar or classes directory.
 * @param exists Filesystem probe, injected. Answers for directories as well as files.
 */
internal fun windowsLauncherPathFor(
    jpackageAppPath: String?,
    codeSourcePath: String?,
    exists: (String) -> Boolean,
): String? {
    // jpackage's own answer, and the same property WindowsProtocolHandler trusts
    // first. It names the launcher directly, so nothing has to be inferred from the
    // install layout.
    jpackageAppPath?.takeIf { it.isNotBlank() && exists(it) }?.let { return it }

    // Otherwise walk up from the running code looking for the launcher beside a
    // directory we are inside: `<install>\app\composeApp.jar` -> `<install>\BOSS.exe`.
    // Nearest match wins, and each candidate has to look like an install.
    return codeSourcePath
        ?.let(::File)
        ?.let { code -> generateSequence(code.parentFile) { it.parentFile } }
        ?.take(WINDOWS_LAUNCHER_SEARCH_DEPTH)
        ?.filter { directory -> isWindowsInstallDir(directory, exists) }
        ?.map { File(it, WINDOWS_LAUNCHER_NAME).path }
        ?.firstOrNull(exists)
}

/** Whether [directory] carries one of the [WINDOWS_INSTALL_MARKER_DIRS] jpackage emits. */
private fun isWindowsInstallDir(
    directory: File,
    exists: (String) -> Boolean,
): Boolean = WINDOWS_INSTALL_MARKER_DIRS.any { exists(File(directory, it).path) }

/**
 * Platform-specific update installation logic
 *
 * This class handles the actual installation of updates for different platforms.
 * For macOS, it uses a helper script pattern to safely install updates after the app quits.
 */
sealed class InstallResult {
    data class Success(
        val message: String,
    ) : InstallResult()

    data class RequiresRestart(
        val message: String,
    ) : InstallResult()

    data class Error(
        val message: String,
        val failureReason: InstallFailureReason? = null,
    ) : InstallResult()
}

object UpdateInstaller {
    private val logger = BossLogger.forComponent("UpdateInstaller")

    /**
     * Validate a downloaded update file, failing closed.
     *
     * Checks, in order (name before filesystem, so a hostile name is refused
     * without touching disk):
     * 1. Filename characters, via the same [UpdatePathValidator] rules
     *    [UpdateScriptGenerator] uses - the filename is about to be interpolated
     *    into a generated shell/batch script
     * 2. Expected extension (.dmg, .msi, .jar, .deb, .rpm)
     * 3. Existence
     * 4. Containment inside the staging directory, decided on the **real** path
     *    (symlinks resolved)
     *
     * Every failure throws. Previously a canonical path escaping the staging
     * directory, or a filename carrying a dollar sign, a backtick or a semicolon,
     * only logged a warning and the update installed anyway - while
     * `UpdateScriptGenerator.validatePath` rejected the identical input, so
     * whether a suspicious artifact was installed depended on which entry point
     * ran (Issue #37).
     *
     * @param downloadFile The file to validate
     * @param expectedExtension Expected file extension (e.g., ".dmg")
     * @throws SecurityException if file is invalid or suspicious
     */
    internal fun validateDownloadFile(
        downloadFile: File,
        expectedExtension: String,
        stagingDir: File = defaultStagingDir(),
    ) {
        val filename = downloadFile.name

        // Filename characters: shared rules, hard failure. Checked before any
        // filesystem access so a hostile name is refused outright.
        UpdatePathValidator.validateFileName(filename, "Download filename")

        // Validate file extension
        if (!filename.endsWith(expectedExtension, ignoreCase = true)) {
            throw SecurityException(
                "Invalid file extension. Expected $expectedExtension but got: $filename",
            )
        }

        // Check file exists
        if (!downloadFile.exists()) {
            throw SecurityException("Download file does not exist: ${downloadFile.absolutePath}")
        }

        // Resolve BOTH sides to their real paths - symlinks followed - and compare
        // as paths, not strings.
        //
        // File.getCanonicalPath() is not sound for this on Windows: JDK 17's
        // windows/native/libjava/canonicalize_md.c has no reparse-point handling
        // at all (it normalises via _wfullpath and fixes casing via
        // FindFirstFileW), so a symlink inside the staging directory keeps a
        // canonical path inside the staging directory and the containment check
        // passed. Current JDKs added a getFinalPath() call, but deliberately
        // best-effort - "Do not fail if the final path cannot be obtained" -
        // returning the unresolved path when it fails, which is still no basis for
        // a security decision. Path.toRealPath() is specified to resolve symbolic
        // links on every platform and to fail rather than degrade. (java.io.File's
        // own javadoc points at Path#toRealPath.) Caught by the Windows CI leg.
        val realPath =
            try {
                downloadFile.toPath().toRealPath()
            } catch (e: IOException) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Failed to resolve the real path of the downloaded file - rejecting update file",
                    error = e,
                )
                throw SecurityException("Failed to resolve path: ${downloadFile.absolutePath}", e)
            }
        val realStagingDir =
            try {
                stagingDir.toPath().toRealPath()
            } catch (e: IOException) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Failed to resolve the real path of the staging directory - rejecting update file",
                    error = e,
                )
                throw SecurityException("Failed to resolve staging directory: ${stagingDir.absolutePath}", e)
            }

        // Path.startsWith compares name elements, so a sibling directory such as
        // "boss-updates-evil" cannot satisfy it the way a string prefix could. The
        // artifact must also be something *inside* the directory, not the directory.
        if (realPath == realStagingDir || !realPath.startsWith(realStagingDir)) {
            logger.error(
                LogCategory.SYSTEM,
                "Download file escapes the staging directory - rejecting update file",
                mapOf(
                    "expected" to realStagingDir.toString(),
                    "actual" to realPath.toString(),
                ),
            )
            throw SecurityException(
                "Download file is outside the staging directory - rejected for security: $realPath",
            )
        }

        logger.debug(LogCategory.SYSTEM, "Validated download file", mapOf("filename" to filename))
    }

    /**
     * Extract version from update file name.
     *
     * Expected formats:
     * - macOS: BOSS-8.12.18-Universal.dmg
     * - Windows: BOSS-8.12.18.msi
     * - Linux DEB: BOSS-8.12.18-amd64.deb or BOSS-8.12.18-arm64.deb
     * - Linux RPM: BOSS-8.12.18-amd64.rpm or BOSS-8.12.18-arm64.rpm
     * - Linux JAR: BOSS-8.12.18-amd64.jar or BOSS-8.12.18-arm64.jar
     *
     * @param file The update file
     * @return Parsed version, or null if version cannot be extracted
     */
    private fun extractVersionFromFilename(file: File): Version? =
        try {
            val filename = file.name
            logger.debug(LogCategory.SYSTEM, "Extracting version from filename", mapOf("filename" to filename))

            // Remove BOSS- prefix and file extension (with architecture suffixes)
            val versionStr =
                filename
                    .removePrefix("BOSS-")
                    .removeSuffix("-Universal.dmg")
                    .removeSuffix("-amd64.deb")
                    .removeSuffix("-arm64.deb")
                    .removeSuffix("-amd64.rpm")
                    .removeSuffix("-arm64.rpm")
                    .removeSuffix("-amd64.jar")
                    .removeSuffix("-arm64.jar")
                    .removeSuffix(".dmg")
                    .removeSuffix(".msi")
                    .removeSuffix(".jar")
                    .removeSuffix(".deb")
                    .removeSuffix(".rpm")

            logger.debug(LogCategory.SYSTEM, "Extracted version string", mapOf("version" to versionStr))

            Version.parse(versionStr)
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Failed to extract version from filename", error = e)
            null
        }

    /**
     * Verify update is not a downgrade (Issue #111 fix).
     *
     * Prevents installing older versions which was the root cause of Issue #111.
     *
     * @param downloadFile The update file to verify
     * @return true if safe to install, false if downgrade detected
     */
    private fun verifyNoDowngrade(downloadFile: File): Boolean {
        val downloadedVersion = extractVersionFromFilename(downloadFile)

        if (downloadedVersion == null) {
            logger.warn(
                LogCategory.SYSTEM,
                "Cannot verify update version - extraction failed",
                mapOf(
                    "filename" to downloadFile.name,
                ),
            )
            // Allow installation if version cannot be extracted (for manual updates)
            return true
        }

        val currentVersion = AppVersion.CURRENT

        logger.info(
            LogCategory.SYSTEM,
            "Version check",
            mapOf(
                "current" to currentVersion.toString(),
                "download" to downloadedVersion.toString(),
            ),
        )

        if (downloadedVersion < currentVersion) {
            logger.error(
                LogCategory.SYSTEM,
                "Downgrade detected - cannot install older version",
                mapOf(
                    "downloadVersion" to downloadedVersion.toString(),
                    "currentVersion" to currentVersion.toString(),
                ),
            )
            return false
        }

        if (downloadedVersion == currentVersion) {
            logger.info(
                LogCategory.SYSTEM,
                "Same version detected - allowing reinstall",
                mapOf(
                    "version" to downloadedVersion.toString(),
                ),
            )
        } else {
            logger.info(
                LogCategory.SYSTEM,
                "Update verified",
                mapOf(
                    "from" to currentVersion.toString(),
                    "to" to downloadedVersion.toString(),
                ),
            )
        }

        return true
    }

    /**
     * Install update for the current platform
     *
     * @param downloadPath Path to the downloaded update file
     * @return InstallResult indicating success, restart required, or error
     */
    suspend fun installUpdate(downloadPath: String): InstallResult {
        return try {
            val downloadFile = File(downloadPath)

            // Before ANY filesystem access: the artifact name came from the release
            // catalog and is interpolated into a generated install script. The
            // per-platform validateDownloadFile below repeats this; hoisting it here
            // makes "validated before we touch the file" true for the whole entry
            // point, not just inside that helper (Issue #37).
            UpdatePathValidator.validateFileName(downloadFile.name, "Download filename")

            if (!downloadFile.exists()) {
                logger.error(LogCategory.SYSTEM, "Update file not found", mapOf("path" to downloadPath))
                return InstallResult.Error("Update file not found")
            }

            // Verify this is not a downgrade (Issue #111 fix)
            if (!verifyNoDowngrade(downloadFile)) {
                return InstallResult.Error(
                    "Cannot install older version. This update appears to be a downgrade from your current version.",
                )
            }

            // Validate downloaded file type matches expected types for current platform
            // This prevents installing wrong package type (e.g., .msi on Linux)
            val fileName = downloadFile.name.lowercase()
            val validExtensions =
                when (getCurrentPlatform()) {
                    "macOS" -> listOf(".dmg")

                    "Windows" -> listOf(".msi")

                    "Linux-deb" -> listOf(".deb", ".jar")

                    // JAR fallback allowed
                    "Linux-rpm" -> listOf(".rpm", ".jar")

                    // JAR fallback allowed
                    else -> listOf(".jar")
                }
            if (!validExtensions.any { fileName.endsWith(it) }) {
                logger.error(
                    LogCategory.SYSTEM,
                    "File type mismatch",
                    mapOf(
                        "filename" to fileName,
                        "platform" to getCurrentPlatform(),
                        "expected" to validExtensions.joinToString(),
                    ),
                )
                return InstallResult.Error(
                    "Downloaded file type '$fileName' is not valid for this platform. Expected: ${validExtensions.joinToString()}",
                )
            }
            logger.debug(
                LogCategory.SYSTEM,
                "File type validated",
                mapOf(
                    "filename" to fileName,
                    "platform" to getCurrentPlatform(),
                ),
            )

            // Route based on actual file extension (not platform) to handle fallback cases
            // e.g., when .deb isn't available but .jar is for Linux ARM64
            when {
                fileName.endsWith(".dmg") -> {
                    installMacOSUpdate(downloadFile)
                }

                fileName.endsWith(".msi") -> {
                    installWindowsUpdate(downloadFile)
                }

                fileName.endsWith(".deb") -> {
                    installLinuxDebUpdate(downloadFile)
                }

                fileName.endsWith(".rpm") -> {
                    installLinuxRpmUpdate(downloadFile)
                }

                fileName.endsWith(".jar") -> {
                    installJarUpdate(downloadFile)
                }

                else -> {
                    logger.error(LogCategory.SYSTEM, "Unknown update file type", mapOf("filename" to downloadFile.name))
                    InstallResult.Error("Unknown update file type: ${downloadFile.extension}")
                }
            }
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Error installing update", error = e)
            InstallResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Install macOS update using helper script pattern
     *
     * The app cannot delete itself while running. Instead:
     * 1. Generate a helper script with the current process PID
     * 2. Launch the script in the background
     * 3. Return RequiresRestart to signal the app should quit
     * 4. Script waits for app to quit, then installs update
     */
    private suspend fun installMacOSUpdate(downloadFile: File): InstallResult {
        return withContext(Dispatchers.IO) {
            try {
                logger.info(LogCategory.SYSTEM, "Starting macOS update installation")

                // Validate download file for security (early check)
                validateDownloadFile(downloadFile, ".dmg")

                // Get current application bundle path
                val currentAppPath = getCurrentApplicationPath()
                if (currentAppPath == null) {
                    logger.warn(LogCategory.SYSTEM, "Could not determine app path - falling back to manual DMG install")
                    return@withContext openDMGForManualInstallation(downloadFile)
                }

                logger.debug(LogCategory.SYSTEM, "Target application path", mapOf("path" to currentAppPath))

                // Verify DMG is valid by attempting to mount it
                logger.debug(LogCategory.SYSTEM, "Mounting DMG for verification")
                val mountTest =
                    ProcessBuilder(
                        "hdiutil",
                        "attach",
                        downloadFile.absolutePath,
                        "-nobrowse",
                        "-quiet",
                        "-verify",
                    ).start()
                mountTest.waitFor()

                if (mountTest.exitValue() != 0) {
                    logger.error(LogCategory.SYSTEM, "DMG mounting failed")
                    return@withContext InstallResult.Error("Failed to mount DMG for verification")
                }

                // Find the mounted volume
                val mountedVolume = findMountedBossVolume()
                if (mountedVolume == null) {
                    logger.error(LogCategory.SYSTEM, "Could not find mounted BOSS volume")
                    cleanupDMG(null) // Try to cleanup any stray mounts
                    return@withContext InstallResult.Error("Could not locate mounted DMG volume")
                }

                // Use try-finally to ensure DMG is always unmounted, even if exceptions occur
                try {
                    logger.debug(LogCategory.SYSTEM, "Verifying DMG contents", mapOf("volume" to mountedVolume.name))

                    // Verify app bundle exists in DMG
                    val appBundle =
                        findAppBundleInVolume(mountedVolume)
                            ?: throw IllegalStateException("Could not find BOSS.app in mounted DMG")

                    // Refuse a build this Mac cannot launch, while BOSS is still
                    // running so the user gets a real dialog. The installer script
                    // repeats this check as defence-in-depth, but by the time it
                    // runs the app has already quit — an abort there is invisible
                    // and reads like a crash. Engine upgrades move this floor
                    // (JxBrowser 9.4.0 took it 12.0 -> 13.0) and the release
                    // manifest carries no minimum-OS field, so nothing upstream
                    // stops the update being offered.
                    unsupportedOsError(appBundle)?.let {
                        return@withContext InstallResult.Error(it, InstallFailureReason.UnsupportedOs)
                    }

                    logger.info(LogCategory.SYSTEM, "DMG verified successfully", mapOf("appBundle" to appBundle.name))

                    // DMG is valid - now we can safely unmount it (script will remount it)
                    // Unmounting happens in the finally block below
                } finally {
                    // CRITICAL: Always unmount the DMG, even if verification failed
                    logger.debug(LogCategory.SYSTEM, "Cleaning up verification mount")
                    cleanupDMG(mountedVolume)
                }

                // At this point, DMG has been verified and unmounted
                // Generate the update script that will remount, install, and cleanup
                val currentPid = ProcessHandle.current().pid()
                logger.debug(LogCategory.SYSTEM, "Generating update script", mapOf("pid" to currentPid))

                val scriptFile =
                    UpdateScriptGenerator.generateMacOSUpdateScript(
                        dmgPath = downloadFile.absolutePath,
                        targetAppPath = currentAppPath,
                        appPid = currentPid,
                    )

                // Launch the script in the background
                logger.info(LogCategory.SYSTEM, "Launching update script")
                UpdateScriptGenerator.launchScript(scriptFile)

                // Return RequiresRestart - the UpdateManager will handle quitting
                InstallResult.RequiresRestart(
                    "Update is ready to install. The app will quit and install the update.",
                )
            } catch (e: Exception) {
                logger.error(LogCategory.SYSTEM, "Error during update preparation", error = e)
                InstallResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Install Windows update using helper script pattern
     * Similar to macOS, but uses MSI installer
     */
    private suspend fun installWindowsUpdate(downloadFile: File): InstallResult =
        withContext(Dispatchers.IO) {
            try {
                logger.info(LogCategory.SYSTEM, "Starting Windows update installation")

                // Validate download file for security (early check)
                validateDownloadFile(downloadFile, ".msi")

                // Resolve the launcher while BOSS is still running - the script needs
                // it to bring the app back, and after msiexec there is no process left
                // to ask. Unlike the macOS path an unresolvable one is not fatal: the
                // install still happens, the user just starts BOSS themselves.
                val launcherPath = getWindowsLauncherPath()
                if (launcherPath == null) {
                    logger.warn(
                        LogCategory.SYSTEM,
                        "Could not determine the BOSS launcher path - the update will not relaunch",
                    )
                }

                // Generate update script with current process PID
                val currentPid = ProcessHandle.current().pid()
                logger.debug(LogCategory.SYSTEM, "Generating update script", mapOf("pid" to currentPid))

                val scriptFile =
                    UpdateScriptGenerator.generateWindowsUpdateScript(
                        msiPath = downloadFile.absolutePath,
                        appPid = currentPid,
                        targetExePath = launcherPath,
                    )

                // Launch the script in the background
                logger.info(LogCategory.SYSTEM, "Launching update script")
                UpdateScriptGenerator.launchScript(scriptFile)

                // Return RequiresRestart
                InstallResult.RequiresRestart(
                    "Update is ready to install. The app will quit and install the update.",
                )
            } catch (e: Exception) {
                logger.error(LogCategory.SYSTEM, "Error during update preparation", error = e)
                InstallResult.Error(e.message ?: "Unknown error")
            }
        }

    /**
     * Install JAR update (Linux/other platforms)
     * JAR files can be replaced while running, so no restart needed
     */
    private suspend fun installJarUpdate(downloadFile: File): InstallResult {
        return withContext(Dispatchers.IO) {
            try {
                logger.info(LogCategory.SYSTEM, "Starting JAR update installation")

                // Validate download file for security (early check)
                validateDownloadFile(downloadFile, ".jar")

                // Get current JAR path
                val currentJar = getCurrentJarPath()
                if (currentJar == null) {
                    logger.error(LogCategory.SYSTEM, "Could not determine current JAR path")
                    return@withContext InstallResult.Error("Could not locate current JAR")
                }

                // Backup current JAR
                val backupJar = File(currentJar.parentFile, "${currentJar.name}.backup")
                currentJar.copyTo(backupJar, overwrite = true)
                logger.debug(LogCategory.SYSTEM, "Backed up current JAR", mapOf("backup" to backupJar.absolutePath))

                // Replace current JAR
                downloadFile.copyTo(currentJar, overwrite = true)

                logger.info(LogCategory.SYSTEM, "JAR updated successfully")
                InstallResult.Success("Update installed. Restart the app to use the new version.")
            } catch (e: Exception) {
                logger.error(LogCategory.SYSTEM, "Failed to update JAR", error = e)
                InstallResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Install Linux DEB update using helper script pattern
     * Uses pkexec (graphical sudo) or sudo for privilege escalation
     */
    private suspend fun installLinuxDebUpdate(downloadFile: File): InstallResult =
        withContext(Dispatchers.IO) {
            try {
                logger.info(LogCategory.SYSTEM, "Starting Linux DEB update installation")

                // Validate download file for security (early check)
                validateDownloadFile(downloadFile, ".deb")

                // Generate update script with current process PID
                val currentPid = ProcessHandle.current().pid()
                logger.debug(LogCategory.SYSTEM, "Generating DEB update script", mapOf("pid" to currentPid))

                val scriptFile =
                    UpdateScriptGenerator.generateLinuxDebUpdateScript(
                        debPath = downloadFile.absolutePath,
                        appPid = currentPid,
                    )

                // Launch the script in the background
                logger.info(LogCategory.SYSTEM, "Launching DEB update script")
                UpdateScriptGenerator.launchScript(scriptFile)

                // Return RequiresRestart
                InstallResult.RequiresRestart(
                    "Update is ready to install. The app will quit and install the update.",
                )
            } catch (e: Exception) {
                logger.error(LogCategory.SYSTEM, "Error during DEB update preparation", error = e)
                InstallResult.Error(e.message ?: "Unknown error")
            }
        }

    /**
     * Install Linux RPM update using helper script pattern
     * Uses pkexec (graphical sudo) or sudo for privilege escalation
     */
    private suspend fun installLinuxRpmUpdate(downloadFile: File): InstallResult =
        withContext(Dispatchers.IO) {
            try {
                logger.info(LogCategory.SYSTEM, "Starting Linux RPM update installation")

                // Validate download file for security (early check)
                validateDownloadFile(downloadFile, ".rpm")

                // Generate update script with current process PID
                val currentPid = ProcessHandle.current().pid()
                logger.debug(LogCategory.SYSTEM, "Generating RPM update script", mapOf("pid" to currentPid))

                val scriptFile =
                    UpdateScriptGenerator.generateLinuxRpmUpdateScript(
                        rpmPath = downloadFile.absolutePath,
                        appPid = currentPid,
                    )

                // Launch the script in the background
                logger.info(LogCategory.SYSTEM, "Launching RPM update script")
                UpdateScriptGenerator.launchScript(scriptFile)

                // Return RequiresRestart
                InstallResult.RequiresRestart(
                    "Update is ready to install. The app will quit and install the update.",
                )
            } catch (e: Exception) {
                logger.error(LogCategory.SYSTEM, "Error during RPM update preparation", error = e)
                InstallResult.Error(e.message ?: "Unknown error")
            }
        }

    /**
     * Path of the installed `BOSS.exe` for the update script to relaunch, or null when
     * it cannot be resolved or would be refused by [UpdatePathValidator].
     *
     * The validation is swallowed here rather than left to the script generator on
     * purpose. The generator throws, and a throw on this argument would abort an
     * update that is otherwise fine - trading "installs but does not relaunch" for
     * "does not install", which is strictly worse. In practice it cannot trigger: the
     * MSI path passed alongside it lives under the same user profile and so carries
     * the same account name, and the filename component is the constant `BOSS.exe`.
     */
    internal fun getWindowsLauncherPath(): String? {
        val launcher =
            try {
                windowsLauncherPathFor(
                    jpackageAppPath = System.getProperty(JPACKAGE_APP_PATH_PROPERTY),
                    codeSourcePath = currentCodeSourceFile()?.path,
                    exists = { File(it).exists() },
                )
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Error resolving the Windows launcher path", error = e)
                null
            } ?: return null

        return try {
            UpdatePathValidator.validatePathAndFileName(launcher, "Target exe path")
            // Masked: a per-user install puts the Windows account name in this path, and
            // `maskUserPath` is the sibling helper written for exactly that shape.
            logger.debug(
                LogCategory.SYSTEM,
                "Resolved Windows launcher for relaunch",
                mapOf("path" to WindowsProtocolCleanup.maskUserPath(launcher)),
            )
            launcher
        } catch (e: SecurityException) {
            logger.warn(
                LogCategory.SYSTEM,
                "Launcher path refused by validation - installing without a relaunch",
                mapOf("reason" to (e.message ?: "unknown")),
            )
            null
        }
    }

    /**
     * The jar or classes directory this code is running from, or null if the location
     * is unavailable. Goes through [java.net.URI] rather than `location.path`: that is
     * URL-encoded, so a Windows install under a profile with a space in it yields a
     * `%20` no filesystem call resolves.
     */
    private fun currentCodeSourceFile(): File? =
        try {
            UpdateInstaller::class.java.protectionDomain
                ?.codeSource
                ?.location
                ?.toURI()
                ?.let(::File)
        } catch (e: Exception) {
            logger.debug(
                LogCategory.SYSTEM,
                "Could not resolve the current code source",
                mapOf("error" to (e.message ?: "unknown")),
            )
            null
        }

    /**
     * Get current application path for macOS .app bundle
     * Returns null if running in development mode or path cannot be determined
     */
    fun getCurrentApplicationPath(): String? {
        return try {
            logger.debug(LogCategory.SYSTEM, "Detecting current application path")

            // Method 1: Check java.library.path for .app bundle
            val libraryPath = System.getProperty("java.library.path")
            logger.trace(LogCategory.SYSTEM, "java.library.path", mapOf("path" to (libraryPath ?: "null")))

            val bundlePath = libraryPath?.let(::macOSAppBundlePathFromLibraryPath)

            if (bundlePath != null && File(bundlePath).exists()) {
                logger.debug(LogCategory.SYSTEM, "Found app bundle via library path", mapOf("path" to bundlePath))
                return resolveRealAppPath(bundlePath)
            }

            // Method 2: Try to find app bundle from current JAR/class location
            val jarPath = UpdateInstaller::class.java.protectionDomain.codeSource.location.path
            logger.trace(LogCategory.SYSTEM, "Current code source", mapOf("path" to jarPath))

            var currentFile = File(jarPath)
            // Walk up the directory tree looking for .app bundle
            for (i in 0..5) {
                logger.trace(LogCategory.SYSTEM, "Checking parent", mapOf("index" to i, "path" to currentFile.absolutePath))
                if (currentFile.name.endsWith(".app")) {
                    logger.debug(LogCategory.SYSTEM, "Found app bundle via directory traversal", mapOf("path" to currentFile.absolutePath))
                    return resolveRealAppPath(currentFile.absolutePath)
                }
                currentFile = currentFile.parentFile ?: break
            }

            // Method 3: Check if running from Applications folder
            val applicationsPath = "$MACOS_APPLICATIONS_DIRECTORY/$BOSS_MACOS_APP_BUNDLE_NAME"
            if (File(applicationsPath).exists()) {
                logger.debug(LogCategory.SYSTEM, "Found BOSS in Applications folder", mapOf("path" to applicationsPath))
                return applicationsPath
            }

            logger.debug(LogCategory.SYSTEM, "Could not determine application path - likely dev mode")
            null
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Error getting application path", error = e)
            null
        }
    }

    /**
     * Resolve a possibly *translocated* app path back to the real install location.
     *
     * macOS App Translocation (Gatekeeper path randomization) runs a quarantined,
     * "not-yet-moved-by-Finder" app from a **read-only, randomized, ephemeral** mount
     * under `/private/var/folders/.../T/AppTranslocation/<uuid>/d/<App>.app`. If we feed
     * that path to the update script it will:
     *   - `rm -rf` / `cp -R` into a read-only volume (every write fails), and
     *   - relaunch a path that no longer exists (macOS auto-unmounts the translocation
     *     mount the moment the app quits),
     * so the update silently fails and the app never restarts. See the update helper
     * script in [UpdateScriptGenerator] which also strips the quarantine attribute so
     * future launches are not translocated.
     *
     * When the given path is not translocated it is returned unchanged.
     */
    private fun resolveRealAppPath(path: String): String {
        if (!path.contains(APP_TRANSLOCATION_PATH_SEGMENT)) return path

        logger.warn(
            LogCategory.SYSTEM,
            "App is running translocated by Gatekeeper - resolving real install path",
            mapOf("translocatedPath" to path),
        )

        val resolvedPath =
            realAppPathFor(
                path = path,
                appExists = { File(it).exists() },
                installedAppLookup = ::findInstalledAppViaSpotlight,
            )

        if (resolvedPath != path) {
            logger.info(LogCategory.SYSTEM, "Resolved real app path", mapOf("path" to resolvedPath))
            return resolvedPath
        }

        // 3. Give up and keep the translocated path (update will likely still fail, but
        //    we have logged exactly why).
        logger.warn(LogCategory.SYSTEM, "Could not resolve real app path - falling back to translocated path")
        return path
    }

    /**
     * Locate the installed BOSS.app by its bundle identifier via Spotlight (`mdfind`),
     * skipping translocated mounts and nested helper/framework bundles.
     */
    private fun findInstalledAppViaSpotlight(): String? {
        return try {
            val process =
                ProcessBuilder(
                    "mdfind",
                    "kMDItemCFBundleIdentifier == '$BOSS_MACOS_BUNDLE_ID'",
                ).redirectErrorStream(true)
                    .start()

            // Drain output while mdfind runs so a full OS pipe buffer cannot
            // block the child and turn a successful lookup into a timeout.
            val outputFuture =
                CompletableFuture.supplyAsync {
                    process.inputStream.bufferedReader().use { it.readText() }
                }

            if (!process.waitFor(SPOTLIGHT_LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                outputFuture.cancel(true)
                logger.warn(
                    LogCategory.SYSTEM,
                    "mdfind lookup for installed app timed out",
                    mapOf("timeoutSeconds" to SPOTLIGHT_LOOKUP_TIMEOUT_SECONDS),
                )
                return null
            }

            val output = outputFuture.get(1, TimeUnit.SECONDS)

            if (process.exitValue() != 0) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "mdfind lookup for installed app failed",
                    mapOf("exitCode" to process.exitValue()),
                )
                return null
            }

            output
                .lineSequence()
                .map { it.trim() }
                .filter { it.endsWith(MACOS_APP_BUNDLE_SUFFIX) }
                .filterNot { it.contains(APP_TRANSLOCATION_PATH_SEGMENT) }
                .filterNot { it.contains("/Frameworks/") || it.contains("/Helpers/") }
                .firstOrNull { File(it).exists() }
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "mdfind lookup for installed app failed", error = e)
            null
        }
    }

    /**
     * Find the mounted BOSS volume after DMG mount
     */
    private fun findMountedBossVolume(): File? {
        val volumesDir = File("/Volumes")
        return volumesDir.listFiles()?.find {
            it.name.contains("BOSS", ignoreCase = true) && it.isDirectory
        }
    }

    /**
     * Find the .app bundle in the mounted volume
     */
    fun findAppBundleInVolume(mountedVolume: File): File? =
        mountedVolume.listFiles()?.find {
            it.name.endsWith(".app") && it.name.contains("BOSS", ignoreCase = true)
        }

    /**
     * Open DMG for manual installation (fallback for development mode)
     */
    private fun openDMGForManualInstallation(downloadFile: File): InstallResult =
        try {
            val process = ProcessBuilder("open", downloadFile.absolutePath).start()
            process.waitFor()
            logger.info(LogCategory.SYSTEM, "DMG opened for manual installation", mapOf("path" to downloadFile.absolutePath))
            InstallResult.Success("DMG opened for manual installation")
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Failed to open DMG", error = e)
            InstallResult.Error(e.message ?: "Failed to open DMG")
        }

    /**
     * Unmount a DMG volume
     */
    private fun cleanupDMG(mountedVolume: File?) {
        try {
            if (mountedVolume != null) {
                ProcessBuilder("hdiutil", "detach", mountedVolume.absolutePath, "-quiet")
                    .start()
                    .waitFor()
                logger.debug(LogCategory.SYSTEM, "DMG unmounted successfully")
            } else {
                // Try to unmount any BOSS volume
                val bossVolume = findMountedBossVolume()
                if (bossVolume != null) {
                    ProcessBuilder("hdiutil", "detach", bossVolume.absolutePath, "-quiet")
                        .start()
                        .waitFor()
                    logger.debug(LogCategory.SYSTEM, "DMG unmounted successfully")
                }
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Could not unmount DMG", error = e)
        }
    }

    /**
     * Get the current JAR path (for JAR updates)
     */
    private fun getCurrentJarPath(): File? =
        try {
            val jarPath =
                UpdateInstaller::class.java.protectionDomain.codeSource.location
                    .toURI()
                    .path
            val jarFile = File(jarPath)
            if (jarFile.exists() && jarFile.name.endsWith(".jar")) {
                jarFile
            } else {
                null
            }
        } catch (e: Exception) {
            logger.debug(
                LogCategory.SYSTEM,
                "Could not determine current JAR path - not running from a JAR",
                mapOf("error" to e.toString()),
            )
            null
        }

    /**
     * Detect Linux distribution type (deb-based or rpm-based)
     */
    private fun detectLinuxDistroType(): String {
        return try {
            // Check for dpkg (Debian/Ubuntu)
            val dpkgCheck = ProcessBuilder("which", "dpkg").start()
            dpkgCheck.waitFor()
            if (dpkgCheck.exitValue() == 0) {
                return "Linux-deb"
            }

            // Check for rpm (Fedora/RHEL/CentOS)
            val rpmCheck = ProcessBuilder("which", "rpm").start()
            rpmCheck.waitFor()
            if (rpmCheck.exitValue() == 0) {
                return "Linux-rpm"
            }

            // Check /etc/os-release for more info
            val osRelease = File("/etc/os-release")
            if (osRelease.exists()) {
                val content = osRelease.readText().lowercase()
                return when {
                    content.contains("debian") || content.contains("ubuntu") ||
                        content.contains("mint") || content.contains("pop") -> "Linux-deb"

                    content.contains("fedora") || content.contains("rhel") ||
                        content.contains("centos") || content.contains("rocky") ||
                        content.contains("alma") -> "Linux-rpm"

                    else -> "Linux"
                }
            }

            "Linux"
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Could not detect Linux distro type", error = e)
            "Linux"
        }
    }

    /**
     * Get the current operating system platform
     */
    fun getCurrentPlatform(): String {
        val osName = System.getProperty("os.name").lowercase(Locale.getDefault())
        return when {
            osName.contains("mac") || osName.contains("darwin") -> "macOS"
            osName.contains("win") -> "Windows"
            osName.contains("linux") -> detectLinuxDistroType()
            else -> "Unknown"
        }
    }
}
