package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.PluginStorageFactory
import ai.rever.boss.plugin.api.PluginStorageProvider
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID
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
 */
class PluginStorageProviderImpl(
    private val pluginId: String,
    private val storageDirOverride: File? = null,
) : PluginStorageProvider {
    companion object {
        private val logger = BossLogger.forComponent("PluginStorage")
    }

    private val storageDir: File by lazy {
        val dir = storageDirOverride ?: BossDirectories.resolve("plugin-data/$pluginId")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    private val storageFile: File by lazy {
        File(storageDir, "storage.properties")
    }

    // In-memory cache
    private val cache = ConcurrentHashMap<String, String>()

    // Change notification
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 64)

    // Serializes read-modify-persist-publish transactions per provider.
    private val transactionMutex = Mutex()

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
        transactionMutex.withLock {
            val properties = Properties()
            cache.forEach { (k, v) -> properties[k] = v }
            properties[key] = value

            commitTransaction(properties) {
                cache[key] = value
                _changes.tryEmit(key)
            }
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
        transactionMutex.withLock {
            if (!cache.containsKey(key)) return@withLock

            val properties = Properties()
            cache.forEach { (k, v) -> properties[k] = v }
            properties.remove(key)

            commitTransaction(properties) {
                cache.remove(key)
                _changes.tryEmit(key)
            }
        }
    }

    override suspend fun getAllKeys(): Set<String> = cache.keys.toSet()

    override suspend fun clear() {
        transactionMutex.withLock {
            if (cache.isEmpty()) return@withLock

            val properties = Properties()

            commitTransaction(properties) {
                cache.clear()
                _changes.tryEmit("*")
            }
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

    // ============ Disk Operations ============

    private fun loadFromDisk() {
        try {
            if (storageFile.exists()) {
                val properties = Properties()
                storageFile.inputStream().use { properties.load(it) }
                properties.forEach { key, value ->
                    cache[key.toString()] = value.toString()
                }
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

    private suspend fun commitTransaction(
        properties: Properties,
        onSuccess: () -> Unit,
    ) {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                if (!storageDir.exists()) {
                    storageDir.mkdirs()
                }

                val tempFile = File(storageDir, "storage.properties.tmp.${UUID.randomUUID()}")

                try {
                    tempFile.outputStream().use {
                        properties.store(it, "Plugin storage for $pluginId")
                    }

                    try {
                        Files.move(
                            tempFile.toPath(),
                            storageFile.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(
                            tempFile.toPath(),
                            storageFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }
                } finally {
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                }

                // Execute onSuccess block ONLY after successful atomic move
                onSuccess()
            } catch (e: Exception) {
                logger.error(
                    LogCategory.SYSTEM,
                    "Failed to save plugin storage",
                    mapOf(
                        "pluginId" to pluginId,
                    ),
                    e,
                )
                throw e
            }
        }
    }
}
