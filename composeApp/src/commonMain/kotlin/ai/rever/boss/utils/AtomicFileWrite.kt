package ai.rever.boss.utils

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.ComponentLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

private val OWNER_ONLY_FILE_PERMISSIONS: Set<PosixFilePermission> =
    setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
    )

/**
 * Move [temp] onto this file, replacing it if it already exists.
 *
 * **Do not use `File.renameTo` for this.** Its behaviour when the destination exists is
 * platform-dependent, and the platforms disagree in exactly the way that hides the bug during
 * development: POSIX `rename(2)` replaces the target, so macOS and Linux work, while Win32
 * `MoveFile` fails with `ERROR_ALREADY_EXISTS`, so Windows silently stops overwriting anything
 * after the first write. That cost the browser its favicons on Windows for as long as the cache
 * had an entry — see [ai.rever.boss.cache.FaviconCache].
 *
 * `Files.move` with `REPLACE_EXISTING` is the portable form: on Windows it maps to `MoveFileEx`
 * with `MOVEFILE_REPLACE_EXISTING`. `ATOMIC_MOVE` is requested first because it additionally rules
 * out a torn destination, and is retried without when the move would cross a volume — the only
 * case that raises `AtomicMoveNotSupportedException`. Both callers create their temp file as a
 * sibling of the target, so that fallback should never fire; it is there for a caller that does
 * not. Any other refusal is a plain `IOException` and propagates.
 *
 * @throws IOException if the file could not be replaced.
 */
fun File.atomicMoveFrom(temp: File) {
    try {
        Files.move(
            temp.toPath(),
            toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temp.toPath(), toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * Write [text] to this file atomically: content goes to a UNIQUE sibling
 * temp file first, then replaces the target via [atomicMoveFrom]. A crash
 * mid-write leaves at most a stray temp file, never a truncated target;
 * concurrent writers each use their own temp file so bytes can't interleave —
 * last move wins.
 *
 * On POSIX filesystems, permissions are pinned to owner read/write (0600)
 * before moving into place so state files do not inherit a permissive umask.
 *
 * Shared by everything that persists small state files, including the workspace
 * layout written on shutdown; `grep atomicWriteText` for the current set rather
 * than trusting a list here, which has gone stale once already. Callers
 * previously open-coded this dance with a FIXED temp name, which concurrent
 * writers could clobber.
 */
fun File.atomicWriteText(text: String) {
    parentFile?.mkdirs()
    val tmp = File.createTempFile("$name.", ".tmp", parentFile)
    try {
        if (Files.getFileAttributeView(tmp.toPath(), PosixFileAttributeView::class.java) != null) {
            // Fail closed if a filesystem advertises POSIX permissions but refuses the
            // restriction. Publishing the temp file anyway would defeat this helper's security
            // contract for every state file that relies on it.
            Files.setPosixFilePermissions(tmp.toPath(), OWNER_ONLY_FILE_PERMISSIONS)
        }
        tmp.writeText(text)
        atomicMoveFrom(tmp)
    } finally {
        // No-op when the move took it away; cleans up on failure paths.
        tmp.delete()
    }
}

private fun File.canQuarantine(): Boolean = exists() && !isDirectory && length() > 0L

/**
 * Back up this file to a sibling `<name>.corrupt-<timestamp>` if it exists.
 *
 * Used when a settings or configuration file fails to deserialize. Preserves the user's
 * damaged configuration for diagnosis and recovery, while clearing the path so that
 * subsequent atomic saves can write clean defaults without silently destroying the old data.
 *
 * @return The backup file if the move succeeded, or null if this file did not exist or move failed.
 */
fun File.backupCorrupt(
    logger: ComponentLogger? = null,
    category: LogCategory = LogCategory.SYSTEM,
    error: Throwable? = null,
): File? {
    val parent = parentFile?.takeIf { canQuarantine() } ?: return null

    return try {
        val timestamp = System.currentTimeMillis()
        var candidate = File(parent, "$name.corrupt-$timestamp")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(parent, "$name.corrupt-$timestamp-$counter")
            counter++
        }

        try {
            Files.move(toPath(), candidate.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: IOException) {
            // Fallback for Windows file locks / sharing violations: copy then delete
            copyTo(candidate, overwrite = true)
            delete()
        }

        val activeLogger = logger ?: BossLogger.forComponent("FilePersistence")
        activeLogger.warn(
            category,
            "Quarantined corrupt settings file to ${candidate.name} (error: ${error?.message ?: "unknown"})",
            mapOf(
                "original" to absolutePath,
                "backup" to candidate.absolutePath,
                "size" to candidate.length(),
            ),
            error = error,
        )
        candidate
    } catch (e: IOException) {
        val activeLogger = logger ?: BossLogger.forComponent("FilePersistence")
        activeLogger.error(
            category,
            "Failed to quarantine corrupt settings file $name",
            error = e,
        )
        null
    } catch (e: SecurityException) {
        val activeLogger = logger ?: BossLogger.forComponent("FilePersistence")
        activeLogger.error(
            category,
            "Failed to quarantine corrupt settings file $name",
            error = e,
        )
        null
    }
}
