package ai.rever.boss.components.dialogs

import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Dialog showing all keyboard shortcuts grouped by category.
 * Includes search functionality and a link to customize shortcuts in Settings.
 *
 * @param keymapSettings Current keymap settings containing all shortcuts
 * @param onDismiss Callback when dialog is dismissed
 * @param onOpenSettings Callback to open keyboard shortcuts settings
 */
@Composable
fun ShortcutHelpDialog(
    keymapSettings: KeymapSettings,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    // Group shortcuts by category and filter by search
    val groupedShortcuts = remember(keymapSettings, searchQuery) {
        val allBindings = keymapSettings.shortcuts.values.filter { it.enabled }

        val filtered = if (searchQuery.isBlank()) {
            allBindings
        } else {
            allBindings.filter { binding ->
                binding.description.contains(searchQuery, ignoreCase = true) ||
                binding.actionId.contains(searchQuery, ignoreCase = true) ||
                binding.displayString().contains(searchQuery, ignoreCase = true) ||
                binding.category.contains(searchQuery, ignoreCase = true)
            }
        }

        filtered.groupBy { it.category }.toSortedMap()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = true,
            dismissOnBackPress = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .width(600.dp)
                .heightIn(max = 700.dp),
            shape = RoundedCornerShape(12.dp),
            color = BossTheme.colors.panel
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                // Header with title and close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Keyboard Shortcuts",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = BossTheme.colors.textPrimary
                    )

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = BossTheme.colors.textSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Search bar
                SearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Shortcuts list
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (groupedShortcuts.isEmpty()) {
                        item {
                            Text(
                                text = if (searchQuery.isNotBlank()) "No shortcuts match \"$searchQuery\"" else "No shortcuts configured",
                                fontSize = 14.sp,
                                color = BossTheme.colors.textSecondary,
                                modifier = Modifier.padding(vertical = 16.dp)
                            )
                        }
                    } else {
                        groupedShortcuts.forEach { (category, bindings) ->
                            item(key = "header_$category") {
                                CategoryHeader(category)
                            }

                            items(
                                items = bindings,
                                key = { it.actionId }
                            ) { binding ->
                                ShortcutRow(binding)
                            }

                            item(key = "spacer_$category") {
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Footer with settings button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Press ? to show this dialog",
                        fontSize = 12.sp,
                        color = BossTheme.colors.textSecondary
                    )

                    TextButton(
                        onClick = {
                            onDismiss()
                            onOpenSettings()
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = BossTheme.colors.signal
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Customize Shortcuts")
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(
                color = BossTheme.colors.raised,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = "Search",
            tint = BossTheme.colors.textSecondary,
            modifier = Modifier.size(18.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            textStyle = TextStyle(
                color = BossTheme.colors.textPrimary,
                fontSize = 14.sp
            ),
            cursorBrush = SolidColor(BossTheme.colors.textPrimary),
            singleLine = true,
            modifier = Modifier.weight(1f),
            decorationBox = { innerTextField ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            text = "Search shortcuts...",
                            color = BossTheme.colors.textSecondary,
                            fontSize = 14.sp
                        )
                    }
                    innerTextField()
                }
            }
        )

        if (query.isNotEmpty()) {
            IconButton(
                onClick = { onQueryChange("") },
                modifier = Modifier.size(18.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Clear search",
                    tint = BossTheme.colors.textSecondary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun CategoryHeader(category: String) {
    Text(
        text = category,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = BossTheme.colors.signal,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun ShortcutRow(binding: KeyBinding) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = BossTheme.colors.raised.copy(alpha = 0.5f),
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = binding.description.ifEmpty { binding.actionId },
                fontSize = 14.sp,
                color = BossTheme.colors.textPrimary
            )
            if (binding.context.displayName != "Global") {
                Text(
                    text = "Context: ${binding.context.displayName}",
                    fontSize = 11.sp,
                    color = BossTheme.colors.textSecondary
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Shortcut display
        ShortcutBadge(binding.displayString())
    }
}

@Composable
private fun ShortcutBadge(shortcut: String) {
    Surface(
        color = BossTheme.colors.panel,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = shortcut,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = BossTheme.colors.textPrimary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
