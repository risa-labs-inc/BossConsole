package ai.rever.boss.components.dialogs

import ai.rever.boss.components.workspaces.WorkspaceManager
import ai.rever.boss.icons.FileIcons
import ai.rever.boss.mcp.McpToolRegistryImpl
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeController
import ai.rever.boss.search.GlobalSearchService
import ai.rever.boss.search.MatchRange
import ai.rever.boss.search.SearchCategory
import ai.rever.boss.search.SearchResult
import ai.rever.boss.utils.extractParentName
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.LogSanitizer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val globalSearchLogger = BossLogger.forComponent("GlobalSearchDialog")

// Theme colors — reactive getters into the BOSS design system tokens
// (getters, not cached vals, so theme switches re-skin the dialog).
private val SelectionAccent get() = BossThemeController.current.colors.signal // signal — selection / primary
private val TabsAccent get() = BossThemeController.current.colors.ok // ok — tabs
private val BookmarksAccent get() = BossThemeController.current.colors.warn // warn — bookmarks

// Deliberate one-off: the design system has no purple token (run-config identity color).
private val RunConfigAccent = Color(0xFF9C27B0)
private val CommandsAccent get() = BossThemeController.current.colors.data // data — commands
private val ToolsAccent get() = BossThemeController.current.colors.signal // signal — tools

// Deliberately the quiet one - a settings row is a destination, not a signal - but textSecondary
// rather than textMuted, which is exactly SimpleResultItem's subtitle tone and made the icon read
// as a disabled row beside eight saturated accents.
private val SettingsAccent get() = BossThemeController.current.colors.textSecondary
private val McpAccent get() = BossThemeController.current.colors.data // data — MCP tools, with commands
private val PagesAccent get() = BossThemeController.current.colors.ok // ok — recent pages, with tabs

// Files take the dimmer half of the signal pair so they do not read as Tools, which leads the
// results and holds `signal` proper.
private val FilesAccent get() = BossThemeController.current.colors.signalDim
private val HoverBackground get() = BossThemeController.current.colors.raised
private val CardShape = RoundedCornerShape(12.dp)
private val SmallCardShape = RoundedCornerShape(8.dp)
private val SectionTitleColor get() = BossThemeController.current.colors.textMuted

/**
 * Tiles per row in the empty state.
 *
 * A count, deliberately not a statement about how many rows that makes: the tiles are derived from
 * [SearchCategory] precisely so adding a category needs no edit here, and a comment naming today's
 * total would be the one thing that still went stale.
 */
private const val EMPTY_STATE_TILES_PER_ROW = 5

/**
 * Width of an empty-state tile.
 *
 * Fixed, so the row is a grid rather than ten columns each as wide as its own label: the names come
 * from [SearchCategory.displayName] and run from "Files" to "Recent Pages", which laid out
 * unconstrained gives visibly uneven gaps. Wide enough for two lines of the longest name.
 */
private val EmptyStateTileWidth = 76.dp

/**
 * Ceiling for a result row's trailing chip, so a long plugin id cannot squeeze the title.
 *
 * PascalCase like every other `Dp` val in this file, not SCREAMING_SNAKE_CASE - `.editorconfig`
 * disables ktlint's property-naming rule, so nothing enforces it.
 */
private val TrailingChipMaxWidth = 140.dp

/**
 * Global search dialog for BOSS Spotlight - quickly find files, tabs, bookmarks, and run configs.
 *
 * Accessible via Cmd+Shift+P keyboard shortcut or search button in top bar.
 * Provides fuzzy matching with keyboard navigation across multiple data sources.
 * UI inspired by macOS Spotlight and the BOSS Dashboard design.
 *
 * @param projectPath The project directory to search in
 * @param onDismiss Called when dialog should close
 * @param onFileSelect Called when a file is selected, with the file's absolute path
 * @param onTabSelect Called when an open tab is selected, with windowId, panelId, and tabId
 * @param onBookmarkSelect Called when a bookmark is selected, with the bookmark config
 * @param onRunConfigSelect Called when a run config is selected, with the config ID
 */
