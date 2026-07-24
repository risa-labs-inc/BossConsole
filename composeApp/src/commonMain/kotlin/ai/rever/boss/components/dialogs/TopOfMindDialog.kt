package ai.rever.boss.components.dialogs

import ai.rever.boss.components.common.rememberFaviconLoader
import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.workspaces.WorkspaceManager
import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.topofmind.ActiveTab
import ai.rever.boss.topofmind.TopOfMindStateHolder
import ai.rever.boss.utils.WindowFocusManager
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val topOfMindLogger = BossLogger.forComponent("TopOfMindDialog")

@Composable
fun TopOfMindDialog(
    splitViewState: SplitViewState? = null, // Kept for backward compatibility but not used
    workspaceManager: WorkspaceManager,
    onDismiss: () -> Unit,
    onTabSelect: (ActiveTab) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    val activeTabs by TopOfMindStateHolder.activeTabs.collectAsState()
    var selectedIndex by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Helper function to handle tab selection
    fun handleTabSelect(activeTab: ActiveTab) {
        topOfMindLogger.debug(LogCategory.UI, "Selecting tab", mapOf("title" to activeTab.tabInfo.title, "windowId" to activeTab.windowId))
        // Don't try to focus here - let BossApp handle focus restoration after dialog closes
        onTabSelect(activeTab)
    }

    // Update active tabs when dialog opens and refresh periodically
    // Collects tabs from ALL open windows using the global registry
    LaunchedEffect(Unit) {
        // Immediate update when dialog opens
        TabCollector.refreshGlobalState(workspaceManager)

        // Periodic refresh to ensure dialog has latest data
        while (true) {
            delay(1000) // Check every second while dialog is open
            val currentTabs = TabCollector.collectAllTabs(workspaceManager)
            val existingTabs = TopOfMindStateHolder.activeTabs.value

            if (currentTabs != existingTabs) {
                TopOfMindStateHolder.updateActiveTabs(currentTabs)
            }
        }
    }

    // Filter tabs based on search query
    val filteredTabs =
        if (searchQuery.isBlank()) {
            activeTabs
        } else {
            activeTabs.filter { tab ->
                val tabInfo = tab.tabInfo
                tabInfo.title.contains(searchQuery, ignoreCase = true) ||
                    // Only check URL for Fluck tabs that have URL property
                    ((tabInfo as? FluckTabInfo)?.url?.contains(searchQuery, ignoreCase = true) == true) ||
                    tab.workspaceName.contains(searchQuery, ignoreCase = true)
            }
        }

    // Handle keyboard navigation
    LaunchedEffect(selectedIndex, filteredTabs.size) {
        if (filteredTabs.isNotEmpty()) {
            val clampedIndex = selectedIndex.coerceIn(0, filteredTabs.size - 1)
            if (clampedIndex != selectedIndex) {
                selectedIndex = clampedIndex
            }
            coroutineScope.launch {
                listState.animateScrollToItem(selectedIndex)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            ),
    ) {
        Surface(
            modifier =
                Modifier
                    .width(600.dp)
                    .height(500.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            when (event.key) {
                                Key.Escape -> {
                                    onDismiss()
                                    true
                                }

                                Key.DirectionUp -> {
                                    if (filteredTabs.isNotEmpty()) {
                                        selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                                    }
                                    true
                                }

                                Key.DirectionDown -> {
                                    if (filteredTabs.isNotEmpty()) {
                                        selectedIndex = (selectedIndex + 1).coerceAtMost(filteredTabs.size - 1)
                                    }
                                    true
                                }

                                Key.Enter -> {
                                    if (filteredTabs.isNotEmpty() && selectedIndex < filteredTabs.size) {
                                        handleTabSelect(filteredTabs[selectedIndex])
                                    }
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
            shape = RoundedCornerShape(8.dp),
            color = BossTheme.colors.panel,
            elevation = 8.dp,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp),
            ) {
                // Title
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Language,
                        contentDescription = "Browser tabs",
                        tint = BossTheme.colors.textPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Top of mind",
                        color = BossTheme.colors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        "${filteredTabs.size} tab${if (filteredTabs.size != 1) "s" else ""}",
                        color = BossTheme.colors.textSecondary,
                        fontSize = 12.sp,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Search bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        selectedIndex = 0 // Reset selection when searching
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            "Search tabs by title, type, or workspace...",
                            color = BossTheme.colors.textSecondary,
                            fontSize = 14.sp,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Search,
                            contentDescription = "Search",
                            tint = BossTheme.colors.textSecondary,
                        )
                    },
                    colors =
                        TextFieldDefaults.outlinedTextFieldColors(
                            backgroundColor = BossTheme.colors.raised,
                            focusedBorderColor = BossTheme.colors.line,
                            unfocusedBorderColor = BossTheme.colors.line,
                            textColor = BossTheme.colors.textPrimary,
                            cursorColor = BossTheme.colors.textPrimary,
                        ),
                    singleLine = true,
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Tabs list
                if (filteredTabs.isEmpty()) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (searchQuery.isBlank()) "No active tabs found" else "No tabs matching \"$searchQuery\"",
                            color = BossTheme.colors.textSecondary,
                            fontSize = 14.sp,
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(filteredTabs.size) { index ->
                            val activeTab = filteredTabs[index]
                            val isSelected = index == selectedIndex

                            ActiveTabDialogItem(
                                activeTab = activeTab,
                                isSelected = isSelected,
                                onTabClick = { handleTabSelect(activeTab) },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Instructions
                Text(
                    "↑↓ to navigate • Enter to select • Esc to close",
                    color = BossTheme.colors.textSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

@Composable
private fun ActiveTabDialogItem(
    activeTab: ActiveTab,
    isSelected: Boolean,
    onTabClick: () -> Unit,
) {
    // Load favicon using shared composable (with error handling and caching)
    val loadedFavicon = rememberFaviconLoader(activeTab.tabInfo)

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable { onTabClick() },
        color = if (isSelected) BossTheme.colors.signal.copy(alpha = 0.3f) else BossTheme.colors.raised,
        elevation = if (isSelected) 2.dp else 0.dp,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Display favicon if available, otherwise fallback icon
            loadedFavicon?.let { favicon ->
                // Display actual favicon (safe call, no !!)
                Image(
                    painter = favicon.asPainter(),
                    contentDescription = "Tab icon",
                    modifier = Modifier.size(16.dp),
                )
            } ?: run {
                // Fallback to appropriate vector icon based on tab type
                val fallbackIcon =
                    when (activeTab.tabInfo) {
                        is FluckTabInfo -> Icons.Outlined.Language

                        // Browser tabs
                        else -> Icons.Outlined.Tab // Other tab types
                    }
                Icon(
                    fallbackIcon,
                    contentDescription = "Tab icon",
                    tint = if (isSelected) BossTheme.colors.textPrimary else BossTheme.colors.textSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
            ) {
                // Tab title
                Text(
                    text = activeTab.tabInfo.title,
                    fontSize = 14.sp,
                    color = if (isSelected) BossTheme.colors.textPrimary else BossTheme.colors.textPrimary,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // URL or tab type info
                val secondaryText =
                    when (val tabInfo = activeTab.tabInfo) {
                        is FluckTabInfo -> tabInfo.url
                        else -> tabInfo.typeId.typeId // Show tab type for non-browser tabs
                    }

                if (secondaryText.isNotEmpty()) {
                    Text(
                        text = secondaryText,
                        fontSize = 12.sp,
                        color =
                            if (isSelected) {
                                BossTheme.colors.textSecondary.copy(alpha = 0.9f)
                            } else {
                                BossTheme.colors.textSecondary
                            },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Workspace badge
            Surface(
                color = BossTheme.colors.line,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = activeTab.workspaceName,
                    fontSize = 10.sp,
                    color = BossTheme.colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}
