package ai.rever.boss.service.settings

import ai.rever.boss.ipc.proto.services.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.ConcurrentHashMap

/**
 * gRPC implementation of SettingsService with file-based persistence.
 *
 * All settings are stored in ~/.boss/settings.json as a flat JSON list.
 * In-memory map is the runtime source of truth; disk is loaded once at
 * startup and written synchronously on every mutation.
 */
class SettingsServiceImpl(
    storageFile: File = defaultStorageFile(),
) : SettingsServiceGrpcKt.SettingsServiceCoroutineImplBase() {
    private val logger = LoggerFactory.getLogger(SettingsServiceImpl::class.java)

    companion object {
        private fun defaultStorageFile(): File = File(System.getProperty("user.home"), ".boss/settings.json")
    }

    @Serializable
    private data class PersistedSetting(
        val key: String,
        val value: String,
        val namespace: String,
        val updatedAt: Long,
    )

    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }
    private val settingsFile =
        storageFile.absoluteFile
            .also { it.parentFile?.mkdirs() }

    internal val mutations = Mutex()
    private val settings = ConcurrentHashMap<String, SettingValue>()
    private val changes = MutableSharedFlow<SettingValue>(extraBufferCapacity = 64)

    init {
        loadFromDisk()
    }

    /** Composite storage key: "namespace/key" or just "key" for global namespace. */
    private fun storageKey(
        namespace: String,
        key: String,
    ): String = if (namespace.isBlank()) key else "$namespace/$key"

    // ---- Disk persistence helpers ----

    private fun loadFromDisk() {
        if (!settingsFile.exists() || !settingsFile.isFile) return
        try {
            val list = json.decodeFromString<List<PersistedSetting>>(settingsFile.readText())
            list.forEach { ps ->
                settings[storageKey(ps.namespace, ps.key)] =
                    SettingValue
                        .newBuilder()
                        .setKey(ps.key)
                        .setValue(ps.value)
                        .setNamespace(ps.namespace)
                        .setUpdatedAt(ps.updatedAt)
                        .setFound(true)
                        .build()
            }
            logger.info("Loaded {} setting(s) from disk", settings.size)
        } catch (e: Exception) {
            logger.warn("Failed to load settings from disk: {}", e.message)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun saveToDisk() {
        try {
            val list =
                settings.values.map { sv ->
                    PersistedSetting(
                        key = sv.key,
                        value = sv.value,
                        namespace = sv.namespace,
                        updatedAt = sv.updatedAt,
                    )
                }
            val parent = settingsFile.parentFile ?: return
            if (!parent.exists()) {
                parent.mkdirs()
            }
            val encoded = json.encodeToString(list)
            val tmp = Files.createTempFile(parent.toPath(), "${settingsFile.name}.", ".tmp").toFile()
            try {
                applyPosixOwnerPermissions(tmp.toPath())
                tmp.writeText(encoded)
                atomicMoveFile(tmp.toPath(), settingsFile.toPath())
            } finally {
                tmp.delete()
            }
        } catch (e: Exception) {
            logger.warn("Failed to persist settings: {}", e.message)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun applyPosixOwnerPermissions(path: Path) {
        if (!hasPosix(path)) return
        // Fail closed when a filesystem advertises POSIX support but refuses the restriction;
        // publishing the temporary file would violate the owner-only persistence contract.
        Files.setPosixFilePermissions(
            path,
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        )
    }

    private fun atomicMoveFile(
        source: Path,
        target: Path,
    ) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source,
                target,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun hasPosix(path: Path): Boolean = path.fileSystem.supportedFileAttributeViews().contains("posix")

    // ---- gRPC method implementations ----

    override suspend fun getSetting(request: GetSettingRequest): SettingValue {
        val stored = settings[storageKey(request.namespace, request.key)]
        return stored ?: SettingValue
            .newBuilder()
            .setKey(request.key)
            .setNamespace(request.namespace)
            .setValue(request.defaultValue)
            .setFound(false)
            .build()
    }

    override suspend fun setSetting(request: SetSettingRequest): SettingValue =
        withContext(Dispatchers.IO) {
            logger.debug("setSetting: namespace={}, key={}", request.namespace, request.key)
            val value =
                SettingValue
                    .newBuilder()
                    .setKey(request.key)
                    .setValue(request.value)
                    .setFound(true)
                    .setNamespace(request.namespace)
                    .setUpdatedAt(System.currentTimeMillis())
                    .build()
            mutations.withLock {
                settings[storageKey(request.namespace, request.key)] = value
                saveToDisk()
            }
            changes.tryEmit(value)
            value
        }

    override fun watchSetting(request: GetSettingRequest): Flow<SettingValue> =
        flow {
            // Emit current value first
            settings[storageKey(request.namespace, request.key)]?.let { emit(it) }
            // Stream subsequent changes matching this key and namespace
            changes
                .filter { it.key == request.key && it.namespace == request.namespace }
                .collect { emit(it) }
        }

    override suspend fun listSettings(request: ListSettingsRequest): SettingsListResponse {
        val prefix = request.namespacePrefix
        val all =
            if (prefix.isBlank()) {
                settings.values.toList()
            } else {
                settings.values.filter { it.namespace.startsWith(prefix) }
            }
        val total = all.size
        val limit = if (request.limit > 0) request.limit else Int.MAX_VALUE
        val offset = if (request.offset > 0) request.offset else 0
        val page = all.drop(offset).take(limit)
        return SettingsListResponse
            .newBuilder()
            .addAllSettings(page)
            .setTotalCount(total)
            .build()
    }
}
