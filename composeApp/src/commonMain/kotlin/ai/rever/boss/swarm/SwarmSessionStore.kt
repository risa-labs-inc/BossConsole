package ai.rever.boss.swarm

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private const val SWARM_SESSIONS_FILE_NAME = "swarm-sessions.json"

/** Bumped only when a reader of an older journal would misread a newer one. */
private const val SWARM_SESSIONS_VERSION = 1

/** The on-disk shape of the swarm journal, wrapped so the format can grow without ambiguity. */
@Serializable
internal data class SwarmSessionsDocument(
    val version: Int = SWARM_SESSIONS_VERSION,
    val sessions: List<SwarmSession> = emptyList(),
)

/** Why the swarm journal is not usable as written. */
sealed interface SwarmStoreFault {
    val message: String

    /** The file exists but does not parse, so the sessions in it are not readable. */
    data class Unreadable(
        val path: String,
        val error: String,
    ) : SwarmStoreFault {
        override val message: String
            get() = "Swarm session journal could not be read ($error): $path"
    }

    /** The journal could not be replaced, so the previous contents are still what is on disk. */
    data class Unwritable(
        val path: String,
        val error: String,
    ) : SwarmStoreFault {
        override val message: String
            get() = "Swarm session journal could not be written ($error): $path"
    }
}

/**
 * What an [SwarmSessionStore.upsert] did: the journal as it now stands, and the write failure if it
 * could not be persisted.
 *
 * Both halves are returned, because either one alone would mislead. The list alone says a write
 * landed that may not have, and the failure alone leaves the caller with no journal to read. [error]
 * is exactly what [SwarmSessionStore.save] returns, so a write failure is reported in the same words
 * whichever path hit it.
 */
data class SwarmUpsertResult(
    /** The journal including the upserted session, whether or not it reached disk. */
    val sessions: List<SwarmSession>,
    /** Non-null when the write failed; [sessions] is then what the journal *should* hold. */
    val error: String?,
)

/**
 * Reads and writes the swarm session journal at `~/.boss/swarm-sessions.json`.
 *
 * Follows the persistence shape the rest of `~/.boss` already uses: a nullable file so the store can
 * be built without one in tests, an absent file treated as an empty journal rather than a fault, and
 * a write that replaces the file atomically so a crash part way through cannot leave a half journal
 * behind.
 *
 * A file that exists but cannot be read is reported through [fault] rather than being treated as
 * empty. That distinction is the same one the operation ledger makes, and for the same reason: an
 * operator looking at a session list has to be told the journal is unreadable, not shown a confident
 * empty list that reads as "you never ran a swarm".
 */
