package ai.rever.boss.workspace

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Normalizes directory path separators and trailing slashes for cross-platform consistency.
 */
fun normalizeWorkspacePath(path: String): String {
    val trimmed = path.trim().replace('\\', '/')
    return if (trimmed.endsWith('/') && trimmed.length > 1) {
        trimmed.dropLast(1)
    } else {
        trimmed
    }
}

/**
 * Metadata representing a recent workspace directory for project picker and quick switching.
 */
data class RecentWorkspaceItem(
    val path: String,
    val name: String,
    val lastOpenedTimestamp: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false,
)

/**
 * Thread-safe registry tracking MRU recent project workspaces (max capacity: 10).
 * Aligns with host ProjectState MRU persistence semantics.
 */
object RecentWorkspaceRegistry {
    private const val MAX_CAPACITY = 10

    private val lock = Any()
    private val workspaceList = mutableListOf<RecentWorkspaceItem>()

    private val _workspacesFlow = MutableStateFlow<List<RecentWorkspaceItem>>(emptyList())
    val workspacesFlow: StateFlow<List<RecentWorkspaceItem>> = _workspacesFlow.asStateFlow()

    /**
     * Adds or promotes a workspace directory path in the MRU buffer.
     */
    fun addWorkspace(
        path: String,
        name: String,
    ) {
        val cleanPath = normalizeWorkspacePath(path)
        if (cleanPath.isEmpty()) return

        val cleanName = name.trim().ifEmpty { cleanPath.substringAfterLast('/').ifEmpty { "Workspace" } }

        synchronized(lock) {
            val existingIndex = workspaceList.indexOfFirst { it.path.equals(cleanPath, ignoreCase = true) }
            val isPinned = if (existingIndex != -1) workspaceList[existingIndex].isPinned else false

            if (existingIndex != -1) {
                workspaceList.removeAt(existingIndex)
            }

            val item =
                RecentWorkspaceItem(
                    path = cleanPath,
                    name = cleanName,
                    lastOpenedTimestamp = System.currentTimeMillis(),
                    isPinned = isPinned,
                )
            workspaceList.add(0, item)

            while (workspaceList.size > MAX_CAPACITY) {
                val removeIndex = workspaceList.indexOfLast { !it.isPinned }
                if (removeIndex != -1) {
                    workspaceList.removeAt(removeIndex)
                } else {
                    workspaceList.removeAt(workspaceList.size - 1)
                }
            }

            _workspacesFlow.value = workspaceList.toList()
        }
    }

    /**
     * Toggles pin status for a workspace path. Pinned workspaces are preserved during capacity eviction.
     */
    fun togglePin(path: String): Boolean {
        val cleanPath = normalizeWorkspacePath(path)
        synchronized(lock) {
            val index = workspaceList.indexOfFirst { it.path.equals(cleanPath, ignoreCase = true) }
            if (index != -1) {
                val current = workspaceList[index]
                val updated = current.copy(isPinned = !current.isPinned)
                workspaceList[index] = updated
                _workspacesFlow.value = workspaceList.toList()
                return updated.isPinned
            }
            return false
        }
    }

    /**
     * Returns a snapshot list of recent workspace items.
     */
    fun getRecentWorkspaces(): List<RecentWorkspaceItem> =
        synchronized(lock) {
            workspaceList.toList()
        }

    /**
     * Returns a list of pinned workspace items.
     */
    fun getPinnedWorkspaces(): List<RecentWorkspaceItem> =
        synchronized(lock) {
            workspaceList.filter { it.isPinned }
        }

    /**
     * Removes a workspace from the buffer.
     */
    fun removeWorkspace(path: String) {
        val cleanPath = normalizeWorkspacePath(path)
        synchronized(lock) {
            workspaceList.removeAll { it.path.equals(cleanPath, ignoreCase = true) }
            _workspacesFlow.value = workspaceList.toList()
        }
    }

    /**
     * Clears all recent workspaces from memory.
     */
    fun clear() {
        synchronized(lock) {
            workspaceList.clear()
            _workspacesFlow.value = emptyList()
        }
    }
}
