package ai.rever.boss.plugin.window

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Callback interface for project selection events.
 * Implement this to handle project updates (e.g., updating recent projects list).
 */
fun interface ProjectSelectionCallback {
    fun onProjectSelected(project: Project)
}

/**
 * Point-in-time immutable snapshot of a window's project and lifecycle state.
 */
data class WindowStateSnapshot(
    val isClosed: Boolean,
    val projectPath: String,
    val generation: Long,
)

/**
 * An active execution lease on a window's workspace context.
 * Guarantees that the window has not transitioned away from [token].
 * If the window transitions (project switch or close), the lease is invalidated
 * and any attached coroutine job is cancelled immediately.
 */
interface WorkspaceExecutionLease : AutoCloseable {
    val token: WorkspaceContextToken
    val isValid: Boolean
}

inline fun <T> WorkspaceExecutionLease.use(block: () -> T): T =
    try {
        block()
    } finally {
        close()
    }

/**
 * Per-window project state.
 * Each window has its own selected project, independent of other windows.
 *
 * @property windowId Unique identifier for the window
 */
class WindowProjectState(
    val windowId: String,
) {
    private val lock = Any()
    private val logger = BossLogger.forComponent("WindowProjectState")
    private val activeLeases = mutableSetOf<WorkspaceExecutionLeaseImpl>()
    private val _selectedProject =
        MutableStateFlow(
            Project(
                name = "No Project",
                path = "",
                lastOpened = 0L,
            ),
        )
    val selectedProject: StateFlow<Project> = _selectedProject.asStateFlow()

    private val _generation = AtomicLong(0L)

    /**
     * Monotonic generation counter advancing on every project state transition.
     * Used by optimistic concurrency control to detect stale in-flight operations.
     */
    val generation: Long get() = _generation.get()

    /** Whether this window's state has been unregistered/closed. */
    @Volatile
    var isClosed: Boolean = false
        internal set

    // Callback for project selection (e.g., to update recent projects)
    @Volatile
    private var projectSelectionCallback: ProjectSelectionCallback? = null

    private class WorkspaceExecutionLeaseImpl(
        override val token: WorkspaceContextToken,
        private val onClose: (WorkspaceExecutionLeaseImpl) -> Unit,
    ) : WorkspaceExecutionLease {
        @Volatile
        override var isValid: Boolean = true
            internal set

        fun invalidate() {
            isValid = false
        }

        override fun close() {
            onClose(this)
        }
    }

    /**
     * Set the callback to be notified when a project is selected.
     */
    fun setProjectSelectionCallback(callback: ProjectSelectionCallback?) {
        projectSelectionCallback = callback
    }

    /**
     * Takes an atomic point-in-time snapshot of the window's state.
     */
    fun snapshot(): WindowStateSnapshot =
        synchronized(lock) {
            WindowStateSnapshot(
                isClosed = isClosed,
                projectPath = _selectedProject.value.path,
                generation = _generation.get(),
            )
        }

    /**
     * Produces an immutable [WorkspaceContextToken] representing the active project
     * and current generation epoch of this window.
     */
    fun currentContextToken(): WorkspaceContextToken =
        synchronized(lock) {
            WorkspaceContextToken(
                windowId = windowId,
                projectPath = _selectedProject.value.path,
                generation = _generation.get(),
            )
        }

    /**
     * Atomically acquires an execution lease bound to [token].
     * Returns null if the window is closed, generation does not match, or project path differs.
     */
    fun acquireExecutionLease(
        token: WorkspaceContextToken,
    ): WorkspaceExecutionLease? =
        synchronized(lock) {
            val snap = snapshot()
            if (snap.isClosed) return null
            if (snap.generation != token.generation) return null
            val norm1 = snap.projectPath.replace('\\', '/').trimEnd('/')
            val norm2 = token.projectPath.replace('\\', '/').trimEnd('/')
            if (!norm1.equals(norm2, ignoreCase = true)) return null

            val lease =
                WorkspaceExecutionLeaseImpl(
                    token = token,
                    onClose = { l ->
                        synchronized(lock) {
                            activeLeases.remove(l)
                        }
                    },
                )
            activeLeases.add(lease)
            lease
        }

    /**
     * Mark this window state as closed. Called on window unregistration.
     * In-flight operations holding an execution lease are invalidated.
     */
    fun markClosed() {
        val currentGen: Long
        synchronized(lock) {
            isClosed = true
            currentGen = _generation.incrementAndGet()
            activeLeases.forEach { it.invalidate() }
            activeLeases.clear()
        }
    }

    /**
     * Select a project in this window.
     * In-flight operations holding an execution lease on the old workspace are invalidated.
     *
     * @param project The project to select
     */
    fun selectProject(project: Project) {
        val updatedProject = project.copy(lastOpened = System.currentTimeMillis())
        val currentGen: Long
        synchronized(lock) {
            currentGen = _generation.incrementAndGet()
            _selectedProject.value = updatedProject
            activeLeases.forEach { it.invalidate() }
            activeLeases.clear()
        }
        projectSelectionCallback?.onProjectSelected(updatedProject)
        logger.debug(LogCategory.FILE, "Selected project", mapOf("windowId" to windowId, "name" to project.name, "path" to project.path, "generation" to currentGen))
    }

    /**
     * Get the currently selected project.
     */
    fun currentProject(): Project = _selectedProject.value
}
