package ai.rever.boss.services.bookmarks

import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.window_panel.SplitViewStateRegistry
import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.bookmark.BookmarkCollection
import ai.rever.boss.plugin.bookmark.BookmarkOpenResult
import ai.rever.boss.plugin.bookmark.BookmarkOpeningProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared by host UI and the optional plugin-facing opening contract. Callers display failures. */
internal class HostBookmarkOpeningProvider(
    private val service: BookmarkOpeningService = BookmarkOpeningService.shared,
    private val resolveWindow: (String) -> SplitViewState? = SplitViewStateRegistry::getState,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : BookmarkOpeningProvider {
    companion object {
        val shared = HostBookmarkOpeningProvider()
    }

    override suspend fun openBookmark(
        bookmark: Bookmark,
        windowId: String,
        panelId: String?,
        forceNewTab: Boolean,
    ): BookmarkOpenResult =
        withContext(uiDispatcher) {
            val window =
                resolveWindow(windowId)
                    ?: return@withContext BookmarkOpenResult(false, "The requested window is no longer open.")
            service.open(window, bookmark, panelId, forceNewTab) { resolveWindow(windowId) === window }
        }
}

internal data class BookmarkSearchRequest(
    val bookmarkId: String,
    val collectionId: String,
    val windowId: String,
    val panelId: String,
)

/** Resolve current data at activation, then use precisely the same route as the sidebar/library. */
internal suspend fun openBookmarkFromSearch(
    request: BookmarkSearchRequest,
    collections: List<BookmarkCollection> = BookmarkAPIAccess.getCollections(),
    opener: BookmarkOpeningProvider = HostBookmarkOpeningProvider.shared,
): BookmarkOpenResult {
    val collection = collections.firstOrNull { it.id == request.collectionId }
    val bookmark =
        collection?.bookmarks?.firstOrNull { it.id == request.bookmarkId }
            ?: return BookmarkOpenResult(
                false,
                "This bookmark is no longer available. Refresh Search or open All Bookmarks.",
            )
    return opener.openBookmark(bookmark, request.windowId, request.panelId, forceNewTab = false)
}
