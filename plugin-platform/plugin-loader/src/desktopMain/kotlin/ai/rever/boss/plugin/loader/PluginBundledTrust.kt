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
 *
 * Marker persistence matches the adjacent `PluginSignatureSidecar` shape (#1108): every write is
 * staged to a unique sibling then renamed into place rather than truncate-then-write, reads and
 * writes share this object's monitor so a reader mid-replace observes either the old complete
 * digest or the new complete digest (never a truncated intermediate), and the recorded text is
 * length-validated as a 64-char lowercase hex string so a half-written marker cannot masquerade
 * as a present-but-mismatched digest and silently strip trust from the bytes it was meant to
 * bind.
 */
object PluginBundledTrust {
    private val logger = BossLogger.forComponent("PluginBundledTrust")

    private const val SUFFIX = ".bundled-trust"
    private const val SHA256_HEX_LENGTH = 64

    fun pathFor(jarPath: String): String = "$jarPath$SUFFIX"

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
            val recorded = readMarker(sourcePath)
            val matches =
                recorded != null &&
                    recorded == FileHashing.sha256(File(sourcePath)) &&
                    recorded == FileHashing.sha256(File(destinationPath))
            if (matches) {
                writeMarker(destinationPath, recorded)
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
        val recorded = readMarker(jarPath)
        val actual = recorded?.let { runCatching { FileHashing.sha256(File(jarPath)) }.getOrNull() }
        return actual != null && recorded == actual
    }

    /** Remove the marker (e.g. alongside a deleted/replaced JAR). Best-effort. */
    @Synchronized
    fun delete(jarPath: String) {
        File(pathFor(jarPath)).delete()
    }

    /**
     * Persist [content] (a sha256 hex digest) atomically at the marker path for [jarPath].
     *
     * The marker is read concurrently with startup reconciliation and with the in-process loader,
     * so a `File.writeText()`-style truncate-then-write would race the reader: a load landing
     * between truncate and completion would observe a partial digest and reject trust for the
     * rest of the session. Stage to a unique sibling and move into place instead - the move is
     * atomic where the filesystem supports it and falls back to a plain replace where it does
     * not, so any out-of-process reader still observes either the old marker or the new one.
     *
     * The unique sibling matters even for a single writer: two writers racing one JAR used a
     * fixed `<jar>.bundled-trust.tmp` path, so A's bytes could land as the surviving marker
     * after B's truncate destroyed A's staging. Two `<name>NNNN.tmp` files cannot collide.
     *
     * Synchronized on the object monitor so an in-process read or delete cannot observe the
     * transient absence Windows introduces during `Files.move(REPLACE_EXISTING)`, where the
     * target is unlinked before its replacement appears. The marker operations are short and
     * do no hashing or network work, so one process-wide lock avoids both that race and an
     * unbounded lock map.
     */
    @Synchronized
    private fun writeMarker(
        jarPath: String,
        content: String,
    ) {
        val target = File(pathFor(jarPath))
        val parent = target.absoluteFile.parentFile
        // Same directory as the target: Files.move is only atomic within a filesystem,
        // and the default temp dir is often a different one.
        val tmp = Files.createTempFile(parent.toPath(), target.name, ".tmp").toFile()
        try {
            tmp.writeText(content)
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

    /**
     * Read the marker for [jarPath] and validate the recorded text is a complete SHA-256 hex
     * digest. Any value that is not exactly 64 lowercase hex characters is treated as absent:
     * a half-written marker cannot match any JAR's actual digest, and carrying it forward to a
     * comparison would either always fail (noisy) or, in the unlikely event a forged marker
     * happens to be 64 chars of garbage, fail-closed against `isTrusted`'s own equality check.
     * Read failures are swallowed for the same reason a missing marker is: the marker is a
     * hint, and a hint we cannot read is the same as one we have not been given.
     */
    @Synchronized
    private fun readMarker(jarPath: String): String? {
        val raw =
            try {
                Files.readString(File(pathFor(jarPath)).toPath()).trim()
            } catch (_: NoSuchFileException) {
                null
            } catch (_: java.io.IOException) {
                // A directory at the marker path, a permission denial, or any other read
                // failure is treated identically to absence. Fail closed: the bundled-plugin
                // exemption is a hint, and an unreadable hint is the same as no hint.
                null
            }
        return raw?.takeIf { isPlausibleDigest(it) }
    }

    private fun isPlausibleDigest(value: String): Boolean {
        if (value.length != SHA256_HEX_LENGTH) return false
        return value.all { it in '0'..'9' || it in 'a'..'f' }
    }
}
