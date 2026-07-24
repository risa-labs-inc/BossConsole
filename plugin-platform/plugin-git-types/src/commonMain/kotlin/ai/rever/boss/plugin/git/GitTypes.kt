package ai.rever.boss.plugin.git

/**
 * Information about a Git branch.
 *
 * @param name The branch name (e.g., "main", "origin/main")
 * @param isCurrent Whether this is the currently checked out branch
 * @param isRemote Whether this is a remote tracking branch
 */
data class GitBranchInfo(
    val name: String,
    val isCurrent: Boolean = false,
    val isRemote: Boolean = false,
)

/**
 * Result of a Git operation.
 */
sealed class GitOperationResult {
    data class Success(
        val message: String = "",
    ) : GitOperationResult()

    data class Error(
        val message: String,
        val exitCode: Int = -1,
    ) : GitOperationResult()
}

/**
 * Type of file status in Git.
 */
enum class GitFileStatusType {
    MODIFIED,
    ADDED,
    DELETED,
    RENAMED,
    COPIED,
    UNTRACKED,
    IGNORED,
    UNMERGED,
}

/**
 * Information about a file's Git status.
 *
 * @param path The relative path to the file
 * @param indexStatus Status in the index (staged area)
 * @param workTreeStatus Status in the working tree
 * @param isStaged Whether the file has staged changes
 * @param isUnstaged Whether the file has unstaged changes
 * @param originalPath For renames/copies, the original file path
 */
data class GitFileStatus(
    val path: String,
    val indexStatus: GitFileStatusType?,
    val workTreeStatus: GitFileStatusType?,
    val isStaged: Boolean,
    val isUnstaged: Boolean,
    val originalPath: String? = null,
)

/**
 * Information about a Git commit.
 *
 * @param hash Full commit hash
 * @param shortHash Short commit hash (7 chars)
 * @param author Author name
 * @param authorEmail Author email
 * @param date Commit timestamp (epoch seconds)
 * @param subject First line of commit message
 * @param body Rest of commit message (may be null)
 * @param refs Branch/tag names pointing to this commit
 * @param parentHashes Parent commit hashes
 */
data class GitCommitInfo(
    val hash: String,
    val shortHash: String,
    val author: String,
    val authorEmail: String,
    val date: Long,
    val subject: String,
    val body: String? = null,
    val refs: List<String> = emptyList(),
    val parentHashes: List<String> = emptyList(),
)

/**
 * Information about a Git stash entry.
 *
 * @param index Stash index (0, 1, 2, etc.)
 * @param message Stash message
 * @param branch Branch the stash was created on
 */
data class GitStashInfo(
    val index: Int,
    val message: String,
    val branch: String?,
)

/**
 * Event for opening a git command in the terminal.
 *
 * @param command The full git command to execute (including 'git' prefix)
 * @param workingDirectory The working directory for the command
 * @param operationName Human-readable name for the operation (e.g., "Pull", "Push")
 * @param sourceWindowId The window ID that initiated the event (for per-window filtering)
 */
data class GitTerminalOpenEvent(
    val command: String,
    val workingDirectory: String,
    val operationName: String,
    val sourceWindowId: String,
)
