package ai.rever.boss.kernel.services

import ai.rever.boss.components.plugin.panels.left_top.ProjectState
import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.ProjectData
import ai.rever.boss.plugin.api.ProjectDataProvider
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Kernel-side bridge for `ProjectDataService`.
 *
 * **Every call requires a verified caller identity (BossConsole#53)**, the same requirement and
 * helper shape introduced for the Secret Service in PR #505 and since applied to ActiveTabs,
 * Download, Git, Log, PluginUI, RoleManagement and Supabase. Before this bridge checked
 * identity at all, any process able to open a connection to the kernel IPC server - not only the
 * plugins the host itself loaded - could [watchRecentProjects] to see every recently opened
 * project's path (which routinely contains a username, per this repo's own AGENTS.md), and could
 * [selectProject] to force an arbitrary window to switch its open project. That is not merely a
 * UI surprise: a project switch publishes a [ai.rever.boss.plugin.api.ProjectChangeEvent] on the
 * application-wide event bus every installed plugin can subscribe to (see AGENTS.md's "Browser
 * telemetry" section), so an unauthenticated caller could trigger that broadcast on demand and
 * hand every plugin a filesystem path it never asked to see.
 *
 * BossConsole#53 tracks the remaining unguarded services on this server.
 */
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
     * Revocation is checked before each emission; idle streams are not proactively disconnected,
     * and snapshots already delivered to a caller cannot be recalled.
     */
    override fun watchRecentProjects(request: Empty): Flow<ProjectListResponse> {
        // Capture the gRPC context before returning the asynchronously collected flow - the same
        // shape ActiveTabsServiceBridge.watchActiveTabs uses, and for the same reason: the
        // interceptor's Context is not carried into the coroutine that later collects this flow.
        val currentIdentity = ProcessIdentityInterceptor.CURRENT_IDENTITY.get()
        return flow {
            val caller = currentIdentity?.invoke() ?: refuseIdentity("watchRecentProjects")
            ProjectState.recentProjects.collect { projects ->
                if (currentIdentity.invoke() != caller) {
                    refuseIdentity("watchRecentProjects", "revocation")
                }
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
    }

    override suspend fun updateRecentProjects(request: ProjectProto): Empty {
        val caller = authenticatedCallerOrRefuse("updateRecentProjects")
        logMutation("updateRecentProjects", caller)
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
        val caller = authenticatedCallerOrRefuse("removeRecentProject")
        logMutation("removeRecentProject", caller)
        provider.removeRecentProject(request.path)
        return Empty.getDefaultInstance()
    }

    override suspend fun selectProject(request: ProjectProto): Empty {
        val caller = authenticatedCallerOrRefuse("selectProject")
        logMutation("selectProject", caller)
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

    /**
     * Unary RPCs only: the verified identity, or a thrown `PERMISSION_DENIED` when there is none.
     *
     * Mirrors the helper introduced by PR #505 (BossConsole#53) - fails closed rather than let a
     * request with no credential fall through to [provider] with nothing to attribute it to.
     */
    private fun authenticatedCallerOrRefuse(rpc: String): String =
        ProcessIdentityInterceptor.AUTHENTICATED_PROCESS_ID.get() ?: refuseIdentity(rpc)

    private fun logMutation(
        rpc: String,
        caller: String,
    ) {
        logger.info(
            LogCategory.AUTH,
            "Authenticated project mutation requested",
            mapOf("rpc" to rpc, "caller" to caller),
        )
    }

    private fun refuseIdentity(
        rpc: String,
        stage: String = "bind",
    ): Nothing {
        logger.warn(
            LogCategory.AUTH,
            "Refused $rpc: no current verified process identity on this call",
            mapOf("rpc" to rpc, "stage" to stage),
        )
        throw StatusException(Status.PERMISSION_DENIED.withDescription(NO_IDENTITY))
    }

    private companion object {
        val logger = BossLogger.forComponent("ProjectDataServiceBridge")

        const val NO_IDENTITY = "This call presented no verified process identity"
    }
}
