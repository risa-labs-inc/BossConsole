package ai.rever.boss.mcp.rlm

import ai.rever.boss.mcp.MAX_MCP_RESULT_CHARS
import kotlinx.serialization.Serializable

/**
 * The fixed action set `codebase_query_rlm` accepts.
 *
 * Deliberately closed. An action set that let a caller name an arbitrary delegate would
 * turn this tool into a generic proxy: the policy engine's per-tool view of *what is being
 * asked for* (`McpPolicyEngine.policyFor`, the risk evaluator, the operator's approval
 * dialog) is all keyed on a tool name, and a proxy that forwards under its own name would
 * present every delegate call as one read-only RLM query. Four verbs, each hard-wired to
 * one delegate - or, for [SUBQUERY], to recursion the engine itself bounds.
 *
 * The delegates are the `codebase_*` / `project_*` tools contributed by the external
 * `boss-plugin-codebase` plugin, whose argument names are asserted by
 * [ai.rever.boss.mcp.rlm.RlmEngineTest] so schema drift in that plugin fails a test here
 * rather than silently producing empty results at runtime.
 */
enum class RlmAction {
    /** Read a line range out of one file. Delegates to `codebase_read`, then slices. */
    READ_RANGE,

    /** Search file contents across the project. Delegates to `project_search`. */
    GREP,

    /** List the file tree under a directory. Delegates to `codebase_tree`. */
    LIST_TREE,

    /** Recurse: run a nested set of queries and fold their results under this node. */
    SUBQUERY,
    ;

    companion object {
        /**
         * Case-insensitive and whitespace-tolerant, because these arrive from an LLM that
         * writes `read_range` and ` READ_RANGE ` about equally often. Returns `null` for
         * anything else rather than defaulting - a typo must be reported, not guessed at.
         */
        fun parse(raw: String?): RlmAction? {
            val needle = raw?.trim()?.uppercase() ?: return null
            return entries.firstOrNull { it.name == needle }
        }
    }
}

/**
 * One node of a recursive codebase query, as it arrives on the wire.
 *
 * Every field is optional except [action] so that the delegate argument names can stay
 * delegate-specific without a field per delegate: [glob] maps to `project_search`'s
 * `pathPattern`, [treeDepth] to `codebase_tree`'s `depth`, and [maxResults] to
 * `project_search`'s `maxResults`. Keeping the RLM-facing names delegate-agnostic is what
 * lets a delegate be swapped without changing the contract an agent was told about.
 *
 * [subqueries] is what [RlmAction.SUBQUERY] expands. It is a plain recursive list, so the
 * shape an agent sends *is* the recursion tree; nothing here is inferred.
 */
@Serializable
data class RlmQuery(
    val action: String,
    val path: String? = null,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val query: String? = null,
    val glob: String? = null,
    val maxResults: Int? = null,
    val treeDepth: Int? = null,
    val subqueries: List<RlmQuery> = emptyList(),
)

/**
 * The bounds that keep one RLM call from becoming an unbounded walk of the repository.
 *
 * These are the only thing standing between a plausible-looking agent query and a
 * thousands-node fan-out: each node is a delegate call that the registry will itself
 * ledger, policy-check and cap, so the cost of a runaway tree is paid three times over.
 * A caller cannot raise any of these - none is reachable from [RlmQuery] - because the
 * whole point is that the bound holds when the caller is the thing misbehaving.
 */
object RlmLimits {
    /**
     * Maximum node depth. Depth 1 is the root, so a SUBQUERY node at depth 3 is refused:
     * it could only produce children at depth 4.
     */
    const val MAX_DEPTH: Int = 3

    /**
     * Maximum nodes executed per call, root included. Also the cost ceiling: one
     * `codebase_tree` over a large repository is not cheap, and 24 of them is already a
     * lot of filesystem work to trigger from a single tool call.
     */
    const val MAX_NODES: Int = 24

    /** Per-node output cap, so one delegate's answer cannot crowd out the rest of the tree. */
    const val MAX_NODE_CHARS: Int = 8_000

    /**
     * Ceiling on the rendered tree, and the reason [MAX_NODES] x [MAX_NODE_CHARS] does not have to
     * stay under the host's own limit for a whole tool result.
     *
     * The registry caps every result at `MAX_MCP_RESULT_CHARS` and cuts it from the *tail*, which is
     * the wrong end for a tree: the deepest nodes are the ones that answer the question, and they
     * are exactly what a silent tail cut removes. Worse, the cut marker would land after a header
     * that only ever mentions the node budget, so a reader could not tell which bound they hit.
     * This stops the render while nodes are still being written and says how many it left out.
     *
     * Derived from the host's ceiling rather than written as a second number, because two
     * independent ceilings for one product is the failure this constant exists to prevent. The
     * headroom covers the tree's own per-node lines plus the note this bound appends.
     */
    const val MAX_RENDER_CHARS: Int = MAX_MCP_RESULT_CHARS - 10_000

    /**
     * How deep a *payload* this tool will parse, as opposed to how deep it will execute
     * ([MAX_DEPTH]).
     *
     * The two are different numbers on purpose. Execution is capped at [MAX_DEPTH] and a deeper
     * node is refused with a reason, which is more informative than rejecting the whole call - so
     * the parser has to accept trees the engine will never fully run. It only has to be low enough
     * that the decoder's own recursion, which is one stack frame per nesting level, cannot exhaust
     * the stack: a `StackOverflowError` is an [Error], not an [Exception], so it would escape the
     * handler that turns every other malformed request into a named result.
     */
    const val MAX_WIRE_DEPTH: Int = 32

    /** `project_search` default when the caller does not say. */
    const val DEFAULT_GREP_RESULTS: Int = 50

    /**
     * RLM's own ceiling on `maxResults`, well under `project_search`'s own 2,000.
     *
     * Two reasons, and neither is the delegate cache - that cache lives for one `run()` and is
     * gone by the next call. The matches land in this run's rendered tree, and the tree is a tool
     * result: it stays in the conversation for the rest of the session and is re-sent as cached
     * prefix on every later request, so a large grep is a recurring cost rather than a one-off.
     * And past the point a single node can carry ([MAX_NODE_CHARS]) the extra matches are cut from
     * the node anyway, so asking for more only buys search work whose answer is discarded.
     */
    const val MAX_GREP_RESULTS: Int = 200
}
