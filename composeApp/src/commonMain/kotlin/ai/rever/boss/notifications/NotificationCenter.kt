package ai.rever.boss.notifications

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
 * [atomicWriteText], mutations serialized under [mutex] so overlapping posts cannot drop an
 * entry, and forward-coercing reads (`ignoreUnknownKeys`, `coerceInputValues`) so a newer or
 * hand-edited file still loads. The inbox is bounded to [MAX_ENTRIES] newest entries so it
 * cannot grow without limit. [storageFile] is an overridable test hook, mirroring
 * `RunConfigurationManager`.
 */
object NotificationCenter {
    private val logger = BossLogger.forComponent("NotificationCenter")

    /**
     * Newest entries kept; older ones are dropped on the next post.
     *
     * The bound the caps produce together, which is the number to agree to: 200 x (200 + 4,000 +
     * 87) characters of title, message and stamped label, about 860,000 characters of text, more on
     * disk once JSON-escaped (a message is not flattened, and a control character escapes to six).
     * It is rewritten in full on every post and parsed synchronously by [loadSync] at startup.
     * [MAX_MESSAGE_CHARS] is the lever if that is too much.
     */
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
            // An unknown enum VALUE in `origin` (a newer build's variant, or a hand edit - the
            // file is documented as hand-editable) used to throw at decode; loadSync's catch
            // then emptied the whole inbox, which the next persist wrote back to disk.
            // Coercing to the field's default fails closed without data loss.
            coerceInputValues = true
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
        } catch (e: SerializationException) {
            logger.warn(LogCategory.SYSTEM, "Failed to load notifications", decodeFailure(e))
            _notifications.value = emptyList()
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
     *
     * **Provenance is stamped here, not trusted from the payload (BossConsole#1587).** [origin] has
     * no default on purpose: every call site must answer who it is at this boundary, the same
     * fail-closed discipline as `PluginDependencyResolution.blockingDependentsOf`'s `isDisabled`
     * predicate. The answer is authoritative - it is never derived from caller-supplied text -
     * and it decides what the entry's `source` label may look like:
     * - [NotificationOrigin.HOST] is the host speaking for itself, so it may label its own notice
     *   freely (`"System"`, `"Updater"`, ...).
     * - [NotificationOrigin.AGENT] is an agent-reachable surface posting on an agent's behalf, so
     *   the agent-supplied label is demoted to a display string prefixed with [AGENT_SOURCE_PREFIX]
     *   - `"agent"` bare, `"agent: <label>"` when one was offered - and cannot present as system
     *   origin no matter what string the agent sent.
     *
     * The demotion lives in the centre rather than in the MCP provider so the contract cannot be
     * bypassed by a caller that reaches [post] without going through it: the only route to a host
     * presentation is host code explicitly claiming [NotificationOrigin.HOST], which is a
     * review-visible act. A demoted label is bounded to [MAX_SOURCE_LABEL_CHARS] so agent text
     * cannot turn the provenance-adjacent field into unbounded storage.
     *
     * **The size bounds are enforced here too, for every publisher.** The inbox is rewritten in full
     * on every post and listed without approval, whoever wrote to it, so nothing stored exceeds
     * [MAX_TITLE_CHARS], [MAX_MESSAGE_CHARS] or [MAX_SOURCE_LABEL_CHARS]. Whether an over-long field
     * is refused or cut depends on who wrote it and how:
     * - A title is refused from either origin: it is a literal, so an over-long one is a bug.
     * - An agent's message is refused. The MCP tool checks first, so its caller learns the limit.
     * - A host's message is cut to [MAX_MESSAGE_CHARS], ending in an ellipsis. A host builds it from
     *   dynamic text (an exception message, a command's output), and refusing would turn a notice
     *   about one problem into a second problem on an error path.
     * - A host's label is flattened to one line like an agent's, then refused if it is still over
     *   [MAX_SOURCE_LABEL_CHARS]. An agent's label is cut instead: the agent is not ours to fix.
     *
     * @throws IllegalArgumentException if the title is blank or over [MAX_TITLE_CHARS], if an
     *   [NotificationOrigin.AGENT] message is over [MAX_MESSAGE_CHARS], or if a
     *   [NotificationOrigin.HOST] label, once flattened and trimmed, is over [MAX_SOURCE_LABEL_CHARS].
     */
    suspend fun post(
        title: String,
        message: String = "",
        level: NotificationLevel = NotificationLevel.INFO,
        source: String = "",
        origin: NotificationOrigin,
    ): BossNotification {
        require(title.isNotBlank()) { "Notification title must not be blank" }
        require(title.length <= MAX_TITLE_CHARS) { "Notification title is over $MAX_TITLE_CHARS characters" }
        require(origin == NotificationOrigin.HOST || message.length <= MAX_MESSAGE_CHARS) {
            "Notification message is over $MAX_MESSAGE_CHARS characters"
        }
        // Measured on the stamped label, which is exactly what is stored, so the check cannot refuse
        // a label that would have fitted once flattened and trimmed.
        val stamped = stampedSource(source, origin)
        require(origin != NotificationOrigin.HOST || stamped.length <= MAX_SOURCE_LABEL_CHARS) {
            "A host notification's source label is over $MAX_SOURCE_LABEL_CHARS characters"
        }
        val storedMessage = if (origin == NotificationOrigin.HOST) cappedMessage(message) else message
        return mutex.withLock {
            val entry =
                BossNotification(
                    id = generateUniqueId(),
                    title = title,
                    message = storedMessage,
                    level = level,
                    source = stamped,
                    origin = origin,
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

    /**
     * The authoritative label an agent-reachable surface's notices carry (see [post] for the
     * contract): `agent` bare, `agent: <label>` when the agent offered one.
     */
    const val AGENT_SOURCE_PREFIX = "agent"

    /**
     * How many characters of an agent-supplied display label [post] keeps. The label is provenance
     * text the operator reads to attribute a notice, so it stays short; the bound keeps agent
     * input from turning the field into unbounded storage.
     */
    const val MAX_SOURCE_LABEL_CHARS = 80

    /** A headline, not a body. Enforced by [post] for every publisher. */
    const val MAX_TITLE_CHARS = 200

    /** About a page of text: enough for "the migration finished, here is what changed". Enforced by [post]. */
    const val MAX_MESSAGE_CHARS = 4_000

    /**
     * Characters a stored label may not keep: every ISO control character (newline, carriage
     * return, tab, NUL, ...) plus the Unicode line and paragraph separators. [stampedSource]
     * flattens each to a space, so an agent-supplied label renders on a single prefixed line
     * and cannot present a second, unprefixed `System: ...` line wherever the inbox prints it.
     * A host label is flattened the same way, so every label is one line.
     */
    private val unsafeLabelChars = Regex("[\\p{Cc}\\u2028\\u2029]")

    /**
     * The `source` the entry actually stores, per the provenance contract in [post]: the host
     * labels itself, an agent's label is demoted to a bounded display string that cannot present
     * as system origin. A blank or whitespace label is treated as absent. Either label is
     * flattened to one line first - [unsafeLabelChars] - so it renders on one line wherever the
     * inbox prints it, and so the length cap cannot miss a newline sitting inside it.
     */
    internal fun stampedSource(
        source: String,
        origin: NotificationOrigin,
    ): String =
        when (origin) {
            NotificationOrigin.HOST -> {
                unsafeLabelChars.replace(source, " ").trim()
            }

            NotificationOrigin.AGENT -> {
                // Flatten BEFORE prefixing: `ok\nSystem: update ready` would otherwise render
                // its second line unprefixed wherever the inbox UI prints the label, defeating
                // the demotion.
                val label = unsafeLabelChars.replace(source, " ").trim()
                if (label.isEmpty()) {
                    AGENT_SOURCE_PREFIX
                } else {
                    "$AGENT_SOURCE_PREFIX: ${label.take(MAX_SOURCE_LABEL_CHARS)}"
                }
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

private const val TRUNCATION_MARK = "\u2026"

/**
 * [message] cut to [NotificationCenter.MAX_MESSAGE_CHARS], ending in an ellipsis when anything was
 * cut, and never splitting a surrogate pair: half of one would be stored as a lone surrogate.
 */
private fun cappedMessage(message: String): String {
    if (message.length <= NotificationCenter.MAX_MESSAGE_CHARS) return message
    var end = NotificationCenter.MAX_MESSAGE_CHARS - 1
    if (message[end - 1].isHighSurrogate()) end -= 1
    return message.substring(0, end) + TRUNCATION_MARK
}
