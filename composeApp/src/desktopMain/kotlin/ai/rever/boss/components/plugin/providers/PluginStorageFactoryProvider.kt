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

    // Construct once per key: init reads disk and sweeps orphan temps under the map's bin lock.
    // The mapping function must never call back into this factory.
    override fun createStorage(pluginId: String): PluginStorageProvider =
        storageCache.computeIfAbsent(pluginId) {
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
 * Cancellation while queued commits nothing; cancellation during a commit can still report
 * cancellation to the caller after disk and cache have both committed. This is process-local
 * coordination: a second process or an external editor is not part of the transaction.
 * [storageDirOverride] and [writeProperties] are test seams; production uses the default path/writer.
 */
internal class PluginStorageProviderImpl(
    private val pluginId: String,
    private val storageDirOverride: File? = null,
    private val writeProperties: (File, Properties) -> Unit = ::writePluginProperties,
) : PluginStorageProvider {
    companion object {
        private val logger = BossLogger.forComponent("PluginStorage")
    }

    private val storageDir: File by lazy {
        storageDirOverride ?: BossDirectories.resolve("plugin-data/$pluginId")
    }
    private val storageFile: File by lazy { File(storageDir, "storage.properties") }

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
        mutate(key, skipUnchanged = true) { it - key }
    }

    override suspend fun getAllKeys(): Set<String> = cache.keys.toSet()

    override suspend fun clear() {
        mutate("*", skipUnchanged = true) { emptyMap() }
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
        // computeIfAbsent constructs one provider per id, before any of its writes can start.
        val tempPrefix = tempPrefixFor(storageFile)
        storageDir
            .listFiles { file -> file.name.startsWith(tempPrefix) }
            ?.forEach { orphan -> orphan.delete() }
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
        skipUnchanged: Boolean = false,
        transform: (Map<String, String>) -> Map<String, String>,
    ) {
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                currentCoroutineContext().ensureActive()
                // Waiting callers remain cancellable. Once admitted, a blocking file commit and
                // cache publication must finish together even if the caller cancels during I/O.
                withContext(NonCancellable) {
                    val updated = transform(cache)
                    if (!skipUnchanged || updated != cache) {
                        persistSnapshot(updated, changedKey)
                    }
                }
            }
        }
    }

    private fun persistSnapshot(
        updated: Map<String, String>,
        changedKey: String,
    ) {
        val properties = Properties().apply { putAll(updated) }
        try {
            writeProperties(storageFile, properties)
        } catch (e: Exception) {
            logger.error(
                LogCategory.SYSTEM,
                "Failed to save plugin storage",
                mapOf("pluginId" to pluginId),
                e,
            )
            throw e
        }
        // Publication is outside the persistence catch: a notification failure
        // must not be reported as a failed file commit.
        cache = updated
        if (!_changes.tryEmit(changedKey)) {
            logger.warn(
                LogCategory.SYSTEM,
                "Plugin storage change notification buffer full",
                mapOf("pluginId" to pluginId),
            )
        }
    }
}

/**
 * Writes a unique sibling file and syncs its content before replacement. On the atomic path,
 * a surviving rename after power loss points at complete content. The parent directory is not
 * synced, so power loss can lose the rename itself; the shared move helper's non-atomic fallback
 * cannot promise atomic replacement. A process crash does not lose the kernel's page cache.
 */
internal fun tempPrefixFor(file: File) = "${file.name}.tmp."

internal fun writePluginProperties(
    file: File,
    properties: Properties,
) {
    val target = file.absoluteFile
    target.parentFile.mkdirs()
    val temporary = Files.createTempFile(target.parentFile.toPath(), tempPrefixFor(target), ".tmp").toFile()
    try {
        // Keep OutputStream encoding: Properties.load(InputStream) expects Latin-1 with escaped
        // Unicode, not the unescaped Unicode emitted by Properties.store(Writer).
        // Files.createTempFile deliberately uses restrictive permissions on POSIX stores.
        temporary.outputStream().use { out ->
            properties.store(out, "Plugin storage")
            out.fd.sync()
        }
        target.atomicMoveFrom(temporary)
    } finally {
        temporary.delete()
    }
}
