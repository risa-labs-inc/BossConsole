package ai.rever.boss.utils

import java.io.File

/** The sibling that holds the last-known-good copy of this settings file. */
fun File.settingsBackupFile(): File = File(parentFile, "$name.bak")

/**
 * Loads and decodes this settings file, keeping a last-known-good `.bak` sibling.
 *
 * The manager-less settings managers used to reset every preference in a file to defaults on the
 * first decode error - one out-of-range field, an unknown enum from a downgrade, or a torn write
 * was enough to lose the lot. This keeps a backup instead:
 *
 * - missing file: returns null, so the caller runs its own first-run / defaults path;
 * - parses cleanly: refreshes the `.bak` from it and returns the value;
 * - fails to parse: tries the `.bak`; if THAT parses, rewrites this file from it and returns the
 *   value; otherwise returns null so the caller falls back to defaults - but only after a real
 *   backup attempt.
 *
 * [decode] must throw on invalid content (as `kotlinx.serialization` does). Backup read/write
 * failures never propagate: a backup is best effort and must not turn a recoverable load into a
 * crash. [onCorruptPrimary] and [onRestoredFromBackup] are for logging.
 */
@Suppress("ReturnCount") // Distinct early exits for missing / clean / restored / unrecoverable.
fun <T> File.loadSettingsWithBackup(
    decode: (String) -> T,
    onCorruptPrimary: (Exception) -> Unit = {},
    onRestoredFromBackup: () -> Unit = {},
): T? {
    if (!exists()) return null

    val primary = runCatching { decode(readText()) }
    primary.getOrNull()?.let { value ->
        // Promote the just-verified content so a later corruption has something to fall back to.
        runCatching { copyTo(settingsBackupFile(), overwrite = true) }
        return value
    }
    onCorruptPrimary(primary.exceptionOrNull().asException())

    val backup = settingsBackupFile()
    if (!backup.exists()) return null
    val backupContent = runCatching { backup.readText() }.getOrNull() ?: return null
    val restored = runCatching { decode(backupContent) }.getOrNull() ?: return null

    // Repair the primary file from the backup so the next load is clean again.
    runCatching { writeText(backupContent) }
    onRestoredFromBackup()
    return restored
}

private fun Throwable?.asException(): Exception = this as? Exception ?: RuntimeException(this)
