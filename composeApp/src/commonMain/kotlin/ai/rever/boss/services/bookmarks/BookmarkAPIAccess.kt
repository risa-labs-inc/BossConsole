package ai.rever.boss.services.bookmarks

import ai.rever.boss.plugin.api.BookmarkDataProvider
import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.bookmark.BookmarkCollection
import ai.rever.boss.plugin.bookmark.FavoriteWorkspace
import ai.rever.boss.plugin.workspace.TabConfig
import ai.rever.boss.components.plugin.DefaultPlugin
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState

private val logger = BossLogger.forComponent("BookmarkAPIAccess")

/**
 * Provides access to BookmarkDataProvider from the plugin system.
 *
 * This is the bridge between BossConsole UI and the bookmarks plugin.
 * When the bookmarks plugin is installed, getPluginAPI(BookmarkDataProvider::class.java)
 * returns the provider. When uninstalled, it returns null and bookmark features
 * gracefully degrade (context menus hide bookmark options, etc.).
 *
 * Usage in Compose:
 * ```kotlin
 * val bookmarkAPI = BookmarkAPIAccess.getProvider(defaultPlugin)
 * if (bookmarkAPI != null) {
 *     // Show bookmark features
 * }
 * ```
 */
object BookmarkAPIAccess {
    // Cache for the default plugin reference
    private var cachedDefaultPlugin: DefaultPlugin? = null

    /**
     * Set the DefaultPlugin reference for API access.
     * Call this once from BossApp when creating the DefaultPlugin.
     */
    fun initialize(defaultPlugin: DefaultPlugin) {
        cachedDefaultPlugin = defaultPlugin
        logger.debug(LogCategory.SYSTEM, "BookmarkAPIAccess initialized")
    }

    /**
     * Get the BookmarkDataProvider from the plugin system.
     *
     * @return The provider if the bookmarks plugin is installed, null otherwise
     */
    fun getProvider(): BookmarkDataProvider? {
        val plugin = cachedDefaultPlugin ?: return null
        return plugin.getPluginAPI(BookmarkDataProvider::class.java)
    }

    /**
     * Get the BookmarkDataProvider using the provided DefaultPlugin.
     *
     * @param defaultPlugin The DefaultPlugin to query
     * @return The provider if the bookmarks plugin is installed, null otherwise
     */
    fun getProvider(defaultPlugin: DefaultPlugin): BookmarkDataProvider? {
        return defaultPlugin.getPluginAPI(BookmarkDataProvider::class.java)
    }

    // ==================== Convenience Methods (Graceful Degradation) ====================

    /**
     * Get bookmark collections, or empty list if plugin not available.
     */
    fun getCollections(): List<BookmarkCollection> {
        return getProvider()?.collections?.value ?: emptyList()
    }

    /**
     * Get favorite workspaces, or empty list if plugin not available.
     */
    fun getFavoriteWorkspaces(): List<FavoriteWorkspace> {
        return getProvider()?.favoriteWorkspaces?.value ?: emptyList()
    }

    /**
     * Check if a tab is bookmarked, or false if plugin not available.
     */
    fun isTabBookmarked(tabConfig: TabConfig): Boolean {
        return getProvider()?.isTabBookmarked(tabConfig) ?: false
    }

    /**
     * Find bookmark for tab, or null if plugin not available.
     */
    fun findBookmarkForTab(tabConfig: TabConfig): Pair<String, String>? {
        return getProvider()?.findBookmarkForTab(tabConfig)
    }

    /**
     * Check if workspace is favorited, or false if plugin not available.
     */
    fun isFavorite(workspaceId: String): Boolean {
        return getProvider()?.isFavorite(workspaceId) ?: false
    }

    /**
     * Add bookmark (no-op if plugin not available).
     */
    fun addBookmark(collectionName: String, bookmark: Bookmark) {
        getProvider()?.addBookmark(collectionName, bookmark)
    }

    /**
     * Remove bookmark (no-op if plugin not available).
     */
    fun removeBookmark(collectionId: String, bookmarkId: String) {
        getProvider()?.removeBookmark(collectionId, bookmarkId)
    }

    /**
     * Add favorite workspace (no-op if plugin not available).
     */
    fun addFavoriteWorkspace(workspaceId: String, workspaceName: String) {
        getProvider()?.addFavoriteWorkspace(workspaceId, workspaceName)
    }

    /**
     * Remove favorite workspace (no-op if plugin not available).
     */
    fun removeFavoriteWorkspace(workspaceId: String) {
        getProvider()?.removeFavoriteWorkspace(workspaceId)
    }
}

/**
 * Composable helper to observe bookmark collections reactively.
 *
 * Returns empty list if bookmarks plugin is not installed.
 */
@Composable
fun rememberBookmarkCollections(): List<BookmarkCollection> {
    val provider = BookmarkAPIAccess.getProvider()
    return if (provider != null) {
        provider.collections.collectAsState().value
    } else {
        emptyList()
    }
}
