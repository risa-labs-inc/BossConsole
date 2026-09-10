package ai.rever.boss.plugin.loader

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import java.io.File

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

    /** Mark [jarPath]'s current bytes as trusted. Best-effort. */
    internal fun markTrusted(
        jarPath: String,
        sha256: String,
    ) {
        runCatching { File(pathFor(jarPath)).writeText(sha256) }
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
                File(pathFor(jarPath)).writeText(bundledDigest)
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
            val marker = File(pathFor(sourcePath))
            val recorded =
                marker
                    .takeIf { it.isFile }
                    ?.readText()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            val matches =
                recorded != null &&
                    recorded == FileHashing.sha256(File(sourcePath)) &&
                    recorded == FileHashing.sha256(File(destinationPath))
            if (matches) {
                File(pathFor(destinationPath)).writeText(requireNotNull(recorded))
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
        val marker = File(pathFor(jarPath))
        if (!marker.exists()) return false
        val recorded = runCatching { marker.readText().trim() }.getOrNull()
        val actual = runCatching { FileHashing.sha256(File(jarPath)) }.getOrNull()
        return !recorded.isNullOrEmpty() && recorded == actual
    }

    /** Remove the marker (e.g. alongside a deleted/replaced JAR). Best-effort. */
    fun delete(jarPath: String) {
        File(pathFor(jarPath)).delete()
    }
}
