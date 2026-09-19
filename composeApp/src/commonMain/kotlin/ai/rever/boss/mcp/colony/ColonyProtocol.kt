package ai.rever.boss.mcp.colony

import kotlinx.serialization.Serializable

/**
 * The performatives BOSS Colony agents exchange.
 *
 * Borrowed in vocabulary only from agent-communication work in the same space (ACP / A2A): this is
 * not a compliant implementation of either, there is no wire protocol here, and no message is ever
 * delivered agent-to-agent. Every one of these is carried by a host-mediated MCP tool call that the
 * policy engine and the ledger already govern (see [ColonyTools]).
 */
enum class ColonyPerformative {
    PROPOSE,
    ACCEPT,
    REJECT,
    COUNTER,
    HANDOFF,
}

/**
 * Where a proposal stands.
 *
 * The lifecycle is deliberately linear and small: `PROPOSED -> ACCEPTED | REJECTED | COUNTERED`,
 * then `ACCEPTED -> IN_PROGRESS -> DONE | HANDED_OFF`. A counter opens a *new* proposal in the same
 * thread rather than mutating the old one, which is what makes a thread reconstructable from
 * append-only records: nothing is ever rewritten, so replaying the messages in order reproduces the
 * state without needing the live store.
 *
 * Two states are reachable only from outside the negotiation tools. Nothing in the tool set reports
 * that accepted work started ([IN_PROGRESS]) or finished ([DONE]), so a thread ends at accepted,
 * rejected or handed off unless a caller reports otherwise. They are declared because they are part
 * of the lifecycle an operator reasons about and because a handoff is legal from [IN_PROGRESS] as
 * well as from [ACCEPTED]; minting a `colony_start`/`colony_done` pair to reach them would have been
 * a wider tool surface than the feature asks for.
 */
enum class ColonyLifecycle {
    PROPOSED,
    ACCEPTED,
    REJECTED,
    COUNTERED,
    IN_PROGRESS,
    DONE,
    HANDED_OFF,
    ;

    /** Lifecycle states from which no further transition is legal. */
    val isTerminal: Boolean get() = this == DONE || this == REJECTED || this == HANDED_OFF
}

/** One negotiated message. Immutable, so a thread is a list of these and nothing more. */
@Serializable
data class ColonyMessage(
    val messageId: String,
    val threadId: String,
    val performative: ColonyPerformative,
    val fromWorktreeId: String,
    val toWorktreeId: String,
    val timestamp: Long,
    /** The proposal this message answers. Null only on [ColonyPerformative.PROPOSE]. */
    val proposalId: String? = null,
    val task: String? = null,
    val scope: String? = null,
    val reason: String? = null,
    val alternativeScope: String? = null,
    /** Free-form handoff payload. Recorded in the ledger's sanitized args like every other tool arg. */
    val context: String? = null,
)

/** A negotiation thread: the messages in the order they were accepted, and the state they imply. */
@Serializable
data class ColonyThread(
    val threadId: String,
    val messages: List<ColonyMessage>,
) {
    val state: ColonyLifecycle get() = ColonyProtocol.stateOf(messages)
}

/**
 * The Colony protocol: the legal transitions, and the identity rules that let the ledger attribute a
 * record to a message without the tool handler having to hand its own id back to the registry.
 *
 * That second part is the load-bearing one. The registry writes the `McpOperationRecord` for every
 * invocation, and it only ever sees the tool name and the arguments, never the handler's return
 * value. So the id of the message a call *creates* has to be a pure function of what the call was,
 * and both sides compute it from [messageIdFor]. Deriving it from the args is also what makes a
 * retried call idempotent: the same request names the same message twice.
 */
object ColonyProtocol {
    /** The `colony_*` tool names, in the order they are registered. */
    val toolNames: List<String> =
        listOf(
            ColonyTools.PROPOSE,
            ColonyTools.ACCEPT,
            ColonyTools.REJECT,
            ColonyTools.COUNTER,
            ColonyTools.HANDOFF,
        )