@Composable
fun GlobalSearchDialog(
    projectPath: String,
    workspaceManager: WorkspaceManager,
    windowId: String,
    onDismiss: () -> Unit,
    onFileSelect: (String) -> Unit,
    onTabSelect: ((windowId: String, panelId: String, tabId: String) -> Unit)? = null,
    onBookmarkSelect: ((bookmarkId: String, collectionId: String) -> Unit)? = null,
    onRunConfigSelect: ((configId: String) -> Unit)? = null,
    onCommandSelect: ((actionId: String) -> Unit)? = null,
    onToolSelect: ((panelId: String) -> Unit)? = null,
    onSettingSelect: ((result: SearchResult.SettingResult) -> Unit)? = null,
    onPageSelect: ((url: String) -> Unit)? = null,
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedIndex by remember { mutableStateOf(0) }
    // Track if selection was changed by keyboard (to enable scroll) vs hover (no scroll)
    var scrollToSelected by remember { mutableStateOf(false) }
    val allResults by GlobalSearchService.searchResults.collectAsState()
    val activeCategory by GlobalSearchService.activeCategory.collectAsState()
    val isIndexing by GlobalSearchService.isIndexing.collectAsState()
    val isSearching by GlobalSearchService.isSearching.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val searchFieldFocusRequester = remember { FocusRequester() }

    // Get filtered results based on active category
    val filteredResults =
        remember(allResults, activeCategory) {
            GlobalSearchService.getFilteredResults()
        }

    // Get result counts by category
    val resultCounts =
        remember(allResults) {
            GlobalSearchService.getResultCounts()
        }

    // Animation state for staggered appearance
    var showContent by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(50)
        showContent = true
        delay(100)
        searchFieldFocusRequester.requestFocus()
    }

    // Refresh active tabs when dialog opens (collect from all windows)
    LaunchedEffect(Unit) {
        TabCollector.refreshGlobalState(workspaceManager)
    }

    // Index project when dialog opens
    LaunchedEffect(projectPath) {
        if (projectPath.isBlank()) {
            globalSearchLogger.warn(LogCategory.UI, "Empty project path, skipping file indexing")
            return@LaunchedEffect
        }
        globalSearchLogger.debug(LogCategory.UI, "Indexing project for search", mapOf("path" to projectPath))
        GlobalSearchService.indexProject(projectPath)
    }

    // Debounced search as user types
    // 50ms debounce balances responsiveness with avoiding excessive searches while typing fast
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            GlobalSearchService.clearResults()
            return@LaunchedEffect
        }
        delay(50)
        // This window, so the Tools rows come from the sidebar this dialog can actually open, and
        // so a signpost is offered only when its panel is present here - see SearchSources.
        GlobalSearchService.search(searchQuery, windowId)
    }

    // Auto-scroll to selected item (only when triggered by keyboard)
    LaunchedEffect(selectedIndex, scrollToSelected) {
        if (scrollToSelected && filteredResults.isNotEmpty()) {
            val clampedIndex = selectedIndex.coerceIn(0, filteredResults.size - 1)
            coroutineScope.launch {
                // A RESULT index is not a LazyColumn item index when sections are shown: each
                // section contributes a header and a trailing spacer of its own, so the two drift
                // by two per section above the selection. Arrowing into a late category used to
                // scroll visibly short of the row it had selected.
                val grouped = activeCategory == SearchCategory.ALL
                listState.animateScrollToItem(listItemIndexFor(clampedIndex, filteredResults, showSections = grouped))
            }
            scrollToSelected = false
        }
    }

    // Clamp selected index when results change
    LaunchedEffect(filteredResults.size) {
        if (filteredResults.isNotEmpty()) {
            val clampedIndex = selectedIndex.coerceIn(0, filteredResults.size - 1)
            if (clampedIndex != selectedIndex) {
                selectedIndex = clampedIndex
            }
        }
    }

    // Reset selected index when category changes
    LaunchedEffect(activeCategory) {
        selectedIndex = 0
        scrollToSelected = true
    }

    // Clear results when dialog closes
    DisposableEffect(Unit) {
        onDispose {
            GlobalSearchService.clearResults()
            GlobalSearchService.setActiveCategory(SearchCategory.ALL)
        }
    }

    // Handle result selection
    // Note: If a callback is null, the dialog closes without action. This is intentional
    // fallback behavior - the integrating code may not support all result types.
    fun selectResult(result: SearchResult) {
        // One shape for every branch, in `dispatchResult`: name the pick, hand it to the host, and
        // close when the host supplied no handler.
        fun <T> dispatch(
            detailKey: String,
            detailValue: String,
            arg: T,
            handler: ((T) -> Unit)?,
        ) = dispatchResult(result, detailKey, detailValue, arg, handler, onDismiss)

        when (result) {
            is SearchResult.FileResult -> {
                dispatch("file", result.path, result.path, onFileSelect)
            }

            is SearchResult.TabResult -> {
                dispatch(
                    "tab",
                    result.tabId,
                    result,
                    onTabSelect?.let { select ->
                        { r: SearchResult.TabResult -> select(r.windowId, r.panelId, r.tabId) }
                    },
                )
            }

            is SearchResult.BookmarkResult -> {
                dispatch(
                    "bookmark",
                    result.bookmarkId,
                    result,
                    onBookmarkSelect?.let { select ->
                        { r: SearchResult.BookmarkResult -> select(r.bookmarkId, r.collectionId) }
                    },
                )
            }

            is SearchResult.RunConfigResult -> {
                dispatch("config", result.configId, result.configId, onRunConfigSelect)
            }

            is SearchResult.CommandResult -> {
                dispatch("actionId", result.actionId, result.actionId, onCommandSelect)
            }

            is SearchResult.ToolResult -> {
                dispatch("panelId", result.panelId, result.panelId, onToolSelect)
            }

            is SearchResult.SettingResult -> {
                dispatch("setting", result.label, result, onSettingSelect)
            }

            is SearchResult.PageResult -> {
                // The only detail value that is a URL, and a recent page carries its query string.
                // The handler still gets the real one - only the log line is masked, which is what
                // AGENTS.md names LogSanitizer for and what RecentBrowserPagesManager already does
                // to the same data on the way in.
                dispatch("url", LogSanitizer.maskUriParams(result.url), result.url, onPageSelect)
            }

            // No handler, by design: an MCP tool takes arguments a search row cannot collect, so
            // this answers "does a tool for this exist, and what is it called" and stops.
            //
            // Logged here rather than through dispatchResult, which warns about a missing handler.
            // That warning is worth having for a branch the host merely forgot to wire, and this
            // branch is never going to have a handler - routing through it meant every MCP pick
            // filed a warning, which is how a real integration gap stops being noticeable.
            is SearchResult.McpToolResult -> {
                globalSearchLogger.debug(
                    LogCategory.UI,
                    "MCP tool selected; nothing to activate",
                    mapOf("tool" to result.name),
                )
                onDismiss()
            }
        }
    }

    BossDialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
                usePlatformDefaultWidth = false,
            ),
    ) {
        Surface(
            modifier =
                Modifier
                    .width(700.dp)
                    .heightIn(min = 450.dp, max = 600.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            when (event.key) {
                                Key.Escape -> {
                                    onDismiss()
                                    true
                                }

                                Key.DirectionUp -> {
                                    if (filteredResults.isNotEmpty()) {
                                        selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                                        scrollToSelected = true
                                    }
                                    true
                                }

                                Key.DirectionDown -> {
                                    if (filteredResults.isNotEmpty()) {
                                        selectedIndex = (selectedIndex + 1).coerceAtMost(filteredResults.size - 1)
                                        scrollToSelected = true
                                    }
                                    true
                                }

                                Key.Enter -> {
                                    if (filteredResults.isNotEmpty() && selectedIndex < filteredResults.size) {
                                        selectResult(filteredResults[selectedIndex])
                                    }
                                    true
                                }

                                Key.Tab -> {
                                    // The categories ON SCREEN, which is what CategoryTabs draws -
                                    // not every entry in the enum. Cycling the enum walked through
                                    // categories with no chip and no results, so Tab landed on
                                    // "No Tools Found" with nothing on the chip row to say where
                                    // the user was. Four more categories made that four times
                                    // likelier, so it stopped being survivable.
                                    val categories = visibleCategories(resultCounts, activeCategory)
                                    val currentIndex = categories.indexOf(activeCategory)
                                    val nextIndex =
                                        if (event.isShiftPressed) {
                                            (currentIndex - 1 + categories.size) % categories.size
                                        } else {
                                            (currentIndex + 1) % categories.size
                                        }
                                    GlobalSearchService.setActiveCategory(categories[nextIndex])
                                    true
                                }

                                else -> {
                                    false
                                }
                            }
                        } else {
                            false
                        }
                    },
            shape = RoundedCornerShape(16.dp),
            color = BossTheme.colors.panel,
            elevation = 16.dp,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
            ) {
                // Header with title
                AnimatedVisibility(
                    visible = showContent,
                    enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { -it / 2 },
                ) {
                    SearchDialogHeader(
                        fileCount = GlobalSearchService.getIndexedFileCount(),
                        isIndexing = isIndexing,
                        onClose = onDismiss,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Search input field
                AnimatedVisibility(
                    visible = showContent,
                    enter = fadeIn(tween(200, delayMillis = 50)) + slideInVertically(tween(200, delayMillis = 50)) { -it / 2 },
                ) {
                    SearchInputField(
                        query = searchQuery,
                        onQueryChange = { newQuery ->
                            searchQuery = newQuery
                            selectedIndex = 0
                        },
                        focusRequester = searchFieldFocusRequester,
                        isSearching = isSearching,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Category filter tabs (only show when there are results)
                AnimatedVisibility(
                    visible = showContent && searchQuery.isNotBlank() && allResults.isNotEmpty(),
                    enter = fadeIn(tween(200, delayMillis = 75)),
                ) {
                    CategoryTabs(
                        activeCategory = activeCategory,
                        resultCounts = resultCounts,
                        onCategorySelect = { category ->
                            GlobalSearchService.setActiveCategory(category)
                            selectedIndex = 0
                        },
                    )
                }

                if (searchQuery.isNotBlank() && allResults.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Content area
                AnimatedVisibility(
                    visible = showContent,
                    enter = fadeIn(tween(200, delayMillis = 100)) + slideInVertically(tween(200, delayMillis = 100)) { it / 2 },
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),
                    ) {
                        when {
                            searchQuery.isBlank() -> {
                                EmptySearchState()
                            }

                            isIndexing -> {
                                IndexingState()
                            }

                            filteredResults.isEmpty() && !isSearching -> {
                                NoResultsState(query = searchQuery, category = activeCategory)
                            }

                            else -> {
                                SearchResultsList(
                                    results = filteredResults,
                                    selectedIndex = selectedIndex,
                                    listState = listState,
                                    showSections = activeCategory == SearchCategory.ALL,
                                    onResultClick = { result -> selectResult(result) },
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Footer with keyboard hints
                AnimatedVisibility(
                    visible = showContent,
                    enter = fadeIn(tween(200, delayMillis = 150)),
                ) {
                    KeyboardHints()
                }
            }
        }
    }
}

/**
 * Header with title and stats.
 */
@Composable
private fun SearchDialogHeader(
    fileCount: Int,
    isIndexing: Boolean,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Spotlight-style icon
            Box(
                modifier =
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(SelectionAccent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    tint = SelectionAccent,
                    modifier = Modifier.size(20.dp),
                )
            }

            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "BOSS Search",
                        color = BossTheme.colors.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // Shortcut hint
                    Box(
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(BossTheme.colors.raised)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "⇧⇧",
                            color = BossTheme.colors.textSecondary,
                            fontSize = 11.sp,
                        )
                    }
                }
                Text(
                    text = if (isIndexing) "Indexing files..." else "$fileCount files indexed",
                    color = BossTheme.colors.textSecondary,
                    fontSize = 11.sp,
                )
            }
        }

        // Close button
        IconButton(
            onClick = onClose,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Close",
                tint = BossTheme.colors.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Category filter tabs (horizontally scrollable).
 */
@Composable
private fun CategoryTabs(
    activeCategory: SearchCategory,
    resultCounts: Map<SearchCategory, Int>,
    onCategorySelect: (SearchCategory) -> Unit,
) {
    val scrollState = rememberScrollState()

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(SmallCardShape)
                .background(BossTheme.colors.raised)
                .horizontalScroll(scrollState)
                .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (category in visibleCategories(resultCounts, activeCategory)) {
            val count = resultCounts[category] ?: 0
            val isActive = category == activeCategory

            CategoryTab(
                category = category,
                count = count,
                isActive = isActive,
                onClick = { onCategorySelect(category) },
            )
        }
    }
}

@Composable
private fun CategoryTab(
    category: SearchCategory,
    count: Int,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val backgroundColor = if (isActive) SelectionAccent.copy(alpha = 0.2f) else Color.Transparent
    val textColor = if (isActive) SelectionAccent else BossTheme.colors.textSecondary

    val icon = category.chipIcon()

    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(backgroundColor)
                .clickable { onClick() }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = textColor,
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = category.displayName,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
        if (count > 0 && category != SearchCategory.ALL) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = count.toString(),
                color = textColor.copy(alpha = 0.7f),
                fontSize = 10.sp,
            )
        }
    }
}

