package ai.rever.boss.config

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.VersionConstants
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.sha256Of
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipInputStream

/**
 * Utility for auto-downloading BOSS-branded Chromium binaries.
 *
 * Archives are fetched from Supabase Storage first with the BossConsole-Releases
 * GitHub repository as backup (see [ChromiumReleaseSource]), then extracted to
 * ~/.boss/boss-chromium/. Every install extracts into a sibling ".incoming"
 * directory first and swaps it into place only once the bundle is verified,
 * so a failed or interrupted extraction can never destroy the only working
 * engine (#910). Installs triggered from Settings while an engine is running
 * are staged into ~/.boss/boss-chromium.pending and swapped in on the next
 * startup by [promotePendingInstall].
 */
object ChromiumAutoDownloader {
    private val logger = BossLogger.forComponent("ChromiumAutoDownloader")

    // JxBrowser version from generated VersionConstants (source: gradle/libs.versions.toml)
    private val JXBROWSER_VERSION = VersionConstants.JXBROWSER_VERSION
    private const val VERSION_FILE = "version.txt"

    /**
     * Info.plist keys an engine bundle must not declare. See [declaresBrowserTypes].
     */
    private val BROWSER_TYPE_KEYS = listOf("CFBundleURLTypes", "CFBundleDocumentTypes")

    // Commit marker for staged installs: written strictly last by downloadChromium
    // (staged=true), required by promotePendingInstall. Guards against promoting a
    // staging dir whose executable.name/version.txt happen to exist (e.g. extracted
    // from the archive itself) but whose extraction never actually completed.
    private const val STAGED_COMPLETE_MARKER = ".staging-complete"

    // Suffix of the sibling directory [installFromCandidates] extracts into
    // before atomically swapping it into the engine directory. Extracting
    // straight over the live directory let any mid-extraction failure — a
    // corrupted archive, a full disk, an antivirus lock — delete the only
    // working engine with no rollback (#910).
    private const val INCOMING_DIR_SUFFIX = ".incoming"

    // Suffix of the sibling directory the current engine is renamed aside
    // into during a swap, deleted only once the new engine is in place — the
    // same shape [promotePendingInstall] uses ("boss-chromium.old").
    private const val OLD_DIR_SUFFIX = ".old"

    /** The engine version matching this build's bundled JxBrowser library. */
    val defaultVersion: String get() = JXBROWSER_VERSION

    /** The engine version to install/run: Settings pin, else [defaultVersion]. */
    val effectiveVersion: String get() = BrowserEngineSettingsManager.effectiveVersion

    /**
     * Download progress information
     */
    data class DownloadProgress(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val isComplete: Boolean = false,
        val isExtracting: Boolean = false,
        val error: String? = null,
    ) {
        val progressFraction: Float
            get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f

        val downloadedMB: Long
            get() = bytesDownloaded / (1024 * 1024)

        val totalMB: Long
            get() = totalBytes / (1024 * 1024)
    }

    /**
     * Get the target directory for Chromium installation
     */
    fun getChromiumDir(): Path = BossDirectories.resolve("boss-chromium").toPath()

    /** Staging directory for engine installs done while the current engine is running. */
    fun getPendingChromiumDir(): Path = BossDirectories.resolve("boss-chromium.pending").toPath()

    /** The version of the currently installed engine, or null if none/unknown. */
    fun installedVersion(): String? = installedVersionAt(getChromiumDir())

    /**
     * The version stamp in [dir], or null when there isn't one.
     *
     * Generalised from the cache-only reader so the bundled engine can be checked
     * with the same rule. The stamp is the only version signal that works on every
     * platform — the framework-layout probe FluckEngine uses is macOS-specific — so
     * without it a bundled engine gets no version check at all off macOS
     * (BossConsole#123).
     */
    fun installedVersionAt(dir: Path): String? {
        val versionFile = dir.resolve(VERSION_FILE).toFile()
        return try {
            if (versionFile.exists()) versionFile.readText().trim().takeIf { it.isNotEmpty() } else null
        } catch (e: Exception) {
            logger.debug(
                LogCategory.BROWSER,
                "Could not read installed Chromium version marker",
                mapOf("error" to e.toString()),
            )
            null
        }
    }

