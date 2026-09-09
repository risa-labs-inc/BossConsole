package ai.rever.boss.git

import ai.rever.boss.components.events.GitTerminalEventBus
import ai.rever.boss.components.workspaces.CommandProcessor
import ai.rever.boss.plugin.api.GitDiffData
import ai.rever.boss.plugin.api.GitFileStatusData
import ai.rever.boss.plugin.api.GitFileStatusTypeData
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.LogSanitizer
import ai.rever.boss.window.WindowGitState
import ai.rever.boss.window.WindowGitStateRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import ai.rever.boss.plugin.git.GitOperationResult.Error as GitError
import ai.rever.boss.plugin.git.GitOperationResult.Success as GitSuccess

/**
 * Desktop implementation of GitService using git CLI.
 *
 * Uses ProcessBuilder to execute git commands and parse their output.
 * All I/O operations run on Dispatchers.IO for non-blocking execution.
 */
actual object GitService {
    private val logger = BossLogger.forComponent("GitService")
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _currentBranch = MutableStateFlow<String?>(null)
    actual val currentBranch: StateFlow<String?> = _currentBranch.asStateFlow()

    private val _isGitRepository = MutableStateFlow(false)
    actual val isGitRepository: StateFlow<Boolean> = _isGitRepository.asStateFlow()

    private val _localBranches = MutableStateFlow<List<GitBranchInfo>>(emptyList())
    actual val localBranches: StateFlow<List<GitBranchInfo>> = _localBranches.asStateFlow()

    private val _remoteBranches = MutableStateFlow<List<GitBranchInfo>>(emptyList())
    actual val remoteBranches: StateFlow<List<GitBranchInfo>> = _remoteBranches.asStateFlow()

    private val _isGitAvailable = MutableStateFlow(false)
    actual val isGitAvailable: StateFlow<Boolean> = _isGitAvailable.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    actual val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    actual val lastError: StateFlow<String?> = _lastError.asStateFlow()

    // New StateFlows for extended functionality
    private val _fileStatus = MutableStateFlow<List<GitFileStatus>>(emptyList())
    actual val fileStatus: StateFlow<List<GitFileStatus>> = _fileStatus.asStateFlow()

    private val _commitLog = MutableStateFlow<List<GitCommitInfo>>(emptyList())
    actual val commitLog: StateFlow<List<GitCommitInfo>> = _commitLog.asStateFlow()

    private val _stashList = MutableStateFlow<List<GitStashInfo>>(emptyList())
    actual val stashList: StateFlow<List<GitStashInfo>> = _stashList.asStateFlow()

    private var currentProjectPath: String? = null
    private var refreshJob: Job? = null

    // How many git commands are in flight OR waiting on [gitCommandLock], and
    // the boolean view of it. The lock is process-wide, so a slow index-write
    // (a commit waiting on gpg pinentry now holds it for up to ten minutes)
    // freezes every git command in every window; before this the only evidence
    // was a log line. The UI can say "a git command is running".
    private val gitCommandDepth = AtomicInteger(0)
    private val _gitCommandsRunning = MutableStateFlow(false)
    actual val gitCommandsRunning: StateFlow<Boolean> = _gitCommandsRunning.asStateFlow()

    init {
        // Check if git is available on system startup
        scope.launch {
            _isGitAvailable.value = checkGitAvailable()
        }
    }

    actual suspend fun refresh(projectPath: String) =
        withContext(Dispatchers.IO) {
            // Cancel any pending refresh
            refreshJob?.cancel()

            currentProjectPath = projectPath
            _isLoading.value = true
            _lastError.value = null

            try {
                if (!_isGitAvailable.value) {
                    _isGitRepository.value = false
                    _currentBranch.value = null
                    _localBranches.value = emptyList()
                    _remoteBranches.value = emptyList()
                    return@withContext
                }

                // Check if directory is a git repository
                val isRepo = isGitRepo(projectPath)
                _isGitRepository.value = isRepo

                if (!isRepo) {
                    _currentBranch.value = null
                    _localBranches.value = emptyList()
                    _remoteBranches.value = emptyList()
                    return@withContext
                }

                // Get current branch (or short SHA for detached HEAD)
                _currentBranch.value = getCurrentBranchName(projectPath)

                // Get local branches
                _localBranches.value = getLocalBranchList(projectPath)

                // Get remote branches
                _remoteBranches.value = getRemoteBranchList(projectPath)
            } catch (e: Exception) {
                _lastError.value = e.message
                logger.warn(LogCategory.SYSTEM, "Error refreshing git state", error = e)
            } finally {
                _isLoading.value = false
            }
        }

    actual suspend fun checkout(
        branchName: String,
        windowId: String?,
        projectPathOverride: String?,
    ): GitOperationResult =
        withContext(Dispatchers.IO) {
            val projectPath =
                projectPathOverride ?: currentProjectPath
                    ?: return@withContext GitError("No project selected")

            _isLoading.value = true
            try {
                if (!isSafeRefName(branchName)) {
                    return@withContext GitError("Refused an unsafe ref: branch")
                }
                // A slash does not mean "remote": `feature/x` is a legal LOCAL branch
                // name, and the branch picker feeds local names through here with their
                // slashes intact - stripping unconditionally checked out `x` (or failed).
                // Only when no local branch matches is the name read as remote-tracking
                // (`origin/feature` -> `feature`, git sets up tracking itself).
                val isLocalBranch =
                    runGitCommand(projectPath, "show-ref", "--verify", "--quiet", "refs/heads/$branchName")
                        .exitCode == 0
                val localName =
                    if (!isLocalBranch && branchName.contains("/")) {
                        branchName.substringAfter("/")
                    } else {
                        branchName
                    }
                // `--` terminates the revision list: without it `checkout <name>`
                // on a name that is also a path checks OUT THE PATH, discarding
                // that file's uncommitted changes.
                val result = runGitCommand(projectPath, "checkout", localName, "--")
                if (result.exitCode == 0) {
                    // Refresh state after checkout
                    refresh(projectPath)
                    refreshWindowState(windowId)
                    GitSuccess("Switched to branch '$localName'")
                } else {
                    val errorMsg = result.error.ifEmpty { result.output }.trim()
                    _lastError.value = errorMsg
                    GitError(errorMsg, result.exitCode)
                }
            } finally {
                _isLoading.value = false
            }
        }

    actual suspend fun createBranch(
        branchName: String,
        checkout: Boolean,
        windowId: String?,
        projectPathOverride: String?,
    ): GitOperationResult =
        withContext(Dispatchers.IO) {
            val projectPath =
                projectPathOverride ?: currentProjectPath
                    ?: return@withContext GitError("No project selected")

            // Validated like every other ref-taking command. Host-UI-only today, but
            // "not currently plugin-reachable" is a property of the callers, not of
            // this function, and the checkout/cherry-pick/revert guards exist exactly
            // because that property changed once already.
            if (!isSafeRefName(branchName)) {
                return@withContext GitError("Refused an unsafe ref: branch")
            }

            _isLoading.value = true
            try {
                val args =
                    if (checkout) {
                        listOf("checkout", "-b", branchName)
                    } else {
                        listOf("branch", branchName)
                    }

                val result = runGitCommand(projectPath, *args.toTypedArray())
                if (result.exitCode == 0) {
                    // Refresh state after creation
                    refresh(projectPath)
                    refreshWindowState(windowId)
                    GitSuccess("Created branch '$branchName'")
                } else {
                    val errorMsg = result.error.ifEmpty { result.output }.trim()
                    _lastError.value = errorMsg
                    GitError(errorMsg, result.exitCode)
                }
            } finally {
                _isLoading.value = false
            }
        }

    actual suspend fun pull(projectPathOverride: String?): GitOperationResult =
        withContext(Dispatchers.IO) {
            val projectPath =
                projectPathOverride ?: currentProjectPath
                    ?: return@withContext GitError("No project selected")

            _isLoading.value = true
            try {
                // A remote operation: runRemoteGitCommand, not runGitCommand, so a
                // credential prompt fails immediately instead of blocking for the
                // whole local timeout while holding gitCommandLock.
                val result = runRemoteGitCommand(projectPath, "pull")
                if (result.exitCode == 0) {
                    // Refresh state after pull
                    refresh(projectPath)
                    val message = result.output.trim().ifEmpty { "Pull completed successfully" }
                    GitSuccess(message)
                } else {
                    val errorMsg = result.error.ifEmpty { result.output }.trim()
                    _lastError.value = errorMsg
                    GitError(errorMsg, result.exitCode)
                }
            } finally {
                _isLoading.value = false
            }
        }

    actual suspend fun push(projectPathOverride: String?): GitOperationResult =
        withContext(Dispatchers.IO) {
            val projectPath =
                projectPathOverride ?: currentProjectPath
                    ?: return@withContext GitError("No project selected")

            _isLoading.value = true
            try {
                // Use -u to set upstream if not already set. Remote operation, so it
                // gets the non-interactive guard and the remote bound (see pull()).
                val result = runRemoteGitCommand(projectPath, "push", "-u", "origin", "HEAD")
                if (result.exitCode == 0) {
                    val message =
                        result.output.trim().ifEmpty {
                            result.error.trim().ifEmpty { "Push completed successfully" }
                        }
                    GitSuccess(message)
                } else {
                    val errorMsg = result.error.ifEmpty { result.output }.trim()
                    _lastError.value = errorMsg
                    GitError(errorMsg, result.exitCode)
                }
            } finally {
                _isLoading.value = false
            }
        }

    actual suspend fun getCreatePRUrl(projectPathOverride: String?): String? =
        withContext(Dispatchers.IO) {
            val projectPath = projectPathOverride ?: currentProjectPath ?: return@withContext null
            val branch = _currentBranch.value ?: return@withContext null

            try {
                // Get the remote origin URL
                val result = runGitCommand(projectPath, "remote", "get-url", "origin")
                if (result.exitCode != 0) return@withContext null

                val remoteUrl = result.output.trim()
                val repoUrl = parseRemoteUrl(remoteUrl) ?: return@withContext null

                // Construct the PR creation URL based on the platform
                when {
                    repoUrl.contains("github.com") -> {
                        // GitHub: https://github.com/owner/repo/compare/branch?expand=1
                        "$repoUrl/compare/$branch?expand=1"
                    }

                    repoUrl.contains("gitlab.com") || repoUrl.contains("gitlab") -> {
                        // GitLab: https://gitlab.com/owner/repo/-/merge_requests/new?merge_request[source_branch]=branch
                        "$repoUrl/-/merge_requests/new?merge_request[source_branch]=$branch"
                    }

                    repoUrl.contains("bitbucket.org") -> {
                        // Bitbucket: https://bitbucket.org/owner/repo/pull-requests/new?source=branch
                        "$repoUrl/pull-requests/new?source=$branch"
                    }

                    else -> {
                        null
                    }
                }
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Error getting PR URL", error = e)
                null
            }
        }

    actual suspend fun merge(
        branchName: String,
        projectPathOverride: String?,
    ): GitOperationResult =
        withContext(Dispatchers.IO) {
            val projectPath =
                projectPathOverride ?: currentProjectPath
                    ?: return@withContext GitError("No project selected")

            // The last ref-taking verbs without the guard - which contradicted the
            // createBranch reasoning ("not currently plugin-reachable is a property
            // of the callers, not of this function").
            if (!isSafeRefName(branchName)) {
                return@withContext GitError("Refused an unsafe ref: branch")
            }
            _isLoading.value = true
            try {
                val result = runGitCommand(projectPath, "merge", branchName)
                if (result.exitCode == 0) {
                    // Refresh state after merge
                    refresh(projectPath)
                    val message = result.output.trim().ifEmpty { "Merged '$branchName' successfully" }
                    GitSuccess(message)
                } else {
                    val errorMsg = result.error.ifEmpty { result.output }.trim()
                    _lastError.value = errorMsg
                    GitError(errorMsg, result.exitCode)
                }
            } finally {
                _isLoading.value = false
            }
        }

    actual suspend fun rebase(
        branchName: String,
        projectPathOverride: String?,
    ): GitOperationResult =
        withContext(Dispatchers.IO) {
            val projectPath =
                projectPathOverride ?: currentProjectPath
                    ?: return@withContext GitError("No project selected")

            // See merge(): validated like every other ref-taking command.
            if (!isSafeRefName(branchName)) {
                return@withContext GitError("Refused an unsafe ref: branch")
            }
            _isLoading.value = true
            try {
                val result = runGitCommand(projectPath, "rebase", branchName)
                if (result.exitCode == 0) {
                    // Refresh state after rebase
                    refresh(projectPath)
                    val message = result.output.trim().ifEmpty { "Rebased onto '$branchName' successfully" }
                    GitSuccess(message)
                } else {
                    val errorMsg = result.error.ifEmpty { result.output }.trim()
                    _lastError.value = errorMsg
                    GitError(errorMsg, result.exitCode)
                }
            } finally {
                _isLoading.value = false
            }
        }

    actual fun clear() {
        refreshJob?.cancel()
        currentProjectPath = null
        _currentBranch.value = null
        _isGitRepository.value = false
        _localBranches.value = emptyList()
        _remoteBranches.value = emptyList()
        _lastError.value = null
        _isLoading.value = false
        _fileStatus.value = emptyList()
        _commitLog.value = emptyList()
        _stashList.value = emptyList()
    }

    actual fun getCurrentProjectPath(): String? = currentProjectPath

    // ===== File Status & Staging Implementation =====

    actual suspend fun getStatus(projectPathOverride: String?): List<GitFileStatus> =
        withContext(Dispatchers.IO) {
            val projectPath = projectPathOverride ?: currentProjectPath ?: return@withContext emptyList()

            try {
                // Use porcelain v1 format for stable parsing
                val result = runGitCommand(projectPath, "status", "--porcelain=v1")
                if (result.exitCode != 0) {
                    _lastError.value = result.error.ifEmpty { result.output }
                    return@withContext emptyList()
                }

                val statuses = parseStatusOutput(result.output)

                _fileStatus.value = statuses
                statuses
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Error getting git status", error = e)
                emptyList()
            }
        }

    /**
     * Index-column porcelain codes that never describe staged content: `?` (untracked)
     * and `!` (ignored). Every other modelled code can be staged — including `U`
     * (unmerged), whose path really does hold conflict stages 1/2/3 in the index.
     * One place to revisit when a new [GitFileStatusType] is modelled.
     */
    private val NEVER_STAGED =
        setOf(GitFileStatusType.UNTRACKED, GitFileStatusType.IGNORED)

    /**
     * Parse the whole output of `git status --porcelain=v1` into file statuses.
     *
     * The single choke point for both status call sites, and the one place that decides
     * which porcelain entries are *changes* at all. Ignored entries (`!! path`, emitted
     * only when `--ignored` is passed) are dropped: an ignored path is neither staged
     * nor an unstaged change, so it belongs in neither list the commit dialog renders
     * (it filters on `isStaged` / `isUnstaged`) nor in the status stream the plugin IPC
     * bridge forwards. Fixing only one of the two booleans would just move such an entry
     * from the staged list to the unstaged one.
     *
     * [parseStatusLine] itself stays faithful to porcelain and still reports IGNORED, so
     * a future caller that deliberately passes `--ignored` can parse those lines — it
     * just has to opt in here rather than inherit them silently.
     */
    internal fun parseStatusOutput(output: String): List<GitFileStatus> =
        output
            .lines()
            .filter { it.isNotBlank() }
            .mapNotNull { parseStatusLine(it) }
            .filterNot { it.indexStatus == GitFileStatusType.IGNORED }

    /**
     * Parse a single line from `git status --porcelain=v1`.
     * Format: XY PATH or XY ORIG_PATH -> PATH (for renames)
     * X = index status, Y = worktree status
     */
    internal fun parseStatusLine(line: String): GitFileStatus? {
        if (line.length < 3) return null

        val indexChar = line[0]
        val workTreeChar = line[1]
        val pathPart = line.substring(3)

        // Handle rename/copy with arrow. C-unquote afterwards (not before): with
        // core.quotePath on (the default) git wraps a non-ASCII path in quotes
        // and octal-escapes its bytes, and that token then fails to resolve
        // when the panel hands it back as a pathspec (stage/discard/diffFile).
        // Same decoder parseNameStatus uses - without it the two parsers
        // report two spellings for the same file.
        val (path, originalPath) =
            if (pathPart.contains(" -> ")) {
                val parts = pathPart.split(" -> ")
                UnifiedDiffParser.cUnquote(parts[1]) to UnifiedDiffParser.cUnquote(parts[0])
            } else {
                UnifiedDiffParser.cUnquote(pathPart) to null
            }

        val indexStatus = parseStatusChar(indexChar)
        val workTreeStatus = parseStatusChar(workTreeChar)

        // A file is staged if it has an index status, minus the codes that fill the index
        // column without describing staged content (see [NEVER_STAGED]).
        val isStaged = indexStatus != null && indexStatus !in NEVER_STAGED

        // A file is unstaged if it has a worktree status (not space). Note this is a
        // faithful reading of the worktree column, not a judgement about the entry: for
        // "??" and "!!" it is true because both columns carry the code. Untracked is a
        // real (unstaged) change so that is correct; ignored is not a change at all, and
        // is excluded from the status list by [parseStatusOutput] rather than here.
        val isUnstaged = workTreeStatus != null

        return GitFileStatus(
            path = path,
            indexStatus = indexStatus,
            workTreeStatus = workTreeStatus,
            isStaged = isStaged,
            isUnstaged = isUnstaged,
            originalPath = originalPath,
        )
    }

    internal fun parseStatusChar(c: Char): GitFileStatusType? =
        when (c) {
            'M' -> GitFileStatusType.MODIFIED
            'A' -> GitFileStatusType.ADDED
            'D' -> GitFileStatusType.DELETED
            'R' -> GitFileStatusType.RENAMED
            'C' -> GitFileStatusType.COPIED
            '?' -> GitFileStatusType.UNTRACKED
            '!' -> GitFileStatusType.IGNORED
            'U' -> GitFileStatusType.UNMERGED
            ' ' -> null
            else -> null
        }

    /**
     * Serializes every git invocation from this service.
     *
     * git takes `.git/index.lock` for a write and does not queue behind it -
     * a second concurrent write simply fails, which is how staging several
     * files at once staged the first and silently dropped the rest.
     *
     * The lock sits around the whole command rather than around the writes
     * alone, because `git status` also takes it: it writes the refreshed stat
     * cache back to the index. With only the writes guarded, the status read
     * that every operation performs afterwards still collided with the next
     * file's `git add`, and a file was lost roughly one run in three.
     *
     * These are user-driven, IO-bound commands, so serializing them costs
     * nothing that matters.
     */
    // A coroutines Mutex, not a ReentrantLock: with the index-write bound at
    // ten minutes, a commit parked on gpg pinentry used to hold a BLOCKING
    // lock on Dispatchers.IO - parking its own IO thread plus one per queued
    // caller (64 shared IO threads total, also serving file reads, the
    // content-search walk, plugin IO). A suspend lock parks no thread; the
    // waiters wait as coroutines.
    private val gitCommandLock = kotlinx.coroutines.sync.Mutex()

    // The write verbs take a projectPathOverride for the same reason the diff reads
    // do: the global currentProjectPath belongs to whichever window ALIGNED last, and
    // aligning it in a separate step (ensureRepoState) leaves a window in which
    // another window's align lands between this window's align and its command - so
    // a discard ran `git restore` in the other window's worktree. The override is
    // resolved by the caller from ITS window and travels with the call, so no
    // interleaving can redirect it.

    actual suspend fun stage(
        filePath: String,
        windowId: String?,
        projectPathOverride: String?,
    ): GitOperationResult =
        withContext(Dispatchers.IO) {
            val projectPath =
                projectPathOverride ?: currentProjectPath
                    ?: return@withContext GitError("No project selected")

            val result = runGitCommand(projectPath, "add", "--", filePath)
            if (result.exitCode == 0) {
                getStatus(projectPath) // Refresh the repo that was written
                refreshWindowState(windowId) // Refresh window-specific status
                GitSuccess("Staged '$filePath'")
            } else {
                val errorMsg = result.error.ifEmpty { result.output }.trim()
                GitError(errorMsg, result.exitCode)
            }
        }

    actual suspend fun stageAll(
        windowId: String?,
        projectPathOverride: String?,
    ): GitOperationResult =
        withContext(Dispatchers.IO) {
            val projectPath =
                projectPathOverride ?: currentProjectPath
                    ?: return@withContext GitError("No project selected")

            val result = runGitCommand(projectPath, "add", "-A")
            if (result.exitCode == 0) {
                getStatus(projectPath) // Refresh the repo that was written
                refreshWindowState(windowId) // Refresh window-specific status
                GitSuccess("Staged all changes")
            } else {
                val errorMsg = result.error.ifEmpty { result.output }.trim()
                GitError(errorMsg, result.exitCode)
            }
        }

    actual suspend fun unstage(
        filePath: String,
        windowId: String?,
        projectPathOverride: String?,
    ): GitOperationResult =
        withContext(Dispatchers.IO) {
            val projectPath =
                projectPathOverride ?: currentProjectPath
                    ?: return@withContext GitError("No project selected")

            // A staged rename is ONE index entry spanning two paths. Restoring
            // only the new path unstages the addition and leaves the deletion
            // of the old path staged - which is why unstaging `helper2.py` put
            // `helper.py` back in STAGED. VS Code's git extension passes both
            // sides for exactly this reason, so pass both here too.
            val paths = stagedPathsFor(projectPath, filePath)
            val result = runGitCommand(projectPath, "restore", "--staged", "--", *paths.toTypedArray())
            if (result.exitCode == 0) {
                getStatus(projectPath) // Refresh the repo that was written
                refreshWindowState(windowId) // Refresh window-specific status
                GitSuccess("Unstaged '$filePath'")
            } else {
                val errorMsg = result.error.ifEmpty { result.output }.trim()
                GitError(errorMsg, result.exitCode)
            }
        }

    /**
     * Every index path that belongs with [filePath].
     *
     * For a staged rename that is both sides of the arrow; for anything else
     * it is just the path itself. Read from the current status rather than
     * remembered, so it stays correct when the index changed underneath.
     */
    private suspend fun stagedPathsFor(
        projectPath: String,
        filePath: String,
    ): List<String> {
        val status =
            runCatching {
                runGitCommand(projectPath, "status", "--porcelain=v1", "--untracked-files=all")
            }.getOrNull()
        if (status == null || status.exitCode != 0) return listOf(filePath)
        val entry =
            status.output
                .lines()
                .filter { it.length > 3 }
                .mapNotNull { parseStatusLine(it) }
                .firstOrNull { it.path == filePath }
        val original = entry?.originalPath
        return if (original.isNullOrBlank() || original == filePath) {
            listOf(filePath)
        } else {
            listOf(filePath, original)
        }
    }

    actual suspend fun unstageAll(
        windowId: String?,
        projectPathOverride: String?,
    ): GitOperationResult =
        withContext(Dispatchers.IO) {
            val projectPath =
                projectPathOverride ?: currentProjectPath
                    ?: return@withContext GitError("No project selected")

            val result = runGitCommand(projectPath, "restore", "--staged", ".")
            if (result.exitCode == 0) {
                getStatus(projectPath) // Refresh the repo that was written
                refreshWindowState(windowId) // Refresh window-specific status
                GitSuccess("Unstaged all changes")
            } else {
                val errorMsg = result.error.ifEmpty { result.output }.trim()
                GitError(errorMsg, result.exitCode)
            }
        }

    actual suspend fun discardChanges(
        filePath: String,
        windowId: String?,
        projectPathOverride: String?,
    ): GitOperationResult =
        withContext(Dispatchers.IO) {
            // Destructive, so the override matters most here: a relative path that
            // exists in two open worktrees restores in whichever one the global
            // happened to point at, silently destroying uncommitted work.
            val projectPath =
                projectPathOverride ?: currentProjectPath
                    ?: return@withContext GitError("No project selected")

            val result = runGitCommand(projectPath, "restore", "--", filePath)
            if (result.exitCode == 0) {
                getStatus(projectPath) // Refresh the repo that was written
                refreshWindowState(windowId) // Refresh window-specific status
                GitSuccess("Discarded changes to '$filePath'")
            } else {
                val errorMsg = result.error.ifEmpty { result.output }.trim()
                GitError(errorMsg, result.exitCode)
            }
        }

    // ===== Commit Implementation =====

    actual suspend fun commit(
        message: String,
        amend: Boolean,
        windowId: String?,
        projectPathOverride: String?,
    ): GitOperationResult =
        withContext(Dispatchers.IO) {
            val projectPath =
                projectPathOverride ?: currentProjectPath
                    ?: return@withContext GitError("No project selected")

            _isLoading.value = true
            try {
                val args =
                    if (amend) {
                        listOf("commit", "--amend", "-m", message)
                    } else {
                        listOf("commit", "-m", message)
                    }

                val result = runGitCommand(projectPath, *args.toTypedArray())
                if (result.exitCode == 0) {
                    getStatus(projectPath) // Refresh the repo that was written
                    getLog(projectPathOverride = projectPath) // Same repo for the log
                    refreshWindowState(windowId) // Refresh window-specific status and log
                    val action = if (amend) "Amended commit" else "Created commit"
                    GitSuccess(action)
                } else {
                    val errorMsg = result.error.ifEmpty { result.output }.trim()
                    _lastError.value = errorMsg
                    GitError(errorMsg, result.exitCode)
                }
            } finally {
                _isLoading.value = false
            }
        }

    actual suspend fun getLastCommitMessage(): String? =
        withContext(Dispatchers.IO) {
            val projectPath = currentProjectPath ?: return@withContext null

            try {
                val result = runGitCommand(projectPath, "log", "-1", "--format=%B")
                if (result.exitCode == 0) {
                    result.output.trim().ifEmpty { null }
                } else {
                    null
                }
            } catch (e: Exception) {
                logger.debug(LogCategory.SYSTEM, "Could not read last commit message", mapOf("error" to e.toString()))
                null
            }
        }

    /**
     * Whether [filePath] is untracked in [workingDir]: absent from the index
     * entirely. A tracked file (even a clean or deleted one) has an index entry
     * and goes through the ordinary diff; only a genuinely untracked file needs
     * the /dev/null fallback in [getFileDiff].
     */
    private suspend fun isUntrackedFile(
        workingDir: String,
        filePath: String,
    ): Boolean {
        val r = runGitCommand(workingDir, "ls-files", "--", filePath)
        return r.exitCode == 0 && r.output.isBlank()
    }

    // ===== Commit Log Implementation =====

    actual suspend fun getLog(
        limit: Int,
        projectPathOverride: String?,
    ): List<GitCommitInfo> =
        withContext(Dispatchers.IO) {
            val projectPath =
                projectPathOverride ?: currentProjectPath
                    ?: return@withContext emptyList()

            try {
                // Format: hash|shorthash|author|email|timestamp|subject|parents|refs
                // Using %x00 as separator to handle special characters in subject
                val format = "%H%x00%h%x00%an%x00%ae%x00%at%x00%s%x00%P%x00%D"
                val result = runGitCommand(projectPath, "log", "--format=$format", "-n", limit.toString())

                if (result.exitCode != 0) {
                    return@withContext emptyList()
                }

                val commits =
                    result.output
                        .lines()
                        .filter { it.isNotBlank() }
                        .mapNotNull { parseCommitLine(it) }

                _commitLog.value = commits
                commits
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Error getting git log", error = e)
                emptyList()
            }
        }

    internal fun parseCommitLine(line: String): GitCommitInfo? {
        val parts = line.split("\u0000")
        if (parts.size < 6) return null

        return try {
            GitCommitInfo(
                hash = parts[0],
                shortHash = parts[1],
                author = parts[2],
                authorEmail = parts[3],
                date = parts[4].toLongOrNull() ?: 0,
                subject = parts[5],
                parentHashes = parts.getOrNull(6)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList(),
                refs = parts.getOrNull(7)?.split(", ")?.filter { it.isNotBlank() } ?: emptyList(),
            )
        } catch (e: Exception) {
            logger.debug(LogCategory.SYSTEM, "Skipping unparsable git log line", mapOf("error" to e.toString()))
            null
        }
    }

    actual suspend fun cherryPick(
        commitHash: String,
        windowId: String?,
        projectPathOverride: String?,
    ): GitOperationResult =
        withContext(Dispatchers.IO) {
            val projectPath =
                projectPathOverride ?: currentProjectPath
                    ?: return@withContext GitError("No project selected")

            if (!isSafeRefName(commitHash)) {
                return@withContext GitError("Refused an unsafe ref: commit")
            }
            _isLoading.value = true
            try {
                val result = runGitCommand(projectPath, "cherry-pick", commitHash)
                if (result.exitCode == 0) {
                    refresh(projectPath)
                    // The panel that issued the cherry-pick sees its own status and
                    // log refresh, like every other write - without this it reported
                    // success while its changes list stayed stale until the next poll.
                    refreshWindowState(windowId)
                    GitSuccess("Cherry-picked $commitHash")
                } else {
                    val errorMsg = result.error.ifEmpty { result.output }.trim()
                    _lastError.value = errorMsg
                    GitError(errorMsg, result.exitCode)
                }
            } finally {
                _isLoading.value = false
            }
        }

    actual suspend fun revert(
        commitHash: String,
        windowId: String?,
        projectPathOverride: String?,
    ): GitOperationResult =
        withContext(Dispatchers.IO) {
            val projectPath =
                projectPathOverride ?: currentProjectPath
                    ?: return@withContext GitError("No project selected")

            if (!isSafeRefName(commitHash)) {
                return@withContext GitError("Refused an unsafe ref: commit")
            }
            _isLoading.value = true
            try {
                val result = runGitCommand(projectPath, "revert", "--no-edit", commitHash)
                if (result.exitCode == 0) {
                    refresh(projectPath)
                    // See cherryPick: the issuing panel refreshes itself.
                    refreshWindowState(windowId)
                    GitSuccess("Reverted $commitHash")
                } else {
                    val errorMsg = result.error.ifEmpty { result.output }.trim()
                    _lastError.value = errorMsg
                    GitError(errorMsg, result.exitCode)
                }
            } finally {
                _isLoading.value = false
            }
        }

    // ===== Stash Implementation =====

    actual suspend fun stash(
        message: String?,
        includeUntracked: Boolean,
    ): GitOperationResult =
        withContext(Dispatchers.IO) {
            val projectPath =
                currentProjectPath
                    ?: return@withContext GitError("No project selected")

            _isLoading.value = true
            try {
                val args = mutableListOf("stash", "push")
                if (includeUntracked) {
                    args.add("-u")
                }
                if (message != null) {
                    args.add("-m")
                    args.add(message)
                }

                val result = runGitCommand(projectPath, *args.toTypedArray())
                if (result.exitCode == 0) {
                    getStatus()
                    refreshStashList()
                    GitSuccess(result.output.trim().ifEmpty { "Stashed changes" })
                } else {
                    val errorMsg = result.error.ifEmpty { result.output }.trim()
                    _lastError.value = errorMsg
                    GitError(errorMsg, result.exitCode)
                }
            } finally {
                _isLoading.value = false
            }
        }

    actual suspend fun stashPop(index: Int): GitOperationResult =
        withContext(Dispatchers.IO) {
            val projectPath =
                currentProjectPath
                    ?: return@withContext GitError("No project selected")

            _isLoading.value = true
            try {
                val result = runGitCommand(projectPath, "stash", "pop", "stash@{$index}")
                if (result.exitCode == 0) {
                    getStatus()
                    refreshStashList()
                    GitSuccess("Popped stash@{$index}")
                } else {
                    val errorMsg = result.error.ifEmpty { result.output }.trim()
                    _lastError.value = errorMsg
                    GitError(errorMsg, result.exitCode)
                }
            } finally {
                _isLoading.value = false
            }
        }

    actual suspend fun stashApply(index: Int): GitOperationResult =
        withContext(Dispatchers.IO) {
            val projectPath =
                currentProjectPath
                    ?: return@withContext GitError("No project selected")

            _isLoading.value = true
            try {
                val result = runGitCommand(projectPath, "stash", "apply", "stash@{$index}")
                if (result.exitCode == 0) {
                    getStatus()
                    GitSuccess("Applied stash@{$index}")
                } else {
                    val errorMsg = result.error.ifEmpty { result.output }.trim()
                    _lastError.value = errorMsg
                    GitError(errorMsg, result.exitCode)
                }
            } finally {
                _isLoading.value = false
            }
        }

    actual suspend fun stashDrop(index: Int): GitOperationResult =
        withContext(Dispatchers.IO) {
            val projectPath =
                currentProjectPath
                    ?: return@withContext GitError("No project selected")

            _isLoading.value = true
            try {
                val result = runGitCommand(projectPath, "stash", "drop", "stash@{$index}")
                if (result.exitCode == 0) {
                    refreshStashList()
                    GitSuccess("Dropped stash@{$index}")
                } else {
                    val errorMsg = result.error.ifEmpty { result.output }.trim()
                    _lastError.value = errorMsg
                    GitError(errorMsg, result.exitCode)
                }
            } finally {
                _isLoading.value = false
            }
        }

    actual suspend fun refreshStashList(): List<GitStashInfo> =
        withContext(Dispatchers.IO) {
            val projectPath = currentProjectPath ?: return@withContext emptyList()

            try {
                // Format: stash@{0}: On branch: message
                val result = runGitCommand(projectPath, "stash", "list")
                if (result.exitCode != 0) {
                    return@withContext emptyList()
                }

                val stashes =
                    result.output
                        .lines()
                        .filter { it.isNotBlank() }
                        .mapIndexedNotNull { index, line -> parseStashLine(index, line) }

                _stashList.value = stashes
                stashes
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Error getting stash list", error = e)
                emptyList()
            }
        }

    internal fun parseStashLine(
        index: Int,
        line: String,
    ): GitStashInfo? {
        // Format: stash@{0}: On branch_name: message
        // or: stash@{0}: WIP on branch_name: hash message
        val regex = Regex("""stash@\{(\d+)\}:\s*(?:(?:WIP on|On)\s+(\S+?):\s*)?(.*)""")
        val match = regex.find(line) ?: return null

        return GitStashInfo(
            index = match.groupValues[1].toIntOrNull() ?: index,
            branch = match.groupValues[2].takeIf { it.isNotBlank() },
            message = match.groupValues[3].trim(),
        )
    }

    // ===== Terminal Integration Implementation =====

    actual suspend fun pullInTerminal(
        windowId: String,
        projectPathOverride: String?,
    ) {
        val projectPath = projectPathOverride ?: currentProjectPath ?: return
        GitTerminalEventBus.openGitTerminal(
            command = "git pull",
            workingDirectory = projectPath,
            operationName = "Pull",
            sourceWindowId = windowId,
        )
    }

    actual suspend fun pushInTerminal(
        windowId: String,
        projectPathOverride: String?,
    ) {
        val projectPath = projectPathOverride ?: currentProjectPath ?: return
        GitTerminalEventBus.openGitTerminal(
            command = "git push -u origin HEAD",
            workingDirectory = projectPath,
            operationName = "Push",
            sourceWindowId = windowId,
        )
    }

    actual suspend fun mergeInTerminal(
        windowId: String,
        branchName: String,
        projectPathOverride: String?,
    ) {
        val projectPath = projectPathOverride ?: currentProjectPath ?: return
        // The branch name lands in a SHELL command string, so this is shell
        // injection, not just option injection, if an unvalidated name ever
        // reaches it. [isSafeRefName] is argv-safety only - git refnames
        // legally contain `;`, `|`, `&`, `$` and backtick - so the value is
        // shell-quoted as well. Host-UI-only today, with names git itself
        // produced - which is exactly the property createBranch's guard says
        // not to lean on.
        if (!isSafeRefName(branchName)) {
            logger.warn(LogCategory.SYSTEM, "mergeInTerminal refused an unsafe ref", mapOf("branch" to branchName))
            return
        }
        GitTerminalEventBus.openGitTerminal(
            command = "git merge ${CommandProcessor.quotePath(branchName)}",
            workingDirectory = projectPath,
            operationName = "Merge",
            sourceWindowId = windowId,
        )
    }

    actual suspend fun rebaseInTerminal(
        windowId: String,
        branchName: String,
        projectPathOverride: String?,
    ) {
        val projectPath = projectPathOverride ?: currentProjectPath ?: return
        // See mergeInTerminal: the name lands in a shell command string, so
        // isSafeRefName (argv-safety) is not enough on its own - shell-quote it.
        if (!isSafeRefName(branchName)) {
            logger.warn(LogCategory.SYSTEM, "rebaseInTerminal refused an unsafe ref", mapOf("branch" to branchName))
            return
        }
        GitTerminalEventBus.openGitTerminal(
            command = "git rebase ${CommandProcessor.quotePath(branchName)}",
            workingDirectory = projectPath,
            operationName = "Rebase",
            sourceWindowId = windowId,
        )
    }

    actual suspend fun runInTerminal(
        windowId: String,
        vararg args: String,
    ) {
        val projectPath = currentProjectPath ?: return
        // Every argument is interpolated VERBATIM into a shell command string.
        // There is no in-repo caller today; a future one must pass literal,
        // trusted arguments only - or shell-quote them, as mergeInTerminal does.
        val command = "git ${args.joinToString(" ")}"
        GitTerminalEventBus.openGitTerminal(
            command = command,
            workingDirectory = projectPath,
            operationName = "Git",
            sourceWindowId = windowId,
        )
    }

    // ===== Private helper functions =====

    // Internal (not private) so runProcessBounded's drain/timeout behaviour is
    // testable - the stderr-flood deadlock it exists to prevent froze the whole app.
    internal data class GitCommandResult(
        val output: String,
        val error: String,
        val exitCode: Int,
        // True when stdout hit MAX_GIT_OUTPUT_CHARS and the tail was dropped. The
        // diff getters refuse a truncated stream: it parses into a PARTIAL diff
        // that looks complete, which is worse than showing no diff at all.
        val truncated: Boolean = false,
    )

    private fun checkGitAvailable(): Boolean =
        try {
            val process =
                ProcessBuilder("git", "--version")
                    .redirectErrorStream(true)
                    .start()
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Git not available", error = e)
            false
        }

    private suspend fun isGitRepo(projectPath: String): Boolean =
        try {
            val result = runGitCommand(projectPath, "rev-parse", "--is-inside-work-tree")
            result.exitCode == 0 && result.output.trim() == "true"
        } catch (e: Exception) {
            logger.debug(
                LogCategory.SYSTEM,
                "git rev-parse failed - treating path as non-repo",
                mapOf("error" to e.toString()),
            )
            false
        }

    private suspend fun getCurrentBranchName(projectPath: String): String? {
        return try {
            // First try to get the branch name
            val result = runGitCommand(projectPath, "rev-parse", "--abbrev-ref", "HEAD")
            if (result.exitCode == 0) {
                val branch = result.output.trim()
                if (branch.isNotEmpty() && branch != "HEAD") {
                    return branch
                }
                // If HEAD, we're in detached state - get short SHA
                val shaResult = runGitCommand(projectPath, "rev-parse", "--short", "HEAD")
                if (shaResult.exitCode == 0) {
                    shaResult.output.trim().takeIf { it.isNotEmpty() }
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            logger.debug(LogCategory.SYSTEM, "Could not determine current branch", mapOf("error" to e.toString()))
            null
        }
    }

    private suspend fun getLocalBranchList(projectPath: String): List<GitBranchInfo> {
        return try {
            // Get branches with format that includes current marker
            val result =
                runGitCommand(
                    projectPath,
                    "branch",
                    "--format=%(refname:short)%(HEAD)",
                )
            if (result.exitCode != 0) return emptyList()

            result.output
                .lines()
                .filter { it.isNotBlank() }
                .map { line ->
                    // `%(HEAD)` is "*" for the checked-out branch and a SPACE
                    // for every other one, so the marker has to be trimmed off
                    // both ways: dropping only the "*" left every non-current
                    // name with a trailing space ("side "), which is not a ref
                    // git will resolve - `git log "side "` and `git checkout
                    // "side "` both fail on it.
                    val isCurrent = line.trimEnd().endsWith("*")
                    val name = line.trimEnd().removeSuffix("*").trim()
                    GitBranchInfo(name = name, isCurrent = isCurrent, isRemote = false)
                }.filter { it.name.isNotEmpty() }
                .sortedWith(compareBy({ !it.isCurrent }, { it.name })) // Current branch first
        } catch (e: Exception) {
            logger.debug(LogCategory.SYSTEM, "Could not list local branches", mapOf("error" to e.toString()))
            emptyList()
        }
    }

    private suspend fun getRemoteBranchList(projectPath: String): List<GitBranchInfo> {
        return try {
            val result =
                runGitCommand(
                    projectPath,
                    "branch",
                    "-r",
                    "--format=%(refname:short)",
                )
            if (result.exitCode != 0) return emptyList()

            result.output
                .lines()
                .filter { it.isNotBlank() }
                .filter { !it.contains("HEAD") } // Exclude origin/HEAD
                .map { name ->
                    GitBranchInfo(name = name.trim(), isCurrent = false, isRemote = true)
                }.filter { it.name.isNotEmpty() }
                .sortedBy { it.name }
        } catch (e: Exception) {
            logger.debug(LogCategory.SYSTEM, "Could not list remote branches", mapOf("error" to e.toString()))
            emptyList()
        }
    }

    // ===== Diff =====

    /**
     * Parses diff output, degrading an unparseable stream to "no diff" rather than
     * throwing. The parser's `require(path.isNotEmpty())` can still trip on a header
     * shape DIFF_SHAPE_FLAGS does not cover; a blank diff tab is recoverable, an
     * exception propagating into the calling plugin is not.
     *
     * A TRUNCATED stream is refused for the same honesty reason: it parses into a
     * partial diff that looks complete - files silently missing, a hunk cut mid-way -
     * which reads as "that's the whole change". No diff is a visible failure; a
     * plausible partial diff is a lie.
     *
     * "Too large to render" comes back as ONE [GitDiffData] whose [GitDiffData.rawUnified]
     * is the explanation: the unified view renders that text as-is, so the user sees why
     * the tab is empty instead of reading blank as "no changes" - the exact ambiguity
     * the refusal exists to avoid.
     */
    internal fun parseDiffSafely(
        result: GitCommandResult,
        label: String,
    ): List<GitDiffData> {
        if (result.truncated) {
            val capMb = MAX_GIT_OUTPUT_CHARS / (1024 * 1024)
            logger.warn(
                LogCategory.SYSTEM,
                "diff output exceeded the $capMb MB cap; reporting too large to render",
                mapOf("label" to label),
            )
            return listOf(
                GitDiffData(
                    path = label,
                    rawUnified =
                        "This diff is too large to render (the git output exceeded the $capMb MB cap). " +
                            "Narrow the diff to fewer files to see the change.",
                ),
            )
        }
        return runCatching { UnifiedDiffParser.parse(result.output) }
            .getOrElse {
                logger.warn(LogCategory.SYSTEM, "diff parse failed; showing no diff", error = it)
                emptyList()
            }
    }

    /**
     * Runs a diff command at [FULL_FILE_CONTEXT], and when the stream blows the output
     * cap retries once with a small context before falling back to the too-large marker.
     *
     * The full context is what makes a diff tab readable (you can read around a change);
     * its cost is that one huge file, or a big commit, can exceed the cap at full
     * context. The small-context retry still shows the changes themselves - less to
     * read around, but not blank - and it is the last stop: if even `-U3` exceeds the
     * cap, the marker from [parseDiffSafely] is the honest answer.
     */
    // Guard clauses per failure mode, each with its own log line; see the same call on
    // replacementIsReady in RetiredPlugins.kt.
    @Suppress("ReturnCount")
    private suspend fun runDiffWithTruncationFallback(
        label: String,
        command: suspend (contextFlag: String) -> GitCommandResult,
    ): List<GitDiffData> {
        val full = command("-U$FULL_FILE_CONTEXT")
        if (full.exitCode != 0) {
            logger.debug(LogCategory.SYSTEM, "diff failed", mapOf("label" to label, "error" to full.error))
            return emptyList()
        }
        // One retry at the small context when the full-context stream blew the output cap.
        val effective =
            if (full.truncated) {
                logger.info(
                    LogCategory.SYSTEM,
                    "diff exceeded the output cap at full context; retrying with a small context",
                    mapOf("label" to label),
                )
                val small = command("-U$SMALL_FILE_CONTEXT")
                if (small.exitCode != 0) {
                    logger.debug(
                        LogCategory.SYSTEM,
                        "diff retry failed",
                        mapOf("label" to label, "error" to small.error),
                    )
                    return emptyList()
                }
                small
            } else {
                full
            }
        return parseDiffSafely(effective, label)
    }

    actual suspend fun getFileDiff(
        filePath: String,
        staged: Boolean,
        projectPathOverride: String?,
    ): List<GitDiffData> =
        withContext(Dispatchers.IO) {
            val projectPath = projectPathOverride ?: currentProjectPath ?: return@withContext emptyList()
            // -U with a huge context: a diff tab is a file viewer that marks
            // changes, not a patch. With git's default 3 lines the file arrives
            // as disconnected fragments and there is no way to read around a
            // change. `git diff` clamps the context to the file, so this costs
            // nothing extra on a small one.
            //
            // An untracked file has no index or HEAD entry, so `git diff
            // [--cached]` emits nothing for it and the tab opens blank - which
            // reads as "no changes", the exact ambiguity the too-large marker
            // was added to close through the other door. Diff /dev/null against
            // the file instead, so its rows arrive all-added like a staged new
            // file's do.
            val untracked = isUntrackedFile(projectPath, filePath)
            runDiffWithTruncationFallback(filePath) { context ->
                val diffArgs = (mutableListOf("diff") + DIFF_SHAPE_FLAGS).toMutableList()
                diffArgs += context
                if (untracked) {
                    // `--no-index` diffs /dev/null against the file (exit 1 when
                    // they differ, which they always do), because `git diff --
                    // <path>` emits nothing for a path git does not track.
                    diffArgs += listOf("--no-index", "--", "/dev/null", filePath)
                    val r = runGitCommand(projectPath, *diffArgs.toTypedArray())
                    // Normalise the exit: an ordinary diff exits 0 either way.
                    GitCommandResult(r.output, r.error, if (r.exitCode == 1) 0 else r.exitCode, r.truncated)
                } else {
                    if (staged) diffArgs += "--cached"
                    diffArgs += listOf("--", filePath)
                    runGitCommand(projectPath, *diffArgs.toTypedArray())
                }
            }
        }

    actual suspend fun getCommitDiff(
        commitHash: String,
        filePath: String?,
        projectPathOverride: String?,
    ): List<GitDiffData> =
        withContext(Dispatchers.IO) {
            val projectPath = projectPathOverride ?: currentProjectPath ?: return@withContext emptyList()
            // Validated for the same reason as getLogForRef: a ref beginning with
            // `-` is read by git as an OPTION, and `git diff --output=<path>`
            // TRUNCATES that path. These endpoints are reachable from the
            // git_diff_ref / git_diff_between MCP tools, which are declared
            // readOnly - so an unvalidated ref turns a read-only tool into an
            // arbitrary file write. `--` terminates the revision list so a ref
            // can never be taken for a pathspec either.
            if (!isSafeRefName(commitHash)) {
                logger.warn(LogCategory.SYSTEM, "getCommitDiff refused an unsafe ref", mapOf("commit" to commitHash))
                return@withContext emptyList()
            }
            // -U matches getFileDiff/getRefDiff: the diff tab is a file viewer, so a
            // commit-scope tab rendered 3-line fragments while the others showed whole files.
            runDiffWithTruncationFallback(filePath ?: commitHash.take(8)) { context ->
                val diffArgs = (mutableListOf("show") + DIFF_SHAPE_FLAGS).toMutableList()
                diffArgs += listOf(context, "--format=", "--find-renames", commitHash, "--")
                if (filePath != null) diffArgs += filePath
                runGitCommand(projectPath, *diffArgs.toTypedArray())
            }
        }

    actual suspend fun getRefDiff(
        fromRef: String,
        toRef: String,
        filePath: String?,
        projectPathOverride: String?,
    ): List<GitDiffData> =
        withContext(Dispatchers.IO) {
            val projectPath = projectPathOverride ?: currentProjectPath ?: return@withContext emptyList()
            // See getCommitDiff: both refs are validated and the revision list is
            // terminated, because `git diff --output=<path>` truncates that path.
            if (!isSafeRefName(fromRef) || !isSafeRefName(toRef)) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "getRefDiff refused an unsafe ref",
                    mapOf("from" to fromRef, "to" to toRef),
                )
                return@withContext emptyList()
            }
            val label = if (filePath != null) filePath else "$fromRef...$toRef"
            runDiffWithTruncationFallback(label) { context ->
                val diffArgs = (mutableListOf("diff") + DIFF_SHAPE_FLAGS).toMutableList()
                diffArgs += listOf(context, fromRef, toRef, "--")
                if (filePath != null) diffArgs += filePath
                runGitCommand(projectPath, *diffArgs.toTypedArray())
            }
        }

    actual suspend fun getDiffFileNames(
        staged: Boolean,
        projectPathOverride: String?,
    ): List<GitFileStatusData> =
        withContext(Dispatchers.IO) {
            val projectPath = projectPathOverride ?: currentProjectPath ?: return@withContext emptyList()
            val args =
                if (staged) {
                    listOf("diff", "--name-status", "--cached")
                } else {
                    listOf("diff", "--name-status")
                }
            val result = runGitCommand(projectPath, *args.toTypedArray())
            if (result.exitCode != 0) return@withContext emptyList()
            parseNameStatus(result.output, staged)
        }

    private fun parseNameStatus(
        output: String,
        staged: Boolean,
    ): List<GitFileStatusData> {
        val list = mutableListOf<GitFileStatusData>()
        for (raw in output.lines()) {
            if (raw.isBlank()) continue
            val parts = raw.split("\t")
            val type = statusTypeFromCode(parts.firstOrNull().orEmpty()) ?: continue
            // Renames/copies carry two columns: STATUS100\told\tnew.
            // C-unquote: with core.quotePath on (the default) git wraps a non-ASCII
            // path in quotes and octal-escapes its bytes, and that token then fails to
            // resolve when handed back as a pathspec (dead tab / empty diff). The same
            // decoder the diff header uses fixes it; an unquoted path passes through.
            val path = UnifiedDiffParser.cUnquote(parts.getOrNull(if (parts.size > 2) 2 else 1) ?: continue)
            list.add(
                GitFileStatusData(
                    path = path,
                    indexStatus = if (staged) type else null,
                    workTreeStatus = if (staged) null else type,
                    isStaged = staged,
                    isUnstaged = !staged,
                ),
            )
        }
        return list
    }

    private fun statusTypeFromCode(code: String): GitFileStatusTypeData? =
        when (code.firstOrNull()) {
            'M' -> GitFileStatusTypeData.MODIFIED
            'A' -> GitFileStatusTypeData.ADDED
            'D' -> GitFileStatusTypeData.DELETED
            'R' -> GitFileStatusTypeData.RENAMED
            'C' -> GitFileStatusTypeData.COPIED
            'T' -> GitFileStatusTypeData.MODIFIED
            'U' -> GitFileStatusTypeData.UNMERGED
            else -> null
        }

    /**
     * Counts the caller in [gitCommandDepth] for as long as it holds - or is
     * queued for - [gitCommandLock], so [gitCommandsRunning] is true from the
     * first command until the last one that queued behind it finishes.
     *
     * A plain suspend function (not inline): the block must be able to wait on
     * the coroutines Mutex, and an inline non-suspend wrapper would forbid it.
     */
    private suspend fun <T> withGitCommandSignal(block: suspend () -> T): T {
        val entering = gitCommandDepth.incrementAndGet() == 1
        if (entering) _gitCommandsRunning.value = true
        try {
            return block()
        } finally {
            val remaining = gitCommandDepth.decrementAndGet()
            if (remaining == 0) _gitCommandsRunning.value = false
        }
    }

    private suspend fun runGitCommand(
        workingDir: String,
        vararg args: String,
    ): GitCommandResult =
        withGitCommandSignal {
            gitCommandLock.withLock {
                runGitCommandLocked(workingDir, *args)
            }
        }

    private fun runGitCommandLocked(
        workingDir: String,
        vararg args: String,
    ): GitCommandResult {
        val process =
            ProcessBuilder("git", *args)
                .directory(File(workingDir))
                .apply {
                    // Inherit parent process environment for SSH/git credentials
                    environment().putAll(System.getenv())
                    // Never interactive on this path: a credential prompt or a
                    // hook reading stdin would otherwise hold gitCommandLock for
                    // the whole bound. Same guard runRemoteGitCommand applies.
                    environment()["GIT_TERMINAL_PROMPT"] = "0"
                    // `--` blocks option injection, not pathspec magic: without
                    // this a plugin-passed `:/` reaches `git restore -- :/`,
                    // which discards the entire worktree. Every pathspec this
                    // file passes is already a literal path, so nothing
                    // regresses.
                    environment()["GIT_LITERAL_PATHSPECS"] = "1"
                }.start()
        // The child gets no input at all: with a live stdin pipe, a hook or
        // credential helper that READS it (rather than prompting on a tty)
        // would block until the timeout while holding gitCommandLock.
        // (Redirect.discard() does this, but is Java 9+ - this target is 8.)
        process.outputStream.close()

        // Drained concurrently and bounded, for the reasons runRemoteGitCommand
        // spells out. This path had neither, and the index lock made that worse
        // rather than better: gitCommandLock serialises every git command in the
        // app, so ONE local command blocked on a full stderr pipe - a big
        // `git add -A` emitting per-file warnings, a chatty hook, a commit waiting
        // on gpg pinentry - froze the status poll, the diff tabs and the top bar
        // in every window, with no timeout to end it.
        //
        // Index-writing commands get a much longer bound: killing `git commit`
        // mid-run leaves `.git/index.lock` behind and every later git command in
        // the repo fails until the user deletes it by hand. Pre-commit hooks that
        // run a formatter or a test subset, and gpg commits waiting on pinentry,
        // routinely outlive the tight bound that is right for reads.
        val timeout =
            if (args.firstOrNull() in INDEX_WRITE_SUBCOMMANDS) {
                INDEX_WRITE_TIMEOUT_SECONDS
            } else {
                LOCAL_GIT_TIMEOUT_SECONDS
            }
        return runProcessBounded(process, timeout, args, workingDir)
    }

    /**
     * Subcommands that take `.git/index.lock` for the duration of the command. A
     * forcible kill mid-run strands the lock file, so these get the generous bound.
     */
    private val INDEX_WRITE_SUBCOMMANDS =
        setOf("add", "commit", "restore", "checkout", "merge", "rebase", "cherry-pick", "revert", "stash", "mv")

    /**
     * Refresh window-specific git state after a write operation.
     * This ensures the UI updates immediately in the correct window.
     */
    private suspend fun refreshWindowState(windowId: String?) {
        windowId?.let { id ->
            WindowGitStateRegistry.get(id)?.let { windowState ->
                getStatusForWindow(windowState)
                getLogForWindow(windowState)
            }
        }
    }

    /**
     * Parse a git remote URL (SSH or HTTPS) into an HTTPS URL for browser access.
     *
     * Supports formats:
     * - git@github.com:owner/repo.git -> https://github.com/owner/repo
     * - https://github.com/owner/repo.git -> https://github.com/owner/repo
     * - ssh://git@github.com/owner/repo.git -> https://github.com/owner/repo
     */
    internal fun parseRemoteUrl(remoteUrl: String): String? =
        try {
            when {
                // SSH format: git@github.com:owner/repo.git
                remoteUrl.startsWith("git@") -> {
                    val withoutPrefix = remoteUrl.removePrefix("git@")
                    val host = withoutPrefix.substringBefore(":")
                    val path = withoutPrefix.substringAfter(":").removeSuffix(".git")
                    "https://$host/$path"
                }

                // SSH URL format: ssh://git@github.com/owner/repo.git
                remoteUrl.startsWith("ssh://") -> {
                    val withoutProtocol = remoteUrl.removePrefix("ssh://")
                    val withoutUser = withoutProtocol.substringAfter("@")
                    val url = withoutUser.removeSuffix(".git")
                    "https://$url"
                }

                // HTTPS format: https://github.com/owner/repo.git
                remoteUrl.startsWith("https://") || remoteUrl.startsWith("http://") -> {
                    remoteUrl.removeSuffix(".git")
                }

                else -> {
                    null
                }
            }
        } catch (e: Exception) {
            logger.debug(LogCategory.SYSTEM, "Could not resolve remote URL to web URL", mapOf("error" to e.toString()))
            null
        }

    // ===== Window-Specific Operations =====

    /**
     * Refresh git state for a specific window.
     * Updates the provided WindowGitState instead of global state.
     * This allows multiple windows to have independent git states.
     */
    actual fun alignCurrentProjectPath(projectPath: String) {
        currentProjectPath = projectPath
    }

    /**
     * Test-only inverse of [alignCurrentProjectPath], which can only point, never
     * unpoint: a test that steers the global at a temp repo must be able to put
     * "no project" back, or a later test in the same JVM reads a deleted dir.
     */
    internal fun clearCurrentProjectPathForTests() {
        currentProjectPath = null
    }

    actual suspend fun refreshForWindow(
        projectPath: String,
        windowGitState: WindowGitState?,
    ) = withContext(Dispatchers.IO) {
        if (windowGitState == null) return@withContext

        windowGitState.setProjectPath(projectPath)
        // The file- and ref-scoped diff commands (getFileDiff, getCommitDiff,
        // getRefDiff) read the GLOBAL currentProjectPath, which only the top
        // bar's refresh() ever set. For a project picked through a panel it
        // stayed null, so every diff tab opened on "No changes to show" while
        // the same panel listed the changes correctly. Seed it from the window
        // that is asking; null is never the better answer.
        if (currentProjectPath != projectPath) {
            currentProjectPath = projectPath
        }
        windowGitState.setLoading(true)

        try {
            if (!_isGitAvailable.value) {
                windowGitState.updateGitState(
                    isRepo = false,
                    branch = null,
                    local = emptyList(),
                    remote = emptyList(),
                )
                return@withContext
            }

            // Check if directory is a git repository
            val isRepo = isGitRepo(projectPath)

            if (!isRepo) {
                windowGitState.updateGitState(
                    isRepo = false,
                    branch = null,
                    local = emptyList(),
                    remote = emptyList(),
                )
                return@withContext
            }

            // Get current branch (or short SHA for detached HEAD)
            val branch = getCurrentBranchName(projectPath)

            // Get local branches
            val local = getLocalBranchList(projectPath)

            // Get remote branches
            val remote = getRemoteBranchList(projectPath)

            // Update window-specific state
            windowGitState.updateGitState(
                isRepo = isRepo,
                branch = branch,
                local = local,
                remote = remote,
            )
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Error refreshing git for window", error = e)
        } finally {
            windowGitState.setLoading(false)
        }
    }

    /**
     * Refresh stash list for a specific window.
     */
    actual suspend fun refreshStashListForWindow(windowGitState: WindowGitState?): List<GitStashInfo> =
        withContext(Dispatchers.IO) {
            if (windowGitState == null) return@withContext emptyList()

            val projectPath = windowGitState.projectPath.value ?: return@withContext emptyList()

            try {
                val result = runGitCommand(projectPath, "stash", "list")
                if (result.exitCode != 0) {
                    return@withContext emptyList()
                }

                val stashes =
                    result.output
                        .lines()
                        .filter { it.isNotBlank() }
                        .mapIndexedNotNull { index, line -> parseStashLine(index, line) }

                windowGitState.updateStashList(stashes)
                stashes
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Error getting stash list for window", error = e)
                emptyList()
            }
        }

    /**
     * Get file status for a specific window.
     */
    actual suspend fun getStatusForWindow(windowGitState: WindowGitState?): List<GitFileStatus> =
        withContext(Dispatchers.IO) {
            if (windowGitState == null) return@withContext emptyList()

            val projectPath = windowGitState.projectPath.value ?: return@withContext emptyList()

            try {
                // --untracked-files=all, not git's default: the default
                // collapses an untracked directory into one `?? dir/` entry, so
                // a source-control view has a row it cannot expand, stage
                // individually, or show a diff for. Listing the files is what
                // every editor's SCM view does. .gitignore still applies, so an
                // ignored build/ or node_modules/ contributes nothing.
                val result =
                    runGitCommand(projectPath, "status", "--porcelain=v1", "--untracked-files=all")
                if (result.exitCode != 0) {
                    return@withContext emptyList()
                }

                val statuses = parseStatusOutput(result.output)

                windowGitState.updateFileStatus(statuses)
                statuses
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Error getting status for window", error = e)
                emptyList()
            }
        }

    /**
     * Get commit log for a specific window.
     */
    actual suspend fun getLogForWindow(
        windowGitState: WindowGitState?,
        limit: Int,
    ): List<GitCommitInfo> =
        withContext(Dispatchers.IO) {
            if (windowGitState == null) return@withContext emptyList()

            val projectPath = windowGitState.projectPath.value ?: return@withContext emptyList()

            try {
                val format = "%H%x00%h%x00%an%x00%ae%x00%at%x00%s%x00%P%x00%D"
                val result = runGitCommand(projectPath, "log", "--format=$format", "-n", limit.toString())

                if (result.exitCode != 0) {
                    return@withContext emptyList()
                }

                val commits =
                    result.output
                        .lines()
                        .filter { it.isNotBlank() }
                        .mapNotNull { parseCommitLine(it) }

                windowGitState.updateCommitLog(commits)
                commits
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Error getting log for window", error = e)
                emptyList()
            }
        }

    // ===== Window-scoped remote + ref-scoped log (boss-plugin-api 1.0.87) =====

    /**
     * A ref safe to hand to `git log` as a positional argument.
     *
     * The graph's branch picker feeds this from a list git itself produced, but
     * the value reaches here through the plugin API, so it is validated at the
     * boundary rather than trusted: anything starting with `-` would be read as
     * an OPTION (`--upload-pack=…` and friends), and whitespace or control
     * characters are not part of any refname git will accept anyway.
     *
     * Pure and internal so [ai.rever.boss.git] tests can pin it - a check like
     * this is exactly the kind that rots silently.
     *
     * This is ARGV safety, not shell safety: git refnames legally contain `;`,
     * `|`, `&`, `$` and backtick, all of which pass. The two callers that build
     * a shell command string ([mergeInTerminal], [rebaseInTerminal]) therefore
     * also pass the value through [CommandProcessor.quotePath].
     */
    internal fun isSafeRefName(ref: String): Boolean {
        if (ref.isBlank()) return false
        if (ref.startsWith("-")) return false
        if (ref.length > MAX_REF_LENGTH) return false
        return ref.none { it.isWhitespace() || it.code < 0x20 || it == '\u007F' }
    }

    private const val MAX_REF_LENGTH = 255

    /**
     * [mergeInTerminal] and [rebaseInTerminal] build their command string through
     * [CommandProcessor.quotePath] (the repo's one definition of shell quoting),
     * because `git check-ref-format --branch` accepts names carrying `;`, `|`,
     * `&`, `$` and backtick, and those are live shell metacharacters in a
     * command string. The platform decision is the processor's: POSIX
     * close-escape-reopen on Unix, doubled quotes under Windows PowerShell -
     * a local POSIX-only copy of the quoting used to hand the PowerShell
     * dance to the Windows terminal, and a branch name with an apostrophe
     * produced a broken command there. The result is what a user sees typed
     * in the terminal pane, so the quoting is deliberate and visible, not hidden.
     */

    /**
     * A git command that talks to a REMOTE: bounded, and never interactive.
     *
     * Two properties the ordinary [runGitCommand] does not have, and that
     * fetch/pull/push are the first callers reachable from a panel to need.
     *
     * Non-interactive, because the child process gets no terminal: a
     * credential prompt would have nothing to read from and would block on
     * stdin forever. `GIT_TERMINAL_PROMPT=0` (and the empty askpass hooks)
     * turn that into an immediate authentication failure the panel can show.
     *
     * Bounded, because the wait happens while holding [gitCommandLock] - the
     * lock every git command in the app queues behind. A hung fetch would not
     * only fail its own button, it would freeze the status poll, the diff
     * tabs and the top bar for the rest of the session. A killed process
     * releases the lock; a hung one never does.
     */
    private suspend fun runRemoteGitCommand(
        workingDir: String,
        vararg args: String,
    ): GitCommandResult =
        withGitCommandSignal {
            gitCommandLock.withLock {
                val process =
                    ProcessBuilder("git", *args)
                        .directory(File(workingDir))
                        .apply {
                            environment().putAll(System.getenv())
                            // Fail instead of prompting: there is no tty to prompt on.
                            environment()["GIT_TERMINAL_PROMPT"] = "0"
                            environment()["GIT_ASKPASS"] = ""
                            environment()["SSH_ASKPASS"] = ""
                        }.start()

                // The child gets no input at all: a helper that READS stdin would
                // block on the live pipe (see runGitCommandLocked, where the
                // discard() equivalent was needed for the same reason).
                process.outputStream.close()

                // Both pipes are drained on their OWN threads, and the timeout wraps the
                // whole interaction. Two bugs live in the obvious ordering:
                //
                // 1. `readText()` blocks until EOF, i.e. until the child exits. Reading
                //    before `waitFor` made the timeout unreachable - it could only fire
                //    after the process had already finished. A fetch hung on an
                //    unreachable host blocked here forever, holding gitCommandLock, which
                //    every git command in the app queues behind.
                // 2. Draining stdout to EOF *before* touching stderr deadlocks whenever
                //    the child fills the stderr pipe buffer (~64KB): it blocks writing
                //    stderr, so it never closes stdout, so we never stop reading. git
                //    fetch writes progress to stderr, so this needed no network fault.
                runProcessBounded(process, REMOTE_GIT_TIMEOUT_SECONDS, args, workingDir)
            }
        }

    /** How long a remote git command may hold the git lock before it is killed. */
    private const val REMOTE_GIT_TIMEOUT_SECONDS = 120L

    /** How long to wait for a killed child's pipes to close before giving up on its output. */
    private const val STREAM_DRAIN_TIMEOUT_SECONDS = 5L

    /**
     * Local git commands are bounded too - generously, since they touch no network,
     * but a hook or a credential prompt can still wedge one, and it would be holding
     * [gitCommandLock] while it did.
     */
    private const val LOCAL_GIT_TIMEOUT_SECONDS = 60L

    /**
     * The bound for [INDEX_WRITE_SUBCOMMANDS]. Long, because the failure mode of the
     * kill is worse than the failure mode of the wait: a killed index write strands
     * `.git/index.lock`, wedging the repository, while a slow hook merely holds the
     * lock - annoying, recoverable, and ended by this bound eventually anyway.
     */
    private const val INDEX_WRITE_TIMEOUT_SECONDS = 600L

    /**
     * Runs [process] to completion with both pipes drained concurrently and a wall
     * clock bound, returning whatever output was produced either way.
     *
     * The ordering is the whole point, and both callers need it:
     * - drain BEFORE waiting, on separate threads, or a child that fills one pipe
     *   blocks writing it, never closes the other, and a sequential reader never
     *   returns - a deadlock that needs no timeout to be unrecoverable;
     * - wait with a bound, so a genuinely hung child ends;
     * - kill, then wait for the kill, so the pipes actually close before joining
     *   the readers - otherwise the join just moves the hang one line down.
     */
    internal fun runProcessBounded(
        process: Process,
        timeoutSeconds: Long,
        args: Array<out String>,
        workingDir: String? = null,
        capChars: Int = MAX_GIT_OUTPUT_CHARS,
    ): GitCommandResult {
        val outBuf = StringBuilder()
        val errBuf = StringBuilder()
        val outDropped = AtomicBoolean(false)
        // stderr gets its own flag: [truncated] is what parseDiffSafely refuses on,
        // and a stderr flood must not mark a perfectly complete stdout truncated
        // (nothing reads a stderr "truncated" bit, so the drop is silent).
        val errDropped = AtomicBoolean(false)
        val outReader = drainAsync(process.inputStream, outBuf, outDropped, capChars)
        val errReader = drainAsync(process.errorStream, errBuf, errDropped, capChars)
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(STREAM_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        outReader.join(STREAM_DRAIN_TIMEOUT_SECONDS * 1000)
        errReader.join(STREAM_DRAIN_TIMEOUT_SECONDS * 1000)
        val output = synchronized(outBuf) { outBuf.toString() }
        val error = synchronized(errBuf) { errBuf.toString() }
        // Set where bytes were actually dropped (drainAsync), not inferred from
        // the length: output of EXACTLY the cap is complete, and the consequence
        // of a false positive is refusing the whole diff.
        val truncated = outDropped.get()
        if (truncated) {
            logger.warn(
                LogCategory.SYSTEM,
                "git output truncated at the $capChars-char cap",
                mapOf("args" to args.joinToString(" ")),
            )
        }
        if (finished) return GitCommandResult(output, error, process.exitValue(), truncated)
        logger.warn(
            LogCategory.SYSTEM,
            "git command timed out",
            mapOf("args" to args.joinToString(" "), "timeoutSeconds" to timeoutSeconds.toString()),
        )
        // A kill mid-index-write strands .git/index.lock, and git's later "File
        // exists" error never says a killed BOSS command left it. Probe and say so.
        // Gated on the subcommand: a timed-out `git diff` / `log` / `status`
        // never takes the lock, and telling the user to delete a lock some OTHER
        // process legitimately holds is advice to break a live repo.
        val lockHint =
            if (
                args.firstOrNull() in INDEX_WRITE_SUBCOMMANDS &&
                workingDir != null &&
                staleIndexLockExists(workingDir)
            ) {
                " The killed command may have left .git/index.lock behind; " +
                    "delete that file if git commands keep failing."
            } else {
                ""
            }
        return GitCommandResult(
            output,
            "Timed out after ${timeoutSeconds}s. Check the remote, your network, or your credentials.$lockHint",
            TIMEOUT_EXIT_CODE,
            truncated,
        )
    }

    /**
     * Best-effort probe for `.git/index.lock` under [workingDir]. Handles a worktree
     * checkout too, where `.git` is a FILE containing `gitdir: <path>`.
     */
    private fun staleIndexLockExists(workingDir: String): Boolean =
        runCatching {
            val dotGit = File(workingDir, ".git")
            val gitDir =
                when {
                    dotGit.isDirectory -> {
                        dotGit
                    }

                    dotGit.isFile -> {
                        dotGit
                            .readText()
                            .lineSequence()
                            .firstOrNull { it.startsWith("gitdir:") }
                            ?.removePrefix("gitdir:")
                            ?.trim()
                            ?.let { p -> if (File(p).isAbsolute) File(p) else File(workingDir, p) }
                    }

                    else -> {
                        null
                    }
                }
            gitDir != null && File(gitDir, "index.lock").exists()
        }.getOrDefault(false)

    private const val TIMEOUT_EXIT_CODE = 124

    /**
     * Drains [stream] into [sink] on a daemon thread.
     *
     * A thread rather than a coroutine: this is called from inside
     * `gitCommandLock.withLock`, which is a blocking lock held across the whole
     * command, so suspending here would park a lock-holding thread. Daemon, so a
     * reader still blocked on a wedged pipe cannot keep the JVM alive at shutdown.
     */
    private fun drainAsync(
        stream: java.io.InputStream,
        sink: StringBuilder,
        dropped: java.util.concurrent.atomic.AtomicBoolean,
        capChars: Int = MAX_GIT_OUTPUT_CHARS,
    ): Thread =
        Thread {
            runCatching {
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    val buf = CharArray(DRAIN_BUFFER_CHARS)
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        // Bounded memory. A whole-file-context (-U100000) diff of a large
                        // multi-file commit can be hundreds of MB on stdout; buffering it
                        // all - while holding gitCommandLock - risks an OOM that takes the
                        // app down. Past the cap we keep reading (so the child never blocks
                        // on a full pipe) but stop appending; the result is flagged
                        // truncated (where the drop happens, not inferred from length)
                        // and the diff getters refuse it rather than rendering a partial
                        // diff that looks complete.
                        //
                        // The cap is PER SINK, in CHARS: a UTF-8 byte stream decodes to at
                        // most as many chars, so the real ceiling is 32M chars per stream
                        // (~64 MB of UTF-8) and up to ~128 MB per command with both
                        // streams at the cap - all held in memory while the
                        // process-wide lock is held. The number is a bound, not a budget:
                        // it must stay far below what the heap can absorb, and it is not
                        // a byte count despite the "MB" framing below.
                        synchronized(sink) {
                            if (sink.length < capChars) {
                                val room = capChars - sink.length
                                if (n > room) dropped.set(true)
                                sink.appendRange(buf, 0, minOf(n, room))
                            } else {
                                dropped.set(true)
                            }
                        }
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

    private const val DRAIN_BUFFER_CHARS = 8192

    /**
     * Ceiling PER SINK, counted in CHARS, applied to stdout and stderr independently:
     * a UTF-8 byte stream decodes to at most as many chars, so the real ceiling is
     * ~64 MB per stream and ~128 MB per command with both at the cap, held in memory
     * while the process-wide lock is held. It is a bound against OOM, not a byte
     * budget; see [drainAsync].
     */
    private const val MAX_GIT_OUTPUT_CHARS = 32 * 1024 * 1024

    actual suspend fun getLogForRef(
        windowGitState: WindowGitState?,
        ref: String?,
        limit: Int,
    ): List<GitCommitInfo> =
        withContext(Dispatchers.IO) {
            if (windowGitState == null) return@withContext emptyList()
            val projectPath = windowGitState.projectPath.value ?: return@withContext emptyList()
            val target = ref?.trim().orEmpty()
            if (target.isNotEmpty() && !isSafeRefName(target)) {
                logger.warn(LogCategory.SYSTEM, "Refusing an unsafe git ref for log", mapOf("ref" to target))
                return@withContext emptyList()
            }

            try {
                val format = "%H%x00%h%x00%an%x00%ae%x00%at%x00%s%x00%P%x00%D"
                // `--` terminates the revision list, so a ref that survived the
                // check above still cannot be read as a pathspec or an option.
                val args =
                    buildList {
                        add("log")
                        add("--format=$format")
                        add("-n")
                        add(limit.toString())
                        if (target.isNotEmpty()) add(target)
                        add("--")
                    }
                val result = runGitCommand(projectPath, *args.toTypedArray())
                if (result.exitCode != 0) return@withContext emptyList()

                // Deliberately NOT windowGitState.updateCommitLog(...): see the
                // expect declaration - that flow is HEAD's history.
                result.output
                    .lines()
                    .filter { it.isNotBlank() }
                    .mapNotNull { parseCommitLine(it) }
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Error getting log for ref", error = e)
                emptyList()
            }
        }

    actual suspend fun listBranchesForWindow(windowGitState: WindowGitState?): List<GitBranchInfo> =
        withContext(Dispatchers.IO) {
            val projectPath = windowGitState?.projectPath?.value ?: return@withContext emptyList()
            if (!isGitRepo(projectPath)) return@withContext emptyList()
            getLocalBranchList(projectPath) + getRemoteBranchList(projectPath)
        }

    actual suspend fun fetchForWindow(
        windowGitState: WindowGitState?,
        prune: Boolean,
    ): GitOperationResult =
        withContext(Dispatchers.IO) {
            val projectPath =
                windowGitState?.projectPath?.value
                    ?: return@withContext GitError("No project selected")
            val args = if (prune) arrayOf("fetch", "--all", "--prune") else arrayOf("fetch", "--all")
            val result = runRemoteGitCommand(projectPath, *args)
            if (result.exitCode == 0) {
                // A fetch moves remote-tracking refs, which is exactly what the
                // branch list and the graph decorations show.
                refreshForWindow(projectPath, windowGitState)
                // git fetch reports on stderr; an empty pair means "nothing new",
                // which is a result worth saying out loud rather than silence.
                val message =
                    result.error
                        .trim()
                        .ifEmpty { result.output.trim() }
                        .ifEmpty { "Already up to date" }
                GitSuccess(message)
            } else {
                val errorMsg = result.error.ifEmpty { result.output }.trim()
                _lastError.value = errorMsg
                GitError(errorMsg, result.exitCode)
            }
        }

    actual suspend fun pullForWindow(windowGitState: WindowGitState?): GitOperationResult =
        withContext(Dispatchers.IO) {
            val projectPath =
                windowGitState?.projectPath?.value
                    ?: return@withContext GitError("No project selected")
            val result = runRemoteGitCommand(projectPath, "pull")
            if (result.exitCode == 0) {
                refreshForWindow(projectPath, windowGitState)
                getStatusForWindow(windowGitState)
                getLogForWindow(windowGitState)
                GitSuccess(result.output.trim().ifEmpty { "Pull completed successfully" })
            } else {
                val errorMsg = result.error.ifEmpty { result.output }.trim()
                _lastError.value = errorMsg
                GitError(errorMsg, result.exitCode)
            }
        }

    actual suspend fun pushForWindow(windowGitState: WindowGitState?): GitOperationResult =
        withContext(Dispatchers.IO) {
            val projectPath =
                windowGitState?.projectPath?.value
                    ?: return@withContext GitError("No project selected")
            // -u sets upstream on a branch that has none. No --force, ever.
            val result = runRemoteGitCommand(projectPath, "push", "-u", "origin", "HEAD")
            if (result.exitCode == 0) {
                refreshForWindow(projectPath, windowGitState)
                val message =
                    result.output.trim().ifEmpty {
                        result.error.trim().ifEmpty { "Push completed successfully" }
                    }
                GitSuccess(message)
            } else {
                val errorMsg = result.error.ifEmpty { result.output }.trim()
                _lastError.value = errorMsg
                GitError(errorMsg, result.exitCode)
            }
        }

    /**
     * Clone a Git repository to the specified directory.
     * Executes git clone with progress output and streams updates via callback.
     * Includes a 10-minute timeout to prevent indefinite hangs.
     *
     * @param repositoryUrl The URL of the repository to clone
     * @param targetDirectory The directory where the repository should be cloned
     * @param onProgress Callback for progress updates
     * @return GitOperationResult indicating success or failure
     */
    actual suspend fun cloneRepository(
        repositoryUrl: String,
        targetDirectory: String,
        onProgress: (String) -> Unit,
    ): GitOperationResult =
        withContext(Dispatchers.IO) {
            logger.info(
                LogCategory.GENERAL,
                "Starting git clone",
                mapOf(
                    "url" to LogSanitizer.maskUriParams(repositoryUrl),
                    "target" to targetDirectory,
                ),
            )

            try {
                // Check if git is available
                if (!checkGitAvailable()) {
                    val error = "Git is not installed. Please install git to clone repositories."
                    logger.error(LogCategory.SYSTEM, error)
                    return@withContext GitError(error)
                }

                // Validate target directory
                val targetDir = File(targetDirectory)
                val parentDir = targetDir.parentFile

                // Check if parent directory exists and is writable
                if (parentDir == null || !parentDir.exists()) {
                    val error = "Parent directory does not exist: ${parentDir?.absolutePath ?: "unknown"}"
                    logger.error(LogCategory.GENERAL, error)
                    return@withContext GitError(error)
                }

                if (!parentDir.isDirectory) {
                    val error = "Parent path is not a directory: ${parentDir.absolutePath}"
                    logger.error(LogCategory.GENERAL, error)
                    return@withContext GitError(error)
                }

                if (!parentDir.canWrite()) {
                    val error = "Parent directory is not writable: ${parentDir.absolutePath}"
                    logger.error(LogCategory.GENERAL, error)
                    return@withContext GitError(error)
                }

                // Check if target directory already exists (git clone will fail if it does)
                if (targetDir.exists()) {
                    val error = "Directory already exists: $targetDirectory"
                    logger.error(LogCategory.GENERAL, error)
                    return@withContext GitError(error)
                }

                // Execute git clone with progress, wrapped in timeout (10 minutes for large repos)
                withTimeout(600_000L) {
                    // 10 minutes timeout
                    onProgress("Initializing clone...")

                    val process =
                        ProcessBuilder(
                            "git",
                            "clone",
                            "--progress",
                            repositoryUrl,
                            targetDirectory,
                        ).apply {
                            // Inherit parent process environment for SSH/git credentials
                            environment().putAll(System.getenv())
                        }.redirectErrorStream(true) // Merge stderr into stdout for progress
                            .start()

                    try {
                        // Read progress output
                        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                line?.let { progressLine ->
                                    // Git progress comes on stderr, but we redirected it to stdout
                                    // Filter and send meaningful progress updates
                                    when {
                                        progressLine.contains("Cloning into") -> {
                                            onProgress("Cloning repository...")
                                        }

                                        progressLine.contains("remote: Counting objects") -> {
                                            onProgress("Receiving objects...")
                                        }

                                        progressLine.contains("Receiving objects") -> {
                                            // Extract percentage if available
                                            val percentMatch = Regex("(\\d+)%").find(progressLine)
                                            if (percentMatch != null) {
                                                onProgress("Receiving objects: ${percentMatch.value}")
                                            } else {
                                                onProgress("Receiving objects...")
                                            }
                                        }

                                        progressLine.contains("Resolving deltas") -> {
                                            val percentMatch = Regex("(\\d+)%").find(progressLine)
                                            if (percentMatch != null) {
                                                onProgress("Resolving deltas: ${percentMatch.value}")
                                            } else {
                                                onProgress("Resolving deltas...")
                                            }
                                        }

                                        progressLine.contains("Checking out files") -> {
                                            onProgress("Checking out files...")
                                        }
                                    }
                                    logger.debug(LogCategory.GENERAL, "Clone progress: $progressLine")
                                }
                            }
                        }

                        val exitCode = process.waitFor()

                        if (exitCode == 0) {
                            onProgress("Clone completed successfully")
                            logger.info(
                                LogCategory.GENERAL,
                                "Repository cloned successfully",
                                mapOf("target" to targetDirectory),
                            )
                            GitSuccess()
                        } else {
                            val errorMessage =
                                when {
                                    repositoryUrl.contains("@") && exitCode == 128 -> {
                                        "Authentication failed. Please configure your SSH keys or git credentials."
                                    }

                                    exitCode == 128 -> {
                                        "Repository not found or access denied. Please check the URL and your permissions."
                                    }

                                    exitCode == 1 -> {
                                        "Network error. Please check your internet connection."
                                    }

                                    else -> {
                                        "Clone failed with exit code $exitCode. Please check the repository URL and try again."
                                    }
                                }
                            logger.error(
                                LogCategory.GENERAL,
                                "Clone failed",
                                mapOf("exitCode" to exitCode, "message" to errorMessage),
                            )
                            GitError(errorMessage)
                        }
                    } finally {
                        // Ensure process is destroyed if still running
                        if (process.isAlive) {
                            process.destroyForcibly()
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                val errorMessage =
                    "Clone operation timed out after 10 minutes. " +
                        "The repository may be too large or the connection too slow. Try cloning from terminal instead."
                logger.error(LogCategory.GENERAL, errorMessage, error = e)
                // Clean up partial clone
                try {
                    File(targetDirectory).deleteRecursively()
                } catch (cleanupError: Exception) {
                    logger.warn(LogCategory.GENERAL, "Failed to clean up after timeout", error = cleanupError)
                }
                GitError(errorMessage)
            } catch (e: IOException) {
                val errorMessage =
                    when {
                        e.message?.contains("Connection refused") == true -> {
                            "Network connection refused. Check your internet connection and firewall settings."
                        }

                        e.message?.contains("No such host") == true || e.message?.contains("unknown host") == true -> {
                            "Repository host not found. Check the repository URL and your DNS settings."
                        }

                        e.message?.contains("Permission denied") == true -> {
                            "Permission denied. Check file system permissions for the target directory."
                        }

                        e.message?.contains("No space left") == true -> {
                            "Insufficient disk space. Free up space and try again."
                        }

                        else -> {
                            "I/O error during clone: ${e.message}. Check your network connection and disk space."
                        }
                    }
                logger.error(LogCategory.GENERAL, errorMessage, error = e)
                GitError(errorMessage)
            } catch (e: SecurityException) {
                val errorMessage = "Security permission denied. Check file system permissions for '${File(
                    targetDirectory,
                ).parentFile?.absolutePath}'."
                logger.error(LogCategory.GENERAL, errorMessage, error = e)
                GitError(errorMessage)
            } catch (e: InterruptedException) {
                val errorMessage = "Clone operation was interrupted. Please try again."
                logger.error(LogCategory.GENERAL, errorMessage, error = e)
                // Clean up partial clone
                try {
                    File(targetDirectory).deleteRecursively()
                } catch (cleanupError: Exception) {
                    logger.warn(LogCategory.GENERAL, "Failed to clean up after interruption", error = cleanupError)
                }
                GitError(errorMessage)
            } catch (e: Exception) {
                val errorMessage = "Unexpected error during clone: ${e.message ?: e.javaClass.simpleName}. Please check logs for details."
                logger.error(LogCategory.GENERAL, errorMessage, error = e)
                GitError(errorMessage)
            }
        }

    /**
     * Watches `<projectPath>/.git/HEAD` and `<projectPath>/.git/refs/heads`
     * via `WatchService` and refreshes [windowGitState] when an external
     * checkout, switch, or rebase changes the current branch / SHA.
     *
     * Suspends until the surrounding coroutine is cancelled. Catches and
     * coalesces fast bursts (a checkout typically writes HEAD, ORIG_HEAD,
     * index, …) so we only do one refresh per ~150 ms quiet period.
     */
    actual suspend fun watchGitHeadForWindow(
        projectPath: String,
        windowGitState: WindowGitState?,
    ) {
        val gitDir = File(projectPath, ".git")
        if (!gitDir.exists() || !gitDir.isDirectory) return

        withContext(Dispatchers.IO) {
            val watcher =
                try {
                    java.nio.file.FileSystems
                        .getDefault()
                        .newWatchService()
                } catch (e: Exception) {
                    logger.warn(LogCategory.SYSTEM, "Could not create FS watcher; HEAD updates won't auto-refresh", error = e)
                    return@withContext
                }
            try {
                val gitPath = gitDir.toPath()
                val refsHeadsPath = gitDir.resolve("refs").resolve("heads").toPath()
                val keys = mutableMapOf<java.nio.file.WatchKey, java.nio.file.Path>()

                fun register(p: java.nio.file.Path) {
                    if (!java.nio.file.Files
                            .isDirectory(p)
                    ) {
                        return
                    }
                    runCatching {
                        val k =
                            p.register(
                                watcher,
                                java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
                                java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY,
                                java.nio.file.StandardWatchEventKinds.ENTRY_DELETE,
                            )
                        keys[k] = p
                    }
                }
                // .git itself (HEAD lives here) and refs/heads (branch tips).
                register(gitPath)
                register(refsHeadsPath)

                // Coalesce: capture the first event in a window, drain the
                // queue for ~150 ms, then refresh once.
                val coalesceWindowMs = 150L
                while (kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == true) {
                    val key =
                        try {
                            watcher.take()
                        } catch (_: InterruptedException) {
                            break
                        } catch (_: java.nio.file.ClosedWatchServiceException) {
                            break
                        }
                    var sawHeadOrRef = false

                    fun consumeKey(k: java.nio.file.WatchKey) {
                        val parent = keys[k] ?: return
                        for (ev in k.pollEvents()) {
                            val ctx = ev.context() ?: continue
                            val name = ctx.toString()
                            // .git/HEAD, .git/packed-refs, .git/refs/heads/<branch>
                            if (parent == gitPath && (name == "HEAD" || name == "packed-refs")) {
                                sawHeadOrRef = true
                            } else if (parent == refsHeadsPath) {
                                sawHeadOrRef = true
                            }
                        }
                        k.reset()
                    }
                    consumeKey(key)
                    // Drain bursty events.
                    val deadline = System.currentTimeMillis() + coalesceWindowMs
                    while (true) {
                        val remaining = deadline - System.currentTimeMillis()
                        if (remaining <= 0) break
                        val next = watcher.poll(remaining, java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
                        consumeKey(next)
                    }
                    if (sawHeadOrRef) {
                        try {
                            refreshForWindow(projectPath, windowGitState)
                            // Keep the rest of the git UI consistent with
                            // the new HEAD: file status (working tree),
                            // commit log (visible history), stash list.
                            // The git-status / git-log plugins observe
                            // these via GitDataProvider, so they re-render
                            // automatically.
                            getStatusForWindow(windowGitState)
                            getLogForWindow(windowGitState, 100)
                            refreshStashListForWindow(windowGitState)
                        } catch (e: Exception) {
                            logger.warn(LogCategory.SYSTEM, "HEAD-watcher refresh failed", error = e)
                        }
                    }
                }
            } finally {
                runCatching { watcher.close() }
            }
        }
    }
}

/**
 * Context lines requested for a diff that is read as a file rather than as a
 * patch. Git clamps this to the file's length, so a small file costs nothing.
 */
private const val FULL_FILE_CONTEXT = 100_000

// The retry context for a diff that blew the output cap at full context.
private const val SMALL_FILE_CONTEXT = 3

// Pins the diff header shape against a user's personal gitconfig. `diff.noprefix`,
// `diff.mnemonicPrefix`, `color.diff=always` and `diff.external` all reshape the
// `diff --git` header or inject colour, which UnifiedDiffParser cannot read - and a
// header it cannot read throws out through the provider into the calling plugin.
// These flags force `a/`..`b/` prefixes, no colour, and git's own diff, regardless.
private val DIFF_SHAPE_FLAGS = listOf("--no-color", "--no-ext-diff", "--src-prefix=a/", "--dst-prefix=b/")
