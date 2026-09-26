package ai.rever.boss.downloads

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.decodeFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Persistent history of completed downloads, in `~/.boss/download-history.json`.
 *
 * The browser's `DownloadManager` tracks live progress in memory and forgets everything on
 * restart; this records each completed download durably so the operator (and an agent through
 * `DownloadHistoryMcpToolProvider`) can see what was downloaded across sessions.
 *
 * The persistence contract is the one every small BOSS state file follows: atomic writes via
 * [atomicWriteText], mutations serialized under [mutex], forward-coercing reads
 * (`ignoreUnknownKeys`), and a bound of [MAX_ENTRIES] newest records. [storageFile] is an
 * overridable test hook, mirroring `RunConfigurationManager`.
 */
object DownloadHistoryManager {
    private val logger = BossLogger.forComponent("DownloadHistoryManager")

    /** Newest records kept; older ones drop off on the next record. */
    const val MAX_ENTRIES = 500

    private val defaultStorageFile by lazy { BossDirectories.resolve("download-history.json") }

    @Volatile
    private var storageFileOverride: File? = null

    internal var storageFile: File
        get() = storageFileOverride ?: defaultStorageFile
        set(value) {
            storageFileOverride = value
        }

    @Volatile
    internal var clock: () -> Long = { System.currentTimeMillis() }

    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    private val mutex = Mutex()

    private val _downloads = MutableStateFlow<List<DownloadRecord>>(emptyList())

    @Volatile
    private var loaded = false

    /** A failed read must not be replaced by a later download's one-record history. */
    @Volatile
    internal var loadFailed: Boolean = false
        private set

    /** Newest first. */
    val downloads: StateFlow<List<DownloadRecord>> = _downloads.asStateFlow()

    internal fun loadSync() {
        try {
            if (storageFile.exists()) {
                val history = json.decodeFromString(DownloadHistory.serializer(), storageFile.readText())
                _downloads.value = history.downloads
            } else {
                _downloads.value = emptyList()
            }
            loadFailed = false
        } catch (e: SerializationException) {
            logger.warn(LogCategory.SYSTEM, "Failed to load download history", decodeFailure(e))
            _downloads.value = emptyList()
            loadFailed = true
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.warn(LogCategory.SYSTEM, "Failed to load download history", error = e)
            _downloads.value = emptyList()
            loadFailed = true
        } finally {
            loaded = true
        }
    }

    internal fun resetForTesting(
        testFile: File? = null,
        loadNow: Boolean = true,
    ) {
        storageFile = testFile ?: defaultStorageFile
        clock = { System.currentTimeMillis() }
        loaded = false
        _downloads.value = emptyList()
        loadFailed = false
        if (loadNow) {
            storageFile.parentFile?.mkdirs()
            loadSync()
        }
    }

    /** Load on the IO dispatcher before the first operation, without blocking object initialization. */
    private suspend fun ensureLoaded() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            storageFile.parentFile?.mkdirs()
            loadSync()
        }
    }

    /** Read a consistent snapshot after the initial disk load. */
    suspend fun list(): List<DownloadRecord> =
        mutex.withLock {
            ensureLoaded()
            check(!loadFailed) { "Download history could not be read; clear it before listing" }
            _downloads.value
        }

    /**
     * Record a completed download of [url] saved to [filePath]. The file name is derived from the
     * path. Returns the stored record.
     */
    suspend fun record(
        url: String,
        filePath: String,
        sizeBytes: Long? = null,
    ): DownloadRecord =
        mutex.withLock {
            ensureLoaded()
            check(!loadFailed) { "Download history could not be read; clear it before recording new downloads" }
            val completedAt = clock()
            val record =
                DownloadRecord(
                    id = "download-${UUID.randomUUID()}",
                    url = url,
                    fileName = File(filePath).name,
                    filePath = filePath,
                    sizeBytes = sizeBytes,
                    completedAt = completedAt,
                )
            val updated = (listOf(record) + _downloads.value).take(MAX_ENTRIES)
            persist(updated)
            _downloads.value = updated
            record
        }

    /** Remove one record. Returns true when it existed. */
    suspend fun remove(id: String): Boolean =
        mutex.withLock {
            ensureLoaded()
            check(!loadFailed) { "Download history could not be read; clear it before removing records" }
            val current = _downloads.value
            val updated = current.filterNot { it.id == id }
            if (updated.size == current.size) {
                false
            } else {
                persist(updated)
                _downloads.value = updated
                true
            }
        }

    /** Remove every record. Returns the number removed. */
    suspend fun clear(): Int =
        mutex.withLock {
            ensureLoaded()
            val removed = _downloads.value.size
            persist(emptyList())
            _downloads.value = emptyList()
            loadFailed = false
            removed
        }

    private suspend fun persist(records: List<DownloadRecord>) =
        withContext(Dispatchers.IO) {
            try {
                val content = json.encodeToString(DownloadHistory.serializer(), DownloadHistory(records))
                storageFile.atomicWriteText(content)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                logger.warn(LogCategory.SYSTEM, "Failed to save download history", error = e)
                throw e
            }
        }
}
