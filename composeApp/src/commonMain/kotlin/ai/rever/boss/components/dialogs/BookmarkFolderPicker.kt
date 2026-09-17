package ai.rever.boss.components.dialogs

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun BookmarkCollectionPicker(
    collections: List<Pair<String, String>>,
    selectedId: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Folder", style = MaterialTheme.typography.caption, color = BossTheme.colors.textSecondary)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                enabled = enabled,
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = BossTheme.colors.textPrimary),
            ) {
                val selectedName = collections.find { it.first == selectedId }?.second
                Text(
                    selectedName ?: "Choose folder",
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Choose folder")
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                collections.forEach { (id, name) ->
                    DropdownMenuItem(onClick = {
                        onSelect(id)
                        expanded = false
                    }) { Text(name) }
                }
            }
        }
        TextButton(enabled = enabled, onClick = onCreate) { Text("New folder") }
    }
}
