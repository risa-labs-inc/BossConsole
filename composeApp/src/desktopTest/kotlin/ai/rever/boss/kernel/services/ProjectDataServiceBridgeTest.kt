package ai.rever.boss.kernel.services

import ai.rever.boss.components.plugin.panels.left_top.ProjectState
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.ProjectProto
import ai.rever.boss.plugin.api.ProjectData
import ai.rever.boss.plugin.api.ProjectDataProvider
import ai.rever.boss.window.Project
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Two routing guarantees on the kernel bridge:
 *
 * 1. `selectProject` confines provider mutation to the UI dispatcher, which lets
 *    project-change announcement stay lock-free.
 *
 * 2. BossConsole#520: `watchRecentProjects` must read [ProjectState] - the process-wide
 *    singleton - rather than a per-window [ProjectDataProvider]'s own mirror, because that
 *    mirror stops updating the moment its owning window disposes it
 *    (`ProjectDataProviderImpl.dispose`). Reading it instead would freeze a KERNEL client at
 *    whatever the window last saw.
 */
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

    @Test
    fun `watchRecentProjects reflects the process-wide ProjectState, not the per-window provider`() =
        runBlocking {
            // Seeded, not read-only: under composeApp's test-home isolation (user.home
            // redirected to a fresh build/test-home per task) the singleton's list is
            // always empty in the test JVM, so asserting against whatever it happened to
            // hold was a no-op - an implementation that merely emitted [] passed it.
            // Seeding makes the assertion check a real value. The write is still safe:
            // persistence lands in the per-run test home, not the developer's real
            // recent-projects.json, and the finally below restores the list. The seed's
            // path is a real directory because ProjectState's one-shot disk load, if it
            // still runs when the test writes the file, filters out projects whose
            // directories do not exist.
            val seedDir = Files.createTempDirectory("boss-bridge-watch-seed").toFile()
            val secondDir = Files.createTempDirectory("boss-bridge-watch-second").toFile()
            val seed = Project(name = "boss-bridge-seed", path = seedDir.absolutePath, lastOpened = 0L)
            val second = Project(name = "boss-bridge-second", path = secondDir.absolutePath, lastOpened = 0L)
            try {
                val decoy = ProjectData(name = "decoy-window-only-project", path = "/nowhere/decoy", lastOpened = 0L)
                val bridge = ProjectDataServiceBridge(FakeProjectDataProvider(MutableStateFlow(listOf(decoy))))

                awaitInRecentProjects(seed)
                val expected = ProjectState.recentProjects.value.map { it.path }
                assertTrue(seed.path in expected, "seed disappeared before the first emission")

                // One collection, two emissions: the first pins the routing (the value
                // equals the process-wide list, not the per-window decoy), the second
                // pins liveness (a later mutation still reaches a client that keeps
                // collecting).
                val emissions = mutableListOf<List<String>>()
                val collector =
                    launch {
                        bridge
                            .watchRecentProjects(Empty.getDefaultInstance())
                            .take(2)
                            .collect { emissions += it.projectsList.map { p -> p.path } }
                    }
                withTimeout(5_000) {
                    while (emissions.size < 1) delay(10)
                }
                assertEquals(expected, emissions.getOrNull(0), "first emission must be the process-wide list")
                assertNotEquals(listOf(decoy.path), emissions.getOrNull(0), "emission came from the per-window mirror")

                ProjectState.updateRecentProjects(second)
                withTimeout(5_000) {
                    while (emissions.size < 2) delay(10)
                }
                collector.join()
                assertTrue(
                    second.path in emissions.getOrNull(1).orEmpty(),
                    "second emission $emissions did not carry the post-mutation value; the stream is not live",
                )
            } finally {
                ProjectState.removeRecentProject(seed.path)
                ProjectState.removeRecentProject(second.path)
                seedDir.deleteRecursively()
                secondDir.deleteRecursively()
            }
        }

    /**
     * Seeds [project] into the real [ProjectState] and returns once it is present. Reseeds on
     * every poll, because the singleton's one-shot disk load can run once, late, and replace
     * the whole list with the file's contents; the load happens at most once, so this loop
     * terminates.
     */
    private suspend fun awaitInRecentProjects(project: Project) {
        withTimeout(5_000) {
            while (project.path !in ProjectState.recentProjects.value.map { it.path }) {
                ProjectState.updateRecentProjects(project)
                delay(10)
            }
        }
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

/** A provider whose [recentProjects] is fixed and deliberately unlike [ProjectState]'s value. */
private class FakeProjectDataProvider(
    override val recentProjects: StateFlow<List<ProjectData>>,
) : ProjectDataProvider {
    override fun updateRecentProjects(project: ProjectData) = error("not used by this test")

    override fun removeRecentProject(projectPath: String) = error("not used by this test")

    override fun selectProject(project: ProjectData) = error("not used by this test")
}
