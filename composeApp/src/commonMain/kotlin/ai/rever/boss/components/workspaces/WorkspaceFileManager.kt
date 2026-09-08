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
     * For records that live beside the Spaces without being one - `Last_Session_Set.json`. Not
     * typed as a [LayoutWorkspace] because it is not one, and blocking rather than suspending
     * because the shutdown path is the only caller: see [saveWorkspaceBlocking] for why a dispatch
     * cannot be trusted while the process is closing.
     *
     * **Write and remove are ONE verb** because the caller has one intention - make the record on
     * disk be the truth - and the removal is not tidiness: a session-set file left over from a
     * three-Space session is read in preference to `Last_Session.json`, so leaving it would
     * restore two Spaces the user had closed.
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
     * Generate a filename from workspace name
     */
    fun generateFileName(workspaceName: String): String {
        // Replace spaces and special characters with underscores
        val sanitized = workspaceName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        return "$sanitized.json"
    }

    /**
     * Extract workspace name from filename
     */
    fun extractWorkspaceName(fileName: String): String = fileName.removeSuffix(".json").replace("_", " ")
}
