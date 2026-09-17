package ai.rever.boss.window

import ai.rever.boss.components.window_panel.TabPaths
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** One decision boundary for operator-selected projects, independent of the picker route. */
internal class ProjectOpenCoordinator(
    private val currentProject: () -> Project,
    private val openCurrent: (Project) -> Unit,
    private val openNew: (Project) -> Unit,
) {
    var pendingProject by mutableStateOf<Project?>(null)
        private set
    var unavailableProject by mutableStateOf<Project?>(null)
        private set

    fun request(project: Project) {
        pendingProject = null
        unavailableProject = null
        if (!validate(project)) return
        val current = currentProject()
        when {
            TabPaths.pathsMatch(current.path, project.path) -> Unit
            current.path.isBlank() -> openCurrent(project)
            else -> pendingProject = project
        }
    }

    fun confirmCurrent() {
        val project = pendingProject ?: return
        pendingProject = null
        if (validate(project) && !TabPaths.pathsMatch(currentProject().path, project.path)) openCurrent(project)
    }

    fun confirmNew() {
        val project = pendingProject ?: return
        pendingProject = null
        if (validate(project)) openNew(project)
    }

    fun dismissChoice() {
        pendingProject = null
    }

    fun dismiss() {
        pendingProject = null
        unavailableProject = null
    }

    private fun validate(project: Project): Boolean {
        if (project.path.isNotBlank() && File(project.path).isDirectory) return true
        unavailableProject = project
        return false
    }
}
