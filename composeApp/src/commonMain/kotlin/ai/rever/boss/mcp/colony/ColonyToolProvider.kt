package ai.rever.boss.mcp.colony

import ai.rever.boss.mcp.McpArgumentSanitizer
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * BOSS Colony: host-mediated agent-to-agent negotiation and a shared scratchpad.
 *
 * **Everything here is an ordinary governed MCP tool call.** There is no transport in this file, no
 * peer connection, no agent-to-agent channel, and no second policy or audit path: an agent proposes,
 * accepts, rejects, counters or hands off by calling a tool, and the existing registry, policy
 * engine and ledger decide and record it exactly as they do for `run_command`. The vocabulary of the
 * performatives is borrowed from agent-communication work in the same space, but this is not a
 * compliant ACP or A2A implementation and does not claim to be one.
 *
 * The tools are declared `readOnly = false` rather than relying on the mutating catalog alone, which
 * is the same belt-and-braces posture `WorkspaceMcpToolProvider` takes: the catalog is a name list,
 * and a name list is exactly the thing that goes stale. `colony_brain_read` is the one exception -
 * it reads the scratchpad and declares `readOnly = true`, so it is not gated.
 *
 * **What is durable.** Nothing in this object is. [store] holds the live threads a transition is
 * validated against, and losing it loses no history: every call above produced an
 * `McpOperationRecord`, and [ColonyLedgerReconstruction] rebuilds the same threads from the ledger
 * alone. That is the property a test pins, and it is why the handler validates against the store but
 * never treats it as the record.
 */
// One cohesive MCP tool provider: the seven tool definitions stay beside their handlers.
@Suppress("TooManyFunctions")
object ColonyToolProvider : McpToolProvider {
    override val providerId: String = "boss-colony"

    private val logger = BossLogger.forComponent("ColonyToolProvider")

    private val json = Json { encodeDefaults = true }

    /** The live negotiation threads. Not the record - see the class note. */
    internal val store = ColonyNegotiationStore()

    /** The per-session shared scratchpad. */
    internal val brain = ColonyBrain()

    /**
     * Test hook: the clock messages are stamped with.
     *
     * Epoch milliseconds from `System.currentTimeMillis`, deliberately the same clock the ledger
     * stamps `McpOperationRecord.timestamp` with, so a message and the record of the call that
     * created it order the same way when a thread is replayed.
     */
    internal var now: () -> Long = { System.currentTimeMillis() }

    /** Arguments every negotiation tool needs, per performative. */
    private val requiredArgs =
        mapOf(
            ColonyPerformative.PROPOSE to
                listOf(ColonyArgs.THREAD_ID, ColonyArgs.FROM_WORKTREE_ID, ColonyArgs.TO_WORKTREE_ID, ColonyArgs.TASK),
            ColonyPerformative.ACCEPT to
                listOf(
                    ColonyArgs.THREAD_ID,
                    ColonyArgs.PROPOSAL_ID,
                    ColonyArgs.FROM_WORKTREE_ID,
                    ColonyArgs.TO_WORKTREE_ID,
                ),
            ColonyPerformative.REJECT to
                listOf(
                    ColonyArgs.THREAD_ID,
                    ColonyArgs.PROPOSAL_ID,
                    ColonyArgs.FROM_WORKTREE_ID,
                    ColonyArgs.TO_WORKTREE_ID,
                    ColonyArgs.REASON,
                ),
            ColonyPerformative.COUNTER to
                listOf(
                    ColonyArgs.THREAD_ID,
                    ColonyArgs.PROPOSAL_ID,
                    ColonyArgs.FROM_WORKTREE_ID,
                    ColonyArgs.TO_WORKTREE_ID,
                    ColonyArgs.ALTERNATIVE_SCOPE,
                ),
            ColonyPerformative.HANDOFF to
                listOf(ColonyArgs.THREAD_ID, ColonyArgs.FROM_WORKTREE_ID, ColonyArgs.TO_WORKTREE_ID, ColonyArgs.TASK),
        )

    override fun tools(): List<McpToolDefinition> =
        listOf(
            proposeTool(),
            acceptTool(),
            rejectTool(),
            counterTool(),
            handoffTool(),
            brainWriteTool(),
            brainReadTool(),
        )

    // =========================================================================
    // Tool definitions
    // =========================================================================

