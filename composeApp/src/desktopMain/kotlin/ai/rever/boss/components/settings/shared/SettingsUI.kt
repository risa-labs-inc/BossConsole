package ai.rever.boss.components.settings.shared

import BossDarkAccent
import BossDarkBorder
import BossDarkSurface
import ai.rever.boss.components.settings.shared.SettingsTheme.TextPrimary
import ai.rever.boss.components.settings.shared.SettingsTheme.TextMuted
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

/**
 * Shared UI components for settings screens
 */

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun DropdownSelector(
    label: String,
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { },
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                textColor = TextPrimary,
                focusedBorderColor = BossDarkAccent,
                unfocusedBorderColor = BossDarkBorder,
                focusedLabelColor = BossDarkAccent,
                unfocusedLabelColor = TextMuted,
                trailingIconColor = TextMuted
            )
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(BossDarkSurface)
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    onClick = {
                        onValueChange(option)
                        expanded = false
                    },
                    modifier = Modifier.background(BossDarkSurface)
                ) {
                    Text(
                        text = option,
                        color = TextPrimary
                    )
                }
            }
        }
    }
}
