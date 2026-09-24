package ai.rever.boss.mcp.coordination

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * A shared board where agents say what they are working on, exposed as `mcp__boss__agent_*`.
 *
 * ## The problem
 *
 * BOSS is the harness that runs several agents at once: Claude Code in one pane, Codex in
 * another, on the same repository. Two of them given overlapping tasks will silently edit the
 * same file and the second write wins. Nothing in BOSS, or in any MCP server, tells either one
 * that the other exists.
 *
 * ## What this is, precisely
 *
 * An **advisory board**, three tools:
 *
 * - `agent_claim` announces what an agent is working on, with a TTL.
 * - `agent_peers` lists the other active agents and **flags file overlap**.
 * - `agent_release` retires a claim when the work is done.
 *
 * ## What it is not, and why
 *
 * MCP carries **no caller identity**. `McpOperationRecord` has no session id, no terminal pane
 * and no client name, so the host cannot tell which agent made a call. Every agent therefore
 * **declares its own name**, and nothing verifies it. Two consequences, both deliberate:
 *
 * - **Not a lock.** Nothing prevents an agent editing a file another agent claimed. A lock that
 *   nothing enforces is a lock that gets broken, and shipping one would promise more than the
 *   mechanism can deliver. Overlap is reported so an agent can decide; it is not refused.
 * - **Not a security boundary.** An agent can claim any name, including another agent's. This
 *   coordinates cooperating agents on one machine. It defends against nothing.
 *
 * Both are stated in the tool descriptions too, because the agent reading them is the one that
 * needs to know what the answer is worth.
 *
 * ## Governance posture
 *
 * `agent_claim` and `agent_release` **write shared state that other agents read**, so both
 * declare `readOnly = false` and inherit the host's ASK default. `agent_peers` only reads.
 * No `requiredPermissions`: the board holds task descriptions an agent wrote about itself, which
 * is less sensitive than the MCP ledger, and the server is loopback only.
 */
@Suppress("TooManyFunctions")
internal object CoordinationMcpToolProvider : McpToolProvider {
    const val AGENT_CLAIM: String = "agent_claim"
    const val AGENT_PEERS: String = "agent_peers"
    const val AGENT_RELEASE: String = "agent_release"

    private val json = Json { prettyPrint = false }

    override val providerId: String = "boss-coordination"

    /** Overridable so tests get a temp board instead of the real one. */
    internal var store: AgentClaimStore = AgentClaimStore()

    /** Overridable so tests control expiry without sleeping. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    override fun tools(): List<McpToolDefinition> = listOf(claimTool(), peersTool(), releaseTool())

    // ---------------------------------------------------------------- definitions

    private fun claimTool() =
        McpToolDefinition(
            name = AGENT_CLAIM,
            description =
                "Announce what you are working on so other agents in this BOSS can see it, and find out " +
                    "immediately whether anyone else has claimed the same files. Call this BEFORE you start " +
                    "editing, with the files you expect to touch. Re-calling replaces your previous claim. " +
                    "This is advisory: it does NOT lock anything and does not stop another agent editing the " +
                    "same file. You choose your own agent_id and nothing verifies it.",
            inputSchema =
                """
                {
                  "type": "object",
                  "properties": {
                    "agent_id": {
                      "type": "string",
                      "description": "A name for yourself, stable for this task. Letters, digits, dot, dash, underscore."
                    },
                    "task": { "type": "string", "description": "One line on what you are doing." },
                    "files": {
                      "type": "array", "items": { "type": "string" },
                      "description": "Files you expect to edit. Use paths spelled the same way other agents would."
                    },
                    "ttl_minutes": {
                      "type": "integer", "minimum": 1, "maximum": 240, "default": 30,
                      "description": "How long the claim lives if you do not release it."
                    }
                  },
                  "required": ["agent_id", "task"]
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handleClaim(args) },
            readOnly = false,
        )

    private fun peersTool() =
        McpToolDefinition(
            name = AGENT_PEERS,
            description =
                "List other agents currently working in this BOSS, what they said they are doing, and which " +
                    "of your claimed files they have also claimed. Call this when a task touches shared code, " +
                    "or before a wide refactor. Pass your agent_id to get overlap flagged against your own " +
                    "claim. Advisory only: an overlap is a warning to coordinate, not a refusal.",
            inputSchema =
                """
                {
                  "type": "object",
                  "properties": {
                    "agent_id": {
                      "type": "string",
                      "description": "Your own id, so overlap with your claim is computed. Omit to just list everyone."
                    }
                  }
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handlePeers(args) },
            readOnly = true,
        )

    private fun releaseTool() =
        McpToolDefinition(
            name = AGENT_RELEASE,
            description =
                "Retire your claim when the work is finished, so other agents stop seeing you as active on " +
                    "those files. Claims also expire on their own, so forgetting this is survivable rather " +
                    "than permanent.",
            inputSchema =
                """
                {
                  "type": "object",
                  "properties": {
                    "agent_id": { "type": "string", "description": "The id you claimed under." }
                  },
                  "required": ["agent_id"]
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handleRelease(args) },
            readOnly = false,
        )

    // ---------------------------------------------------------------- handlers

    private suspend fun handleClaim(args: McpToolArgs): McpToolResult {
        val agentId = AgentClaims.sanitizeAgentId(args.string("agent_id"))
        val task = AgentClaims.sanitizeTask(args.string("task"))
        validateClaim(agentId, task)?.let { return it }
        requireNotNull(agentId)

        val now = clock()
        val claim =
            AgentClaim(
                agentId = agentId,
                task = task,
                files = AgentClaims.sanitizeFiles(readFiles(args)),
                claimedAtMs = now,
                expiresAtMs = AgentClaims.expiryFor(now, args.int("ttl_minutes")),
            )

        val board = withContext(Dispatchers.IO) { store.update(now) { AgentClaims.upsert(it, claim, now) } }
        val overlaps = AgentClaims.overlapsFor(agentId, board, now)

        return ok(
            buildJsonObject {
                put("claimed", true)
                put("agent_id", agentId)
                put("files_claimed", claim.files.size)
                put("expires_in_minutes", AgentClaims.clampTtlMinutes(args.int("ttl_minutes")))
                putOverlaps(overlaps)
                put("advisory_note", ADVISORY_NOTE)
            },
        )
    }

    /** The refusal for a bad claim, or null when it is usable. Split out to keep one exit per path. */
    private fun validateClaim(
        agentId: String?,
        task: String,
    ): McpToolResult? =
        when {
            agentId == null -> error(BAD_AGENT_ID)
            task.isEmpty() -> error("task is required: one line describing what you are doing.")
            else -> null
        }