    private fun proposeTool(): McpToolDefinition =
        McpToolDefinition(
            name = ColonyTools.PROPOSE,
            description =
                "Open or continue a BOSS Colony negotiation by proposing a task and the scope it " +
                    "claims. Addressed to one other worktree on the same host.",
            inputSchema =
                negotiationSchema(
                    extraProperties =
                        listOf(
                            ColonyArgs.TASK to "What the proposing worktree intends to do",
                            ColonyArgs.SCOPE to "Optional scope the proposal claims, e.g. a module or path prefix",
                        ),
                    required = requiredArgs.getValue(ColonyPerformative.PROPOSE),
                ),
            handler = McpToolHandler { args -> negotiate(ColonyTools.PROPOSE, args) },
            readOnly = false,
        )

    private fun acceptTool(): McpToolDefinition =
        McpToolDefinition(
            name = ColonyTools.ACCEPT,
            description = "Accept a proposal in a negotiation thread, moving the thread to accepted.",
            inputSchema =
                negotiationSchema(
                    extraProperties = emptyList(),
                    required = requiredArgs.getValue(ColonyPerformative.ACCEPT),
                ),
            handler = McpToolHandler { args -> negotiate(ColonyTools.ACCEPT, args) },
            readOnly = false,
        )

    private fun rejectTool(): McpToolDefinition =
        McpToolDefinition(
            name = ColonyTools.REJECT,
            description = "Reject a proposal in a negotiation thread, with the reason it was rejected.",
            inputSchema =
                negotiationSchema(
                    extraProperties = listOf(ColonyArgs.REASON to "Why the proposal was rejected"),
                    required = requiredArgs.getValue(ColonyPerformative.REJECT),
                ),
            handler = McpToolHandler { args -> negotiate(ColonyTools.REJECT, args) },
            readOnly = false,
        )

    private fun counterTool(): McpToolDefinition =
        McpToolDefinition(
            name = ColonyTools.COUNTER,
            description =
                "Counter a proposal with an alternative scope. A counter is a new proposal in the " +
                    "same thread rather than an edit of the original, so the thread stays a replayable " +
                    "sequence of messages.",
            inputSchema =
                negotiationSchema(
                    extraProperties =
                        listOf(ColonyArgs.ALTERNATIVE_SCOPE to "The scope this worktree would accept instead"),
                    required = requiredArgs.getValue(ColonyPerformative.COUNTER),
                ),
            handler = McpToolHandler { args -> negotiate(ColonyTools.COUNTER, args) },
            readOnly = false,
        )

    private fun handoffTool(): McpToolDefinition =
        McpToolDefinition(
            name = ColonyTools.HANDOFF,
            description =
                "Hand accepted or in-progress work to another worktree, carrying the task and the " +
                    "context needed to continue it. Requires operator approval by default, because it " +
                    "moves work between worktrees rather than only recording a decision.",
            inputSchema =
                negotiationSchema(
                    extraProperties =
                        listOf(
                            ColonyArgs.TASK to "The work being handed over",
                            ColonyArgs.CONTEXT to "Free-form context the receiving worktree needs",
                        ),
                    required = requiredArgs.getValue(ColonyPerformative.HANDOFF),
                ),
            handler = McpToolHandler { args -> negotiate(ColonyTools.HANDOFF, args) },
            readOnly = false,
        )

