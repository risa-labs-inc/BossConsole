package ai.rever.boss.plugin.ipc

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.WorkspaceServiceGrpcKt
import ai.rever.boss.plugin.api.WorkspaceDataProvider
import ai.rever.boss.plugin.workspace.LayoutWorkspace
import ai.rever.boss.plugin.workspace.WorkspaceSerializer
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
 * IPC proxy implementation of WorkspaceDataProvider.
 *
 * Bridges the plugin API's [WorkspaceDataProvider] interface to the
 * WorkspaceService gRPC endpoint. Layout data is round-tripped through
 * JSON stored in the proto's `metadata["layout_json"]` field so the
 * full [LayoutWorkspace] structure is preserved across process boundaries.
 *
 * Runs in the kernel process as a drop-in replacement for the in-process
 * WorkspaceDataProviderImpl when a plugin runs out-of-process.
 */
class WorkspaceDataProviderProxy(
    channel: ManagedChannel,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : WorkspaceDataProvider {

    private val stub = WorkspaceServiceGrpcKt.WorkspaceServiceCoroutineStub(channel)

    private val _workspaces = MutableStateFlow<List<LayoutWorkspace>>(emptyList())
    override val workspaces: StateFlow<List<LayoutWorkspace>> = _workspaces.asStateFlow()

    private val _currentWorkspace = MutableStateFlow<LayoutWorkspace?>(null)
    override val currentWorkspace: StateFlow<LayoutWorkspace?> = _currentWorkspace.asStateFlow()

    init {
        scope.launch { watchWorkspaces() }
        scope.launch { watchCurrentWorkspace() }
    }

    // ---- Background watchers ----

    private suspend fun watchWorkspaces() {
        var delayMs = 1_000L
        while (scope.isActive) {
            try {
                stub.watchWorkspaces(Empty.getDefaultInstance()).collect { response ->
                    _workspaces.value = response.workspacesList.mapNotNull { it.toLayoutWorkspace() }
                }
                delayMs = 1_000L
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    private suspend fun watchCurrentWorkspace() {
        var delayMs = 1_000L
        while (scope.isActive) {
            try {
                stub.watchCurrentWorkspace(Empty.getDefaultInstance()).collect { response ->
                    _currentWorkspace.value = if (response.found) response.workspace.toLayoutWorkspace() else null
                }
                delayMs = 1_000L
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    // ---- WorkspaceDataProvider implementation ----

    override fun loadWorkspace(workspace: LayoutWorkspace) {
        scope.launch {
            try {
                stub.loadWorkspace(
                    ai.rever.boss.ipc.proto.services.LoadWorkspaceRequest.newBuilder()
                        .setWorkspaceId(workspace.id)
                        .setProjectPath(workspace.projectPath ?: "")
                        .build()
                )
            } catch (_: Exception) {}
        }
    }

    override fun updateCurrentWorkspace(newWorkspace: LayoutWorkspace) {
        scope.launch {
            try {
                stub.saveWorkspace(newWorkspace.toSaveRequest())
            } catch (_: Exception) {}
        }
    }

    override fun saveCurrentWorkspace(name: String?): LayoutWorkspace? {
        val ws = _currentWorkspace.value ?: return null
        val toSave = if (name != null) ws.copy(name = name) else ws
        scope.launch {
            try {
                stub.saveWorkspace(toSave.toSaveRequest())
            } catch (_: Exception) {}
        }
        return toSave
    }

    override fun exportWorkspace(workspace: LayoutWorkspace): String =
        WorkspaceSerializer.serialize(workspace)

    override fun deleteWorkspace(name: String) {
        val target = _workspaces.value.firstOrNull { it.name == name } ?: return
        scope.launch {
            try {
                stub.deleteWorkspace(
                    ai.rever.boss.ipc.proto.services.DeleteWorkspaceRequest.newBuilder()
                        .setWorkspaceId(target.id)
                        .build()
                )
            } catch (_: Exception) {}
        }
    }

    override fun renameWorkspace(oldName: String, newName: String) {
        val target = _workspaces.value.firstOrNull { it.name == oldName } ?: return
        scope.launch {
            try {
                stub.saveWorkspace(target.copy(name = newName).toSaveRequest())
            } catch (_: Exception) {}
        }
    }

    // ---- Conversion helpers ----

    private fun ai.rever.boss.ipc.proto.services.WorkspaceInfo.toLayoutWorkspace(): LayoutWorkspace? {
        val layoutJson = metadataMap["layout_json"] ?: return null
        return try {
            WorkspaceSerializer.deserialize(layoutJson)
        } catch (_: Exception) {
            null
        }
    }

    private fun LayoutWorkspace.toSaveRequest(): ai.rever.boss.ipc.proto.services.SaveWorkspaceRequest {
        val layoutJson = WorkspaceSerializer.serialize(this)
        return ai.rever.boss.ipc.proto.services.SaveWorkspaceRequest.newBuilder()
            .setWorkspaceId(id)
            .setName(name)
            .setProjectPath(projectPath ?: "")
            .putMetadata("layout_json", layoutJson)
            .putMetadata("description", description)
            .build()
    }
}
