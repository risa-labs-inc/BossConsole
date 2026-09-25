package ai.rever.boss.mcp.colony

import ai.rever.boss.mcp.McpOperationRecord
import java.util.concurrent.ConcurrentHashMap

/**
 * The live negotiation threads of this process.
 *
 * In memory on purpose: it holds what the handlers need to *validate* a transition, and nothing more.
 * The durable record of a negotiation is the MCP ledger - every colony call is a governed tool call
 * and produces an [McpOperationRecord] whether or not this store ever sees it - so losing this store
 * loses no history. [ColonyLedgerReconstruction] rebuilds exactly the same threads from the ledger
 * alone, and a test pins that the two agree.
 */
class ColonyNegotiationStore {
    private val threads = ConcurrentHashMap<String, MutableList<ColonyMessage>>()

    /** Append a message, returning the thread it landed in. Threads are keyed by [ColonyMessage.threadId]. */
    fun append(message: ColonyMessage): ColonyThread =
        synchronized(this) {
            threads.getOrPut(message.threadId) { mutableListOf() } += message
            ColonyThread(message.threadId, threads.getValue(message.threadId).toList())
        }

    fun messages(threadId: String): List<ColonyMessage> = synchronized(this) { threads[threadId].orEmpty().toList() }

    fun state(threadId: String): ColonyLifecycle = ColonyProtocol.stateOf(messages(threadId))

    fun threads(): List<ColonyThread> {
        val ids = synchronized(this) { threads.keys.sorted() }
        return ids.map { ColonyThread(it, messages(it)) }
    }

    /** The proposal a message answers, or the message itself when it is a proposal. */
    fun proposalOf(
        threadId: String,
        proposalId: String,
    ): ColonyMessage? = messages(threadId).firstOrNull { it.messageId == proposalId }

    fun clear() = synchronized(this) { threads.clear() }
}

/**
 * Rebuilds negotiation threads from ledger records.
 *
 * This is what makes "reconstructable as a thread from the ledger" a property of the ledger rather
 * than of the live store: grouping the records by `colonyThreadId` and replaying them in timestamp
 * order reproduces the thread, including its state, with no cooperation from the process that wrote
 * them. A record that carries no thread id is skipped rather than grouped under a blank one, and
 * so is one for a call that failed - see [toMessage].
 *
 * Only the record's sanitized arguments are used, which is also the guarantee that reconstruction
 * cannot surface something the ledger would not already show a reader: the args are what the ledger
 * persists, and secrets are stripped from them before the record is written.
 */
object ColonyLedgerReconstruction {
    fun threads(records: List<McpOperationRecord>): List<ColonyThread> =
        records
            .mapNotNull { toMessage(it) }
            .groupBy { it.threadId }
            .toSortedMap()
            .map { (threadId, messages) -> ColonyThread(threadId, messages.sortedBy { it.timestamp }) }

    /**
     * The message a ledger record describes, or null when the record is not a colony message.
     *
     * A failed call produced no message. The ledger records attempts - a refused transition, a
     * policy denial, a timeout - and every one of those carries the thread attribution of the call
     * that made it, so replaying attributed records without this guard would invent messages for
     * negotiations that never happened. `isError` is what separates "this call created the message"
     * from "this call tried to".
     */
    fun toMessage(record: McpOperationRecord): ColonyMessage? =
        record.colonyThreadId
            ?.takeIf { !record.isError && it.isNotBlank() }
            ?.let { threadId -> messageOf(record, threadId) }

    private fun messageOf(
        record: McpOperationRecord,
        threadId: String,
    ): ColonyMessage? {
        val performative = ColonyProtocol.performativeFor(record.toolName) ?: return null
        val args = record.sanitizedArgs
        return ColonyMessage(
            messageId =
                record.colonyMessageId?.takeIf { it.isNotBlank() }
                    ?: ColonyProtocol.messageIdFor(record.toolName, args),
            threadId = threadId,
            performative = performative,
            fromWorktreeId = args[ColonyArgs.FROM_WORKTREE_ID].orEmpty(),
            toWorktreeId = args[ColonyArgs.TO_WORKTREE_ID].orEmpty(),
            timestamp = record.timestamp,
            proposalId = args[ColonyArgs.PROPOSAL_ID]?.takeIf { it.isNotBlank() },
            task = args[ColonyArgs.TASK]?.takeIf { it.isNotBlank() },
            scope = args[ColonyArgs.SCOPE]?.takeIf { it.isNotBlank() },
            reason = args[ColonyArgs.REASON]?.takeIf { it.isNotBlank() },
            alternativeScope = args[ColonyArgs.ALTERNATIVE_SCOPE]?.takeIf { it.isNotBlank() },
            context = args[ColonyArgs.CONTEXT]?.takeIf { it.isNotBlank() },
        )
    }
}