/**
 * Custom search input field matching dashboard theme.
 */
@Composable
private fun SearchInputField(
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
    isSearching: Boolean,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(CardShape)
                .background(BossTheme.colors.raised)
                .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = "Search",
            tint = if (query.isNotEmpty()) SelectionAccent else BossTheme.colors.textSecondary,
            modifier = Modifier.size(22.dp),
        )

        Spacer(modifier = Modifier.width(12.dp))

        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier =
                Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
            textStyle =
                TextStyle(
                    color = BossTheme.colors.textPrimary,
                    fontSize = 16.sp,
                ),
            singleLine = true,
            cursorBrush = SolidColor(SelectionAccent),
            decorationBox = { innerTextField ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            text = "Search tools, settings, files, tabs, commands...",
                            color = BossTheme.colors.textSecondary,
                            fontSize = 16.sp,
                        )
                    }
                    innerTextField()
                }
            },
        )

        if (isSearching) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = SelectionAccent,
                strokeWidth = 2.dp,
            )
        } else if (query.isNotEmpty()) {
            IconButton(
                onClick = { onQueryChange("") },
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Clear",
                    tint = BossTheme.colors.textSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * Empty state when no search query entered.
 */
@Composable
private fun EmptySearchState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Every category the search actually has, read off the enum rather than listed here.
        //
        // Listing them by hand is what left this panel advertising five sources after four more
        // were added: tools, settings, MCP tools and recent pages were all searchable and nothing
        // on this screen said so. Derived, it cannot drift again - a new category appears here the
        // moment it exists, in the same order as the chips and the sections.
        //
        // Chunked rather than one row: nine tiles (every category but ALL) do not fit a narrow
        // window, and wrapping keeps them centred instead of clipping the last ones. The spacer
        // inside the loop fires after the last row too, which is deliberate - it plus the one
        // below is the 24dp this panel had before.
        SearchCategory.entries
            .filter { it != SearchCategory.ALL }
            .chunked(EMPTY_STATE_TILES_PER_ROW)
            .forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    row.forEach { category ->
                        SearchCategoryPreview(category.chipIcon(), category.displayName, category.accent())
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Search Everything",
            color = BossTheme.colors.textPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text =
                "Open a tool or a settings page, find a file, switch tabs, run a command " +
                    "or a config, open a bookmark or a recent page, or look up an MCP tool",
            color = BossTheme.colors.textSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Quick tips
        Row(
            modifier =
                Modifier
                    .clip(SmallCardShape)
                    .background(BossTheme.colors.raised)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Tip:",
                color = SelectionAccent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Use Tab to switch between categories",
                color = BossTheme.colors.textSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun SearchCategoryPreview(
    icon: ImageVector,
    label: String,
    color: Color,
) {
    Column(
        modifier = Modifier.width(EmptyStateTileWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            color = BossTheme.colors.textSecondary,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Indexing state with progress indicator.
 */
@Composable
private fun IndexingState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(40.dp),
            color = SelectionAccent,
            strokeWidth = 3.dp,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Indexing Project",
            color = BossTheme.colors.textPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "This only happens once per session",
            color = BossTheme.colors.textSecondary,
            fontSize = 13.sp,
        )
    }
}

/**
 * No results state.
 */
@Composable
private fun NoResultsState(
    query: String,
    category: SearchCategory,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.SearchOff,
            contentDescription = null,
            tint = BossTheme.colors.textSecondary.copy(alpha = 0.5f),
            modifier = Modifier.size(48.dp),
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (category == SearchCategory.ALL) "No Results Found" else "No ${category.displayName} Found",
            color = BossTheme.colors.textPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "No matches for \"$query\"",
            color = BossTheme.colors.textSecondary,
            fontSize = 13.sp,
        )

        if (category != SearchCategory.ALL) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Try searching in \"All\" categories",
                color = SelectionAccent,
                fontSize = 12.sp,
            )
        }
    }
}

