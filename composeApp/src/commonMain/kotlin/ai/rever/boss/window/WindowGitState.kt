package ai.rever.boss.window

import ai.rever.boss.git.GitBranchInfo
import ai.rever.boss.git.GitCommitInfo
import ai.rever.boss.git.GitFileStatus
import ai.rever.boss.git.GitStashInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Window-scoped state for Git repository information.
 * Each window maintains its own git state independently.
 *
 * This allows different windows to show git information for their respective projects
 * without affecting each other. When a new window opens with no project or a non-git
 * project, it won't clear the git state of other windows.
 *
 * Pattern follows WindowRunnerState for consistency.
 */
class WindowGitState(
    val windowId: String,
) {
    private val _projectPath = MutableStateFlow<String?>(null)
    val projectPath: StateFlow<String?> = _projectPath.asStateFlow()

    private val _isGitRepository = MutableStateFlow(false)
    val isGitRepository: StateFlow<Boolean> = _isGitRepository.asStateFlow()

    private val _currentBranch = MutableStateFlow<String?>(null)
    val currentBranch: StateFlow<String?> = _currentBranch.asStateFlow()

    private val _localBranches = MutableStateFlow<List<GitBranchInfo>>(emptyList())
    val localBranches: StateFlow<List<GitBranchInfo>> = _localBranches.asStateFlow()

    private val _remoteBranches = MutableStateFlow<List<GitBranchInfo>>(emptyList())
    val remoteBranches: StateFlow<List<GitBranchInfo>> = _remoteBranches.asStateFlow()

    private val _stashList = MutableStateFlow<List<GitStashInfo>>(emptyList())
    val stashList: StateFlow<List<GitStashInfo>> = _stashList.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _fileStatus = MutableStateFlow<List<GitFileStatus>>(emptyList())
    val fileStatus: StateFlow<List<GitFileStatus>> = _fileStatus.asStateFlow()

    private val _commitLog = MutableStateFlow<List<GitCommitInfo>>(emptyList())
    val commitLog: StateFlow<List<GitCommitInfo>> = _commitLog.asStateFlow()

    /**
     * Update all git state for this window.
     * Called from GitService after fetching git data.
     */
    fun updateGitState(
        isRepo: Boolean,
        branch: String?,
        local: List<GitBranchInfo>,
        remote: List<GitBranchInfo>,
    ) {
        _isGitRepository.value = isRepo
        _currentBranch.value = branch
        _localBranches.value = local
        _remoteBranches.value = remote
    }

    /**
     * Update loading state for this window.
     */
    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }

    /**
     * Set the project path for this window.
     */
    fun setProjectPath(path: String?) {
        _projectPath.value = path
    }

    /**
     * Update stash list for this window.
     */
    fun updateStashList(stashes: List<GitStashInfo>) {
        _stashList.value = stashes
    }

    /**
     * Update file status for this window.
     */
    fun updateFileStatus(statuses: List<GitFileStatus>) {
        _fileStatus.value = statuses
    }

    /**
     * Update commit log for this window.
     */
    fun updateCommitLog(commits: List<GitCommitInfo>) {
        _commitLog.value = commits
    }

    /**
     * Clear git state for this window.
     * Called when no project is selected or project is not a git repository.
     */
    fun clear() {
        _projectPath.value = null
        _isGitRepository.value = false
        _currentBranch.value = null
        _localBranches.value = emptyList()
        _remoteBranches.value = emptyList()
        _stashList.value = emptyList()
        _isLoading.value = false
        _fileStatus.value = emptyList()
        _commitLog.value = emptyList()
    }
}
