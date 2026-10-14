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
     * Get full path for a workspace file.
     *
     * @param fileName a bare file name in the workspace directory, as
     *   [WorkspaceFileManagerCommon.isBareFileName] defines one. Callers inside this class derive
     *   names from [WorkspaceFileManagerCommon.fileNameForId] or from a real directory listing, so
     *   they always satisfy it.
     * @throws IllegalArgumentException when [fileName] is not such a name. The refusal is the point
     *   (a name with a separator in it would address a file outside the directory), so a caller
     *   passing a name it did not derive should expect it and not assume a null or a false.
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
     * [LayoutWorkspace.generateId] adds entropy to make accidental id collisions very unlikely.
     * Deriving the path from that id lets Spaces share a display name without sharing a file.
     * Copied from `WorkspaceServiceImpl.persistToDisk`, which has written `<id>.json` all along.
     *
     * It also closes a reserved-path collision as a class: `generateFileName("Last Session Set")`
     * resolved to `Last_Session_Set.json`, so a Space with that name overwrote the session record
     * and was then skipped on load, and nothing refused the name.
     *
     * Sanitised the same way, because an id read out of a hand-edited file is arbitrary text and a
     * path separator in it would escape the directory.
     *
     * A blank id is refused rather than sanitised: `fileNameForId("")` is the literal file
     * `.json`, which every id-less Space would resolve to and share.
     */
    fun fileNameForId(workspaceId: String): String {
        require(workspaceId.isNotBlank()) { "a workspace file name needs a non-blank id" }
        return "${sanitize(workspaceId)}.json"
    }

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
     * The record files that live in the workspaces directory without being Spaces: the
     * session-set store and the Space-theme store - exactly the files [WorkspaceManager]'s
     * directory scan skips by name (see `LAST_SESSION_SET_FILE` / `SPACE_THEMES_FILE`).
     *
     * **One list for both sides of the boundary.** The scan skips these because they are not
     * Spaces; every write that reaches the directory on a caller-chosen name must refuse the
     * same files, or a workspace tool can be aimed at the host's own persisted records: an id
     * whose [fileNameForId] lands here does not save a Space, it overwrites the store. A
     * `Space_Themes.json` written through a workspace tool silently wipes every Space's theme
     * assignment, and a `Last_Session_Set.json` that is not a session-set record makes the
     * next launch's session restore wrong or absent (#926). Keeping the scan's skips on this
     * list too means neither side can drift from the other; the write side extends it rather
     * than replaces it - see [reservedRecordFileNames].
     */
    val reservedDocumentFileNames: Set<String> = setOf(LAST_SESSION_SET_FILE, SPACE_THEMES_FILE)

    /**
     * The files a caller-chosen workspace id must never persist onto: the document records
     * above, plus the two names of the single-Space session record (#964, #1643) - the legacy
     * `Last_Session.json` a pre-[fileNameForId] install wrote every session, and
     * `last-session.json`, the file [fileNameForId] of [LAST_SESSION_ID] writes for the live
     * record. Unlike
     * the document records these DO parse as Spaces: the load scan reads the real session
     * record, so they are not scan skips - but the write side still refuses the name, or one
     * caller-chosen id destroys the crash-recovery record.
     *
     * This is the one list both write gates consult: the MCP path's refusal helper
     * (`WorkspaceMcpToolProvider.refusalForReservedStoreFile`, via
     * [reservedWorkspaceStoreFileName]) and the import gate (`withImportableId`), so the two
     * cannot drift apart. A new DOCUMENT record belongs in [reservedDocumentFileNames], which
     * carries it here automatically; a new session-record spelling belongs here.
     */
    val reservedRecordFileNames: Set<String> =
        reservedDocumentFileNames + setOf(LEGACY_LAST_SESSION_FILE, fileNameForId(LAST_SESSION_ID))

    /** [reservedDocumentFileNames] folded once for the case-insensitive comparison below. */
    private val reservedDocumentFileNamesFolded: Set<String> = reservedDocumentFileNames.map { it.lowercase() }.toSet()

    /**
     * Whether [fileName] is one of the reserved record files (see [reservedDocumentFileNames]) -
     * the gate a caller-chosen name passes before anything is written.
     *
     * Compared case-folded because the filesystems this lands on fold too: APFS and NTFS are
     * case-insensitive by default, so an exact-match gate is bypassed by spelling the same
     * record in lower case (`space_themes.json` IS `Space_Themes.json` there), and the
     * lowercase write replaces the store the gate exists to protect (#926).
     */
    fun isReservedDocumentFileName(fileName: String): Boolean = fileName.lowercase() in reservedDocumentFileNamesFolded

    /**
     * Whether [fileName] names a file directly inside the workspace directory: one path component
     * that addresses a file in it and nothing else. Every file this manager reads, writes or
     * deletes is named by such a name, and [getWorkspaceFilePath] refuses anything else, so a name
     * that reaches it from outside (an MCP argument, a hand-edited file) cannot climb out of the
     * directory. A refusal is deliberately not "sanitize and continue": a caller that built a name
     * with a separator in it has a bug, and quietly mapping it onto some other file would hide it.
     *
     * The rule is lexical because Windows resolves `..` lexically too: `dir\missing\..\x.json`
     * reaches `dir\x.json` there even though `missing` does not exist, so an existence check on the
     * joined path is not a defence. For the same reason it judges the name Win32 would *use*, not
     * the one it was handed: Win32 trims trailing spaces and dots from a path component, so `".. "`
     * addresses the parent directory there while passing a naive `!= ".."` test. Trimming collapses
     * every all-dots-and-spaces name (`.`, `..`, `...`, `".. "`) to empty, which is why one
     * emptiness test covers them all. `:` is refused because it introduces a drive (`C:x.json`) or
     * an alternate data stream (`notes.json:evil`); on the JDK those throw `InvalidPathException`
     * at the join today, which is an accident of the JDK rather than something this rule states.
     * An empty name is refused because `Paths.get(dir, "")` is `dir` itself, so it addresses the
     * workspace directory, and `deleteWorkspace("")` then aimed at the directory rather than at a
     * file in it.
     *
     * This is the **name**-level rule. [ai.rever.boss.mcp.isSafeWorkspaceId] is the **id**-level
     * one, applied earlier at the MCP tool boundary, and the two deliberately differ: an id is a
     * caller-chosen identifier, so it refuses any `..` substring and every ISO control character,
     * while a name here may legitimately contain `..` inside it (`my..space.json`). Neither implies
     * the other; this one is the last gate, and holds for names that never passed the first.
     */
    fun isBareFileName(fileName: String): Boolean =
        // The component Win32 would actually resolve, which is the one that has to be a file name.
        fileName.trimEnd(' ', '.').isNotEmpty() &&
            fileName.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() }

    /**
     * Extract workspace name from filename
     */
    fun extractWorkspaceName(fileName: String): String = fileName.removeSuffix(".json").replace("_", " ")
}
