package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.ProjectData
import ai.rever.boss.plugin.api.ProjectDataProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class ProjectDataServiceBridge(
    private val provider: ProjectDataProvider,
) : ProjectDataServiceGrpcKt.ProjectDataServiceCoroutineImplBase() {
    override fun watchRecentProjects(request: Empty): Flow<ProjectListResponse> =
        flow {
            provider.recentProjects.collect { projects ->
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
        provider.selectProject(
            ProjectData(
                name = request.name,
                path = request.path,
                lastOpened = request.lastOpened,
            ),
        )
        return Empty.getDefaultInstance()
    }
}
