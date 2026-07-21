package ai.rever.boss.plugin.ipc

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.ProjectData
import ai.rever.boss.plugin.api.ProjectDataProvider
import io.grpc.ManagedChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * IPC proxy implementation of ProjectDataProvider.
 */
class ProjectDataProviderProxy(
    channel: ManagedChannel,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : ProjectDataProvider {

    private val stub = ProjectDataServiceGrpcKt.ProjectDataServiceCoroutineStub(channel)

    private val _recentProjects = MutableStateFlow<List<ProjectData>>(emptyList())
    override val recentProjects: StateFlow<List<ProjectData>> = _recentProjects.asStateFlow()

    init {
        scope.launch { watchRecentProjects() }
    }

    private suspend fun watchRecentProjects() {
        var delayMs = 1_000L
        while (scope.isActive) {
            try {
                stub.watchRecentProjects(Empty.getDefaultInstance()).collect { response ->
                    _recentProjects.value = response.projectsList.map { it.toData() }
                }
                delayMs = 1_000L
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { delay(delayMs); delayMs = (delayMs * 2).coerceAtMost(30_000L) }
        }
    }

    override fun updateRecentProjects(project: ProjectData) {
        scope.launch {
            try {
                stub.updateRecentProjects(project.toProto())
            } catch (_: Exception) {}
        }
    }

    override fun removeRecentProject(projectPath: String) {
        scope.launch {
            try {
                stub.removeRecentProject(ProjectPathRequest.newBuilder().setPath(projectPath).build())
            } catch (_: Exception) {}
        }
    }

    override fun selectProject(project: ProjectData) {
        scope.launch {
            try {
                stub.selectProject(project.toProto())
            } catch (_: Exception) {}
        }
    }

    private fun ProjectProto.toData() = ProjectData(
        name = name,
        path = path,
        lastOpened = lastOpened,
    )

    private fun ProjectData.toProto(): ProjectProto =
        ProjectProto.newBuilder()
            .setName(name)
            .setPath(path)
            .setLastOpened(lastOpened)
            .build()
}
