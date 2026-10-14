package ai.rever.boss.components.home

import ai.rever.boss.components.dialogs.RemoveProjectDialog
import ai.rever.boss.project.ProjectRemovalScope
import ai.rever.boss.window.Project
import androidx.compose.runtime.Composable

/**
 * The dialog a project card can raise, kept out of [HomeScreen] so its body stays a readable list
 * of sections. Opening one is asked by the window's own dialog (see `ProjectOpenRequests`).
 */
@Composable
internal fun HomeProjectDialogs(
    projectToRemove: Project?,
    openProjectPath: String,
    onRemoveDone: () -> Unit,
    onRemove: (Project, ProjectRemovalScope) -> Unit,
) {
    projectToRemove?.let { project ->
        RemoveProjectDialog(
            project = project,
            isOpenHere = project.path == openProjectPath,
            onDismiss = onRemoveDone,
            onConfirm = { removalScope ->
                onRemoveDone()
                onRemove(project, removalScope)
            },
        )
    }
}
