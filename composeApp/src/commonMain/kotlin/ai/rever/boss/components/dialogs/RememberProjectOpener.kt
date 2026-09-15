package ai.rever.boss.components.dialogs

import ai.rever.boss.plugin.ui.BossAlertDialog
import ai.rever.boss.window.Project
import ai.rever.boss.window.ProjectOpenCoordinator
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/** Shared validation and destination choice for native browse and recent-project selections. */
@Composable
internal fun rememberProjectOpener(
    currentProject: Project,
    onOpenCurrent: (Project) -> Unit,
    onOpenNew: (Project) -> Unit,
): ProjectOpenCoordinator {
    val current by rememberUpdatedState(currentProject)
    val openCurrent by rememberUpdatedState(onOpenCurrent)
    val openNew by rememberUpdatedState(onOpenNew)
    val opener = remember { ProjectOpenCoordinator({ current }, { openCurrent(it) }, { openNew(it) }) }
    opener.pendingProject?.let { project ->
        ProjectOpenModeDialog(
            project = project,
            onDismiss = opener::dismissChoice,
            onOpenInCurrentWindow = { opener.confirmCurrent() },
            onOpenInNewWindow = { opener.confirmNew() },
        )
    }
    opener.unavailableProject?.let { project ->
        BossAlertDialog(
            onDismissRequest = opener::dismiss,
            title = { Text("Project Not Found") },
            text = {
                Text(
                    "The project folder for \"${project.name}\" is unavailable. " +
                        "Choose an existing folder and try again.",
                )
            },
            confirmButton = { TextButton(onClick = opener::dismiss) { Text("OK") } },
        )
    }
    return opener
}