    /**
     * Swap in an engine staged by a Settings-triggered install (see [downloadChromium]
     * with staged=true). Must be called at startup BEFORE [isChromiumInstalled] /
     * engine creation, while nothing holds files in the engine directory.
     *
     * Also the startup housekeeper for swap leftovers: an install interrupted
     * mid-extraction parks content in a sibling ".incoming" directory (reclaimed
     * here), and a swap interrupted after moving the current engine aside left
     * that engine in the backup with no engine directory in its place — restored
     * here so the user keeps the last working engine instead of a forced
     * re-download.
     */
    fun promotePendingInstall() {
        promotePendingInstall(
            pending = getPendingChromiumDir().toFile(),
            target = getChromiumDir().toFile(),
            backup = BossDirectories.resolve("boss-chromium.old"),
        )
    }

    // Directory params are injectable for tests.
    internal fun promotePendingInstall(
        pending: File,
        target: File,
        backup: File,
    ) {
        // Reclaim the disk of an install interrupted mid-extraction: content
        // lands in a sibling ".incoming" directory (see [installFromCandidates])
        // that nothing else reads, whether or not the attempt got far enough
        // to create the pending or target directory.
        incomingDirFor(pending.toPath()).toFile().deleteRecursively()
        incomingDirFor(target.toPath()).toFile().deleteRecursively()

        if (!pending.exists()) {
            // A swap — this promotion or the reinstall in installFromCandidates —
            // that crashed after moving the current engine aside left it parked in
            // the backup with the target directory gone. Restore the only
            // working engine instead of forcing a full re-download.
            if (!target.exists() && backup.exists() && backup.renameTo(target)) {
                logger.warn(
                    LogCategory.BROWSER,
                    "Restored the engine left aside by an interrupted swap",
                )
            }
            return
        }

        try {
            if (!pending.resolve(STAGED_COMPLETE_MARKER).exists()) {
                // Interrupted staged download — the marker is written strictly last,
                // so its absence means extraction never completed. Discard.
                pending.deleteRecursively()
                logger.info(LogCategory.BROWSER, "Discarded incomplete pending engine install")
                return
            }

            // Swap via a backup dir so a failed rename (File.renameTo is
            // platform-dependent — lingering handles/AV on Windows can make it
            // return false) never destroys the only working engine: the current
            // engine is moved aside, restored if promotion fails, and deleted
            // only after the staged engine is in place.
            if (backup.exists()) backup.deleteRecursively() // stale backup from an earlier failed swap

            if (target.exists() && !target.renameTo(backup)) {
                logger.warn(
                    LogCategory.BROWSER,
                    "Could not move current engine aside; keeping staged install for next launch",
                )
                return
            }
            if (pending.renameTo(target)) {
                target.resolve(STAGED_COMPLETE_MARKER).delete()
                backup.deleteRecursively()
                logger.info(
                    LogCategory.BROWSER,
                    "Promoted pending engine install",
                    mapOf(
                        "version" to (
                            target
                                .resolve(VERSION_FILE)
                                .takeIf { it.exists() }
                                ?.readText()
                                ?.trim() ?: "unknown"
                        ),
                    ),
                )
            } else if (backup.exists() && backup.renameTo(target)) {
                logger.warn(
                    LogCategory.BROWSER,
                    "Could not promote pending engine install; previous engine restored",
                )
            } else {
                logger.error(
                    LogCategory.BROWSER,
                    "Engine swap failed and previous engine could not be restored; startup will re-download",
                )
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Error promoting pending engine install", error = e)
        }
    }

    /**
     * Check if Chromium is already installed, valid, and matches the effective
     * engine version (Settings pin, else the bundled JxBrowser version).
     */
    fun isChromiumInstalled(): Boolean = chromiumInstalledAt(recordRepair = ::recordRepairAttempt)

    /** Check the cache without consuming the startup repair attempt. Safe for status queries. */
    internal fun isChromiumInstalledReadOnly(): Boolean = chromiumInstalledAt(recordRepair = {})

    internal fun chromiumInstalledAt(
        dir: Path = getChromiumDir(),
        requiredVersion: String = effectiveVersion,
        isMac: Boolean = System.getProperty("os.name").lowercase().contains("mac"),
        repairAttempted: () -> Boolean = ::repairAlreadyAttempted,
        recordRepair: () -> Unit,
    ): Boolean {
        if (!dir.toFile().exists()) return false

        // Check executable.name exists (required by JxBrowser)
        val executableNameFile = dir.resolve("executable.name").toFile()
        if (!executableNameFile.exists()) return false

        // Check version matches current JxBrowser version
        val versionFile = dir.resolve(VERSION_FILE).toFile()
        if (!versionFile.exists()) {
            logger.debug(LogCategory.BROWSER, "Chromium version file not found, will re-download")
            return false
        }

        val installedVersion = versionFile.readText().trim()
        if (installedVersion != requiredVersion) {
            logger.info(
                LogCategory.BROWSER,
                "Chromium version mismatch",
                mapOf(
                    "installed" to installedVersion,
                    "required" to requiredVersion,
                ),
            )
            return false
        }

        // On macOS, verify the executable has proper permissions
        // This catches cached Chromium from older versions that didn't set execute bit correctly
        if (isMac) {
            val executableName = executableNameFile.readText().trim()
            // executable.name holds the bundle name without its suffix (the branding
            // workflow writes `basename "$APP_BUNDLE" .app`), so the directory on
            // disk is "<name>.app". Without it this resolved to a path that never
            // exists and the permission check below silently never ran.
            val executablePath = dir.resolve("$executableName.app/Contents/MacOS/$executableName").toFile()
            if (executablePath.exists() && !executablePath.canExecute()) {
                logger.info(LogCategory.BROWSER, "Chromium executable missing execute permission, will re-download")
                return false
            }

            if (declaresBrowserTypes(dir.resolve("$executableName.app/Contents/Info.plist").toFile())) {
                // Bounded to one attempt per version. Without this, an engine that
                // still declares the keys after the re-download - the rebuild never
                // published, or published unrepaired - would invalidate the
                // directory on EVERY launch and re-fetch ~160 MB forever, silently.
                // The marker lives outside the engine directory because a
                // re-download replaces that whole directory.
                if (repairAttempted()) {
                    logger.warn(
                        LogCategory.BROWSER,
                        "Chromium still registers itself as a browser after a re-download; keeping it",
                        mapOf("version" to requiredVersion),
                    )
                } else {
                    recordRepair()
                    logger.info(
                        LogCategory.BROWSER,
                        "Cached Chromium still registers itself as a browser, will re-download",
                        mapOf("version" to requiredVersion),
                    )
                    return false
                }
            }
        }

        return true
    }

    /**
     * Whether an extracted engine bundle still claims URL schemes or document
     * types from the OS.
     *
     * **Why this is a content check and not a version check.** Engines built
     * before `build-chromium-branding.yml` learned to strip these keys declare
     * http, https, `file` and 17 document types (`public.html`, `public.text`,
     * `com.adobe.pdf`, the image and video types, ...) under the id
     * `ai.rever.boss.browser` and the name "BOSS". Launch Services then offers
     * two indistinguishable "BOSS" entries for the default browser and puts the
     * engine in Finder's Open With for a dozen file kinds, where choosing it
     * launches a rendering engine with no window. See AGENTS.md.
     *
     * The version number cannot detect that. The fix ships inside a *rebuild* of
     * an already-published engine, so `version.txt` reads the same before and
     * after and [isValidChromiumDir]'s equality test short-circuits. Asking the
     * bundle what it actually declares is the only thing that tells a repaired
     * engine from the one it replaced, and it is self-limiting: once the clean
     * engine is in place this answers false forever, on every version.
     *
     * Deliberately a substring test rather than a plist parse. The engine's
     * `Info.plist` is XML (verified against a shipped 9.5.0 bundle), so
     * `<key>NAME</key>` is exact and unambiguous, and the JDK has no binary-plist
     * reader to fall back on. **Fails closed**: an unreadable or absent plist
     * answers false, because forcing a ~160 MB download on a file we could not
     * read would be the worse mistake - and a genuinely broken bundle is already
     * caught by the executable checks above.
     */

    /** Marker recording that a browser-types re-download was already tried for a version. */
    private fun repairMarker(): File = BossDirectories.resolve("boss-chromium.types-repair")

    private fun repairAlreadyAttempted(): Boolean =
        try {
            repairMarker().takeIf { it.isFile }?.readText()?.trim() == effectiveVersion
        } catch (e: Exception) {
            logger.debug(
                LogCategory.BROWSER,
                "Could not read the repair marker",
                mapOf("reason" to (e.message ?: "unknown")),
            )
            false
        }

    private fun recordRepairAttempt() {
        try {
            repairMarker().writeText(effectiveVersion)
        } catch (e: Exception) {
            // Logged, not fatal. The consequence of failing to write it is one
            // extra download attempt on the next launch, not a broken engine.
            logger.warn(LogCategory.BROWSER, "Could not record the repair marker", error = e)
        }
    }

    internal fun declaresBrowserTypes(plist: File): Boolean =
        try {
            if (!plist.isFile) {
                false
            } else {
                val text = plist.readText()
                BROWSER_TYPE_KEYS.any { key -> text.contains("<key>$key</key>") }
            }
        } catch (e: Exception) {
            logger.debug(
                LogCategory.BROWSER,
                "Could not read the engine Info.plist; assuming it is fine",
                mapOf("reason" to (e.message ?: "unknown")),
            )
            false
        }

    /**
     * Detect the current platform for download URL.
     * Must match the file names in BossConsole-Releases:
     * - boss-chromium-macos-arm64.zip
     * - boss-chromium-macos-x64.zip
     * - boss-chromium-windows-x64.zip
     * - boss-chromium-windows-arm64.zip
     * - boss-chromium-linux-x64.zip
     * - boss-chromium-linux-arm64.zip
     */
    fun detectPlatform(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        return when {
            os.contains("mac") && (arch.contains("aarch64") || arch.contains("arm64")) -> {
                "macos-arm64"
            }

            os.contains("mac") -> {
                "macos-x64"
            }

            os.contains("win") && (arch.contains("aarch64") || arch.contains("arm64")) -> {
                "windows-arm64"
            }

            os.contains("win") -> {
                "windows-x64"
            }

            os.contains("linux") && (arch.contains("aarch64") || arch.contains("arm64")) -> {
                "linux-arm64"
            }

            os.contains("linux") -> {
                "linux-x64"
            }

            else -> {
                logger.warn(LogCategory.BROWSER, "Unknown platform, defaulting to linux-x64", mapOf("os" to os, "arch" to arch))
                "linux-x64"
            }
        }
    }

    /**
     * Download and install the effective engine version with progress reporting.
     *
     * @param onProgress Callback for download progress updates
     * @return Result containing the installation path on success, or an exception on failure
     */
    suspend fun downloadChromium(onProgress: (DownloadProgress) -> Unit): Result<Path> =
        downloadChromium(effectiveVersion, staged = false, onProgress)

    /**
     * Download and install a specific engine version, trying each release source
     * in order (Supabase primary, GitHub backup).
     *
     * @param version Engine version to install (e.g. "9.1.2")
     * @param staged When true, extract into [getPendingChromiumDir] instead of the
     *   live engine directory — required when an engine is currently running (its
     *   files can't be safely deleted/replaced, especially on Windows). The staged
     *   install is applied by [promotePendingInstall] on next startup.
     * @param onProgress Callback for download progress updates
     * @return Result containing the installation path on success, or an exception on failure
     */
    suspend fun downloadChromium(
        version: String,
        staged: Boolean = false,
        onProgress: (DownloadProgress) -> Unit,
    ): Result<Path> =
        withContext(Dispatchers.IO) {
            val archiveName = "boss-chromium-${detectPlatform()}.zip"
            installFromCandidates(
                candidates = ChromiumReleaseSource.downloadCandidates(version, archiveName),
                version = version,
                targetDir = if (staged) getPendingChromiumDir() else getChromiumDir(),
                staged = staged,
                onProgress = onProgress,
            )
        }

    /**
     * Try each download candidate in order: fetch, verify checksum (when the
     * catalog provides one), extract, stamp version. The transfer and extract
     * steps are injectable for tests.
     */
    internal suspend fun installFromCandidates(
        candidates: List<EngineDownloadCandidate>,
        version: String,
        targetDir: Path,
        staged: Boolean,
        onProgress: (DownloadProgress) -> Unit,
        fetch: (String, Path) -> Unit = { url, dest -> downloadWithProgress(url, dest, onProgress) },
        extract: (Path, Path) -> Unit = { zip, dest -> extractZip(zip, dest) },
    ): Result<Path> {
        var lastError: Exception? = null
        for (candidate in candidates) {
            logger.info(
                LogCategory.BROWSER,
                "Downloading BOSS-branded Chromium",
                mapOf(
                    "source" to candidate.sourceName,
                    "url" to candidate.url,
                    "targetDir" to targetDir.toString(),
                ),
            )

            try {
                // Create parent directories
                Files.createDirectories(targetDir.parent)

                // Download to temp file with progress
                val tempFile = Files.createTempFile("boss-chromium-", ".zip")
                try {
                    fetch(candidate.url, tempFile)

                    // Integrity check before extracting a native binary we will
                    // execute. Like the app updater, this guards against
                    // Storage/CDN corruption. Both candidates carry the catalog
                    // hash when lookup provides one; a failed or hashless lookup
                    // leaves the backup unverified.
                    if (candidate.sha256 != null) {
                        val actualSha = sha256Of(tempFile.toFile())
                        if (!candidate.sha256.equals(actualSha, ignoreCase = true)) {
                            throw IllegalStateException(
                                "Engine archive checksum mismatch from ${candidate.sourceName} " +
                                    "(expected ${candidate.sha256}, got $actualSha)",
                            )
                        }
                        logger.info(
                            LogCategory.BROWSER,
                            "Engine archive checksum verified",
                            mapOf(
                                "source" to candidate.sourceName,
                            ),
                        )
                    } else {
                        logger.debug(
                            LogCategory.BROWSER,
                            "No checksum available for engine archive",
                            mapOf(
                                "source" to candidate.sourceName,
                            ),
                        )
                    }

                    // Update status to extracting
                    onProgress(DownloadProgress(0, 0, isExtracting = true))

                    // Extract into a sibling ".incoming" directory and only
                    // move it into place once the bundle is verified. This
                    // flow used to delete the installed engine before
                    // extracting, so any failure from here on — corrupted
                    // archive, full disk, an antivirus lock — left no engine
                    // at all. The incoming dir is a sibling rather than
                    // java.io.tmpdir because the final move-in is a rename,
                    // which must not cross a filesystem boundary (#910).
                    val incomingDir = incomingDirFor(targetDir)
                    incomingDir.toFile().deleteRecursively() // partial content from an interrupted attempt
                    try {
                        extract(tempFile, incomingDir)

                        // Verify extraction produced executable.name before
                        // the installed engine is disturbed in any way.
                        val executableNameFile = incomingDir.resolve("executable.name").toFile()
                        if (!executableNameFile.exists()) {
                            throw IllegalStateException(
                                "Extraction completed but executable.name not found. " +
                                    "The downloaded archive may be corrupted.",
                            )
                        }

                        // Write version file to track installed version
                        incomingDir.resolve(VERSION_FILE).toFile().writeText(version)
                        logger.debug(LogCategory.BROWSER, "Version file written", mapOf("version" to version))

                        // Written strictly last, before the swap:
                        // promotePendingInstall refuses staging dirs without
                        // this marker, and the rename carries it — and the
                        // version stamp above — into place.
                        if (staged) {
                            incomingDir.resolve(STAGED_COMPLETE_MARKER).toFile().writeText(version)
                        }

                        // Swap the verified bundle in; the current engine
                        // moves aside, the new one takes its place, and any
                        // failure restores the previous engine untouched.
                        swapIncomingEngine(incomingDir, targetDir)
                    } finally {
                        // No-op once the swap has renamed the directory into
                        // place; reclaims the disk of a failed attempt.
                        incomingDir.toFile().deleteRecursively()
                    }

                    // Clean up old JxBrowser default Chromium directory if it exists
                    cleanupOldChromium()

                    // Small delay after extraction to let file system sync
                    // This helps avoid a race condition in JxBrowser's IPC layer
                    // that can cause crashes on first launch after extraction
                    kotlinx.coroutines.delay(500)

                    logger.info(
                        LogCategory.BROWSER,
                        "BOSS-branded Chromium installed successfully",
                        mapOf(
                            "source" to candidate.sourceName,
                            "version" to version,
                            "path" to targetDir.toString(),
                        ),
                    )
                    onProgress(DownloadProgress(0, 0, isComplete = true))
                    return Result.success(targetDir)
                } finally {
                    // Clean up temp file
                    try {
                        Files.deleteIfExists(tempFile)
                    } catch (e: Exception) {
                        logger.debug(
                            LogCategory.BROWSER,
                            "Could not delete temp file",
                            mapOf("error" to e.toString()),
                        )
                    }
                }
            } catch (e: Exception) {
                lastError = e
                logger.warn(
                    LogCategory.BROWSER,
                    "Chromium download failed from source",
                    mapOf(
                        "source" to candidate.sourceName,
                    ),
                    error = e,
                )
            }
        }

        val error = lastError ?: IllegalStateException("No engine download sources available")
        logger.error(LogCategory.BROWSER, "Chromium download failed from all sources", error = error)
        onProgress(DownloadProgress(0, 0, error = error.message ?: "Unknown error"))
        return Result.failure(error)
    }

    /**
     * The sibling ".incoming" directory [installFromCandidates] extracts into
     * before atomically swapping it into [dir]. A sibling of the target
     * (not java.io.tmpdir) because the swap is a rename, which must stay
     * within one filesystem.
     */
    internal fun incomingDirFor(dir: Path): Path = dir.resolveSibling(dir.fileName.toString() + INCOMING_DIR_SUFFIX)

    /**
     * Move a fully extracted, verified engine from its ".incoming" sibling
     * directory into [targetDir] without ever leaving a window where no
     * engine exists — the directory counterpart of the house
     * `atomicWriteText` file swap, and the same move-aside / promote /
     * restore shape [promotePendingInstall] uses for staged installs.
     *
     * The current engine is renamed aside and deleted only once the new
     * directory is in place; if the move-in fails, the previous engine is
     * restored. All three directories are siblings, so every step is a
     * same-filesystem rename.
     */
    internal fun swapIncomingEngine(
        incoming: Path,
        targetDir: Path,
    ) {
        val target = targetDir.toFile()
        val backup = targetDir.resolveSibling(targetDir.fileName.toString() + OLD_DIR_SUFFIX).toFile()

        // Leftover from an earlier swap of the same target that crashed or failed.
        if (backup.exists()) backup.deleteRecursively()

        check(!target.exists() || target.renameTo(backup)) {
            "Could not move the current engine aside for the swap; keeping it (target=$targetDir)."
        }

        if (incoming.toFile().renameTo(target)) {
            if (backup.exists() && !backup.deleteRecursively()) {
                logger.warn(
                    LogCategory.BROWSER,
                    "Could not delete the previous engine directory after the swap",
                    mapOf("path" to backup.toString()),
                )
            }
            return
        }

        // The new engine could not be moved in. Restore the previous one, or
        // report that even that failed — promotePendingInstall retries the
        // restore from the backup on the next startup.
        check(!backup.exists() || backup.renameTo(target)) {
            "Engine swap failed and the previous engine could not be restored (backup=$backup)."
        }
        error("Engine swap failed; the previous engine was restored.")
    }

    /**
     * Download a file with progress reporting
     */
    private fun downloadWithProgress(
        urlString: String,
        targetPath: Path,
        onProgress: (DownloadProgress) -> Unit,
    ) {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 30000
        connection.readTimeout = 60000
        connection.setRequestProperty("User-Agent", "BOSS-App")

        // Follow redirects (GitHub releases use redirects)
        connection.instanceFollowRedirects = true

        try {
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP error: $responseCode ${connection.responseMessage}")
            }

            val totalBytes = connection.contentLengthLong
            var bytesDownloaded = 0L

            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(targetPath.toFile()).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        bytesDownloaded += bytesRead

                        // Report progress
                        onProgress(DownloadProgress(bytesDownloaded, totalBytes))
                    }
                }
            }

            logger.debug(LogCategory.BROWSER, "Download complete", mapOf("sizeMB" to bytesDownloaded / (1024 * 1024)))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Extract a zip file to a target directory.
     * On macOS, uses native `ditto` to preserve symlinks, resource forks,
     * and code signatures. Java's ZipInputStream breaks macOS framework
     * symlinks (e.g. Versions/Current), causing Chromium startup failures.
     */
    private fun extractZip(
        zipPath: Path,
        targetDir: Path,
    ) {
        logger.debug(LogCategory.BROWSER, "Extracting Chromium", mapOf("targetDir" to targetDir.toString()))
        Files.createDirectories(targetDir)

        if (System.getProperty("os.name").lowercase().contains("mac")) {
            extractWithDitto(zipPath, targetDir)
        } else {
            extractWithJava(zipPath, targetDir)
        }

        logger.debug(LogCategory.BROWSER, "Extraction complete")
    }

    /**
     * Extract using macOS native `ditto` which preserves symlinks and code signatures.
     *
     * There is deliberately no Java fallback: ZipInputStream materializes
     * symlink entries as small plain text files, so the extracted bundle
     * would have text files where `Chromium Framework.framework/Versions/Current`
     * and friends must be — an install that reports success and leaves an
     * engine that never boots (#910). A `ditto` failure fails the install so
     * the error reaches the retry dialog; the next attempt (or the next
     * candidate source) gets a fresh `ditto` run instead.
     *
     * @param runDitto Runs the extraction; returns the exit code and the merged
     *   process output. Injectable for tests.
     */
    internal fun extractWithDitto(
        zipPath: Path,
        targetDir: Path,
        runDitto: (Path, Path) -> Pair<Int, String> = ::launchDitto,
    ) {
        val (exitCode, output) = runDitto(zipPath, targetDir)
        if (exitCode != 0) {
            logger.error(
                LogCategory.BROWSER,
                "ditto extraction failed; refusing the known-broken Java fallback on macOS",
                mapOf("exitCode" to exitCode, "output" to output),
            )
            throw IllegalStateException(
                "ditto extraction failed (exit code $exitCode); the engine was not installed. " +
                    "The Java extractor cannot preserve the macOS framework symlinks, " +
                    "so it is not used as a fallback.",
            )
        }
    }

    /** Runs macOS `ditto -xk`; returns its exit code and merged stdout/stderr. */
    private fun launchDitto(
        zipPath: Path,
        targetDir: Path,
    ): Pair<Int, String> {
        val process =
            ProcessBuilder("ditto", "-xk", zipPath.toString(), targetDir.toString())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    /**
     * Extract using Java's ZipInputStream (non-macOS platforms — see
     * [extractWithDitto] for why macOS never falls back to this).
     */
    private fun extractWithJava(
        zipPath: Path,
        targetDir: Path,
    ) {
        ZipInputStream(Files.newInputStream(zipPath)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val targetPath = targetDir.resolve(entry.name).normalize()

                // Security check: prevent zip slip attack
                if (!targetPath.startsWith(targetDir)) {
                    throw SecurityException("Zip entry outside target directory: ${entry.name}")
                }

                if (entry.isDirectory) {
                    Files.createDirectories(targetPath)
                } else {
                    // Ensure parent directories exist
                    Files.createDirectories(targetPath.parent)

                    Files.newOutputStream(targetPath).use { output ->
                        zis.copyTo(output)
                    }

                    // Preserve executable bit on Unix
                    if (!System.getProperty("os.name").lowercase().contains("win")) {
                        val name = entry.name.lowercase()
                        val isMacOSExecutable = name.contains(".app/contents/macos/")
                        val isChromium = name.contains("chromium") || name.contains("boss")
                        val isSharedLib = name.endsWith(".so")
                        val isShellScript = name.endsWith(".sh")
                        val fileName = targetPath.fileName.toString()
                        val hasNoExtension = !fileName.contains(".")

                        if (isMacOSExecutable || isChromium || isSharedLib || isShellScript || hasNoExtension) {
                            targetPath.toFile().setExecutable(true)
                        }
                    }
                }

                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * Clean up old JxBrowser default Chromium directory to save disk space.
     * This removes the unbranded Chromium that JxBrowser may have downloaded
     * before we switched to branded Chromium.
     */
    private fun cleanupOldChromium() {
        val oldDir = BossDirectories.resolve("jxbrowser-chromium").toPath()
        if (oldDir.toFile().exists()) {
            logger.debug(LogCategory.BROWSER, "Cleaning up old JxBrowser Chromium", mapOf("path" to oldDir.toString()))
            try {
                oldDir.toFile().deleteRecursively()
                logger.info(LogCategory.BROWSER, "Old Chromium directory cleaned up (~500MB freed)")
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "Could not clean up old Chromium", error = e)
                // Non-fatal - don't fail the download
            }
        }
    }
}