    private fun brainWriteTool(): McpToolDefinition =
        McpToolDefinition(
            name = ColonyTools.BRAIN_WRITE,
            description =
                "Append a note to this swarm session's shared scratchpad, so other worktrees can read " +
                    "what this one found. Append-only and session-scoped: notes are never edited, and " +
                    "they do not survive the session.",
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {
                        "sessionId": { "type": "string", "description": "Swarm session the board belongs to" },
                        "worktreeId": { "type": "string", "description": "Worktree making the assertion" },
                        "note": { "type": "string", "description": "What to tell the other worktrees" }
                    },
                    "required": ["sessionId", "worktreeId", "note"]
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> brainWrite(args) },
            readOnly = false,
        )

    private fun brainReadTool(): McpToolDefinition =
        McpToolDefinition(
            name = ColonyTools.BRAIN_READ,
            description =
                "Read the most recent notes on this swarm session's shared scratchpad, oldest first.",
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {
                        "sessionId": { "type": "string", "description": "Swarm session the board belongs to" },
                        "limit": { "type": "string", "description": "How many of the newest notes to return" }
                    },
                    "required": ["sessionId"]
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> brainRead(args) },
            readOnly = true,
        )

    /**
     * The shared part of a negotiation tool's schema: the thread, the two worktrees, and the
     * proposal being answered, plus whatever is specific to the performative.
     *
     * `proposalId` is offered to every performative but only required by the answering ones - a
     * proposal is its own proposal, so requiring it there would be a field the caller cannot fill in
     * before the message it names exists.
     */
    private fun negotiationSchema(
        extraProperties: List<Pair<String, String>>,
        required: List<String>,
    ): String =
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                val properties =
                    listOf(
                        ColonyArgs.THREAD_ID to "Negotiation thread this message belongs to",
                        ColonyArgs.FROM_WORKTREE_ID to "Worktree sending this message",
                        ColonyArgs.TO_WORKTREE_ID to "Worktree this message is addressed to",
                        ColonyArgs.PROPOSAL_ID to "The proposal this message answers",
                    ) + extraProperties
                properties.forEach { (name, description) ->
                    putJsonObject(name) {
                        put("type", "string")
                        put("description", description)
                    }
                }
            }
            putJsonArray("required") { required.forEach { element -> add(JsonPrimitive(element)) } }
        }.toString()

    // =========================================================================
    // Handlers
    // =========================================================================

    /**
     * Record one negotiation message, refusing any transition the protocol does not allow.
     *
     * A refusal is returned as an error and *not* appended, which is what keeps a thread meaningful:
     * a second accept on an already-accepted thread is a mistake worth surfacing, not a message. The
     * call is still recorded in the ledger with its thread attribution, and
     * [ColonyLedgerReconstruction] skips failed records for exactly that reason - the ledger records
     * attempts, and only a call that succeeded created a message.
     */
    // Each guard rejects a different malformed call, so they stay as separate early returns.
    @Suppress("ReturnCount")
    private fun negotiate(
        toolName: String,
        args: McpToolArgs,
    ): McpToolResult {
        val performative =
            ColonyProtocol.performativeFor(toolName)
                ?: return failure("'$toolName' is not a colony negotiation tool")

        val idArgs = idArgs(args)
        val missing = requiredArgs.getValue(performative).filter { idArgs[it].isNullOrBlank() }
        if (missing.isNotEmpty()) {
            return failure("Missing required argument(s) for $toolName: ${missing.joinToString(", ")}")
        }

        val threadId = idArgs.getValue(ColonyArgs.THREAD_ID)
        val current = store.state(threadId)
        if (!ColonyProtocol.isLegal(current, performative)) {
            return failure(
                "Illegal ${performative.name.lowercase()} for thread '$threadId': its state is $current, " +
                    "which does not accept one",
            )
        }

        // An answered proposal the live store cannot find is only an error when the store knows this
        // thread at all. The store is in-memory, so after a restart it knows nothing and refusing
        // would break a negotiation the ledger still holds; when it does know the thread, an unknown
        // proposal id is a real mistake rather than a gap in what this process happens to remember.
        val answered = idArgs[ColonyArgs.PROPOSAL_ID]
        val known = store.messages(threadId)
        if (answered != null && known.isNotEmpty() && store.proposalOf(threadId, answered) == null) {
            return failure("Thread '$threadId' has no message '$answered' to answer")
        }

        val message = messageFor(toolName, performative, idArgs)
        val thread = store.append(message)
        logger.info(
            LogCategory.SYSTEM,
            "Colony negotiation message recorded",
            mapOf(
                "tool" to toolName,
                "threadId" to threadId,
                "messageId" to message.messageId,
                "state" to thread.state.name,
            ),
        )
        return McpToolResult(
            buildJsonObject {
                put("threadId", thread.threadId)
                put("messageId", message.messageId)
                put("performative", performative.name)
                put("state", thread.state.name)
                put("messages", thread.messages.size)
            }.toString(),
        )
    }

    @Suppress("ReturnCount") // One guard per required argument, then the write itself.
    private fun brainWrite(args: McpToolArgs): McpToolResult {
        val sessionId =
            args.string("sessionId")?.takeIf { it.isNotBlank() }
                ?: return failure("Missing required argument for ${ColonyTools.BRAIN_WRITE}: sessionId")
        val worktreeId =
            args.string("worktreeId")?.takeIf { it.isNotBlank() }
                ?: return failure("Missing required argument for ${ColonyTools.BRAIN_WRITE}: worktreeId")
        val note =
            args.string("note")?.takeIf { it.isNotBlank() }
                ?: return failure("Missing required argument for ${ColonyTools.BRAIN_WRITE}: note")
        if (!ColonyBrain.isSafeSessionId(sessionId)) {
            return failure("'$sessionId' is not a usable swarm session id")
        }
        val written =
            brain.write(sessionId, worktreeId, note)
                ?: return failure("Could not append the note to session '$sessionId'")
        return McpToolResult(
            buildJsonObject {
                put("noteId", written.id)
                put("sessionId", written.sessionId)
                put("worktreeId", written.worktreeId)
                put("timestamp", written.timestamp)
            }.toString(),
        )
    }

    @Suppress("ReturnCount") // One guard per required argument, then the read itself.
    private fun brainRead(args: McpToolArgs): McpToolResult {
        val sessionId =
            args.string("sessionId")?.takeIf { it.isNotBlank() }
                ?: return failure("Missing required argument for ${ColonyTools.BRAIN_READ}: sessionId")
        if (!ColonyBrain.isSafeSessionId(sessionId)) {
            return failure("'$sessionId' is not a usable swarm session id")
        }
        val limit = args.string("limit")?.toIntOrNull() ?: ColonyBrain.DEFAULT_READ_LIMIT
        return McpToolResult(json.encodeToString(brain.read(sessionId, limit)))
    }

    // =========================================================================
    // Message construction
    // =========================================================================

    /**
     * The message a successful call creates.
     *
     * The shape mirrors `ColonyLedgerReconstruction.toMessage` field for field, and both derive
     * [ColonyMessage.messageId] from the same sanitized arguments, which is what makes the live
     * thread and the thread rebuilt from the ledger the same thread rather than two that merely
     * resemble each other.
     */
    private fun messageFor(
        toolName: String,
        performative: ColonyPerformative,
        idArgs: Map<String, String>,
    ): ColonyMessage =
        ColonyMessage(
            messageId = ColonyProtocol.messageIdFor(toolName, idArgs),
            threadId = idArgs.getValue(ColonyArgs.THREAD_ID),
            performative = performative,
            fromWorktreeId = idArgs.getValue(ColonyArgs.FROM_WORKTREE_ID),
            toWorktreeId = idArgs.getValue(ColonyArgs.TO_WORKTREE_ID),
            timestamp = now(),
            // A proposal answers nothing, including when it is a counter's follow-up: the counter is
            // itself the message that answers the proposal before it.
            proposalId = if (performative == ColonyPerformative.PROPOSE) null else idArgs[ColonyArgs.PROPOSAL_ID],
            task = idArgs[ColonyArgs.TASK]?.takeIf { it.isNotBlank() },
            scope = idArgs[ColonyArgs.SCOPE]?.takeIf { it.isNotBlank() },
            reason = idArgs[ColonyArgs.REASON]?.takeIf { it.isNotBlank() },
            alternativeScope = idArgs[ColonyArgs.ALTERNATIVE_SCOPE]?.takeIf { it.isNotBlank() },
            context = idArgs[ColonyArgs.CONTEXT]?.takeIf { it.isNotBlank() },
        )

    /**
     * The arguments a message id is derived from, sanitized exactly as the ledger sanitizes them
     * before it persists the call.
     *
     * Two things depend on this. Only the arguments the caller actually supplied are included -
     * absent ones are dropped rather than defaulted, because the sanitizer renders a null as the
     * literal string `"null"` and a thread id derived from that would be wrong rather than merely
     * ugly. And the sanitizing step is what keeps the two id spaces identical: a `task` or `context`
     * that happens to read like a credential is redacted on its way to disk, and without the same
     * redaction here the live store would name a message the ledger names differently.
     */
    private fun idArgs(args: McpToolArgs): Map<String, String> =
        McpArgumentSanitizer.sanitize(
            ColonyArgs.all.mapNotNull { name -> args.string(name)?.let { name to it } }.toMap(),
        )

    private fun failure(message: String): McpToolResult = McpToolResult(message, isError = true)
}