class SwarmSessionStore(
    private val storeFile: File? = BossDirectories.resolve(SWARM_SESSIONS_FILE_NAME),
) {
    private val logger = BossLogger.forComponent("SwarmSessionStore")
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            // encodeDefaults is load-bearing, and specifically for `version`. That field carries a
            // default, and kotlinx omits defaulted fields unless told otherwise - so without this
            // the version marker the whole format depends on is absent from every journal ever
            // written. A reader still tolerates its absence (the default covers that), but a writer
            // that never states it makes the versioning decorative.
            //
            // Note this is the OPPOSITE of the operation ledger's choice, and deliberately so: the
            // ledger must NOT encode defaults, because re-encoding a historical record has to
            // reproduce its hash byte for byte. Here nothing is hashed and explicit is better.
            encodeDefaults = true
        }

    /** The resolved journal path, for operator-facing inspection, or null when not persisting. */
    val persistencePath: String? get() = storeFile?.absolutePath

    private val _fault = MutableStateFlow<SwarmStoreFault?>(null)

    /**
     * Set when the journal could not be read or written.
     *
     * Cleared by whatever proves it false, not by "the next write" alone: a successful read clears an
     * [SwarmStoreFault.Unreadable], and a successful write clears either kind. An
     * [SwarmStoreFault.Unwritable] deliberately survives a read - parsing the file says nothing about
     * whether the write that failed has since landed, and a store that forgot a failed write because
     * someone read the old contents would be reporting a journal state it does not have.
     */
    val fault: StateFlow<SwarmStoreFault?> = _fault.asStateFlow()

    /** Serializes the read-modify-write in [upsert] against other upserts. */
    private val writeLock = Mutex()

    /**
     * Every session in the journal, oldest first.
     *
     * An absent file is an empty journal, because that is what a first run looks like. A file that
     * exists but does not parse is a fault: this returns empty so the caller has something usable,
     * and [fault] carries the reason so the emptiness is not mistaken for fact.
     *
     * A read that succeeds clears an [SwarmStoreFault.Unreadable] - including the read that finds no
     * file at all, since a journal that has been moved aside is no longer unreadable, and this is the
     * call a UI makes repeatedly while it is on screen.
     */
    @Suppress("TooGenericExceptionCaught") // A journal fault is reported, not thrown at the UI.
    fun load(): List<SwarmSession> {
        val file = storeFile?.takeIf { it.exists() }
        if (file == null) {
            clearUnreadableFault()
            return emptyList()
        }
        return try {
            val sessions = json.decodeFromString<SwarmSessionsDocument>(file.readText()).sessions
            clearUnreadableFault()
            sessions
        } catch (t: Exception) {
            val error = t.message ?: t::class.simpleName ?: "unknown error"
            // A write failure is more actionable than a read failure when both are possible: a
            // later read of the path cannot prove that the failed write landed, so it must not
            // replace the write fault with a read fault.
            if (_fault.value !is SwarmStoreFault.Unwritable) {
                _fault.value = SwarmStoreFault.Unreadable(file.path, error)
            }
            logger.error(
                LogCategory.SYSTEM,
                "Failed to parse swarm session journal",
                mapOf("path" to file.path, "error" to error),
            )
            emptyList()
        }
    }

    /**
     * Drops a read fault, which a read that just succeeded has disproved.
     *
     * Only [SwarmStoreFault.Unreadable] is cleared: an [SwarmStoreFault.Unwritable] is about a write
     * that still may not have landed, and reading the file cannot answer that.
     */
    private fun clearUnreadableFault() {
        if (_fault.value is SwarmStoreFault.Unreadable) _fault.value = null
    }

    /**
     * Replaces the journal with [sessions] and returns null, or returns the failure message.
     *
     * Returning the message instead of throwing leaves the caller in charge of how loudly to fail,
     * which is the contract the MCP policy engine already uses for its own file.
     */
    @Suppress("TooGenericExceptionCaught") // A write failure leaves the previous journal in force.
    fun save(sessions: List<SwarmSession>): String? {
        val file = storeFile ?: return null
        return try {
            // atomicWriteText creates the parent directory itself and replaces the file atomically,
            // so a crash part way through leaves the previous journal intact rather than truncated.
            file.atomicWriteText(json.encodeToString(SwarmSessionsDocument(sessions = sessions)))
            _fault.value = null
            null
        } catch (t: Exception) {
            val error = t.message ?: t::class.simpleName ?: "unknown write error"
            _fault.value = SwarmStoreFault.Unwritable(file.path, error)
            logger.error(
                LogCategory.SYSTEM,
                "Failed to write swarm session journal",
                mapOf("path" to file.path, "error" to error),
            )
            error
        }
    }

    /**
     * Inserts or replaces [session] by id, and reports the journal alongside the write failure.
     *
     * Order is preserved rather than re-sorted, so the journal reads as a history of what the
     * operator started and when, which is the only ordering anyone has ever wanted from it.
     *
     * The failure is returned rather than dropped, because a caller that got the merged list back
     * with no error would reasonably read it as "this is on disk now", and [fault] is only visible to
     * whatever is collecting it. [writeLock] covers the whole read-modify-write: two concurrent
     * callers would otherwise each read the same journal and each write their own version, silently
     * losing one session. The first caller of this is an orchestrator polling per-worktree git stats,
     * which is exactly concurrent upserts, so the lock is not speculative. It is held across [load]
     * and [save] only, never across caller code.
     */
    suspend fun upsert(session: SwarmSession): SwarmUpsertResult =
        writeLock.withLock {
            val existing = load()
            val index = existing.indexOfFirst { it.id == session.id }
            val updated =
                if (index >= 0) {
                    existing.toMutableList().also { it[index] = session }
                } else {
                    existing + session
                }
            SwarmUpsertResult(sessions = updated, error = save(updated))
        }
}
