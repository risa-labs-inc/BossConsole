package ai.rever.boss.notifications

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The operator's notification inbox, persisted in `~/.boss/notifications.json`.
 *
 * Unlike `StatusMessageManager`, whose next message cancels the previous one, entries here are
 * durable: a long task that finishes while the operator is away, an available update, or a note
 * an agent leaves through the MCP tools all survive until read or cleared. The host publishes into
 * it and a bell/inbox UI can observe [notifications]; agents reach it through
 * `NotificationMcpToolProvider`.
 *
 * The persistence contract is the one every small BOSS state file follows: atomic writes via
 * [atomicWriteText], mutations serialized under [mutex] so overlapping posts cannot drop an entry,
 * and forward-coercing reads (`ignoreUnknownKeys`) so a newer or hand-edited file still loads. The
 * inbox is bounded to [MAX_ENTRIES] newest entries so it cannot grow without limit. [storageFile]
 * is an overridable test hook, mirroring `RunConfigurationManager`.
 */
object NotificationCenter {
    private val logger = BossLogger.forComponent("NotificationCenter")

    /** Newest entries kept; older ones are dropped on the next post. */
    const val MAX_ENTRIES = 200

    private val defaultStorageFile = BossDirectories.resolve("notifications.json")

    @Volatile
    internal var storageFile: File = defaultStorageFile

    @Volatile
    internal var clock: () -> Long = { System.currentTimeMillis() }

    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    private val mutex = Mutex()

    private val _notifications = MutableStateFlow<List<BossNotification>>(emptyList())

    /** Newest first. */
    val notifications: StateFlow<List<BossNotification>> = _notifications.asStateFlow()

    init {
        storageFile.parentFile?.mkdirs()
        loadSync()
    }

    internal fun loadSync() {
        try {
            if (storageFile.exists()) {
                val store = json.decodeFromString(NotificationStore.serializer(), storageFile.readText())
                _notifications.value = store.notifications.sortedByDescending { it.createdAt }.take(MAX_ENTRIES)
            } else {
                _notifications.value = emptyList()
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.warn(LogCategory.SYSTEM, "Failed to load notifications", error = e)
            _notifications.value = emptyList()
        }
    }

    internal fun resetForTesting(testFile: File? = null) {
        storageFile = testFile ?: defaultStorageFile
        clock = { System.currentTimeMillis() }
        loadSync()
    }

    /** The number of unread entries. */
    fun unreadCount(): Int = _notifications.value.count { !it.read }

    /**
     * Post a new notification and persist it, dropping the oldest beyond [MAX_ENTRIES]. Newly
     * posted entries are unread; the returned entry carries the assigned id.
     */
    suspend fun post(
        title: String,
        message: String = "",
        level: NotificationLevel = NotificationLevel.INFO,
        source: String = "",
    ): BossNotification {
        require(title.isNotBlank()) { "Notification title must not be blank" }
        return mutex.withLock {
            val entry =
                BossNotification(
                    id = generateUniqueId(),
                    title = title,
                    message = message,
                    level = level,
                    source = source,
                    createdAt = clock(),
                    read = false,
                )
            val updated = (listOf(entry) + _notifications.value).take(MAX_ENTRIES)
            // Persist BEFORE publishing: a failed write must not leave the
            // in-memory inbox ahead of the disk copy.
            persist(updated)
            _notifications.value = updated
            entry
        }
    }

    /** Mark one entry read. Returns true when it existed and was not already read. */
    suspend fun markRead(id: String): Boolean =
        mutex.withLock {
            val current = _notifications.value
            val target = current.firstOrNull { it.id == id } ?: return@withLock false
            if (target.read) return@withLock false
            val updated = current.map { if (it.id == id) it.copy(read = true) else it }
            // Persist BEFORE publishing: a failed write must not leave the
            // in-memory inbox ahead of the disk copy.
            persist(updated)
            _notifications.value = updated
            true
        }

    /** Mark every entry read. Returns the number of entries that changed. */
    suspend fun markAllRead(): Int =
        mutex.withLock {
            val current = _notifications.value
            val changed = current.count { !it.read }
            if (changed == 0) return@withLock 0
            val updated = current.map { if (it.read) it else it.copy(read = true) }
            // Persist BEFORE publishing: a failed write must not leave the
            // in-memory inbox ahead of the disk copy.
            persist(updated)
            _notifications.value = updated
            changed
        }

    /** Remove every entry. Returns the number removed. */
    suspend fun clear(): Int =
        mutex.withLock {
            val removed = _notifications.value.size
            if (removed == 0) return@withLock 0
            // Persist BEFORE publishing: a failed write must not leave the
            // in-memory inbox ahead of the disk copy.
            persist(emptyList())
            _notifications.value = emptyList()
            removed
        }

    private suspend fun persist(entries: List<BossNotification>) =
        withContext(Dispatchers.IO) {
            try {
                val content = json.encodeToString(NotificationStore.serializer(), NotificationStore(entries))
                storageFile.atomicWriteText(content)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                logger.warn(LogCategory.SYSTEM, "Failed to save notifications", error = e)
                throw e
            }
        }

    private fun generateUniqueId(): String {
        var id: String
        do {
            id = "notif-${clock()}-${(0..9999).random()}"
        } while (_notifications.value.any { it.id == id })
        return id
    }
}
