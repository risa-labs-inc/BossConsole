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
        val matchRanges: List<MatchRange>,
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
        val matchRanges: List<MatchRange>,
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
        val matchRanges: List<MatchRange>,
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
        val matchRanges: List<MatchRange>,
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
        override val score: Int,
    ) : SearchResult() {
        override val displayName: String = description
        override val category: SearchCategory = SearchCategory.COMMANDS
    }

    /**
     * A tool - a plugin's sidebar panel - reachable without hunting for its icon.
     *
     * @property panelId The panel's id, which is what activates it. NOT the plugin id: the two
     *   differ for several plugins, and `activatePlugin` matches on this one.
     * @property label The tool's own name, as its sidebar icon and the tools launcher show it
     * @property score The fuzzy match score
     */
    data class ToolResult(
        val panelId: String,
        val label: String,
        override val score: Int,
    ) : SearchResult() {
        override val displayName: String = label
        override val category: SearchCategory = SearchCategory.TOOLS
    }

    /**
     * A row in the Settings window.
     *
     * @property section The settings section to navigate to, or null for a plugin page
     * @property pluginPageId Set instead of [section] for a plugin's own settings page
     * @property panelId Set instead of both for a row that is not in the Settings window at all:
     *   picking it opens that sidebar panel, the way a `ToolResult` does. The Settings index calls
     *   these signposts - a setting that moved out, keeping the words a user still types for it.
     * @property group The group within the section, for the highlight
     * @property label The setting's label, which is also what the highlight matches on
     * @property breadcrumb Where it lives, e.g. "Appearance > Tab Bar", shown as the subtitle
     * @property highlightable False when landing on the section is all this entry can do
     * @property score The fuzzy match score
     */
    data class SettingResult(
        val section: String?,
        val pluginPageId: String?,
        val panelId: String?,
        val group: String?,
        val label: String,
        val breadcrumb: String,
        val highlightable: Boolean,
        override val score: Int,
    ) : SearchResult() {
        override val displayName: String = label
        override val category: SearchCategory = SearchCategory.SETTINGS
    }

    /**
     * An MCP tool, indexed so its existence and name can be found - and nothing else.
     *
     * There is deliberately no activation for this one. An MCP tool takes arguments a search row
     * cannot collect, so selecting it closes the dialog, which is what [GlobalSearchDialog] already
     * does for a result whose handler is absent. The question this answers is "is there a tool for
     * this, and what is it called", which is why [enabled] and [providerId] are carried: a disabled
     * tool is precisely the one someone is looking for.
     *
     * @property name The tool's name, e.g. `git_status`. Clients see it as `mcp__boss__git_status`
     * @property providerId The plugin that contributes it
     * @property description The tool's own description, matched on as well as the name
     * @property enabled Whether it is currently exposed to clients
     * @property score The fuzzy match score
     */
    data class McpToolResult(
        val name: String,
        val providerId: String,
        val description: String,
        val enabled: Boolean,
        override val score: Int,
    ) : SearchResult() {
        override val displayName: String = name
        override val category: SearchCategory = SearchCategory.MCP
    }

    /**
     * A page from the browser's recent history.
     *
     * @property url The page's URL, which is what opens, and is matched on as well as the title
     * @property title The page title
     * @property score The fuzzy match score
     */
    data class PageResult(
        val url: String,
        val title: String,
        override val score: Int,
    ) : SearchResult() {
        override val displayName: String = title.ifBlank { url }
        override val category: SearchCategory = SearchCategory.PAGES
    }
}

/**
 * Categories of search results for filtering.
 *
 * **Declaration order is display order**, in three places that have to agree: the chip row, the
 * section order in the "All" view, and - because `SearchResultsList` numbers rows by walking these
 * entries - which row the keyboard starts on. `getFilteredResults` sorts by this ordinal for that
 * reason, so reordering here moves all three together and never desynchronises them.
 *
 * [TOOLS] then [SETTINGS] lead because they are the destinations: a tool or a settings row is a
 * thing you open and use, where a file match is a thing you then have to find something in. They
 * also lose the most from being ranked by score alone, because the category ordering is absolute -
 * any file match outranks every settings match, however good, and selection starts at index 0, so
 * whatever leads is what Enter opens. "atlas" put one tool under fifteen files that shared the
 * word; "dark theme" in a repo holding a `DarkTheme.kt` did the same to the settings row that
 * actually changes the theme.
 *
 * The absoluteness is the trade. A per-category score bonus would let an exceptional file match
 * beat a mediocre settings one, which is arguably better ranking and definitely worse to predict -
 * and predictability is what a launcher is for. Ordering stays absolute; the order itself is the
 * knob.
 *
 * [MCP] and [PAGES] sit last for the opposite reason: an MCP row cannot be activated at all, and a
 * recent page is the weakest kind of intent.
 */
enum class SearchCategory(
    val displayName: String,
    val icon: String,
) {
    ALL("All", "apps"),
    TOOLS("Tools", "apps"),
    SETTINGS("Settings", "settings"),
    TABS("Open Tabs", "tab"),
    FILES("Files", "description"),
    BOOKMARKS("Bookmarks", "bookmark"),
    RUN_CONFIGS("Run Configs", "play_arrow"),
    COMMANDS("Commands", "terminal"),
    MCP("MCP Tools", "build"),
    PAGES("Recent Pages", "history"),
}

/**
 * Represents a range of characters that matched the search query.
 * Used for highlighting matched portions in the UI.
 *
 * @property start The start index (inclusive)
 * @property end The end index (exclusive)
 */
data class MatchRange(
    val start: Int,
    val end: Int,
)

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
    val lowerName: String = name.lowercase(),
)