/**
 * Where the row for [resultIndex] actually sits in [SearchResultsList]'s `LazyColumn`.
 *
 * The list emits a header before each section and a spacer after it, so with sections on there are
 * two extra items per section that starts at or before the selection. Scrolling wants the item
 * index; selection is expressed as a result index; this converts one to the other.
 *
 * Derived from the same walk `SearchResultsList` performs rather than a count of distinct
 * categories, so the two cannot disagree about where a section begins.
 *
 * Correct only because `getFilteredResults` groups by category ordinal - `distinctBy` counts a
 * category once, which is true of a grouped list and false of an interleaved one. Two coupled
 * invariants in two files, either changeable alone, which is why both have tests.
 */
internal fun listItemIndexFor(
    resultIndex: Int,
    results: List<SearchResult>,
    showSections: Boolean,
): Int {
    if (!showSections) return resultIndex
    val sectionsBefore = results.take(resultIndex + 1).distinctBy { it.category }.size
    return resultIndex + sectionsBefore * 2 - 1
}

/**
 * Search results list with optional section headers.
 */
@Composable
private fun SearchResultsList(
    results: List<SearchResult>,
    selectedIndex: Int,
    listState: androidx.compose.foundation.lazy.LazyListState,
    showSections: Boolean,
    onResultClick: (SearchResult) -> Unit,
) {
    // Group results by category for section display
    val groupedResults =
        remember(results, showSections) {
            if (showSections) {
                results.groupBy { it.category }
            } else {
                mapOf(results.firstOrNull()?.category to results)
            }
        }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (showSections) {
            // Show results grouped by category with section headers
            var globalIndex = 0
            for (category in SearchCategory.entries) {
                if (category == SearchCategory.ALL) continue
                val categoryResults = groupedResults[category] ?: continue
                if (categoryResults.isEmpty()) continue

                // Section header
                item(key = "header-$category") {
                    SectionHeader(category = category, count = categoryResults.size)
                }

                // Results in this section
                items(categoryResults.size, key = { "$category-$it" }) { localIndex ->
                    val result = categoryResults[localIndex]
                    val itemGlobalIndex = globalIndex + localIndex
                    val isSelected = itemGlobalIndex == selectedIndex

                    SearchResultItem(
                        result = result,
                        isSelected = isSelected,
                        onClick = { onResultClick(result) },
                    )
                }

                globalIndex += categoryResults.size

                // Spacer between sections
                item(key = "spacer-$category") {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        } else {
            // Show flat list without section headers
            items(results.size, key = { it }) { index ->
                val result = results[index]
                val isSelected = index == selectedIndex

                SearchResultItem(
                    result = result,
                    isSelected = isSelected,
                    onClick = { onResultClick(result) },
                )
            }
        }
    }
}

/**
 * Section header for grouped results.
 */
@Composable
private fun SectionHeader(
    category: SearchCategory,
    count: Int,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            // chipIcon, not a second table: this one named only the five original categories and
            // fell through to Apps, so every section added by this PR - Tools, Settings, MCP Tools,
            // Recent Pages - drew the generic grid icon while its own chip drew the right one.
            imageVector = category.chipIcon(),
            contentDescription = null,
            tint = SectionTitleColor,
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = category.displayName,
            color = SectionTitleColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "($count)",
            color = SectionTitleColor.copy(alpha = 0.6f),
            fontSize = 10.sp,
        )
    }
}

