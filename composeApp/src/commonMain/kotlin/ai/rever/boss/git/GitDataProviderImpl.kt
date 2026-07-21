package ai.rever.boss.git

import ai.rever.boss.components.events.FileEventBus
import ai.rever.boss.plugin.api.GitCommitInfoData
import ai.rever.boss.plugin.api.GitDataProvider
import ai.rever.boss.plugin.api.GitFileStatusData
import ai.rever.boss.plugin.api.GitFileStatusTypeData
import ai.rever.boss.plugin.api.GitOperationResultData
import ai.rever.boss.plugin.git.GitCommitInfo
import ai.rever.boss.plugin.git.GitFileStatus
import ai.rever.boss.plugin.git.GitFileStatusType
import ai.rever.boss.plugin.git.GitOperationResult
import ai.rever.boss.window.WindowGitState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Implementation of GitDataProvider that wraps GitService and WindowGitState.
 *
 * This adapter allows the Git panels to be extracted to separate modules
 * while keeping the Git infrastructure in composeApp.
 *
 * @param windowGitState The window-specific git state (nullable for flexibility)
 * @param windowIdProvider Provider for the current window ID
 */
class GitDataProviderImpl(
    private val windowGitState: WindowGitState?,
    private val windowIdProvider: () -> String?
) : GitDataProvider {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // State flows mapped from WindowGitState - initialized with current values
    private val _fileStatus = MutableStateFlow(
        windowGitState?.fileStatus?.value?.map { it.toData() } ?: emptyList()
    )
    override val fileStatus: StateFlow<List<GitFileStatusData>> = _fileStatus

    private val _commitLog = MutableStateFlow(
        windowGitState?.commitLog?.value?.map { it.toData() } ?: emptyList()
    )
    override val commitLog: StateFlow<List<GitCommitInfoData>> = _commitLog

    private val _isGitRepository = MutableStateFlow(
        windowGitState?.isGitRepository?.value ?: false
    )
    override val isGitRepository: StateFlow<Boolean> = _isGitRepository

    private val _isLoading = MutableStateFlow(
        windowGitState?.isLoading?.value ?: false
    )
    override val isLoading: StateFlow<Boolean> = _isLoading

    init {
        // Collect from WindowGitState and map to plugin API types
        windowGitState?.let { state ->
            scope.launch {
                state.fileStatus.collect { statuses ->
                    _fileStatus.value = statuses.map { it.toData() }
                }
            }
            scope.launch {
                state.commitLog.collect { commits ->
                    _commitLog.value = commits.map { it.toData() }
                }
            }
            scope.launch {
                state.isGitRepository.collect { isRepo ->
                    _isGitRepository.value = isRepo
                }
            }
            scope.launch {
                state.isLoading.collect { loading ->
                    _isLoading.value = loading
                }
            }
        }
    }

    override suspend fun refreshStatus() {
        GitService.getStatusForWindow(windowGitState)
    }

    override suspend fun refreshLog(limit: Int) {
        GitService.getLogForWindow(windowGitState, limit)
    }

    override suspend fun stage(filePath: String): GitOperationResultData {
        return GitService.stage(filePath, windowIdProvider()).toData()
    }

    override suspend fun unstage(filePath: String): GitOperationResultData {
        return GitService.unstage(filePath, windowIdProvider()).toData()
    }

    override suspend fun stageAll(): GitOperationResultData {
        return GitService.stageAll(windowIdProvider()).toData()
    }

    override suspend fun unstageAll(): GitOperationResultData {
        return GitService.unstageAll(windowIdProvider()).toData()
    }

    override suspend fun discardChanges(filePath: String): GitOperationResultData {
        return GitService.discardChanges(filePath, windowIdProvider()).toData()
    }

    override suspend fun cherryPick(commitHash: String): GitOperationResultData {
        return GitService.cherryPick(commitHash).toData()
    }

    override suspend fun revert(commitHash: String): GitOperationResultData {
        return GitService.revert(commitHash).toData()
    }

    override suspend fun checkout(ref: String): GitOperationResultData {
        return GitService.checkout(ref, windowIdProvider()).toData()
    }

    override fun getCurrentProjectPath(): String? {
        return GitService.getCurrentProjectPath()
    }

    override fun openFile(filePath: String, windowId: String) {
        val projectPath = getCurrentProjectPath()
        if (projectPath != null) {
            val fullPath = "$projectPath/$filePath"
            scope.launch {
                FileEventBus.openFile(fullPath, sourceWindowId = windowId)
            }
        }
    }

    // ===== Type Conversion Extensions =====

    private fun GitFileStatus.toData(): GitFileStatusData = GitFileStatusData(
        path = path,
        indexStatus = indexStatus?.toData(),
        workTreeStatus = workTreeStatus?.toData(),
        isStaged = isStaged,
        isUnstaged = isUnstaged
    )

    private fun GitFileStatusType.toData(): GitFileStatusTypeData = when (this) {
        GitFileStatusType.MODIFIED -> GitFileStatusTypeData.MODIFIED
        GitFileStatusType.ADDED -> GitFileStatusTypeData.ADDED
        GitFileStatusType.DELETED -> GitFileStatusTypeData.DELETED
        GitFileStatusType.RENAMED -> GitFileStatusTypeData.RENAMED
        GitFileStatusType.COPIED -> GitFileStatusTypeData.COPIED
        GitFileStatusType.UNTRACKED -> GitFileStatusTypeData.UNTRACKED
        GitFileStatusType.IGNORED -> GitFileStatusTypeData.IGNORED
        GitFileStatusType.UNMERGED -> GitFileStatusTypeData.UNMERGED
    }

    private fun GitCommitInfo.toData(): GitCommitInfoData = GitCommitInfoData(
        hash = hash,
        shortHash = shortHash,
        subject = subject,
        author = author,
        authorEmail = authorEmail,
        date = date,
        refs = refs
    )

    private fun GitOperationResult.toData(): GitOperationResultData = when (this) {
        is GitOperationResult.Success -> GitOperationResultData.Success(message)
        is GitOperationResult.Error -> GitOperationResultData.Error(message)
    }
}
