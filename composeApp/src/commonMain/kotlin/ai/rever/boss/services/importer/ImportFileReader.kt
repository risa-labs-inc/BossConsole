package ai.rever.boss.services.importer

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File

/**
 * Turns an exported file into an [ImportPreview].
 *
 * Separate from [ImportService] so that reading untrusted files stays apart
 * from writing to the vault and the bookmark store. Handles plaintext
 * credentials, so the logging rule is absolute: counts and reasons only, never
 * a password, username or full URL.
 */
object ImportFileReader {
    private val logger = BossLogger.forComponent("ImportFileReader")

    /**
     * Read [path] and work out what it holds.
     *
     * Sniffs on content rather than extension alone — people rename exports.
     */
    fun parseFile(path: String): Result<ImportPreview> =
        runCatching {
            val file = File(path)
            if (!file.isFile) throw UnrecognisedImportFileException("That path is not a file.")
            file.readText()
        }.fold(
            onSuccess = { text -> parseContent(path, text) },
            onFailure = { error ->
                logger.warn(
                    LogCategory.FILE,
                    "Could not read import file",
                    mapOf("reason" to (error.message ?: error::class.simpleName.orEmpty())),
                )
                Result.failure(error)
            },
        )

    /**
     * Same as [parseFile], for callers that already hold the file's text — the
     * file picker reads content as it selects, and a password CSV should not be
     * pulled into memory twice.
     *
     * [path] is used only to sniff the extension.
     */
    fun parseContent(
        path: String,
        text: String,
    ): Result<ImportPreview> =
        runCatching {
            if (text.isBlank()) throw UnrecognisedImportFileException("That file is empty.")

            val looksLikeHtml =
                text.contains("<DT", ignoreCase = true) ||
                    text.contains("NETSCAPE-Bookmark-file", ignoreCase = true) ||
                    path.endsWith(".html", ignoreCase = true) ||
                    path.endsWith(".htm", ignoreCase = true)

            // Password-manager formats are sniffed before HTML/CSV: their markers
            // are unambiguous, so a KeePass export saved with an odd extension is
            // still read correctly.
            when {
                KeePassXmlParser.looksLikeKeePass(text) -> parseKeePassXml(text)
                BitwardenJsonParser.looksLikeBitwarden(text) -> parseBitwardenJson(text)
                looksLikeHtml -> parseBookmarkHtml(text)
                else -> parsePasswordCsv(text)
            }
        }.onFailure { error ->
            logger.warn(
                LogCategory.FILE,
                "Could not parse import file",
                mapOf("reason" to (error.message ?: error::class.simpleName.orEmpty())),
            )
        }

    private fun parseBookmarkHtml(text: String): ImportPreview {
        val bookmarks = NetscapeBookmarkParser.parse(text)
        if (bookmarks.isEmpty()) {
            throw UnrecognisedImportFileException(
                "No bookmarks found in that file. Export bookmarks as HTML and try again.",
            )
        }
        logger.info(LogCategory.FILE, "Parsed bookmark export", mapOf("count" to bookmarks.size))
        return ImportPreview(bookmarks = bookmarks)
    }

    private fun parseBitwardenJson(text: String): ImportPreview {
        val passwords = BitwardenJsonParser.parseEntries(text)
        if (passwords.isEmpty()) {
            throw UnrecognisedImportFileException(
                "No logins found in that Bitwarden export. Export the vault unencrypted and try again.",
            )
        }
        logger.info(LogCategory.AUTH, "Parsed Bitwarden export", mapOf("count" to passwords.size))
        return passwordManagerPreview(passwords)
    }

    private fun parseKeePassXml(text: String): ImportPreview {
        val passwords = KeePassXmlParser.parseEntries(text)
        if (passwords.isEmpty()) {
            throw UnrecognisedImportFileException(
                "No entries with a password found in that KeePass export.",
            )
        }
        logger.info(LogCategory.AUTH, "Parsed KeePass export", mapOf("count" to passwords.size))
        return passwordManagerPreview(passwords)
    }

