package ai.rever.boss.search

/**
 * Data models for global search results (BOSS Spotlight).
 *
 * Search results span multiple categories: files, tabs, bookmarks, and run configurations.
 * Each result includes a display name, relevance score, and type-specific metadata.
 */
sealed class SearchResult {
    abstract val displayName: String
    abstract val score: Int
    abstract val category: SearchCategory

    /**
     * A file search result.
     *
     * @property name The file name (e.g., "BossApp.kt")
     * @property path The absolute file path
     * @property relativePath The path relative to project root
     * @property score The fuzzy match score (higher is better)
     * @property matchRanges Character ranges that matched the query (for highlighting)
     */
    data class FileResult(
        val name: String,
        val path: String,
        val relativePath: String,
        override val score: Int,
        val matchRanges: List<MatchRange>
    ) : SearchResult() {
        override val displayName: String = name
        override val category: SearchCategory = SearchCategory.FILES
    }

    /**
     * An open tab search result.
     *
     * @property title The tab title
     * @property tabId The unique tab ID
     * @property workspaceName The workspace containing this tab
     * @property windowId The window containing this tab
     * @property panelId The panel containing this tab
     * @property tabType The type of tab (browser, editor, terminal)
     * @property url For browser tabs, the URL
     * @property filePath For editor tabs, the file path
     * @property score The fuzzy match score
     * @property matchRanges Character ranges that matched the query
     */
    data class TabResult(
        val title: String,
        val tabId: String,
        val workspaceName: String,
        val windowId: String,
        val panelId: String,
        val tabType: String,
        val url: String? = null,
        val filePath: String? = null,
        override val score: Int,
        val matchRanges: List<MatchRange>
    ) : SearchResult() {
        override val displayName: String = title
        override val category: SearchCategory = SearchCategory.TABS
    }

    /**
     * A bookmark search result.
     *
     * @property title The bookmark title
     * @property bookmarkId The unique bookmark ID
     * @property collectionId The collection containing this bookmark
     * @property collectionName The collection name
     * @property tabType The type of bookmarked tab
     * @property url For browser bookmarks, the URL
     * @property filePath For file bookmarks, the file path
     * @property score The fuzzy match score
     * @property matchRanges Character ranges that matched the query
     */
    data class BookmarkResult(
        val title: String,
        val bookmarkId: String,
        val collectionId: String,
        val collectionName: String,
        val tabType: String,
        val url: String? = null,
        val filePath: String? = null,
        override val score: Int,
        val matchRanges: List<MatchRange>
    ) : SearchResult() {
        override val displayName: String = title
        override val category: SearchCategory = SearchCategory.BOOKMARKS
    }

    /**
     * A run configuration search result.
     *
     * @property name The configuration name
     * @property configId The unique configuration ID
     * @property language The programming language
     * @property filePath The file path of the entry point
     * @property configType The type of configuration (MAIN_FUNCTION, SCRIPT, etc.)
     * @property score The fuzzy match score
     * @property matchRanges Character ranges that matched the query
     */
    data class RunConfigResult(
        val name: String,
        val configId: String,
        val language: String,
        val filePath: String,
        val configType: String,
        override val score: Int,
        val matchRanges: List<MatchRange>
    ) : SearchResult() {
        override val displayName: String = name
        override val category: SearchCategory = SearchCategory.RUN_CONFIGS
    }

    /**
     * A command/action search result (for future command palette feature).
     *
     * @property actionId The KeymapActions action ID
     * @property description Human-readable description of the command
     * @property shortcut The keyboard shortcut (e.g., "Cmd+N"), if any
     * @property score The fuzzy match score
     */
    data class CommandResult(
        val actionId: String,
        val description: String,
        val shortcut: String?,
        override val score: Int
    ) : SearchResult() {
        override val displayName: String = description
        override val category: SearchCategory = SearchCategory.COMMANDS
    }
}

/**
 * Categories of search results for filtering.
 */
enum class SearchCategory(val displayName: String, val icon: String) {
    ALL("All", "apps"),
    TABS("Open Tabs", "tab"),
    FILES("Files", "description"),
    BOOKMARKS("Bookmarks", "bookmark"),
    RUN_CONFIGS("Run Configs", "play_arrow"),
    COMMANDS("Commands", "terminal")
}

/**
 * Represents a range of characters that matched the search query.
 * Used for highlighting matched portions in the UI.
 *
 * @property start The start index (inclusive)
 * @property end The end index (exclusive)
 */
data class MatchRange(val start: Int, val end: Int)

/**
 * An indexed file entry for fast searching.
 *
 * @property name The file name
 * @property path The absolute file path
 * @property relativePath The path relative to project root
 * @property lowerName The lowercase file name for case-insensitive matching
 */
data class IndexedFile(
    val name: String,
    val path: String,
    val relativePath: String,
    val lowerName: String = name.lowercase()
)
