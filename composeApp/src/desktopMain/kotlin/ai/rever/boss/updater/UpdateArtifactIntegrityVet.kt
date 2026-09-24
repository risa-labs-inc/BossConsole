package ai.rever.boss.updater

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.sha256Of
import java.io.File
import java.io.IOException

/** Sidecar suffix distinguishing checksum markers from the update artifacts they vouch for. */
private const val CHECKSUM_SIDECAR_SUFFIX = ".sha256"

/** The exact shape a bound checksum must have: [sha256Of] hex output, 64 characters. */
private const val SHA256_HEX_LENGTH = 64

/**
 * Fail-closed integrity gate for the app's own staged update artifacts.
 *
 * The download path verifies a fetched installer against the release catalog's
 * sha256 (the `app_releases` row, RLS-locked to service-role writes) and binds the
 * VERIFIED hash as a sidecar marker beside the published artifact.
 * [UpdateInstaller.installUpdate] re-checks the staged bytes against that marker
 * before a single installer command runs, so verification does not rely on
 * nothing having touched the staging directory between download and the user
 * pressing Install - a window that can span app restarts.
 *
 * This mirrors what the plugin lane calls UpdateJarIdentityVet (#947) and the
 * engine lane EngineArchiveIntegrityVet (#1237): an update artifact that cannot
 * prove its bytes match the verified checksum is refused outright, because every
 * install path it feeds (msiexec, hdiutil, dpkg/rpm, or the jar-overwrite fallback
 * that replaces the running jar in place) operates on the app's own installation
 * with the user's privileges.
 *
 * The marker proves only "these exact bytes passed the catalog check at download
 * time". It is written after verification and re-read before install, so:
 * - a swapped or tampered artifact fails the re-hash,
 * - an artifact staged without verification (a hashless manifest, a crash between
 *   publish and marker write, or a leftover from before this gate existed) has no
 *   marker and is refused,
 * - a truncated or malformed marker is refused.
 */
internal object UpdateArtifactIntegrityVet {
    private val logger = BossLogger.forComponent("UpdateArtifactIntegrityVet")

    /**
     * The sidecar marker beside [artifact] that carries its verified checksum.
     * Derived from the artifact's own location, so it always sits in the same
     * (restricted) staging directory and introduces no new path.
     */
    @Suppress("MaxLineLength") // ktlint requires the single-line signature+body form (129 < 140)
    internal fun checksumSidecarOf(artifact: File): File = File(artifact.parentFile, ".${artifact.name}$CHECKSUM_SIDECAR_SUFFIX")

    /**
     * Bind [verifiedSha256] to [artifact] once its bytes passed the catalog check,
     * so the install boundary can re-verify them later.
     *
     * @throws SecurityException when the hash is malformed or the marker cannot be
     * written - fail closed: the download is then refused rather than staged
     * without a verifiable checksum.
     */
    internal fun bindVerifiedChecksum(
        artifact: File,
        verifiedSha256: String,
    ) {
        if (verifiedSha256.length != SHA256_HEX_LENGTH || !verifiedSha256.all { it.isDigit() || it in 'a'..'f' }) {
            throw SecurityException(
                "Refusing to bind a malformed checksum for ${artifact.name}: $verifiedSha256",
            )
        }
        try {
            checksumSidecarOf(artifact).writeText(verifiedSha256)
        } catch (e: IOException) {
            throw SecurityException("Could not bind the verified checksum beside ${artifact.name}", e)
        }
    }

    /**
     * The install-boundary half: re-hash [artifact] and require a bound, matching
     * checksum. Called before any installer command is dispatched, so a refusal
     * keeps the previous installation exactly as it was.
     *
     * @throws SecurityException on every refusal path: no marker, an unreadable or
     * malformed marker, an unreadable artifact, or a hash mismatch.
     */
    internal fun requireVerifiedChecksum(artifact: File) {
        val sidecar = checksumSidecarOf(artifact)
        if (!sidecar.exists()) {
            refuse(artifact, "no verified checksum is bound for the staged update")
        }
        val bound =
            try {
                sidecar.readText().trim()
            } catch (e: IOException) {
                refuse(artifact, "its bound checksum could not be read", e)
            }
        if (bound.length != SHA256_HEX_LENGTH || !bound.all { it.isDigit() || it in 'a'..'f' }) {
            refuse(artifact, "its bound checksum is malformed: '$bound'")
        }
        val actual =
            try {
                sha256Of(artifact)
            } catch (e: IOException) {
                refuse(artifact, "the artifact could not be hashed", e)
            }
        if (!bound.equals(actual, ignoreCase = true)) {
            refuse(artifact, "it no longer matches its verified checksum (expected $bound, got $actual)")
        }
    }

    /** Log why the artifact is being refused, then fail closed with the same reason. */
    private fun refuse(
        artifact: File,
        because: String,
        cause: Exception? = null,
    ): Nothing {
        logger.error(
            LogCategory.SYSTEM,
            "Refusing to install the staged update: $because",
            mapOf("artifact" to artifact.name),
            error = cause,
        )
        throw SecurityException("Refusing to install ${artifact.name}: $because")
    }
}
