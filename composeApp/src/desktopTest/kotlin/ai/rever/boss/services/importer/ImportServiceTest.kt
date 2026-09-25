package ai.rever.boss.services.importer

import ai.rever.boss.plugin.api.BookmarkDataProvider
import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.bookmark.BookmarkCollection
import ai.rever.boss.plugin.bookmark.FavoriteWorkspace
import ai.rever.boss.plugin.workspace.TabConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Recording stand-in for the bookmarks plugin.
 *
 * [BulkProvider] overrides `addBookmarks` the way plugin 2.2.0 does;
 * [LegacyProvider] does not, so it inherits the interface default the way every
 * earlier plugin build does. The distinction is the whole point of these tests.
 */
private abstract class RecordingProvider : BookmarkDataProvider {
    val state = MutableStateFlow<List<BookmarkCollection>>(emptyList())
    override val collections: StateFlow<List<BookmarkCollection>> get() = state
    override val favoriteWorkspaces: StateFlow<List<FavoriteWorkspace>> = MutableStateFlow(emptyList())

    val singleAdds = mutableListOf<Pair<String, Bookmark>>()
    val bulkAdds = mutableListOf<Pair<String, List<Bookmark>>>()
    val createdCollections = mutableListOf<String>()

    override fun addBookmark(
        collectionName: String,
        bookmark: Bookmark,
    ) {
        // Mirrors BookmarkManager: a missing collection is a silent no-op.
        if (state.value.none { it.name == collectionName }) return
        singleAdds.add(collectionName to bookmark)
    }

    override fun createCollection(name: String): BookmarkCollection {
        createdCollections.add(name)
        // Mirrors BookmarkManager: appends unconditionally, NOT get-or-create.
        val created = BookmarkCollection(name = name)
        state.value = state.value + created
        return created
    }

    override fun removeBookmark(
        collectionId: String,
        bookmarkId: String,
    ) = Unit

    override fun updateBookmark(
        collectionId: String,
        bookmark: Bookmark,
    ) = Unit

    override fun moveBookmark(
        bookmarkId: String,
        fromCollectionId: String,
        toCollectionId: String,
    ) = Unit

    override fun markBookmarkAsAccessed(
        collectionId: String,
        bookmarkId: String,
    ) = Unit

    override fun isTabBookmarked(tabConfig: TabConfig): Boolean = false

    override fun findBookmarkForTab(tabConfig: TabConfig): Pair<String, String>? = null

    override fun deleteCollection(collectionId: String) = Unit

    override fun renameCollection(
        collectionId: String,
        newName: String,
    ) = Unit

    override fun addFavoriteWorkspace(
        workspaceId: String,
        workspaceName: String,
    ) = Unit

    override fun removeFavoriteWorkspace(workspaceId: String) = Unit

    override fun isFavorite(workspaceId: String): Boolean = false
}

private class BulkProvider : RecordingProvider() {
    override val supportsBulkAdd: Boolean get() = true

    override fun addBookmarks(
        collectionName: String,
        bookmarks: List<Bookmark>,
    ) {
        bulkAdds.add(collectionName to bookmarks)
        if (state.value.none { it.name == collectionName }) createCollection(collectionName)
    }
}

private class LegacyProvider : RecordingProvider()

