package ai.rever.boss.components.dialogs

import ai.rever.boss.git.GitFileStatus
import ai.rever.boss.git.GitOperationResult
import ai.rever.boss.git.GitService
import ai.rever.boss.plugin.git.GitOperationResult.Error

/** Keeps a dialog's draft and commands attached to the repository it opened for. */
internal class CommitDialogRepository(
    private val projectPath: String?,
    private val windowId: String?,
    private val currentProjectPath: () -> String?,
) {
    val unavailableReason: String?
        get() =
            when {
                projectPath.isNullOrBlank() -> {
                    "No project selected in this window."
                }

                currentProjectPath() != projectPath -> {
                    "The project changed. Close this dialog and reopen Commit in the intended project."
                }

                else -> {
                    null
                }
            }

    suspend fun status(): List<GitFileStatus> =
        if (unavailableReason == null) GitService.getStatus(projectPathOverride = projectPath) else emptyList()

    suspend fun stage(path: String): GitOperationResult = write { GitService.stage(path, windowId, it) }

    suspend fun unstage(path: String): GitOperationResult = write { GitService.unstage(path, windowId, it) }

    suspend fun stageAll(): GitOperationResult = write { GitService.stageAll(windowId, it) }

    suspend fun unstageAll(): GitOperationResult = write { GitService.unstageAll(windowId, it) }

    suspend fun commit(
        message: String,
        amend: Boolean,
        signOff: Boolean = false,
    ): GitOperationResult = write { GitService.commit(message, amend, windowId, it, signOff) }

    suspend fun lastCommitMessage(): String? =
        if (unavailableReason == null) GitService.getLastCommitMessage(projectPathOverride = projectPath) else null

    private suspend fun write(action: suspend (String) -> GitOperationResult): GitOperationResult {
        unavailableReason?.let { return Error(it) }
        return action(requireNotNull(projectPath))
    }
}
