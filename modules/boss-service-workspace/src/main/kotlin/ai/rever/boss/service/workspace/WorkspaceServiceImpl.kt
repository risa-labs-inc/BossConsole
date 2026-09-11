package ai.rever.boss.service.workspace

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import io.grpc.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap

/**
 * gRPC implementation of WorkspaceService with file-based persistence.
 *
 * Workspaces are stored in ~/.boss/workspaces/{workspaceId}.json.
 * The in-memory map is the runtime source of truth; disk is written on
 * every mutation and read once at startup.
 */
class WorkspaceServiceImpl(
    storageDirectory: File = File(System.getProperty("user.home"), ".boss/workspaces"),
) : WorkspaceServiceGrpcKt.WorkspaceServiceCoroutineImplBase() {
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

    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }
    private val storageRoot = Files.createDirectories(storageDirectory.toPath()).toRealPath()
    private val storageIdentity = directoryIdentity()

    private fun directoryIdentity(): Any? =
        Files
            .readAttributes(storageRoot, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            .fileKey()

    private val mutations = Mutex()
    private val workspaces = ConcurrentHashMap<String, WorkspaceInfo>()
    private val currentWorkspaceFlow = MutableStateFlow<WorkspaceInfo?>(null)

    init {
        loadFromDisk()
    }

    // ---- Disk persistence helpers ----

    private fun validateId(id: String) {
        // A portable filename component, including existing workspace-<millis> IDs.
        // Do not normalize rejected IDs into aliases of other workspaces.
        val reserved = id.substringBefore('.').matches(Regex("(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])"))
        if (!id.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9._-]{0,199}")) || id.endsWith('.') || reserved) {
            throw Status.INVALID_ARGUMENT.withDescription("Invalid workspace ID").asRuntimeException()
        }
    }

    private fun workspacePath(id: String): Path {
        validateId(id)
        check(Files.isDirectory(storageRoot, NOFOLLOW_LINKS) && storageRoot.toRealPath() == storageRoot) {
            "Workspace storage directory changed"
        }
        check(directoryIdentity() == storageIdentity) {
            "Workspace storage directory was replaced"
        }
        val target = storageRoot.resolve("$id.json")
        check(!Files.isSymbolicLink(target)) { "Workspace file must not be a symbolic link" }
        if (Files.exists(target, NOFOLLOW_LINKS)) {
            val alias =
                workspaces.keys.any { storedId ->
                    val storedPath = storageRoot.resolve("$storedId.json")
                    val exists = Files.exists(storedPath, NOFOLLOW_LINKS)
                    storedId != id && exists && Files.isSameFile(storedPath, target)
                }
            if (alias) {
                throw Status.ALREADY_EXISTS.withDescription("Workspace ID already exists").asRuntimeException()
            }
        }
        return target
    }

    private fun loadFromDisk() {
        storageRoot
            .toFile()
            .listFiles { f ->
                Files.isRegularFile(f.toPath(), NOFOLLOW_LINKS) && f.extension == "json"
            }?.forEach { file ->
                try {
                    val text =
                        Files.newByteChannel(file.toPath(), setOf(READ, NOFOLLOW_LINKS)).use { channel ->
                            java.nio.channels.Channels
                                .newInputStream(channel)
                                .bufferedReader()
                                .readText()
                        }
                    val pw = json.decodeFromString<PersistedWorkspace>(text)
                    workspacePath(pw.id)
                    require(file.name == "${pw.id}.json") { "Workspace filename does not match its ID" }
                    workspaces[pw.id] = pw.toProto()
                    logger.debug("Loaded workspace from disk: id={}", pw.id)
                } catch (e: Exception) {
                    logger.warn("Failed to load workspace file {}: {}", file.name, e.message)
                }
            }
        logger.info("Loaded {} workspace(s) from disk", workspaces.size)
    }

    private fun saveToDisk(ws: WorkspaceInfo) {
        val target = workspacePath(ws.id)
        val temporary = Files.createTempFile(storageRoot, ".workspace-", ".tmp")
        try {
            val pw =
                PersistedWorkspace(
                    id = ws.id,
                    name = ws.name,
                    projectPath = ws.projectPath,
                    description = ws.description,
                    createdAt = ws.createdAt,
                    lastOpenedAt = ws.lastOpenedAt,
                    tabCount = ws.tabCount,
                    metadata = ws.metadataMap,
                )
            Files.writeString(temporary, json.encodeToString(pw))
            // Replacement never follows an existing file link or truncates an old record.
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun deleteFromDisk(workspaceId: String) {
        Files.deleteIfExists(workspacePath(workspaceId))
    }

    private fun PersistedWorkspace.toProto(): WorkspaceInfo =
        WorkspaceInfo
            .newBuilder()
            .setId(id)
            .setName(name)
            .setProjectPath(projectPath)
            .setDescription(description)
            .setCreatedAt(createdAt)
            .setLastOpenedAt(lastOpenedAt)
            .setTabCount(tabCount)
            .putAllMetadata(metadata)
            .build()

    private suspend fun <T> mutate(action: () -> T): T = withContext(Dispatchers.IO) { mutations.withLock { action() } }

    // ---- gRPC method implementations ----

    override suspend fun getWorkspaces(request: Empty): WorkspacesResponse =
        WorkspacesResponse
            .newBuilder()
            .addAllWorkspaces(workspaces.values.toList())
            .build()

    override fun watchWorkspaces(request: Empty): Flow<WorkspacesResponse> =
        flow {
            // Emit current snapshot first, then re-emit on every currentWorkspace change
            // (all mutations also touch currentWorkspaceFlow so watchers stay in sync)
            emit(WorkspacesResponse.newBuilder().addAllWorkspaces(workspaces.values.toList()).build())
            currentWorkspaceFlow.collect {
                emit(WorkspacesResponse.newBuilder().addAllWorkspaces(workspaces.values.toList()).build())
            }
        }

    override suspend fun getCurrentWorkspace(request: Empty): WorkspaceResponse {
        val ws = currentWorkspaceFlow.value
        return WorkspaceResponse
            .newBuilder()
            .setFound(ws != null)
            .apply { ws?.let { setWorkspace(it) } }
            .build()
    }

    override fun watchCurrentWorkspace(request: Empty): Flow<WorkspaceResponse> =
        flow {
            currentWorkspaceFlow.collect { ws ->
                emit(
                    WorkspaceResponse
                        .newBuilder()
                        .setFound(ws != null)
                        .apply { ws?.let { setWorkspace(it) } }
                        .build(),
                )
            }
        }

    override suspend fun loadWorkspace(request: LoadWorkspaceRequest): WorkspaceResponse =
        mutate {
            if (request.workspaceId.isNotEmpty()) validateId(request.workspaceId)
            logger.info("loadWorkspace: id={}, path={}", request.workspaceId, request.projectPath)

            val ws =
                when {
                    request.workspaceId.isNotBlank() -> {
                        workspaces[request.workspaceId]
                    }

                    request.projectPath.isNotBlank() -> {
                        workspaces.values.firstOrNull { it.projectPath == request.projectPath }
                    }

                    else -> {
                        null
                    }
                }

            if (ws != null) {
                val now = System.currentTimeMillis()
                val updated = ws.toBuilder().setLastOpenedAt(now).build()
                saveToDisk(updated)
                workspaces[ws.id] = updated
                currentWorkspaceFlow.value = updated
                return@mutate WorkspaceResponse
                    .newBuilder()
                    .setFound(true)
                    .setWorkspace(updated)
                    .build()
            }

            if (request.projectPath.isNotBlank()) {
                // Auto-create workspace for unknown path
                val now = System.currentTimeMillis()
                val newWs =
                    WorkspaceInfo
                        .newBuilder()
                        .setId("workspace-$now")
                        .setName(File(request.projectPath).name)
                        .setProjectPath(request.projectPath)
                        .setCreatedAt(now)
                        .setLastOpenedAt(now)
                        .build()
                saveToDisk(newWs)
                workspaces[newWs.id] = newWs
                currentWorkspaceFlow.value = newWs
                return@mutate WorkspaceResponse
                    .newBuilder()
                    .setFound(true)
                    .setWorkspace(newWs)
                    .build()
            }

            WorkspaceResponse
                .newBuilder()
                .setFound(false)
                .setErrorMessage("Workspace not found: ${request.workspaceId}")
                .build()
        }

    override suspend fun saveWorkspace(request: SaveWorkspaceRequest): WorkspaceResponse =
        mutate {
            validateId(request.workspaceId)
            logger.info("saveWorkspace: id={}", request.workspaceId)
            val existing = workspaces[request.workspaceId]
            val now = System.currentTimeMillis()
            val ws =
                WorkspaceInfo
                    .newBuilder()
                    .setId(request.workspaceId)
                    .setName(request.name)
                    .setProjectPath(request.projectPath)
                    .setCreatedAt(existing?.createdAt ?: now)
                    .setLastOpenedAt(now)
                    .putAllMetadata(request.metadataMap)
                    .build()
            saveToDisk(ws)
            workspaces[ws.id] = ws
            // Propagate to watchers via currentWorkspaceFlow nudge
            if (currentWorkspaceFlow.value?.id == ws.id) {
                currentWorkspaceFlow.value = ws
            } else {
                val prev = currentWorkspaceFlow.value
                currentWorkspaceFlow.value = prev // triggers distinct-until-changed pass-through
            }
            WorkspaceResponse
                .newBuilder()
                .setFound(true)
                .setWorkspace(ws)
                .build()
        }

    override suspend fun deleteWorkspace(request: DeleteWorkspaceRequest): Empty =
        mutate {
            validateId(request.workspaceId)
            logger.info("deleteWorkspace: id={}", request.workspaceId)
            deleteFromDisk(request.workspaceId)
            workspaces.remove(request.workspaceId)
            if (currentWorkspaceFlow.value?.id == request.workspaceId) {
                currentWorkspaceFlow.value = null
            }
            Empty.getDefaultInstance()
        }
}
