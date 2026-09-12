package ai.rever.boss.pet

import ai.rever.boss.updater.UpdateState
import ai.rever.boss.utils.logging.LogSanitizer

/** Observes only the updater's task; quiet updater transitions must not dismiss other results. */
internal fun reportPetUpdateState(
    controller: BossPetController,
    state: UpdateState,
) {
    if (state !is UpdateState.ReadyToInstall && state !is UpdateState.Downloading) {
        controller.retractCompletion(UPDATE_TASK_ID, "Update ready to install")
    }
    when (state) {
        is UpdateState.CheckingForUpdates,
        is UpdateState.Downloading,
        is UpdateState.Installing,
        -> {
            controller.taskStarted(UPDATE_TASK_ID)
        }

        is UpdateState.ReadyToInstall -> {
            controller.taskFinished(UPDATE_TASK_ID, "Update ready to install")
        }

        is UpdateState.RestartRequired -> {
            controller.taskFinished(UPDATE_TASK_ID, "Restart BOSS", requiresAcknowledgement = true)
        }

        is UpdateState.Error -> {
            controller.taskFailed(UPDATE_TASK_ID, petUpdateFailureLabel(state.message))
        }

        is UpdateState.Idle,
        is UpdateState.UpToDate,
        is UpdateState.UpdateAvailable,
        -> {
            controller.taskStopped(UPDATE_TASK_ID)
        }
    }
}

private const val UPDATE_TASK_ID = "app-update"

internal fun petUpdateFailureLabel(message: String): String {
    val detail =
        LogSanitizer
            .sanitizeExceptionMessage(message)
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(80)
    return if (detail.isEmpty()) "Update failed" else "Update: $detail"
}
