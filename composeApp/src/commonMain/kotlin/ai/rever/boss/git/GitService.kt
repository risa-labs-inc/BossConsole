@file:Suppress("UNUSED")

package ai.rever.boss.git

import ai.rever.boss.plugin.api.GitDiffData
import ai.rever.boss.plugin.api.GitFileStatusData
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
     * True while ANY git command is running or queued on the process-wide git
     * lock - all windows, all repos, including status polls. [isLoading] tracks
     * one operation's own button state; this is what the user needs when a
     * DIFFERENT window's slow command (an index write now holds the lock for up
     * to ten minutes) freezes every git read: "a git command is running",
     * rather than an unexplained blank.
     */
    val gitCommandsRunning: StateFlow<Boolean>

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
     * @param projectPathOverride Repository to act on; null falls back to the global
     *   current project path. Window-scoped callers pass their own (see the write-verbs
     *   note below) - the global belongs to whichever window aligned it last.
     * @return Result indicating success or failure
     */
    suspend fun checkout(
        branchName: String,
        windowId: String? = null,
        projectPathOverride: String? = null,
    ): GitOperationResult

    /**
     * Create a new branch.
     *
     * @param branchName The new branch name
     * @param checkout If true, checkout the new branch immediately
     * @param windowId Optional window ID to update window-specific state after operation
     * @return Result indicating success or failure
     */
    suspend fun createBranch(
        branchName: String,
        checkout: Boolean = true,
        windowId: String? = null,
        projectPathOverride: String? = null,
    ): GitOperationResult

    /**
     * Pull changes from remote.
     *
     * @param projectPathOverride The repo to run in (the caller's window's project).
     * @return Result indicating success or failure
     */
    suspend fun pull(projectPathOverride: String? = null): GitOperationResult

    /**
     * Push changes to remote.
     *
     * @param projectPathOverride The repo to run in (the caller's window's project).
     * @return Result indicating success or failure
     */
    suspend fun push(projectPathOverride: String? = null): GitOperationResult

    /**
     * Get the URL for creating a pull request in the browser.
     * Returns the GitHub/GitLab PR creation URL based on the remote origin.
     *
     * @param projectPathOverride The repo to read the remote from (the caller's window's project).
     * @return The PR creation URL, or null if not a supported remote
     */
    suspend fun getCreatePRUrl(projectPathOverride: String? = null): String?

    /**
     * Merge a branch into the current branch.
     *
     * @param branchName The branch to merge into current
     * @param projectPathOverride The repo to merge in (the caller's window's project).
     * @return Result indicating success or failure
     */
    suspend fun merge(
        branchName: String,
        projectPathOverride: String? = null,
    ): GitOperationResult

    /**
     * Rebase current branch onto another branch.
     *
     * @param branchName The branch to rebase onto
     * @param projectPathOverride The repo to rebase in (the caller's window's project).
     * @return Result indicating success or failure
     */
    suspend fun rebase(
        branchName: String,
        projectPathOverride: String? = null,
    ): GitOperationResult

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
     * @param projectPathOverride The repo to read (null: the global current project).
     * @return List of files with their status
     */
    suspend fun getStatus(projectPathOverride: String? = null): List<GitFileStatus>

    /**
     * Stage a file for commit.
     *
     * @param filePath Path to the file (relative to project root)
     * @param windowId Optional window ID to update window-specific state after operation
     * @return Result indicating success or failure
     */
    // The write verbs below all take a projectPathOverride: resolving the repo from
    // the global current project path in a separate step leaves a window where
    // another window's align lands in between, and the write runs in the wrong
    // worktree. The override travels with the call, so nothing can redirect it.

    suspend fun stage(
        filePath: String,
        windowId: String? = null,
        projectPathOverride: String? = null,
    ): GitOperationResult

    /**
     * Stage all modified files.
     *
     * @param windowId Optional window ID to update window-specific state after operation
     * @return Result indicating success or failure
     */
    suspend fun stageAll(
        windowId: String? = null,
        projectPathOverride: String? = null,
    ): GitOperationResult

    /**
     * Unstage a file.
     *
     * @param filePath Path to the file (relative to project root)
     * @param windowId Optional window ID to update window-specific state after operation
     * @return Result indicating success or failure
     */
    suspend fun unstage(
        filePath: String,
        windowId: String? = null,
        projectPathOverride: String? = null,
    ): GitOperationResult

    /**
     * Unstage all staged files.
     *
     * @param windowId Optional window ID to update window-specific state after operation
     * @return Result indicating success or failure
     */
    suspend fun unstageAll(
        windowId: String? = null,
        projectPathOverride: String? = null,
    ): GitOperationResult

    /**
     * Discard changes to a file in the working tree.
     *
     * @param filePath Path to the file (relative to project root)
     * @param windowId Optional window ID to update window-specific state after operation
     * @return Result indicating success or failure
     */
    suspend fun discardChanges(
        filePath: String,
        windowId: String? = null,
        projectPathOverride: String? = null,
    ): GitOperationResult

    // ===== Commit =====

    /**
     * Commit staged changes.
     *
     * @param message Commit message
     * @param amend Whether to amend the previous commit
     * @param windowId Optional window ID to update window-specific state after operation
     * @return Result indicating success or failure
     */
    suspend fun commit(
        message: String,
        amend: Boolean = false,
        windowId: String? = null,
        projectPathOverride: String? = null,
    ): GitOperationResult

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
     * @param projectPathOverride The repo to read the log from (the caller's window's
     * project); null falls back to the global current project
     * @return List of commits
     */
    suspend fun getLog(
        limit: Int = 100,
        projectPathOverride: String? = null,
    ): List<GitCommitInfo>

    /**
     * Cherry-pick a commit onto the current branch.
     *
     * @param commitHash The commit hash to cherry-pick
     * @param windowId Optional window ID to update window-specific state after operation
     * @return Result indicating success or failure
     */
    suspend fun cherryPick(
        commitHash: String,
        windowId: String? = null,
        projectPathOverride: String? = null,
    ): GitOperationResult

    /**
     * Revert a commit.
     *
     * @param commitHash The commit hash to revert
     * @param windowId Optional window ID to update window-specific state after operation
     * @return Result indicating success or failure
     */
    suspend fun revert(
        commitHash: String,
        windowId: String? = null,
        projectPathOverride: String? = null,
    ): GitOperationResult

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
    suspend fun stash(
        message: String? = null,
        includeUntracked: Boolean = false,
    ): GitOperationResult

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
    suspend fun pullInTerminal(
        windowId: String,
        projectPathOverride: String? = null,
    )

    /**
     * Run git push in the terminal (for real-time output).
     *
     * @param windowId The window ID for per-window terminal isolation (Issue #498)
     */
    suspend fun pushInTerminal(
        windowId: String,
        projectPathOverride: String? = null,
    )

    /**
     * Run git merge in the terminal (for real-time output).
     *
     * @param windowId The window ID for per-window terminal isolation (Issue #498)
     * @param branchName Branch to merge
     */
    suspend fun mergeInTerminal(
        windowId: String,
        branchName: String,
        projectPathOverride: String? = null,
    )

    /**
     * Run git rebase in the terminal (for real-time output).
     *
     * @param windowId The window ID for per-window terminal isolation (Issue #498)
     * @param branchName Branch to rebase onto
     */
    suspend fun rebaseInTerminal(
        windowId: String,
        branchName: String,
        projectPathOverride: String? = null,
    )

    /**
     * Run a custom git command in the terminal.
     *
     * @param windowId The window ID for per-window terminal isolation (Issue #498)
     * @param args Git command arguments (without 'git' prefix)
     */
    suspend fun runInTerminal(
        windowId: String,
        vararg args: String,
    )

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
        onProgress: (String) -> Unit = {},
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

    /**
     * Point the global `currentProjectPath` at [projectPath] - a cheap assignment,
     * no git invocation.
     *
     * The window-scoped write verbs no longer depend on this - they carry their own
     * projectPathOverride, because align-then-write is two steps and another window's
     * align could land in between. This still matters for everything that reads the
     * global with no override (merge/rebase/stash, the top-bar actions): once two
     * windows on different worktrees have settled, the global belonged to whichever
     * refreshed last. Aligning here, unconditionally and before the gated probe, keeps
     * the global tracking the window whose provider is being used.
     */
    fun alignCurrentProjectPath(projectPath: String)

    suspend fun refreshForWindow(
        projectPath: String,
        windowGitState: ai.rever.boss.window.WindowGitState?,
    )

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
    suspend fun getLogForWindow(
        windowGitState: ai.rever.boss.window.WindowGitState?,
        limit: Int = 100,
    ): List<GitCommitInfo>

    // ===== Window-scoped remote + ref-scoped log (boss-plugin-api 1.0.87) =====
    //
    // These take the WINDOW's state rather than reading the global
    // `currentProjectPath` the way pull()/push() do. The global is written by
    // whichever window refreshed last, so a two-window session could fetch,
    // pull or push the wrong repository; a window-scoped path cannot.

    /**
     * Commit log for an arbitrary [ref] (`git log <ref>`), for the graph's
     * branch selector.
     *
     * Deliberately does NOT write [ai.rever.boss.window.WindowGitState.commitLog]:
     * that flow is HEAD's history, which the top bar and the git-log panel
     * read, and filling it with another branch's commits would silently
     * mis-label them.
     *
     * @param ref branch, tag or commit-ish. Blank/null means HEAD.
     * @return the commits, or an empty list when [ref] resolves to nothing.
     */
    suspend fun getLogForRef(
        windowGitState: ai.rever.boss.window.WindowGitState?,
        ref: String?,
        limit: Int = 100,
    ): List<GitCommitInfo>

    /**
     * Local + remote-tracking branches of the window's project, read fresh.
     *
     * [ai.rever.boss.window.WindowGitState.localBranches] is only written by
     * [refreshForWindow], which panels call at most once per project, so it
     * goes stale the moment a branch is created or fetched.
     */
    suspend fun listBranchesForWindow(windowGitState: ai.rever.boss.window.WindowGitState?): List<GitBranchInfo>

    /** `git fetch --all [--prune]` in the window's project. */
    suspend fun fetchForWindow(
        windowGitState: ai.rever.boss.window.WindowGitState?,
        prune: Boolean = false,
    ): GitOperationResult

    /** `git pull` in the window's project. */
    suspend fun pullForWindow(windowGitState: ai.rever.boss.window.WindowGitState?): GitOperationResult

    /**
     * `git push -u origin HEAD` in the window's project. Never `--force`:
     * a force push has no in-app undo and belongs in a terminal.
     */
    suspend fun pushForWindow(windowGitState: ai.rever.boss.window.WindowGitState?): GitOperationResult

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

    // ===== Diff =====

    /**
     * Unified diff for one file.
     *
     * @param filePath Path relative to the project root.
     * @param staged true = index vs HEAD (staged changes), false = working tree vs index.
     * @return Parsed diff data for the file, or empty if there is no diff (or git failed).
     */
    // Each takes the WINDOW's project path. Reading the global `currentProjectPath`
    // meant a two-window session could render the OTHER window's diff: the global is
    // written by whichever window refreshed last, and ensureRepoState only reseeds it
    // when that window's project CHANGED - so once both windows settle, nothing
    // re-aligns it before a diff read. Same fix, same reason, as the remote verbs above.
    // A null path falls back to the global for callers that have no window.
    suspend fun getFileDiff(
        filePath: String,
        staged: Boolean,
        projectPathOverride: String? = null,
    ): List<GitDiffData>

    /**
     * Unified diff of a single commit (against its parent; a root commit diffs
     * against the empty tree).
     *
     * @param commitHash Full or short commit hash.
     * @param filePath Optional path to restrict the diff to one file.
     */
    suspend fun getCommitDiff(
        commitHash: String,
        filePath: String? = null,
        projectPathOverride: String? = null,
    ): List<GitDiffData>

    /**
     * Unified diff between two refs.
     *
     * @param fromRef Base ref (commit/branch/tag).
     * @param toRef Target ref (commit/branch/tag).
     * @param filePath Optional path to restrict the diff to one file.
     */
    suspend fun getRefDiff(
        fromRef: String,
        toRef: String,
        filePath: String? = null,
        projectPathOverride: String? = null,
    ): List<GitDiffData>

    /**
     * Name-status listing of the changed files.
     *
     * @param staged true = staged (index vs HEAD), false = working tree vs index.
     * @return One entry per changed file (untracked files are not included -
     * they have no index or HEAD entry to diff against).
     */
    suspend fun getDiffFileNames(
        staged: Boolean,
        projectPathOverride: String? = null,
    ): List<GitFileStatusData>
}
