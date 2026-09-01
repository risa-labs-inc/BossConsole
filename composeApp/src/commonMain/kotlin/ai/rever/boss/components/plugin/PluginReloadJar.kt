package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.Version
import java.io.File

/**
 * The JAR a reload should load: the one the plugin is running from, the installer's
 * recorded one, or the best match in the plugin directory, with the highest manifest
 * version winning when more than one candidate exists.
 *
 * **Every candidate is checked against the filesystem, and that is the whole point.** A reload is
 * usually triggered by an update that has already replaced the jar: the new file is version-named,
 * so it has a DIFFERENT name and the old one is deleted, which makes [loadedJarPath] reliably stale
 * in exactly the case reload matters most. Trusting it unloaded the plugin and then failed to load
 * it, leaving the plugin gone until the next restart, with only the load half logging anything.
 *
 * On Windows the previously-loaded JAR may survive the updater's cleanup (`delete()` can return
 * false while the JVM holds a lock on it). Position-based ordering would then reload from the stale
 * jar, so this resolver compares manifest versions and picks the highest. When no manifest version
 * is available it falls back to the original position order, preserving callers that do not supply
 * a version reader.
 *
 * Tiebreaks (matching [ai.rever.boss.plugin.PluginJarReconciler.pickWinner]):
 *  1. Highest parseable manifest version (unparseable versions sort lowest as `0.0.0`).
 *  2. The path matching [persistedJarPath] (the installed record), then newest mtime, then filename.
 *
 * Returning null rather than guessing lets the caller keep the plugin running instead of unloading
 * it for a load that cannot work.
 *
 * Pure, with [exists], [relocated], and [manifestVersion] injected, so the decision is testable
 * without a filesystem.
 */
internal fun resolveReloadJarPath(
    loadedJarPath: String?,
    persistedJarPath: String?,
    exists: (String) -> Boolean,
    relocated: () -> String?,
    manifestVersion: (String) -> String? = { null },
): String? {
    val candidates = mutableListOf<String>()

    if (loadedJarPath != null && exists(loadedJarPath)) candidates.add(loadedJarPath)
    if (persistedJarPath != null && exists(persistedJarPath)) candidates.add(persistedJarPath)

    // Always consider the directory-resolved candidate: the loaded and persisted paths can both
    // be stale on Windows if the old JAR could not be deleted, and the directory may already hold
    // a newer version under a new name. [relocated] is responsible for its own existence check
    // (this is how the original function treated it and how [findRelocatedPluginJar] behaves).
    val relocatedJarPath = relocated()
    if (relocatedJarPath != null && !candidates.contains(relocatedJarPath)) {
        candidates.add(relocatedJarPath)
    }

    if (candidates.isEmpty()) return null

    val candidateVersions =
        candidates.map { path ->
            path to runCatching { manifestVersion(path)?.let { Version.parse(it) } }.getOrNull()
        }

    return if (candidateVersions.all { it.second == null }) {
        // No manifest version information available: keep the original position-based
        // preference so callers that do not supply a version reader behave as before.
        candidates.first()
    } else {
        candidateVersions
            .maxWithOrNull(
                compareBy(
                    { it.second ?: Version(0, 0, 0) },
                    { it.first == persistedJarPath },
                    { File(it.first).lastModified() },
                    { File(it.first).name },
                ),
            )?.first
    }
}