/**
 * Individual search result item with hover effects.
 */
@Composable
private fun SearchResultItem(
    result: SearchResult,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val scale by animateFloatAsState(
        targetValue = if (isHovered || isSelected) 1.01f else 1f,
        animationSpec = spring(dampingRatio = 0.7f),
    )

    val backgroundColor =
        when {
            isSelected -> SelectionAccent.copy(alpha = 0.15f)
            isHovered -> HoverBackground
            else -> BossTheme.colors.raised
        }

    // Two families, not nine cases. The older result types each have a shape of their own - a file
    // shows its matched ranges, a tab shows which window it is in - and are dispatched below; the
    // four newer ones differ only in data, so they share one row and one table (`simpleRow`).
    //
    // Which family this is comes from simpleRow returning a row or null, and THAT `when` is
    // exhaustive over the sealed class - so adding a result type fails the build there rather than
    // reaching a runtime error from inside a composable.
    val row = result.simpleRow()
    if (row != null) {
        SimpleResultItem(row, isSelected, isHovered, scale, backgroundColor, interactionSource, onClick)
    } else {
        DetailedResultItem(result, isSelected, isHovered, scale, backgroundColor, interactionSource, onClick)
    }
}

/**
 * The result types that each draw themselves differently.
 *
 * Split from [SearchResultItem] so that adding a result type to the simple family does not keep
 * growing one function past what anyone can read at once.
 */
@Composable
@Suppress("LongParameterList")
private fun DetailedResultItem(
    result: SearchResult,
    isSelected: Boolean,
    isHovered: Boolean,
    scale: Float,
    backgroundColor: Color,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
) {
    when (result) {
        is SearchResult.FileResult -> {
            FileResultItem(result, isSelected, isHovered, scale, backgroundColor, interactionSource, onClick)
        }

        is SearchResult.TabResult -> {
            TabResultItem(result, isSelected, isHovered, scale, backgroundColor, interactionSource, onClick)
        }

        is SearchResult.BookmarkResult -> {
            BookmarkResultItem(result, isSelected, isHovered, scale, backgroundColor, interactionSource, onClick)
        }

        is SearchResult.RunConfigResult -> {
            RunConfigResultItem(result, isSelected, isHovered, scale, backgroundColor, interactionSource, onClick)
        }

        is SearchResult.CommandResult -> {
            CommandResultItem(result, isSelected, isHovered, scale, backgroundColor, interactionSource, onClick)
        }

        // Named rather than left to an `else`, so this `when` is exhaustive too. With an `else`,
        // the exhaustiveness argument for `simpleRow` only went one way: a new type failed the
        // build there, and the cheapest way to satisfy that was to add it to the null-returning
        // list - after which this function compiled unchanged and threw during composition, which
        // in this app means the render-recovery path. Now both ends fail at compile time.
        //
        // Unreachable by construction: `SearchResultItem` only calls this when `simpleRow` was
        // null, which is exactly the five types above.
        is SearchResult.ToolResult,
        is SearchResult.SettingResult,
        is SearchResult.McpToolResult,
        is SearchResult.PageResult,
        -> {
            // Logged and skipped rather than thrown. `simpleRow` already makes a new result type a
            // compile error, so this branch is unreachable - and it runs inside a composable,
            // where a throw means the render-recovery path takes out the whole dialog. One missing
            // row is the better failure for something that cannot happen.
            globalSearchLogger.error(
                LogCategory.UI,
                "DetailedResultItem got a simple result type; skipping the row",
                mapOf("type" to result::class.simpleName.orEmpty()),
            )
        }
    }
}

