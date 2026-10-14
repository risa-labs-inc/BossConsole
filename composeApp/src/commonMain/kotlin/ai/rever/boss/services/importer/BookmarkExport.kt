package ai.rever.boss.services.importer

import ai.rever.boss.components.bars.horizontal.StatusMessageManager
import ai.rever.boss.platform.pickSaveFile
import ai.rever.boss.plugin.bookmark.BookmarkCollection
import ai.rever.boss.services.bookmarks.BookmarkAPIAccess
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** How an "Export Bookmarks..." run ended. [message] says it to the user. */
sealed interface BookmarkExportResult {
    /** The Bookmarks tool is not loaded, so what it holds is unknown. Not the same as holding nothing. */
    data object ToolUnavailable : BookmarkExportResult

    /** Nothing a browser could import, so no file was asked for. */
    data class NothingToExport(
        val skipped: Int,
    ) : BookmarkExportResult

    data object Cancelled : BookmarkExportResult

    data class Exported(
        val path: String,
        val file: NetscapeBookmarkFile,
    ) : BookmarkExportResult

    data class Failed(
        val path: String,
        val reason: String,
    ) : BookmarkExportResult
}

/**
 * "Export Bookmarks...": every bookmark collection, written to a file the user picks, as the
 * other half of [ImportService.importBookmarks]. The file itself is [NetscapeBookmarkWriter]'s.
 */
object BookmarkExport {
    const val SUGGESTED_FILE_NAME = "boss-bookmarks.html"
    private const val MESSAGE_DURATION_MS = 6000L

    private val logger = BossLogger.forComponent("BookmarkExport")

    /**
     * What the Bookmarks tool holds, or null when it is not loaded.
     *
     * Deliberately not [BookmarkAPIAccess.getCollections], which answers an empty list in both
     * cases and would report a missing tool as "no bookmarks".
     */
    internal fun installedCollections() = BookmarkAPIAccess.getProvider()?.collections?.value

    /** The menu command. The outcome goes to the status bar; a cancelled dialog says nothing. */
    suspend fun runFromMenu() {
        val result =
            export(
                collections = installedCollections(),
                chooseFile = { suggested -> pickSaveFile(suggested, allowedExtensions = listOf("html", "htm")) },
            )
        result.message()?.let { StatusMessageManager.showMessage(it, MESSAGE_DURATION_MS) }
    }

    /**
     * Write [collections] to the file [chooseFile] names.
     *
     * Everything after the null check runs on [io]: building the file, [chooseFile] and
     * [writeFile]. The desktop save dialog wraps itself in `SwingUtilities.invokeAndWait`, which
     * throws `java.lang.Error` when called from the event thread the menu runs on, and an `Error`
     * gets past the dialog's own `catch (e: Exception)`.
     *
     * The default [writeFile] is [atomicWriteText], so on a POSIX filesystem the export is
     * readable by its owner only (0600), like every file that helper writes. That suits a file
     * whose URLs can carry tokens, and it means other accounts on the machine, root aside, cannot
     * read it until its owner changes that.
     *
     * @param collections null when the Bookmarks tool is not loaded
     * @param chooseFile the path to write, or null when the user cancelled
     */
    suspend fun export(
        collections: List<BookmarkCollection>?,
        chooseFile: (suggestedName: String) -> String?,
        writeFile: (path: String, text: String) -> Unit = { path, text -> File(path).atomicWriteText(text) },
        io: CoroutineDispatcher = Dispatchers.IO,
    ): BookmarkExportResult {
        if (collections == null) return BookmarkExportResult.ToolUnavailable
        return withContext(io) {
            val file = NetscapeBookmarkWriter.write(collections)
            if (file.bookmarks == 0) {
                BookmarkExportResult.NothingToExport(file.skipped)
            } else {
                chooseFile(SUGGESTED_FILE_NAME)?.let { path -> save(path, file, writeFile) }
                    ?: BookmarkExportResult.Cancelled
            }
        }
    }

    private fun save(
        path: String,
        file: NetscapeBookmarkFile,
        writeFile: (path: String, text: String) -> Unit,
    ): BookmarkExportResult =
        try {
            writeFile(path, file.html)
            // Counts only: bookmark URLs can carry tokens, so they are never logged.
            val counts = mapOf("bookmarks" to file.bookmarks, "skipped" to file.skipped)
            logger.info(LogCategory.FILE, "Bookmarks exported", counts)
            BookmarkExportResult.Exported(path, file)
        } catch (e: IOException) {
            failed(path, e)
        } catch (e: IllegalArgumentException) {
            // File.createTempFile refuses a prefix under three characters, and atomicWriteText
            // builds its prefix from the file name, so a one-letter name fails here (#1035).
            failed(path, e)
        }

    private fun failed(
        path: String,
        e: Exception,
    ): BookmarkExportResult.Failed {
        logger.warn(LogCategory.FILE, "Bookmark export failed", mapOf("file" to File(path).name), error = e)
        return BookmarkExportResult.Failed(path, e.message ?: e::class.simpleName.orEmpty())
    }
}

/** The status-bar sentence for this result; null when there is nothing to say. */
fun BookmarkExportResult.message(): String? =
    when (this) {
        BookmarkExportResult.ToolUnavailable -> {
            "The Bookmarks tool isn't available, so bookmarks couldn't be exported."
        }

        is BookmarkExportResult.NothingToExport -> {
            if (skipped == 0) {
                "There are no bookmarks to export."
            } else {
                "There are no web bookmarks to export; ${count(skipped, "bookmark")} ${notWebPage(skipped)}."
            }
        }

        BookmarkExportResult.Cancelled -> {
            null
        }

        is BookmarkExportResult.Exported -> {
            val left = file.skipped
            val leftOut = if (left == 0) "" else ", leaving out $left that ${notWebPage(left)}"
            "Exported ${count(file.bookmarks, "bookmark")} from ${count(file.sourceCollections, "collection")} " +
                "to ${File(path).name}$leftOut."
        }

        is BookmarkExportResult.Failed -> {
            "Couldn't export bookmarks to ${File(path).name}: $reason"
        }
    }

private fun count(
    n: Int,
    noun: String,
): String = if (n == 1) "1 $noun" else "$n ${noun}s"

private fun notWebPage(n: Int): String = if (n == 1) "isn't a web page" else "aren't web pages"
