package ai.rever.boss.components.workspaces

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * Manages layout workspaces with file-based storage
 */
class WorkspaceManager {
    private val logger = BossLogger.forComponent("WorkspaceManager")
    private val _currentWorkspace = MutableStateFlow<LayoutWorkspace?>(null)
    val currentWorkspace: StateFlow<LayoutWorkspace?> = _currentWorkspace.asStateFlow()

    private val _workspaces = MutableStateFlow<List<LayoutWorkspace>>(emptyList())
    val workspaces: StateFlow<List<LayoutWorkspace>> = _workspaces.asStateFlow()

    /**
     * Every workspace each open window is RUNNING, by window id.
     *
     * A set per window, not a single id, because a window runs several workspaces at once.
     * Switching does not tear the old one down - `SplitViewState.preserveCurrentState` keeps its
     * whole split tree, live tab components and all - so a workspace you have switched away from
     * is still going, and its tabs still appear in `collectAllTabs`.
     *
     * [currentWorkspace] answers none of this. It is one value on a manager every window shares:
     * with two windows on different workspaces it names whichever loaded last, and it never knew
     * about the ones running behind the one on screen.
     */
    private val _windowWorkspaces = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val windowWorkspaces: StateFlow<Map<String, Set<String>>> = _windowWorkspaces.asStateFlow()

    /** Every workspace running anywhere, across every open window. */
    val liveWorkspaceIds: Set<String> get() =
        _windowWorkspaces.value.values
            .flatten()
            .toSet()

    /**
     * Record every workspace [windowId] is running.
     *
     * Called by the window itself, which is the only thing that knows - the live set lives in
     * that window's SplitViewState. There is no single place inside this manager to hook:
     * `loadWorkspace` is reached from restore, from menu actions, from plugins and from the
     * workspace button, and only some of those know which window they are acting for.
     */
    fun setWindowWorkspaces(
        windowId: String,
        workspaceIds: Set<String>,
    ) {
        _windowWorkspaces.value =
            if (workspaceIds.isEmpty()) {
                _windowWorkspaces.value - windowId
            } else {
                _windowWorkspaces.value + (windowId to workspaceIds)
            }
    }

    /** Forget a window, on close. Without this its workspace stays marked active for ever. */
    fun releaseWindow(windowId: String) {
        _windowWorkspaces.value = _windowWorkspaces.value - windowId
    }

    private val fileManager = WorkspaceFileManager()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Callback for when a workspace is deleted
    private var onWorkspaceDeleted: ((String) -> Unit)? = null

    init {
        // Load workspaces from both predefined and saved files
        loadAllWorkspaces()
    }

    private fun loadAllWorkspaces() {
        scope.launch {
            // Start with predefined workspaces
            val allWorkspaces = mutableListOf<LayoutWorkspace>()
            allWorkspaces.addAll(PredefinedWorkspaces.allWorkspaces)

            // Load saved workspaces from disk
            try {
                val savedWorkspaces =
                    withContext(Dispatchers.IO) {
                        fileManager.listWorkspaces()
                    }
                savedWorkspaces.forEach { fileInfo ->
                    val workspace =
                        withContext(Dispatchers.IO) {
                            fileManager.loadWorkspace(fileInfo.fileName)
                        }
                    workspace?.let {
                        // Ensure workspace has an ID
                        val workspaceWithId =
                            if (it.id.isEmpty()) {
                                it.copy(id = LayoutWorkspace.generateId())
                            } else {
                                it
                            }
                        // Only add if not already in predefined list
                        if (allWorkspaces.none { ws -> ws.name == workspaceWithId.name }) {
                            allWorkspaces.add(workspaceWithId)
                        }
                    }
                }
            } catch (e: Exception) {
                // Log error but continue with predefined workspaces
                logger.warn(LogCategory.WORKSPACE, "Error loading workspaces", error = e)
            }

            _workspaces.value = allWorkspaces
        }
    }

    /**
     * Load a workspace
     */
    fun loadWorkspace(workspace: LayoutWorkspace) {
        _currentWorkspace.value = workspace
    }

    /**
     * Save current workspace to disk
     */
    fun saveCurrentWorkspace(name: String? = null): LayoutWorkspace? {
        val current = _currentWorkspace.value ?: return null
        val savedWorkspace =
            current.copy(
                id = current.id.ifEmpty { LayoutWorkspace.generateId() },
                name = name ?: current.name,
                timestamp = Clock.System.now().toEpochMilliseconds(),
            )

        scope.launch {
            // Save to disk (on IO thread)
            val filePath =
                withContext(Dispatchers.IO) {
                    fileManager.saveWorkspace(savedWorkspace)
                }
            if (filePath != null) {
                // Update workspaces list (on Main thread)
                val workspaces = _workspaces.value.toMutableList()
                val existingIndex = workspaces.indexOfFirst { it.name == savedWorkspace.name }

                if (existingIndex >= 0) {
                    workspaces[existingIndex] = savedWorkspace
                } else {
                    workspaces.add(savedWorkspace)
                }

                _workspaces.value = workspaces
                _currentWorkspace.value = savedWorkspace
            }
        }

        return savedWorkspace
    }

