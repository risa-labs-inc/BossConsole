package ai.rever.boss.services.importer

import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.workspace.TabConfig
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.fail

class BookmarkExportTest {
    private val work =
        collectionOf(
            "Work",
            webBookmark("Docs", "https://docs.example.com/"),
            webBookmark("Mail", "https://mail.example.com/"),
            Bookmark(id = "t", tabConfig = TabConfig(type = "terminal", title = "Shell"), workspaceName = ""),
        )

    private val neverAsked: (String) -> String? = { fail("the save dialog must not be shown") }
    private val neverWritten: (String, String) -> Unit = { _, _ -> fail("nothing may be written") }

    @Test
    fun `a missing Bookmarks tool is reported as unavailable, not as having no bookmarks`() =
        runTest {
            val result = BookmarkExport.export(collections = null, chooseFile = neverAsked, writeFile = neverWritten)

            assertEquals(BookmarkExportResult.ToolUnavailable, result)
            assertEquals("The Bookmarks tool isn't available, so bookmarks couldn't be exported.", result.message())
        }

    @Test
    fun `with no Bookmarks tool loaded the collections are unknown, not empty`() {
        // Nothing in the test JVM initialises BookmarkAPIAccess, which is the state a missing
        // plugin leaves. BookmarkAPIAccess.getCollections() would answer emptyList() here.
        assertNull(BookmarkExport.installedCollections())
    }

    @Test
    fun `nothing is asked or written when no bookmark is a web page`() =
        runTest {
            val onlyTerminal =
                collectionOf(
                    "Shells",
                    Bookmark(id = "t", tabConfig = TabConfig(type = "terminal", title = "Shell"), workspaceName = ""),
                )

            val result = BookmarkExport.export(listOf(onlyTerminal), chooseFile = neverAsked, writeFile = neverWritten)

            assertEquals(BookmarkExportResult.NothingToExport(skipped = 1), result)
            assertEquals("There are no web bookmarks to export; 1 bookmark isn't a web page.", result.message())
        }

    @Test
    fun `with no bookmarks at all nothing is asked or written`() =
        runTest {
            val result = BookmarkExport.export(emptyList(), chooseFile = neverAsked, writeFile = neverWritten)

            assertEquals(BookmarkExportResult.NothingToExport(skipped = 0), result)
            assertEquals("There are no bookmarks to export.", result.message())
        }

    @Test
    fun `cancelling the save dialog writes nothing and says nothing`() =
        runTest {
            val result = BookmarkExport.export(listOf(work), chooseFile = { null }, writeFile = neverWritten)

            assertEquals(BookmarkExportResult.Cancelled, result)
            assertNull(result.message())
        }

    @Test
    fun `the export is written where the user chose and reports what it wrote`() =
        runTest {
            val written = mutableListOf<Pair<String, String>>()
            val result =
                BookmarkExport.export(
                    listOf(work),
                    chooseFile = { suggested ->
                        assertEquals("boss-bookmarks.html", suggested)
                        "/exports/bookmarks.html"
                    },
                    writeFile = { path, text -> written.add(path to text) },
                )

            assertIs<BookmarkExportResult.Exported>(result)
            assertEquals(listOf("/exports/bookmarks.html" to NetscapeBookmarkWriter.write(listOf(work)).html), written)
            assertEquals(
                "Exported 2 bookmarks from 1 collection to bookmarks.html, leaving out 1 that isn't a web page.",
                result.message(),
            )
        }

    @Test
    fun `a write the disk refuses is reported with its reason`() =
        runTest {
            val result =
                BookmarkExport.export(
                    listOf(work),
                    chooseFile = { "/exports/bookmarks.html" },
                    writeFile = { _, _ -> throw IOException("disk full") },
                )

            assertIs<BookmarkExportResult.Failed>(result)
            assertEquals("Couldn't export bookmarks to bookmarks.html: disk full", result.message())
        }

    @Test
    fun `a file name too short for the temp file is reported, not thrown`() =
        runTest {
            // File.createTempFile refuses a prefix under three characters with an
            // IllegalArgumentException, and atomicWriteText uses "<name>." as the prefix (#1035).
            val tooShort = IllegalArgumentException("Prefix string \"a.\" too short: length must be at least 3")
            val result =
                BookmarkExport.export(
                    listOf(work),
                    chooseFile = { "/exports/a" },
                    writeFile = { _, _ -> throw tooShort },
                )

            assertIs<BookmarkExportResult.Failed>(result)
        }

    @Test
    fun `the save dialog and the write run on the IO dispatcher, not the caller's thread`() =
        runTest {
            // pickSaveFile wraps its dialog in SwingUtilities.invokeAndWait, which throws
            // java.lang.Error when called from the event thread the menu runs on.
            val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "export-io") }
            val threads = mutableListOf<String>()
            try {
                BookmarkExport.export(
                    listOf(work),
                    chooseFile = {
                        threads.add(Thread.currentThread().name)
                        "/exports/bookmarks.html"
                    },
                    writeFile = { _, _ -> threads.add(Thread.currentThread().name) },
                    io = executor.asCoroutineDispatcher(),
                )
            } finally {
                executor.shutdownNow()
            }

            assertEquals(listOf("export-io", "export-io"), threads)
        }

    @Test
    fun `the exported file reads back through the importer`() =
        runTest {
            val dir = Files.createTempDirectory("bookmark-export").toFile()
            try {
                val target = File(dir, "bookmarks.html")

                val result = BookmarkExport.export(listOf(work), chooseFile = { target.absolutePath })

                assertIs<BookmarkExportResult.Exported>(result)
                assertEquals(
                    listOf(
                        ImportedBookmark("Docs", "https://docs.example.com/", "Work"),
                        ImportedBookmark("Mail", "https://mail.example.com/", "Work"),
                    ),
                    NetscapeBookmarkParser.parse(target.readText()),
                )
                val left = dir.listFiles().orEmpty().map { it.name }
                assertEquals(listOf("bookmarks.html"), left, "no temp file may be left behind")
            } finally {
                dir.deleteRecursively()
            }
        }
}
