package ai.rever.boss.components.bars.horizontal

import ai.rever.boss.plugin.browser.BrowserSettings
import ai.rever.boss.plugin.browser.BrowserSettingsManager
import ai.rever.boss.plugin.ui.BossAlertDialog
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.window.WindowManager
import ai.rever.boss.window.WindowOperations
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Suppress("LongMethod")
@Composable
actual fun BrowserProfilePicker(windowId: String) {
    // 1. Get current window's profile
    val windowState = WindowManager.getWindow(windowId) ?: return
    val currentProfileId = windowState.browserProfileId

    // 2. We need a state for the dropdown and dialog
    var expanded by remember { mutableStateOf(false) }
    var showNewProfileDialog by remember { mutableStateOf(false) }
    var newProfileName by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    val availableProfiles =
        remember {
            mutableStateListOf<String>().also {
                it.addAll(BrowserSettings.availableProfiles)
            }
        }

    // 3. Render a compact button
    Box {
        TextButton(
            onClick = { expanded = true },
            colors = ButtonDefaults.textButtonColors(contentColor = BossTheme.colors.textPrimary),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Icon(
                Icons.Outlined.AccountCircle,
                contentDescription = "Browser Profile",
                modifier = Modifier.size(16.dp),
                tint = BossTheme.colors.textSecondary,
            )
            Spacer(modifier = Modifier.width(4.dp))
            val displayName =
                if (currentProfileId == "browser-profile") {
                    "Default"
                } else {
                    currentProfileId.removePrefix("browser-profile-").replaceFirstChar { it.uppercase() }
                }
            Text(displayName, fontSize = 12.sp)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(BossTheme.colors.panel),
        ) {
            availableProfiles.forEach { profile ->
                val isSelected = profile == currentProfileId
                val name =
                    if (profile == "browser-profile") {
                        "Default"
                    } else {
                        profile.removePrefix("browser-profile-").replaceFirstChar { it.uppercase() }
                    }

                DropdownMenuItem(
                    onClick = {
                        expanded = false
                        if (!isSelected) {
                            WindowOperations.createNewWindow(browserProfileId = profile)
                        }
                    },
                    modifier =
                        Modifier.background(
                            if (isSelected) {
                                BossTheme.colors.signal.copy(alpha = 0.1f)
                            } else {
                                BossTheme.colors.panel
                            },
                        ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = name,
                            color = BossTheme.colors.textPrimary,
                            fontSize = 13.sp,
                        )
                        if (isSelected) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = "Active",
                                tint = BossTheme.colors.signalText,
                                modifier = Modifier.size(16.dp),
                            )
                        } else {
                            Icon(
                                Icons.Outlined.OpenInNew,
                                contentDescription = "Open in New Window",
                                tint = BossTheme.colors.textSecondary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
            Divider(color = BossTheme.colors.line)
            DropdownMenuItem(
                onClick = {
                    expanded = false
                    showNewProfileDialog = true
                },
            ) {
                Row {
                    Icon(
                        Icons.Outlined.Add,
                        contentDescription = "New Profile",
                        tint = BossTheme.colors.signalText,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Create New Profile...", color = BossTheme.colors.signalText, fontSize = 13.sp)
                }
            }
        }
    }

    if (showNewProfileDialog) {
        BossAlertDialog(
            onDismissRequest = {
                showNewProfileDialog = false
                newProfileName = ""
            },
            title = {
                Text(
                    "Create New Profile",
                    color = BossTheme.colors.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Column {
                    Text(
                        "Enter a name for the new browser profile:",
                        color = BossTheme.colors.textSecondary,
                        fontSize = 13.sp,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = newProfileName,
                        onValueChange = { newProfileName = it },
                        label = { Text("Profile Name") },
                        placeholder = { Text("e.g., Work, Personal") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                            TextFieldDefaults.outlinedTextFieldColors(
                                textColor = BossTheme.colors.textPrimary,
                                focusedBorderColor = BossTheme.colors.signal,
                                unfocusedBorderColor = BossTheme.colors.line,
                                focusedLabelColor = BossTheme.colors.signalText,
                                unfocusedLabelColor = BossTheme.colors.textSecondary,
                                placeholderColor = BossTheme.colors.textSecondary.copy(alpha = 0.5f),
                            ),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = newProfileName.trim()
                        if (trimmed.isNotBlank()) {
                            val profileId = "browser-profile-${trimmed.replace(" ", "-").lowercase()}"
                            if (!BrowserSettings.availableProfiles.contains(profileId)) {
                                BrowserSettings.availableProfiles.add(profileId)
                                availableProfiles.add(profileId)
                                coroutineScope.launch { BrowserSettingsManager.saveSettings() }
                            }
                            showNewProfileDialog = false
                            newProfileName = ""
                            // Open in new window immediately
                            WindowOperations.createNewWindow(browserProfileId = profileId)
                        }
                    },
                    enabled = newProfileName.trim().isNotBlank(),
                ) {
                    Text(
                        "Create",
                        color =
                            if (newProfileName.trim().isNotBlank()) {
                                BossTheme.colors.signal
                            } else {
                                BossTheme.colors.textSecondary
                            },
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showNewProfileDialog = false
                        newProfileName = ""
                    },
                ) {
                    Text("Cancel", color = BossTheme.colors.textSecondary)
                }
            },
            backgroundColor = BossTheme.colors.panel,
            contentColor = BossTheme.colors.textPrimary,
        )
    }
}
