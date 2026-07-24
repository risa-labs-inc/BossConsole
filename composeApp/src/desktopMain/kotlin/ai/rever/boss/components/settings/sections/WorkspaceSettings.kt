package ai.rever.boss.components.settings.sections

import ai.rever.boss.components.settings.shared.SettingsSection
import ai.rever.boss.components.settings.shared.SettingsTheme.AccentColor
import ai.rever.boss.components.settings.shared.SettingsTheme.BorderColor
import ai.rever.boss.components.settings.shared.SettingsTheme.TextPrimary
import ai.rever.boss.components.settings.shared.SettingsTheme.TextSecondary
import ai.rever.boss.components.workspaces.PredefinedWorkspaces
import ai.rever.boss.components.workspaces.WorkspaceSettingsManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun WorkspaceSettings() {
    val settings by WorkspaceSettingsManager.currentSettings.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    val workspaceOptions =
        buildList {
            add(
                WorkspaceOption(
                    id = "none",
                    name = "None",
                    description = "Don't auto-apply workspace when project is selected",
                ),
            )
            PredefinedWorkspaces.allWorkspaces.forEach { workspace ->
                add(
                    WorkspaceOption(
                        id = workspace.id,
                        name = workspace.name,
                        description = workspace.description,
                    ),
                )
            }
        }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSection(title = "Default Workspace") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                workspaceOptions.forEach { option ->
                    WorkspaceOptionItem(
                        title = option.name,
                        description = option.description,
                        selected = settings.defaultWorkspaceId == option.id,
                        onClick = {
                            coroutineScope.launch {
                                WorkspaceSettingsManager.setDefaultWorkspaceId(option.id)
                            }
                        },
                    )
                }
            }
        }

        SettingsSection(title = "About Workspaces") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                NoteItem(text = "Workspaces define panel layouts with terminals and browsers")
                NoteItem(text = "Terminal commands use {projectPath} placeholder for the current project")
                NoteItem(text = "Browser tabs use {gitRemoteUrl} to open the project's GitHub page")
                NoteItem(text = "Save custom workspaces via the Workspace button in the top bar")
            }
        }
    }
}

private data class WorkspaceOption(
    val id: String,
    val name: String,
    val description: String,
)

@Composable
private fun WorkspaceOptionItem(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) AccentColor else BorderColor
    val backgroundColor = if (selected) AccentColor.copy(alpha = 0.15f) else Color.Transparent

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, borderColor, RoundedCornerShape(6.dp))
                .background(backgroundColor)
                .clickable(onClick = onClick)
                .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (selected) AccentColor else TextPrimary,
            )
            Text(
                text = description,
                fontSize = 11.sp,
                color = TextSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = "Selected",
                tint = AccentColor,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun NoteItem(text: String) {
    Text(
        text = "• $text",
        fontSize = 12.sp,
        color = TextSecondary,
        lineHeight = 18.sp,
    )
}
