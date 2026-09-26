package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.PluginStorageFactory
import ai.rever.boss.plugin.api.PluginStorageProvider
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStreamReader
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

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
 * All mutations are serialized through [commitMutex]. A mutation is first
 * applied to an isolated snapshot, persisted atomically, and only then
 * published to the in-memory cache and change flow.
 */
class PluginStorageProviderImpl(
    private val pluginId: String,
    private val storageFileOverride: File? = null,
) : PluginStorageProvider {
    companion object {
        private val logger = BossLogger.forComponent("PluginStorage")
    }

    private val storageDir: File by lazy {
        val dir = BossDirectories.resolve("plugin-data/$pluginId")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    private val storageFile: File by lazy {
        storageFileOverride ?: File(storageDir, "storage.properties")
    }

    /**
     * Last successfully committed in-memory state.
     *
     * The reference itself is atomically replaced after a successful disk
     * commit. Readers therefore see either the previous complete snapshot or
     * the new complete snapshot, never a partially updated map.
     */
    @Volatile
    private var cache: Map<String, String> = emptyMap()

    /**
     * Test-only hook that runs after the disk snapshot has been persisted and
     * immediately before the committed cache reference is swapped.
     */
    internal var beforeCachePublishHookForTest: (() -> Unit)? = null

    /**
     * Serializes the complete read-modify-persist-publish transaction.
     *
     * The mutex is intentionally held until the disk commit and cache
     * publication are both complete, so concurrent mutations cannot
     * overwrite each other's snapshots.
     */
    private val commitMutex = Mutex()

    internal fun commitMutexForTest(): Mutex = commitMutex

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
        commit(
            changedKey = key,
        ) { candidate ->
            candidate[key] = value
        }
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
        commit(
            changedKey = key,
        ) { candidate ->
            candidate.remove(key)
        }
    }

    override suspend fun getAllKeys(): Set<String> = cache.keys.toSet()

    override suspend fun clear() {
        commit(
            changedKey = "*",
        ) { candidate ->
            candidate.clear()
        }
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

    // ============ Transaction / Disk Operations ============

    /**
     * Performs one complete storage mutation transaction:
     *
     * 1. Wait for the provider mutation lock.
     * 2. Create a snapshot of the last committed cache.
     * 3. Apply the requested mutation to the snapshot.
     * 4. Persist the snapshot using an atomic sibling temp file.
     * 5. Publish the complete snapshot to the in-memory cache with one
     *    reference swap.
     * 6. Emit the change notification.
     *
     * Cancellation is allowed while waiting for the mutex. Once the actual
     * commit begins, the disk write and cache publication run under
     * [NonCancellable] so the committed disk and memory states cannot diverge.
     */
    private suspend fun commit(
        changedKey: String,
        mutation: (MutableMap<String, String>) -> Unit,
    ) {
        commitMutex.withLock {
            // Cancellation while waiting for the mutex must not mutate storage.
            coroutineContext.ensureActive()

            val candidate = cache.toMutableMap()
            mutation(candidate)

            withContext(Dispatchers.IO + NonCancellable) {
                // Persist first. If this throws, the existing cache remains
                // unchanged and no notification is emitted.
                saveSnapshotToDisk(candidate)

                // This hook is only used by tests to hold the commit after
                // persistence but before the in-memory publication.
                beforeCachePublishHookForTest?.invoke()

                // Publish the complete committed snapshot with one reference
                // swap. Readers can therefore never observe a partially
                // cleared or partially populated cache.
                cache = candidate.toMap()

                // Notify observers only after both disk and cache commit.
                _changes.tryEmit(changedKey)
            }
        }
    }

    /**
     * Serializes a snapshot using the existing java.util.Properties format
     * and atomically replaces the storage file.
     *
     * StringWriter produces the Properties textual representation, and
     * atomicWriteText writes that representation as UTF-8. loadFromDisk()
     * therefore reads the same format using a UTF-8 Reader.
     */
    private fun saveSnapshotToDisk(snapshot: Map<String, String>) {
        val properties = Properties()

        snapshot.forEach { (key, value) ->
            properties[key] = value
        }

        val writer = StringWriter()
        properties.store(
            writer,
            "Plugin storage for $pluginId",
        )

        storageFile.parentFile?.mkdirs()
        storageFile.atomicWriteText(writer.toString())
    }

    private fun loadFromDisk() {
        try {
            if (storageFile.exists()) {
                val properties = Properties()

                InputStreamReader(
                    storageFile.inputStream(),
                    StandardCharsets.UTF_8,
                ).use { reader ->
                    properties.load(reader)
                }

                val loaded = buildMap {
                    properties.forEach { key, value ->
                        put(key.toString(), value.toString())
                    }
                }

                cache = loaded

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
}
