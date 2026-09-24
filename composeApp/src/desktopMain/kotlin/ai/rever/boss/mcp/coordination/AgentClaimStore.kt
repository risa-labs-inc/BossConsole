package ai.rever.boss.mcp.coordination

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private val logger = BossLogger.forComponent("AgentCoordination")

@Serializable
internal data class ClaimBoard(
    val claims: List<AgentClaim> = emptyList(),
)

/** What a read found, including whether the file had to be discarded. */
internal data class BoardRead(
    val claims: List<AgentClaim>,
    /** True when the file existed but could not be parsed, so it was treated as empty. */
    val unreadable: Boolean,
)

/**
 * The shared claim board on disk.
 *
 * `~/.boss/agent-claims.json`, so it survives a restart and is visible to every agent on the
 * machine rather than only to one BOSS window.
 *
 * ## Why a file rather than memory
 *
 * Two agents in one BOSS process would be served by a singleton, and that was the first design.
 * A file is used instead because the interesting case is not guaranteed to be one process: BOSS
 * can be restarted while an agent is mid task, and an operator can reasonably run a second BOSS.
 * A file costs a few milliseconds per call and removes the question.
 *
 * ## Writes are atomic
 *
 * Written to a sibling temp file and moved into place, so a crash between open and flush cannot
 * leave a truncated board that every later read rejects. `outputStream()` truncates on open, so
 * writing in place would destroy a good board the instant the write began.
 *
 * ## An unreadable board is empty, not fatal
 *
 * This is coordination advice, not governance. If the file is corrupt the honest answer is "I
 * cannot see any peers", reported as [BoardRead.unreadable] so the caller can say so, rather than
 * failing a tool call an agent is using to avoid a collision.
 */
internal class AgentClaimStore(
    private val fileProvider: () -> File = { BossDirectories.resolve(FILE_NAME) },
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private val lock = Any()

    @Suppress("TooGenericExceptionCaught")
    fun read(nowMs: Long): BoardRead =
        synchronized(lock) {
            val file = fileProvider()
            if (!file.isFile) return BoardRead(emptyList(), unreadable = false)
            return try {
                val board = json.decodeFromString(ClaimBoard.serializer(), file.readText())
                BoardRead(AgentClaims.active(board.claims, nowMs), unreadable = false)
            } catch (t: Throwable) {
                logger.warn(LogCategory.SYSTEM, "Agent claim board could not be read", error = t)
                BoardRead(emptyList(), unreadable = true)
            }
        }

    /**
     * Applies [mutate] to the active board and persists the result.
     *
     * Read, change and write happen under one lock so two agents claiming at the same moment
     * cannot each write a board that omits the other. This guards concurrency **within one JVM**
     * only; two BOSS processes racing can still lose a claim, which is acceptable for advisory
     * data and is recorded in the docs rather than solved with a lock file.
     */
    fun update(
        nowMs: Long,
        mutate: (List<AgentClaim>) -> List<AgentClaim>,
    ): List<AgentClaim> =
        synchronized(lock) {
            val current = read(nowMs).claims
            val next = mutate(current)
            write(next)
            next
        }

    @Suppress("TooGenericExceptionCaught")
    private fun write(claims: List<AgentClaim>) {
        val file = fileProvider()
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json.encodeToString(ClaimBoard.serializer(), ClaimBoard(claims)))
            moveIntoPlace(temp, file)
        } catch (t: Throwable) {
            // A board that cannot be written is a coordination aid that is not working, which is
            // worth a log line and is not worth failing the agent's call over.
            logger.warn(LogCategory.SYSTEM, "Agent claim board could not be written", error = t)
        }
    }

    private fun moveIntoPlace(
        temp: File,
        target: File,
    ) {
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            // Some Windows filesystems refuse an atomic move across the same directory. A plain
            // replace is still better than writing in place, which truncates on open.
            logger.debug(LogCategory.SYSTEM, "Atomic move unavailable, falling back", mapOf("reason" to e.message))
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    internal companion object {
        const val FILE_NAME: String = "agent-claims.json"
    }
}
