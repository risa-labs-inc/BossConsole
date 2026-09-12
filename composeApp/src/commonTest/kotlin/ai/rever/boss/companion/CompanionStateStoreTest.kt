package ai.rever.boss.companion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CompanionStateStoreTest {
    @Test
    fun tracksMultipleTasksIndependently() {
        val store = CompanionStateStore()

        store.handle(
            CompanionEvent.Started(
                taskId = "task-1",
                taskName = "Build project",
                sourceWindowId = "window-1",
            ),
        )

        store.handle(
            CompanionEvent.Started(
                taskId = "task-2",
                taskName = "Run tests",
                sourceWindowId = "window-2",
            ),
        )

        assertEquals(
            CompanionTaskStatus.WORKING,
            store.getTask("task-1")?.status,
        )
        assertEquals(
            CompanionTaskStatus.WORKING,
            store.getTask("task-2")?.status,
        )
    }

    @Test
    fun completionOnlyChangesTheMatchingTask() {
        val store = CompanionStateStore()

        store.handle(
            CompanionEvent.Started(
                taskId = "task-1",
                taskName = "Build project",
            ),
        )

        store.handle(
            CompanionEvent.Started(
                taskId = "task-2",
                taskName = "Run tests",
            ),
        )

        store.handle(
            CompanionEvent.Completed(
                taskId = "task-1",
                taskName = "Build project",
            ),
        )

        assertEquals(
            CompanionTaskStatus.COMPLETED,
            store.getTask("task-1")?.status,
        )
        assertEquals(
            CompanionTaskStatus.WORKING,
            store.getTask("task-2")?.status,
        )
    }

    @Test
    fun failedTaskIsDistinctFromCompletedTask() {
        val store = CompanionStateStore()

        store.handle(
            CompanionEvent.Completed(
                taskId = "successful-task",
                taskName = "Successful build",
            ),
        )

        store.handle(
            CompanionEvent.Failed(
                taskId = "failed-task",
                taskName = "Failed build",
            ),
        )

        assertEquals(
            CompanionTaskStatus.COMPLETED,
            store.getTask("successful-task")?.status,
        )
        assertEquals(
            CompanionTaskStatus.FAILED,
            store.getTask("failed-task")?.status,
        )
    }

    @Test
    fun waitingForInputIsNotTreatedAsCompleted() {
        val store = CompanionStateStore()

        store.handle(
            CompanionEvent.Started(
                taskId = "task-1",
                taskName = "Interactive task",
            ),
        )

        store.handle(
            CompanionEvent.WaitingForInput(
                taskId = "task-1",
                taskName = "Interactive task",
            ),
        )

        val task = store.getTask("task-1")

        assertNotNull(task)
        assertEquals(
            CompanionTaskStatus.WAITING_FOR_INPUT,
            task.status,
        )
    }

    @Test
    fun waitingTaskCanLaterComplete() {
        val store = CompanionStateStore()

        store.handle(
            CompanionEvent.WaitingForInput(
                taskId = "task-1",
                taskName = "Interactive task",
            ),
        )

        assertEquals(
            CompanionTaskStatus.WAITING_FOR_INPUT,
            store.getTask("task-1")?.status,
        )

        store.handle(
            CompanionEvent.Completed(
                taskId = "task-1",
                taskName = "Interactive task",
            ),
        )

        assertEquals(
            CompanionTaskStatus.COMPLETED,
            store.getTask("task-1")?.status,
        )
    }

    @Test
    fun taskContextIsPreservedForNavigation() {
        val store = CompanionStateStore()

        store.handle(
            CompanionEvent.Completed(
                taskId = "task-1",
                taskName = "Build project",
                sourceWindowId = "window-1",
                context =
                    CompanionTaskContext(
                        windowId = "window-1",
                        tabId = "terminal-7",
                        resultAvailable = true,
                    ),
            ),
        )

        val task = store.getTask("task-1")

        assertNotNull(task)
        assertEquals("window-1", task.context?.windowId)
        assertEquals("terminal-7", task.context?.tabId)
        assertEquals(true, task.context?.resultAvailable)
    }
}
