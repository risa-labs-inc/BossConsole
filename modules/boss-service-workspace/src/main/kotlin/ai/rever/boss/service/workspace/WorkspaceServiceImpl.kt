package ai.rever.boss.service.workspace

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * gRPC implementation of WorkspaceService with file-based persistence.
 *
 * Workspaces are stored in ~/.boss/workspaces/{workspaceId}.json.
 * The in-memory map is the runtime source of truth; disk is written on
 * every mutation and read once at startup.
 */
class WorkspaceServiceImpl : WorkspaceServiceGrpcKt.WorkspaceServiceCoroutineImplBase() {

    private val logger = LoggerFactory.getLogger(WorkspaceServiceImpl::class.java)

    @Serializable
    private data class PersistedWorkspace(
        val id: String,
        val name: String,
        val projectPath: String = "",
        val description: String = "",
        val createdAt: Long = 0L,
        val lastOpenedAt: Long = 0L,
        val tabCount: Int = 0,
        val metadata: Map<String, String> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val bossDir = File(System.getProperty("user.home"), ".boss/workspaces").also { it.mkdirs() }

    private val workspaces = ConcurrentHashMap<String, WorkspaceInfo>()
    private val currentWorkspaceFlow = MutableStateFlow<WorkspaceInfo?>(null)

    init {
        loadFromDisk()
    }

    // ---- Disk persistence helpers ----

    private fun loadFromDisk() {
        bossDir.listFiles { f -> f.isFile && f.extension == "json" }?.forEach { file ->
            try {
                val pw = json.decodeFromString<PersistedWorkspace>(file.readText())
                workspaces[pw.id] = pw.toProto()
                logger.debug("Loaded workspace from disk: id={}", pw.id)
            } catch (e: Exception) {
                logger.warn("Failed to load workspace file {}: {}", file.name, e.message)
            }
        }
        logger.info("Loaded {} workspace(s) from disk", workspaces.size)
    }

    private fun saveToDisk(ws: WorkspaceInfo) {
        try {
            val pw = PersistedWorkspace(
                id = ws.id,
                name = ws.name,
                projectPath = ws.projectPath,
                description = ws.description,
                createdAt = ws.createdAt,
                lastOpenedAt = ws.lastOpenedAt,
                tabCount = ws.tabCount,
                metadata = ws.metadataMap,
            )
            File(bossDir, "${ws.id}.json").writeText(json.encodeToString(pw))
        } catch (e: Exception) {
            logger.warn("Failed to persist workspace {}: {}", ws.id, e.message)
        }
    }

    private fun deleteFromDisk(workspaceId: String) {
        try {
            File(bossDir, "$workspaceId.json").delete()
        } catch (e: Exception) {
            logger.warn("Failed to delete workspace file {}: {}", workspaceId, e.message)
        }
    }

    private fun PersistedWorkspace.toProto(): WorkspaceInfo = WorkspaceInfo.newBuilder()
        .setId(id)
        .setName(name)
        .setProjectPath(projectPath)
        .setDescription(description)
        .setCreatedAt(createdAt)
        .setLastOpenedAt(lastOpenedAt)
        .setTabCount(tabCount)
        .putAllMetadata(metadata)
        .build()

    // ---- gRPC method implementations ----

    override suspend fun getWorkspaces(request: Empty): WorkspacesResponse {
        return WorkspacesResponse.newBuilder()
            .addAllWorkspaces(workspaces.values.toList())
            .build()
    }

    override fun watchWorkspaces(request: Empty): Flow<WorkspacesResponse> = flow {
        // Emit current snapshot first, then re-emit on every currentWorkspace change
        // (all mutations also touch currentWorkspaceFlow so watchers stay in sync)
        emit(WorkspacesResponse.newBuilder().addAllWorkspaces(workspaces.values.toList()).build())
        currentWorkspaceFlow.collect {
            emit(WorkspacesResponse.newBuilder().addAllWorkspaces(workspaces.values.toList()).build())
        }
    }

    override suspend fun getCurrentWorkspace(request: Empty): WorkspaceResponse {
        val ws = currentWorkspaceFlow.value
        return WorkspaceResponse.newBuilder()
            .setFound(ws != null)
            .apply { ws?.let { setWorkspace(it) } }
            .build()
    }

    override fun watchCurrentWorkspace(request: Empty): Flow<WorkspaceResponse> = flow {
        currentWorkspaceFlow.collect { ws ->
            emit(
                WorkspaceResponse.newBuilder()
                    .setFound(ws != null)
                    .apply { ws?.let { setWorkspace(it) } }
                    .build()
            )
        }
    }

    override suspend fun loadWorkspace(request: LoadWorkspaceRequest): WorkspaceResponse =
        withContext(Dispatchers.IO) {
            logger.info("loadWorkspace: id={}, path={}", request.workspaceId, request.projectPath)

            val ws = when {
                request.workspaceId.isNotBlank() -> workspaces[request.workspaceId]
                request.projectPath.isNotBlank() ->
                    workspaces.values.firstOrNull { it.projectPath == request.projectPath }
                else -> null
            }

            if (ws != null) {
                val now = System.currentTimeMillis()
                val updated = ws.toBuilder().setLastOpenedAt(now).build()
                workspaces[ws.id] = updated
                saveToDisk(updated)
                currentWorkspaceFlow.value = updated
                return@withContext WorkspaceResponse.newBuilder().setFound(true).setWorkspace(updated).build()
            }

            if (request.projectPath.isNotBlank()) {
                // Auto-create workspace for unknown path
                val now = System.currentTimeMillis()
                val newWs = WorkspaceInfo.newBuilder()
                    .setId("workspace-$now")
                    .setName(File(request.projectPath).name)
                    .setProjectPath(request.projectPath)
                    .setCreatedAt(now)
                    .setLastOpenedAt(now)
                    .build()
                workspaces[newWs.id] = newWs
                saveToDisk(newWs)
                currentWorkspaceFlow.value = newWs
                return@withContext WorkspaceResponse.newBuilder().setFound(true).setWorkspace(newWs).build()
            }

            WorkspaceResponse.newBuilder()
                .setFound(false)
                .setErrorMessage("Workspace not found: ${request.workspaceId}")
                .build()
        }

    override suspend fun saveWorkspace(request: SaveWorkspaceRequest): WorkspaceResponse =
        withContext(Dispatchers.IO) {
            logger.info("saveWorkspace: id={}", request.workspaceId)
            val existing = workspaces[request.workspaceId]
            val now = System.currentTimeMillis()
            val ws = WorkspaceInfo.newBuilder()
                .setId(request.workspaceId)
                .setName(request.name)
                .setProjectPath(request.projectPath)
                .setCreatedAt(existing?.createdAt ?: now)
                .setLastOpenedAt(now)
                .putAllMetadata(request.metadataMap)
                .build()
            workspaces[ws.id] = ws
            saveToDisk(ws)
            // Propagate to watchers via currentWorkspaceFlow nudge
            if (currentWorkspaceFlow.value?.id == ws.id) {
                currentWorkspaceFlow.value = ws
            } else {
                val prev = currentWorkspaceFlow.value
                currentWorkspaceFlow.value = prev // triggers distinct-until-changed pass-through
            }
            WorkspaceResponse.newBuilder().setFound(true).setWorkspace(ws).build()
        }

    override suspend fun deleteWorkspace(request: DeleteWorkspaceRequest): Empty =
        withContext(Dispatchers.IO) {
            logger.info("deleteWorkspace: id={}", request.workspaceId)
            workspaces.remove(request.workspaceId)
            deleteFromDisk(request.workspaceId)
            if (currentWorkspaceFlow.value?.id == request.workspaceId) {
                currentWorkspaceFlow.value = null
            }
            Empty.getDefaultInstance()
        }
}
