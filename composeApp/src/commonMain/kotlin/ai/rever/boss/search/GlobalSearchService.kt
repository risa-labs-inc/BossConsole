package ai.rever.boss.search

import ai.rever.boss.keymap.KeymapSettingsManager
import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.keymap.model.formatShortcutLabel
import ai.rever.boss.plugin.api.PluginSearchResult
import ai.rever.boss.plugin.api.SearchResultAction
import ai.rever.boss.run.RunConfigurationManager
import ai.rever.boss.topofmind.TopOfMindStateHolder
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

private val logger = BossLogger.forComponent("GlobalSearchService")

/**
 * Central coordination service for BOSS Spotlight global search functionality.
 *
 * Searches across multiple data sources:
 * - Files in the project directory
 * - Open tabs across all windows
 * - Bookmarks (via registered SearchProviders from plugins)
 * - Run configurations
 * - Plugin-contributed search results
 */
object GlobalSearchService {
    private val fileIndexer = FileIndexer()

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _activeCategory = MutableStateFlow(SearchCategory.ALL)
    val activeCategory: StateFlow<SearchCategory> = _activeCategory.asStateFlow()

    /**
     * The file indexer's isIndexing state.
     */
    val isIndexing: StateFlow<Boolean> = fileIndexer.isIndexing

    /**
     * The currently indexed project path.
     */
    val indexedPath: StateFlow<String?> = fileIndexer.indexedPath

    /**
     * Maximum number of results per category.
     *
     * Set to 15 to balance between showing enough results for discovery
     * while keeping the UI responsive and not overwhelming. With 6 categories,
     * this allows up to 90 results maximum which fits well in the dialog.
     */
    private const val MAX_RESULTS_PER_CATEGORY = 15

    /**
     * Minimum fuzzy match score threshold for results.
     *
     * Set to 1 (very permissive) to show partial matches. Higher values
     * would filter out results where only 1-2 characters match, but users
     * expect to see something even with minimal input. The results are
     * sorted by score anyway, so low-scoring matches appear at the bottom.
     */
    private const val MIN_SCORE = 1

    /**
     * Score a match on a long, prose-y field - an MCP tool's description, a page's URL - or null
     * if it should not count as a hit at all.
     *
     * **A substring, not a subsequence.** [FuzzyMatcher] accepts any in-order subsequence, which is
     * right for a name you are half-recalling and disastrous for a paragraph: "abc" matches almost
     * any description ever written. A score floor does not fix it either - the scorer pays +10 per
     * word-boundary character and +20 for starting at index 0, so scattered initials on prose
     * score well into the sixties. Requiring the field to actually CONTAIN what was typed is the
     * rule that holds, and it is the honest one: you get a description hit when you typed a phrase
     * that is in the description.
     *
     * Ranking still comes from [FuzzyMatcher], so these sort among themselves as everything else
     * does. Worth the strictness because any non-empty category draws a section header: without
     * it, a two-character query sprouted a whole "MCP Tools" section of rows that cannot even be
     * activated.
     */
    private fun proseScore(
        queryLower: String,
        field: String,
    ): Int? {
        // `search()` trims the query for every source, so this needs no trim of its own - it did
        // once, and having only this one trim is what made whitespace change the shape of the
        // results rather than their number.
        val fieldLower = field.lowercase()
        if (queryLower.isEmpty() || !fieldLower.contains(queryLower)) return null
        return FuzzyMatcher.match(queryLower, field, fieldLower)?.score?.takeIf { it >= MIN_SCORE }
    }

    /**
     * Index a project directory for file searching.
     *
     * If switching to a different project, the old index is automatically cleared
     * to prevent memory buildup from multiple indexed projects.
     *
     * @param projectPath The root directory to index
     * @param forceReindex If true, re-index even if already indexed
     */
    suspend fun indexProject(
        projectPath: String,
        forceReindex: Boolean = false,
    ) {
        // Clear old index when switching projects to prevent memory leak
        val currentPath = fileIndexer.indexedPath.value
        if (currentPath != null && currentPath != projectPath) {
            logger.debug(
                LogCategory.FILE,
                "Clearing old index before switching projects",
                mapOf("oldPath" to currentPath, "newPath" to projectPath),
            )
            fileIndexer.clearIndex()
        }
        fileIndexer.indexProject(projectPath, forceReindex)
    }