    private suspend fun handlePeers(args: McpToolArgs): McpToolResult {
        val requestedId = args.string("agent_id")?.let { AgentClaims.sanitizeAgentId(it) }
        val now = clock()
        val read = withContext(Dispatchers.IO) { store.read(now) }
        val peers = read.claims.filterNot { requestedId != null && it.agentId.equals(requestedId, ignoreCase = true) }
        val overlaps = requestedId?.let { AgentClaims.overlapsFor(it, read.claims, now) }.orEmpty()

        return ok(
            buildJsonObject {
                put("peer_count", peers.size)
                if (read.unreadable) put("board_unreadable", true)
                if (requestedId != null && read.claims.none { it.agentId.equals(requestedId, ignoreCase = true) }) {
                    put("your_claim_missing", true)
                    put("your_claim_note", "You have no active claim. Call agent_claim so peers can see you too.")
                }
                putJsonArray("peers") {
                    peers.forEach { peer ->
                        add(
                            buildJsonObject {
                                put("agent_id", peer.agentId)
                                put("task", peer.task)
                                put("claimed_at_ms", peer.claimedAtMs)
                                put("expires_at_ms", peer.expiresAtMs)
                                putJsonArray("files") { peer.files.forEach { file -> add(file) } }
                            },
                        )
                    }
                }
                putOverlaps(overlaps)
                put("advisory_note", ADVISORY_NOTE)
            },
        )
    }

    private suspend fun handleRelease(args: McpToolArgs): McpToolResult {
        val agentId = AgentClaims.sanitizeAgentId(args.string("agent_id")) ?: return error(BAD_AGENT_ID)
        val now = clock()
        val before = withContext(Dispatchers.IO) { store.read(now) }.claims
        val had = before.any { it.agentId.equals(agentId, ignoreCase = true) }
        withContext(Dispatchers.IO) { store.update(now) { AgentClaims.release(it, agentId, now) } }

        return ok(
            buildJsonObject {
                put("released", had)
                put("agent_id", agentId)
                if (!had) put("note", "No active claim under that id. It may already have expired.")
            },
        )
    }

    // ---------------------------------------------------------------- shared

    private fun JsonObjectBuilder.putOverlaps(overlaps: List<Overlap>) {
        put("overlap_count", overlaps.size)
        putJsonArray("overlaps") {
            overlaps.forEach { overlap ->
                add(
                    buildJsonObject {
                        put("peer_agent_id", overlap.peerAgentId)
                        put("peer_task", overlap.peerTask)
                        put("peer_claimed_at_ms", overlap.peerClaimedAtMs)
                        putJsonArray("shared_files") { overlap.sharedFiles.forEach { file -> add(file) } }
                    },
                )
            }
        }
        if (overlaps.isNotEmpty()) put("overlap_note", OVERLAP_NOTE)
    }

    /**
     * The `files` argument.
     *
     * `McpToolArgs` exposes only top-level scalars, so an array has to be parsed out of [raw]
     * by hand. A comma separated string is accepted too, because a model that has been told
     * "files" will sometimes send one, and refusing it would fail a call over an encoding
     * detail rather than anything the agent meant.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    internal fun readFiles(args: McpToolArgs): List<String> {
        val fromScalar = args.string("files")
        val parsed =
            try {
                val root = Json.parseToJsonElement(args.raw) as? JsonObject
                (root?.get("files") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
            } catch (t: Throwable) {
                null
            }
        return parsed
            ?: fromScalar?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()
    }

    private fun ok(payload: JsonObject) = McpToolResult(json.encodeToString(JsonObject.serializer(), payload))

    private fun error(message: String) = McpToolResult(message, isError = true)

    private const val BAD_AGENT_ID =
        "agent_id is required and must be letters, digits, dot, dash or underscore, up to 64 characters."

    private const val ADVISORY_NOTE =
        "Advisory only. Nothing here locks a file or stops another agent editing it, and agent ids are " +
            "self-declared and unverified. Treat an overlap as a reason to coordinate, not as a refusal."

    private const val OVERLAP_NOTE =
        "Another agent has claimed files you also claimed. Consider splitting the work, waiting, or leaving " +
            "those files alone. If you proceed, expect your edits and theirs to conflict."
}
