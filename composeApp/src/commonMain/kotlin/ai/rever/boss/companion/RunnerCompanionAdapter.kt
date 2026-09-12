package ai.rever.boss.companion

import ai.rever.boss.components.events.RunEventBus
import ai.rever.boss.components.events.RunProcessEvent
import ai.rever.boss.components.events.RunProcessEventBus
import ai.rever.boss.components.events.RunProcessStatus
import ai.rever.boss.plugin.run.RunExecuteEvent
import ai.rever.boss.plugin.run.RunStopEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

sealed class CompanionRunnerEvent {
    data class Execute(
        val event: RunExecuteEvent,
    ) : CompanionRunnerEvent()

    data class Stop(
        val event: RunStopEvent,
    ) : CompanionRunnerEvent()

    data class ProcessResult(
        val event: RunProcessEvent,
    ) : CompanionRunnerEvent()
}

interface CompanionRunnerEventSource {
    val events: Flow<CompanionRunnerEvent>
}

object BossRunnerEventSource : CompanionRunnerEventSource {
    override val events: Flow<CompanionRunnerEvent> =
        merge(
            RunEventBus.executeEvents.map { CompanionRunnerEvent.Execute(it) },
            RunEventBus.stopEvents.map { CompanionRunnerEvent.Stop(it) },
            RunProcessEventBus.events.map { CompanionRunnerEvent.ProcessResult(it) },
        )
}

@Suppress("TooManyFunctions")
class RunnerCompanionAdapter(
    private val eventSource: CompanionRunnerEventSource = BossRunnerEventSource,
    private val emitEvent: suspend (CompanionEvent) -> Unit = {
        ai.rever.boss.components.events.CompanionEventBus
            .emit(it)
    },
) {
    private val trackedTasks = LinkedHashMap<String, CompanionTask>()
    private val taskConfigIds = LinkedHashMap<String, String>()
    private var job: Job? = null

    fun start(scope: CoroutineScope): Job {
        stop()

        job =
            scope.launch {
                eventSource.events.collect { event ->
                    handle(event)
                }
            }

        return job!!
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun clear() {
        trackedTasks.clear()
        taskConfigIds.clear()
    }

    suspend fun handle(event: CompanionRunnerEvent) {
        when (event) {
            is CompanionRunnerEvent.Execute -> Unit
            is CompanionRunnerEvent.Stop -> handleStop(event.event)
            is CompanionRunnerEvent.ProcessResult -> handleProcessResult(event.event)
        }
    }

    fun trackedTask(taskId: String): CompanionTask? = trackedTasks[taskId]

    fun trackedTasks(): List<CompanionTask> = trackedTasks.values.toList()

    private suspend fun handleProcessResult(event: RunProcessEvent) {
        when (event.status) {
            RunProcessStatus.STARTED -> handleStarted(event)
            RunProcessStatus.WAITING_FOR_INPUT -> handleWaitingForInput(event)
            RunProcessStatus.COMPLETED -> handleCompleted(event)
            RunProcessStatus.FAILED -> handleFailed(event)
            RunProcessStatus.STOPPED -> handleStopped(event)
        }
    }

    private suspend fun handleStarted(event: RunProcessEvent) {
        val task =
            CompanionTask(
                id = event.processId,
                name = event.configName,
                status = CompanionTaskStatus.WORKING,
                sourceWindowId = event.windowId,
            )

        trackedTasks[task.id] = task
        taskConfigIds[task.id] = event.configId

        emitEvent(
            CompanionEvent.Started(
                taskId = task.id,
                taskName = task.name,
                sourceWindowId = task.sourceWindowId,
            ),
        )
    }

    private suspend fun handleWaitingForInput(event: RunProcessEvent) {
        trackedTasks[event.processId]
            ?.takeIf { it.isActive() }
            ?.let { task ->
                val waiting =
                    task.copy(
                        status = CompanionTaskStatus.WAITING_FOR_INPUT,
                        context =
                            CompanionTaskContext(
                                windowId = event.windowId,
                                tabId = event.terminalId,
                            ),
                    )

                trackedTasks[task.id] = waiting

                emitEvent(
                    CompanionEvent.WaitingForInput(
                        taskId = waiting.id,
                        taskName = waiting.name,
                        sourceWindowId = waiting.sourceWindowId,
                        context = waiting.context,
                    ),
                )
            }
    }

    private suspend fun handleCompleted(event: RunProcessEvent) {
        trackedTasks[event.processId]
            ?.takeIf { it.isActive() }
            ?.let { task ->
                val completed =
                    task.copy(
                        status = CompanionTaskStatus.COMPLETED,
                        context =
                            CompanionTaskContext(
                                windowId = event.windowId,
                                tabId = event.terminalId,
                                resultAvailable = true,
                            ),
                    )

                trackedTasks[task.id] = completed

                emitEvent(
                    CompanionEvent.Completed(
                        taskId = completed.id,
                        taskName = completed.name,
                        sourceWindowId = completed.sourceWindowId,
                        context = completed.context,
                    ),
                )
            }
    }

    private suspend fun handleFailed(event: RunProcessEvent) {
        trackedTasks[event.processId]
            ?.takeIf { it.isActive() }
            ?.let { task ->
                val failed =
                    task.copy(
                        status = CompanionTaskStatus.FAILED,
                        context =
                            CompanionTaskContext(
                                windowId = event.windowId,
                                tabId = event.terminalId,
                            ),
                    )

                trackedTasks[task.id] = failed

                emitEvent(
                    CompanionEvent.Failed(
                        taskId = failed.id,
                        taskName = failed.name,
                        sourceWindowId = failed.sourceWindowId,
                        context = failed.context,
                    ),
                )
            }
    }

    private suspend fun handleStopped(event: RunProcessEvent) {
        trackedTasks[event.processId]
            ?.takeIf { it.isActive() }
            ?.let { task ->
                stopTask(task)
            }
    }

    private suspend fun handleStop(event: RunStopEvent) {
        val tasks =
            trackedTasks.values
                .filter { it.isActive() }
                .filter { task ->
                    event.sourceWindowId == task.sourceWindowId
                }.filter { task ->
                    event.configId == null ||
                        taskConfigIds[task.id] == event.configId
                }.toList()

        tasks.forEach { stopTask(it) }
    }

    private suspend fun stopTask(task: CompanionTask) {
        val stopped =
            task.copy(status = CompanionTaskStatus.STOPPED)

        trackedTasks[task.id] = stopped

        emitEvent(
            CompanionEvent.Stopped(
                taskId = stopped.id,
                taskName = stopped.name,
                sourceWindowId = stopped.sourceWindowId,
                context = stopped.context,
            ),
        )
    }

    private fun CompanionTask.isActive(): Boolean =
        status == CompanionTaskStatus.WORKING ||
            status == CompanionTaskStatus.WAITING_FOR_INPUT
}
