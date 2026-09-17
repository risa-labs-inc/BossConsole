package ai.rever.boss.project

import ai.rever.boss.window.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Shared history with immediate UI updates and one ordered persistence writer. */
internal class RecentProjectHistory(
    scope: CoroutineScope,
    load: suspend () -> List<Project>?,
    save: suspend (List<Project>) -> Unit,
) {
    private val lock = Any()
    private val state = MutableStateFlow<List<Project>>(emptyList())
    private val pending = mutableListOf<(List<Project>) -> List<Project>>()
    private val snapshots = Channel<List<Project>>(Channel.CONFLATED)
    private var initialized = false
    val recentProjects = state.asStateFlow()

    init {
        scope.launch {
            val loaded = load()
            val seed = loaded.orEmpty().distinctBy { it.path }.take(MAX_RECENT_PROJECTS)
            synchronized(lock) {
                val shouldSave = loaded != null || pending.isNotEmpty()
                state.value = pending.fold(seed) { projects, mutation -> mutation(projects) }
                pending.clear()
                initialized = true
                // A failed read must not destroy the existing file on startup.
                if (shouldSave) snapshots.trySend(state.value)
            }
            // Loading finishes before any write. Only this coroutine writes snapshots,
            // so a slow old write can never overwrite a newer completed write.
            for (snapshot in snapshots) {
                save(snapshot)
            }
        }
    }

    fun removeRecentProject(path: String) {
        mutate { projects -> projects.filter { it.path != path } }
    }

    fun updateRecentProjects(project: Project) {
        val updated = project.copy(lastOpened = System.currentTimeMillis())
        mutate { projects ->
            (listOf(updated) + projects.filter { it.path != project.path }).take(MAX_RECENT_PROJECTS)
        }
    }

    private fun mutate(mutation: (List<Project>) -> List<Project>) {
        synchronized(lock) {
            state.value = mutation(state.value)
            if (initialized) {
                snapshots.trySend(state.value)
            } else {
                pending += mutation
            }
        }
    }

    private companion object {
        const val MAX_RECENT_PROJECTS = 10
    }
}
