package ai.rever.boss.services.importer

import ai.rever.boss.plugin.bookmark.BookmarkCollection
import ai.rever.boss.services.importer.browser.isImportableUrl

/**
 * What [NetscapeBookmarkWriter.write] produced.
 *
 * @property collections collections at least one bookmark was written from, which is what the
 *   status message reports. Every collection is still written as a folder, empty ones included.
 * @property bookmarks bookmarks written
 * @property skipped bookmarks left out because they are not web pages. A terminal or editor
 *   tab has no URL a browser could open, and a URL the importer refuses (a `javascript:`
 *   bookmarklet) is left out as well, so everything that is written also reads back.
 */
data class NetscapeBookmarkFile(
    val html: String,
    val collections: Int,
    val bookmarks: Int,
    val skipped: Int,
)

/**
 * Writes bookmark collections as a "Netscape Bookmark File", the format every browser's
 * Import Bookmarks command reads, and the inverse of [NetscapeBookmarkParser].
 *
 * Each collection becomes one folder, in order. The escaping is chosen for that parser as much
 * as for browsers, because importing the file back into BOSS has to give the same collection,
 * title and URL:
 * - the parser reads `HREF` up to the first quote of either kind, so an apostrophe in a URL is
 *   written as `&#39;` rather than left to cut the URL short;
 * - it decodes the named entities and `&#39;` only, so no numeric or hex entity is written;
 * - `&` is escaped first, or the `&` of every entity written after it would be escaped again.
 */
object NetscapeBookmarkWriter {
    private const val MILLIS_PER_SECOND = 1000L

    private val HEADER =
        listOf(
            "<!DOCTYPE NETSCAPE-Bookmark-file-1>",
            "<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">",
            "<TITLE>Bookmarks</TITLE>",
            "<H1>Bookmarks</H1>",
        )

    fun write(collections: List<BookmarkCollection>): NetscapeBookmarkFile {
        var bookmarks = 0
        var skipped = 0
        var sources = 0
        val html =
            buildString {
                HEADER.forEach(::appendLine)
                appendLine("<DL><p>")
                for (collection in collections) {
                    append("    <DT><H3 ADD_DATE=\"").append(seconds(collection.createdAt)).append("\">")
                    append(escape(collection.name)).appendLine("</H3>")
                    appendLine("    <DL><p>")
                    val before = bookmarks
                    for (bookmark in collection.bookmarks) {
                        val url = bookmark.tabConfig.url?.takeIf { isImportableUrl(it) }
                        if (url == null) {
                            skipped++
                            continue
                        }
                        append("        <DT><A HREF=\"").append(escape(url))
                        append("\" ADD_DATE=\"").append(seconds(bookmark.createdAt)).append("\">")
                        append(escape(bookmark.tabConfig.title)).appendLine("</A>")
                        bookmarks++
                    }
                    if (bookmarks > before) sources++
                    appendLine("    </DL><p>")
                }
                appendLine("</DL><p>")
            }
        return NetscapeBookmarkFile(html, sources, bookmarks, skipped)
    }

    /** Browsers read ADD_DATE as Unix seconds; BOSS stores milliseconds. */
    private fun seconds(epochMillis: Long): Long = epochMillis / MILLIS_PER_SECOND

    private fun escape(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
}
