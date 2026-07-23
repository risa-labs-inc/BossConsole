package ai.rever.boss.components.dialogs

import BossDarkAccent
import BossDarkBackground
import BossDarkSecondary
import BossDarkSuccess
import BossDarkSurface
import BossDarkTextMuted
import BossDarkTextPrimary
import BossDarkTextSecondary
import BossDarkWarning
import ai.rever.boss.components.workspaces.WorkspaceManager
import ai.rever.boss.icons.FileIcons
import ai.rever.boss.search.GlobalSearchService
import ai.rever.boss.search.MatchRange
import ai.rever.boss.search.SearchCategory
import ai.rever.boss.search.SearchResult
import ai.rever.boss.utils.extractParentName
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val globalSearchLogger = BossLogger.forComponent("GlobalSearchDialog")

// Theme colors — reactive getters into the BOSS design system tokens
// (getters, not cached vals, so theme switches re-skin the dialog).
private val AccentBlue get() = BossDarkAccent      // signal — selection / primary
private val AccentGreen get() = BossDarkSuccess    // ok — tabs
private val AccentOrange get() = BossDarkWarning   // warn — bookmarks
// Deliberate one-off: the design system has no purple token (run-config identity color).
private val AccentPurple = Color(0xFF9C27B0)
private val AccentCyan get() = BossDarkSecondary   // data — commands
private val HoverBackground get() = BossDarkSurface
private val CardShape = RoundedCornerShape(12.dp)
private val SmallCardShape = RoundedCornerShape(8.dp)
private val SectionTitleColor get() = BossDarkTextMuted

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
    onDismiss: () -> Unit,
    onFileSelect: (String) -> Unit,
    onTabSelect: ((windowId: String, panelId: String, tabId: String) -> Unit)? = null,
    onBookmarkSelect: ((bookmarkId: String, collectionId: String) -> Unit)? = null,
    onRunConfigSelect: ((configId: String) -> Unit)? = null,
    onCommandSelect: ((actionId: String) -> Unit)? = null
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
    val filteredResults = remember(allResults, activeCategory) {
        GlobalSearchService.getFilteredResults()
    }

    // Get result counts by category
    val resultCounts = remember(allResults) {
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
        GlobalSearchService.search(searchQuery)
    }

    // Auto-scroll to selected item (only when triggered by keyboard)
    LaunchedEffect(selectedIndex, scrollToSelected) {
        if (scrollToSelected && filteredResults.isNotEmpty()) {
            val clampedIndex = selectedIndex.coerceIn(0, filteredResults.size - 1)
            coroutineScope.launch {
                listState.animateScrollToItem(clampedIndex)
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
        when (result) {
            is SearchResult.FileResult -> {
                globalSearchLogger.debug(LogCategory.UI, "File selected from search", mapOf("file" to result.path))
                onFileSelect(result.path)
            }
            is SearchResult.TabResult -> {
                globalSearchLogger.debug(LogCategory.UI, "Tab selected from search", mapOf("tab" to result.tabId))
                if (onTabSelect != null) {
                    onTabSelect.invoke(result.windowId, result.panelId, result.tabId)
                } else {
                    globalSearchLogger.warn(LogCategory.UI, "No tab select handler, closing dialog")
                    onDismiss()
                }
            }
            is SearchResult.BookmarkResult -> {
                globalSearchLogger.debug(LogCategory.UI, "Bookmark selected from search", mapOf("bookmark" to result.bookmarkId))
                if (onBookmarkSelect != null) {
                    onBookmarkSelect.invoke(result.bookmarkId, result.collectionId)
                } else {
                    globalSearchLogger.warn(LogCategory.UI, "No bookmark select handler, closing dialog")
                    onDismiss()
                }
            }
            is SearchResult.RunConfigResult -> {
                globalSearchLogger.debug(LogCategory.UI, "Run config selected from search", mapOf("config" to result.configId))
                if (onRunConfigSelect != null) {
                    onRunConfigSelect.invoke(result.configId)
                } else {
                    globalSearchLogger.warn(LogCategory.UI, "No run config select handler, closing dialog")
                    onDismiss()
                }
            }
            is SearchResult.CommandResult -> {
                globalSearchLogger.debug(LogCategory.UI, "Command selected from search", mapOf("actionId" to result.actionId))
                if (onCommandSelect != null) {
                    onCommandSelect.invoke(result.actionId)
                } else {
                    globalSearchLogger.warn(LogCategory.UI, "No command select handler, closing dialog")
                    onDismiss()
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
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
                                // Cycle through categories
                                val categories = SearchCategory.entries
                                val currentIndex = categories.indexOf(activeCategory)
                                val nextIndex = if (event.isShiftPressed) {
                                    (currentIndex - 1 + categories.size) % categories.size
                                } else {
                                    (currentIndex + 1) % categories.size
                                }
                                GlobalSearchService.setActiveCategory(categories[nextIndex])
                                true
                            }
                            else -> false
                        }
                    } else {
                        false
                    }
                },
            shape = RoundedCornerShape(16.dp),
            color = BossDarkBackground,
            elevation = 16.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                // Header with title
                AnimatedVisibility(
                    visible = showContent,
                    enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { -it / 2 }
                ) {
                    SearchDialogHeader(
                        fileCount = GlobalSearchService.getIndexedFileCount(),
                        isIndexing = isIndexing,
                        onClose = onDismiss
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Search input field
                AnimatedVisibility(
                    visible = showContent,
                    enter = fadeIn(tween(200, delayMillis = 50)) + slideInVertically(tween(200, delayMillis = 50)) { -it / 2 }
                ) {
                    SearchInputField(
                        query = searchQuery,
                        onQueryChange = { newQuery ->
                            searchQuery = newQuery
                            selectedIndex = 0
                        },
                        focusRequester = searchFieldFocusRequester,
                        isSearching = isSearching
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Category filter tabs (only show when there are results)
                AnimatedVisibility(
                    visible = showContent && searchQuery.isNotBlank() && allResults.isNotEmpty(),
                    enter = fadeIn(tween(200, delayMillis = 75))
                ) {
                    CategoryTabs(
                        activeCategory = activeCategory,
                        resultCounts = resultCounts,
                        onCategorySelect = { category ->
                            GlobalSearchService.setActiveCategory(category)
                            selectedIndex = 0
                        }
                    )
                }

                if (searchQuery.isNotBlank() && allResults.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Content area
                AnimatedVisibility(
                    visible = showContent,
                    enter = fadeIn(tween(200, delayMillis = 100)) + slideInVertically(tween(200, delayMillis = 100)) { it / 2 }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
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
                                    onResultClick = { result -> selectResult(result) }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Footer with keyboard hints
                AnimatedVisibility(
                    visible = showContent,
                    enter = fadeIn(tween(200, delayMillis = 150))
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
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Spotlight-style icon
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AccentBlue.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    tint = AccentBlue,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "BOSS Search",
                        color = BossDarkTextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    // Shortcut hint
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(BossDarkSurface)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "⇧⇧",
                            color = BossDarkTextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
                Text(
                    text = if (isIndexing) "Indexing files..." else "$fileCount files indexed",
                    color = BossDarkTextSecondary,
                    fontSize = 11.sp
                )
            }
        }

        // Close button
        IconButton(
            onClick = onClose,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Close",
                tint = BossDarkTextSecondary,
                modifier = Modifier.size(20.dp)
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
    onCategorySelect: (SearchCategory) -> Unit
) {
    val scrollState = rememberScrollState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(SmallCardShape)
            .background(BossDarkSurface)
            .horizontalScroll(scrollState)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Only show categories that have results (or ALL)
        val visibleCategories = SearchCategory.entries.filter { category ->
            category == SearchCategory.ALL || (resultCounts[category] ?: 0) > 0
        }

        for (category in visibleCategories) {
            val count = resultCounts[category] ?: 0
            val isActive = category == activeCategory

            CategoryTab(
                category = category,
                count = count,
                isActive = isActive,
                onClick = { onCategorySelect(category) }
            )
        }
    }
}

@Composable
private fun CategoryTab(
    category: SearchCategory,
    count: Int,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isActive) AccentBlue.copy(alpha = 0.2f) else Color.Transparent
    val textColor = if (isActive) AccentBlue else BossDarkTextSecondary

    val icon = when (category) {
        SearchCategory.ALL -> Icons.Outlined.Apps
        SearchCategory.FILES -> Icons.Outlined.Description
        SearchCategory.TABS -> Icons.Outlined.Tab
        SearchCategory.BOOKMARKS -> Icons.Outlined.Bookmark
        SearchCategory.RUN_CONFIGS -> Icons.Outlined.PlayArrow
        SearchCategory.COMMANDS -> Icons.Outlined.Terminal
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = textColor,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = category.displayName,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1
        )
        if (count > 0 && category != SearchCategory.ALL) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = count.toString(),
                color = textColor.copy(alpha = 0.7f),
                fontSize = 10.sp
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
    isSearching: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(CardShape)
            .background(BossDarkSurface)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = "Search",
            tint = if (query.isNotEmpty()) AccentBlue else BossDarkTextSecondary,
            modifier = Modifier.size(22.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            textStyle = TextStyle(
                color = BossDarkTextPrimary,
                fontSize = 16.sp
            ),
            singleLine = true,
            cursorBrush = SolidColor(AccentBlue),
            decorationBox = { innerTextField ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            text = "Search files, tabs, commands...",
                            color = BossDarkTextSecondary,
                            fontSize = 16.sp
                        )
                    }
                    innerTextField()
                }
            }
        )

        if (isSearching) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = AccentBlue,
                strokeWidth = 2.dp
            )
        } else if (query.isNotEmpty()) {
            IconButton(
                onClick = { onQueryChange("") },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Clear",
                    tint = BossDarkTextSecondary,
                    modifier = Modifier.size(16.dp)
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
        verticalArrangement = Arrangement.Center
    ) {
        // Search categories preview
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SearchCategoryPreview(Icons.Outlined.Tab, "Tabs", AccentGreen)
            SearchCategoryPreview(Icons.Outlined.Description, "Files", AccentBlue)
            SearchCategoryPreview(Icons.Outlined.Terminal, "Commands", AccentCyan)
            SearchCategoryPreview(Icons.Outlined.Bookmark, "Bookmarks", AccentOrange)
            SearchCategoryPreview(Icons.Outlined.PlayArrow, "Run", AccentPurple)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Search Everything",
            color = BossDarkTextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Find files, switch tabs, run commands, open bookmarks, or run configs",
            color = BossDarkTextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Quick tips
        Row(
            modifier = Modifier
                .clip(SmallCardShape)
                .background(BossDarkSurface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Tip:",
                color = AccentBlue,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Use Tab to switch between categories",
                color = BossDarkTextSecondary,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun SearchCategoryPreview(
    icon: ImageVector,
    label: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            color = BossDarkTextSecondary,
            fontSize = 11.sp
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
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(40.dp),
            color = AccentBlue,
            strokeWidth = 3.dp
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Indexing Project",
            color = BossDarkTextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "This only happens once per session",
            color = BossDarkTextSecondary,
            fontSize = 13.sp
        )
    }
}

/**
 * No results state.
 */
@Composable
private fun NoResultsState(query: String, category: SearchCategory) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.SearchOff,
            contentDescription = null,
            tint = BossDarkTextSecondary.copy(alpha = 0.5f),
            modifier = Modifier.size(48.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (category == SearchCategory.ALL) "No Results Found" else "No ${category.displayName} Found",
            color = BossDarkTextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "No matches for \"$query\"",
            color = BossDarkTextSecondary,
            fontSize = 13.sp
        )

        if (category != SearchCategory.ALL) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Try searching in \"All\" categories",
                color = AccentBlue,
                fontSize = 12.sp
            )
        }
    }
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
    onResultClick: (SearchResult) -> Unit
) {
    // Group results by category for section display
    val groupedResults = remember(results, showSections) {
        if (showSections) {
            results.groupBy { it.category }
        } else {
            mapOf(results.firstOrNull()?.category to results)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
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
                        onClick = { onResultClick(result) }
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
                    onClick = { onResultClick(result) }
                )
            }
        }
    }
}