    private fun passwordManagerPreview(entries: List<ImportedPassword>): ImportPreview {
        val passwords = mutableListOf<ImportedPassword>()
        val skipped = mutableListOf<SkippedRow>()
        entries.forEachIndexed { index, entry ->
            val reason =
                when {
                    entry.website.isBlank() || isNonWebPasswordEntry(entry.website) -> SkipReason.MISSING_URL
                    entry.username.isBlank() -> SkipReason.MISSING_USERNAME
                    entry.password.isEmpty() -> SkipReason.MISSING_PASSWORD
                    else -> null
                }
            if (reason == null) {
                passwords.add(entry)
            } else {
                skipped.add(SkippedRow(index + 1, reason, displayLabel(entry.website, entry.username)))
            }
        }
        return ImportPreview(passwords = passwords, skipped = skipped)
    }

    private fun parsePasswordCsv(text: String): ImportPreview {
        val table = CsvParser.parseTable(text)
        val columns = table?.let { resolvePasswordColumns(it.header) }

        // One throw covering every way the file can turn out not to be a
        // password export, so the caller gets a single clear message.
        if (table == null || columns == null) {
            throw UnrecognisedImportFileException(
                "That CSV doesn't look like a password export - no URL and password columns found.",
            )
        }

        val passwords = mutableListOf<ImportedPassword>()
        val skipped = mutableListOf<SkippedRow>()

        table.rows.forEachIndexed { index, row ->
            // +2: one for the header, one to make it 1-based like a spreadsheet.
            when (val parsed = parsePasswordRow(row, columns, rowNumber = index + 2)) {
                is RowOutcome.Usable -> passwords.add(parsed.password)
                is RowOutcome.Skip -> skipped.add(parsed.row)
            }
        }

        if (passwords.isEmpty() && skipped.isEmpty()) {
            throw UnrecognisedImportFileException("That CSV has a header but no rows.")
        }

        logger.info(
            LogCategory.AUTH,
            "Parsed password export",
            mapOf("usable" to passwords.size, "skipped" to skipped.size),
        )
        return ImportPreview(passwords = passwords, skipped = skipped)
    }

    /** Column positions for [header], or null if it isn't a password export. */
    private fun resolvePasswordColumns(header: List<String>): PasswordColumns? {
        val url = CsvParser.urlColumn(header)
        val password = CsvParser.passwordColumn(header)
        if (url < 0 || password < 0) return null

        return PasswordColumns(
            url = url,
            username = CsvParser.usernameColumn(header),
            password = password,
            notes = CsvParser.notesColumn(header),
        )
    }

    /** Resolved column positions for one password export's header. */
    private data class PasswordColumns(
        val url: Int,
        val username: Int,
        val password: Int,
        val notes: Int,
    )

    private sealed interface RowOutcome {
        data class Usable(
            val password: ImportedPassword,
        ) : RowOutcome

        data class Skip(
            val row: SkippedRow,
        ) : RowOutcome
    }

    /**
     * Turn one CSV row into a credential, or say why it can't be used.
     *
     * `CreateSecretRequest.validate()` rejects empty passwords and blank identities, so these are
     * filtered here rather than letting the RPC refuse them one at a time.
     * Exports legitimately contain such rows — a passkey-only entry carries a
     * username but no password.
     */
    private fun parsePasswordRow(
        row: List<String>,
        columns: PasswordColumns,
        rowNumber: Int,
    ): RowOutcome {
        fun cell(column: Int): String = if (column >= 0) row.getOrNull(column)?.trim().orEmpty() else ""

        /** Untrimmed: edge whitespace is part of a password, not formatting. */
        fun rawCell(column: Int): String = if (column >= 0) row.getOrNull(column).orEmpty() else ""

        val url = cell(columns.url)
        val username = cell(columns.username)
        // Trimming a quoted " hunter2 " would store a value that looks right and
        // silently does not work, with nothing to point at.
        val password = rawCell(columns.password)

        val reason =
            when {
                row.size <= maxOf(columns.url, columns.password) -> SkipReason.MALFORMED_ROW
                url.isEmpty() -> SkipReason.MISSING_URL
                username.isEmpty() -> SkipReason.MISSING_USERNAME
                password.isEmpty() -> SkipReason.MISSING_PASSWORD
                else -> null
            }

        return if (reason != null) {
            RowOutcome.Skip(SkippedRow(rowNumber, reason, displayLabel(url, username)))
        } else {
            RowOutcome.Usable(
                ImportedPassword(
                    website = url,
                    username = username,
                    password = password,
                    notes = cell(columns.notes).ifEmpty { null },
                ),
            )
        }
    }
}
