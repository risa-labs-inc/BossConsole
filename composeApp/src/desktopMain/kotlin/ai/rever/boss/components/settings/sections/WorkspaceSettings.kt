package ai.rever.boss.components.settings.sections

import ai.rever.boss.components.settings.shared.SettingsSection
import ai.rever.boss.components.settings.shared.SettingsTheme.AccentColor
import ai.rever.boss.components.settings.shared.SettingsTheme.BorderColor
import ai.rever.boss.components.settings.shared.SettingsTheme.TextPrimary
import ai.rever.boss.components.settings.shared.SettingsTheme.TextSecondary
import ai.rever.boss.components.workspaces.PredefinedWorkspaces
import ai.rever.boss.components.workspaces.WorkspaceSettings
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

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // The titles stay literal here, inside SettingsSection, because that is what the settings
        // search index is built from - see SettingsSearchIndexDriftTest. Hiding one behind a
        // wrapper made "Default Workspace" unfindable, and an indexed setting that navigates and
        // then highlights nothing reads as the search being broken.
        SettingsSection(title = "Default Workspace") {
            WorkspaceOptionList(
                options = defaultWorkspaceOptions(),
                selectedId = settings.defaultWorkspaceId,
                onSelect = { id -> coroutineScope.launch { WorkspaceSettingsManager.setDefaultWorkspaceId(id) } },
            )
        }

        SettingsSection(title = "When Switching Workspaces") {
            WorkspaceOptionList(
                options = switchOptions(),
                selectedId = settings.onWorkspaceSwitch,
                onSelect = { id -> coroutineScope.launch { WorkspaceSettingsManager.setOnWorkspaceSwitch(id) } },
            )
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

/**
 * What can happen when a project is selected.
 *
 * Order matters: this is the list a first-run user reads top-down, and the two "no layout
 * applied" answers belong together above the layouts themselves.
 */
private fun defaultWorkspaceOptions(): List<WorkspaceOption> =
    buildList {
        // Order matters: this is the list a first-run user reads top-down, and the two
        // "no layout applied" answers belong together above the layouts themselves.
        add(
            WorkspaceOption(
                id = WorkspaceSettings.ASK_WORKSPACE_ID,
                name = "Ask",
                description = "Start with no workspace, then ask which one when a project is selected",
            ),
        )
        add(
            WorkspaceOption(
                id = WorkspaceSettings.NO_WORKSPACE_ID,
                name = "None",
                description = "Never apply a workspace, and never ask",
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

/**
 * What happens to the workspace you leave when switching.
 *
 * Here as well as in the switch dialog, because "Don't ask again" is a decision someone has to be
 * able to take back - and the dialog it silences is the only other place it appears.
 */
private fun switchOptions(): List<WorkspaceOption> =
    listOf(
        WorkspaceOption(
            id = WorkspaceSettings.SWITCH_ASK,
            name = "Ask",
            description = "Ask whether to keep the workspace you are leaving running",
        ),
        WorkspaceOption(
            id = WorkspaceSettings.SWITCH_KEEP,
            name = "Keep running",
            description = "Leave its tabs open in the background, so switching back is instant",
        ),
        WorkspaceOption(
            id = WorkspaceSettings.SWITCH_CLOSE,
            name = "Close it",
            description = "Close its tabs, and rebuild the workspace from its layout next time",
        ),
    )

/** One group of mutually exclusive options. Both sections here are exactly that. */
@Composable
private fun WorkspaceOptionList(
    options: List<WorkspaceOption>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            WorkspaceOptionItem(
                title = option.name,
                description = option.description,
                selected = selectedId == option.id,
                onClick = { onSelect(option.id) },
            )
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