    /**
     * Persist [layout] as the "Last Session" workspace, blocking until the file
     * has been written, and return whether the write succeeded.
     *
     * For the shutdown path only. [saveCurrentWorkspace] is fire-and-forget on a
     * `Dispatchers.Main` scope, which cannot be trusted while the app is closing:
     * the coroutine is queued behind the teardown that scheduled it, so the
     * process can exit before the write ever starts — the layout users report as
     * "disappeared". This writes on the calling thread instead, so returning
     * means the session is on disk.
     */
    fun saveLastSessionBlocking(layout: LayoutWorkspace): Boolean {
        val lastSession =
            asLastSession(layout).copy(
                timestamp = Clock.System.now().toEpochMilliseconds(),
            )
        val filePath = fileManager.saveWorkspaceBlocking(lastSession)
        if (filePath == null) {
            logger.warn(LogCategory.WORKSPACE, "Last Session save failed", mapOf("workspace" to lastSession.name))
            return false
        }
        _currentWorkspace.value = lastSession
        _workspaces.value =
            _workspaces.value.toMutableList().also { workspaces ->
                val existingIndex = workspaces.indexOfFirst { it.name == lastSession.name }
                if (existingIndex >= 0) workspaces[existingIndex] = lastSession else workspaces.add(lastSession)
            }
        return true
    }

    /**
     * Reset to default workspace
     */
    fun resetToDefault() {
        _currentWorkspace.value = null
    }

    /**
     * Export workspace to JSON
     */
    fun exportWorkspace(workspace: LayoutWorkspace): String = WorkspaceSerializer.serialize(workspace)

    /**
     * Import workspace from JSON
     */
    fun importWorkspace(jsonString: String): LayoutWorkspace? =
        try {
            val workspace = WorkspaceSerializer.deserialize(jsonString)

            // Save the imported workspace to disk
            scope.launch {
                withContext(Dispatchers.IO) {
                    fileManager.saveWorkspace(workspace)
                }

                // Update workspaces list (on Main thread)
                val workspaces = _workspaces.value.toMutableList()
                if (workspaces.none { it.name == workspace.name }) {
                    workspaces.add(workspace)
                    _workspaces.value = workspaces
                }
            }

            workspace
        } catch (e: Exception) {
            logger.warn(LogCategory.WORKSPACE, "Failed to import workspace from JSON", error = e)
            null
        }

    /**
     * Set callback for when a workspace is deleted
     */
    fun setOnWorkspaceDeleted(callback: (String) -> Unit) {
        onWorkspaceDeleted = callback
    }

    /**
     * Delete a workspace
     */
    fun deleteWorkspace(name: String) {
        scope.launch {
            // Find workspace
            val workspace = _workspaces.value.find { it.name == name }
            if (workspace != null && !PredefinedWorkspaces.allWorkspaces.any { it.name == name }) {
                // Only delete if it's not a predefined workspace
                val fileName = WorkspaceFileManagerCommon.generateFileName(name)
                val deleted =
                    withContext(Dispatchers.IO) {
                        fileManager.deleteWorkspace(fileName)
                    }
                if (deleted) {
                    // Update state on Main thread
                    _workspaces.value = _workspaces.value.filter { it.name != name }

                    // Notify that workspace was deleted (this will cleanup tabs)
                    onWorkspaceDeleted?.invoke(workspace.id)

                    // If current workspace was deleted, reset
                    if (_currentWorkspace.value?.name == name) {
                        resetToDefault()
                    }
                }
            }
        }
    }

    /**
     * Rename a workspace
     */
    fun renameWorkspace(
        oldName: String,
        newName: String,
    ) {
        // Don't allow renaming to an existing name or empty name
        if (newName.isEmpty() || newName == oldName) return
        if (_workspaces.value.any { it.name == newName }) {
            logger.debug(LogCategory.WORKSPACE, "Workspace with name already exists", mapOf("name" to newName))
            return
        }

        scope.launch {
            // Find workspace
            val workspace = _workspaces.value.find { it.name == oldName }
            if (workspace != null && !PredefinedWorkspaces.allWorkspaces.any { it.name == oldName }) {
                // Only rename if it's not a predefined workspace
                val oldFileName = WorkspaceFileManagerCommon.generateFileName(oldName)
                val newFileName = WorkspaceFileManagerCommon.generateFileName(newName)

                // Create renamed workspace
                val renamedWorkspace =
                    workspace.copy(
                        name = newName,
                        timestamp = Clock.System.now().toEpochMilliseconds(),
                    )

                // Save with new name and delete old file
                val success =
                    withContext(Dispatchers.IO) {
                        val saved = fileManager.saveWorkspace(renamedWorkspace, newFileName)
                        if (saved != null) {
                            fileManager.deleteWorkspace(oldFileName)
                            true
                        } else {
                            false
                        }
                    }

                if (success) {
                    // Update state on Main thread
                    _workspaces.value =
                        _workspaces.value.map {
                            if (it.name == oldName) renamedWorkspace else it
                        }

                    // If current workspace was renamed, update it
                    if (_currentWorkspace.value?.name == oldName) {
                        _currentWorkspace.value = renamedWorkspace
                    }
                }
            }
        }
    }

    /**
     * Update current workspace with new layout
     */
    fun updateCurrentWorkspace(newWorkspace: LayoutWorkspace) {
        _currentWorkspace.value = newWorkspace
    }

    /**
     * Get the workspace directory path
     */
    fun getWorkspaceDirectory(): String = fileManager.getDefaultWorkspaceDirectory()
}

// Global instance
val workspaceManager = WorkspaceManager()
