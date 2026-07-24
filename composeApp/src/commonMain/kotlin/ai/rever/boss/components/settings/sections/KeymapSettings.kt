package ai.rever.boss.components.settings.sections

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun KeymapSettings() {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<ShortcutCategory?>(null) }

    val shortcuts = remember { getKeyboardShortcuts() }

    val filteredShortcuts = shortcuts.filter { shortcut ->
        val matchesSearch = searchQuery.isEmpty() ||
            shortcut.action.contains(searchQuery, ignoreCase = true) ||
            shortcut.description.contains(searchQuery, ignoreCase = true) ||
            shortcut.key.contains(searchQuery, ignoreCase = true)

        val matchesCategory = selectedCategory == null || shortcut.category == selectedCategory

        matchesSearch && matchesCategory
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Keyboard Shortcuts",
            style = MaterialTheme.typography.h5,
            fontWeight = FontWeight.Bold,
            color = BossTheme.colors.textPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Reference for all available keyboard shortcuts in BOSS Console",
            style = MaterialTheme.typography.body2,
            color = BossTheme.colors.textSecondary
        )

        Spacer(modifier = Modifier.height(24.dp))

        TextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search shortcuts...", color = BossTheme.colors.textSecondary) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = BossTheme.colors.textSecondary) },
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.textFieldColors(
                backgroundColor = BossTheme.colors.signal.copy(alpha = 0.1f),
                textColor = BossTheme.colors.textPrimary,
                cursorColor = BossTheme.colors.signal,
                focusedIndicatorColor = BossTheme.colors.signal,
                unfocusedIndicatorColor = Color.Transparent
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChipButton(
                text = "All",
                selected = selectedCategory == null,
                onClick = { selectedCategory = null }
            )

            ShortcutCategory.entries.forEach { category ->
                FilterChipButton(
                    text = category.displayName,
                    selected = selectedCategory == category,
                    onClick = { selectedCategory = category }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (filteredShortcuts.isEmpty()) {
            Text(
                text = "No shortcuts found matching your search.",
                color = BossTheme.colors.textSecondary,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            filteredShortcuts
                .groupBy { it.category }
                .forEach { (category, shortcuts) ->
                    CategorySection(
                        category = category,
                        shortcuts = shortcuts
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
        }
    }
}

@Composable
private fun FilterChipButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            backgroundColor = if (selected) BossTheme.colors.signal else BossTheme.colors.signal.copy(alpha = 0.1f),
            contentColor = if (selected) BossTheme.colors.textPrimary else BossTheme.colors.textSecondary
        ),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        elevation = ButtonDefaults.elevation(0.dp, 0.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.caption,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
        )
    }
}

@Composable
private fun CategorySection(
    category: ShortcutCategory,
    shortcuts: List<KeyboardShortcut>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = BossTheme.colors.panel,
        elevation = 0.dp,
        border = BorderStroke(1.dp, BossTheme.colors.signal.copy(alpha = 0.2f)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = category.displayName,
                style = MaterialTheme.typography.subtitle1,
                fontWeight = FontWeight.Bold,
                color = BossTheme.colors.textPrimary
            )

            Spacer(modifier = Modifier.height(12.dp))

            shortcuts.forEach { shortcut ->
                ShortcutRow(shortcut)
                if (shortcut != shortcuts.last()) {
                    Divider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = BossTheme.colors.signal.copy(alpha = 0.1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ShortcutRow(shortcut: KeyboardShortcut) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = shortcut.action,
                style = MaterialTheme.typography.body1,
                color = BossTheme.colors.textPrimary
            )
            Text(
                text = shortcut.description,
                style = MaterialTheme.typography.caption,
                color = BossTheme.colors.textSecondary
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        ShortcutKeysDisplay(
            key = shortcut.key,
            modifiers = shortcut.modifiers
        )
    }
}

@Composable
private fun ShortcutKeysDisplay(
    key: String,
    modifiers: List<String>
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        modifiers.forEach { modifier ->
            KeyCap(text = getModifierSymbol(modifier))
        }
        KeyCap(text = key)
    }
}

@Composable
private fun KeyCap(text: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = BossTheme.colors.signal.copy(alpha = 0.2f),
        border = BorderStroke(1.dp, BossTheme.colors.signal.copy(alpha = 0.3f))
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.body2,
            fontFamily = FontFamily.Monospace,
            color = BossTheme.colors.textPrimary
        )
    }
}
