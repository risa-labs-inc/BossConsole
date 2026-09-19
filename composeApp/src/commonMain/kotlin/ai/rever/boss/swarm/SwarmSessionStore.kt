package ai.rever.boss.swarm

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** Set when the journal could not be read or written. Cleared by the next successful write. */
    val fault: StateFlow<SwarmStoreFault?> = _fault.asStateFlow()

    /**
     * Every session in the journal, oldest first.
     *
     * An absent file is an empty journal, because that is what a first run looks like. A file that
     * exists but does not parse is a fault: this returns empty so the caller has something usable,
     * and [fault] carries the reason so the emptiness is not mistaken for fact.
     */
    @Suppress("TooGenericExceptionCaught") // A journal fault is reported, not thrown at the UI.
    fun load(): List<SwarmSession> {
        val file = storeFile?.takeIf { it.exists() } ?: return emptyList()
        return try {
            json.decodeFromString<SwarmSessionsDocument>(file.readText()).sessions
        } catch (t: Exception) {
            val error = t.message ?: t::class.simpleName ?: "unknown error"
            _fault.value = SwarmStoreFault.Unreadable(file.path, error)
            logger.error(
                LogCategory.SYSTEM,
                "Failed to parse swarm session journal",
                mapOf("path" to file.path, "error" to error),
            )
            emptyList()
        }
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
     * Inserts or replaces [session] by id and returns the journal as written.
     *
     * Order is preserved rather than re-sorted, so the journal reads as a history of what the
     * operator started and when, which is the only ordering anyone has ever wanted from it.
     */
    fun upsert(session: SwarmSession): List<SwarmSession> {
        val existing = load()
        val index = existing.indexOfFirst { it.id == session.id }
        val updated =
            if (index >= 0) {
                existing.toMutableList().also { it[index] = session }
            } else {
                existing + session
            }
        save(updated)
        return updated
    }
}
