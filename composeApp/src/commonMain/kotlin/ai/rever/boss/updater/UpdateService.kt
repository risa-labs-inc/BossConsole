package ai.rever.boss.updater

import ai.rever.boss.utils.Version
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * GitHub Release data models
 */
@Serializable
data class GitHubRelease(
    val tag_name: String,
    val name: String,
    val body: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val published_at: String,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class GitHubAsset(
    val name: String,
    val browser_download_url: String? = null,
    val size: Long = 0,
    val content_type: String = "",
    // Optional integrity hash. GitHub releases don't provide this (stays null);
    // the Supabase source populates it from the `app_releases` manifest so the
    // download can be verified.
    val sha256: String? = null,
)

/**
 * Update information for the application
 */
data class UpdateInfo(
    val available: Boolean,
    val currentVersion: Version,
    val latestVersion: Version,
    val releaseNotes: String,
    val downloadUrl: String? = null,
    val assetSize: Long = 0,
    val assetName: String = "",
    // Optional integrity hash for the asset (populated by the Supabase source).
    // When present, the download is verified against it before install.
    val sha256: String? = null,
) {
    val isNewerVersionAvailable: Boolean
        get() = available && latestVersion.isNewerThan(currentVersion)
}

/**
 * Extended version information for version selection
 */
data class VersionInfo(
    val version: Version,
    val releaseDate: String,
    val downloadSize: Long,
    val releaseNotes: String,
    val downloadUrl: String,
    val isDraft: Boolean,
    val isPrerelease: Boolean,
    val sha256: String? = null,
)

/** A specific install refusal that needs different follow-up behavior. */
enum class InstallFailureReason {
    UnsupportedOs,
}

/**
 * Result of an install attempt.
 *
 * Carries [errorMessage] rather than a bare Boolean because the installer can
 * refuse for reasons the user needs stated — most importantly a release whose
 * `LSMinimumSystemVersion` this Mac does not meet. Flattening that to `false`
 * surfaced a red "Installation failed" with the real explanation only in a log
 * file, which is indistinguishable from a crash to the person looking at it.
 */
data class InstallOutcome(
    val succeeded: Boolean,
    val errorMessage: String? = null,
    val failureReason: InstallFailureReason? = null,
)

/**
 * Platform-specific update service interface
 */
expect class UpdateService() {
    suspend fun checkForUpdates(): UpdateInfo

    suspend fun downloadUpdate(
        updateInfo: UpdateInfo,
        onProgress: (progress: Float) -> Unit,
    ): String?

    suspend fun installUpdate(downloadPath: String): InstallOutcome

    /**
     * Delete a staged download the user decided not to install.
     *
     * Containment is checked against the same staging directory the installer
     * validates against: the path travels through [UpdateState] and is the only
     * argument here, so a deletion that trusted it would be an arbitrary-file
     * delete primitive rather than a cleanup.
     */
    fun discardDownload(downloadPath: String)

    fun getCurrentPlatform(): String

    fun getExpectedAssetName(version: Version): String

    // New methods for version selection
    suspend fun fetchAllReleases(): List<VersionInfo>

    suspend fun fetchVersionDetails(version: Version): UpdateInfo?
}

/**
 * Update check result sealed class
 */
sealed class UpdateResult {
    object NoUpdateAvailable : UpdateResult()

    /**
     * The window that asked has gone away, so nothing was done. Distinct from
     * [NoUpdateAvailable], which is a real answer about the installed version.
     */
    object HandleReleased : UpdateResult()

    data class UpdateAvailable(
        val updateInfo: UpdateInfo,
    ) : UpdateResult()

    data class Error(
        val message: String,
        val exception: Exception? = null,
    ) : UpdateResult()
}