    /**
     * Set the active search category for filtering.
     */
    fun setActiveCategory(category: SearchCategory) {
        _activeCategory.value = category
    }

    /**
     * Search across all data sources matching the given query.
     *
     * Searches are run in parallel across all categories for better performance
     * with large datasets. Also queries registered search providers from plugins.
     *
     * @param query The search query
     * @return every match from every source, in no particular order. [getFilteredResults] is what
     *   orders them - by category, then score - and is the only order anything draws or arrows
     *   through.
     */
    suspend fun search(
        rawQuery: String,
        windowId: String?,
    ): List<SearchResult> {
        // Trimmed once, here, so all nine sources agree. proseScore trimmed its own needle and the
        // fuzzy sources did not, which made trailing whitespace change the SHAPE of the results
        // rather than just their number: typing "git " and pausing dropped every Tools row, since
        // a space is not a subsequence of `git_status`, while the description hit survived. That is
        // the same asymmetry proseScore's trim was added to avoid, pointing the other way.
        val query = rawQuery.trim()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return emptyList()
        }

        _isSearching.value = true

        try {
            // Read ONCE, and shared by the two sources that need it. Both used to call
            // SearchSources.tools(windowId) from their own async, which did the slot flatten twice
            // per keystroke and, worse, let them see different snapshots: a plugin registering
            // between the two reads yields a Tool row whose signpost was filtered out, or the
            // reverse. searchSettings' KDoc promises the two agree, so they have to read the same
            // list rather than two lists that usually match.
            val windowTools = SearchSources.tools(windowId)

            val results =
                withContext(Dispatchers.Default) {
                    // Run all searches in parallel for better performance
                    coroutineScope {
                        val searchResults =
                            listOf(
                                async { isolated("files") { searchFiles(query) } },
                                async { isolated("tabs") { searchTabs(query) } },
                                // Includes bookmarks from plugin
                                async { isolated("plugins") { searchPluginProviders(query) } },
                                async { isolated("runConfigs") { searchRunConfigs(query) } },
                                async { isolated("commands") { searchCommands(query) } },
                                async { isolated("tools") { searchTools(query, windowTools) } },
                                async { isolated("settings") { searchSettings(query, windowTools) } },
                                async { isolated("mcp") { searchMcpTools(query) } },
                                async { isolated("pages") { searchRecentPages(query) } },
                            ).awaitAll().flatten()

                        // Deliberately NOT sorted here. getFilteredResults is what orders results
                        // for every reader - category first, then score - and sorting again on the
                        // way in only invited the belief that this list is the drawn order. It is
                        // not; it is the unordered union of the sources.
                        searchResults
                    }
                }

            _searchResults.value = results
            return results
        } finally {
            _isSearching.value = false
        }
    }

    /**
     * Run one source, and let it fail alone.
     *
     * `awaitAll` rethrows the first failure and cancels its siblings, so without this a single
     * throwing source took the whole search down - the exception propagating out of `search()`
     * into the dialog's `LaunchedEffect`. [SearchSources] promises the opposite ("a missing source
     * returns no results rather than failing the whole search"), and that was true of an ABSENT
     * source and not of a throwing one.
     *
     * Worth having now rather than later: the fan-out went from five sources to nine, and three of
     * the new ones read plugin-derived state - a window's sidebar items, `visiblePages()`,
     * provider-contributed MCP records - which is exactly the kind of thing that throws while a
     * plugin is unloading.
     *
     * [LinkageError] is caught as well as [Exception], and that is the whole point rather than
     * belt-and-braces: an unloaded plugin class throws `NoClassDefFoundError`, which is an `Error`,
     * so a version of this that caught only `Exception` would have missed precisely the case the
     * paragraph above describes. Broader than `Throwable` is deliberately not caught - an
     * `OutOfMemoryError` is not something a search source should absorb.
     *
     * `CancellationException` is deliberately not caught: a cancelled search is the debounce doing
     * its job, and swallowing it here would break the coroutine contract.
     */
    private inline fun <T> isolated(
        source: String,
        body: () -> List<T>,
    ): List<T> =
        try {
            body()
        } catch (e: CancellationException) {
            throw e
        } catch (e: LinkageError) {
            // The characteristic throw when a plugin's classloader has gone while its objects are
            // still referenced - AGENTS.md names `NoClassDefFoundError from code that is still
            // running` for exactly this. It is an Error, not an Exception, so catching Exception
            // alone left the one case this guard was written for propagating out of awaitAll.
            logger.warn(
                LogCategory.UI,
                "Search source hit an unloaded class; skipping it",
                mapOf("source" to source),
                error = e,
            )
            emptyList()
        } catch (e: Exception) {
            logger.warn(LogCategory.UI, "Search source failed; skipping it", mapOf("source" to source), error = e)
            emptyList()
        }

    /**
     * Get results filtered by the active category, in the order they are drawn.
     *
     * **Category first, score second.** `_searchResults` is sorted by score alone, which is the
     * right order for one category and the wrong one for "All": the dialog draws "All" grouped
     * into sections, walking [SearchCategory] in declaration order, and it numbers rows as it goes.
     * The keyboard indexes into THIS list. While the two orders disagreed, the highlighted row and
     * the row Enter activated were different rows - and every appended category made that easier
     * to hit. Sorting here is what keeps one order for drawing, arrowing and Enter.
     *
     * It is also what puts Tools first rather than a tool competing on score with fifteen files
     * that share a word; see [SearchCategory] for why that order is what it is.
     *
     * A no-op for a single category, where the ordinal is constant and score order survives.
     */
    fun getFilteredResults(): List<SearchResult> {
        val category = _activeCategory.value
        val results = _searchResults.value

        val inCategory =
            if (category == SearchCategory.ALL) {
                results
            } else {
                results.filter { it.category == category }
            }

        return inCategory.sortedWith(
            compareBy<SearchResult> { it.category.ordinal }.thenByDescending { it.score },
        )
    }

    /**
     * Get result counts by category.
     */
    fun getResultCounts(): Map<SearchCategory, Int> {
        val results = _searchResults.value
        return SearchCategory.entries.associateWith { category ->
            if (category == SearchCategory.ALL) {
                results.size
            } else {
                results.count { it.category == category }
            }
        }
    }

    /**
     * Search files using fuzzy matching.
     */
    private fun searchFiles(query: String): List<SearchResult.FileResult> {
        val files = fileIndexer.indexedFiles.value
        if (files.isEmpty()) {
            return emptyList()
        }

        val queryLower = query.lowercase()
        val results = mutableListOf<SearchResult.FileResult>()

        for (file in files) {
            val nameMatch = FuzzyMatcher.match(queryLower, file.name, file.lowerName)

            if (nameMatch != null && nameMatch.score >= MIN_SCORE) {
                results.add(
                    SearchResult.FileResult(
                        name = file.name,
                        path = file.path,
                        relativePath = file.relativePath,
                        score = nameMatch.score + 50,
                        matchRanges = nameMatch.matchRanges,
                    ),
                )
                continue
            }

            val pathMatch = FuzzyMatcher.match(queryLower, file.relativePath, file.relativePath.lowercase())
            if (pathMatch != null && pathMatch.score >= MIN_SCORE) {
                val fileNameStart = file.relativePath.lastIndexOf('/') + 1
                val adjustedRanges =
                    pathMatch.matchRanges
                        .filter { it.start >= fileNameStart || it.end > fileNameStart }
                        .map { range ->
                            MatchRange(
                                start = maxOf(0, range.start - fileNameStart),
                                // Clamp end to file name length to prevent out-of-bounds
                                end = minOf(file.name.length, maxOf(0, range.end - fileNameStart)),
                            )
                        }.filter { it.start < file.name.length && it.end > it.start }

                results.add(
                    SearchResult.FileResult(
                        name = file.name,
                        path = file.path,
                        relativePath = file.relativePath,
                        score = pathMatch.score,
                        matchRanges = adjustedRanges,
                    ),
                )
            }
        }

        return results
            .sortedByDescending { it.score }
            .take(MAX_RESULTS_PER_CATEGORY)
    }

    /**
     * Search open tabs.
     */
    private fun searchTabs(query: String): List<SearchResult.TabResult> {
        val tabs = TopOfMindStateHolder.activeTabs.value
        if (tabs.isEmpty()) {
            return emptyList()
        }

        val queryLower = query.lowercase()
        val results = mutableListOf<SearchResult.TabResult>()

        for (tab in tabs) {
            val title = tab.tabInfo.title
            val titleMatch = FuzzyMatcher.match(queryLower, title, title.lowercase())

            if (titleMatch != null && titleMatch.score >= MIN_SCORE) {
                results.add(
                    SearchResult.TabResult(
                        title = title,
                        tabId = tab.tabInfo.id,
                        workspaceName = tab.workspaceName,
                        windowId = tab.windowId,
                        panelId = tab.panelId,
                        tabType = tab.tabInfo.typeId.typeId,
                        url = null, // Would need FluckTabInfo check
                        filePath = null, // Would need EditorTabInfo check
                        score = titleMatch.score + 30, // Bonus for tabs (currently visible)
                        matchRanges = titleMatch.matchRanges,
                    ),
                )
            }
        }

        return results
            .sortedByDescending { it.score }
            .take(MAX_RESULTS_PER_CATEGORY)
    }

    /**
     * Search registered search providers (includes bookmarks plugin and any other plugins).
     *
     * This queries all SearchProviders registered via PluginContext.registerSearchProvider().
     * The bookmarks plugin registers a BookmarkSearchProvider that contributes bookmark results.
     */
    private suspend fun searchPluginProviders(query: String): List<SearchResult> {
        val providers = SearchRegistryImpl.providers.value
        if (providers.isEmpty()) {
            return emptyList()
        }

        val results = mutableListOf<SearchResult>()

        for (provider in providers) {
            try {
                val providerResults = provider.search(query, MAX_RESULTS_PER_CATEGORY)

                // Convert PluginSearchResult to SearchResult
                for (result in providerResults) {
                    val searchResult = convertPluginSearchResult(result)
                    if (searchResult != null) {
                        results.add(searchResult)
                    }
                }
            } catch (e: Exception) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Search provider failed",
                    mapOf(
                        "providerId" to provider.providerId,
                    ),
                    error = e,
                )
            }
        }

        return results
            .sortedByDescending { it.score }
            .take(MAX_RESULTS_PER_CATEGORY)
    }

    /**
     * Convert a PluginSearchResult to a SearchResult.
     */
    private fun convertPluginSearchResult(result: PluginSearchResult): SearchResult? {
        // Map category string to SearchCategory
        val category =
            when (result.category.lowercase()) {
                "bookmarks" -> SearchCategory.BOOKMARKS
                "files" -> SearchCategory.FILES
                "tabs" -> SearchCategory.TABS
                "run configs", "run_configs" -> SearchCategory.RUN_CONFIGS
                "commands" -> SearchCategory.COMMANDS
                else -> SearchCategory.BOOKMARKS // Default to bookmarks for plugin results
            }

        // Convert match ranges
        val matchRanges = result.matchRanges.map { MatchRange(it.start, it.end) }

        return when (category) {
            SearchCategory.BOOKMARKS -> {
                val url =
                    when (val action = result.action) {
                        is SearchResultAction.OpenUrl -> action.url
                        else -> null
                    }
                val filePath =
                    when (val action = result.action) {
                        is SearchResultAction.OpenFile -> action.path
                        else -> null
                    }

                SearchResult.BookmarkResult(
                    title = result.title,
                    bookmarkId = result.id,
                    collectionId = result.metadata["collectionId"] ?: "",
                    collectionName = result.metadata["collectionName"] ?: result.providerId,
                    tabType = result.metadata["tabType"] ?: "browser",
                    url = url,
                    filePath = filePath,
                    score = result.score,
                    matchRanges = matchRanges,
                )
            }

            else -> {
                null
            } // Other categories handled by dedicated search methods
        }
    }

    /**
     * Search run configurations.
     */
    private fun searchRunConfigs(query: String): List<SearchResult.RunConfigResult> {
        val settings = RunConfigurationManager.currentSettings.value
        val detectedConfigs = RunConfigurationManager.detectedConfigurations.value

        val allConfigs = settings.configurations + detectedConfigs
        if (allConfigs.isEmpty()) {
            return emptyList()
        }

        val queryLower = query.lowercase()
        val results = mutableListOf<SearchResult.RunConfigResult>()

        for (config in allConfigs) {
            val name = config.name
            val nameMatch = FuzzyMatcher.match(queryLower, name, name.lowercase())

            if (nameMatch != null && nameMatch.score >= MIN_SCORE) {
                results.add(
                    SearchResult.RunConfigResult(
                        name = name,
                        configId = config.id,
                        language = config.language.displayName,
                        filePath = config.filePath,
                        configType = config.type.name,
                        score = nameMatch.score,
                        matchRanges = nameMatch.matchRanges,
                    ),
                )
            }
        }

        return results
            .sortedByDescending { it.score }
            .take(MAX_RESULTS_PER_CATEGORY)
    }

    /**
     * Search commands/actions from KeymapActions.
     */
    private fun searchCommands(query: String): List<SearchResult.CommandResult> {
        val allActionIds = KeymapActions.getAllActionIds()
        val settings = KeymapSettingsManager.currentSettings.value
        val queryLower = query.lowercase()
        val results = mutableListOf<SearchResult.CommandResult>()

        for (actionId in allActionIds) {
            val description = KeymapActions.getDescription(actionId)

            // Match against description
            val descriptionMatch = FuzzyMatcher.match(queryLower, description, description.lowercase())

            // Also match against action ID (e.g., "window.new")
            val actionIdMatch = FuzzyMatcher.match(queryLower, actionId, actionId.lowercase())

            val bestMatch = listOfNotNull(descriptionMatch, actionIdMatch).maxByOrNull { it.score }

            if (bestMatch != null && bestMatch.score >= MIN_SCORE) {
                // Get shortcut for this action
                val binding = settings.shortcuts[actionId]
                val shortcut =
                    if (binding != null && binding.enabled) {
                        formatShortcut(binding.modifiers, binding.key)
                    } else {
                        null
                    }

                results.add(
                    SearchResult.CommandResult(
                        actionId = actionId,
                        description = description,
                        shortcut = shortcut,
                        score = bestMatch.score,
                    ),
                )
            }
        }

        return results
            .sortedByDescending { it.score }
            .take(MAX_RESULTS_PER_CATEGORY)
    }

    /**
     * Search the tools - plugin panels - in the active window's sidebar.
     *
     * **Hidden tools included**, deliberately, exactly as `ToolLauncherDialog` argues about its own
     * list: a tool someone hid from a strip is the one they will come here to find, and this is the
     * surface that has to be able to reach anything.
     *
     * Matched on the panel id as well as the label, because the id is what a plugin's own
     * documentation and its MCP tools call it.
     */
    private fun searchTools(
        query: String,
        windowTools: List<ToolSearchRecord>,
    ): List<SearchResult.ToolResult> {
        val queryLower = query.lowercase()

        return windowTools
            .mapNotNull { tool ->
                val labelMatch = FuzzyMatcher.match(queryLower, tool.label, tool.label.lowercase())
                val idMatch = FuzzyMatcher.match(queryLower, tool.panelId, tool.panelId.lowercase())
                val best = listOfNotNull(labelMatch, idMatch).maxByOrNull { it.score }

                best?.takeIf { it.score >= MIN_SCORE }?.let {
                    SearchResult.ToolResult(panelId = tool.panelId, label = tool.label, score = it.score)
                }
            }.sortedByDescending { it.score }
            .take(MAX_RESULTS_PER_CATEGORY)
    }

    /**
     * Search the rows of the Settings window.
     *
     * **Ranked by `SettingsSearchMatcher`, not here.** The desktop side registers a search function
     * rather than a list of rows, so settings relevance has one definition shared with the Settings
     * window's own search box. Scoring them here meant a second scorer, and the two did not merely
     * order things differently - `FuzzyMatcher` is a strict subsequence matcher over one target, so
     * "user agent" could not reach "Browser Identity" here while it did there. Whole settings were
     * missing for exactly the multi-word queries the matcher's tokeniser was written for.
     *
     * **A signpost whose panel is absent is dropped.** A signpost points out of the Settings window
     * at a sidebar panel, and `withoutUnreachableSignposts` states the intended behaviour plainly:
     * someone who has never installed Secret Manager should find nothing for "anthropic", rather
     * than a row that closes the dialog having done nothing. The Settings window applies that
     * filter to its own box; this used not to, so the two surfaces disagreed about the one entry
     * type that can be dead.
     *
     * The predicate is this window's registered tools, which is not merely *a* reachability test
     * but the same one activation uses: `activatePlugin` matches `itemsBySlot` on `panelId`, and
     * [SearchSources.tools] is that list flattened. So the row is offered exactly when picking it
     * would work.
     */
    private fun searchSettings(
        query: String,
        windowTools: List<ToolSearchRecord>,
    ): List<SearchResult.SettingResult> {
        val reachablePanels = windowTools.mapTo(mutableSetOf()) { it.panelId }

        return SearchSources
            .settings(query)
            .filter { it.panelId == null || it.panelId in reachablePanels }
            .map { entry ->
                SearchResult.SettingResult(
                    section = entry.section,
                    pluginPageId = entry.pluginPageId,
                    panelId = entry.panelId,
                    group = entry.group,
                    label = entry.label,
                    breadcrumb = entry.breadcrumb,
                    highlightable = entry.highlightable,
                    score = entry.score,
                )
            }.take(MAX_RESULTS_PER_CATEGORY)
    }

    /**
     * Search the MCP tools plugins have contributed.
     *
     * Read through [SearchSources] rather than off the MCP registry directly, so the RBAC filter that
     * decides what appears here is injectable and therefore testable - see
     * [McpToolSearchRecord.enabled] for what the host is required to have already applied.
     *
     * **Name first, description as a weak fallback.** [FuzzyMatcher] succeeds on any in-order
     * subsequence, so a two- or three-character query matches almost any paragraph-length
     * description. Those scored low and still produced a section header, so short queries grew an
     * "MCP Tools" section of rows that cannot even be activated. A description-only hit now has to
     * contain what was typed - see [proseScore] - while a name hit stays fuzzy, because the name is
     * what someone is actually trying to recall.
     *
     * These results have no activation. See [SearchResult.McpToolResult].
     */
    private fun searchMcpTools(query: String): List<SearchResult.McpToolResult> {
        val queryLower = query.lowercase()

        return SearchSources
            .mcpTools()
            .mapNotNull { tool ->
                val nameScore =
                    FuzzyMatcher.match(queryLower, tool.name, tool.name.lowercase())?.score?.takeIf {
                        it >= MIN_SCORE
                    }
                val descScore = proseScore(queryLower, tool.description)

                listOfNotNull(nameScore, descScore).maxOrNull()?.let { score ->
                    SearchResult.McpToolResult(
                        name = tool.name,
                        providerId = tool.providerId,
                        description = tool.description,
                        enabled = tool.enabled,
                        score = score,
                    )
                }
            }.sortedByDescending { it.score }
            .take(MAX_RESULTS_PER_CATEGORY)
    }

    /**
     * Search the browser's recent pages.
     *
     * Both the title and the URL, because half of what makes a page findable is its domain -
     * "github" should reach a page whose title never mentions it. The URL goes through [proseScore]
     * for the reason [searchMcpTools] gives: a URL is long enough that a fuzzy subsequence matches
     * it by accident, so it has to actually contain what was typed.
     *
     * **The title deliberately stays fuzzy**, on the same side of the line as an MCP tool's name
     * rather than its description. A title is what someone is trying to recall - half-remembered
     * and half-typed, "pullreq" for "Pull requests" - and holding it to a substring would lose
     * exactly the queries the fuzzy matcher is for. It is a weaker version of the argument than
     * the one for names, since a title is longer than a tool name and a short query can therefore
     * still match one by accident; the section that results is at least made of rows that open
     * something, which is what made the MCP case worth guarding and this one not.
     *
     * Read through [SearchSources] so a unit test can supply pages without the manager, and
     * without the disk read that reaching it entails.
     */
    private fun searchRecentPages(query: String): List<SearchResult.PageResult> {
        val queryLower = query.lowercase()

        return SearchSources
            .recentPages()
            .mapNotNull { page ->
                val titleScore =
                    FuzzyMatcher.match(queryLower, page.title, page.title.lowercase())?.score?.takeIf {
                        it >= MIN_SCORE
                    }
                val urlScore = proseScore(queryLower, page.url)

                listOfNotNull(titleScore, urlScore).maxOrNull()?.let { score ->
                    SearchResult.PageResult(url = page.url, title = page.title, score = score)
                }
            }.sortedByDescending { it.score }
            .take(MAX_RESULTS_PER_CATEGORY)
    }

    /**
     * Format a keyboard shortcut for display.
     */
    private fun formatShortcut(
        modifiers: List<String>,
        key: String,
    ): String = formatShortcutLabel(modifiers, key)

    /**
     * Clear search results.
     */
    fun clearResults() {
        _searchResults.value = emptyList()
    }

    /**
     * Clear the file index.
     */
    fun clearIndex() {
        fileIndexer.clearIndex()
        _searchResults.value = emptyList()
    }

    /**
     * Get the count of indexed files.
     */
    fun getIndexedFileCount(): Int = fileIndexer.getFileCount()
}
