package ai.rever.boss.companion

import ai.rever.boss.components.events.RunProcessEvent
import ai.rever.boss.components.events.RunProcessStatus
import ai.rever.boss.plugin.run.Language
import ai.rever.boss.plugin.run.RunConfiguration
import ai.rever.boss.plugin.run.RunConfigurationType
import ai.rever.boss.plugin.run.RunExecuteEvent
import ai.rever.boss.plugin.run.RunStopEvent
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RunnerCompanionAdapterTest {
    @Test
    fun processStartCreatesWorkingTask() =
        runBlocking {
            val adapter = RunnerCompanionAdapter()

            adapter.handle(
                CompanionRunnerEvent.ProcessResult(
                    RunProcessEvent(
                        processId = "process-1",
                        configId = "config-1",
                        configName = "Build Project",
                        windowId = "window-1",
                        terminalId = "terminal-1",
                        status = RunProcessStatus.STARTED,
                    ),
                ),
            )

            val task = adapter.trackedTask("process-1")

            assertEquals(CompanionTaskStatus.WORKING, task?.status)
            assertEquals("Build Project", task?.name)
            assertEquals("window-1", task?.sourceWindowId)
        }

    @Test
    fun stoppingOneTaskDoesNotStopAnother() =
        runBlocking {
            val adapter = RunnerCompanionAdapter()

            adapter.handle(
                CompanionRunnerEvent.ProcessResult(
                    RunProcessEvent(
                        processId = "process-1",
                        configId = "config-1",
                        configName = "Build One",
                        windowId = "window-1",
                        terminalId = "terminal-1",
                        status = RunProcessStatus.STARTED,
                    ),
                ),
            )

            adapter.handle(
                CompanionRunnerEvent.ProcessResult(
                    RunProcessEvent(
                        processId = "process-2",
                        configId = "config-2",
                        configName = "Build Two",
                        windowId = "window-1",
                        terminalId = "terminal-2",
                        status = RunProcessStatus.STARTED,
                    ),
                ),
            )

            adapter.handle(
                CompanionRunnerEvent.Stop(
                    RunStopEvent(
                        configId = "config-1",
                        sourceWindowId = "window-1",
                    ),
                ),
            )

            assertEquals(
                CompanionTaskStatus.STOPPED,
                adapter.trackedTask("process-1")?.status,
            )
            assertEquals(
                CompanionTaskStatus.WORKING,
                adapter.trackedTask("process-2")?.status,
            )
        }

    @Test
    fun stopAllStopsActiveTasks() =
        runBlocking {
            val adapter = RunnerCompanionAdapter()

            adapter.handle(
                CompanionRunnerEvent.ProcessResult(
                    RunProcessEvent(
                        processId = "process-1",
                        configId = "config-1",
                        configName = "Build One",
                        windowId = "window-1",
                        terminalId = "terminal-1",
                        status = RunProcessStatus.STARTED,
                    ),
                ),
            )

            adapter.handle(
                CompanionRunnerEvent.ProcessResult(
                    RunProcessEvent(
                        processId = "process-2",
                        configId = "config-2",
                        configName = "Build Two",
                        windowId = "window-1",
                        terminalId = "terminal-2",
                        status = RunProcessStatus.STARTED,
                    ),
                ),
            )

            adapter.handle(
                CompanionRunnerEvent.Stop(
                    RunStopEvent(
                        configId = null,
                        sourceWindowId = "window-1",
                    ),
                ),
            )

            assertEquals(
                CompanionTaskStatus.STOPPED,
                adapter.trackedTask("process-1")?.status,
            )
            assertEquals(
                CompanionTaskStatus.STOPPED,
                adapter.trackedTask("process-2")?.status,
            )
        }

    @Test
    fun stopAllOnlyStopsTasksFromSourceWindow() =
        runBlocking {
            val adapter = RunnerCompanionAdapter()

            adapter.handle(
                CompanionRunnerEvent.ProcessResult(
                    RunProcessEvent(
                        processId = "process-1",
                        configId = "config-1",
                        configName = "Build One",
                        windowId = "window-1",
                        terminalId = "terminal-1",
                        status = RunProcessStatus.STARTED,
                    ),
                ),
            )

            adapter.handle(
                CompanionRunnerEvent.ProcessResult(
                    RunProcessEvent(
                        processId = "process-2",
                        configId = "config-2",
                        configName = "Build Two",
                        windowId = "window-2",
                        terminalId = "terminal-2",
                        status = RunProcessStatus.STARTED,
                    ),
                ),
            )

            adapter.handle(
                CompanionRunnerEvent.Stop(
                    RunStopEvent(
                        configId = null,
                        sourceWindowId = "window-1",
                    ),
                ),
            )

            assertEquals(
                CompanionTaskStatus.STOPPED,
                adapter.trackedTask("process-1")?.status,
            )
            assertEquals(
                CompanionTaskStatus.WORKING,
                adapter.trackedTask("process-2")?.status,
            )
        }

    @Test
    fun sourceWindowIsPreserved() =
        runBlocking {
            val adapter = RunnerCompanionAdapter()

            adapter.handle(
                CompanionRunnerEvent.ProcessResult(
                    RunProcessEvent(
                        processId = "process-1",
                        configId = "config-1",
                        configName = "Build Project",
                        windowId = "window-42",
                        terminalId = "terminal-1",
                        status = RunProcessStatus.STARTED,
                    ),
                ),
            )

            assertEquals(
                "window-42",
                adapter.trackedTask("process-1")?.sourceWindowId,
            )
        }

    @Test
    fun simultaneousRunsOfSameConfigurationRemainIndependent() =
        runBlocking {
            val adapter = RunnerCompanionAdapter()

            adapter.handle(
                CompanionRunnerEvent.ProcessResult(
                    RunProcessEvent(
                        processId = "process-1",
                        configId = "same-config",
                        configName = "Build Project",
                        windowId = "window-1",
                        terminalId = "terminal-1",
                        status = RunProcessStatus.STARTED,
                    ),
                ),
            )

            adapter.handle(
                CompanionRunnerEvent.ProcessResult(
                    RunProcessEvent(
                        processId = "process-2",
                        configId = "same-config",
                        configName = "Build Project",
                        windowId = "window-1",
                        terminalId = "terminal-2",
                        status = RunProcessStatus.STARTED,
                    ),
                ),
            )

            assertEquals(2, adapter.trackedTasks().size)

            assertEquals(
                CompanionTaskStatus.WORKING,
                adapter.trackedTask("process-1")?.status,
            )
            assertEquals(
                CompanionTaskStatus.WORKING,
                adapter.trackedTask("process-2")?.status,
            )

            adapter.handle(
                CompanionRunnerEvent.ProcessResult(
                    RunProcessEvent(
                        processId = "process-1",
                        configId = "same-config",
                        configName = "Build Project",
                        windowId = "window-1",
                        terminalId = "terminal-1",
                        status = RunProcessStatus.COMPLETED,
                    ),
                ),
            )

            assertEquals(
                CompanionTaskStatus.COMPLETED,
                adapter.trackedTask("process-1")?.status,
            )
            assertEquals(
                CompanionTaskStatus.WORKING,
                adapter.trackedTask("process-2")?.status,
            )
        }
}
