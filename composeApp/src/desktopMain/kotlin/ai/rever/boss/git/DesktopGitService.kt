package ai.rever.boss.git

import ai.rever.boss.plugin.git.GitOperationResult.Success as GitSuccess
import ai.rever.boss.plugin.git.GitOperationResult.Error as GitError
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.LogSanitizer
import ai.rever.boss.components.events.GitTerminalEventBus
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

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

    init {
        // Check if git is available on system startup
        scope.launch {
            _isGitAvailable.value = checkGitAvailable()
        }
    }

    actual suspend fun refresh(projectPath: String) = withContext(Dispatchers.IO) {
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

    actual suspend fun checkout(branchName: String, windowId: String?): GitOperationResult = withContext(Dispatchers.IO) {
        val projectPath = currentProjectPath
            ?: return@withContext GitError("No project selected")

        _isLoading.value = true
        try {
            // For remote branches like "origin/feature", extract just "feature"
            // Git will automatically set up tracking
            val localName = if (branchName.contains("/")) {
                branchName.substringAfter("/")
            } else {
                branchName
            }

            val result = runGitCommand(projectPath, "checkout", localName)
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

    actual suspend fun createBranch(branchName: String, checkout: Boolean, windowId: String?): GitOperationResult =
        withContext(Dispatchers.IO) {
            val projectPath = currentProjectPath
                ?: return@withContext GitError("No project selected")

            _isLoading.value = true
            try {
                val args = if (checkout) {
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

    actual suspend fun pull(): GitOperationResult = withContext(Dispatchers.IO) {
        val projectPath = currentProjectPath
            ?: return@withContext GitError("No project selected")

        _isLoading.value = true
        try {
            val result = runGitCommand(projectPath, "pull")
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

    actual suspend fun push(): GitOperationResult = withContext(Dispatchers.IO) {
        val projectPath = currentProjectPath
            ?: return@withContext GitError("No project selected")

        _isLoading.value = true
        try {
            // Use -u to set upstream if not already set
            val result = runGitCommand(projectPath, "push", "-u", "origin", "HEAD")
            if (result.exitCode == 0) {
                val message = result.output.trim().ifEmpty {
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

    actual suspend fun getCreatePRUrl(): String? = withContext(Dispatchers.IO) {
        val projectPath = currentProjectPath ?: return@withContext null
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
                else -> null
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Error getting PR URL", error = e)
            null
        }
    }

    actual suspend fun merge(branchName: String): GitOperationResult = withContext(Dispatchers.IO) {
        val projectPath = currentProjectPath
            ?: return@withContext GitError("No project selected")

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

    actual suspend fun rebase(branchName: String): GitOperationResult = withContext(Dispatchers.IO) {
        val projectPath = currentProjectPath
            ?: return@withContext GitError("No project selected")

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

    actual suspend fun getStatus(): List<GitFileStatus> = withContext(Dispatchers.IO) {
        val projectPath = currentProjectPath ?: return@withContext emptyList()

        try {
            // Use porcelain v1 format for stable parsing
            val result = runGitCommand(projectPath, "status", "--porcelain=v1")
            if (result.exitCode != 0) {
                _lastError.value = result.error.ifEmpty { result.output }
                return@withContext emptyList()
            }

            val statuses = result.output.lines()
                .filter { it.isNotBlank() }
                .mapNotNull { parseStatusLine(it) }

            _fileStatus.value = statuses
            statuses
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Error getting git status", error = e)
            emptyList()
        }
    }

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

        // Handle rename/copy with arrow
        val (path, originalPath) = if (pathPart.contains(" -> ")) {
            val parts = pathPart.split(" -> ")
            parts[1] to parts[0]
        } else {
            pathPart to null
        }

        val indexStatus = parseStatusChar(indexChar)
        val workTreeStatus = parseStatusChar(workTreeChar)

        // A file is staged if it has an index status (not space or ?)
        val isStaged = indexStatus != null && indexStatus != GitFileStatusType.UNTRACKED
        // A file is unstaged if it has a worktree status (not space)
        val isUnstaged = workTreeStatus != null

        return GitFileStatus(
            path = path,
            indexStatus = indexStatus,
            workTreeStatus = workTreeStatus,
            isStaged = isStaged,
            isUnstaged = isUnstaged,
            originalPath = originalPath
        )
    }

    internal fun parseStatusChar(c: Char): GitFileStatusType? {
        return when (c) {
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
    }

    actual suspend fun stage(filePath: String, windowId: String?): GitOperationResult = withContext(Dispatchers.IO) {
        val projectPath = currentProjectPath
            ?: return@withContext GitError("No project selected")

        val result = runGitCommand(projectPath, "add", "--", filePath)
        if (result.exitCode == 0) {
            getStatus() // Refresh global status
            refreshWindowState(windowId) // Refresh window-specific status
            GitSuccess("Staged '$filePath'")
        } else {
            val errorMsg = result.error.ifEmpty { result.output }.trim()
            GitError(errorMsg, result.exitCode)
        }
    }

    actual suspend fun stageAll(windowId: String?): GitOperationResult = withContext(Dispatchers.IO) {
        val projectPath = currentProjectPath
            ?: return@withContext GitError("No project selected")

        val result = runGitCommand(projectPath, "add", "-A")
        if (result.exitCode == 0) {
            getStatus() // Refresh global status
            refreshWindowState(windowId) // Refresh window-specific status
            GitSuccess("Staged all changes")
        } else {
            val errorMsg = result.error.ifEmpty { result.output }.trim()
            GitError(errorMsg, result.exitCode)
        }
    }

    actual suspend fun unstage(filePath: String, windowId: String?): GitOperationResult = withContext(Dispatchers.IO) {
        val projectPath = currentProjectPath
            ?: return@withContext GitError("No project selected")

        val result = runGitCommand(projectPath, "restore", "--staged", "--", filePath)
        if (result.exitCode == 0) {
            getStatus() // Refresh global status
            refreshWindowState(windowId) // Refresh window-specific status
            GitSuccess("Unstaged '$filePath'")
        } else {
            val errorMsg = result.error.ifEmpty { result.output }.trim()
            GitError(errorMsg, result.exitCode)
        }
    }

    actual suspend fun unstageAll(windowId: String?): GitOperationResult = withContext(Dispatchers.IO) {
        val projectPath = currentProjectPath
            ?: return@withContext GitError("No project selected")

        val result = runGitCommand(projectPath, "restore", "--staged", ".")
        if (result.exitCode == 0) {
            getStatus() // Refresh global status
            refreshWindowState(windowId) // Refresh window-specific status
            GitSuccess("Unstaged all changes")
        } else {
            val errorMsg = result.error.ifEmpty { result.output }.trim()
            GitError(errorMsg, result.exitCode)
        }
    }

    actual suspend fun discardChanges(filePath: String, windowId: String?): GitOperationResult = withContext(Dispatchers.IO) {
        val projectPath = currentProjectPath
            ?: return@withContext GitError("No project selected")

        val result = runGitCommand(projectPath, "restore", "--", filePath)
        if (result.exitCode == 0) {
            getStatus() // Refresh global status
            refreshWindowState(windowId) // Refresh window-specific status
            GitSuccess("Discarded changes to '$filePath'")
        } else {
            val errorMsg = result.error.ifEmpty { result.output }.trim()
            GitError(errorMsg, result.exitCode)
        }
    }

    // ===== Commit Implementation =====

    actual suspend fun commit(message: String, amend: Boolean, windowId: String?): GitOperationResult = withContext(Dispatchers.IO) {
        val projectPath = currentProjectPath
            ?: return@withContext GitError("No project selected")

        _isLoading.value = true
        try {
            val args = if (amend) {
                listOf("commit", "--amend", "-m", message)
            } else {
                listOf("commit", "-m", message)
            }

            val result = runGitCommand(projectPath, *args.toTypedArray())
            if (result.exitCode == 0) {
                getStatus() // Refresh global status
                getLog() // Refresh global log
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

    actual suspend fun getLastCommitMessage(): String? = withContext(Dispatchers.IO) {
        val projectPath = currentProjectPath ?: return@withContext null

        try {
            val result = runGitCommand(projectPath, "log", "-1", "--format=%B")
            if (result.exitCode == 0) {
                result.output.trim().ifEmpty { null }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ===== Commit Log Implementation =====

    actual suspend fun getLog(limit: Int): List<GitCommitInfo> = withContext(Dispatchers.IO) {
        val projectPath = currentProjectPath ?: return@withContext emptyList()

        try {
            // Format: hash|shorthash|author|email|timestamp|subject|parents|refs
            // Using %x00 as separator to handle special characters in subject
            val format = "%H%x00%h%x00%an%x00%ae%x00%at%x00%s%x00%P%x00%D"
            val result = runGitCommand(projectPath, "log", "--format=$format", "-n", limit.toString())

            if (result.exitCode != 0) {
                return@withContext emptyList()
            }

            val commits = result.output.lines()
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
                refs = parts.getOrNull(7)?.split(", ")?.filter { it.isNotBlank() } ?: emptyList()
            )
        } catch (e: Exception) {
            null
        }
    }

    actual suspend fun cherryPick(commitHash: String): GitOperationResult = withContext(Dispatchers.IO) {
        val projectPath = currentProjectPath
            ?: return@withContext GitError("No project selected")

        _isLoading.value = true
        try {
            val result = runGitCommand(projectPath, "cherry-pick", commitHash)
            if (result.exitCode == 0) {
                refresh(projectPath)
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

    actual suspend fun revert(commitHash: String): GitOperationResult = withContext(Dispatchers.IO) {
        val projectPath = currentProjectPath
            ?: return@withContext GitError("No project selected")

        _isLoading.value = true
        try {
            val result = runGitCommand(projectPath, "revert", "--no-edit", commitHash)
            if (result.exitCode == 0) {
                refresh(projectPath)
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

    actual suspend fun stash(message: String?, includeUntracked: Boolean): GitOperationResult =
        withContext(Dispatchers.IO) {
            val projectPath = currentProjectPath
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

    actual suspend fun stashPop(index: Int): GitOperationResult = withContext(Dispatchers.IO) {
        val projectPath = currentProjectPath
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

    actual suspend fun stashApply(index: Int): GitOperationResult = withContext(Dispatchers.IO) {
        val projectPath = currentProjectPath
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

    actual suspend fun stashDrop(index: Int): GitOperationResult = withContext(Dispatchers.IO) {
        val projectPath = currentProjectPath
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

    actual suspend fun refreshStashList(): List<GitStashInfo> = withContext(Dispatchers.IO) {
        val projectPath = currentProjectPath ?: return@withContext emptyList()

        try {
            // Format: stash@{0}: On branch: message
            val result = runGitCommand(projectPath, "stash", "list")
            if (result.exitCode != 0) {
                return@withContext emptyList()
            }

            val stashes = result.output.lines()
                .filter { it.isNotBlank() }
                .mapIndexedNotNull { index, line -> parseStashLine(index, line) }

            _stashList.value = stashes
            stashes
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Error getting stash list", error = e)
            emptyList()
        }
    }

    internal fun parseStashLine(index: Int, line: String): GitStashInfo? {
        // Format: stash@{0}: On branch_name: message
        // or: stash@{0}: WIP on branch_name: hash message
        val regex = Regex("""stash@\{(\d+)\}:\s*(?:(?:WIP on|On)\s+(\S+?):\s*)?(.*)""")
        val match = regex.find(line) ?: return null

        return GitStashInfo(
            index = match.groupValues[1].toIntOrNull() ?: index,
            branch = match.groupValues[2].takeIf { it.isNotBlank() },
            message = match.groupValues[3].trim()
        )
    }

    // ===== Terminal Integration Implementation =====

    actual suspend fun pullInTerminal(windowId: String) {
        val projectPath = currentProjectPath ?: return
        GitTerminalEventBus.openGitTerminal(
            command = "git pull",
            workingDirectory = projectPath,
            operationName = "Pull",
            sourceWindowId = windowId
        )
    }

    actual suspend fun pushInTerminal(windowId: String) {
        val projectPath = currentProjectPath ?: return
        GitTerminalEventBus.openGitTerminal(
            command = "git push -u origin HEAD",
            workingDirectory = projectPath,
            operationName = "Push",
            sourceWindowId = windowId
        )
    }

    actual suspend fun mergeInTerminal(windowId: String, branchName: String) {
        val projectPath = currentProjectPath ?: return
        GitTerminalEventBus.openGitTerminal(
            command = "git merge $branchName",
            workingDirectory = projectPath,
            operationName = "Merge",
            sourceWindowId = windowId
        )
    }

    actual suspend fun rebaseInTerminal(windowId: String, branchName: String) {
        val projectPath = currentProjectPath ?: return
        GitTerminalEventBus.openGitTerminal(
            command = "git rebase $branchName",
            workingDirectory = projectPath,
            operationName = "Rebase",
            sourceWindowId = windowId
        )
    }

    actual suspend fun runInTerminal(windowId: String, vararg args: String) {
        val projectPath = currentProjectPath ?: return
        val command = "git ${args.joinToString(" ")}"
        GitTerminalEventBus.openGitTerminal(
            command = command,
            workingDirectory = projectPath,
            operationName = "Git",
            sourceWindowId = windowId
        )
    }

    // ===== Private helper functions =====

    private data class GitCommandResult(
        val output: String,
        val error: String,
        val exitCode: Int
    )

    private fun checkGitAvailable(): Boolean {
        return try {
            val process = ProcessBuilder("git", "--version")
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Git not available", error = e)
            false
        }
    }

    private fun isGitRepo(projectPath: String): Boolean {
        return try {
            val result = runGitCommand(projectPath, "rev-parse", "--is-inside-work-tree")
            result.exitCode == 0 && result.output.trim() == "true"
        } catch (e: Exception) {
            false
        }
    }

    private fun getCurrentBranchName(projectPath: String): String? {
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
            null
        }
    }

    private fun getLocalBranchList(projectPath: String): List<GitBranchInfo> {
        return try {
            // Get branches with format that includes current marker
            val result = runGitCommand(
                projectPath,
                "branch",
                "--format=%(refname:short)%(HEAD)"
            )
            if (result.exitCode != 0) return emptyList()

            result.output.lines()
                .filter { it.isNotBlank() }
                .map { line ->
                    // The line ends with * if it's the current branch
                    val isCurrent = line.endsWith("*")
                    val name = if (isCurrent) line.dropLast(1) else line
                    GitBranchInfo(name = name, isCurrent = isCurrent, isRemote = false)
                }
                .sortedWith(compareBy({ !it.isCurrent }, { it.name })) // Current branch first
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getRemoteBranchList(projectPath: String): List<GitBranchInfo> {
        return try {
            val result = runGitCommand(
                projectPath,
                "branch",
                "-r",
                "--format=%(refname:short)"
            )
            if (result.exitCode != 0) return emptyList()

            result.output.lines()
                .filter { it.isNotBlank() }
                .filter { !it.contains("HEAD") } // Exclude origin/HEAD
                .map { name ->
                    GitBranchInfo(name = name, isCurrent = false, isRemote = true)
                }
                .sortedBy { it.name }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun runGitCommand(workingDir: String, vararg args: String): GitCommandResult {
        val process = ProcessBuilder("git", *args)
            .directory(File(workingDir))
            .apply {
                // Inherit parent process environment for SSH/git credentials
                environment().putAll(System.getenv())
            }
            .start()

        val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
        val error = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
        val exitCode = process.waitFor()

        return GitCommandResult(output, error, exitCode)
    }

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
    internal fun parseRemoteUrl(remoteUrl: String): String? {
        return try {
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
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ===== Window-Specific Operations =====

    /**
     * Refresh git state for a specific window.
     * Updates the provided WindowGitState instead of global state.
     * This allows multiple windows to have independent git states.
     */
    actual suspend fun refreshForWindow(projectPath: String, windowGitState: WindowGitState?) =
        withContext(Dispatchers.IO) {
            if (windowGitState == null) return@withContext

            windowGitState.setProjectPath(projectPath)
            windowGitState.setLoading(true)

            try {
                if (!_isGitAvailable.value) {
                    windowGitState.updateGitState(
                        isRepo = false,
                        branch = null,
                        local = emptyList(),
                        remote = emptyList()
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
                        remote = emptyList()
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
                    remote = remote
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

                val stashes = result.output.lines()
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
                val result = runGitCommand(projectPath, "status", "--porcelain=v1")
                if (result.exitCode != 0) {
                    return@withContext emptyList()
                }

                val statuses = result.output.lines()
                    .filter { it.isNotBlank() }
                    .mapNotNull { parseStatusLine(it) }

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
    actual suspend fun getLogForWindow(windowGitState: WindowGitState?, limit: Int): List<GitCommitInfo> =
        withContext(Dispatchers.IO) {
            if (windowGitState == null) return@withContext emptyList()

            val projectPath = windowGitState.projectPath.value ?: return@withContext emptyList()

            try {
                val format = "%H%x00%h%x00%an%x00%ae%x00%at%x00%s%x00%P%x00%D"
                val result = runGitCommand(projectPath, "log", "--format=$format", "-n", limit.toString())

                if (result.exitCode != 0) {
                    return@withContext emptyList()
                }

                val commits = result.output.lines()
                    .filter { it.isNotBlank() }
                    .mapNotNull { parseCommitLine(it) }

                windowGitState.updateCommitLog(commits)
                commits
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Error getting log for window", error = e)
                emptyList()
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
        onProgress: (String) -> Unit
    ): GitOperationResult = withContext(Dispatchers.IO) {
        logger.info(
            LogCategory.GENERAL,
            "Starting git clone",
            mapOf(
                "url" to LogSanitizer.maskUriParams(repositoryUrl),
                "target" to targetDirectory
            )
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
            withTimeout(600_000L) { // 10 minutes timeout
                onProgress("Initializing clone...")

                val process = ProcessBuilder(
                    "git", "clone", "--progress", repositoryUrl, targetDirectory
                )
                    .apply {
                        // Inherit parent process environment for SSH/git credentials
                        environment().putAll(System.getenv())
                    }
                    .redirectErrorStream(true) // Merge stderr into stdout for progress
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
                                    progressLine.contains("Cloning into") -> onProgress("Cloning repository...")
                                    progressLine.contains("remote: Counting objects") -> onProgress("Receiving objects...")
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
                                    progressLine.contains("Checking out files") -> onProgress("Checking out files...")
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
                            mapOf("target" to targetDirectory)
                        )
                        GitSuccess()
                    } else {
                        val errorMessage = when {
                            repositoryUrl.contains("@") && exitCode == 128 ->
                                "Authentication failed. Please configure your SSH keys or git credentials."
                            exitCode == 128 ->
                                "Repository not found or access denied. Please check the URL and your permissions."
                            exitCode == 1 ->
                                "Network error. Please check your internet connection."
                            else ->
                                "Clone failed with exit code $exitCode. Please check the repository URL and try again."
                        }
                        logger.error(
                            LogCategory.GENERAL,
                            "Clone failed",
                            mapOf("exitCode" to exitCode, "message" to errorMessage)
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
            val errorMessage = "Clone operation timed out after 10 minutes. The repository may be too large or the connection too slow. Try cloning from terminal instead."
            logger.error(LogCategory.GENERAL, errorMessage, error = e)
            // Clean up partial clone
            try {
                File(targetDirectory).deleteRecursively()
            } catch (cleanupError: Exception) {
                logger.warn(LogCategory.GENERAL, "Failed to clean up after timeout", error = cleanupError)
            }
            GitError(errorMessage)
        } catch (e: IOException) {
            val errorMessage = when {
                e.message?.contains("Connection refused") == true ->
                    "Network connection refused. Check your internet connection and firewall settings."
                e.message?.contains("No such host") == true || e.message?.contains("unknown host") == true ->
                    "Repository host not found. Check the repository URL and your DNS settings."
                e.message?.contains("Permission denied") == true ->
                    "Permission denied. Check file system permissions for the target directory."
                e.message?.contains("No space left") == true ->
                    "Insufficient disk space. Free up space and try again."
                else ->
                    "I/O error during clone: ${e.message}. Check your network connection and disk space."
            }
            logger.error(LogCategory.GENERAL, errorMessage, error = e)
            GitError(errorMessage)
        } catch (e: SecurityException) {
            val errorMessage = "Security permission denied. Check file system permissions for '${File(targetDirectory).parentFile?.absolutePath}'."
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
            val watcher = try {
                java.nio.file.FileSystems.getDefault().newWatchService()
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Could not create FS watcher; HEAD updates won't auto-refresh", error = e)
                return@withContext
            }
            try {
                val gitPath = gitDir.toPath()
                val refsHeadsPath = gitDir.resolve("refs").resolve("heads").toPath()
                val keys = mutableMapOf<java.nio.file.WatchKey, java.nio.file.Path>()
                fun register(p: java.nio.file.Path) {
                    if (!java.nio.file.Files.isDirectory(p)) return
                    runCatching {
                        val k = p.register(
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
                    val key = try {
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
