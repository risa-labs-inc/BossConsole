package ai.rever.boss.plugin.loader

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption

/**
 * Exempts one specific JAR from store-signature enforcement because the HOST itself placed it
 * there, copied verbatim from the app's own bundled-plugins directory (BossConsole#102) - which
 * ships inside the signed, notarized app image and carries no store `.sig`. The store-signature
 * scheme defends against an attacker with store/DB write access substituting a malicious
 * downloadable JAR; a bundled JAR's integrity already comes from a different, stronger boundary
 * (the OS verifying the app's own code signature before any of it runs), so requiring a second
 * signature scheme for a file the app shipped inside itself would only reproduce that guarantee
 * with paperwork - and would hard-fail loading every bundled plugin the moment enforcement is on,
 * since none of them carry a sidecar today.
 *
 * A `<jar>.bundled-trust` marker beside the JAR, content-addressed by sha256 so replacing the
 * bytes at that path invalidates it: a marker surviving a later, unrelated JAR at the same
 * filename (a stale reconciler leftover, a manual side-load, a store update reusing the name)
 * must NOT inherit trust it was never given.
 *
 * The host binds copies (including copies installed by older hosts) by comparing against the
 * bundled source, never by trusting a manifest id or version. This is a local provenance cache,
 * not a cryptographic credential: a process able to write both the JAR and its marker can forge
 * it. As with the app bundle and development directory override, local filesystem integrity is
 * outside the store/DB-substitution threat model.
 */
object PluginBundledTrust {
    private val logger = BossLogger.forComponent("PluginBundledTrust")

    private const val SUFFIX = ".bundled-trust"

    fun pathFor(jarPath: String): String = "$jarPath$SUFFIX"

    /**
     * Publish a complete marker without exposing truncate-then-write state to [readMarker].
     * The object monitor also avoids Windows refusing a replacement while this process reads
     * the destination. External readers still get the atomic-move guarantee where supported.
     */
    @Synchronized
    private fun writeMarker(
        jarPath: String,
        sha256: String,
    ) {
        val target = File(pathFor(jarPath)).absoluteFile
        val parent = requireNotNull(target.parentFile) { "Bundled trust marker has no parent" }
        val tmp = Files.createTempFile(parent.toPath(), target.name, ".tmp").toFile()
        try {
            tmp.writeText(sha256)
            try {
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tmp.delete()
        }
    }

    /** A complete marker value, or null only when no marker exists. */
    @Synchronized
    private fun readMarker(jarPath: String): String? =
        try {
            Files.readString(File(pathFor(jarPath)).toPath()).trim().takeIf { it.isNotEmpty() }
        } catch (_: NoSuchFileException) {
            null
        }

    /** Mark [jarPath]'s current bytes as trusted. Best-effort. */
    internal fun markTrusted(
        jarPath: String,
        sha256: String,
    ) {
        runCatching { writeMarker(jarPath, sha256) }
    }

    /**
     * Bind an installed copy to trusted bundled bytes, including when startup skips copying an
     * already-installed version. A matching id/version alone never grants the exemption.
     * Returns false if either file cannot be read, differs, or the marker cannot be written.
     * Read/write failures are logged; a differing store update is an ordinary non-match.
     * A partial marker write fails closed: no exemption until a subsequent startup retries.
     */
    fun bindToBundle(
        jarPath: String,
        bundledJar: File,
    ): Boolean =
        runCatching {
            val bundledDigest = FileHashing.sha256(bundledJar)
            if (FileHashing.sha256(File(jarPath)) != bundledDigest) {
                false
            } else {
                writeMarker(jarPath, bundledDigest)
                true
            }
        }.onFailure { error ->
            logger.warn(
                LogCategory.SYSTEM,
                "Could not establish bundled plugin trust",
                mapOf("jarPath" to jarPath, "errorType" to error.javaClass.simpleName),
            )
        }.getOrDefault(false)

    /** Preserve existing provenance across a snapshot/restore; never certify previously unbound bytes. */
    fun copyTrust(
        sourcePath: String,
        destinationPath: String,
    ): Boolean =
        runCatching {
            val recorded =
                readMarker(sourcePath)
            val matches =
                recorded != null &&
                    recorded == FileHashing.sha256(File(sourcePath)) &&
                    recorded == FileHashing.sha256(File(destinationPath))
            if (matches) {
                writeMarker(destinationPath, requireNotNull(recorded))
            } else {
                delete(destinationPath)
            }
            matches
        }.onFailure { error ->
            logger.warn(
                LogCategory.SYSTEM,
                "Could not preserve bundled plugin trust",
                mapOf("jarPath" to destinationPath, "errorType" to error.javaClass.simpleName),
            )
        }.getOrDefault(false)

    /**
     * Whether [jarPath]'s CURRENT bytes match a marker this object wrote for them.
     *
     * Re-hashes the file rather than trusting the marker's mere presence, so a JAR swapped in
     * after the marker was written (same filename, different content) reads as untrusted.
     */
    fun isTrusted(jarPath: String): Boolean {
        val actual = runCatching { FileHashing.sha256(File(jarPath)) }.getOrNull() ?: return false
        return isTrusted(jarPath, actual)
    }

    /**
     * Whether [jarPath]'s marker records [actualSha256].
     *
     * The load-time variant: the caller supplies the digest of the bytes it is
     * about to execute (the staged copy), so the marker is compared against
     * what will load rather than whatever currently sits at [jarPath] — a swap
     * of the original file between hashing and loading cannot move the answer.
     */
    fun isTrusted(
        jarPath: String,
        actualSha256: String,
    ): Boolean {
        val marker = File(pathFor(jarPath))
        if (!marker.exists()) return false
        val recorded = runCatching { marker.readText().trim() }.getOrNull()
        return !recorded.isNullOrEmpty() && recorded == actualSha256
    }

    /** Remove the marker (e.g. alongside a deleted/replaced JAR). Best-effort. */
    @Synchronized
    fun delete(jarPath: String) {
        File(pathFor(jarPath)).delete()
    }
}
