package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.ProjectProto
import ai.rever.boss.plugin.api.ProjectData
import ai.rever.boss.plugin.api.ProjectDataProvider
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins the UI confinement that lets project-change announcement stay lock-free. */
class ProjectDataServiceBridgeTest {
    @Test
    fun `selectProject switches from the grpc caller to the UI dispatcher`() {
        val provider = RecordingProjectDataProvider()
        val executor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, UI_THREAD_NAME)
            }

        executor.asCoroutineDispatcher().use { uiDispatcher ->
            val bridge = ProjectDataServiceBridge(provider, uiDispatcher)
            val response =
                runBlocking {
                    bridge.selectProject(
                        ProjectProto
                            .newBuilder()
                            .setName("Selected")
                            .setPath("/tmp/boss-bridge-selected")
                            .setLastOpened(42L)
                            .build(),
                    )
                }

            assertEquals(Empty.getDefaultInstance(), response)
        }

        assertTrue(
            provider.selectionThread?.startsWith(UI_THREAD_NAME) == true,
            "selection ran on ${provider.selectionThread}",
        )
        assertEquals(
            ProjectData(name = "Selected", path = "/tmp/boss-bridge-selected", lastOpened = 42L),
            provider.selectedProject,
        )
    }

    private class RecordingProjectDataProvider : ProjectDataProvider {
        override val recentProjects: StateFlow<List<ProjectData>> = MutableStateFlow(emptyList())
        var selectedProject: ProjectData? = null
        var selectionThread: String? = null

        override fun updateRecentProjects(project: ProjectData) = Unit

        override fun removeRecentProject(projectPath: String) = Unit

        override fun selectProject(project: ProjectData) {
            selectedProject = project
            selectionThread = Thread.currentThread().name
        }
    }

    private companion object {
        const val UI_THREAD_NAME = "project-data-ui-test"
    }
}
