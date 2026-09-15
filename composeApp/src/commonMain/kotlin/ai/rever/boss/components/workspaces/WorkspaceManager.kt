package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.ui.BossThemeController
import ai.rever.boss.plugin.ui.BossThemes
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * Manages layout workspaces with file-based storage
 */
class WorkspaceManager internal constructor(
    private val fileManager: WorkspaceFileManager,
    private val scope: CoroutineScope,
    private val writeWorkspace: suspend (LayoutWorkspace, String) -> String? = { workspace, fileName ->
        withContext(Dispatchers.IO) { fileManager.saveWorkspace(workspace, fileName) }
    },
    private val removeWorkspace: suspend (String) -> Boolean = { fileName ->
        withContext(Dispatchers.IO) { fileManager.deleteWorkspace(fileName) }
    },
) {
    constructor() : this(WorkspaceFileManager(), CoroutineScope(Dispatchers.Main + SupervisorJob()))

    private val loaded = CompletableDeferred<Unit>()
    private val mutations = OrderedWorkspaceMutations(scope)

    /** Wait for the disk seed before making decisions about saved identities or legacy paths. */
    internal suspend fun awaitLoaded() = loaded.await()

    private val logger = BossLogger.forComponent("WorkspaceManager")
    private var currentRevision = 0L
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

    /**
     * The file each Space was LOADED from, by id, for the ones whose path predates
     * [WorkspaceFileManagerCommon.fileNameForId].
     *
     * A new Space is written to `<id>.json` and needs no entry. A Space read out of
     * `Code_Review.json` keeps saving into `Code_Review.json`, so **an upgrade rewrites and renames
     * nothing on disk** - the same in-memory-migration discipline `mergeSavedWorkspaces` uses, and
     * for the same reason: a launch that half-wrote would be worse than any naming.
     *
     * Populated by the load scan, which already reads every file's id out of its contents.
     */
    private val loadedFileNames = mutableMapOf<String, String>()

    /** Where [workspace] should be written: the file it came from, or `<id>.json`. */
    private fun fileNameFor(workspace: LayoutWorkspace): String =
        loadedFileNames[workspace.id] ?: WorkspaceFileManagerCommon.fileNameForId(workspace.id)

    // Callback for when a workspace is deleted
    private var onWorkspaceDeleted: ((String) -> Unit)? = null

    /**
     * Which theme each Space has been given, by workspace id - the OVERRIDES only.
     *
     * A Space that names none is not absent from the feature, it is riding a default:
     * [TEMPLATE_SPACE_THEMES] for one of the eight layouts BOSS ships, and the Settings choice for
     * everything else. [spaceThemeId] is what resolves the three; nothing should read this map
     * without it.
     */
    private val _spaceThemes = MutableStateFlow<Map<String, String>>(emptyMap())
    val spaceThemes: StateFlow<Map<String, String>> = _spaceThemes.asStateFlow()

    /**
     * Whether anything has assigned a Space theme since this manager was built.
     *
     * The disk read is a SEED, and a seed must not land on top of a decision. [loadSpaceThemes] is
     * asynchronous, so a `setSpaceTheme` made while it is still in flight - the template
     * inheritance in `WorkspaceTemplate` does exactly that, in the first moments of a manager's
     * life - was overwritten by whatever the file happened to say, silently and only sometimes.
     * Merging the two instead would fix a set and break a CLEAR, since a cleared key is absent
     * from both maps and the file would put it back.
     */
    private var spaceThemesAssigned = false

    /**
     * The colour each Space is wearing, by workspace id, for `ActiveTabsProvider.workspaceAccents`.
     *
     * Keyed over every Space the app knows - the eight shipped layouts and everything saved
     * ([workspaces] already holds both), plus whatever is running and whatever has an override -
     * because the panels that read this are listing Spaces they are not in. The union is taken
     * rather than [workspaces] alone so a Space running from a file that has since been deleted
     * still has a colour while it is on screen.
     *
     * Derived from all three inputs, so it moves when a Space is re-themed AND when the Settings
     * baseline changes: every Space with no theme of its own is riding that baseline, so a Settings
     * pick has to repaint all of them.
     */
    val spaceAccents: StateFlow<Map<String, Color>> =
        combine(
            _workspaces,
            _windowWorkspaces,
            _spaceThemes,
            SettingsThemeBaseline.themeId,
        ) { spaces, windows, overrides, baseline ->
            (spaces.map { it.id } + windows.values.flatten() + overrides.keys)
                .filter { it.isNotEmpty() }
                .distinct()
                .associateWith { BossThemes.byId(spaceThemeId(it, overrides, baseline)).colors.signal }
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** The theme [workspaceId] should be showing, resolved through [spaceThemeId]. */
    fun themeIdFor(workspaceId: String): String {
        val overrides = _spaceThemes.value
        return spaceThemeId(workspaceId, overrides, SettingsThemeBaseline.themeId.value)
    }

    /**
     * Give [workspaceId] a theme of its own, or hand it back to its default when [themeId] is null.
     *
     * **`BossThemeController.select` and nothing else.** `AppThemeSettingsManager.select` would
     * write the id into `app-theme-settings.json`, and after two Space switches the baseline the
     * user chose in Settings would be gone - along with the answer for every Space that names no
     * theme. A Space theme is an override LAYERED OVER that choice, which is why the two writes
     * land in different files.
     */
    fun setSpaceTheme(
        workspaceId: String,
        themeId: String?,
    ) {
        val updated = withSpaceTheme(_spaceThemes.value, workspaceId, themeId, SettingsThemeBaseline.themeId.value)
        // Marked even when nothing moves, because "the answer is already what you asked for" is
        // still a decision the seed must not overwrite.
        spaceThemesAssigned = true
        if (updated == _spaceThemes.value) return
        _spaceThemes.value = updated
        // Only when it is the Space on screen: re-theming a Space you are not in must not re-skin
        // the app out from under you.
        if (_currentWorkspace.value?.id == workspaceId) applySpaceTheme(workspaceId)
        scope.launch {
            val written =
                withContext(Dispatchers.IO) {
                    fileManager.writeDocumentBlocking(SPACE_THEMES_FILE, spaceThemesDocument(updated))
                }
            if (!written) {
                logger.warn(
                    LogCategory.WORKSPACE,
                    "Space theme write failed",
                    mapOf("workspace" to workspaceId, "themes" to updated.size.toString()),
                )
            }
        }
    }

    /** Put the app on [workspaceId]'s theme. A no-op for an id whose answer is already showing. */
    private fun applySpaceTheme(workspaceId: String) {
        BossThemeController.select(themeIdFor(workspaceId))
    }

    private fun loadSpaceThemes() {
        scope.launch {
            val json = fileManager.loadDocument(SPACE_THEMES_FILE)
            val assignments = spaceThemesFrom(json)
            if (json != null && assignments.isEmpty()) {
                // There were bytes and they said nothing: a broken record, or one naming only
                // themes this build has retired. An empty map is never written (see
                // `spaceThemesDocument`), so there is no innocent case this shouts about.
                logger.warn(LogCategory.WORKSPACE, "Space themes record could not be read")
            }
            // The seed lands only on a store nobody has written to yet. See [spaceThemesAssigned].
            if (!spaceThemesAssigned) _spaceThemes.value = assignments
            // Re-resolve whatever is already on screen. This read is asynchronous and the session
            // restore does not wait for it, so a Space entered first would be sitting on the
            // Settings theme rather than its own until the next switch.
            _currentWorkspace.value?.let { applySpaceTheme(it.id) }
        }
    }

    init {
        // Load workspaces from both predefined and saved files
        loadAllWorkspaces()
        loadSpaceThemes()
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
                    // And the Space-to-theme record, beside it and not a Space either. See
                    // SPACE_THEMES_FILE.
                    if (fileInfo.fileName == SPACE_THEMES_FILE) return@forEach
                    val workspace =
                        withContext(Dispatchers.IO) {
                            fileManager.loadWorkspace(fileInfo.fileName)
                        }
                    workspace?.let {
                        // Ensure workspace has an ID
                        val withId = if (it.id.isEmpty()) it.copy(id = LayoutWorkspace.generateId()) else it
                        saved.add(withId)
                        // Remember the file it came from, so a legacy path keeps being this
                        // Space's file. A file already named `<id>.json` records the same answer
                        // `fileNameFor` would derive, so the entry is harmless either way.
                        loadedFileNames[withId.id] = fileInfo.fileName
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
            loaded.complete(Unit)
        }
    }

    /**
     * Load a workspace - ENTER this Space, which is also what re-skins the app.
     *
     * **The one door.** Every way into a Space goes through here: the Space button and its menu,
     * the picker, the switch path, a deep link, the CLI, a plugin's `WorkspaceDataProvider`, the
     * fresh-start default and both session restores. Hanging the theme off this rather than off a
     * collector on [currentWorkspace] is what keeps a rename or a save - which also write that
     * flow - from being read as entering somewhere.
     *
     * With two windows on different Spaces the last switch wins, because there is one
     * `BossThemeController` for the process and one app to skin. That is accepted, and it is the
     * same thing [currentWorkspace] itself has always done.
     */
    fun loadWorkspace(workspace: LayoutWorkspace) {
        currentRevision++
        _currentWorkspace.value = workspace
        applySpaceTheme(workspace.id)
    }

    /**
     * Save current workspace to disk
     */
    fun saveCurrentWorkspace(name: String? = null): LayoutWorkspace? {
        val current = _currentWorkspace.value ?: return null
        val saved = prepareSavedWorkspace(current, name)
        enqueueSave(current, saved, name)
        // Compatibility: this is the proposed snapshot, not a persistence acknowledgement.
        // Call saveWorkspaceAwait when a later action depends on the write succeeding.
        return saved
    }

    /** Complete only after the ordered disk write and saved-list publication succeed. */
    suspend fun saveWorkspaceAwait(
        current: LayoutWorkspace,
        name: String? = null,
    ): Result<LayoutWorkspace> = enqueueSave(current, prepareSavedWorkspace(current, name), name).await()

    private fun prepareSavedWorkspace(
        current: LayoutWorkspace,
        name: String?,
    ): LayoutWorkspace {
        val now = Clock.System.now().toEpochMilliseconds()
        return if (isSpaceSlot(current.id)) {
            // Saving while the current Space is a SLOT - a shipped layout, or the `last-session`
            // autosave record - creates the user's own copy rather than writing over the slot:
            // a new id, and a name that collides with nothing and is never "Last Session".
            // Writing the slot's own id and name is what the old behaviour did, and the file it
            // produced was silently dropped on the next launch. See `savedCopyOfSlot`.
            savedCopyOfSlot(
                current = current,
                id = LayoutWorkspace.generateId(),
                now = now,
                // Only other SPACES, so saving out of the shipped Claude Code gives a Space
                // called "Claude Code" rather than "Claude Code 2". See `savedSpaceNames`.
                takenNames = savedSpaceNames(_workspaces.value),
                requestedName = name,
            )
        } else {
            current.copy(
                id = current.id.ifEmpty { LayoutWorkspace.generateId() },
                // A typed name goes through `uniqueWorkspaceName` too, which it used to
                // bypass entirely - the one path that could still put two identical rows in
                // the list. Its own name is never "taken" by itself, so re-saving a Space
                // under the name it already has is not numbered.
                name =
                    name?.let {
                        uniqueWorkspaceName(it, savedSpaceNames(_workspaces.value) - current.name)
                    } ?: current.name,
                timestamp = now,
            )
        }
    }

    private fun enqueueSave(
        current: LayoutWorkspace,
        proposed: LayoutWorkspace,
        requestedName: String?,
        expectedRevision: Long = currentRevision,
    ): Deferred<Result<LayoutWorkspace>> =
        mutations.submit {
            loaded.await()
            val latest = savedCopyOf(proposed.id)
            // A rename queued ahead of this save owns the display name. The caller's live layout
            // can be older metadata while still containing newer tabs that need saving.
            val takenNames = savedSpaceNames(_workspaces.value.filter { it.id != proposed.id })
            val committedName =
                when {
                    requestedName == null && latest != null -> {
                        latest.name
                    }

                    isSpaceSlot(current.id) -> {
                        savedCopyOfSlot(
                            current = current,
                            id = proposed.id,
                            now = proposed.timestamp,
                            takenNames = takenNames,
                            requestedName = requestedName,
                        ).name
                    }

                    requestedName != null -> {
                        uniqueWorkspaceName(requestedName, takenNames)
                    }

                    else -> {
                        proposed.name
                    }
                }
            val saved = proposed.copy(name = committedName)
            persistWorkspace(saved)
            // Saving is not switching. Neither another Space nor edits made during I/O may be
            // replaced by the captured snapshot. Equality is insufficient after an A -> B -> A switch.
            if (currentRevision == expectedRevision && _currentWorkspace.value?.id == current.id) {
                _currentWorkspace.value = saved
            }
            saved
        }

    private suspend fun persistWorkspace(workspace: LayoutWorkspace) {
        val fileName = fileNameFor(workspace)
        check(writeWorkspace(workspace, fileName) != null) { "Could not save Space '${workspace.name}'" }
        loadedFileNames[workspace.id] = fileName
        val existing = _workspaces.value.indexOfFirst { it.id == workspace.id }
        _workspaces.value =
            _workspaces.value.toMutableList().also {
                if (existing >= 0) it[existing] = workspace else it.add(workspace)
            }
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
        val fileName = fileNameFor(record)
        val filePath =
            withContext(Dispatchers.IO) {
                fileManager.saveWorkspace(record, fileName)
            }
        if (filePath == null) {
            logger.warn(LogCategory.WORKSPACE, "Last Session record write failed")
            return false
        }
        loadedFileNames[record.id] = fileName
        _workspaces.value =
            _workspaces.value.toMutableList().also { workspaces ->
                // By ID. By NAME this wrote over whatever row happened to be called "Last
                // Session", which after the merge stopped keying on names can be a Space of the
                // user's that is merely CALLED that.
                val existingIndex = workspaces.indexOfFirst { it.id == record.id }
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
        val fileName = fileNameFor(lastSession)
        val filePath = fileManager.saveWorkspaceBlocking(lastSession, fileName)
        if (filePath == null) {
            logger.warn(LogCategory.WORKSPACE, "Last Session save failed", mapOf("workspace" to lastSession.name))
            return false
        }
        loadedFileNames[lastSession.id] = fileName
        currentRevision++
        _currentWorkspace.value = lastSession
        _workspaces.value =
            _workspaces.value.toMutableList().also { workspaces ->
                // By ID, for the reason `saveLastSessionRecord` states.
                val existingIndex = workspaces.indexOfFirst { it.id == lastSession.id }
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
        currentRevision++
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

            // Import shares the named-Space writer; an old import cannot resurrect a deleted
            // entry after a later delete, and failed writes never publish a saved-list entry.
            mutations.submit {
                loaded.await()
                persistWorkspace(workspace)
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
     * Delete the Space named [name].
     *
     * **Resolves the name to ONE Space and then works by id.** Two rows can share a name now that
     * the merge keys on ids, and by name this filtered BOTH out of the list while deleting one
     * file and firing `onWorkspaceDeleted` once - so the second Space vanished from every list
     * while its tabs were never torn down and its file stayed on disk. The name form is kept
     * because it is the plugin api's shape (`WorkspaceDataProvider.deleteWorkspace(name)`); a
     * caller that knows which one it means should use [deleteWorkspaceById].
     */
    fun deleteWorkspace(name: String) {
        val workspace = _workspaces.value.find { it.name == name } ?: return
        deleteWorkspaceById(workspace.id)
    }

    /** Delete the Space with [workspaceId]. Refuses a shipped layout, which has no file to delete. */
    fun deleteWorkspaceById(workspaceId: String) {
        enqueueDelete(workspaceId)
    }

    suspend fun deleteWorkspaceByIdAwait(workspaceId: String): Result<Unit> = enqueueDelete(workspaceId).await()

    private fun enqueueDelete(workspaceId: String): Deferred<Result<Unit>> =
        mutations.submit {
            loaded.await()
            val workspace = savedCopyOf(workspaceId) ?: error("Space no longer exists")
            check(isUserOwnedSpace(workspaceId)) { "A shipped Space cannot be deleted" }
            check(removeWorkspace(fileNameFor(workspace))) { "Could not delete Space '${workspace.name}'" }
            _workspaces.value = _workspaces.value.filter { it.id != workspaceId }
            loadedFileNames.remove(workspaceId)
            // Commit state before notifying external callbacks; their failure cannot undo the disk.
            if (_currentWorkspace.value?.id == workspaceId) resetToDefault()
            try {
                onWorkspaceDeleted?.invoke(workspaceId)
            } catch (e: Exception) {
                logger.warn(LogCategory.WORKSPACE, "Space deleted but cleanup callback failed", error = e)
            }
        }

    /**
     * Rename a workspace
     */
    fun renameWorkspace(
        oldName: String,
        newName: String,
    ) {
        val workspace = _workspaces.value.find { it.name == oldName } ?: return
        renameWorkspaceById(workspace.id, newName)
    }

    /**
     * Rename the Space with [workspaceId].
     *
     * **A rename no longer moves a file.** The path comes from the id, so writing the renamed Space
     * to the same file IS the rename - where the name-derived path needed a write-then-delete pair
     * that left the old file behind whenever the write failed. It also means a legacy file keeps
     * its path under a new display name, which is what stops an upgrade renaming anything on disk.
     */
    fun renameWorkspaceById(
        workspaceId: String,
        newName: String,
    ) {
        enqueueRename(workspaceId, newName)
    }

    suspend fun renameWorkspaceByIdAwait(
        workspaceId: String,
        newName: String,
    ): Result<LayoutWorkspace> = enqueueRename(workspaceId, newName).await()

    private fun enqueueRename(
        workspaceId: String,
        newName: String,
    ): Deferred<Result<LayoutWorkspace>> =
        mutations.submit {
            loaded.await()
            // Read after every earlier write has committed, so renaming retains newly saved tabs.
            val existing = savedCopyOf(workspaceId) ?: error("Space no longer exists")
            check(isUserOwnedSpace(workspaceId)) { "A shipped Space cannot be renamed" }
            require(newName.isNotEmpty()) { "Space name cannot be empty" }
            check(_workspaces.value.none { it.id != workspaceId && it.name == newName }) {
                "Space name is already in use"
            }
            if (existing.name == newName) return@submit existing
            val renamed = existing.copy(name = newName, timestamp = Clock.System.now().toEpochMilliseconds())
            persistWorkspace(renamed)
            // Rename only the identity metadata of the live layout, never its unsaved contents.
            _currentWorkspace.value?.takeIf { it.id == workspaceId }?.let {
                _currentWorkspace.value = it.copy(name = newName)
            }
            renamed
        }

    /**
     * Update current workspace with new layout
     */
    fun updateCurrentWorkspace(newWorkspace: LayoutWorkspace) {
        currentRevision++
        _currentWorkspace.value = newWorkspace
    }

    /**
     * Get the workspace directory path
     */
    fun getWorkspaceDirectory(): String = fileManager.getDefaultWorkspaceDirectory()
}

// Global instance
val workspaceManager = WorkspaceManager()
