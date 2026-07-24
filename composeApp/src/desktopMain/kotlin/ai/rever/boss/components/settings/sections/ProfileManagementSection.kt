package ai.rever.boss.components.settings.sections

import ai.rever.boss.plugin.browser.BrowserSettings
import ai.rever.boss.plugin.browser.BrowserSettingsManager
import ai.rever.boss.components.settings.shared.SettingsSection
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Profile Management section for Fluck Browser settings
 *
 * Allows users to:
 * - View current browser profile
 * - Switch between existing profiles
 * - Create new browser profiles
 *
 * Extracted from FluckBrowserSettings.kt to keep files under 300 lines
 */
@Composable
fun ProfileManagementSection(
    currentProfile: String,
    onProfileChange: (String) -> Unit
) {
    var showSwitchProfileMenu by remember { mutableStateOf(false) }
    var showNewProfileDialog by remember { mutableStateOf(false) }
    var newProfileName by remember { mutableStateOf("") }

    val availableProfiles = remember {
        mutableStateListOf<String>().also {
            it.addAll(BrowserSettings.availableProfiles)
        }
    }

    val coroutineScope = rememberCoroutineScope()

    SettingsSection(
        title = "Browser Profiles",
        description = "Manage browser data and sessions"
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = BossTheme.colors.ink,
            shape = RoundedCornerShape(8.dp),
            elevation = 0.dp,
            border = BorderStroke(1.dp, BossTheme.colors.line)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Current Profile",
                            color = BossTheme.colors.textSecondary,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = currentProfile,
                            color = BossTheme.colors.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Row {
                        Box {
                            TextButton(
                                onClick = { showSwitchProfileMenu = true },
                                colors = ButtonDefaults.textButtonColors(contentColor = BossTheme.colors.signal)
                            ) {
                                Icon(
                                    Icons.Outlined.SwapHoriz,
                                    contentDescription = "Switch",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Switch", fontSize = 13.sp)
                            }

                            DropdownMenu(
                                expanded = showSwitchProfileMenu,
                                onDismissRequest = { showSwitchProfileMenu = false },
                                modifier = Modifier.background(BossTheme.colors.panel)
                            ) {
                                availableProfiles.forEach { profile ->
                                    DropdownMenuItem(
                                        onClick = {
                                            onProfileChange(profile)
                                            BrowserSettings.currentProfile = profile
                                            showSwitchProfileMenu = false
                                        },
                                        modifier = Modifier.background(
                                            if (profile == currentProfile)
                                                BossTheme.colors.signal.copy(alpha = 0.1f)
                                            else BossTheme.colors.panel
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = profile,
                                                color = BossTheme.colors.textPrimary,
                                                fontSize = 13.sp
                                            )
                                            if (profile == currentProfile) {
                                                Icon(
                                                    Icons.Outlined.Check,
                                                    contentDescription = "Selected",
                                                    tint = BossTheme.colors.signal,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        TextButton(
                            onClick = { showNewProfileDialog = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = BossTheme.colors.signal)
                        ) {
                            Icon(
                                Icons.Outlined.Add,
                                contentDescription = "Add",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("New Profile", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }

    // New Profile Dialog
    if (showNewProfileDialog) {
        AlertDialog(
            onDismissRequest = {
                showNewProfileDialog = false
                newProfileName = ""
            },
            title = {
                Text(
                    "Create New Profile",
                    color = BossTheme.colors.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column {
                    Text(
                        "Enter a name for the new browser profile:",
                        color = BossTheme.colors.textSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = newProfileName,
                        onValueChange = { newProfileName = it },
                        label = { Text("Profile Name") },
                        placeholder = { Text("e.g., Work, Personal, Development") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            textColor = BossTheme.colors.textPrimary,
                            focusedBorderColor = BossTheme.colors.signal,
                            unfocusedBorderColor = BossTheme.colors.line,
                            focusedLabelColor = BossTheme.colors.signal,
                            unfocusedLabelColor = BossTheme.colors.textSecondary,
                            placeholderColor = BossTheme.colors.textSecondary.copy(alpha = 0.5f)
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newProfileName.isNotBlank()) {
                            val profileName = "browser-profile-${newProfileName.replace(" ", "-").lowercase()}"
                            availableProfiles.add(profileName)
                            BrowserSettings.availableProfiles.add(profileName)
                            onProfileChange(profileName)
                            showNewProfileDialog = false
                            newProfileName = ""

                            // Save settings
                            coroutineScope.launch {
                                BrowserSettingsManager.saveSettings()
                            }
                        }
                    },
                    enabled = newProfileName.isNotBlank()
                ) {
                    Text(
                        "Create",
                        color = if (newProfileName.isNotBlank()) BossTheme.colors.signal else BossTheme.colors.textSecondary,
                        fontSize = 13.sp
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showNewProfileDialog = false
                        newProfileName = ""
                    }
                ) {
                    Text("Cancel", color = BossTheme.colors.textSecondary, fontSize = 13.sp)
                }
            },
            backgroundColor = BossTheme.colors.panel,
            contentColor = BossTheme.colors.textPrimary
        )
    }
}
