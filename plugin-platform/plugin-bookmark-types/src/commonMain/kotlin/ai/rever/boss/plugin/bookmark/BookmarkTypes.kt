package ai.rever.boss.plugin.bookmark

import ai.rever.boss.plugin.workspace.TabConfig
import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/**
 * Represents a target workspace and panel for opening a bookmark.
 *
 * @property workspaceName The name of the target workspace
 * @property panelId The target panel ID within the workspace (null = use active panel)
 */
@Immutable
@Serializable
data class WorkspacePanelTarget(
    val workspaceName: String,
    val panelId: String? = null
)

/**
 * Represents a single bookmark (a saved tab).
 *
 * A bookmark is essentially a TabConfig with additional metadata.
 * When clicked, it opens the tab in the specified workspaces and panels,
 * or in the current workspace's active panel if not specified.
 *
 * @property id Unique identifier for the bookmark
 * @property tabConfig The tab configuration to open
 * @property workspaceName Which workspace this bookmark originated from
 * @property targetWorkspaceName Legacy: Target workspace to open tab in
 * @property targetPanelId Legacy: Target panel ID within workspace
 * @property targetWorkspaces Target workspaces and panels (empty = use current)
 * @property notes Optional user notes about the bookmark
 * @property tags Tags for filtering/organization
 * @property createdAt Creation timestamp
 * @property lastAccessedAt Updated when bookmark is clicked
 */
@Immutable
@Serializable
data class Bookmark(
    val id: String = generateId(),
    val tabConfig: TabConfig,
    val workspaceName: String,
    @Deprecated("Use targetWorkspaces instead")
    val targetWorkspaceName: String? = null,
    @Deprecated("Use targetWorkspaces instead")
    val targetPanelId: String? = null,
    val targetWorkspaces: List<WorkspacePanelTarget> = emptyList(),
    val notes: String = "",
    val tags: List<String> = emptyList(),
    val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
    val lastAccessedAt: Long = 0L
) {
    companion object {
        /**
         * Generate a unique bookmark ID based on current timestamp.
         */
        fun generateId(): String = "bookmark-${Clock.System.now().toEpochMilliseconds()}"
    }

    /**
     * Update last accessed time.
     */
    fun markAsAccessed(): Bookmark {
        return copy(lastAccessedAt = Clock.System.now().toEpochMilliseconds())
    }
}

/**
 * Represents a collection of bookmarks.
 *
 * Collections allow users to organize bookmarks into groups.
 * The special "Favorites" collection is automatically created.
 *
 * Examples:
 * - "Favorites" (special, isFavorite = true)
 * - "Work"
 * - "Research"
 * - "Daily Sites"
 *
 * @property id Unique identifier for the collection
 * @property name Display name
 * @property bookmarks List of bookmarks in this collection
 * @property isFavorite Is this the special "Favorites" collection?
 * @property createdAt Creation timestamp
 */
@Immutable
@Serializable
data class BookmarkCollection(
    val id: String = generateId(),
    val name: String,
    val bookmarks: List<Bookmark> = emptyList(),
    val isFavorite: Boolean = false,
    val createdAt: Long = Clock.System.now().toEpochMilliseconds()
) {
    companion object {
        /**
         * Generate a unique collection ID based on current timestamp.
         */
        fun generateId(): String = "collection-${Clock.System.now().toEpochMilliseconds()}"

        /**
         * Special collection name for favorites.
         */
        const val FAVORITES_NAME = "Favorites"
    }

    /**
     * Add a bookmark to this collection.
     */
    fun addBookmark(bookmark: Bookmark): BookmarkCollection {
        return copy(bookmarks = bookmarks + bookmark)
    }

    /**
     * Remove a bookmark from this collection.
     */
    fun removeBookmark(bookmarkId: String): BookmarkCollection {
        return copy(bookmarks = bookmarks.filter { it.id != bookmarkId })
    }

    /**
     * Update a bookmark in this collection.
     */
    fun updateBookmark(bookmark: Bookmark): BookmarkCollection {
        return copy(bookmarks = bookmarks.map {
            if (it.id == bookmark.id) bookmark else it
        })
    }

    /**
     * Find a bookmark by ID.
     */
    fun findBookmark(bookmarkId: String): Bookmark? {
        return bookmarks.find { it.id == bookmarkId }
    }
}

/**
 * Marks a workspace as favorite.
 *
 * Favorite workspaces appear in the "Favorite Workspaces" section
 * of the bookmarks sidebar for quick access.
 *
 * @property workspaceId The workspace's unique identifier
 * @property workspaceName The workspace's display name
 * @property markedAt When the workspace was marked as favorite
 */
@Immutable
@Serializable
data class FavoriteWorkspace(
    val workspaceId: String,
    val workspaceName: String,
    val markedAt: Long = Clock.System.now().toEpochMilliseconds()
) {
    companion object {
        /**
         * Create a new favorite workspace entry.
         */
        fun create(workspaceId: String, workspaceName: String): FavoriteWorkspace {
            return FavoriteWorkspace(
                workspaceId = workspaceId,
                workspaceName = workspaceName,
                markedAt = Clock.System.now().toEpochMilliseconds()
            )
        }
    }
}
