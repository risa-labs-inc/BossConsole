package ai.rever.boss.components.dialogs

import BossDarkAccent
import BossDarkBackground
import BossDarkBorder
import BossDarkSurface
import BossDarkTextPrimary
import BossDarkTextSecondary
import ai.rever.boss.components.bookmarks.BookmarkCollection
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Star
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Selection mode for collection dialog
 */
enum class CollectionSelectionMode {
    COPY,  // Multi-select for copying to multiple collections
    MOVE   // Single-select for moving to one collection
}

/**
 * Dialog for selecting collections to copy or move bookmarks
 *
 * @param title Dialog title
 * @param collections List of available collections
 * @param excludeCollectionId Collection ID to exclude (source collection)
 * @param mode Selection mode (COPY or MOVE)
 * @param onDismiss Callback when dialog is dismissed
 * @param onConfirm Callback with selected collection IDs
 */
@Composable
fun CollectionSelectionDialog(
    title: String,
    collections: List<BookmarkCollection>,
    excludeCollectionId: String? = null,
    mode: CollectionSelectionMode = CollectionSelectionMode.COPY,
    onDismiss: () -> Unit,
    onConfirm: (selectedCollectionIds: Set<String>) -> Unit
) {
    // Filter out excluded collection
    val availableCollections = collections.filter { it.id != excludeCollectionId }

    // Selected collections state
    var selectedCollections by remember { mutableStateOf<Set<String>>(emptySet()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = true,
            dismissOnBackPress = true
        )
    ) {
        Surface(
            modifier = Modifier
                .width(500.dp)
                .heightIn(max = 600.dp),
            shape = RoundedCornerShape(8.dp),
            color = BossDarkBackground
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                // Title
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = BossDarkTextPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Subtitle
                Text(
                    text = if (mode == CollectionSelectionMode.COPY) {
                        "Select collections to copy bookmark to (multi-select)"
                    } else {
                        "Select a collection to move bookmark to"
                    },
                    fontSize = 13.sp,
                    color = BossDarkTextSecondary
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Collections list
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (availableCollections.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No other collections available",
                                    fontSize = 13.sp,
                                    color = BossDarkTextSecondary,
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                )
                            }
                        }
                    } else {
                        items(availableCollections) { collection ->
                            CollectionSelectionItem(
                                collection = collection,
                                isSelected = selectedCollections.contains(collection.id),
                                mode = mode,
                                onClick = {
                                    selectedCollections = if (mode == CollectionSelectionMode.MOVE) {
                                        // Single selection for MOVE
                                        setOf(collection.id)
                                    } else {
                                        // Multi-selection for COPY
                                        if (selectedCollections.contains(collection.id)) {
                                            selectedCollections - collection.id
                                        } else {
                                            selectedCollections + collection.id
                                        }
                                    }
                                }
                            )
                        }
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
                            contentColor = BossDarkTextSecondary
                        )
                    ) {
                        Text("Cancel")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            onConfirm(selectedCollections)
                            onDismiss()
                        },
                        enabled = selectedCollections.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = BossDarkAccent,
                            contentColor = Color.Black,
                            disabledBackgroundColor = BossDarkBorder,
                            disabledContentColor = BossDarkTextSecondary
                        ),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = if (mode == CollectionSelectionMode.COPY) "Copy" else "Move",
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

/**
 * Individual collection selection item
 */
@Composable
private fun CollectionSelectionItem(
    collection: BookmarkCollection,
    isSelected: Boolean,
    mode: CollectionSelectionMode,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) BossDarkSurface else Color.Transparent,
        border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(1.dp, BossDarkAccent)
        } else {
            androidx.compose.foundation.BorderStroke(1.dp, BossDarkBorder)
        }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Selection indicator
            Icon(
                imageVector = if (mode == CollectionSelectionMode.MOVE) {
                    // Radio button for single selection
                    if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle
                } else {
                    // Checkbox for multi-selection
                    if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked
                },
                contentDescription = if (isSelected) "Selected" else "Not selected",
                tint = if (isSelected) BossDarkAccent else BossDarkTextSecondary,
                modifier = Modifier.size(20.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Collection icon
            Icon(
                imageVector = if (collection.isFavorite) Icons.Outlined.Star else Icons.Outlined.Folder,
                contentDescription = null,
                tint = if (collection.isFavorite) BossDarkAccent else BossDarkTextSecondary,
                modifier = Modifier.size(18.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Collection name
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = collection.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = BossDarkTextPrimary
                )
                Text(
                    text = "${collection.bookmarks.size} bookmarks",
                    fontSize = 12.sp,
                    color = BossDarkTextSecondary
                )
            }
        }
    }
}