/**
 * Section header for grouped results.
 */
@Composable
private fun SectionHeader(category: SearchCategory, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon = when (category) {
            SearchCategory.FILES -> Icons.Outlined.Description
            SearchCategory.TABS -> Icons.Outlined.Tab
            SearchCategory.BOOKMARKS -> Icons.Outlined.Bookmark
            SearchCategory.RUN_CONFIGS -> Icons.Outlined.PlayArrow
            SearchCategory.COMMANDS -> Icons.Outlined.Terminal
            else -> Icons.Outlined.Apps
        }

        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = SectionTitleColor,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = category.displayName,
            color = SectionTitleColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "($count)",
            color = SectionTitleColor.copy(alpha = 0.6f),
            fontSize = 10.sp
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
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val scale by animateFloatAsState(
        targetValue = if (isHovered || isSelected) 1.01f else 1f,
        animationSpec = spring(dampingRatio = 0.7f)
    )

    val backgroundColor = when {
        isSelected -> AccentBlue.copy(alpha = 0.15f)
        isHovered -> HoverBackground
        else -> BossDarkSurface
    }

    when (result) {
        is SearchResult.FileResult -> FileResultItem(result, isSelected, isHovered, scale, backgroundColor, interactionSource, onClick)
        is SearchResult.TabResult -> TabResultItem(result, isSelected, isHovered, scale, backgroundColor, interactionSource, onClick)
        is SearchResult.BookmarkResult -> BookmarkResultItem(result, isSelected, isHovered, scale, backgroundColor, interactionSource, onClick)
        is SearchResult.RunConfigResult -> RunConfigResultItem(result, isSelected, isHovered, scale, backgroundColor, interactionSource, onClick)
        is SearchResult.CommandResult -> CommandResultItem(result, isSelected, isHovered, scale, backgroundColor, interactionSource, onClick)
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
    onClick: () -> Unit
) {
    val fileIconInfo = FileIcons.forFile(result.name)
    val parentFolder = result.path.extractParentName()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(SmallCardShape)
            .background(backgroundColor)
            .clickable { onClick() }
            .hoverable(interactionSource)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = fileIconInfo.icon,
            contentDescription = result.name,
            tint = fileIconInfo.color,
            modifier = Modifier.size(22.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlightMatches(result.name, result.matchRanges, isSelected || isHovered),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = result.relativePath,
                fontSize = 11.sp,
                color = BossDarkTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (parentFolder.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(BossDarkBackground)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Text(
                    text = parentFolder,
                    fontSize = 10.sp,
                    color = BossDarkTextSecondary
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
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(SmallCardShape)
            .background(backgroundColor)
            .clickable { onClick() }
            .hoverable(interactionSource)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Tab,
            contentDescription = null,
            tint = AccentGreen,
            modifier = Modifier.size(22.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlightMatches(result.title, result.matchRanges, isSelected || isHovered),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${result.tabType} • ${result.workspaceName}",
                fontSize = 11.sp,
                color = BossDarkTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(AccentGreen.copy(alpha = 0.15f))
                .padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            Text(
                text = "Open",
                fontSize = 10.sp,
                color = AccentGreen
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
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(SmallCardShape)
            .background(backgroundColor)
            .clickable { onClick() }
            .hoverable(interactionSource)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Bookmark,
            contentDescription = null,
            tint = AccentOrange,
            modifier = Modifier.size(22.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlightMatches(result.title, result.matchRanges, isSelected || isHovered),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${result.tabType} • ${result.collectionName}",
                fontSize = 11.sp,
                color = BossDarkTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(AccentOrange.copy(alpha = 0.15f))
                .padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            Text(
                text = result.collectionName,
                fontSize = 10.sp,
                color = AccentOrange,
                maxLines = 1
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
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(SmallCardShape)
            .background(backgroundColor)
            .clickable { onClick() }
            .hoverable(interactionSource)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.PlayArrow,
            contentDescription = null,
            tint = AccentPurple,
            modifier = Modifier.size(22.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlightMatches(result.name, result.matchRanges, isSelected || isHovered),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${result.language} • ${result.configType}",
                fontSize = 11.sp,
                color = BossDarkTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(AccentPurple.copy(alpha = 0.15f))
                .padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            Text(
                text = "Run",
                fontSize = 10.sp,
                color = AccentPurple
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
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(SmallCardShape)
            .background(backgroundColor)
            .clickable { onClick() }
            .hoverable(interactionSource)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Terminal,
            contentDescription = null,
            tint = AccentCyan,
            modifier = Modifier.size(22.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.description,
                color = if (isSelected || isHovered) BossDarkTextPrimary else BossDarkTextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (result.shortcut != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(AccentCyan.copy(alpha = 0.15f))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Text(
                    text = result.shortcut,
                    fontSize = 10.sp,
                    color = AccentCyan
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
        modifier = Modifier
            .fillMaxWidth()
            .clip(SmallCardShape)
            .background(BossDarkSurface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
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
private fun KeyboardHint(key: String, action: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(BossDarkBackground)
                .padding(horizontal = 7.dp, vertical = 3.dp)
        ) {
            Text(
                text = key,
                fontSize = 10.sp,
                color = BossDarkTextPrimary,
                fontWeight = FontWeight.Medium
            )
        }
        Text(
            text = action,
            fontSize = 10.sp,
            color = BossDarkTextSecondary
        )
    }
}

/**
 * Build an AnnotatedString with match highlights.
 */
private fun highlightMatches(
    text: String,
    matchRanges: List<MatchRange>,
    isHighlighted: Boolean
): AnnotatedString {
    val textColor = if (isHighlighted) BossDarkTextPrimary else BossDarkTextSecondary

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
                withStyle(SpanStyle(color = AccentBlue, fontWeight = FontWeight.Bold)) {
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
