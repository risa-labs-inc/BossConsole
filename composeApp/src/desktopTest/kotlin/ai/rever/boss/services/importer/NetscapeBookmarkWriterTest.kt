package ai.rever.boss.services.importer

import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.bookmark.BookmarkCollection
import ai.rever.boss.plugin.workspace.TabConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal fun webBookmark(
    title: String,
    url: String,
    createdAt: Long = 0L,
) = Bookmark(
    id = "b-$title",
    tabConfig = TabConfig(type = "browser", title = title, url = url),
    workspaceName = "",
    createdAt = createdAt,
)

internal fun collectionOf(
    name: String,
    vararg bookmarks: Bookmark,
) = BookmarkCollection(id = "c-$name", name = name, bookmarks = bookmarks.toList(), createdAt = 0L)

/**
 * The oracle is the importer BOSS already ships: whatever the writer produces has to come back
 * through [NetscapeBookmarkParser] as the same collection, title and URL, because that is the
 * one reader in this repo and it is stricter than a browser about quotes and entities.
 */
class NetscapeBookmarkWriterTest {
    private fun readBack(vararg collections: BookmarkCollection): List<ImportedBookmark> =
        NetscapeBookmarkParser.parse(NetscapeBookmarkWriter.write(collections.toList()).html)

    private fun readBackOne(
        collection: String,
        bookmark: Bookmark,
    ): ImportedBookmark = readBack(collectionOf(collection, bookmark)).single()

    @Test
    fun `every web bookmark reads back through the shipped parser, in order`() {
        val read =
            readBack(
                collectionOf(
                    "Work",
                    webBookmark("Docs", "https://docs.example.com/"),
                    webBookmark("Mail", "https://mail.example.com/"),
                ),
                collectionOf("Reading", webBookmark("Blog", "https://blog.example.com/post")),
            )

        assertEquals(
            listOf(
                ImportedBookmark("Docs", "https://docs.example.com/", "Work"),
                ImportedBookmark("Mail", "https://mail.example.com/", "Work"),
                ImportedBookmark("Blog", "https://blog.example.com/post", "Reading"),
            ),
            read,
        )
    }

    @Test
    fun `an apostrophe in a URL is kept whole`() {
        // The parser reads HREF up to the first quote of either kind.
        val url = "https://example.com/search?q=it's&lang=en"

        assertEquals(url, readBackOne("Search", webBookmark("Query", url)).url)
    }

    @Test
    fun `a double quote or angle bracket in a URL is kept whole`() {
        // Chromium percent-encodes these, but BookmarkDataProvider.addBookmark stores whatever
        // TabConfig a caller hands it. Unescaped, the quote ends HREF and the bracket ends the tag.
        val url = "https://example.com/find?q=\"exact\">more"

        assertEquals(url, readBackOne("Search", webBookmark("Exact", url)).url)
    }

    @Test
    fun `markup characters in a title read back as the same text`() {
        val title = "R&D <beta> \"quoted\" &lt; not an entity"

        assertEquals(title, readBackOne("Work", webBookmark(title, "https://rd.example.com/")).title)
    }

    @Test
    fun `a title shaped like a tag is written as text, not markup`() {
        // The shipped parser only strips `<...>` pairs, so it reads a raw `<` back unharmed and
        // cannot see this. A browser can: a page titled like this, exported raw, would run as a
        // script if someone opened the file itself.
        val title = "<script>alert(1)</script>"
        val bookmark = webBookmark(title, "https://x.example.com/")
        val html = NetscapeBookmarkWriter.write(listOf(collectionOf("Work", bookmark))).html

        assertFalse("<script>" in html, html)
        assertTrue("&lt;script&gt;alert(1)&lt;/script&gt;" in html, html)
        assertEquals(listOf(title), NetscapeBookmarkParser.parse(html).map { it.title })
    }

    @Test
    fun `an apostrophe in a title reads back`() {
        val title = "Nikhil's notes"

        assertEquals(title, readBackOne("Work", webBookmark(title, "https://notes.example.com/")).title)
    }

    @Test
    fun `a collection name with markup reads back as the same collection`() {
        val name = "R&D <2026> \"team\""

        assertEquals(name, readBackOne(name, webBookmark("Plan", "https://plan.example.com/")).folder)
    }

    @Test
    fun `bookmarks that are not web pages are left out and counted`() {
        val terminal = Bookmark(id = "t", tabConfig = TabConfig(type = "terminal", title = "Shell"), workspaceName = "")
        val editor =
            Bookmark(
                id = "e",
                tabConfig = TabConfig(type = "editor", title = "Main.kt", filePath = "/work/Main.kt"),
                workspaceName = "",
            )
        val file =
            NetscapeBookmarkWriter.write(
                listOf(
                    collectionOf(
                        "Mixed",
                        terminal,
                        editor,
                        webBookmark("Bookmarklet", "javascript:alert(1)"),
                        webBookmark("Site", "https://site.example.com/"),
                    ),
                ),
            )

        assertEquals(1, file.bookmarks)
        assertEquals(3, file.skipped)
        assertFalse("javascript:" in file.html, "a bookmarklet must not be written")
        assertEquals(
            listOf(ImportedBookmark("Site", "https://site.example.com/", "Mixed")),
            NetscapeBookmarkParser.parse(file.html),
        )
    }

    @Test
    fun `ADD_DATE is written in seconds, which is what browsers read`() {
        val bookmark = webBookmark("Docs", "https://docs.example.com/", createdAt = 1_700_000_000_123L)
        val html = NetscapeBookmarkWriter.write(listOf(collectionOf("Work", bookmark))).html

        assertTrue("ADD_DATE=\"1700000000\"" in html, html)
    }

    @Test
    fun `the file declares UTF-8 and non-ASCII text reads back`() {
        val title = "日本語 ✓ café"
        val bookmark = webBookmark(title, "https://travel.example.com/")
        val html = NetscapeBookmarkWriter.write(listOf(collectionOf("Travel", bookmark))).html

        assertTrue(html.startsWith("<!DOCTYPE NETSCAPE-Bookmark-file-1>"), html)
        assertTrue("charset=UTF-8" in html, html)
        assertEquals(listOf(title), NetscapeBookmarkParser.parse(html).map { it.title })
    }

    @Test
    fun `an empty collection is written as an empty folder`() {
        val file =
            NetscapeBookmarkWriter.write(
                listOf(collectionOf("Empty"), collectionOf("Work", webBookmark("Docs", "https://docs.example.com/"))),
            )

        assertTrue(">Empty</H3>" in file.html, file.html)
        // The importer creates collections from bookmarks, so an empty folder does not come back.
        // Browsers keep it; this pins what BOSS itself does on re-import.
        assertEquals(listOf("Work"), NetscapeBookmarkParser.parse(file.html).map { it.folder })
    }

    @Test
    fun `only collections a bookmark was written from are counted`() {
        val terminal = Bookmark(id = "t", tabConfig = TabConfig(type = "terminal", title = "Shell"), workspaceName = "")
        val file =
            NetscapeBookmarkWriter.write(
                listOf(
                    collectionOf("Empty"),
                    collectionOf("Shells", terminal),
                    collectionOf("Work", webBookmark("Docs", "https://docs.example.com/")),
                ),
            )

        // All three are written as folders; only Work gave the file a bookmark.
        assertEquals(3, Regex("<H3 ").findAll(file.html).count())
        assertEquals(1, file.sourceCollections)
    }
}
