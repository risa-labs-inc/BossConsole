package ai.rever.boss.plugin.bookmark

import ai.rever.boss.plugin.workspace.TabConfig
import kotlinx.coroutines.flow.StateFlow

/** Optional durable library capability. Older providers remain readable through their existing API. */
interface BookmarkLibraryProvider {
    val state: StateFlow<BookmarkLibraryState>

    suspend fun saveBookmark(request: BookmarkSaveRequest): BookmarkMutationResult

    suspend fun setFavorite(
        bookmarkId: String,
        favorite: Boolean,
        expectedRevision: Long,
    ): BookmarkMutationResult

    suspend fun moveBookmark(
        bookmarkId: String,
        collectionId: String,
        expectedRevision: Long,
    ): BookmarkMutationResult

    suspend fun deleteBookmark(
        bookmarkId: String,
        expectedRevision: Long,
    ): BookmarkMutationResult

    suspend fun undo(
        token: String,
        expectedRevision: Long,
    ): BookmarkMutationResult

    suspend fun createCollection(
        name: String,
        expectedRevision: Long,
    ): BookmarkMutationResult

    suspend fun renameCollection(
        collectionId: String,
        name: String,
        expectedRevision: Long,
    ): BookmarkMutationResult

    suspend fun deleteCollection(
        collectionId: String,
        moveToCollectionId: String?,
        expectedRevision: Long,
    ): BookmarkMutationResult

    suspend fun reload()
}

data class BookmarkLibraryState(
    val collections: List<BookmarkCollection> = emptyList(),
    val favoriteBookmarkIds: Set<String> = emptySet(),
    val ready: Boolean = false,
    val error: String? = null,
    val revision: Long = 0,
    val defaultFavorite: Boolean = true,
    /** Storage containers shown as root bookmarks rather than user-created folders. */
    val unfiledCollectionIds: Set<String> = emptySet(),
)

data class BookmarkSaveRequest(
    val collectionId: String,
    val tabConfig: TabConfig,
    val name: String,
    val favorite: Boolean,
    val expectedRevision: Long,
    val bookmarkId: String? = null,
    val allowCopy: Boolean = false,
    val workspaceName: String = "",
)

data class BookmarkMutationResult(
    val success: Boolean,
    val bookmarkId: String? = null,
    val collectionId: String? = null,
    val duplicateBookmarkId: String? = null,
    val undoToken: String? = null,
    val message: String? = null,
)

/** One host opening route for the sidebar, library and search. No saved command is executed on reuse. */
interface BookmarkOpeningProvider {
    suspend fun openBookmark(
        bookmark: Bookmark,
        windowId: String,
        panelId: String?,
        forceNewTab: Boolean,
    ): BookmarkOpenResult
}

data class BookmarkOpenResult(
    val success: Boolean,
    val message: String? = null,
    val tabId: String? = null,
    val reused: Boolean = false,
)
