package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.Version

/**
 * The JAR a reload should load: the one the plugin is running from, else the installer's recorded
 * one — comparing manifest versions when both exist — else whatever is on disk under a new name,
 * else null.
 *
 * **Every candidate is checked against the filesystem, and that is the whole point.** A reload is
 * usually triggered by an update that has already replaced the jar: the new file is version-named,
 * so it has a DIFFERENT name and the old one is deleted, which makes [loadedJarPath] reliably stale
 * in exactly the case reload matters most. Trusting it unloaded the plugin and then failed to load
 * it, leaving the plugin gone until the next restart, with only the load half logging anything.
 *
 * On Windows the previously-loaded JAR may survive the updater's cleanup (`delete()` can return
 * false while the JVM holds a lock on it). Position-based ordering would then reload from the
 * stale jar, so when the loaded jar and the persisted record both exist and both manifests parse,
 * the higher manifest version wins. Equal versions — and every case where no known candidate
 * yields a parseable version — keep the original position preference (loaded first), which also
 * preserves callers that do not supply a version reader.
 *
 * [relocated] is a TRUE last resort, unchanged: it is consulted only when neither candidate in
 * [candidates] survives its existence check or its manifest read. The plugin directory is
 * deliberately not part of the version comparison — a routine reload must not silently swap to a
 * stray dev build or a pinned/downgraded install that happens to declare a higher version under
 * the same [pluginId] key, nor race a download that streams onto a scannable
 * `<pluginId>-<version>.jar` name.
 *
 * Documented trade-off, not a defect: when both known candidates exist, the persisted
 * `installed.json` record CAN redirect a reload away from the running jar while that jar is still
 * on disk — the record's manifest version being higher wins, same directory, same user, no
 * privilege boundary crossed.
 *
 * Returning null rather than guessing lets the caller keep the plugin running instead of unloading
 * it for a load that cannot work.
 *
 * Pure, with [exists], [relocated], and [manifestVersion] injected, so the decision is testable
 * without a filesystem. [onManifestVersionReadFailed] is a hook for callers to log a candidate
 * that will be scored as if it had no version, so a mis-scored reload is diagnosable.
 *
 * Manifest versions are only read when two or more known candidates survive: with a single
 * survivor the comparison cannot change the outcome, so the read is skipped rather than paid for —
 * the menu-driven reload path has no persisted record and runs on the composition dispatcher,
 * where a jar open + manifest parse would be blocking I/O on the UI thread (docs/THREADING.md).
 * Build metadata (`+build`) is stripped before parsing, per semver §10: it must be ignored for
 * precedence, and the manifest reader accepts versions that [Version.parse] cannot split once a
 * `+` is present.
 */
internal fun resolveReloadJarPath(
    candidates: ReloadJarCandidates,
    exists: (String) -> Boolean,
    relocated: () -> String?,
    manifestVersion: (String) -> String? = { null },
    onManifestVersionReadFailed: (String) -> Unit = {},
): String? {
    // Known candidates in the original position order: the running jar, then the installer's
    // record. The directory scan is deliberately NOT consulted here (see [relocated] above).
    val known =
        listOfNotNull(candidates.loadedJarPath, candidates.persistedJarPath)
            .filter { exists(it) }

    return when {
        known.isEmpty() -> {
            relocated()
        }

        // One surviving candidate cannot be out-voted. Reading its manifest would be pure cost,
        // and on the menu-driven reload path that cost is blocking JarFile I/O on the UI thread.
        known.size == 1 -> {
            known.first()
        }

        else -> {
            val versions =
                known.associateWith { path ->
                    runCatching { manifestVersion(path)?.let { Version.parse(it.substringBefore('+')) } }
                        .onFailure { onManifestVersionReadFailed(path) }
                        .getOrNull()
                }
            val readable = versions.filterValues { it != null }
            // Highest manifest version wins; equal versions (and every case where no known
            // candidate yields a parseable version — no-reader callers and unreadable manifests
            // alike) keep the original position preference (the loaded jar first), which is the
            // order this resolver had before version awareness was added.
            readable.entries
                .maxWithOrNull(compareBy({ it.value }, { -known.indexOf(it.key) }))
                ?.key
                ?: known.first()
        }
    }
}
