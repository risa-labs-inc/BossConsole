package ai.rever.boss.components.settings.keymap

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.presets.KeymapPresets
import kotlinx.coroutines.launch

/**
 * Preset selector component for choosing keyboard shortcut presets.
 */
@Composable
fun PresetSelector(
    currentSettings: KeymapSettings,
    onPresetSelected: suspend (String) -> Unit,
    onResetToDefault: suspend () -> Unit,
    modifier: Modifier = Modifier
) {
    var showPresetMenu by remember { mutableStateOf(false) }
    var showResetConfirmation by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Preset selector row (matching SettingsDropdown pattern)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(BossTheme.colors.panel)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Keymap Preset",
                    color = BossTheme.colors.textPrimary,
                    fontSize = 13.sp
                )
                Text(
                    text = getPresetDescription(currentSettings.presetName),
                    color = BossTheme.colors.textSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Box {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(BossTheme.colors.ink)
                        .border(1.dp, BossTheme.colors.line, RoundedCornerShape(4.dp))
                        .clickable { showPresetMenu = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = currentSettings.presetName,
                        color = BossTheme.colors.textPrimary,
                        fontSize = 13.sp
                    )
                    if (currentSettings.customized) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(BossTheme.colors.signal.copy(alpha = 0.2f))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Modified",
                                fontSize = 10.sp,
                                color = BossTheme.colors.signal
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Select preset",
                        tint = BossTheme.colors.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Reset button row (matching SettingsButtonRow pattern)
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
                    text = "Reset Shortcuts",
                    color = BossTheme.colors.textPrimary,
                    fontSize = 13.sp
                )
                Text(
                    text = "Restore all shortcuts to default preset",
                    color = BossTheme.colors.textSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            TextButton(
                onClick = { showResetConfirmation = true },
                colors = ButtonDefaults.textButtonColors(contentColor = BossTheme.colors.signal)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Reset to default",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Reset", fontSize = 13.sp)
            }
        }

    }

    // Preset menu dialog
    if (showPresetMenu) {
        PresetMenuDialog(
            currentPreset = currentSettings.presetName,
            onPresetSelected = { presetName ->
                showPresetMenu = false
                coroutineScope.launch {
                    onPresetSelected(presetName)
                }
            },
            onDismiss = { showPresetMenu = false }
        )
    }

    // Reset confirmation dialog
    if (showResetConfirmation) {
        ResetConfirmationDialog(
            onConfirm = {
                showResetConfirmation = false
                coroutineScope.launch {
                    onResetToDefault()
                }
            },
            onDismiss = { showResetConfirmation = false }
        )
    }
}

/**
 * Dialog showing available presets.
 */
@Composable
private fun PresetMenuDialog(
    currentPreset: String,
    onPresetSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val presets = KeymapPresets.getAvailablePresets()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(450.dp),
            shape = RoundedCornerShape(12.dp),
            color = BossTheme.colors.panel,
            elevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Select Keymap Preset",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BossTheme.colors.textPrimary
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Choose a predefined keyboard shortcut scheme",
                    fontSize = 13.sp,
                    color = BossTheme.colors.textSecondary
                )

                Spacer(modifier = Modifier.height(16.dp))

                presets.forEach { presetName ->
                    PresetMenuItem(
                        presetName = presetName,
                        isSelected = presetName == currentPreset,
                        onClick = { onPresetSelected(presetName) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = BossTheme.colors.textSecondary, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/**
 * Individual preset menu item.
 */
@Composable
private fun PresetMenuItem(
    presetName: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(BossTheme.colors.ink)
            .border(
                width = 1.dp,
                color = if (isSelected) BossTheme.colors.signal.copy(alpha = 0.5f) else BossTheme.colors.line,
                shape = RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = presetName,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) BossTheme.colors.signal else BossTheme.colors.textPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = getPresetDescription(presetName),
                fontSize = 11.sp,
                color = BossTheme.colors.textSecondary
            )
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = BossTheme.colors.signal,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * Confirmation dialog for resetting to default.
 */
@Composable
private fun ResetConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset to Default Keymap?", color = BossTheme.colors.textPrimary) },
        text = {
            Text(
                "This will restore all keyboard shortcuts to the BOSS default keymap. Any customizations will be lost.",
                color = BossTheme.colors.textSecondary
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(backgroundColor = BossTheme.colors.signal)
            ) {
                Text("Reset", color = BossTheme.colors.textPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = BossTheme.colors.textSecondary)
            }
        },
        backgroundColor = BossTheme.colors.raised,
        contentColor = BossTheme.colors.textPrimary
    )
}

/**
 * Gets a description for a preset.
 */
private fun getPresetDescription(presetName: String): String {
    return when (presetName) {
        "BOSS Default" -> "Standard browser-style shortcuts with Cmd-based bindings"
        "VS Code" -> "Visual Studio Code inspired shortcuts (Cmd+P, Cmd+Shift+E, Cmd+Alt+Arrow)"
        "IntelliJ IDEA" -> "IntelliJ IDEA inspired shortcuts (Cmd+E, Cmd+1, Cmd+Alt+Arrow)"
        "Emacs" -> "Emacs-inspired Ctrl-based shortcuts (Ctrl+F, Ctrl+K, Alt+X)"
        else -> "Custom keyboard shortcut configuration"
    }
}
