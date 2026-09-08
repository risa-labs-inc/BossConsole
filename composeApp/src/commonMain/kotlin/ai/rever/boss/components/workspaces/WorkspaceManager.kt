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
        // And its unsaved marks, for the same reason: a closed window's dirt is not anybody's to
        // save, and a reused window id would inherit it.
        _unsavedWorkspaces.value = _unsavedWorkspaces.value - windowId
    }

    /**
     * Ids of the Spaces whose live layout differs from what is on disk, by window.
     *
     * **Per window, and that is not optional.** Two windows run different Spaces and each one's
     * layout is its own; one flat set would light the Save button in a window that has nothing to
     * save the moment the other window was edited. The window is the only party that can answer at
     * all - the live layout lives in its `SplitViewState` - so it reports, exactly as
     * [setWindowWorkspaces] has it report which Spaces it is running.
     *
     * A `StateFlow`, so the vertical bar's Save affordance can watch it. This replaces
     * `TabTreeState.modifiedWorkspaces`, which was written from three places and READ FROM NONE -
     * there was no dirty state in the app, only the bookkeeping for one.
     */
    private val _unsavedWorkspaces = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    /** Every Space with unsaved changes, in every window. See [isWorkspaceUnsavedIn]. */
    val unsavedWorkspaces: StateFlow<Map<String, Set<String>>> = _unsavedWorkspaces.asStateFlow()

    /**
     * Record whether [windowId]'s copy of [workspaceId] has changes that are not on disk.
     *
     * Called from the window's own layout watcher, which extracts the live layout already. The
     * decision itself is [isUnsaved], which is pure and tested; this only stores it.
     */
    fun setWorkspaceUnsaved(
        windowId: String,
        workspaceId: String,
        unsaved: Boolean,
    ) {
        val current = _unsavedWorkspaces.value[windowId].orEmpty()
        val updated = if (unsaved) current + workspaceId else current - workspaceId
        if (updated == current) return
        _unsavedWorkspaces.value =
            if (updated.isEmpty()) {
                _unsavedWorkspaces.value - windowId
            } else {
                _unsavedWorkspaces.value + (windowId to updated)
            }
    }

    /** Whether [windowId] holds unsaved changes to [workspaceId]. */
    fun isWorkspaceUnsavedIn(
        windowId: String,
        workspaceId: String,
    ): Boolean = workspaceId in _unsavedWorkspaces.value[windowId].orEmpty()

    /**
     * The Space with [workspaceId] as it exists ON DISK, or null if nothing there answers to it.
     *
     * [workspaces] is the honest answer to that question and [currentWorkspace] is not:
     * `updateCurrentWorkspace` writes the live layout into it BEFORE the save is attempted, so
     * comparing against it would read clean the instant an auto-save was queued rather than when
     * the bytes landed. This list is only replaced once `fileManager` has returned a path.
     */
    fun savedCopyOf(workspaceId: String): LayoutWorkspace? = _workspaces.value.firstOrNull { it.id == workspaceId }

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
            val saved = mutableListOf<LayoutWorkspace>()

            // Load saved workspaces from disk
            try {
                val savedWorkspaces =
                    withContext(Dispatchers.IO) {
                        fileManager.listWorkspaces()
                    }
                savedWorkspaces.forEach { fileInfo ->
                    // The session-set record lives in this directory and is not a Space. The scan
                    // is "every *.json", so without this it is deserialized as one on every
                    // launch, fails, and logs a warning for ever. See LAST_SESSION_SET_FILE.
                    if (fileInfo.fileName == LAST_SESSION_SET_FILE) return@forEach
                    val workspace =
                        withContext(Dispatchers.IO) {
                            fileManager.loadWorkspace(fileInfo.fileName)
                        }
                    workspace?.let {
                        // Ensure workspace has an ID
                        saved.add(if (it.id.isEmpty()) it.copy(id = LayoutWorkspace.generateId()) else it)
                    }
                }
            } catch (e: Exception) {
                // Log error but continue with predefined workspaces
                logger.warn(LogCategory.WORKSPACE, "Error loading workspaces", error = e)
            }

            // Merged by ID, never by name. A saved file called "Codex" used to be DROPPED here in
            // favour of the shipped layout of that name, and so did a save made while the current
            // Space was a built-in - which is the Save button not working. See
            // `mergeSavedWorkspaces`, which also says what becomes of a legacy file whose id IS a
            // built-in's.
            _workspaces.value = mergeSavedWorkspaces(PredefinedWorkspaces.allWorkspaces, saved)
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
        val now = Clock.System.now().toEpochMilliseconds()
        val savedWorkspace =
            if (current.id in PredefinedWorkspaces.allIds) {
                // Saving while the current Space is a SHIPPED layout creates the user's own copy
                // rather than writing over the template: a new id, and a name that collides with
                // nothing. Writing the built-in's own id and name is what the old behaviour did,
                // and the file it produced was silently dropped on the next launch. See
                // `savedCopyOfBuiltIn`.
                savedCopyOfBuiltIn(
                    current = current,
                    id = LayoutWorkspace.generateId(),
                    now = now,
                    takenNames = _workspaces.value.map { it.name }.toSet(),
                    requestedName = name,
                )
            } else {
                current.copy(
                    id = current.id.ifEmpty { LayoutWorkspace.generateId() },
                    name = name ?: current.name,
                    timestamp = now,
                )
            }

        scope.launch {
            // Save to disk (on IO thread)
            val filePath =
                withContext(Dispatchers.IO) {
                    fileManager.saveWorkspace(savedWorkspace)
                }
            if (filePath != null) {
                // Update workspaces list (on Main thread), keyed by ID for the reason
                // `mergeSavedWorkspaces` is: by NAME, saving a Space of the user's that happens to
                // be called "Codex" replaced the SHIPPED Codex in this list, so the Templates
                // section lost a tile for the rest of the session. By name a RENAME also appended a
                // second entry rather than updating the one it renamed, since the new name matched
                // nothing.
                val workspaces = _workspaces.value.toMutableList()
                val existingIndex = workspaces.indexOfFirst { it.id == savedWorkspace.id }

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
     * Write [record] as the Last Session file, and refresh the list entry for it.
     *
     * The layout watcher's only write. Deliberately does NOT touch [currentWorkspace]: while the
     * user is working in a named Space that is the Space they are in, and stamping it "Last
     * Session" would rename it under them. The caller sets it, from
     * `LayoutWatcherWrite.current`, which is the live layout under the identity it already has.
     *
     * The list entry IS refreshed, because [savedCopyOf] reads that list to answer "what is on
     * disk" and the unsaved flag is derived from the answer - an entry left stale would say the
     * Last Session record needs saving when it had just been written.
     */
    suspend fun saveLastSessionRecord(record: LayoutWorkspace): Boolean {
        val filePath =
            withContext(Dispatchers.IO) {
                fileManager.saveWorkspace(record)
            }
        if (filePath == null) {
            logger.warn(LogCategory.WORKSPACE, "Last Session record write failed")
            return false
        }
        _workspaces.value =
            _workspaces.value.toMutableList().also { workspaces ->
                val existingIndex = workspaces.indexOfFirst { it.name == record.name }
                if (existingIndex >= 0) workspaces[existingIndex] = record else workspaces.add(record)
            }
        return true
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
     * Persist [set] as the multi-Space session record, blocking until the bytes are down, and
     * return whether the write succeeded. A null [set] DELETES the record.
     *
     * The shutdown path's other half, beside [saveLastSessionBlocking], and blocking for the same
     * reason: a coroutine queued on `Dispatchers.Main` while the app is closing may never run.
     * Both are called under `LastSessionCoordinator`'s single claim, so the two files are written
     * together by one window and cannot disagree about which session they describe.
     *
     * The delete is not tidiness. Restore reads the set in preference to `Last_Session.json`, so a
     * set left behind by a three-Space session would reopen two Spaces after a session that had
     * closed them.
     */
    fun saveLastSessionSetBlocking(set: LastSessionSet?): Boolean {
        val written =
            fileManager.writeDocumentBlocking(
                LAST_SESSION_SET_FILE,
                set?.let { LastSessionSetSerializer.serialize(it) },
            )
        if (!written) {
            logger.warn(
                LogCategory.WORKSPACE,
                "Last Session set write failed",
                mapOf("spaces" to (set?.spaces?.size ?: 0).toString(), "removing" to (set == null).toString()),
            )
        }
        return written
    }

    /**
     * The multi-Space session record on disk, or null when there is none or it cannot be read.
     *
     * Null is the ordinary answer, not an error: an installed build upgrading into this has only
     * `Last_Session.json`, and a single-Space session deliberately writes no set. A file that
     * cannot be parsed is also null, so a truncated or hand-broken record falls back to the
     * single-Space restore rather than failing the launch.
     */
    suspend fun loadLastSessionSet(): LastSessionSet? {
        val json = fileManager.loadDocument(LAST_SESSION_SET_FILE) ?: return null
        return try {
            LastSessionSetSerializer.deserialize(json)
        } catch (e: Exception) {
            logger.warn(LogCategory.WORKSPACE, "Last Session set could not be read", error = e)
            null
        }
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