class ImportServiceTest {
    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("import-service-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun bookmarks(vararg spec: Pair<String, String?>): List<ImportedBookmark> =
        spec.mapIndexed { index, (url, folder) ->
            ImportedBookmark(title = "Site $index", url = url, folder = folder)
        }

    // ==================== Bookmark write path ====================

    @Test
    fun `bulk provider gets one call per folder, not per bookmark`() =
        runTest {
            // The regression this guards: looping addBookmark fires one full
            // collections.json rewrite per bookmark.
            val provider = BulkProvider()
            val input =
                bookmarks(
                    "https://a.test/" to "Work",
                    "https://b.test/" to "Work",
                    "https://c.test/" to "Work",
                    "https://d.test/" to "Personal",
                )

            val result = ImportService.importBookmarks(input, provider)

            assertEquals(2, provider.bulkAdds.size, "expected one bulk call per folder")
            assertEquals(0, provider.singleAdds.size, "bulk provider must not fall back to per-item adds")
            assertEquals(4, result.imported)
        }

    @Test
    fun `a legacy provider still lands every bookmark`() =
        runTest {
            // addBookmarks is a default method, so this provider inherits it and the
            // call succeeds — the import must not silently drop anything.
            val provider = LegacyProvider()
            val input = bookmarks("https://a.test/" to "Work", "https://b.test/" to "Work")

            val result = ImportService.importBookmarks(input, provider)

            assertEquals(2, provider.singleAdds.size)
            assertEquals(2, result.imported)
        }

    @Test
    fun `bulk capability is read from the provider, not inferred`() {
        // The provider declares this. Inferring it from the declaring class —
        // the earlier approach — could not distinguish a real override from the
        // interface shim once Kotlin emitted a bridge, and would misreport an
        // override that delegates to super, `by` delegation, or an IPC proxy.
        assertTrue(ImportService.supportsBulkBookmarkInsert(BulkProvider()))
        assertFalse(ImportService.supportsBulkBookmarkInsert(LegacyProvider()))
    }

    @Test
    fun `the interface carries a real default so older plugins inherit it`() {
        // The backward-compatibility guarantee in one assertion: a plugin built
        // against an API without addBookmarks must inherit a JVM default rather
        // than blow up with AbstractMethodError when a newer host calls it.
        val method =
            BookmarkDataProvider::class.java
                .getMethod("addBookmarks", String::class.java, List::class.java)

        assertTrue(method.declaringClass.isInterface)
        assertFalse(
            java.lang.reflect.Modifier
                .isAbstract(method.modifiers),
            "must be a default, not abstract",
        )
    }

    @Test
    fun `a collection is created before bookmarks are added to it`() =
        runTest {
            // addBookmark no-ops on a missing collection, so getting this order
            // wrong yields a "successful" import of nothing.
            val provider = LegacyProvider()

            ImportService.importBookmarks(bookmarks("https://a.test/" to "Fresh"), provider)

            assertEquals(listOf("Fresh"), provider.createdCollections)
            assertEquals(1, provider.singleAdds.size)
        }

    @Test
    fun `an existing collection is reused rather than duplicated`() =
        runTest {
            // createCollection appends unconditionally, so importing into a folder
            // that already exists must not create a second one with the same name.
            val provider = LegacyProvider()
            provider.createCollection("Work")
            provider.createdCollections.clear()

            ImportService.importBookmarks(bookmarks("https://a.test/" to "Work"), provider)

            assertTrue(provider.createdCollections.isEmpty(), "created a duplicate collection")
            assertEquals(1, provider.state.value.count { it.name == "Work" })
        }

    @Test
    fun `folderless bookmarks land in the Imported collection`() =
        runTest {
            val provider = BulkProvider()

            ImportService.importBookmarks(bookmarks("https://a.test/" to null), provider)

            assertEquals(ImportService.DEFAULT_COLLECTION, provider.bulkAdds.single().first)
        }

    @Test
    fun `bookmarks in one batch get distinct ids`() =
        runTest {
            // Every imported entry must have its own id, even when inserted in one batch.
            val provider = BulkProvider()
            val input = (1..200).map { ImportedBookmark("Site $it", "https://site$it.test/", "Work") }

            ImportService.importBookmarks(input, provider)

            val ids =
                provider.bulkAdds
                    .single()
                    .second
                    .map { it.id }
            assertEquals(ids.size, ids.toSet().size, "duplicate bookmark ids in a single batch")
        }

    @Test
    fun `progress reports the tally as it goes, so cancelling can read it`() =
        runTest {
            // The bug this guards: importBookmarks runs inside withContext, which
            // discards its return value when the job is cancelled — so a result
            // that is only *returned* is lost, and the dialog told the user
            // nothing had been written after hundreds of rows had been.
            val provider = BulkProvider()
            val input =
                bookmarks(
                    "https://a.test/" to "Work",
                    "https://b.test/" to "Personal",
                    "https://c.test/" to "Reading",
                )

            val seen = mutableListOf<Int>()
            ImportService.importBookmarks(input, provider) { _, _, soFar -> seen.add(soFar.imported) }

            assertTrue(seen.isNotEmpty(), "progress never reported a tally")
            assertEquals(input.size, seen.last(), "the final progress tally must match what landed")
            // Monotonic: a partial read must never overstate what was written.
            assertEquals(seen.sorted(), seen)
        }

    @Test
    fun `the collection is ensured on the bulk path too`() =
        runTest {
            // The bulk branch used to assume the plugin does get-or-create. A
            // provider that no-ops on a missing collection would make this a
            // "successful" import of nothing.
            val provider = BulkProvider()

            ImportService.importBookmarks(bookmarks("https://a.test/" to "Fresh"), provider)

            assertTrue(
                provider.createdCollections.contains("Fresh"),
                "bulk path did not ensure the collection existed",
            )
        }

    @Test
    fun `a missing provider degrades instead of throwing`() =
        runTest {
            val result = ImportService.importBookmarks(bookmarks("https://a.test/" to null), provider = null)

            assertEquals(0, result.imported)
            assertEquals(1, result.failed)
        }

    // ==================== File sniffing ====================

    private fun write(
        name: String,
        content: String,
    ): String = File(tempDir, name).apply { writeText(content) }.absolutePath

    @Test
    fun `reads a Chrome password export`() {
        val path =
            write(
                "passwords.csv",
                """
                name,url,username,password,note
                Example,https://example.test/,alice,s3cret,
                Acme,https://acme.test/,bob,hunter2,a note
                """.trimIndent(),
            )

        val preview = ImportFileReader.parseFile(path).getOrThrow()

        assertEquals(2, preview.passwords.size)
        assertEquals("alice", preview.passwords.first().username)
        assertTrue(preview.bookmarks.isEmpty())
    }

    @Test
    fun `rows with a blank password or username are skipped, not sent to the RPC`() {
        // CreateSecretRequest.validate() rejects blank fields; passkey-only
        // rows legitimately have no password.
        val path =
            write(
                "passwords.csv",
                """
                url,username,password
                https://ok.test/,alice,s3cret
                https://nopass.test/,bob,
                https://nouser.test/,,s3cret
                """.trimIndent(),
            )

        val preview = ImportFileReader.parseFile(path).getOrThrow()

        assertEquals(1, preview.passwords.size)
        assertEquals(
            listOf(SkipReason.MISSING_PASSWORD, SkipReason.MISSING_USERNAME),
            preview.skipped.map { it.reason },
        )
    }

    @Test
    fun `skipped rows never carry the password`() {
        val path =
            write(
                "passwords.csv",
                "url,username,password\nhttps://nouser.test/,,s3cret\n",
            )

        val preview = ImportFileReader.parseFile(path).getOrThrow()

        assertFalse(
            preview.skipped
                .single()
                .label
                .contains("s3cret"),
        )
    }

    @Test
    fun `reads a bookmarks HTML export`() {
        val path =
            write(
                "bookmarks.html",
                """
                <!DOCTYPE NETSCAPE-Bookmark-file-1>
                <DL><p>
                    <DT><A HREF="https://example.test/">Example</A>
                </DL><p>
                """.trimIndent(),
            )

        val preview = ImportFileReader.parseFile(path).getOrThrow()

        assertEquals(1, preview.bookmarks.size)
        assertTrue(preview.passwords.isEmpty())
    }

    @Test
    fun `rejects a CSV that is not a password export`() {
        val path = write("contacts.csv", "first name,last name\nAda,Lovelace")

        val error = ImportFileReader.parseFile(path).exceptionOrNull()

        assertTrue(error is UnrecognisedImportFileException, "got ${error?.let { it::class.simpleName }}")
    }

    @Test
    fun `rejects an empty file`() {
        val path = write("empty.csv", "")

        assertTrue(ImportFileReader.parseFile(path).exceptionOrNull() is UnrecognisedImportFileException)
    }

    @Test
    fun `rejects a path that is not a file`() {
        assertTrue(ImportFileReader.parseFile(tempDir.absolutePath).isFailure)
    }
}
