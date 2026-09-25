package ai.rever.boss.plugin.bookmark

import ai.rever.boss.plugin.workspace.TabConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the id-minting fix on the bookmark side. `generateId` used to be a bare
 * `Clock.System.now()` read, so a bulk import - or any two creations inside one clock
 * millisecond - minted the same id for two different bookmarks. The id is the entity's
 * identity: findBookmark, updateBookmark and removeBookmark all key on it, so the second
 * bookmark made the first unaddressable, and persisted storage keyed by id overwrote it.
 */
class BookmarkIdCollisionTest {
    private fun bookmark() =
        Bookmark(
            tabConfig = TabConfig(type = "browser", title = "Example", url = "https://example.com"),
            workspaceName = "workspace",
        )

    @Test
    fun `ten thousand bookmark mints are all distinct`() {
        // The loop runs far faster than the clock ticks, so nearly every mint shares a
        // millisecond with its neighbours - the old timestamp-only id fails on iteration one.
        val ids = List(10_000) { Bookmark.generateId() }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `ten thousand collection mints are all distinct`() {
        val ids = List(10_000) { BookmarkCollection.generateId() }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `two generated bookmarks persist as two entries`() {
        val collection = BookmarkCollection(name = "Bulk")
        val updated = collection.addBookmark(bookmark()).addBookmark(bookmark())
        assertEquals(2, updated.bookmarks.size)
        assertEquals(
            2,
            updated.bookmarks
                .map { it.id }
                .toSet()
                .size,
        )
    }

    @Test
    fun `add bookmark preserves a caller supplied duplicate id`() {
        val first = bookmark()
        val collection = BookmarkCollection(name = "Bulk", bookmarks = listOf(first))
        // The colliding shape still arrives - a caller-supplied id, a stale file, an import.
        val updated = collection.addBookmark(first.copy())
        assertEquals(2, updated.bookmarks.size)
        val ids = updated.bookmarks.map { it.id }
        assertEquals(listOf(first.id, first.id), ids)
    }
}
