package ai.rever.boss.plugin.loader

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption

/**
 * A plugin's store signature travels to load time as a `<jar>.sig` sidecar —
 * the signature is over the canonical anchor `pluginId|version|sha256` (see
 * [PluginStoreTrust.versionAnchor]) and is NOT inside the JAR, so whichever
 * downloader fetched the JAR writes the base64 signature next to it. The
 * loader reads it back and verifies at load time, covering every install
 * path (store repository, Toolbox, first-run wizard) at one choke point.
 *
 * Dev side-loads (locally built JARs dropped into the plugin dir) simply have
 * no sidecar and are treated as unsigned.
 */
object PluginSignatureSidecar {
    private const val SUFFIX = ".sig"
    private const val UNSIGNABLE_SUFFIX = ".nosig"

    fun pathFor(jarPath: String): String = "$jarPath$SUFFIX"

    fun unsignablePathFor(jarPath: String): String = "$jarPath$UNSIGNABLE_SUFFIX"

    /**
     * Persist the store signature beside [jarPath], or clear any existing
     * sidecar when there is none. A null OR BLANK signature deletes rather
     * than writing a zero-byte sidecar: an empty `.sig` would read back as a
     * present-but-malformed signature, so treat "" the same as absent. Use
     * this (not [write]) on any path that replaces a JAR in place, so a stale
     * sidecar can't linger beside new bytes.
     */
    fun persist(
        jarPath: String,
        signatureBase64: String?,
    ) {
        if (!signatureBase64.isNullOrBlank()) write(jarPath, signatureBase64) else delete(jarPath)
    }

    /**
     * Persist the base64 store signature beside [jarPath]. Best-effort.
     *
     * Written to a temp file and moved into place rather than truncate-then-write:
     * sidecars are now also written on a background scope *while* plugin loading
     * reads them, and a read landing mid-write would see truncated base64 — which
     * is a present-but-invalid signature, i.e. a hard load failure for that
     * session, not the benign "no signature" case. The move is atomic where the
     * filesystem supports it and falls back to a plain replace where it doesn't.
     *
     * The temp file gets a unique name rather than a fixed `<jar>.sig.tmp`. Two
     * writers racing one JAR shared that path: A writes it, B truncates and
     * rewrites it, A moves B's half-written bytes into place, then B's own move
     * throws on the file A already consumed. Both call sites wrap this in
     * `runCatching`, so it degraded to a warn rather than a crash — but the
     * surviving sidecar could be the interleaved one, and a wrong signature fails
     * load harder than a missing one. Unique temps prevent writers from sharing
     * staging bytes. They do not serialize replacement of the final filename.
     *
     * Reads, writes and deletes share the object monitor: Windows can refuse a
     * replacement while another thread holds the target open for reading or is
     * replacing it. These small sidecar operations do no network or JAR hashing,
     * so one process-wide lock avoids both that race and an unbounded lock map.
     * Atomic replacement still protects readers outside this process where supported.
     */
    @Synchronized
    fun write(
        jarPath: String,
        signatureBase64: String,
    ) {
        val target = File(pathFor(jarPath))
        val parent = target.absoluteFile.parentFile
        // Same directory as the target: Files.move is only atomic within a
        // filesystem, and the default temp dir is often a different one.
        val tmp = Files.createTempFile(parent.toPath(), target.name, ".tmp").toFile()
        try {
            tmp.writeText(signatureBase64)
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
     * The stored base64 signature, or null when no sidecar exists.
     *
     * Reads without an `exists()` pre-check on purpose. The pair was a
     * time-of-check-to-time-of-use race against [write]: `Files.move` with
     * `REPLACE_EXISTING` transiently unlinks the target on Windows, so a reader
     * that passed `exists()` could reach `readText()` after the unlink and throw
     * `FileNotFoundException` out of what every caller treats as a total
     * function. Caught on the Windows CI leg by ConcurrentSidecarWriteTest, which
     * exists for the write side of this same race.
     *
     * Only absence is treated as unsigned. Other read failures must propagate so
     * an existing but unreadable signature does not become warn-and-allow.
     */
    @Synchronized
    fun read(jarPath: String): String? =
        try {
            Files.readString(File(pathFor(jarPath)).toPath()).trim().ifEmpty { null }
        } catch (_: NoSuchFileException) {
            null
        }

    /**
     * Remove a sidecar (e.g. alongside a rejected/purged JAR). Best-effort.
     *
     * Takes the unsignable marker with it. Every path that retires a JAR already
     * calls this, so pairing them here is what keeps [markUnsignable] from needing
     * cleanup obligations of its own at four separate call sites.
     */
    @Synchronized
    fun delete(jarPath: String) {
        File(pathFor(jarPath)).delete()
        File(unsignablePathFor(jarPath)).delete()
    }

    /**
     * Record that [anchor] is known NOT to resolve to a store signature, so the
     * lookup is not repeated for the same triple on the next launch.
     *
     * Only for a *settled* answer: the store has a row for this pluginId and
     * version and vouches for different bytes than the ones on disk. A store that
     * is unreachable, has no row yet, or 403s is an unsettled answer and must stay
     * retryable — marking those would strand a plugin unsigned through the
     * enforcement flip.
     *
     * The marker exists because the retry trigger is "sidecar missing", which the
     * mismatch case deliberately never satisfies. Without it, `getDownloadUrl` is
     * called again on every launch, forever, and it is not read-only: it books a
     * row in `plugin_downloads`, which feeds the store's default
     * `sortBy = "downloads"` ranking. A signature-only store route would remove
     * the cost rather than remember it, but that is an edge-function change; see
     * BossConsole#108.
     *
     * Self-invalidating: the anchor carries pluginId, version and digest, so any
     * change to the local identity, version or digest allows another lookup.
     * A corrected store row alone does not invalidate the marker; remove it to retry.
     */
    fun markUnsignable(
        jarPath: String,
        anchor: String,
    ) {
        runCatching { File(unsignablePathFor(jarPath)).writeText(anchor) }
    }

    /** True when [markUnsignable] recorded exactly this [anchor] for [jarPath]. */
    fun isKnownUnsignable(
        jarPath: String,
        anchor: String,
    ): Boolean {
        val f = File(unsignablePathFor(jarPath))
        return f.exists() && runCatching { f.readText().trim() == anchor }.getOrDefault(false)
    }
}
