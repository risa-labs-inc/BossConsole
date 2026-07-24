package ai.rever.boss.updater

import ai.rever.boss.config.UpdateSourceConfig
import ai.rever.boss.updater.source.FallbackUpdateSource
import ai.rever.boss.updater.source.GitHubUpdateSource
import ai.rever.boss.updater.source.SupabaseUpdateSource
import ai.rever.boss.updater.source.UpdateSource
import ai.rever.boss.utils.AppVersion
import ai.rever.boss.utils.ApplicationRestarter
import ai.rever.boss.utils.Version
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.sha256Of
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.network.sockets.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

actual class UpdateService {
    private val logger = BossLogger.forComponent("UpdateService")

    /**
     * Where release metadata comes from. Supabase is primary (Realtime-fed catalog
     * in the `app_releases` table); GitHub is the automatic backup. Overridable via
     * BOSS_UPDATE_PRIMARY_SOURCE for testing/rollback.
     */
    private val source: UpdateSource = buildSource()

    /** Dedicated GitHub source used only to recover a download if the primary URL fails. */
    private val gitHubSource = GitHubUpdateSource()

    private fun buildSource(): UpdateSource =
        when (UpdateSourceConfig.primarySource) {
            "github" -> GitHubUpdateSource()
            "supabase-only" -> SupabaseUpdateSource()
            else -> FallbackUpdateSource(primary = SupabaseUpdateSource(), backup = GitHubUpdateSource())
        }.also {
            logger.info(LogCategory.SYSTEM, "Update source configured", mapOf("source" to it.name))
        }

    // HTTP client for file downloads - long timeouts for large files
    private val downloadClient =
        HttpClient(CIO) {
            install(HttpTimeout) {
                // Allow up to 15 minutes for entire download (for slow connections)
                // 275MB at 500KB/s = ~9 minutes, so 15 min provides buffer
                requestTimeoutMillis = 900_000 // 15 minutes

                // Connection establishment should be quick
                connectTimeoutMillis = 30_000 // 30 seconds

                // Socket timeout: max time between data packets
                // Ensures connection stays alive during continuous download
                socketTimeoutMillis = 60_000 // 60 seconds
            }
        }

    /** Safe "no update" result used when the catalog is empty or a check fails. */
    private fun upToDate(): UpdateInfo =
        UpdateInfo(
            available = false,
            currentVersion = AppVersion.CURRENT,
            latestVersion = AppVersion.CURRENT,
            releaseNotes = "",
        )

    actual suspend fun checkForUpdates(): UpdateInfo {
        return try {
            val releases = source.listReleases()

            // Determine whether to include pre-releases:
            // 1. If user explicitly enabled prerelease updates, include them
            // 2. If current version is a prerelease, always include prereleases (so beta users get beta updates)
            val includePreReleases =
                UpdateSettings.includePreReleases ||
                    AppVersion.CURRENT.preRelease != null

            // Get the latest version based on prerelease preference
            val latestRelease =
                releases
                    .filter { release ->
                        !release.draft && (includePreReleases || !release.prerelease)
                    }.mapNotNull { release ->
                        Version.parse(release.tag_name)?.let { version -> release to version }
                    }.maxByOrNull { it.second }
                    ?.first
                    ?: return upToDate()

            val latestVersion = Version.parse(latestRelease.tag_name) ?: return upToDate()
            val isUpdateAvailable = latestVersion.isNewerThan(AppVersion.CURRENT)

            // Find the appropriate asset for the current platform
            val platform = getCurrentPlatform()
            val expectedAssetName = getExpectedAssetName(latestVersion)
            logger.debug(
                LogCategory.SYSTEM,
                "Looking for update asset",
                mapOf(
                    "expected" to expectedAssetName,
                    "platform" to platform,
                    "available" to latestRelease.assets.map { it.name }.joinToString(),
                ),
            )

            var asset =
                latestRelease.assets.find {
                    it.name.equals(expectedAssetName, ignoreCase = true)
                }

            // Fallback: If platform-specific package (.deb/.rpm) not found, try JAR
            if (asset == null && (platform == "Linux-deb" || platform == "Linux-rpm")) {
                val jarAssetName = "BOSS-$latestVersion-${getLinuxArchSuffix()}.jar"
                logger.debug(LogCategory.SYSTEM, "Platform package not found, trying JAR fallback", mapOf("jarAsset" to jarAssetName))
                asset =
                    latestRelease.assets.find {
                        it.name.equals(jarAssetName, ignoreCase = true)
                    }
            }

            if (asset == null) {
                logger.warn(LogCategory.SYSTEM, "Expected asset not found in release", mapOf("expected" to expectedAssetName))
            } else {
                logger.debug(LogCategory.SYSTEM, "Found update asset", mapOf("name" to asset.name))
            }

            UpdateInfo(
                available = isUpdateAvailable,
                currentVersion = AppVersion.CURRENT,
                latestVersion = latestVersion,
                releaseNotes = latestRelease.body,
                downloadUrl = asset?.browser_download_url,
                assetSize = asset?.size ?: 0,
                assetName = asset?.name ?: "",
                sha256 = asset?.sha256,
            )
        } catch (e: Exception) {
            val errorMessage =
                when {
                    e.message?.contains("rate limit", ignoreCase = true) == true -> {
                        "Update API rate limit exceeded. Please try again later."
                    }

                    e.message?.contains("JSON", ignoreCase = true) == true -> {
                        "Error parsing update information. Please try again later."
                    }

                    else -> {
                        "Unable to check for updates: ${e.message?.take(100) ?: "Unknown error"}"
                    }
                }
            logger.error(LogCategory.NETWORK, "Error checking for updates", mapOf("error" to errorMessage))
            upToDate()
        }
    }

    actual suspend fun downloadUpdate(
        updateInfo: UpdateInfo,
        onProgress: (progress: Float) -> Unit,
    ): String? {
        val primaryUrl = updateInfo.downloadUrl
        if (primaryUrl == null) {
            logger.error(LogCategory.NETWORK, "No download URL available", mapOf("asset" to updateInfo.assetName))
            return null
        }

        // Try the source-provided URL first (Supabase Storage when Supabase is primary).
        downloadFrom(primaryUrl, updateInfo.assetName, updateInfo.assetSize, updateInfo.sha256, onProgress)
            ?.let { return it }

        // The fallback chain is metadata-only: once Supabase serves the catalog, the
        // download URL is a Storage URL with no automatic recovery. If that download
        // fails (e.g. the bucket isn't public/reachable) recover via the GitHub asset
        // for the same version — unless that's already the URL we just tried.
        val gitHubUrl = gitHubAssetUrlFor(updateInfo.latestVersion)
        if (gitHubUrl != null && gitHubUrl != primaryUrl) {
            logger.warn(
                LogCategory.NETWORK,
                "Primary download failed; falling back to GitHub asset",
                mapOf(
                    "asset" to updateInfo.assetName,
                ),
            )
            return downloadFrom(gitHubUrl, updateInfo.assetName, updateInfo.assetSize, sha256 = null, onProgress)
        }
        return null
    }

    /** Download [url] to a temp file, verifying [sha256] when provided. Returns the path or null. */
    private suspend fun downloadFrom(
        url: String,
        assetName: String,
        assetSize: Long,
        sha256: String?,
        onProgress: (progress: Float) -> Unit,
    ): String? =
        try {
            logger.info(LogCategory.SYSTEM, "Starting update download", mapOf("asset" to assetName, "size" to assetSize))

            val tempDir = File(System.getProperty("java.io.tmpdir"), "boss-updates")
            tempDir.mkdirs()

            val downloadFile = File(tempDir, assetName)
            if (downloadFile.exists()) {
                downloadFile.delete()
            }

            streamToFile(url, assetSize, downloadFile, onProgress)

            if (downloadFile.exists() && downloadFile.length() > 0) {
                // Integrity check, NOT authenticity: the hash and URL come from the same
                // app_releases row, so this guards against Storage/CDN corruption, not a
                // compromised catalog. Update authenticity still rests on OS code-signing.
                val actualSha = if (sha256 != null) sha256Of(downloadFile) else null
                if (sha256 != null && !sha256.equals(actualSha, ignoreCase = true)) {
                    logger.error(
                        LogCategory.SYSTEM,
                        "Update checksum mismatch; discarding download",
                        mapOf(
                            "asset" to assetName,
                            "expected" to sha256,
                            "actual" to (actualSha ?: ""),
                        ),
                    )
                    downloadFile.delete()
                    null
                } else {
                    if (sha256 != null) {
                        logger.info(LogCategory.SYSTEM, "Update checksum verified", mapOf("asset" to assetName))
                    }
                    logger.info(LogCategory.SYSTEM, "Update downloaded successfully", mapOf("path" to downloadFile.absolutePath))
                    downloadFile.absolutePath
                }
            } else {
                logger.error(LogCategory.SYSTEM, "Download failed - file is empty or doesn't exist")
                null
            }
        } catch (e: Exception) {
            val errorMessage =
                when (e) {
                    is HttpRequestTimeoutException -> "Download timeout: File too large or connection too slow"
                    is ConnectTimeoutException -> "Connection timeout: Unable to reach download server"
                    is SocketTimeoutException -> "Network timeout: Download interrupted"
                    else -> e.message ?: "Unknown error"
                }
            logger.error(LogCategory.NETWORK, "Error downloading update", mapOf("error" to errorMessage))
            null
        }

    /** Resolve the GitHub Releases asset URL for [version] — the download-time backup. */
    private suspend fun gitHubAssetUrlFor(version: Version): String? =
        try {
            val release = gitHubSource.getReleaseByTag("v$version")
            val expected = getExpectedAssetName(version)
            release?.assets?.find { it.name.equals(expected, ignoreCase = true) }?.browser_download_url
        } catch (e: Exception) {
            logger.warn(LogCategory.NETWORK, "Could not resolve GitHub fallback asset", error = e)
            null
        }

    /** Compute the lowercase hex SHA-256 of [file]. */

    /**
     * Stream a download to [destFile], reporting throttled progress.
     *
     * Uses Ktor's [prepareGet]/[execute] streaming API and reads the body channel
     * INSIDE the execute lambda, so progress reflects bytes arriving off the socket.
     * The non-streaming `get()` would buffer the whole body into memory before
     * returning, making the bar jump straight to 100% at the end (issue #751). This
     * mirrors the existing streaming download in RemotePluginRepository.
     */
    private suspend fun streamToFile(
        url: String,
        expectedSize: Long,
        destFile: File,
        onProgress: (progress: Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        downloadClient.prepareGet(url).execute { response ->
            check(response.status.value in 200..299) {
                "Download failed (HTTP ${response.status.value} ${response.status.description})"
            }

            // Prefer the actual Content-Length; fall back to the size from the catalog.
            val totalSize = response.headers["Content-Length"]?.toLongOrNull() ?: expectedSize
            logger.trace(LogCategory.SYSTEM, "Download info", mapOf("totalSize" to totalSize, "expectedSize" to expectedSize))

            val channel = response.bodyAsChannel()
            destFile.outputStream().use { output ->
                var downloadedBytes = 0L
                val buffer = ByteArray(8192)
                var lastProgressUpdate = 0L

                while (!channel.isClosedForRead) {
                    val bytesRead = channel.readAvailable(buffer)
                    if (bytesRead > 0) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        // Throttle UI updates: every 256KB or every 5% progress, whichever comes first.
                        val shouldUpdateProgress =
                            if (totalSize > 0) {
                                downloadedBytes - lastProgressUpdate >= 262144 ||
                                    (
                                        downloadedBytes.toFloat() / totalSize -
                                            lastProgressUpdate.toFloat() / totalSize
                                    ) >= 0.05f
                            } else {
                                downloadedBytes - lastProgressUpdate >= 131072
                            }

                        if (shouldUpdateProgress) {
                            val progress =
                                if (totalSize > 0) {
                                    val currentProgress = (downloadedBytes.toFloat() / totalSize.toFloat()).coerceIn(0f, 1f)
                                    // Log only major progress milestones (every 25%).
                                    val progressPct = (currentProgress * 100).toInt()
                                    if (progressPct % 25 == 0 && progressPct > 0) {
                                        logger.trace(
                                            LogCategory.SYSTEM,
                                            "Download progress",
                                            mapOf(
                                                "percent" to progressPct,
                                                "downloadedKB" to (downloadedBytes / 1024),
                                                "totalKB" to (totalSize / 1024),
                                            ),
                                        )
                                    }
                                    currentProgress
                                } else {
                                    // Unknown total size: monotonic curve, asymptotic toward <1
                                    // (never decreases; the explicit onProgress(1f) below finishes it).
                                    val mb = downloadedBytes / 1_048_576f
                                    (1f - 1f / (1f + mb / 8f)).coerceIn(0f, 0.95f)
                                }

                            // Progress updates must happen on the main thread for UI updates.
                            withContext(Dispatchers.Main) {
                                onProgress(progress)
                            }
                            lastProgressUpdate = downloadedBytes
                        }
                    }
                }
            }

            // Ensure 100% is reported on completion, on the main thread.
            withContext(Dispatchers.Main) {
                onProgress(1f)
            }
        }
    }

    actual suspend fun installUpdate(downloadPath: String): Boolean {
        // Delegate to UpdateInstaller
        val result = UpdateInstaller.installUpdate(downloadPath)

        return when (result) {
            is InstallResult.Success -> {
                logger.info(LogCategory.SYSTEM, "Update installed successfully", mapOf("message" to result.message))
                true
            }

            is InstallResult.RequiresRestart -> {
                logger.info(LogCategory.SYSTEM, "Update requires restart", mapOf("message" to result.message))

                // The helper script is now running and waiting for this process to exit
                // We need to quit the app so the script can proceed with installation
                @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
                GlobalScope.launch {
                    // Give the UI a moment to show the "installing" message
                    delay(1000)

                    // Quit the application cleanly
                    ApplicationRestarter.quitForUpdate()
                }

                true
            }

            is InstallResult.Error -> {
                logger.error(LogCategory.SYSTEM, "Update installation failed", mapOf("error" to result.message))
                false
            }
        }
    }

    actual fun getCurrentPlatform(): String = UpdateInstaller.getCurrentPlatform()

    /**
     * Get the Linux architecture suffix based on the current system.
     * Returns "arm64" for ARM64/aarch64 systems, "amd64" for x86_64 systems.
     */
    private fun getLinuxArchSuffix(): String {
        val arch = System.getProperty("os.arch")
        return when {
            arch == "aarch64" || arch == "arm64" -> "arm64"
            else -> "amd64"
        }
    }

    actual fun getExpectedAssetName(version: Version): String =
        when (getCurrentPlatform()) {
            "macOS" -> "BOSS-$version-Universal.dmg"
            "Windows" -> "BOSS-$version.msi"
            "Linux", "Linux-deb" -> "BOSS-$version-${getLinuxArchSuffix()}.deb"
            "Linux-rpm" -> "BOSS-$version-${getLinuxArchSuffix()}.rpm"
            else -> "BOSS-$version-${getLinuxArchSuffix()}.jar" // JAR with arch for native deps
        }

    /**
     * Fetch all releases from the configured source (Supabase primary, GitHub backup).
     */
    actual suspend fun fetchAllReleases(): List<VersionInfo> =
        withContext(Dispatchers.IO) {
            try {
                val allReleases = source.listReleases()

                // Convert to VersionInfo
                allReleases.mapNotNull { release ->
                    try {
                        val version = Version.parse(release.tag_name) ?: return@mapNotNull null
                        val expectedAssetName = getExpectedAssetName(version)
                        val asset =
                            release.assets.find {
                                it.name.equals(expectedAssetName, ignoreCase = true)
                            }

                        if (asset != null) {
                            VersionInfo(
                                version = version,
                                releaseDate = release.published_at,
                                downloadSize = asset.size,
                                releaseNotes = release.body,
                                downloadUrl = asset.browser_download_url ?: "",
                                isDraft = release.draft,
                                isPrerelease = release.prerelease,
                                sha256 = asset.sha256,
                            )
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        logger.warn(LogCategory.NETWORK, "Failed to parse release", mapOf("tag" to release.tag_name), error = e)
                        null
                    }
                }
            } catch (e: Exception) {
                logger.error(LogCategory.NETWORK, "Error fetching all releases", error = e)
                emptyList()
            }
        }

    /**
     * Fetch details for a specific version
     */
    actual suspend fun fetchVersionDetails(version: Version): UpdateInfo? =
        withContext(Dispatchers.IO) {
            try {
                val release = source.getReleaseByTag("v$version") ?: return@withContext null
                val expectedAssetName = getExpectedAssetName(version)
                val asset =
                    release.assets.find {
                        it.name.equals(expectedAssetName, ignoreCase = true)
                    }

                UpdateInfo(
                    available = true,
                    currentVersion = AppVersion.CURRENT,
                    latestVersion = version,
                    releaseNotes = release.body,
                    downloadUrl = asset?.browser_download_url,
                    assetSize = asset?.size ?: 0,
                    assetName = asset?.name ?: "",
                    sha256 = asset?.sha256,
                )
            } catch (e: Exception) {
                logger.error(LogCategory.NETWORK, "Error fetching version details", mapOf("version" to version.toString()), error = e)
                null
            }
        }
}
