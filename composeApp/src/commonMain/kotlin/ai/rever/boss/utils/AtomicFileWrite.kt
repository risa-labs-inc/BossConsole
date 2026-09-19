package ai.rever.boss.utils

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

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

private fun setOwnerOnlyPermissions(file: File) {
    try {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
        } else {
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
            file.setExecutable(false, false)
        }
    } catch (_: Exception) {
        // Best effort on non-POSIX platforms
    }
}

/**
 * Write [text] to this file atomically: content goes to a UNIQUE sibling
 * temp file first, then replaces the target via [atomicMoveFrom]. A crash
 * mid-write leaves at most a stray temp file, never a truncated target;
 * concurrent writers each use their own temp file so bytes can't interleave —
 * last move wins.
 *
 * Shared by everything that persists small state files, including the workspace
 * layout written on shutdown; `grep atomicWriteText` for the current set rather
 * than trusting a list here, which has gone stale once already. Callers
 * previously open-coded this dance with a FIXED temp name, which concurrent
 * writers could clobber.
 */
fun File.atomicWriteText(text: String) {
    parentFile?.mkdirs()
    val prefix = "$name.".let { if (it.length < 3) it.padEnd(3, '_') else it }
    val tmp = File.createTempFile(prefix, ".tmp", parentFile)
    try {
        setOwnerOnlyPermissions(tmp)
        tmp.writeText(text)
        atomicMoveFrom(tmp)
    } finally {
        // No-op when the move took it away; cleans up on failure paths.
        tmp.delete()
    }
}
