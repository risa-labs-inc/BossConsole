package ai.rever.boss.services.importer

/**
 * A credential parsed out of a browser or password-manager export.
 *
 * Holds a plaintext password: never log an instance of this, never persist it
 * anywhere but the encrypted vault, and drop the reference once the import
 * finishes.
 */
data class ImportedPassword(
    val website: String,
    val username: String,
    val password: String,
    val notes: String? = null,
) {
    /**
     * Redacted on purpose.
     *
     * The generated toString would print `password=hunter2`, so a single
     * `mapOf("entry" to entry)` or an exception message interpolating a row
     * would defeat the whole logging discipline. Enforced rather than
     * documented.
     */
    override fun toString(): String = "ImportedPassword(website=$website, username=***, password=***)"
}

/**
 * A bookmark parsed out of a browser export.
 *
 * [folder] is the export's own folder path (e.g. "Work/Clients"); null means the
 * export had it at the top level.
 */
data class ImportedBookmark(
    val title: String,
    val url: String,
    val folder: String? = null,
)

/** Why a row in the source file was not imported. */
enum class SkipReason {
    MISSING_URL,
    MISSING_USERNAME,
    MISSING_PASSWORD,
    MALFORMED_ROW,
    ALREADY_EXISTS,
}

/**
 * A row that was recognised but deliberately not imported.
 *
 * [rowNumber] is 1-based and counts the header, so it lines up with what the
 * user sees opening the file in a spreadsheet.
 */
data class SkippedRow(
    val rowNumber: Int,
    val reason: SkipReason,
    /** Safe to display: never contains a password. */
    val label: String,
)

/** What a source file turned out to contain. */
data class ImportPreview(
    val passwords: List<ImportedPassword> = emptyList(),
    val bookmarks: List<ImportedBookmark> = emptyList(),
    val skipped: List<SkippedRow> = emptyList(),
) {
    val hasAnything: Boolean get() = passwords.isNotEmpty() || bookmarks.isNotEmpty()

    /** Counts only — printing the lists would print every credential. */
    override fun toString(): String {
        val counts = "${passwords.size} passwords, ${bookmarks.size} bookmarks, ${skipped.size} skipped"
        return "ImportPreview($counts)"
    }
}

/** Outcome of writing one half of an [ImportPreview] to storage. */
data class ImportResult(
    val imported: Int = 0,
    val skipped: List<SkippedRow> = emptyList(),
    /**
     * Shown in the results list. Never logged: each entry embeds the username
     * and host, and usernames are usually email addresses.
     */
    val failures: List<String> = emptyList(),
) {
    val failed: Int get() = failures.size
}

/** Raised when a chosen file is not something the importer can read. */
class UnrecognisedImportFileException(
    message: String,
) : Exception(message)

/**
 * A label safe to show in the UI and to log: identifies an entry without ever
 * including its password.
 */
internal fun displayLabel(
    website: String,
    username: String,
): String = if (username.isEmpty()) website else "$username @ $website"

/** Native-app associations must never become browser website matches. */
internal fun isNonWebPasswordEntry(raw: String): Boolean =
    listOf("android://", "androidapp://", "iosapp://").any { raw.trim().startsWith(it, ignoreCase = true) }
