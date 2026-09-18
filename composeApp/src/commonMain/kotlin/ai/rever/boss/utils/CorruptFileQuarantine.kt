package ai.rever.boss.utils

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * How many sets-aside copies of one file are kept. Enough to survive a second bad launch without
 * letting a file that corrupts every time fill the directory.
 */
const val QUARANTINE_KEEP = 3

/**
 * Sets aside a state file whose contents could not be read, and returns where it went, or null when
 * nothing was moved.
 *
 * A settings manager that cannot parse its file falls back to defaults in memory, and that is only
 * safe while the file on disk is left alone. The next save writes the defaults - plus whatever the
 * user just changed - over the only copy of what they had, so one truncated write (a crash, a kill,
 * a full disk) silently turns into "all my customisations are gone". Moving the file out of the way
 * first means the fallback costs a launch on defaults instead of the data, and the user or a support
 * conversation can still open the copy.
 *
 * The move is atomic, so there is no instant at which neither the original nor the copy exists, and
 * it is best effort: a failure returns null and never throws, because recovery must not be what
 * stops the application starting. Only the newest [QUARANTINE_KEEP] copies are kept. [now] is
 * zero-padded into the name so that lexical order is chronological order.
 */
fun File.quarantineCorruptFile(now: Long = System.currentTimeMillis()): File? {
    if (!isFile) return null
    val target = File(absoluteFile.parentFile, "$name$QUARANTINE_MARKER${now.toString().padStart(STAMP_WIDTH, '0')}")
    return try {
        Files.move(toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        pruneQuarantined()
        target
    } catch (_: java.io.IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}

private fun File.pruneQuarantined() {
    val siblings =
        absoluteFile.parentFile
            ?.listFiles { candidate -> candidate.isFile && candidate.name.startsWith("$name$QUARANTINE_MARKER") }
            .orEmpty()
    siblings
        .sortedByDescending { it.name }
        .drop(QUARANTINE_KEEP)
        .forEach { it.delete() }
}

private const val QUARANTINE_MARKER = ".corrupt-"
private const val STAMP_WIDTH = 15
