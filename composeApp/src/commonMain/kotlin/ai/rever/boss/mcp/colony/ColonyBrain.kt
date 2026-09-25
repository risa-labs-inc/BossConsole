package ai.rever.boss.mcp.colony

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One note on the shared scratchpad.
 *
 * Deliberately four fields. This is a bulletin board for cross-worktree discoveries - "worktree-1
 * renamed Foo to Bar; other worktrees touching Foo should know" - not a memory architecture. There
 * is no schema, no query language, and no ownership: a note is an assertion by the worktree that
 * wrote it, and nothing verifies it.
 */
@Serializable
data class ColonyBrainNote(
    val id: String,
    val sessionId: String,
    val worktreeId: String,
    val note: String,
    val timestamp: Long,
)

/**
 * The append-only shared scratchpad, one file per swarm session.
 *
 * `~/.boss/swarm-sessions/<sessionId>/brain.jsonl`, matching the ledger's own convention
 * (`~/.boss/mcp-calls.jsonl`): one JSON object per line, appended, never rewritten. A reader that
 * hits a truncated final line - the process died mid-write - skips it rather than failing the read,
 * because a scratchpad whose value is "the other worktree mentioned something" must not become
 * unreadable because of its last byte.
 *
 * **Scoped to a session on purpose.** Notes are not carried forward, aggregated, or searched across
 * sessions. A swarm session that ends takes its board with it; the durable record of what happened
 * is the MCP ledger, not this file.
 *
 * Nothing here is a transport. Agents reach it through the `colony_brain_*` MCP tools, so a write is
 * a governed, ledgered tool call like every other colony action.
 */
class ColonyBrain(
    private val rootDir: File = BossDirectories.resolve("swarm-sessions"),
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /**
     * Append a note and return it. Never throws: a scratchpad write that fails must not fail the
     * tool call that made it, the same rule the ledger follows.
     */
    @Suppress("ReturnCount") // Two input guards plus the write result; each exit is a distinct refusal.
    fun write(
        sessionId: String,
        worktreeId: String,
        note: String,
    ): ColonyBrainNote? {
        val trimmed = note.trim()
        if (trimmed.isEmpty()) return null
        // Resolved before the record is built, so a note we are not going to write is not minted.
        val file = fileFor(sessionId) ?: return null
        val timestamp = System.currentTimeMillis()
        val record =
            ColonyBrainNote(
                // Derived from the note's own content and write time. A negotiation message id is
                // derived from the negotiation arguments, which a note does not have, so reusing that
                // derivation here would give every note on every board the same id.
                id = ColonyProtocol.contentId("brain-", listOf(sessionId, worktreeId, trimmed, timestamp.toString())),
                sessionId = sessionId,
                worktreeId = worktreeId,
                note = trimmed,
                timestamp = timestamp,
            )
        return try {
            file.parentFile?.mkdirs()
            synchronized(this) {
                file.appendText(json.encodeToString(ColonyBrainNote.serializer(), record) + "\n")
            }
            record
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: Exception,
        ) {
            logger.warn(
                LogCategory.SYSTEM,
                "Colony brain write failed",
                mapOf("sessionId" to sessionId),
                error = failure,
            )
            null
        }
    }

    /** Every note on the session's board, oldest first, capped at [limit] of the most recent. */
    fun read(
        sessionId: String,
        limit: Int = DEFAULT_READ_LIMIT,
    ): List<ColonyBrainNote> {
        val file = fileFor(sessionId)?.takeIf { it.isFile } ?: return emptyList()
        return try {
            file
                .readLines()
                .asSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    runCatching { json.decodeFromString(ColonyBrainNote.serializer(), line) }.getOrNull()
                }.toList()
                .takeLast(limit.coerceIn(1, MAX_READ_LIMIT))
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: Exception,
        ) {
            logger.warn(
                LogCategory.SYSTEM,
                "Colony brain read failed",
                mapOf("sessionId" to sessionId),
                error = failure,
            )
            emptyList()
        }
    }

    /** Forget a session's board. Called when a swarm session ends; never called automatically. */
    fun clearSession(sessionId: String): Boolean = fileFor(sessionId)?.let { it.delete() } ?: false

    /**
     * The file for [sessionId], or null when the id is not one we are willing to turn into a path.
     *
     * This is the only place a caller-supplied string becomes a filesystem path, so it is the place
     * the traversal check lives: `../../` in a session id would otherwise write outside the sessions
     * root. An id that fails the check is refused, not sanitized - silently rewriting it would put
     * two different sessions on one board.
     */
    private fun fileFor(sessionId: String): File? {
        if (!isSafeSessionId(sessionId)) {
            logger.warn(LogCategory.SYSTEM, "Refusing unsafe colony brain session id", mapOf("sessionId" to sessionId))
            return null
        }
        return File(File(rootDir, sessionId), BRAIN_FILE_NAME)
    }

    companion object {
        const val BRAIN_FILE_NAME = "brain.jsonl"
        const val DEFAULT_READ_LIMIT = 50
        const val MAX_READ_LIMIT = 500

        private val logger = BossLogger.forComponent("ColonyBrain")

        /**
         * A session id is a name, not a path: same shape rule the workspace tools apply to ids, for
         * the same reason.
         */
        fun isSafeSessionId(sessionId: String): Boolean =
            sessionId.isNotBlank() &&
                sessionId.length <= 128 &&
                sessionId != "." &&
                sessionId != ".." &&
                sessionId.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() }
    }
}
