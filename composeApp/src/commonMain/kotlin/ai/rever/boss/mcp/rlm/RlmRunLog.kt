package ai.rever.boss.mcp.rlm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The read side of the RLM engine: the runs this session has executed, newest first.
 *
 * The tool result already tells the *caller* what happened - the tree, its depth and its cost.
 * This exists for the *operator*, who never sees a tool result: without it, a recursive query
 * that fanned out to twenty-four delegate calls is visible only as those twenty-four entries in
 * the MCP activity log, with nothing saying they were one query.
 *
 * A class with an injectable [capacity] rather than a singleton, for the same reason
 * [ai.rever.boss.mcp.McpOperationLedger] is one: a test that touched process-wide state would
 * have to reason about every other test that ran before it.
 *
 * In-memory only, deliberately. This is a debugging and observability surface for the current
 * session, not an audit trail - the audit trail is the ledger, which already persists each
 * delegate call with its own hash chain. Duplicating that on disk would add a second, weaker
 * record of the same events.
 */
class RlmRunLog(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val _runs = MutableStateFlow<List<RlmRunResult>>(emptyList())

    /** Newest first, capped at [capacity]. */
    val runs: StateFlow<List<RlmRunResult>> = _runs.asStateFlow()

    /**
     * Newest first, dropping the oldest past [capacity].
     *
     * Prepending rather than appending so the most recent run - the one an operator is almost
     * always asking about - is at index 0 and needs no scroll, matching how the ledger's own
     * ring buffer orders itself.
     */
    fun record(run: RlmRunResult) {
        _runs.update { current -> (listOf(run) + current).take(capacity) }
    }

    fun clear() {
        _runs.value = emptyList()
    }

    companion object {
        /**
         * Runs kept in memory. Smaller than the ledger's 100 because a run is much larger than a
         * record - each carries its whole tree plus up to 8,000 characters of node text.
         */
        const val DEFAULT_CAPACITY: Int = 20
    }
}

// ---------------------------------------------------------------------------
// Derivations. Extensions rather than members so RlmEngine.kt stays about execution,
// and so a caller that only wants a count does not have to know how the tree is shaped.
// ---------------------------------------------------------------------------

/** Deepest node in the tree, root included. Never zero: a run always has a root. */
fun RlmRunResult.maxDepth(): Int = root.depthBelow()

/** Nodes that errored, were refused by a bound, or named a missing delegate. */
fun RlmRunResult.errorCount(): Int = root.errorsBelow()

/** Nodes that actually reached a delegate tool. SUBQUERY nodes run none of their own. */
fun RlmRunResult.delegateCallCount(): Int = root.delegatesBelow()

private fun RlmNode.depthBelow(): Int = 1 + (children.maxOfOrNull { it.depthBelow() } ?: 0)

private fun RlmNode.errorsBelow(): Int = (if (isError) 1 else 0) + children.sumOf { it.errorsBelow() }

private fun RlmNode.delegatesBelow(): Int = (if (delegate != null) 1 else 0) + children.sumOf { it.delegatesBelow() }

/**
 * One line for a status bar, built from the same numbers the dialog shows so the two cannot
 * disagree. Names the bound that was hit rather than only that something went wrong: "truncated"
 * and "3 failed" are different problems with different fixes.
 */
fun RlmRunResult.summary(): String {
    val calls = delegateCallCount()
    val failures = errorCount()
    return buildString {
        append(calls)
        append(if (calls == 1) " call" else " calls")
        append(", depth ")
        append(maxDepth())
        append('/')
        append(RlmLimits.MAX_DEPTH)
        // Reported separately from the call count because the two differ exactly when the cache
        // did something: that count is delegate *nodes*, and a hit is a node that ran no call.
        if (cacheHits > 0) append(", ").append(cacheHits).append(" cached")
        if (truncated) append(", truncated")
        if (failures > 0) append(", ").append(failures).append(" failed")
    }
}

/**
 * One flattened line of a rendered tree.
 *
 * Flattening rather than nesting is what makes the tree testable without Compose: the shape is
 * asserted as (indent, label) pairs in a plain unit test, and the composable only has to draw a
 * list. It also keeps a 24-node tree from needing 24 nested composables.
 */
data class RlmDisplayRow(
    /** Nesting level, 0 for a run header. */
    val indent: Int,
    val label: String,
    /** Right-hand metadata: node id, depth, delegate. Empty for a text preview line. */
    val detail: String,
    val isError: Boolean,
    val isRoot: Boolean = false,
)

/**
 * How many lines of a node's own text are previewed under it.
 *
 * Capped because a single `codebase_read` can carry 8,000 characters: rendering all of it would
 * bury the tree structure, which is the one thing this view exists to show. The full text is in
 * the tool result the agent received.
 */
private const val MAX_PREVIEW_LINES: Int = 3

/** Flattens [runs] into display rows, newest run first, each tree indented under its header. */
fun buildRlmDisplayRows(runs: List<RlmRunResult>): List<RlmDisplayRow> {
    val rows = mutableListOf<RlmDisplayRow>()
    runs.forEachIndexed { index, run ->
        rows +=
            RlmDisplayRow(
                indent = 0,
                label = "Run ${index + 1}",
                detail = run.summary(),
                isError = run.root.isError,
                isRoot = true,
            )

        fun walk(
            node: RlmNode,
            indent: Int,
        ) {
            rows +=
                RlmDisplayRow(
                    indent = indent,
                    label = node.label,
                    detail =
                        buildString {
                            append('d').append(node.depth).append(" #").append(node.id)
                            node.delegate?.let { append(" · ").append(it) }
                        },
                    isError = node.isError,
                )
            node.text
                .lineSequence()
                .filter { it.isNotBlank() }
                .take(MAX_PREVIEW_LINES)
                .forEach { line -> rows += RlmDisplayRow(indent + 1, line, "", node.isError) }
            node.children.forEach { walk(it, indent + 1) }
        }
        walk(run.root, 1)
    }
    return rows
}
