package ai.rever.boss.companion

import ai.rever.boss.components.events.RunProcessEvent
import ai.rever.boss.components.events.RunProcessEventBus
import ai.rever.boss.plugin.api.CustomPluginEvent
import ai.rever.boss.run.ProcessStatus
import ai.rever.boss.run.RunExecutionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class RunProcessCompanionAdapter(
    private val applicationEvents: Flow<CustomPluginEvent>,
    private val emitProcessEvent: suspend (RunProcessEvent) -> Unit = {
        RunProcessEventBus.emit(it)
    },
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope): Job {
        stop()

        job =
            applicationEvents
                .filter { it.sourcePluginId == "boss-plugin-terminal-tab" }
                .filter {
                    it.eventName == "terminal.command.lifecycle" ||
                        it.eventName == "terminal.command.waiting_for_input"
                }.onEach { event ->
                    handle(event)
                }.launchIn(scope)

        return job!!
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    suspend fun handle(event: CustomPluginEvent) {
        when (event.eventName) {
            "terminal.command.waiting_for_input" -> handleWaitingForInput(event.payload)
            "terminal.command.lifecycle" -> handleLifecycle(event.payload)
        }
    }

    private suspend fun handleLifecycle(payload: Map<String, Any?>) {
        val windowId = payload["windowId"] as? String
        val terminalId = payload["terminalId"] as? String
        val exitCode = (payload["exitCode"] as? Number)?.toInt()

        if (windowId != null && terminalId != null && exitCode != null) {
            findRunningProcess(windowId, terminalId)?.let { process ->
                val failed = exitCode != 0

                emitProcessEvent(
                    RunProcessEvent(
                        processId = process.id,
                        configId = process.configId,
                        configName = process.configName,
                        windowId = process.windowId,
                        terminalId = process.terminalId,
                        status =
                            if (failed) {
                                ai.rever.boss.components.events.RunProcessStatus.FAILED
                            } else {
                                ai.rever.boss.components.events.RunProcessStatus.COMPLETED
                            },
                        exitCode = exitCode,
                    ),
                )

                RunExecutionService.markCompleted(
                    processId = process.id,
                    failed = failed,
                )
            }
        }
    }

    private suspend fun handleWaitingForInput(payload: Map<String, Any?>) {
        val windowId = payload["windowId"] as? String
        val terminalId = payload["terminalId"] as? String

        if (windowId != null && terminalId != null) {
            findRunningProcess(windowId, terminalId)?.let { process ->
                emitProcessEvent(
                    RunProcessEvent(
                        processId = process.id,
                        configId = process.configId,
                        configName = process.configName,
                        windowId = process.windowId,
                        terminalId = process.terminalId,
                        status =
                            ai.rever.boss.components.events.RunProcessStatus.WAITING_FOR_INPUT,
                    ),
                )
            }
        }
    }

    private fun findRunningProcess(
        windowId: String,
        terminalId: String,
    ): ai.rever.boss.run.RunningProcess? =
        RunExecutionService.runningProcesses.value.firstOrNull {
            it.windowId == windowId &&
                it.terminalId == terminalId &&
                (
                    it.status == ProcessStatus.STARTING ||
                        it.status == ProcessStatus.RUNNING
                )
        }
}
