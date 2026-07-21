@file:Suppress("UNUSED")
package ai.rever.boss.git

import kotlinx.coroutines.flow.StateFlow

/**
 * Re-exports from plugin-git-types module for backward compatibility.
 * New code should import directly from ai.rever.boss.plugin.git
 */
typealias GitBranchInfo = ai.rever.boss.plugin.git.GitBranchInfo
typealias GitOperationResult = ai.rever.boss.plugin.git.GitOperationResult
typealias GitFileStatusType = ai.rever.boss.plugin.git.GitFileStatusType
typealias GitFileStatus = ai.rever.boss.plugin.git.GitFileStatus
typealias GitCommitInfo = ai.rever.boss.plugin.git.GitCommitInfo
typealias GitStashInfo = ai.rever.boss.plugin.git.GitStashInfo

/**
 * Service for Git operations.
 *
 * Uses git CLI for all operations - requires git to be installed on the system.
 * Follows the expect/actual pattern for platform abstraction.
 *
 * Issue #90: Git Integration for Top Bar
 */
expect object GitService {
    /**
     * Current branch name, or null if not a Git repository or in detached HEAD state.
     * In detached HEAD state, this will contain the short SHA.
     */
    val currentBranch: StateFlow<String?>

    /**
     * Whether the current project is a Git repository.
     */
    val isGitRepository: StateFlow<Boolean>

    /**
     * List of local branches.
     * The current branch (if any) will have isCurrent = true.
     */
    val localBranches: StateFlow<List<GitBranchInfo>>

    /**
     * List of remote tracking branches (e.g., origin/main).
     */
    val remoteBranches: StateFlow<List<GitBranchInfo>>

    /**
     * Whether Git is available on the system.
     */
    val isGitAvailable: StateFlow<Boolean>

    /**
     * Whether a Git operation is in progress.
     */
    val isLoading: StateFlow<Boolean>

    /**
     * Last error message, if any.
     */
    val lastError: StateFlow<String?>

    /**
     * Initialize/refresh Git state for a project path.
     * Should be called when project changes.
     *
     * @param projectPath The root path of the project
     */
    suspend fun refresh(projectPath: String)

    /**
     * Checkout an existing branch.
     * Works for both local and remote branches.
     * When checking out a remote branch (e.g., origin/feature), git will create
     * a local tracking branch automatically.
     *
     * @param branchName The branch name to checkout
     * @param windowId Optional window ID to update window-specific state after operation
     * @return Result indicating success or failure
     */
    suspend fun checkout(branchName: String, windowId: String? = null): GitOperationResult

    /**
     * Create a new branch.
     *
     * @param branchName The new branch name
     * @param checkout If true, checkout the new branch immediately
     * @param windowId Optional window ID to update window-specific state after operation
     * @return Result indicating success or failure
     */
    suspend fun createBranch(branchName: String, checkout: Boolean = true, windowId: String? = null): GitOperationResult

    /**
     * Pull changes from remote.
     *
     * @return Result indicating success or failure
     */
    suspend fun pull(): GitOperationResult

    /**
     * Push changes to remote.
     *
     * @return Result indicating success or failure
     */
    suspend fun push(): GitOperationResult

    /**
     * Get the URL for creating a pull request in the browser.
     * Returns the GitHub/GitLab PR creation URL based on the remote origin.
     *
     * @return The PR creation URL, or null if not a supported remote
     */
    suspend fun getCreatePRUrl(): String?

    /**
     * Merge a branch into the current branch.
     *
     * @param branchName The branch to merge into current
     * @return Result indicating success or failure
     */
    suspend fun merge(branchName: String): GitOperationResult

    /**
     * Rebase current branch onto another branch.
     *
     * @param branchName The branch to rebase onto
     * @return Result indicating success or failure
     */
    suspend fun rebase(branchName: String): GitOperationResult

    /**
     * Clear Git state (when no project is selected).
     */
    fun clear()

    // ===== File Status & Staging =====

    /**
     * List of files with their Git status (staged, unstaged, untracked).
     */
    val fileStatus: StateFlow<List<GitFileStatus>>

    /**
     * Get current file status (refreshes the fileStatus StateFlow).
     *
     * @return List of files with their status
     */
    suspend fun getStatus(): List<GitFileStatus>

    /**
     * Stage a file for commit.
     *
     * @param filePath Path to the file (relative to project root)
     * @param windowId Optional window ID to update window-specific state after operation
     * @return Result indicating success or failure
     */
    suspend fun stage(filePath: String, windowId: String? = null): GitOperationResult

    /**
     * Stage all modified files.
     *
     * @param windowId Optional window ID to update window-specific state after operation
     * @return Result indicating success or failure
     */
    suspend fun stageAll(windowId: String? = null): GitOperationResult

    /**
     * Unstage a file.
     *
     * @param filePath Path to the file (relative to project root)
     * @param windowId Optional window ID to update window-specific state after operation
     * @return Result indicating success or failure
     */
    suspend fun unstage(filePath: String, windowId: String? = null): GitOperationResult

    /**
     * Unstage all staged files.
     *
     * @param windowId Optional window ID to update window-specific state after operation
     * @return Result indicating success or failure
     */
    suspend fun unstageAll(windowId: String? = null): GitOperationResult

    /**
     * Discard changes to a file in the working tree.
     *
     * @param filePath Path to the file (relative to project root)
     * @param windowId Optional window ID to update window-specific state after operation
     * @return Result indicating success or failure
     */
    suspend fun discardChanges(filePath: String, windowId: String? = null): GitOperationResult

    // ===== Commit =====

    /**
     * Commit staged changes.
     *
     * @param message Commit message
     * @param amend Whether to amend the previous commit
     * @param windowId Optional window ID to update window-specific state after operation
     * @return Result indicating success or failure
     */
    suspend fun commit(message: String, amend: Boolean = false, windowId: String? = null): GitOperationResult

    /**
     * Get the last commit message (for amending).
     *
     * @return Last commit message, or null if no commits
     */
    suspend fun getLastCommitMessage(): String?

    // ===== Commit Log =====

    /**
     * List of recent commits.
     */
    val commitLog: StateFlow<List<GitCommitInfo>>

    /**
     * Get commit log (refreshes the commitLog StateFlow).
     *
     * @param limit Maximum number of commits to retrieve
     * @return List of commits
     */
    suspend fun getLog(limit: Int = 100): List<GitCommitInfo>

    /**
     * Cherry-pick a commit onto the current branch.
     *
     * @param commitHash The commit hash to cherry-pick
     * @return Result indicating success or failure
     */
    suspend fun cherryPick(commitHash: String): GitOperationResult

    /**
     * Revert a commit.
     *
     * @param commitHash The commit hash to revert
     * @return Result indicating success or failure
     */
    suspend fun revert(commitHash: String): GitOperationResult

    // ===== Stash =====

    /**
     * List of stash entries.
     */
    val stashList: StateFlow<List<GitStashInfo>>

    /**
     * Stash current changes.
     *
     * @param message Optional stash message
     * @param includeUntracked Whether to include untracked files
     * @return Result indicating success or failure
     */
    suspend fun stash(message: String? = null, includeUntracked: Boolean = false): GitOperationResult

    /**
     * Pop the latest stash (apply and delete).
     *
     * @param index Stash index to pop (default 0 = latest)
     * @return Result indicating success or failure
     */
    suspend fun stashPop(index: Int = 0): GitOperationResult

    /**
     * Apply a stash without deleting it.
     *
     * @param index Stash index to apply (default 0 = latest)
     * @return Result indicating success or failure
     */
    suspend fun stashApply(index: Int = 0): GitOperationResult

    /**
     * Drop (delete) a stash entry.
     *
     * @param index Stash index to drop
     * @return Result indicating success or failure
     */
    suspend fun stashDrop(index: Int): GitOperationResult

    /**
     * Refresh stash list.
     *
     * @return List of stash entries
     */
    suspend fun refreshStashList(): List<GitStashInfo>

    // ===== Terminal Integration =====

    /**
     * Run git pull in the terminal (for real-time output).
     *
     * @param windowId The window ID for per-window terminal isolation (Issue #498)
     */
    suspend fun pullInTerminal(windowId: String)

    /**
     * Run git push in the terminal (for real-time output).
     *
     * @param windowId The window ID for per-window terminal isolation (Issue #498)
     */
    suspend fun pushInTerminal(windowId: String)

    /**
     * Run git merge in the terminal (for real-time output).
     *
     * @param windowId The window ID for per-window terminal isolation (Issue #498)
     * @param branchName Branch to merge
     */
    suspend fun mergeInTerminal(windowId: String, branchName: String)

    /**
     * Run git rebase in the terminal (for real-time output).
     *
     * @param windowId The window ID for per-window terminal isolation (Issue #498)
     * @param branchName Branch to rebase onto
     */
    suspend fun rebaseInTerminal(windowId: String, branchName: String)

    /**
     * Run a custom git command in the terminal.
     *
     * @param windowId The window ID for per-window terminal isolation (Issue #498)
     * @param args Git command arguments (without 'git' prefix)
     */
    suspend fun runInTerminal(windowId: String, vararg args: String)

    /**
     * Get the current project path (for terminal commands).
     */
    fun getCurrentProjectPath(): String?

    // ===== Clone Repository =====

    /**
     * Clone a Git repository to the specified directory.
     *
     * @param repositoryUrl The URL of the repository to clone (https://, git@, ssh://)
     * @param targetDirectory The directory where the repository should be cloned
     * @param onProgress Callback for progress updates (receives progress messages)
     * @return Result indicating success or failure with appropriate message
     */
    suspend fun cloneRepository(
        repositoryUrl: String,
        targetDirectory: String,
        onProgress: (String) -> Unit = {}
    ): GitOperationResult

    // ===== Window-Specific Operations =====

    /**
     * Refresh git state for a specific window.
     * Updates the provided WindowGitState instead of global state.
     *
     * This allows multiple windows to have independent git states,
     * fixing the issue where opening a new window with no project
     * would hide git UI in all windows.
     *
     * @param projectPath The root path of the project
     * @param windowGitState The window-specific git state to update
     */
    suspend fun refreshForWindow(projectPath: String, windowGitState: ai.rever.boss.window.WindowGitState?)

    /**
     * Refresh stash list for a specific window.
     *
     * @param windowGitState The window-specific git state to update
     * @return List of stash entries
     */
    suspend fun refreshStashListForWindow(windowGitState: ai.rever.boss.window.WindowGitState?): List<GitStashInfo>

    /**
     * Get file status for a specific window.
     *
     * @param windowGitState The window-specific git state to update
     * @return List of files with their status
     */
    suspend fun getStatusForWindow(windowGitState: ai.rever.boss.window.WindowGitState?): List<GitFileStatus>

    /**
     * Get commit log for a specific window.
     *
     * @param windowGitState The window-specific git state to update
     * @param limit Maximum number of commits to retrieve
     * @return List of commits
     */
    suspend fun getLogForWindow(windowGitState: ai.rever.boss.window.WindowGitState?, limit: Int = 100): List<GitCommitInfo>

    /**
     * Watch the project's `.git/HEAD` (and refs) for external mutations and
     * refresh [windowGitState] whenever an external `git checkout`,
     * `git switch`, or rebase changes the current branch / SHA. Suspends
     * forever; cancel the surrounding job to stop watching (e.g. when the
     * selected project changes or the window closes).
     *
     * Without this watcher BossTopBar only refreshes git state on project
     * change, so a CLI/filesystem checkout leaves the top-bar branch label
     * stale until the user does something that triggers a refresh inside
     * the app.
     *
     * @param projectPath The root path of the project (must be a git repo).
     * @param windowGitState The window-specific git state to refresh on change.
     */
    suspend fun watchGitHeadForWindow(
        projectPath: String,
        windowGitState: ai.rever.boss.window.WindowGitState?,
    )
}
