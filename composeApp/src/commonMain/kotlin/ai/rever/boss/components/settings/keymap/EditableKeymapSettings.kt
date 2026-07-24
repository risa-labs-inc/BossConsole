package ai.rever.boss.components.settings.keymap

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.rever.boss.keymap.KeymapSettingsManager
import ai.rever.boss.keymap.handler.KeymapValidator
import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.model.TabSwitchMode
import ai.rever.boss.keymap.lifecycle.ShortcutLifecycleManager
import kotlinx.coroutines.launch

/**
 * Main editable keyboard shortcuts settings screen.
 * Allows users to view, search, edit, and customize keyboard shortcuts.
 */
@Composable
fun EditableKeymapSettings() {
    val keymapSettings by KeymapSettingsManager.currentSettings.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var editingBinding by remember { mutableStateOf<KeyBinding?>(null) }
    var showTestDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Compute conflicts
    val conflicts = remember(keymapSettings) {
        KeymapValidator.validate(keymapSettings)
    }

    // Plugin-contributed shortcuts (PluginShortcutRegistry) not yet overridden
    // by the user, shown as synthetic "Plugins" rows. Rebinding one persists a
    // real entry under its actionId (withBinding), which then supersedes the
    // plugin default everywhere — including the interceptor.
    val registeredPluginShortcuts by ai.rever.boss.components.plugin.registries.PluginShortcutRegistryImpl
        .shortcuts.collectAsState()
    val pluginDefaultRows = remember(registeredPluginShortcuts, keymapSettings) {
        registeredPluginShortcuts
            .filter { it.spec.actionId !in keymapSettings.shortcuts }
            .map { registered ->
                val spec = registered.spec
                KeyBinding(
                    actionId = spec.actionId,
                    key = spec.defaultBinding?.key ?: "",
                    modifiers = spec.defaultBinding?.modifiers?.toList() ?: emptyList(),
                    context = ai.rever.boss.keymap.model.ShortcutContext.GLOBAL,
                    enabled = spec.defaultBinding != null,
                    category = "Plugins",
                    description = spec.displayName + (spec.description.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
                )
            }
    }

    // Filter shortcuts based on search and category
    val filteredShortcuts = remember(keymapSettings, pluginDefaultRows, searchQuery, selectedCategory) {
        val shortcuts = keymapSettings.shortcuts.values.toList() + pluginDefaultRows
        shortcuts.filter { binding ->
            val matchesSearch = searchQuery.isBlank() ||
                    binding.description.contains(searchQuery, ignoreCase = true) ||
                    binding.displayString().contains(searchQuery, ignoreCase = true) ||
                    binding.actionId.contains(searchQuery, ignoreCase = true)

            val matchesCategory = selectedCategory == "All" || binding.category == selectedCategory

            matchesSearch && matchesCategory
        }.sortedBy { it.category + it.description }
    }

    // Group by category for display
    val groupedShortcuts = filteredShortcuts.groupBy { it.category }

    // Get all available categories
    val allCategories = remember(keymapSettings, pluginDefaultRows) {
        listOf("All") + (keymapSettings.shortcuts.values.map { it.category } + pluginDefaultRows.map { it.category })
            .distinct().sorted()
    }

    // Make entire content scrollable by putting everything in LazyColumn
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Summary row with shortcuts count and conflict badge
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${keymapSettings.shortcuts.size} shortcuts configured",
                    fontSize = 13.sp,
                    color = BossTheme.colors.textSecondary
                )
                if (conflicts.isNotEmpty()) {
                    ConflictWarningBadge(
                        conflicts = conflicts.flatMap { it.bindings }
                    )
                }
            }
        }

        // Preset Selector
        item {
            PresetSelector(
                currentSettings = keymapSettings,
                onPresetSelected = { presetName ->
                    KeymapSettingsManager.loadPreset(presetName)
                },
                onResetToDefault = {
                    KeymapSettingsManager.resetToDefault()
                }
            )
        }

        // Import/Export
        item {
            KeymapImportExport(
                onImport = { jsonString ->
                    val result = KeymapSettingsManager.importFromJson(jsonString)
                    result != null
                }
            )
        }

        // Test All Shortcuts button
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(BossTheme.colors.panel)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Test Shortcuts",
                        color = BossTheme.colors.textPrimary,
                        fontSize = 13.sp
                    )
                    Text(
                        text = "Verify all shortcuts are working correctly",
                        color = BossTheme.colors.textSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                TextButton(
                    onClick = { showTestDialog = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = BossTheme.colors.signal)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Test All", fontSize = 13.sp)
                }
            }
        }

        // Tab switching behavior (Ctrl+Tab)
        item {
            TabSwitchModeSelector(
                selected = keymapSettings.tabSwitchMode,
                onSelect = { mode ->
                    if (mode != keymapSettings.tabSwitchMode) {
                        coroutineScope.launch {
                            KeymapSettingsManager.updateSettings(
                                keymapSettings.copy(tabSwitchMode = mode)
                            )
                        }
                    }
                }
            )
        }

        item {
            Divider(color = BossTheme.colors.line)
        }

        // Search and filter
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(BossTheme.colors.panel)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Search field
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(BossTheme.colors.ink)
                        .border(1.dp, BossTheme.colors.line, RoundedCornerShape(4.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
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
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f),
                        textStyle = TextStyle(
                            color = BossTheme.colors.textPrimary,
                            fontSize = 13.sp
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(BossTheme.colors.signal),
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Search shortcuts...",
                                    color = BossTheme.colors.textSecondary,
                                    fontSize = 13.sp
                                )
                            }
                            innerTextField()
                        }
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Category filter dropdown
                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(BossTheme.colors.ink)
                            .border(1.dp, BossTheme.colors.line, RoundedCornerShape(4.dp))
                            .clickable { categoryMenuExpanded = true }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (selectedCategory == "All") "All Categories" else selectedCategory,
                            color = BossTheme.colors.textPrimary,
                            fontSize = 13.sp
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Expand",
                            tint = BossTheme.colors.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = categoryMenuExpanded,
                        onDismissRequest = { categoryMenuExpanded = false },
                        modifier = Modifier.background(BossTheme.colors.panel)
                    ) {
                        allCategories.forEach { category ->
                            DropdownMenuItem(
                                onClick = {
                                    selectedCategory = category
                                    categoryMenuExpanded = false
                                }
                            ) {
                                Text(
                                    text = if (category == "All") "All Categories" else category,
                                    color = if (category == selectedCategory) BossTheme.colors.signal
                                    else BossTheme.colors.textPrimary,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // Category headers and shortcuts list
            groupedShortcuts.forEach { (category, shortcuts) ->
                item {
                    CategoryHeader(category = category, count = shortcuts.size)
                }

                items(shortcuts) { binding ->
                    // Find conflicts for this binding
                    val bindingConflicts = KeymapValidator.checkBinding(
                        binding,
                        keymapSettings,
                        excludeActionId = binding.actionId
                    )

                    ShortcutItem(
                        binding = binding,
                        hasConflict = bindingConflicts.isNotEmpty(),
                        conflictingBindings = bindingConflicts,
                        onEdit = { editingBinding = binding }
                    )
                }
            }

            // Empty state
            if (filteredShortcuts.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "No shortcuts found",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = BossTheme.colors.textSecondary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Try a different search term",
                                fontSize = 13.sp,
                                color = BossTheme.colors.textSecondary.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }

    // Edit dialog
    editingBinding?.let { binding ->
        KeyCaptureDialog(
            actionId = binding.actionId,
            actionDescription = binding.description,
            context = binding.context,
            category = binding.category,
            currentBinding = binding,
            onKeyCaptured = { newBinding ->
                coroutineScope.launch {
                    val updatedSettings = keymapSettings.withBinding(newBinding)
                    KeymapSettingsManager.updateSettings(updatedSettings)
                    editingBinding = null
                }
            },
            onDismiss = { editingBinding = null }
        )
    }

    // Test dialog
    if (showTestDialog) {
        ShortcutTestDialog(
            keymapSettings = keymapSettings,
            onDismiss = { showTestDialog = false }
        )
    }
}

/**
 * Category header for grouping shortcuts.
 */
@Composable
private fun TabSwitchModeSelector(
    selected: TabSwitchMode,
    onSelect: (TabSwitchMode) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(BossTheme.colors.panel)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column {
            Text(
                text = "Tab switching (Ctrl+Tab)",
                color = BossTheme.colors.textPrimary,
                fontSize = 13.sp
            )
            Text(
                text = "How Ctrl+Tab and Ctrl+Shift+Tab move between tabs in the active panel",
                color = BossTheme.colors.textSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TabSwitchModeChip(
                label = "Positional",
                description = "Next / previous in tab order",
                isSelected = selected == TabSwitchMode.POSITIONAL,
                onClick = { onSelect(TabSwitchMode.POSITIONAL) },
                modifier = Modifier.weight(1f)
            )
            TabSwitchModeChip(
                label = "Most recently used",
                description = "Alt+Tab style, commit on release",
                isSelected = selected == TabSwitchMode.MRU,
                onClick = { onSelect(TabSwitchMode.MRU) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TabSwitchModeChip(
    label: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (isSelected) BossTheme.colors.signal.copy(alpha = 0.15f) else BossTheme.colors.ink
            )
            .border(
                width = 1.dp,
                color = if (isSelected) BossTheme.colors.signal else BossTheme.colors.line,
                shape = RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            color = if (isSelected) BossTheme.colors.signal else BossTheme.colors.textPrimary,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
        )
        Text(
            text = description,
            color = BossTheme.colors.textSecondary,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun CategoryHeader(category: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = category,
            style = MaterialTheme.typography.subtitle1,
            fontWeight = FontWeight.Bold,
            color = BossTheme.colors.signal
        )
        Text(
            text = "$count shortcut${if (count != 1) "s" else ""}",
            style = MaterialTheme.typography.caption,
            color = BossTheme.colors.textSecondary
        )
    }
}

/**
 * Individual shortcut item.
 */
@Composable
private fun ShortcutItem(
    binding: KeyBinding,
    hasConflict: Boolean,
    conflictingBindings: List<KeyBinding>,
    onEdit: () -> Unit
) {
    // Get lifecycle state for this shortcut
    val lifecycleStates by ShortcutLifecycleManager.states.collectAsState()
    val lifecycleState = lifecycleStates[binding.actionId]

    val borderColor = if (hasConflict) BossTheme.colors.alert.copy(alpha = 0.5f) else BossTheme.colors.line
    val backgroundColor = if (hasConflict) BossTheme.colors.alert.copy(alpha = 0.05f) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left side: description and context
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = binding.description,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = BossTheme.colors.textPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = binding.context.displayName,
                    fontSize = 12.sp,
                    color = BossTheme.colors.textSecondary
                )
                if (!binding.enabled) {
                    Text(
                        text = "• DISABLED",
                        fontSize = 12.sp,
                        color = BossTheme.colors.alert
                    )
                }
                // Show lifecycle state
                if (lifecycleState != null && !lifecycleState.enabled) {
                    Text(
                        text = "• ${lifecycleState.reason ?: "Unavailable"}",
                        fontSize = 12.sp,
                        color = BossTheme.colors.textSecondary
                    )
                }
            }
        }

        // Right side: key display, conflict badge, and edit button
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Conflict badge
            if (hasConflict) {
                ConflictWarningBadge(conflicts = conflictingBindings)
            }

            // Key display
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(BossTheme.colors.signal.copy(alpha = 0.2f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = binding.displayString(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = BossTheme.colors.signal
                )
            }

            // Edit button
            IconButton(
                onClick = onEdit,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit shortcut",
                    tint = BossTheme.colors.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
