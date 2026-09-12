package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.PluginStorageFactory
import ai.rever.boss.plugin.api.PluginStorageProvider
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicMoveFrom
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * Desktop implementation of PluginStorageFactory factory.
 */
actual fun createPluginStorageFactory(): PluginStorageFactory = PluginStorageFactoryImpl.getInstance()

/**
 * Desktop implementation of PluginStorageFactory.
 * Creates plugin-scoped storage providers that persist data to disk.
 */
class PluginStorageFactoryImpl private constructor() : PluginStorageFactory {
    companion object {
        private val logger = BossLogger.forComponent("PluginStorageFactory")

        @Volatile
        private var instance: PluginStorageFactoryImpl? = null

        fun getInstance(): PluginStorageFactoryImpl =
            instance ?: synchronized(this) {
                instance ?: PluginStorageFactoryImpl().also { instance = it }
            }
    }

    // Cache of storage providers per plugin
    private val storageCache = ConcurrentHashMap<String, PluginStorageProviderImpl>()

    override fun createStorage(pluginId: String): PluginStorageProvider =
        storageCache.getOrPut(pluginId) {
            PluginStorageProviderImpl(pluginId)
        }
}

/**
 * Desktop implementation of PluginStorageProvider.
 * Stores data in ~/.boss/plugin-data/{pluginId}/storage.properties
 *
 * The factory shares one provider per plugin across windows. Mutations serialize the entire
 * read-modify-persist-publish transaction; readers see only committed, immutable snapshots.
 * Persistence errors propagate to the caller without publishing a value or change event.
 */
class PluginStorageProviderImpl internal constructor(
    private val pluginId: String,
    private val storageFile: File,
    private val writeProperties: (File, Properties) -> Unit = ::writePluginProperties,
) : PluginStorageProvider {
    companion object {
        private val logger = BossLogger.forComponent("PluginStorage")
    }

    constructor(pluginId: String) : this(
        pluginId,
        File(BossDirectories.resolve("plugin-data/$pluginId"), "storage.properties"),
    )

    @Volatile
    private var cache: Map<String, String> = emptyMap()
    private val mutationMutex = Mutex()

    // Change notification
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 64)

    init {
        // Load existing data on initialization
        loadFromDisk()
    }

    override fun getPluginId(): String = pluginId

    // ============ String Storage ============

    override suspend fun putString(
        key: String,
        value: String,
    ) {
        mutate(key) { it + (key to value) }
    }

    override suspend fun getString(
        key: String,
        defaultValue: String?,
    ): String? = cache[key] ?: defaultValue

    // ============ Int Storage ============

    override suspend fun putInt(
        key: String,
        value: Int,
    ) {
        putString(key, value.toString())
    }

    override suspend fun getInt(
        key: String,
        defaultValue: Int,
    ): Int = getString(key)?.toIntOrNull() ?: defaultValue

    // ============ Long Storage ============

    override suspend fun putLong(
        key: String,
        value: Long,
    ) {
        putString(key, value.toString())
    }

    override suspend fun getLong(
        key: String,
        defaultValue: Long,
    ): Long = getString(key)?.toLongOrNull() ?: defaultValue

    // ============ Boolean Storage ============

    override suspend fun putBoolean(
        key: String,
        value: Boolean,
    ) {
        putString(key, value.toString())
    }

    override suspend fun getBoolean(
        key: String,
        defaultValue: Boolean,
    ): Boolean = getString(key)?.toBooleanStrictOrNull() ?: defaultValue

    // ============ Float Storage ============

    override suspend fun putFloat(
        key: String,
        value: Float,
    ) {
        putString(key, value.toString())
    }

    override suspend fun getFloat(
        key: String,
        defaultValue: Float,
    ): Float = getString(key)?.toFloatOrNull() ?: defaultValue

    // ============ JSON Storage ============

    override suspend fun putJson(
        key: String,
        jsonValue: String,
    ) {
        putString("json:$key", jsonValue)
    }

    override suspend fun getJson(key: String): String? = getString("json:$key")

    // ============ Utility Methods ============

    override suspend fun contains(key: String): Boolean = cache.containsKey(key)

    override suspend fun remove(key: String) {
        mutate(key) { it - key }
    }

    override suspend fun getAllKeys(): Set<String> = cache.keys.toSet()

    override suspend fun clear() {
        mutate("*") { emptyMap() }
    }

    override fun observeString(key: String): Flow<String?> =
        _changes
            .asSharedFlow()
            .map { changedKey ->
                if (changedKey == key || changedKey == "*") {
                    cache[key]
                } else {
                    null
                }
            }

    override fun observeChanges(): Flow<String> = _changes.asSharedFlow()

    // ============ Disk Operations ============

    private fun loadFromDisk() {
        try {
            if (storageFile.exists()) {
                val properties = Properties()
                storageFile.inputStream().use { properties.load(it) }
                cache = properties.entries.associate { (key, value) -> key.toString() to value.toString() }
                logger.debug(
                    LogCategory.SYSTEM,
                    "Loaded plugin storage",
                    mapOf(
                        "pluginId" to pluginId,
                        "keyCount" to cache.size,
                    ),
                )
            }
        } catch (e: Exception) {
            logger.error(
                LogCategory.SYSTEM,
                "Failed to load plugin storage",
                mapOf(
                    "pluginId" to pluginId,
                ),
                e,
            )
        }
    }

    private suspend fun mutate(
        changedKey: String,
        transform: (Map<String, String>) -> Map<String, String>,
    ) {
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                currentCoroutineContext().ensureActive()
                // Waiting callers remain cancellable. Once admitted, a blocking file commit and
                // cache publication must finish together even if the caller cancels during I/O.
                withContext(NonCancellable) {
                    val updated = transform(cache)
                    val properties = Properties().apply { putAll(updated) }
                    writeProperties(storageFile, properties)
                    cache = updated
                    _changes.tryEmit(changedKey)
                }
            }
        }
    }
}

internal fun writePluginProperties(
    file: File,
    properties: Properties,
) {
    val target = file.absoluteFile
    target.parentFile.mkdirs()
    val temporary = Files.createTempFile(target.parentFile.toPath(), "${target.name}.", ".tmp").toFile()
    try {
        // Keep OutputStream encoding: Properties.load(InputStream) expects Latin-1 with escaped
        // Unicode, not the unescaped Unicode emitted by Properties.store(Writer).
        temporary.outputStream().use { properties.store(it, "Plugin storage") }
        target.atomicMoveFrom(temporary)
    } finally {
        temporary.delete()
    }
}
