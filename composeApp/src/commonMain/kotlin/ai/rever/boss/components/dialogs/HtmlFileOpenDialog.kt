package ai.rever.boss.components.dialogs

import ai.rever.boss.html.HtmlFileOpenMode
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Language
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

/**
 * Dialog shown when opening an .html or .htm file.
 * Offers options to open as code editor or webpage with "Remember my choice".
 *
 * @param fileName The name of the HTML file
 * @param filePath The path of the HTML file
 * @param onDismiss Called when dialog is dismissed without selection
 * @param onOpenChoice Called when user selects an option. Receives the mode and whether to remember choice.
 */
@Composable
@Suppress("LongMethod")
fun HtmlFileOpenDialog(
    fileName: String,
    filePath: String,
    onDismiss: () -> Unit,
    onOpenChoice: (mode: HtmlFileOpenMode, rememberChoice: Boolean) -> Unit,
) {
    var rememberChoice by remember { mutableStateOf(false) }

    BossDialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
                usePlatformDefaultWidth = false,
            ),
    ) {
        Card(
            modifier =
                Modifier
                    .width(380.dp)
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            onDismiss()
                            true
                        } else {
                            false
                        }
                    },
            shape = RoundedCornerShape(8.dp),
            backgroundColor = BossTheme.colors.panel,
            elevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
            ) {
                // Title
                Text(
                    text = "Open HTML File",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = BossTheme.colors.textPrimary,
                )

                Spacer(modifier = Modifier.height(8.dp))

                // File path preview
                Text(
                    text = filePath.ifEmpty { fileName },
                    fontSize = 12.sp,
                    color = BossTheme.colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Options
                HtmlOpenOption(
                    icon = Icons.Outlined.Code,
                    title = "Code Editor",
                    description = "Open as source code in code editor",
                    onClick = { onOpenChoice(HtmlFileOpenMode.EDITOR, rememberChoice) },
                )

                Spacer(modifier = Modifier.height(8.dp))

                HtmlOpenOption(
                    icon = Icons.Outlined.Language,
                    title = "Webpage",
                    description = "Open rendered in a browser tab",
                    onClick = { onOpenChoice(HtmlFileOpenMode.BROWSER, rememberChoice) },
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Remember checkbox
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { rememberChoice = !rememberChoice }
                            .padding(vertical = 4.dp),
                ) {
                    Checkbox(
                        checked = rememberChoice,
                        onCheckedChange = null, // Row handles click
                        colors =
                            CheckboxDefaults.colors(
                                checkedColor = BossTheme.colors.signal,
                                uncheckedColor = BossTheme.colors.textMuted,
                                checkmarkColor = Color.White,
                            ),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Remember my choice",
                        fontSize = 14.sp,
                        color = BossTheme.colors.textPrimary,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Cancel button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = BossTheme.colors.textSecondary,
                            ),
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

/**
 * Individual option card in the HTML file open dialog.
 */
@Composable
private fun HtmlOpenOption(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onClick() },
        backgroundColor = BossTheme.colors.raised,
        shape = RoundedCornerShape(6.dp),
        elevation = 0.dp,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = BossTheme.colors.signalText,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = BossTheme.colors.textPrimary,
                )
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = BossTheme.colors.textSecondary,
                )
            }
        }
    }
}