    /** Tool name to performative, or null for anything outside the colony namespace. */
    fun performativeFor(toolName: String): ColonyPerformative? =
        when (toolName) {
            ColonyTools.PROPOSE -> ColonyPerformative.PROPOSE
            ColonyTools.ACCEPT -> ColonyPerformative.ACCEPT
            ColonyTools.REJECT -> ColonyPerformative.REJECT
            ColonyTools.COUNTER -> ColonyPerformative.COUNTER
            ColonyTools.HANDOFF -> ColonyPerformative.HANDOFF
            else -> null
        }

    /**
     * The id of the message a call creates, derived from the call alone.
     *
     * Stable across retries, and distinct across distinct calls: every field that identifies the
     * message is folded in, and the result is truncated rather than hashed for collision resistance
     * (a negotiation thread is a handful of messages, not a global namespace).
     */
    fun messageIdFor(
        toolName: String,
        args: Map<String, String>,
    ): String =
        contentId(
            "colony-",
            listOf(
                toolName,
                args[ColonyArgs.THREAD_ID].orEmpty(),
                args[ColonyArgs.PROPOSAL_ID].orEmpty(),
                args[ColonyArgs.FROM_WORKTREE_ID].orEmpty(),
                args[ColonyArgs.TO_WORKTREE_ID].orEmpty(),
                args[ColonyArgs.TASK].orEmpty(),
                args[ColonyArgs.SCOPE].orEmpty(),
                args[ColonyArgs.ALTERNATIVE_SCOPE].orEmpty(),
                args[ColonyArgs.REASON].orEmpty(),
                args[ColonyArgs.CONTEXT].orEmpty(),
            ),
        )

    /**
     * A stable id for [parts], prefixed with [prefix].
     *
     * The single derivation used by both the negotiation messages and the brain notes, so an id is
     * reproducible from the content that produced it and a retry of the same call names the same
     * message. A NUL separator, because any of these parts can contain a space or a dash.
     */
    fun contentId(
        prefix: String,
        parts: List<String>,
    ): String = prefix + digest(parts.joinToString("\u0000")).take(16)

    /**
     * The state a thread is in, computed from its messages rather than stored.
     *
     * Computed on purpose: a stored state is a second source of truth that can disagree with the
     * messages, and the whole point of the ledger is that the messages are the record.
     */
    fun stateOf(messages: List<ColonyMessage>): ColonyLifecycle {
        val last = messages.lastOrNull() ?: return ColonyLifecycle.PROPOSED
        return when (last.performative) {
            ColonyPerformative.PROPOSE -> ColonyLifecycle.PROPOSED
            ColonyPerformative.ACCEPT -> ColonyLifecycle.ACCEPTED
            ColonyPerformative.REJECT -> ColonyLifecycle.REJECTED
            ColonyPerformative.COUNTER -> ColonyLifecycle.COUNTERED
            ColonyPerformative.HANDOFF -> ColonyLifecycle.HANDED_OFF
        }
    }

    /**
     * Whether [performative] is a legal answer to the state the thread is already in.
     *
     * A proposal is legal from an empty thread, which [stateOf] reports as [ColonyLifecycle.PROPOSED],
     * or as a counter's follow-up. A handoff moves accepted work to another worktree, so it is not a
     * way to answer a proposal and is not legal before one has been accepted.
     */
    fun isLegal(
        current: ColonyLifecycle,
        performative: ColonyPerformative,
    ): Boolean =
        when (performative) {
            ColonyPerformative.PROPOSE -> current == ColonyLifecycle.PROPOSED || current == ColonyLifecycle.COUNTERED
            ColonyPerformative.ACCEPT -> current == ColonyLifecycle.PROPOSED || current == ColonyLifecycle.COUNTERED
            ColonyPerformative.REJECT -> current == ColonyLifecycle.PROPOSED || current == ColonyLifecycle.COUNTERED
            ColonyPerformative.COUNTER -> current == ColonyLifecycle.PROPOSED
            ColonyPerformative.HANDOFF -> current == ColonyLifecycle.ACCEPTED || current == ColonyLifecycle.IN_PROGRESS
        }
}

