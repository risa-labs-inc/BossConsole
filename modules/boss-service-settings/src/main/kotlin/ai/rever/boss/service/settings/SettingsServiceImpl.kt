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
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap

/**
 * gRPC implementation of SettingsService with file-based persistence.
 *
 * All settings are stored in ~/.boss/settings.json as a flat JSON list.
 * In-memory map is the runtime source of truth; disk is loaded once at
 * startup and written synchronously on every mutation.
 */
class SettingsServiceImpl(
    private val storageFile: File = File(System.getProperty("user.home"), ".boss/settings.json"),
) : SettingsServiceGrpcKt.SettingsServiceCoroutineImplBase() {
    private val logger = LoggerFactory.getLogger(SettingsServiceImpl::class.java)

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

    private val settingsFile: File =
        storageFile.also {
            it.parentFile?.mkdirs()
        }

    private val settings = ConcurrentHashMap<String, SettingValue>()
    private val changes = MutableSharedFlow<SettingValue>(extraBufferCapacity = 64)
    internal val mutations = Mutex()

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
        if (!settingsFile.exists()) return
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
            if (!parent.exists()) parent.mkdirs()

            val tempFile = File.createTempFile(".${settingsFile.name}", ".tmp", parent)
            try {
                setOwnerOnlyPermissions(tempFile)
                tempFile.writeText(json.encodeToString(list))
                Files.move(
                    tempFile.toPath(),
                    settingsFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e: Exception) {
                tempFile.delete()
                throw e
            }
        } catch (e: Exception) {
            logger.warn("Failed to persist settings: {}", e.message)
        }
    }

    private fun setOwnerOnlyPermissions(file: File) {
        try {
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
            } else {
                file.setReadable(false, false)
                file.setReadable(true, true)
                file.setWritable(false, false)
                file.setWritable(true, true)
                file.setExecutable(false, false)
            }
        } catch (_: Exception) {
            // Best effort on non-POSIX platforms
        }
    }

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
            mutations.withLock {
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
                settings[storageKey(request.namespace, request.key)] = value
                saveToDisk()
                changes.tryEmit(value)
                value
            }
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
