package ai.rever.boss.companion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CompanionStateStore(
    private val maxFinishedTasks: Int = 10,
) {
    private val _tasks = MutableStateFlow<Map<String, CompanionTask>>(emptyMap())
    val tasks: StateFlow<Map<String, CompanionTask>> = _tasks.asStateFlow()

    fun handle(event: CompanionEvent) {
        val task =
            CompanionTask(
                id = event.taskId,
                name = event.taskName,
                status = event.status(),
                sourceWindowId = event.sourceWindowId,
                context = event.context,
            )

        val updated = _tasks.value.toMutableMap()
        updated[event.taskId] = task

        if (event.isFinished()) {
            trimFinishedTasks(updated)
        }

        _tasks.value = updated
    }

    fun getTask(taskId: String): CompanionTask? = _tasks.value[taskId]

    fun clear() {
        _tasks.value = emptyMap()
    }

    private fun trimFinishedTasks(tasks: MutableMap<String, CompanionTask>) {
        val finished =
            tasks.values
                .filter { it.status.isFinished() }
                .sortedBy { it.id }

        val overflow = finished.size - maxFinishedTasks

        if (overflow > 0) {
            finished.take(overflow).forEach { task ->
                tasks.remove(task.id)
            }
        }
    }

    private fun CompanionEvent.status(): CompanionTaskStatus =
        when (this) {
            is CompanionEvent.Started -> CompanionTaskStatus.WORKING
            is CompanionEvent.WaitingForInput -> CompanionTaskStatus.WAITING_FOR_INPUT
            is CompanionEvent.Completed -> CompanionTaskStatus.COMPLETED
            is CompanionEvent.Failed -> CompanionTaskStatus.FAILED
            is CompanionEvent.Stopped -> CompanionTaskStatus.STOPPED
        }

    private fun CompanionEvent.isFinished(): Boolean = status().isFinished()
}

private fun CompanionTaskStatus.isFinished(): Boolean =
    this == CompanionTaskStatus.COMPLETED ||
        this == CompanionTaskStatus.FAILED ||
        this == CompanionTaskStatus.STOPPED
