package ai.rever.boss.components.settings.keymap

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import ai.rever.boss.keymap.KeymapSettingsManager
import kotlinx.coroutines.launch

/**
 * Component for importing and exporting keymap settings.
 */
@Composable
fun KeymapImportExport(
    onImport: suspend (String) -> Boolean,
    modifier: Modifier = Modifier
) {
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Export row (matching SettingsButtonRow pattern)
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
                    text = "Export Keymap",
                    color = BossTheme.colors.textPrimary,
                    fontSize = 13.sp
                )
                Text(
                    text = "Backup your shortcuts to JSON",
                    color = BossTheme.colors.textSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            TextButton(
                onClick = { showExportDialog = true },
                colors = ButtonDefaults.textButtonColors(contentColor = BossTheme.colors.signal)
            ) {
                Icon(
                    imageVector = Icons.Default.FileDownload,
                    contentDescription = "Export",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Export", fontSize = 13.sp)
            }
        }

        // Import row (matching SettingsButtonRow pattern)
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
                    text = "Import Keymap",
                    color = BossTheme.colors.textPrimary,
                    fontSize = 13.sp
                )
                Text(
                    text = "Restore shortcuts from JSON backup",
                    color = BossTheme.colors.textSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            TextButton(
                onClick = { showImportDialog = true },
                colors = ButtonDefaults.textButtonColors(contentColor = BossTheme.colors.signal)
            ) {
                Icon(
                    imageVector = Icons.Default.FileUpload,
                    contentDescription = "Import",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Import", fontSize = 13.sp)
            }
        }

        // Show import error if any
        importError?.let { error ->
            Text(
                text = "⚠️ $error",
                fontSize = 11.sp,
                color = BossTheme.colors.alert
            )
        }
    }

    // Export dialog
    if (showExportDialog) {
        ExportDialog(
            onDismiss = { showExportDialog = false }
        )
    }

    // Import dialog
    if (showImportDialog) {
        ImportDialog(
            onImport = { jsonString ->
                coroutineScope.launch {
                    val success = onImport(jsonString)
                    if (success) {
                        showImportDialog = false
                        importError = null
                    } else {
                        importError = "Failed to import keymap. Check JSON format."
                    }
                }
            },
            onDismiss = {
                showImportDialog = false
                importError = null
            }
        )
    }
}

/**
 * Dialog for exporting keymap to JSON.
 */
@Composable
private fun ExportDialog(
    onDismiss: () -> Unit
) {
    val exportedJson = KeymapSettingsManager.exportToJson()
    var copied by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .width(600.dp)
                .wrapContentHeight(),
            shape = RoundedCornerShape(12.dp),
            color = BossTheme.colors.panel,
            elevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Export Keymap",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BossTheme.colors.textPrimary
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Copy the JSON below to backup or share your keymap",
                    fontSize = 13.sp,
                    color = BossTheme.colors.textSecondary
                )

                Spacer(modifier = Modifier.height(16.dp))

                // JSON text area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(BossTheme.colors.ink)
                        .border(1.dp, BossTheme.colors.line, RoundedCornerShape(6.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = exportedJson,
                        fontSize = 11.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = BossTheme.colors.textPrimary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = BossTheme.colors.textSecondary, fontSize = 13.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            copied = true
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = BossTheme.colors.signal)
                    ) {
                        Text(if (copied) "Copied!" else "Copy to Clipboard", color = BossTheme.colors.textPrimary, fontSize = 13.sp)
                    }
                }

                if (copied) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "💡 Tip: Save this JSON to a file for backup",
                        fontSize = 11.sp,
                        color = BossTheme.colors.signal
                    )
                }
            }
        }
    }
}

/**
 * Dialog for importing keymap from JSON.
 */
@Composable
private fun ImportDialog(
    onImport: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var jsonInput by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .width(600.dp)
                .wrapContentHeight(),
            shape = RoundedCornerShape(12.dp),
            color = BossTheme.colors.panel,
            elevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Import Keymap",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BossTheme.colors.textPrimary
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Paste keymap JSON below",
                    fontSize = 13.sp,
                    color = BossTheme.colors.textSecondary
                )

                Spacer(modifier = Modifier.height(16.dp))

                // JSON input area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(BossTheme.colors.ink)
                        .border(1.dp, BossTheme.colors.line, RoundedCornerShape(6.dp))
                ) {
                    TextField(
                        value = jsonInput,
                        onValueChange = {
                            jsonInput = it
                            showError = false
                        },
                        modifier = Modifier.fillMaxSize(),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = BossTheme.colors.textPrimary
                        ),
                        placeholder = { Text("Paste JSON here...", color = BossTheme.colors.textSecondary, fontSize = 11.sp) },
                        colors = TextFieldDefaults.textFieldColors(
                            textColor = BossTheme.colors.textPrimary,
                            backgroundColor = Color.Transparent,
                            cursorColor = BossTheme.colors.signal,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                }

                if (showError) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "⚠️ Invalid JSON format. Please check and try again.",
                        fontSize = 11.sp,
                        color = BossTheme.colors.alert
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = BossTheme.colors.textSecondary, fontSize = 13.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (jsonInput.isBlank()) {
                                showError = true
                            } else {
                                onImport(jsonInput)
                            }
                        },
                        enabled = jsonInput.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(backgroundColor = BossTheme.colors.signal)
                    ) {
                        Text("Import", color = BossTheme.colors.textPrimary, fontSize = 13.sp)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "⚠️ Warning: Importing will replace your current keymap",
                    fontSize = 11.sp,
                    color = BossTheme.colors.alert.copy(alpha = 0.7f)
                )
            }
        }
    }
}
