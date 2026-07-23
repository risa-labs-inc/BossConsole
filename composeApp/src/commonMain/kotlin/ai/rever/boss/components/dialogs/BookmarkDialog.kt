package ai.rever.boss.components.dialogs

import ai.rever.boss.components.bookmarks.BookmarkCollection
import ai.rever.boss.components.workspaces.extractPanels
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.min

/**
 * Dialog for selecting collections and workspaces to bookmark a tab into
 *
 * @param tabTitle The title of the tab being bookmarked
 * @param collections List of available bookmark collections
 * @param workspaces List of available workspaces for selection
 * @param onDismiss Callback when dialog is dismissed without action
 * @param onConfirm Callback with selected collection IDs and workspace-panel map
 */
@Composable
fun BookmarkDialog(
    tabTitle: String,
    collections: List<BookmarkCollection>,
    workspaces: List<ai.rever.boss.components.workspaces.LayoutWorkspace>,
    onDismiss: () -> Unit,
    onConfirm: (collectionIds: Set<String>, workspacePanelMap: Map<String, String?>) -> Unit
) {
    // Multi-select state
    // Preselect "Favorites" collection
    val defaultCollection = collections.find { it.isFavorite }?.id ?: collections.firstOrNull()?.id
    var selectedCollections by remember { mutableStateOf(if (defaultCollection != null) setOf(defaultCollection) else emptySet()) }

    // Map of workspace name -> panel ID (null = auto)
    var workspacePanelSelections by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }

    // Workspace section expand/collapse state
    var workspacesSectionExpanded by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = true,
            dismissOnBackPress = true
        )
    ) {
        Surface(
            modifier = Modifier
                .width(700.dp)
                .heightIn(max = 500.dp),
            shape = RoundedCornerShape(8.dp),
            color = BossTheme.colors.panel
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                // Title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Star,
                        contentDescription = "Bookmark",
                        tint = BossTheme.colors.signal,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Add to Bookmarks",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = BossTheme.colors.textPrimary
                        )
                        Text(
                            text = tabTitle,
                            fontSize = 13.sp,
                            color = BossTheme.colors.textSecondary,
                            modifier = Modifier.padding(top = 2.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Scrollable content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    // Section 1: Collections (multi-select pills)
                    Text(
                        text = "Select Collections",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = BossTheme.colors.textPrimary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    if (collections.isEmpty()) {
                        Text(
                            text = "No collections available",
                            fontSize = 13.sp,
                            color = BossTheme.colors.textSecondary
                        )
                    } else {
                        // Simple wrapping layout for pills
                        CollectionPillsLayout(
                            collections = collections,
                            selectedCollections = selectedCollections,
                            onToggleCollection = { collectionId ->
                                selectedCollections = if (selectedCollections.contains(collectionId)) {
                                    selectedCollections - collectionId
                                } else {
                                    selectedCollections + collectionId
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Horizontal divider
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(BossTheme.colors.line)
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Section 2: Workspaces (expandable multi-select pills with panel dropdown)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { workspacesSectionExpanded = !workspacesSectionExpanded }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (workspacesSectionExpanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                            contentDescription = if (workspacesSectionExpanded) "Collapse" else "Expand",
                            modifier = Modifier.size(16.dp),
                            tint = BossTheme.colors.textSecondary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Outlined.WorkOutline,
                            contentDescription = "Workspaces",
                            modifier = Modifier.size(16.dp),
                            tint = BossTheme.colors.textSecondary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Open In Workspaces",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = BossTheme.colors.textPrimary
                        )
                    }

                    if (workspacesSectionExpanded) {
                        Spacer(modifier = Modifier.height(8.dp))

                        if (workspaces.isEmpty()) {
                            Text(
                                text = "No workspaces available",
                                fontSize = 13.sp,
                                color = BossTheme.colors.textSecondary
                            )
                        } else {
                            // Workspace pills with inline panel selection
                            WorkspacePillsLayout(
                                workspaces = workspaces,
                                workspacePanelSelections = workspacePanelSelections,
                                onToggleWorkspace = { workspaceName, panelId ->
                                    workspacePanelSelections = if (workspacePanelSelections.containsKey(workspaceName)) {
                                        workspacePanelSelections - workspaceName
                                    } else {
                                        workspacePanelSelections + (workspaceName to panelId)
                                    }
                                },
                                onUpdatePanel = { workspaceName, panelId ->
                                    workspacePanelSelections = workspacePanelSelections + (workspaceName to panelId)
                                }
                            )
                        }

                        Text(
                            text = "Leave empty to use current workspace",
                            fontSize = 11.sp,
                            color = BossTheme.colors.textSecondary,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = BossTheme.colors.textSecondary
                        )
                    ) {
                        Text("Cancel")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = { onConfirm(selectedCollections, workspacePanelSelections) },
                        enabled = selectedCollections.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = BossTheme.colors.signal,
                            contentColor = BossTheme.colors.onSignal,
                            disabledBackgroundColor = BossTheme.colors.line,
                            disabledContentColor = BossTheme.colors.textSecondary
                        ),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Add Bookmark", fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/**
 * Multi-select collection pills layout
 */
@Composable
private fun CollectionPillsLayout(
    collections: List<BookmarkCollection>,
    selectedCollections: Set<String>,
    onToggleCollection: (String) -> Unit
) {
    // Simple wrapping flow layout using Row with Modifier.weight
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        collections.chunked(3).forEach { rowCollections ->
            Column(modifier = Modifier.weight(1f)) {
                rowCollections.forEach { collection ->
                    CollectionPill(
                        collection = collection,
                        isSelected = selectedCollections.contains(collection.id),
                        onClick = { onToggleCollection(collection.id) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

/**
 * Individual collection pill
 */
@Composable
private fun CollectionPill(
    collection: BookmarkCollection,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) BossTheme.colors.signal else BossTheme.colors.raised,
        border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, BossTheme.colors.line)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (collection.isFavorite) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = "Favorite",
                    modifier = Modifier.size(14.dp),
                    tint = if (isSelected) BossTheme.colors.onSignal else BossTheme.colors.signal
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = collection.name,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) BossTheme.colors.onSignal else BossTheme.colors.textPrimary
            )
        }
    }
}

/**
 * Multi-select workspace pills layout with inline panel dropdown
 */
@Composable
private fun WorkspacePillsLayout(
    workspaces: List<ai.rever.boss.components.workspaces.LayoutWorkspace>,
    workspacePanelSelections: Map<String, String?>,
    onToggleWorkspace: (workspaceName: String, panelId: String?) -> Unit,
    onUpdatePanel: (workspaceName: String, panelId: String?) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        workspaces.forEach { workspace ->
            val isSelected = workspacePanelSelections.containsKey(workspace.name)
            val selectedPanelId = workspacePanelSelections[workspace.name]

            WorkspacePill(
                workspace = workspace,
                isSelected = isSelected,
                selectedPanelId = selectedPanelId,
                onToggle = { onToggleWorkspace(workspace.name, null) },
                onPanelSelected = { panelId -> onUpdatePanel(workspace.name, panelId) }
            )
        }
    }
}

/**
 * Individual workspace pill with inline panel dropdown
 */
@Composable
private fun WorkspacePill(
    workspace: ai.rever.boss.components.workspaces.LayoutWorkspace,
    isSelected: Boolean,
    selectedPanelId: String?,
    onToggle: () -> Unit,
    onPanelSelected: (String?) -> Unit
) {
    var showPanelDropdown by remember { mutableStateOf(false) }
    val panels = workspace.layout.extractPanels()

    // Get panel display name
    val panelDisplayName = if (selectedPanelId == null) {
        "Auto"
    } else {
        panels.find { it.first == selectedPanelId }?.second ?: "Auto"
    }

    Column {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle),
            shape = RoundedCornerShape(16.dp),
            color = if (isSelected) BossTheme.colors.signal else BossTheme.colors.raised,
            border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, BossTheme.colors.line)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (isSelected && selectedPanelId != null) {
                        "${workspace.name}: $panelDisplayName"
                    } else {
                        workspace.name
                    },
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) BossTheme.colors.onSignal else BossTheme.colors.textPrimary
                )

                if (isSelected) {
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = { showPanelDropdown = !showPanelDropdown },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ArrowDropDown,
                            contentDescription = "Select Panel",
                            tint = BossTheme.colors.onSignal
                        )
                    }
                }
            }
        }

        // Inline panel dropdown
        if (isSelected && showPanelDropdown) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 4.dp),
                shape = RoundedCornerShape(8.dp),
                color = BossTheme.colors.raised,
                border = androidx.compose.foundation.BorderStroke(1.dp, BossTheme.colors.line)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    // Auto option
                    PanelOption(
                        displayName = "Auto (active panel)",
                        isSelected = selectedPanelId == null,
                        onClick = {
                            onPanelSelected(null)
                            showPanelDropdown = false
                        }
                    )

                    // Panel options
                    panels.forEach { (panelId, panelLabel) ->
                        PanelOption(
                            displayName = panelLabel,
                            isSelected = selectedPanelId == panelId,
                            onClick = {
                                onPanelSelected(panelId)
                                showPanelDropdown = false
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Individual panel option in dropdown
 */
@Composable
private fun PanelOption(
    displayName: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                color = if (isSelected) BossTheme.colors.raised.copy(alpha = 0.6f) else Color.Transparent,
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = displayName,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
            color = if (isSelected) BossTheme.colors.signal else BossTheme.colors.textPrimary
        )
    }
}
