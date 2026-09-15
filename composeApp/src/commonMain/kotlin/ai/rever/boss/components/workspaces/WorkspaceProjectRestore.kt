package ai.rever.boss.components.workspaces

import ai.rever.boss.utils.extractFileName
import ai.rever.boss.window.Project
import ai.rever.boss.window.WindowProjectState
import kotlin.time.Clock

internal fun restoreWorkspaceProject(
    workspace: LayoutWorkspace,
    windowProjectState: WindowProjectState?,
    restoreProject: Boolean,
) {
    if (restoreProject && windowProjectState != null) {
        workspace.projectPath?.let { path ->
            if (path.isNotEmpty()) {
                val projectName =
                    path
                        .trimEnd('/')
                        .trimEnd('\\')
                        .extractFileName()
                        .ifEmpty { "Project" }
                windowProjectState.selectProject(
                    Project(
                        name = projectName,
                        path = path,
                        lastOpened = Clock.System.now().toEpochMilliseconds(),
                    ),
                )
            }
        }
    }
}
