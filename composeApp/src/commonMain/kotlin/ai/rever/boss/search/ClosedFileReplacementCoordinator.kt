package ai.rever.boss.search

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-local coordination for closed-file replacement transactions.
 *
 * Existing files use their NIO real path as the key, so directory symlink and
 * junction aliases share one admission point on every desktop platform.
 * Canonical paths are the fallback when real-path resolution is unavailable.
 * Entries count both owners and waiters. Removing an entry
 * immediately after unlocking would be unsafe because an existing waiter could
 * retain the old mutex while a new caller receives a different one.
 *
 * Path identity stays stable across atomic replacement; an inode key would not.
 * Real-path failures fall back to canonical identity as a best-effort degradation:
 * platforms where those spellings differ may not coordinate mixed-resolution calls.
 * If canonical resolution also fails, the caller receives a per-file error.
 * This coordinator does not synchronize with external processes or editor buffers
 * opened after the caller has selected the closed-file path.
 */
internal class ClosedFileReplacementCoordinator {
    private val entries = ConcurrentHashMap<String, Entry>()

    internal val trackedFileCount: Int
        get() = entries.size

    suspend fun <T> withFile(
        file: File,
        operation: suspend () -> T,
    ): T {
        val key = fileIdentity(file)
        val entry = acquire(key)

        return try {
            entry.mutex.withLock {
                operation()
            }
        } finally {
            release(key, entry)
        }
    }

    private fun fileIdentity(file: File): String =
        runCatching { file.toPath().toRealPath().toString() }
            .getOrElse { file.canonicalPath }

    private fun acquire(key: String): Entry =
        checkNotNull(
            entries.compute(key) { _, current ->
                (current ?: Entry()).also { it.users++ }
            },
        )

    private fun release(
        key: String,
        entry: Entry,
    ) {
        entries.computeIfPresent(key) { _, current ->
            if (current !== entry) {
                current
            } else {
                current.users--
                current.takeIf { it.users > 0 }
            }
        }
    }

    private class Entry(
        val mutex: Mutex = Mutex(),
        var users: Int = 0,
    )
}

/**
 * Shared across every window-scoped ContentSearchService in this process.
 */
internal val processClosedFileReplacements = ClosedFileReplacementCoordinator()