/**
 * One row shape for the four sources that need nothing special: an icon, a name, where it came
 * from, and an optional chip on the end.
 *
 * Written once rather than as four near-identical copies of [CommandResultItem]. The older result
 * types each have a shape of their own - a file shows matched ranges, a tab shows its window - and
 * are left alone; these four do not, and four copies of the same 50 lines is how their padding and
 * their icon sizes drift apart.
 */
@Composable
@Suppress("LongParameterList")
private fun SimpleResultItem(
    row: SimpleRow,
    isSelected: Boolean,
    isHovered: Boolean,
    scale: Float,
    backgroundColor: Color,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .scale(scale)
                .clip(SmallCardShape)
                .background(backgroundColor)
                .clickable { onClick() }
                .hoverable(interactionSource)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = row.icon,
            contentDescription = null,
            tint = row.accent,
            modifier = Modifier.size(22.dp),
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                color = if (isSelected || isHovered) BossTheme.colors.textPrimary else BossTheme.colors.textSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.subtitle.isNotBlank()) {
                Text(
                    text = row.subtitle,
                    color = BossTheme.colors.textMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        row.trailing?.let { trailing ->
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier =
                    Modifier
                        .widthIn(max = TrailingChipMaxWidth)
                        .clip(RoundedCornerShape(4.dp))
                        .background(row.accent.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                // Bounded and ellipsised, as every other text in this row is. The chip carries a
                // plugin id and those run long (`ai.rever.boss.plugin.dynamic.terminal-tab`);
                // unbounded, it squeezed the title, which is the column with weight(1f).
                Text(
                    text = trailing,
                    fontSize = 10.sp,
                    color = row.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** What one of the four simple rows shows. */
internal data class SimpleRow(
    val icon: ImageVector,
    val accent: Color,
    val title: String,
    val subtitle: String,
    val trailing: String? = null,
)

/**
 * How each of the four simple results is drawn.
 *
 * The differences between them are data - which field is the subtitle, and whether there is a chip
 * on the end - so they live here as a table rather than as four call sites whose padding and icon
 * sizes drift. Icon and accent are not among the differences: both come from [chipIcon] and
 * [accent], so a category looks the same on a result row, its chip and its empty-state tile.
 *
 * No match highlighting, unlike the five detailed rows, which render their `matchRanges`. These
 * four sources carry none - the settings matcher returns ranges only for the label, and the rest
 * never computed any - so the row shows plain text rather than a highlight that would be right on
 * some rows and absent on others.
 *
 * Null for the five types that draw themselves; [SearchResultItem] branches on that to pick the
 * family. Not `@Composable`, and `internal` rather than private, because it is a pure mapping over
 * the sealed class and the one thing worth testing about it - that the two families between them
 * name every result type, exactly once - is not reachable from inside a composable.
 */
internal fun SearchResult.simpleRow(): SimpleRow? =
    when (this) {
        is SearchResult.ToolResult -> {
            SimpleRow(category.chipIcon(), category.accent(), label, panelId)
        }

        is SearchResult.SettingResult -> {
            // The breadcrumb, which is most of what tells two similarly named settings apart.
            SimpleRow(category.chipIcon(), category.accent(), label, breadcrumb)
        }

        is SearchResult.McpToolResult -> {
            SimpleRow(
                icon = category.chipIcon(),
                accent = category.accent(),
                // The name clients call it by, so what is on screen is what gets typed.
                //
                // Safe to read from a composable only because it is `const`: the compiler inlines
                // it, so no McpToolRegistryImpl clinit runs here. Demoting it to a plain `val`
                // would quietly pull that object's disabled-tools file read into composition.
                title = "${McpToolRegistryImpl.CLIENT_TOOL_PREFIX}$name",
                subtitle = description,
                // This row does nothing when selected, so its state has to be legible here: a
                // disabled tool is exactly the one someone searched for, and it says so.
                trailing = if (enabled) providerId else "off - $providerId",
            )
        }

        is SearchResult.PageResult -> {
            // A page with no title falls back to its URL for the title - and then the subtitle was
            // the same URL again, so the row printed it twice. Blank in that case: the icon and
            // the accent already say which category it is.
            // displayName already applies the blank-title rule; computing it again here made two
            // copies of one decision.
            SimpleRow(category.chipIcon(), category.accent(), displayName, if (displayName == url) "" else url)
        }

        // Named one by one rather than left to an `else`, which is the whole point: this `when` is
        // an exhaustive expression over the sealed class, so a new result type fails the build here
        // and its author has to say which family it belongs to. An `else` would take the decision
        // silently and hand the row a runtime error instead.
        is SearchResult.FileResult,
        is SearchResult.TabResult,
        is SearchResult.BookmarkResult,
        is SearchResult.RunConfigResult,
        is SearchResult.CommandResult,
        -> {
            null
        }
    }

@Composable
private fun FileResultItem(
    result: SearchResult.FileResult,
    isSelected: Boolean,
    isHovered: Boolean,
    scale: Float,
    backgroundColor: Color,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
) {
    val fileIconInfo = FileIcons.forFile(result.name)
    val parentFolder = result.path.extractParentName()

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .scale(scale)
                .clip(SmallCardShape)
                .background(backgroundColor)
                .clickable { onClick() }
                .hoverable(interactionSource)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = fileIconInfo.icon,
            contentDescription = result.name,
            tint = fileIconInfo.color,
            modifier = Modifier.size(22.dp),
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlightMatches(result.name, result.matchRanges, isSelected || isHovered),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = result.relativePath,
                fontSize = 11.sp,
                color = BossTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (parentFolder.isNotEmpty()) {
            Box(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(BossTheme.colors.panel)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                Text(
                    text = parentFolder,
                    fontSize = 10.sp,
                    color = BossTheme.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun TabResultItem(
    result: SearchResult.TabResult,
    isSelected: Boolean,
    isHovered: Boolean,
    scale: Float,
    backgroundColor: Color,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .scale(scale)
                .clip(SmallCardShape)
                .background(backgroundColor)
                .clickable { onClick() }
                .hoverable(interactionSource)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Tab,
            contentDescription = null,
            tint = TabsAccent,
            modifier = Modifier.size(22.dp),
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlightMatches(result.title, result.matchRanges, isSelected || isHovered),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${result.tabType} • ${result.workspaceName}",
                fontSize = 11.sp,
                color = BossTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Box(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(TabsAccent.copy(alpha = 0.15f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
        ) {
            Text(
                text = "Open",
                fontSize = 10.sp,
                color = TabsAccent,
            )
        }
    }
}

@Composable
private fun BookmarkResultItem(
    result: SearchResult.BookmarkResult,
    isSelected: Boolean,
    isHovered: Boolean,
    scale: Float,
    backgroundColor: Color,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .scale(scale)
                .clip(SmallCardShape)
                .background(backgroundColor)
                .clickable { onClick() }
                .hoverable(interactionSource)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Bookmark,
            contentDescription = null,
            tint = BookmarksAccent,
            modifier = Modifier.size(22.dp),
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlightMatches(result.title, result.matchRanges, isSelected || isHovered),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${result.tabType} • ${result.collectionName}",
                fontSize = 11.sp,
                color = BossTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Box(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(BookmarksAccent.copy(alpha = 0.15f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
        ) {
            Text(
                text = result.collectionName,
                fontSize = 10.sp,
                color = BookmarksAccent,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RunConfigResultItem(
    result: SearchResult.RunConfigResult,
    isSelected: Boolean,
    isHovered: Boolean,
    scale: Float,
    backgroundColor: Color,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .scale(scale)
                .clip(SmallCardShape)
                .background(backgroundColor)
                .clickable { onClick() }
                .hoverable(interactionSource)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.PlayArrow,
            contentDescription = null,
            tint = RunConfigAccent,
            modifier = Modifier.size(22.dp),
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlightMatches(result.name, result.matchRanges, isSelected || isHovered),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${result.language} • ${result.configType}",
                fontSize = 11.sp,
                color = BossTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Box(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(RunConfigAccent.copy(alpha = 0.15f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
        ) {
            Text(
                text = "Run",
                fontSize = 10.sp,
                color = RunConfigAccent,
            )
        }
    }
}

@Composable
private fun CommandResultItem(
    result: SearchResult.CommandResult,
    isSelected: Boolean,
    isHovered: Boolean,
    scale: Float,
    backgroundColor: Color,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .scale(scale)
                .clip(SmallCardShape)
                .background(backgroundColor)
                .clickable { onClick() }
                .hoverable(interactionSource)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Terminal,
            contentDescription = null,
            tint = CommandsAccent,
            modifier = Modifier.size(22.dp),
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.description,
                color = if (isSelected || isHovered) BossTheme.colors.textPrimary else BossTheme.colors.textSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (result.shortcut != null) {
            Box(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(CommandsAccent.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                Text(
                    text = result.shortcut,
                    fontSize = 10.sp,
                    color = CommandsAccent,
                )
            }
        }
    }
}

/**
 * Keyboard hints footer.
 */
@Composable
private fun KeyboardHints() {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(SmallCardShape)
                .background(BossTheme.colors.raised)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyboardHint(key = "↑↓", action = "Navigate")
        Spacer(modifier = Modifier.width(20.dp))
        KeyboardHint(key = "Tab", action = "Filter")
        Spacer(modifier = Modifier.width(20.dp))
        KeyboardHint(key = "Enter", action = "Open")
        Spacer(modifier = Modifier.width(20.dp))
        KeyboardHint(key = "Esc", action = "Close")
    }
}

@Composable
private fun KeyboardHint(
    key: String,
    action: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(BossTheme.colors.panel)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
        ) {
            Text(
                text = key,
                fontSize = 10.sp,
                color = BossTheme.colors.textPrimary,
                fontWeight = FontWeight.Medium,
            )
        }
        Text(
            text = action,
            fontSize = 10.sp,
            color = BossTheme.colors.textSecondary,
        )
    }
}

/**
 * Build an AnnotatedString with match highlights.
 */
private fun highlightMatches(
    text: String,
    matchRanges: List<MatchRange>,
    isHighlighted: Boolean,
): AnnotatedString {
    val textColor = if (isHighlighted) BossThemeController.current.colors.textPrimary else BossThemeController.current.colors.textSecondary

    if (matchRanges.isEmpty()) {
        return buildAnnotatedString {
            withStyle(SpanStyle(color = textColor, fontWeight = FontWeight.Normal)) {
                append(text)
            }
        }
    }

    return buildAnnotatedString {
        var lastEnd = 0

        for (range in matchRanges.sortedBy { it.start }) {
            val start = range.start.coerceIn(0, text.length)
            val end = range.end.coerceIn(0, text.length)

            if (start > lastEnd) {
                withStyle(SpanStyle(color = textColor, fontWeight = FontWeight.Normal)) {
                    append(text.substring(lastEnd, start))
                }
            }

            if (end > start) {
                withStyle(SpanStyle(color = SelectionAccent, fontWeight = FontWeight.Bold)) {
                    append(text.substring(start, end))
                }
            }

            lastEnd = end
        }

        if (lastEnd < text.length) {
            withStyle(SpanStyle(color = textColor, fontWeight = FontWeight.Normal)) {
                append(text.substring(lastEnd))
            }
        }
    }
}

/**
 * The categories with a chip: [SearchCategory.ALL], the active one, plus any that matched something.
 *
 * Shared with the Tab handler, which is the point - it cycled the whole enum while this list is
 * what the user can see, so Tab could select a category that had no chip and no results.
 *
 * **[active] is kept even at zero results**, which is the other half of the same bug. Filtering on
 * count alone let the selected chip disappear as the query narrowed: Tab to Tools, keep typing
 * until no tool matches, and the row drew only "All" - unhighlighted - over a pane reading "No
 * Tools Found", with nothing on screen saying a filter was on. It also left
 * `indexOf(activeCategory)` at -1 in the Tab handler, which did not crash but cycled from an
 * arbitrary place. A chip that is filtering is always visible now, so neither can happen.
 */
internal fun visibleCategories(
    resultCounts: Map<SearchCategory, Int>,
    active: SearchCategory,
): List<SearchCategory> =
    SearchCategory.entries.filter {
        it == SearchCategory.ALL || it == active || (resultCounts[it] ?: 0) > 0
    }

/**
 * The chip's icon for a category.
 *
 * A table, out here rather than inside `CategoryTab`: it is one branch per category and nothing
 * else in that composable is, so leaving it inline made a layout function read as a lookup.
 */
private fun SearchCategory.chipIcon(): ImageVector =
    when (this) {
        SearchCategory.ALL -> Icons.Outlined.Apps

        SearchCategory.FILES -> Icons.Outlined.Description

        SearchCategory.TABS -> Icons.Outlined.Tab

        SearchCategory.BOOKMARKS -> Icons.Outlined.Bookmark

        SearchCategory.RUN_CONFIGS -> Icons.Outlined.PlayArrow

        SearchCategory.COMMANDS -> Icons.Outlined.Terminal

        // Not Apps, which is ALL's icon: Tools now sits directly beside ALL on the chip row, and
        // the two chips were the same glyph with different words under them.
        SearchCategory.TOOLS -> Icons.Outlined.Extension

        SearchCategory.SETTINGS -> Icons.Outlined.Settings

        SearchCategory.MCP -> Icons.Outlined.Build

        SearchCategory.PAGES -> Icons.Outlined.History
    }

/**
 * The category's accent colour.
 *
 * Beside [chipIcon] and for the same reason: one definition per category, so a result row and the
 * empty-state tile for the same category cannot drift apart - for the four families that share
 * [SimpleRow]. `FILES` is the exception and stays one: a file row is tinted by `FileIcons` per
 * filetype, which is more useful than a category colour, so [FilesAccent] reaches only the chip
 * and the tile. Section headers deliberately do not
 * use it - they tint everything with [SectionTitleColor], which is what keeps them quiet.
 *
 * **Not nine distinct colours, and not trying to be.** The design system carries seven semantic
 * colour tokens and there are nine categories, so inventing more would mean inventing colours the
 * theme does not define - which is worse than sharing, because it is the one thing that does not
 * re-skin when the theme changes. Two pairs share on purpose, chosen so the pair means something:
 * tabs and recent pages are both web pages, commands and MCP tools are both things a machine
 * invokes. The icon and the section header carry the identity; the accent carries the family.
 * `RUN_CONFIGS` remains the documented one-off, having no token at all.
 */
private fun SearchCategory.accent(): Color =
    when (this) {
        SearchCategory.ALL -> SelectionAccent
        SearchCategory.FILES -> FilesAccent
        SearchCategory.TABS -> TabsAccent
        SearchCategory.BOOKMARKS -> BookmarksAccent
        SearchCategory.RUN_CONFIGS -> RunConfigAccent
        SearchCategory.COMMANDS -> CommandsAccent
        SearchCategory.TOOLS -> ToolsAccent
        SearchCategory.SETTINGS -> SettingsAccent
        SearchCategory.MCP -> McpAccent
        SearchCategory.PAGES -> PagesAccent
    }

/**
 * Hand a picked result to its host, or close the dialog when there is no host handler.
 *
 * Nine branches of [GlobalSearchDialog]'s `selectResult` are this same shape, and nine copies is
 * how one of them ends up logging a different key, or forgetting to dismiss and leaving the dialog
 * up over a result that did nothing.
 *
 * A null handler closing the dialog is deliberate, not a fallback of last resort: the integrating
 * code is not required to support every result type, and `McpToolResult` has no handler at all.
 */
@Suppress("LongParameterList")
private fun <T> dispatchResult(
    result: SearchResult,
    detailKey: String,
    detailValue: String,
    arg: T,
    handler: ((T) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val kind = result::class.simpleName.orEmpty()
    globalSearchLogger.debug(LogCategory.UI, "Result selected", mapOf("kind" to kind, detailKey to detailValue))
    if (handler != null) {
        handler(arg)
    } else {
        globalSearchLogger.warn(LogCategory.UI, "No handler for result, closing dialog", mapOf("kind" to kind))
        onDismiss()
    }
}
