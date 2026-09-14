package ai.rever.boss.kernel.services

import ai.rever.boss.components.plugin.panels.left_top.ProjectState
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.ProjectData
import ai.rever.boss.plugin.api.ProjectDataProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

class ProjectDataServiceBridge(
    private val provider: ProjectDataProvider,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : ProjectDataServiceGrpcKt.ProjectDataServiceCoroutineImplBase() {
    /**
     * Deliberately reads [ProjectState.recentProjects] - the process-wide singleton - rather than
     * [provider]'s own [ProjectDataProvider.recentProjects]. The latter is a per-window mirror
     * (`ProjectDataProviderImpl`) that stops updating once its owning window disposes it
     * (BossConsole#520); a KERNEL client watching it would freeze at whatever it last saw. This
     * stream has no such owner to outlive, so it keeps working across every window's lifecycle.
     */
    override fun watchRecentProjects(request: Empty): Flow<ProjectListResponse> =
        flow {
            ProjectState.recentProjects.collect { projects ->
                emit(
                    ProjectListResponse
                        .newBuilder()
                        .addAllProjects(
                            projects.map { project ->
                                ProjectProto
                                    .newBuilder()
                                    .setName(project.name)
                                    .setPath(project.path)
                                    .setLastOpened(project.lastOpened)
                                    .build()
                            },
                        ).build(),
                )
            }
        }

    override suspend fun updateRecentProjects(request: ProjectProto): Empty {
        provider.updateRecentProjects(
            ProjectData(
                name = request.name,
                path = request.path,
                lastOpened = request.lastOpened,
            ),
        )
        return Empty.getDefaultInstance()
    }

    override suspend fun removeRecentProject(request: ProjectPathRequest): Empty {
        provider.removeRecentProject(request.path)
        return Empty.getDefaultInstance()
    }

    override suspend fun selectProject(request: ProjectProto): Empty {
        // The provider mutates per-window UI state and synchronously announces that change.
        // Confining the only non-UI entry point here keeps the state and its previous-path event
        // chain ordered without making third-party event handlers run under a host lock.
        withContext(uiDispatcher) {
            provider.selectProject(
                ProjectData(
                    name = request.name,
                    path = request.path,
                    lastOpened = request.lastOpened,
                ),
            )
        }
        return Empty.getDefaultInstance()
    }
}
