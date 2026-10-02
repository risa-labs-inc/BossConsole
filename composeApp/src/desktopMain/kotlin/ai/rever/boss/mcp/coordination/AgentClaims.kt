package ai.rever.boss.mcp.coordination

import kotlinx.serialization.Serializable

/**
 * One agent's declaration of what it is working on.
 *
 * [files] keeps the paths exactly as the agent wrote them, because that is what reads back most
 * usefully to a human or another agent. Matching is done on [fileKeys] instead, so reporting and
 * comparison can differ without either being wrong.
 */
@Serializable
internal data class AgentClaim(
    val agentId: String,
    val task: String,
    val files: List<String>,
    val claimedAtMs: Long,
    val expiresAtMs: Long,
) {
    /** Normalised forms used for overlap comparison only. Never shown to a caller. */
    val fileKeys: Set<String>
        get() = files.mapNotNullTo(mutableSetOf()) { AgentClaims.pathKey(it) }

    fun isActive(nowMs: Long): Boolean = nowMs < expiresAtMs
}

/** Another agent working on at least one of the same files. */
internal data class Overlap(
    val peerAgentId: String,
    val peerTask: String,
    val sharedFiles: List<String>,
    val peerClaimedAtMs: Long,
)

/**
 * The rules behind the agent coordination tools.
 *
 * Pure: no clock of its own, no file, no registry. Every decision here is a judgement call about
 * a shared advisory board, and judgement calls belong somewhere they can be tested directly.
 *
 * ## What this is honestly not
 *
 * MCP carries **no caller identity**. `McpOperationRecord` has no session id, no terminal pane and
 * no client name, so the host cannot tell which agent made a call. Every agent therefore
 * **declares its own name**, and nothing verifies that declaration. Two consequences follow and
 * both are deliberate:
 *
 * - This is an **advisory board, not a lock.** Nothing prevents an agent from editing a file
 *   another agent claimed. A lock that nothing enforces is a lock that will be broken, and
 *   presenting one would promise more than the mechanism can deliver.
 * - An agent can claim any name, including another agent's. This is a coordination aid between
 *   cooperating agents on one machine, not a security boundary.
 */
internal object AgentClaims {
    /** How long a claim lives if the agent does not say. */
    const val DEFAULT_TTL_MINUTES: Int = 30

    /** Ceiling on a claim's life, so a crashed agent cannot hold a claim forever. */
    const val MAX_TTL_MINUTES: Int = 240

    const val MIN_TTL_MINUTES: Int = 1

    /** Bounds, so one agent cannot fill the board or the payload. */
    const val MAX_AGENTS: Int = 50
    const val MAX_FILES_PER_CLAIM: Int = 100
    const val MAX_AGENT_ID_LENGTH: Int = 64
    const val MAX_TASK_LENGTH: Int = 300
    const val MAX_PATH_LENGTH: Int = 400

    private const val MILLIS_PER_MINUTE = 60_000L

    /** Letters, digits and the separators a sensible agent name uses. No paths, no spaces. */
    private val AGENT_ID_SHAPE = Regex("""[A-Za-z0-9][A-Za-z0-9._\-]{0,63}""")

    /** A usable agent id, or null. Trimmed first so a stray space is not an error. */
    fun sanitizeAgentId(raw: String?): String? = raw?.trim()?.takeIf { AGENT_ID_SHAPE.matches(it) }

    fun clampTtlMinutes(requested: Int?): Int {
        // Block body: ktlintFormat joins to 140 columns, detekt rejects above 120.
        val value = requested ?: DEFAULT_TTL_MINUTES
        return value.coerceIn(MIN_TTL_MINUTES, MAX_TTL_MINUTES)
    }

    /** Takes a nullable TTL because it clamps anyway: an absent value is the default, not an error. */
    fun expiryFor(
        nowMs: Long,
        ttlMinutes: Int?,
    ): Long = nowMs + clampTtlMinutes(ttlMinutes) * MILLIS_PER_MINUTE

