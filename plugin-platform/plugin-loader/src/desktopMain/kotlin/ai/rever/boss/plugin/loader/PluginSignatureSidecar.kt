package ai.rever.boss.plugin.loader

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
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

    fun pathFor(jarPath: String): String = "$jarPath$SUFFIX"

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
     */
    fun write(
        jarPath: String,
        signatureBase64: String,
    ) {
        val target = File(pathFor(jarPath))
        val tmp = File("${target.absolutePath}.tmp")
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

    /** The stored base64 signature, or null when no sidecar exists. */
    fun read(jarPath: String): String? {
        val f = File(pathFor(jarPath))
        return if (f.exists()) f.readText().trim().ifEmpty { null } else null
    }

    /** Remove a sidecar (e.g. alongside a rejected/purged JAR). Best-effort. */
    fun delete(jarPath: String) {
        File(pathFor(jarPath)).delete()
    }
}