/** The `colony_*` tool names, shared by the provider, the catalog and the protocol. */
object ColonyTools {
    const val PROPOSE = "colony_propose"
    const val ACCEPT = "colony_accept"
    const val REJECT = "colony_reject"
    const val COUNTER = "colony_counter"
    const val HANDOFF = "colony_handoff"

    /**
     * The shared-brain tools. Not part of the negotiation protocol, but they are how an agent
     * reaches the scratchpad at all - there is no other agent-facing surface - and a write is a
     * mutation, so they belong in the catalog with the rest.
     */
    const val BRAIN_WRITE = "colony_brain_write"
    const val BRAIN_READ = "colony_brain_read"

    /** Every colony tool, for the mutating catalog and for policy defaults. */
    val all: Set<String> = setOf(PROPOSE, ACCEPT, REJECT, COUNTER, HANDOFF, BRAIN_WRITE, BRAIN_READ)

    /** The colony tools that negotiate. The two brain tools are the only ones that do not. */
    val negotiating: Set<String> = setOf(PROPOSE, ACCEPT, REJECT, COUNTER, HANDOFF)
}

/** The argument names the colony tools accept, shared so the registry and handlers cannot drift. */
object ColonyArgs {
    const val THREAD_ID = "threadId"
    const val PROPOSAL_ID = "proposalId"
    const val MESSAGE_ID = "messageId"
    const val FROM_WORKTREE_ID = "fromWorktreeId"
    const val TO_WORKTREE_ID = "toWorktreeId"
    const val TASK = "task"
    const val SCOPE = "scope"
    const val REASON = "reason"
    const val ALTERNATIVE_SCOPE = "alternativeScope"
    const val CONTEXT = "context"

    /**
     * Every argument name above, in declaration order.
     *
     * Read into the map a message id is derived from, so an id depends on a fixed, named set of
     * arguments rather than on whatever keys the caller happened to send. The brain tools' own
     * arguments (`sessionId`, `worktreeId`, `note`, `limit`) are deliberately absent: they identify
     * a note, not a negotiation message.
     */
    val all: List<String> =
        listOf(
            THREAD_ID,
            PROPOSAL_ID,
            MESSAGE_ID,
            FROM_WORKTREE_ID,
            TO_WORKTREE_ID,
            TASK,
            SCOPE,
            REASON,
            ALTERNATIVE_SCOPE,
            CONTEXT,
        )
}

/**
 * What the ledger should attribute a colony invocation to.
 *
 * `null` for a non-colony tool, so the registry's call site stays a one-liner and no non-colony
 * record grows a field it has no meaning for.
 */
data class ColonyLedgerAttribution(
    val threadId: String,
    val messageId: String,
) {
    companion object {
        /**
         * Attribution for [toolName] with [args], or null when this is not a colony call.
         *
         * A colony call with no thread id is not attributed rather than attributed to a blank
         * thread: a record grouped under `""` would silently join every malformed call into one
         * thread, which is worse than not grouping it at all. The handler refuses such a call, so
         * the record still exists - it just carries no thread claim.
         */
        fun from(
            toolName: String,
            args: Map<String, String>,
        ): ColonyLedgerAttribution? =
            args[ColonyArgs.THREAD_ID]
                ?.takeIf { it.isNotBlank() && ColonyProtocol.performativeFor(toolName) != null }
                ?.let { threadId -> ColonyLedgerAttribution(threadId, ColonyProtocol.messageIdFor(toolName, args)) }
    }
}

private fun digest(text: String): String =
    java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
