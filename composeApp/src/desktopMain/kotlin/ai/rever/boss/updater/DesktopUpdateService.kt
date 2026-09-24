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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Validate a release-catalog asset name before it is used as a path component.
 *
 * Applies the shared [UpdatePathValidator] filename rules and additionally
 * requires a bare basename: `validateFileName` rejects `..` but not `/`, so
 * without this a name like `sub/x.dmg` would still create a subdirectory, and an
 * empty name would resolve to the staging directory itself.
 *
 * @throws SecurityException if [assetName] is not a safe single path component
 */
internal fun validateUpdateAssetName(assetName: String) {
    if (assetName.isEmpty()) {
        throw SecurityException("Update asset name is empty - refusing to use it as a download path")
    }
    UpdatePathValidator.validateFileName(assetName, "Update asset name")
    if (UpdatePathValidator.fileNameComponent(assetName) != assetName) {
        throw SecurityException("Update asset name is not a bare filename - rejected for security: $assetName")
    }
}

actual class UpdateService internal constructor(
    /**
     * Dedicated GitHub source used only to recover a download if the primary URL
     * fails. Injectable so tests can point the fallback at a server they control.
     */
    private val gitHubSource: UpdateSource,
    /**
     * Where staged downloads land. Injectable so regression tests stage inside
     * their own temp dir instead of the shared `boss-updates` staging directory
     * a running app uses (BossConsole#797), the same injection idiom as
     * [UpdateInstaller].validateDownloadFile.
     */
    private val stagingDir: File = defaultStagingDir(),
    private val bindVerifiedChecksum: (File, String) -> Unit = UpdateArtifactIntegrityVet::bindVerifiedChecksum,
) {
    /**
     * Matches the common `expect class UpdateService()` shape (expect/actual
     * constructor matching does not consider default parameter values);
     * production code gets the real GitHub Releases source and the shared
     * staging directory.
     */
    actual constructor() : this(GitHubUpdateSource())

    private val logger = BossLogger.forComponent("UpdateService")

    /**
     * Where release metadata comes from. Supabase is primary (Realtime-fed catalog
     * in the `app_releases` table); GitHub is the automatic backup. Overridable via
     * BOSS_UPDATE_PRIMARY_SOURCE for testing/rollback.
     */
    private val source: UpdateSource = buildSource()

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
        //
        // The fallback fetches the SAME asset of the SAME version the UpdateInfo row
        // describes, so the catalog's hash binds these bytes exactly as it binds the
        // primary download's (the release pipeline publishes one artifact to both
        // sources). Passing null here (BossConsole#797) meant the fallback installed
        // with NO integrity check at all on the path that only runs because a CDN was
        // already misbehaving, and the staged installer later runs elevated. A genuine
        // build difference between sources must fail loudly and fall back to a
        // re-download, never install unverified - and a catalog row that cannot
        // describe its asset with a hash at all is refused downstream the same way,
        // instead of staging an unverifiable body.
        val gitHubUrl = gitHubAssetUrlFor(updateInfo.latestVersion)
        if (gitHubUrl != null && gitHubUrl != primaryUrl) {
            logger.warn(
                LogCategory.NETWORK,
                "Primary download failed; falling back to GitHub asset",
                mapOf(
                    "asset" to updateInfo.assetName,
                ),
            )
            return downloadFrom(
                gitHubUrl,
                updateInfo.assetName,
                updateInfo.assetSize,
                sha256 = updateInfo.sha256,
                onProgress = onProgress,
            )
        }
        return null
    }

    /**
     * Download [url] to a temp file in [stagingDir], verifying the REQUIRED
     * [sha256] - a manifest without one is refused rather than staged - and
     * binding the verified checksum for the install boundary. Returns the path
     * or null.
     *
     * A hashless row is refused BEFORE anything on disk is touched, and that
     * refusal is an [UpdateDownloadRefusedException] with a user-facing reason
     * rather than a null: with GitHub releases list-only, a Supabase outage or
     * a GitHub-primary switch would otherwise offer an update that then fails
     * as a generic "Failed to download update" that explains nothing.
     *
     * `internal` (not private) so the fallback-checksum regression test (BossConsole#797)
     * can drive the verification path directly against a local HTTP server, instead of
     * reproducing the full primary-failure + GitHub-resolution dance.
     */
    internal suspend fun downloadFrom(
        url: String,
        assetName: String,
        assetSize: Long,
        sha256: String?,
        onProgress: (progress: Float) -> Unit,
    ): String? {
        // A hashless catalog row is refused BEFORE anything on disk is touched: the checksum
        // is required, so there is nothing to verify the download against and no reason to
        // fetch it (the refusal used to land only after the whole download completed). The
        // order matters as much as the refusal itself: this call must stay ABOVE the
        // clean-slate deletes below, or a hashless row would destroy a good, already-verified
        // staged update - and its checksum marker - just to reject a different body it never
        // fetched. See `refuseHashlessCatalogRow` for the typed, user-facing reason.
        refuseHashlessCatalogRow(sha256, assetName)

        // Held outside the try so a cancellation can clean up the partial file. A
        // cancelled download otherwise leaves a half-written installer in the staging
        // directory under the exact name the next attempt checks for, and the next
        // one deletes it before writing anyway - so the disk cost is silent until it
        // is a whole DMG.
        var partial: File? = null
        return try {
            logger.info(LogCategory.SYSTEM, "Starting update download", mapOf("asset" to assetName, "size" to assetSize))

            // The asset name comes off the remote release row, and everything below
            // treats it as a path component: File(tempDir, assetName) with a ".."
            // escapes the staging directory, and the delete() + write happen long
            // before the install-time containment check would refuse it - an
            // arbitrary-file delete/overwrite primitive running as the user. An
            // empty name (the "?: \"\"" default upstream) resolves to the staging
            // directory itself, so exists()/delete() would target the directory.
            validateUpdateAssetName(assetName)

            // Same constant the installer validates containment against, so the
            // staging directory can't drift between download and install. Created
            // owner-only: this is where the installer artifact waits between
            // checksum verification and an elevated install, so another local user
            // must not be able to swap it (fails closed).
            val tempDir = createRestrictedDir(stagingDir)

            // The bytes stream into a `.part` sibling and only move under the
            // install name AFTER the catalog checksum verifies, so a download cut
            // short by a crash never leaves a partial artifact sitting under the
            // exact name the installer would later run elevated.
            val downloadFile = File(tempDir, assetName)
            val partFile = File(tempDir, "$assetName.part")

            // Clean slate: remnants of a crashed earlier attempt (a published
            // artifact, its checksum marker, or a partial) must not be mistakable
            // for this attempt's result.
            downloadFile.delete()
            UpdateArtifactIntegrityVet.checksumSidecarOf(downloadFile).delete()
            partFile.delete()
            partial = partFile

            streamToFile(url, assetSize, partFile, onProgress)

            if (partFile.exists() && partFile.length() > 0) {
                publishVerifiedDownload(partFile, downloadFile, assetName, sha256)
            } else {
                logger.error(LogCategory.SYSTEM, "Download failed - file is empty or doesn't exist")
                null
            }
        } catch (e: CancellationException) {
            // Caught ahead of the general clause, which would otherwise swallow it and
            // return null - reporting the user's own Cancel as "Failed to download
            // update" and leaving the partial file behind.
            runCatching { partial?.delete() }
            logger.info(LogCategory.SYSTEM, "Update download cancelled", mapOf("asset" to assetName))
            throw e
        } catch (e: Exception) {
            val errorMessage =
                when (e) {
                    is HttpRequestTimeoutException -> "Download timeout: File too large or connection too slow"
                    is ConnectTimeoutException -> "Connection timeout: Unable to reach download server"
                    is SocketTimeoutException -> "Network timeout: Download interrupted"
                    else -> e.message ?: "Unknown error"
                }
            // The partial goes here too, not only on the cancellation path: a dropped
            // connection otherwise leaves a DMG's worth of disk occupied until the next
            // attempt happens to overwrite it. Same asymmetry the plugin side closed.
            runCatching { partial?.delete() }
            logger.error(LogCategory.NETWORK, "Error downloading update", mapOf("error" to errorMessage))
            null
        }
    }

    /**
     * [downloadFrom]'s catalog-checksum gate, hoisted above everything the download touches
     * on disk: a row that cannot describe its own asset's bytes - a GitHub-only catalog row,
     * whose releases carry no hashes - offers nothing that can be verified before an
     * elevated install, so there is nothing to fetch and nothing to stage. Refused here as
     * its own typed answer with a user-facing reason rather than a null, so it cannot
     * flatten into the generic "Failed to download update" downstream. The call site must
     * stay at the top of [downloadFrom], above its clean-slate deletes: below them, a
     * hashless row would destroy a good, already-verified staged update - and its checksum
     * marker - just to reject a different body it never fetches. The hashless-refusal
     * regression tests pin both the ordering and the zero-fetch contract.
     */
    private fun refuseHashlessCatalogRow(
        sha256: String?,
        assetName: String,
    ) {
        if (sha256 != null) return
        logger.error(
            LogCategory.SYSTEM,
            "Refusing the update download - catalog row has no checksum",
            mapOf("asset" to assetName),
        )
        throw UpdateDownloadRefusedException(
            "The update was not downloaded: the release catalog lists no checksum " +
                "for $assetName, so its integrity cannot be verified.",
        )
    }

    /**
     * The post-download gate both download paths share (BossConsole#797, now fail
     * closed): the staged bytes are verified against the catalog hash, published
     * under the install name, and the verified checksum is bound beside them so
     * [UpdateInstaller.installUpdate] can re-verify it at the install boundary.
     *
     * Integrity check, NOT authenticity: the hash and URL come from the same
     * app_releases row, so this guards against Storage/CDN corruption and a tampered
     * fallback hop, not a compromised catalog. Update authenticity still rests on OS
     * code-signing.
     *
     * The catalog row is the trusted manifest channel (app_releases, writable only
     * by the CI service role), and its sha256 is REQUIRED: a row that cannot
     * describe its own asset's bytes - a GitHub-only catalog row, whose releases
     * carry no hashes - used to stage (and later install, elevated) with NO
     * integrity check at all. Such a download is refused instead, exactly as the
     * engine lane refuses catalog-hashless archives (#1237) and the plugin lane
     * refuses unvetted jars (#947).
     *
     * A mismatch is discarded and reported. Every refusal returns null; the caller
     * surfaces that as a failed download and the previous install keeps running.
     */
    private fun publishVerifiedDownload(
        partFile: File,
        downloadFile: File,
        assetName: String,
        sha256: String?,
    ): String? =
        try {
            val verifiedSha = requireCatalogChecksum(partFile, assetName, sha256)
            publishAtomically(partFile, downloadFile, assetName)
            try {
                bindVerifiedChecksum(downloadFile, verifiedSha)
            } catch (e: Exception) {
                val sidecar = UpdateArtifactIntegrityVet.checksumSidecarOf(downloadFile)
                if (sidecar.exists()) deleteOrComplain(sidecar, "a failed checksum marker")
                deleteOrComplain(downloadFile, "an unbound update download")
                throw SecurityException("Could not bind the verified checksum for $assetName", e)
            }
            logger.info(LogCategory.SYSTEM, "Update checksum verified", mapOf("asset" to assetName))
            logger.info(LogCategory.SYSTEM, "Update downloaded successfully", mapOf("path" to downloadFile.absolutePath))
            downloadFile.absolutePath
        } catch (e: SecurityException) {
            logger.error(
                LogCategory.SYSTEM,
                "Refusing the downloaded update - integrity gate",
                mapOf("asset" to assetName),
                error = e,
            )
            null
        }

    /**
     * Verify [partFile] against the catalog hash. Fails closed on a hashless
     * manifest exactly as on a mismatch, deleting the partial either way.
     *
     * @throws SecurityException when [sha256] is null, the bytes cannot be hashed,
     * or the hash does not match - the download is refused.
     */
    private fun requireCatalogChecksum(
        partFile: File,
        assetName: String,
        sha256: String?,
    ): String {
        if (sha256 == null) {
            deleteOrComplain(partFile, "a hash-less update download")
            throw SecurityException(
                "The update manifest carries no sha256 for $assetName - refusing an unverified download",
            )
        }
        val actualSha = runCatching { sha256Of(partFile) }.getOrNull()
        if (actualSha == null || !sha256.equals(actualSha, ignoreCase = true)) {
            logger.error(
                LogCategory.SYSTEM,
                "Update checksum mismatch; discarding download",
                mapOf(
                    "asset" to assetName,
                    "expected" to sha256,
                    "actual" to (actualSha ?: ""),
                ),
            )
            deleteOrComplain(partFile, "a mismatched update download")
            throw SecurityException(
                "Update checksum mismatch for $assetName - discarding download " +
                    "(expected $sha256, got $actualSha)",
            )
        }
        return actualSha
    }

    /**
     * Publish the verified bytes under the install name, atomically where the
     * filesystem supports it. A crash mid-publish can at worst leave a partial
     * artifact that carries no checksum marker, which the install boundary refuses.
     *
     * @throws SecurityException when the move fails; the partial is deleted.
     */
    private fun publishAtomically(
        partFile: File,
        downloadFile: File,
        assetName: String,
    ) {
        try {
            Files.move(partFile.toPath(), downloadFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            try {
                Files.move(partFile.toPath(), downloadFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (fallback: IOException) {
                deleteOrComplain(partFile, "an unpublished update download")
                throw SecurityException("Could not publish the verified download for $assetName", fallback)
            }
        } catch (e: IOException) {
            deleteOrComplain(partFile, "an unpublished update download")
            throw SecurityException("Could not publish the verified download for $assetName", e)
        }
    }

    /**
     * Delete a refused download, loudly. Not an install vector - the installer only
     * uses the returned path, and the next attempt deletes before writing - but the
     * discard is part of this gate's contract, so a failed delete must be visible,
     * not silent (a Windows AV scanner holding a freshly written MSI is the case).
     */
    private fun deleteOrComplain(
        file: File,
        description: String,
    ) {
        val deleted = runCatching { file.delete() }.getOrDefault(false)
        if (!deleted) {
            logger.error(
                LogCategory.SYSTEM,
                "Could not delete $description from staging",
                mapOf("path" to file.absolutePath),
            )
        }
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

    actual fun discardDownload(downloadPath: String) {
        val file = File(downloadPath)
        // Containment first, exactly as the installer does before it touches an
        // artifact: this path arrives from update state as a plain string, and a
        // delete that trusted it would remove any file the user can write. A path
        // that cannot be resolved is one that is already gone, so absence is the
        // postcondition either way.
        val realStagingDir = runCatching { defaultStagingDir().toPath().toRealPath() }.getOrNull()
        val realPath = runCatching { file.toPath().toRealPath() }.getOrNull()
        val contained =
            realStagingDir != null &&
                realPath != null &&
                realPath != realStagingDir &&
                realPath.startsWith(realStagingDir)
        when {
            realStagingDir == null || realPath == null -> {
                Unit
            }

            !contained -> {
                logger.error(
                    LogCategory.SYSTEM,
                    "Refusing to discard a download outside the staging directory",
                    mapOf("expected" to realStagingDir.toString(), "actual" to realPath.toString()),
                )
            }

            else -> {
                val deleted = runCatching { file.delete() }.getOrDefault(false)
                // The checksum marker goes with its artifact, so a later download of
                // the same asset can never inherit a marker for other bytes.
                val markerDeleted =
                    runCatching { UpdateArtifactIntegrityVet.checksumSidecarOf(file).delete() }
                        .getOrDefault(false)
                logger.info(
                    LogCategory.SYSTEM,
                    "Discarded a downloaded update",
                    mapOf(
                        "path" to file.absolutePath,
                        "deleted" to deleted.toString(),
                        "markerDeleted" to markerDeleted.toString(),
                    ),
                )
            }
        }
    }

    actual suspend fun installUpdate(downloadPath: String): InstallOutcome {
        // Delegate to UpdateInstaller
        val result = UpdateInstaller.installUpdate(downloadPath)

        return when (result) {
            is InstallResult.Success -> {
                logger.info(LogCategory.SYSTEM, "Update installed successfully", mapOf("message" to result.message))
                InstallOutcome(succeeded = true)
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

                InstallOutcome(succeeded = true)
            }

            is InstallResult.Error -> {
                // Carry the message rather than flattening to false: the installer
                // refuses an OS-incompatible release here, and that reason has to
                // reach the dialog or it reads as an unexplained failure.
                logger.error(LogCategory.SYSTEM, "Update installation failed", mapOf("error" to result.message))
                InstallOutcome(
                    succeeded = false,
                    errorMessage = result.message,
                    failureReason = result.failureReason,
                )
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