    /**
     * The comparison key for a path, or null when the path is unusable.
     *
     * Deliberately **over matches rather than under matches**, and the direction is the whole
     * decision. A missed overlap is two agents editing one file believing they are alone, which
     * is the failure this tool exists to prevent. A false overlap is one agent double checking
     * something it did not need to. The second is much cheaper, so:
     *
     * - separators are normalised, since `src\Foo.kt` and `src/Foo.kt` are one file
     * - a leading `./` is dropped
     * - repeated slashes collapse
     * - comparison is **case insensitive**, because Windows and macOS default to case insensitive
     *   filesystems, and treating `Foo.kt` and `foo.kt` as different there would miss real
     *   collisions. On Linux this can pair two genuinely different files, which is the cheap
     *   direction of wrong.
     *
     * What it does NOT do is resolve a path against the project directory, follow symlinks or
     * canonicalise. `src/Foo.kt` and `/home/me/proj/src/Foo.kt` are the same file and will not
     * match. Doing better needs a project root the tool is not given and a filesystem hit per
     * path; agents are told in the tool description to use consistent paths.
     */
    fun pathKey(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.length > MAX_PATH_LENGTH) return null
        return trimmed
            .replace('\\', '/')
            .replace(Regex("/{2,}"), "/")
            .removePrefix("./")
            .trimEnd('/')
            .lowercase()
            .takeIf { it.isNotEmpty() }
    }

    /** Accepted, de-duplicated file paths in the order the agent gave them. */
    fun sanitizeFiles(raw: List<String>): List<String> {
        val seenKeys = mutableSetOf<String>()
        return raw
            .asSequence()
            .map { it.trim() }
            .filter { pathKey(it) != null }
            .filter { seenKeys.add(pathKey(it)!!) }
            .take(MAX_FILES_PER_CLAIM)
            .toList()
    }

    fun sanitizeTask(raw: String?): String = raw?.trim().orEmpty().take(MAX_TASK_LENGTH)

    /** Claims that have not expired, newest claim first. */
    fun active(
        claims: List<AgentClaim>,
        nowMs: Long,
    ): List<AgentClaim> = claims.filter { it.isActive(nowMs) }.sortedByDescending { it.claimedAtMs }

    /**
     * Replaces [claim]'s agent on the board and prunes.
     *
     * An agent id appears at most once: a second claim from the same agent is a new statement of
     * what it is doing, not an additional one. Expired entries are dropped on every write, which
     * is what makes a crashed agent's claim disappear without anyone cleaning up after it.
     *
     * If the board is full, the oldest claim is evicted, so a runaway producing new agent ids
     * cannot wedge the board permanently.
     */
    fun upsert(
        existing: List<AgentClaim>,
        claim: AgentClaim,
        nowMs: Long,
    ): List<AgentClaim> {
        val kept =
            active(existing, nowMs)
                .filterNot { it.agentId.equals(claim.agentId, ignoreCase = true) }
        val withNew = kept + claim
        return if (withNew.size <= MAX_AGENTS) {
            withNew
        } else {
            withNew.sortedByDescending { it.claimedAtMs }.take(MAX_AGENTS)
        }
    }

    fun release(
        existing: List<AgentClaim>,
        agentId: String,
        nowMs: Long,
    ): List<AgentClaim> = active(existing, nowMs).filterNot { it.agentId.equals(agentId, ignoreCase = true) }

    /**
     * Other active agents sharing at least one file with [agentId].
     *
     * An agent never overlaps with itself, and an agent that claimed no files overlaps with
     * nobody: claiming a task with no files is a legitimate "I am busy on something" that should
     * not collide with everyone.
     */
    fun overlapsFor(
        agentId: String,
        claims: List<AgentClaim>,
        nowMs: Long,
    ): List<Overlap> {
        val mine = active(claims, nowMs).firstOrNull { it.agentId.equals(agentId, ignoreCase = true) }
        // No claim at all, or a claim naming no files: either way there is nothing to collide on.
        val myKeys = mine?.fileKeys.orEmpty()
        if (myKeys.isEmpty()) return emptyList()

        return active(claims, nowMs)
            .filterNot { it.agentId.equals(agentId, ignoreCase = true) }
            .mapNotNull { peer ->
                val shared = peer.files.filter { pathKey(it) in myKeys }
                if (shared.isEmpty()) {
                    null
                } else {
                    Overlap(peer.agentId, peer.task, shared, peer.claimedAtMs)
                }
            }.sortedByDescending { it.sharedFiles.size }
    }
}
