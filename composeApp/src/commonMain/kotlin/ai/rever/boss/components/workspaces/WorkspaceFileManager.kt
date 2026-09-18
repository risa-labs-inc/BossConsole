package ai.rever.boss.components.workspaces

/**
 * Manages file-based workspace storage
 */
expect class WorkspaceFileManager(
    /**
     * Directory to store workspaces in. Defaults to the per-user documents
     * location; overridden by tests so they never write to a real home directory.
     */
    directoryOverride: String? = null,
) {
    /**
     * Get the default workspace directory path
     */
    fun getDefaultWorkspaceDirectory(): String

    /**
     * Ensure the workspace directory exists
     */
    suspend fun ensureWorkspaceDirectory(): Boolean

    /**
     * Save a workspace to a file
     */
    suspend fun saveWorkspace(
        workspace: LayoutWorkspace,
        fileName: String? = null,
    ): String?

    /**
     * Save a workspace to a file on the calling thread, returning only once the
     * bytes have been written.
     *
     * For shutdown paths, where dispatching the write to another thread or scope
     * risks the process exiting first. Everything else should use the suspending
     * [saveWorkspace].
     */
    fun saveWorkspaceBlocking(
        workspace: LayoutWorkspace,
        fileName: String? = null,
    ): String?

    /**
     * Load a workspace from a file
     */
    suspend fun loadWorkspace(fileName: String): LayoutWorkspace?

    /**
     * List all saved workspace files
     */
    suspend fun listWorkspaces(): List<WorkspaceFileInfo>

    /**
     * Delete a workspace file
     */
    suspend fun deleteWorkspace(fileName: String): Boolean

    /**
     * Get full path for a workspace file
     */
    fun getWorkspaceFilePath(fileName: String): String

    /**
     * Make [fileName] in the workspace directory hold [content], or be ABSENT when [content] is
     * null. Returns whether the directory now says what was asked.
     *
     * For records that live beside the Spaces without being one - `Last_Session_Set.json` and
     * `Space_Themes.json`. Not typed as a [LayoutWorkspace] because neither is one, and blocking
     * because the SHUTDOWN path needs it so: see [saveWorkspaceBlocking] for why a dispatch cannot
     * be trusted while the process is closing. It is not only the shutdown path's any more -
     * `WorkspaceManager.setSpaceTheme` writes through it from a running app, and takes itself to
     * `Dispatchers.IO` first, which is what a blocking verb asks of a caller that can afford to.
     *
     * **Write and remove are ONE verb** because the caller has one intention - make the record on
     * disk be the truth - and the removal is not tidiness: a session-set file left over from a
     * three-Space session is read in preference to `Last_Session.json`, so leaving it would
     * restore two Spaces the user had closed. The theme record uses the same null for the same
     * reason in a milder form: no Space having a theme of its own is exactly no file.
     */
    fun writeDocumentBlocking(
        fileName: String,
        content: String?,
    ): Boolean

    /** Read [fileName] from the workspace directory, or null if it is absent or unreadable. */
    suspend fun loadDocument(fileName: String): String?
}

/**
 * Information about a workspace file
 */
data class WorkspaceFileInfo(
    val fileName: String,
    val filePath: String,
    val lastModified: Long,
    val size: Long,
)

/**
 * Common workspace file manager functionality
 */
object WorkspaceFileManagerCommon {
    /**
     * Get the default workspace directory name
     */
    fun getDefaultWorkspaceDirectoryName(): String = "BOSS/workspaces"

    /**
     * The file a Space is written to: its ID.
     *
     * **A name is identity, not an address.** The path used to be
     * [generateFileName] of the display name, so one name was one file and two Spaces sharing a
     * name shared a file - the second save atomically replaced the first one's layout while both
     * rows stayed in the list. That hazard is what a `(saved)` suffix on the name was really
     * guarding, and it is reachable with no suffix in sight: `materialisedTemplateName` mints a
     * fresh id with no uniqueness check, so materialising one template twice against one project
     * gives two ids and one file, and a name typed into "Save Space..." bypasses
     * `uniqueWorkspaceName` altogether.
     *
     * An id is already unique by construction (`LayoutWorkspace.generateId()`), so this makes the
     * collision impossible rather than improbable, and it frees the name to be whatever the user
     * wants. Copied from `WorkspaceServiceImpl.persistToDisk`, which has written `<id>.json` all
     * along.
     *
     * It also closes a reserved-path collision as a class: `generateFileName("Last Session Set")`
     * resolved to `Last_Session_Set.json`, so a Space with that name overwrote the session record
     * and was then skipped on load, and nothing refused the name.
     *
     * Sanitised the same way, because an id read out of a hand-edited file is arbitrary text and a
     * path separator in it would escape the directory.
     */
    fun fileNameForId(workspaceId: String): String = "${sanitize(workspaceId)}.json"

    /**
     * Generate a filename from workspace name.
     *
     * **The LEGACY path, and a reader only.** Every file written before [fileNameForId] is named
     * this way, and `WorkspaceManager` keeps saving each of those Spaces into the file it was
     * loaded from - so nothing is rewritten or renamed on disk by an upgrade. Nothing derives a
     * NEW path from a name; use [fileNameForId] for that.
     */
    fun generateFileName(workspaceName: String): String = "${sanitize(workspaceName)}.json"

    private fun sanitize(value: String): String = value.replace(Regex("[^a-zA-Z0-9.-]"), "_")

    /**
     * Whether [fileName] names a file directly inside the workspace directory: one path component,
     * no separator of either platform, not `.` or `..`, no NUL. Every file this manager reads,
     * writes or deletes is named by such a name, and [getWorkspaceFilePath] refuses anything else,
     * so a name that reaches it from outside (an MCP argument, a hand-edited file) cannot climb out
     * of the directory. A refusal is deliberately not "sanitize and continue": a caller that built a
     * name with a separator in it has a bug, and quietly mapping it onto some other file would hide
     * that.
     *
     * The rule is lexical because Windows resolves `..` lexically too: `dir\missing\..\x.json`
     * reaches `dir\x.json` there even though `missing` does not exist, so an existence check on the
     * joined path is not a defence.
     */
    fun isBareFileName(fileName: String): Boolean =
        fileName.isNotEmpty() &&
            fileName != "." &&
            fileName != ".." &&
            fileName.none { it == '/' || it == '\\' || it.code == 0 }

    /**
     * Extract workspace name from filename
     */
    fun extractWorkspaceName(fileName: String): String = fileName.removeSuffix(".json").replace("_", " ")
}
