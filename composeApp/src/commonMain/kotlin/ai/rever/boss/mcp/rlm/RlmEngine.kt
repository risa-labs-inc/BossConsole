package ai.rever.boss.mcp.rlm

import ai.rever.boss.mcp.capMcpResultText
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * How the engine reaches the delegate tools.
 *
 * A seam rather than a direct call to `McpToolRegistryImpl` for two reasons. It is the only
 * way to test the engine at all - a real registry resolves its disabled-tools file from the
 * user's `~/.boss` and would make every test mutate live state (the same reason
 * `McpToolRegistryCore` takes a nullable `disabledFile`). And it is what keeps the engine
 * honest about *how* a sub-call reaches a tool: going through `invoke` means every delegate
 * call gets its own policy consult, its own approval if the policy says ASK, and its own
 * ledger record, which is what "every sub-call is recorded" has to mean to be worth saying.
 */
fun interface RlmToolInvoker {
    suspend fun invoke(
        toolName: String,
        argumentsJson: String,
    ): McpToolResult
}

/**
 * What decided a [RlmAction.SUBQUERY]'s children.
 *
 * This is the seam an LLM-backed planner would occupy. v1 ships
 * [DeterministicRlmSubQueryPlanner], which takes the children the caller wrote, because the
 * host has no inference client to plan with: the credential broker
 * (`ai.rever.boss.llm.CredentialBrokerClient`) exchanges a Supabase session for a bearer
 * token and stops there - it takes no model, no prompt and no `response_format`, and nothing
 * in this repository implements a structured-output call. [UnavailableLlmSubQueryPlanner]
 * is the fail-closed placeholder that keeps that honest until such a client exists.
 *
 * [RlmPlan.Unavailable] exists so a planner that cannot answer says so *as an error*. The
 * alternative - returning an empty child list - is indistinguishable from a planner that
 * looked and found nothing, which is exactly the silent-empty-result failure this codebase
 * has been bitten by before (`ContentSearchService` returns `emptyList()` for both "no
 * matches" and "invalid regex", and had to grow a log line to tell them apart).
 */
fun interface RlmSubQueryPlanner {
    suspend fun plan(
        query: RlmQuery,
        depth: Int,
    ): RlmPlan
}

/** The outcome of asking a [RlmSubQueryPlanner] for a node's children. */
sealed interface RlmPlan {
    /** The children to execute, in order. Never empty - an empty plan is [Unavailable]. */
    data class Planned(
        val queries: List<RlmQuery>,
    ) : RlmPlan

    /** The planner declined, and why. Rendered into the tree as an error node. */
    data class Unavailable(
        val reason: String,
    ) : RlmPlan
}

/**
 * The v1 planner: SUBQUERY means the children the caller wrote, and nothing more.
 *
 * Deterministic, offline and testable. It is also the reason a SUBQUERY is worth having at
 * all in v1 - the recursion, the depth cap, the shared budget and the single rendered tree
 * are all real, even though the *planning* is not yet model-driven.
 */
class DeterministicRlmSubQueryPlanner : RlmSubQueryPlanner {
    override suspend fun plan(
        query: RlmQuery,
        depth: Int,
    ): RlmPlan =
        if (query.subqueries.isEmpty()) {
            RlmPlan.Unavailable(
                "SUBQUERY needs an explicit 'subqueries' array, and none was given. This build has no " +
                    "LLM-backed planner: the host's credential broker mints a bearer token for the " +
                    "gateway but implements no inference call, so sub-queries cannot be generated for you.",
            )
        } else {
            RlmPlan.Planned(query.subqueries)
        }
}

/**
 * The placeholder for a model-driven planner, kept so the seam is visible and so a build
 * that wires an LLM in by mistake fails closed rather than silently planning nothing.
 *
 * The reason string names the missing piece rather than saying "unsupported", because
 * whoever hits it needs to know whether to configure something or file something.
 */
object UnavailableLlmSubQueryPlanner : RlmSubQueryPlanner {
    override suspend fun plan(
        query: RlmQuery,
        depth: Int,
    ): RlmPlan =
        RlmPlan.Unavailable(
            "No LLM-backed RLM planner is configured in this build. Planning sub-queries needs a " +
                "host-side inference client with structured output, which does not exist here: " +
                "CredentialBrokerClient only exchanges a session for a short-lived bearer token. " +
                "Send explicit 'subqueries' instead.",
        )
}

/**
 * One executed node, shaped for display.
 *
 * [depth] is carried on the node rather than derived from nesting so a UI can lay the tree
 * out without re-walking it, and [delegate] is the concrete tool that ran - null for
 * SUBQUERY, which runs no delegate itself. A reader asking "what did this actually do" gets
 * the answer from the node, not from a log.
 */
data class RlmNode(
    val id: Int,
    val depth: Int,
    val action: RlmAction?,
    val label: String,
    val delegate: String?,
    val isError: Boolean,
    val text: String,
    val children: List<RlmNode> = emptyList(),
)

/** The whole run: the tree, what it cost, and whether a bound stopped it. */
data class RlmRunResult(
    val root: RlmNode,
    val nodesExecuted: Int,
    val truncated: Boolean,
    /**
     * Delegate calls this run answered from its own cache rather than re-invoking, so the
     * tree and the ledger can be reconciled: the ledger holds one record per *invocation*,
     * and this is the difference between that count and the number of delegate nodes here.
     */
    val cacheHits: Int = 0,
) {
    /**
     * The tree as text, for the tool result and for any surface that shows the run.
     *
     * Depth and cost are on the first line on purpose: the caller is an LLM, and a tree
     * that hit a bound must not look like a tree that finished. The same reasoning applies to
     * [RlmLimits.MAX_RENDER_CHARS], so hitting *that* bound is stated here too rather than being
     * left to the host's own tail cut, which would take the deepest nodes with it.
     */
    fun render(): String =
        buildString {
            append("RLM query tree - ")
            append(nodesExecuted)
            append(" node(s) executed")
            if (cacheHits > 0) {
                append(", ")
                append(cacheHits)
                append(" delegate call(s) served from cache")
            }
            if (truncated) {
                append(", TRUNCATED: the node budget of ")
                append(RlmLimits.MAX_NODES)
                append(" was reached, so some queries did not run")
            }
            append('\n')
            val omitted = root.renderInto(this, 0, RlmLimits.MAX_RENDER_CHARS)
            if (omitted > 0) {
                append("RLM: this answer reached its render budget of ")
                append(RlmLimits.MAX_RENDER_CHARS)
                append(" characters, so ")
                append(omitted)
                append(" node(s) are missing below. Every node that is shown ran; the rest ran too ")
                append("but did not fit here. The MCP ledger holds each delegate call this run made, ")
                append("and the operator's RLM tree view holds the whole run.\n")
            }
        }

    /**
     * Appends this node and its subtree, and returns how many nodes did not fit in [budget].
     *
     * The fit is decided per node, before any of it is written: a half-written node would be worse
     * than an omitted one, and the count has to be exact for the note that quotes it to be true.
     * A node is measured with its own lines *and* its text, because the text is the expensive part.
     */
    private fun RlmNode.renderInto(
        sb: StringBuilder,
        indent: Int,
        budget: Int,
    ): Int {
        val block = StringBuilder()
        val pad = "  ".repeat(indent)
        block.append(pad)
        block.append(if (isError) "! " else "- ")
        block.append(label)
        block
            .append(" [d")
            .append(depth)
            .append(", #")
            .append(id)
            .append(']')
        delegate?.let { block.append(" -> ").append(it) }
        block.append('\n')
        text.lineSequence().forEach { line ->
            block
                .append(pad)
                .append("    ")
                .append(line)
                .append('\n')
        }
        if (sb.length + block.length > budget) return countNodes()
        sb.append(block)
        return children.sumOf { it.renderInto(sb, indent + 1, budget) }
    }

    /** This subtree's node count, this node included. */
    private fun RlmNode.countNodes(): Int = 1 + children.sumOf { it.countNodes() }
}

/**
 * The recursive, host-executed codebase query engine.
 *
 * "Host-executed" is the point: the recursion runs here, on BOSS's side, under one budget
 * and one depth cap, instead of being left to an agent that would re-enter the MCP server
 * once per step with no memory of the whole. That is also what makes the ledger useful -
 * every delegate call goes through the registry, so the chain records the sub-calls, not
 * just the outer one.
 *
 * Threading: the engine does no I/O of its own. Delegates do their own dispatch (the
 * codebase plugin scans on IO, `ContentSearchService` searches on IO), and the slicing this
 * class does is in-memory string work over an answer already in hand - so there is nothing
 * here to move off the caller's dispatcher. See `docs/THREADING.md`.
 */
class RlmCodebaseEngine(
    private val invoker: RlmToolInvoker,
    private val planner: RlmSubQueryPlanner = DeterministicRlmSubQueryPlanner(),
) {
    private val logger = BossLogger.forComponent("RlmCodebaseEngine")

    suspend fun run(root: RlmQuery): RlmRunResult {
        val budget = RlmBudget()
        // Built here rather than held on the engine: the cache's lifetime is one run, and an
        // engine reused for a second query must not answer it from the first one's world.
        val cache = RlmDelegateCache()
        val node = execute(root, depth = 1, budget = budget, cache = cache)
        if (budget.truncated) {
            logger.warn(
                LogCategory.SYSTEM,
                "RLM query tree hit the node budget",
                mapOf("nodes" to budget.spent, "cap" to RlmLimits.MAX_NODES),
            )
        }
        return RlmRunResult(
            root = node,
            nodesExecuted = budget.spent,
            truncated = budget.truncated,
            cacheHits = cache.hits,
        )
    }

    // Every branch of the action dispatch returns its own node; the two refusal guards
    // ahead of it are the same shape. Splitting them into helpers would thread id/depth/
    // budget through all of them for no gain, so the third return is deliberate.
    @Suppress("ReturnCount")
    private suspend fun execute(
        query: RlmQuery,
        depth: Int,
        budget: RlmBudget,
        cache: RlmDelegateCache,
    ): RlmNode {
        val id = budget.nextId()
        val action = RlmAction.parse(query.action)
        if (action == null) {
            // Reported, never defaulted: a typo silently becoming a LIST_TREE would answer a
            // question nobody asked and look like a successful run.
            return RlmNode(
                id = id,
                depth = depth,
                action = null,
                label = query.action.trim().ifBlank { "(missing action)" },
                delegate = null,
                isError = true,
                text =
                    "Unknown RLM action '${query.action}'. Expected one of: " +
                        RlmAction.entries.joinToString(", ") { it.name } + ".",
            )
        }
        if (!budget.consume()) {
            return RlmNode(
                id = id,
                depth = depth,
                action = action,
                label = action.name,
                delegate = null,
                isError = true,
                text =
                    "RLM budget exhausted: this call may execute at most ${RlmLimits.MAX_NODES} " +
                        "nodes. This query did not run. Narrow the tree and try again.",
            )
        }
        val node =
            when (action) {
                RlmAction.READ_RANGE -> readRange(id, depth, query, cache)
                RlmAction.GREP -> grep(id, depth, query, cache)
                RlmAction.LIST_TREE -> listTree(id, depth, query, cache)
                RlmAction.SUBQUERY -> subquery(id, depth, query, budget, cache)
            }
        // A leaf action carrying a populated child list is reported, not silently dropped, for the
        // same reason a typo'd action is refused rather than guessed at: the caller wrote something
        // this did not do. It is not refused outright - the action itself is valid, and its answer
        // is still the answer to the question that was asked - but the node says where they went.
        return if (action != RlmAction.SUBQUERY && query.subqueries.isNotEmpty()) {
            node.copy(text = IGNORED_CHILDREN_PREFIX + "\n" + node.text)
        } else {
            node
        }
    }

    /**
     * `codebase_read` takes **only** `path` - it has no line-range argument and truncates
     * at 50,000 characters. So a range is served by reading the whole file and slicing here.
     * When the requested range starts past what the delegate returned, that is reported as
     * the range being unreachable through this delegate rather than as an empty read, since
     * those two mean very different things to whoever asked.
     */
    // Missing-path refusal, delegate-error propagation and the success node are three
    // distinct returns; collapsing them would nest the happy path two levels deep.
    @Suppress("ReturnCount")
    private suspend fun readRange(
        id: Int,
        depth: Int,
        query: RlmQuery,
        cache: RlmDelegateCache,
    ): RlmNode {
        val path =
            query.path?.takeIf { it.isNotBlank() }
                ?: return errorNode(id, depth, RlmAction.READ_RANGE, DELEGATE_READ, "READ_RANGE requires 'path'.")
        val answer = callDelegate(cache, DELEGATE_READ, buildJsonObject { put("path", path) })
        if (answer.result.isError) {
            return errorNode(id, depth, RlmAction.READ_RANGE, DELEGATE_READ, answer.result.text)
        }
        val start = (query.startLine ?: 1).coerceAtLeast(1)
        val end = query.endLine ?: Int.MAX_VALUE
        return RlmNode(
            id = id,
            depth = depth,
            action = RlmAction.READ_RANGE,
            label = "read $path ${rangeLabel(start, end)}" + cacheSuffix(answer),
            delegate = DELEGATE_READ,
            isError = false,
            text =
                nodeText(
                    answer,
                    capMcpResultText(sliceLines(answer.result.text, start, end), RlmLimits.MAX_NODE_CHARS),
                ),
        )
    }

    private suspend fun grep(
        id: Int,
        depth: Int,
        query: RlmQuery,
        cache: RlmDelegateCache,
    ): RlmNode {
        val needle =
            query.query?.takeIf { it.isNotBlank() }
                ?: return errorNode(id, depth, RlmAction.GREP, DELEGATE_GREP, "GREP requires 'query'.")
        val args =
            buildJsonObject {
                put("query", needle)
                // RLM's `glob` is the delegate's `pathPattern`. Translated here rather than
                // named `pathPattern` in the public contract so a delegate swap does not
                // change what an agent was told to send.
                query.glob?.takeIf { it.isNotBlank() }?.let { put("pathPattern", it) }
                put(
                    "maxResults",
                    (query.maxResults ?: RlmLimits.DEFAULT_GREP_RESULTS)
                        .coerceIn(1, RlmLimits.MAX_GREP_RESULTS),
                )
            }
        val answer = callDelegate(cache, DELEGATE_GREP, args)
        return RlmNode(
            id = id,
            depth = depth,
            action = RlmAction.GREP,
            label = "grep \"$needle\"" + (query.glob?.let { " in $it" } ?: "") + cacheSuffix(answer),
            delegate = DELEGATE_GREP,
            isError = answer.result.isError,
            text = nodeText(answer, capMcpResultText(answer.result.text, RlmLimits.MAX_NODE_CHARS)),
        )
    }

    private suspend fun listTree(
        id: Int,
        depth: Int,
        query: RlmQuery,
        cache: RlmDelegateCache,
    ): RlmNode {
        val args =
            buildJsonObject {
                // Omitted entirely when absent: the delegate then defaults to the open
                // project root, which is the answer an agent asking for "the tree" wants
                // and is not something this engine can compute for it.
                query.path?.takeIf { it.isNotBlank() }?.let { put("path", it) }
                query.treeDepth?.let { put("depth", it.coerceIn(1, MAX_TREE_DEPTH)) }
            }
        val answer = callDelegate(cache, DELEGATE_TREE, args)
        return RlmNode(
            id = id,
            depth = depth,
            action = RlmAction.LIST_TREE,
            label = "tree" + (query.path?.let { " $it" } ?: " (project root)") + cacheSuffix(answer),
            delegate = DELEGATE_TREE,
            isError = answer.result.isError,
            text = nodeText(answer, capMcpResultText(answer.result.text, RlmLimits.MAX_NODE_CHARS)),
        )
    }

    private suspend fun subquery(
        id: Int,
        depth: Int,
        query: RlmQuery,
        budget: RlmBudget,
        cache: RlmDelegateCache,
    ): RlmNode {
        // Refused before planning: planning costs a delegate call in a model-driven planner,
        // and spending it on children that can never run would burn budget to produce an error.
        if (depth >= RlmLimits.MAX_DEPTH) {
            return errorNode(
                id,
                depth,
                RlmAction.SUBQUERY,
                null,
                "SUBQUERY refused: this node is already at depth $depth and the cap is " +
                    "${RlmLimits.MAX_DEPTH}, so its children would exceed it.",
            )
        }
        return when (val plan = planner.plan(query, depth)) {
            is RlmPlan.Unavailable -> {
                errorNode(id, depth, RlmAction.SUBQUERY, null, plan.reason)
            }

            is RlmPlan.Planned -> {
                if (plan.queries.isEmpty()) {
                    errorNode(id, depth, RlmAction.SUBQUERY, null, "The planner returned no sub-queries.")
                } else {
                    val children = plan.queries.map { execute(it, depth + 1, budget, cache) }
                    RlmNode(
                        id = id,
                        depth = depth,
                        action = RlmAction.SUBQUERY,
                        label = "subquery (${children.size} child/children)",
                        delegate = null,
                        isError = children.all { it.isError },
                        text = "",
                        children = children,
                    )
                }
            }
        }
    }

    private fun errorNode(
        id: Int,
        depth: Int,
        action: RlmAction,
        delegate: String?,
        message: String,
    ): RlmNode =
        RlmNode(
            id = id,
            depth = depth,
            action = action,
            label = action.name,
            delegate = delegate,
            isError = true,
            text = message,
        )

    /**
     * One delegate call, memoized for the life of this run.
     *
     * The arguments are serialized once and used as both the key and the payload, so a request
     * cannot be remembered under one spelling and looked up under another.
     *
     * Only successes are remembered. A failure from a delegate is usually about the world at
     * that instant - a plugin not installed yet, a file briefly locked - and memoizing one
     * would let a single blip poison every later identical query in the same tree, which is
     * the opposite of what a cache is for.
     */
    private suspend fun callDelegate(
        cache: RlmDelegateCache,
        toolName: String,
        args: JsonObject,
    ): DelegateAnswer {
        val argumentsJson = args.toString()
        cache.get(toolName, argumentsJson)?.let { return DelegateAnswer(it, fromCache = true) }
        val result = invoker.invoke(toolName, argumentsJson)
        if (!result.isError) cache.put(toolName, argumentsJson, result)
        return DelegateAnswer(result, fromCache = false)
    }

    /** Names the reuse on the node's own line, so the tree reads right without opening text. */
    private fun cacheSuffix(answer: DelegateAnswer): String = if (answer.fromCache) " (cached)" else ""

    /**
     * Marks a body that came from the cache, because a hit is a delegate call that did **not**
     * happen. The ledger records invocations, so it will hold one record where the tree shows
     * two nodes; saying so here is what keeps the two reconcilable. The alternative is a tree
     * quietly claiming work the audit trail has no entry for.
     */
    private fun nodeText(
        answer: DelegateAnswer,
        body: String,
    ): String = if (answer.fromCache) CACHE_HIT_PREFIX + "\n" + body else body

    internal companion object {
        /**
         * The delegate tool names. Asserted by `RlmEngineTest` against the argument names
         * the `boss-plugin-codebase` plugin actually reads, so a rename there fails here.
         */
        const val DELEGATE_READ: String = "codebase_read"
        const val DELEGATE_TREE: String = "codebase_tree"
        const val DELEGATE_GREP: String = "project_search"

        /** `codebase_tree` coerces its own depth to 1..6; mirrored so the request is honest. */
        const val MAX_TREE_DEPTH: Int = 6

        /**
         * Prefixed to the body of a node whose delegate call this run had already made. One
         * line, so it costs exactly one of the three preview lines the operator's tree shows.
         */
        const val CACHE_HIT_PREFIX: String =
            "RLM: cache hit - an identical delegate call already ran earlier in this tree, so it " +
                "was not repeated. One policy consult and one ledger record cover both."

        /**
         * Prefixed to the body of a node whose query carried `subqueries` that its action cannot
         * use. One line, like [CACHE_HIT_PREFIX], and for the same reason: the rendered tree is
         * the only surface where this can be said, and the caller is the one who wrote them.
         */
        const val IGNORED_CHILDREN_PREFIX: String =
            "RLM: this node carried 'subqueries', which only the SUBQUERY action expands, so they " +
                "were not run. Send them as separate queries, or nest them under a SUBQUERY."

        /**
         * 1-based, inclusive, and tolerant of a range that runs past the end of the text.
         *
         * Past-the-end is not clamped silently: a caller that asked for lines 4000-4100 of a
         * file the delegate returned 900 lines of needs to know it got a partial answer
         * because `codebase_read` truncates, not because the file ends there.
         *
         * An inverted range is reported for the same reason, and it is the one case that would
         * otherwise be silent: `coerceIn` turns `3..1` into `subList(2, 2)`, which joins to the
         * empty string - indistinguishable from a file with no lines in that range. An LLM
         * writing the two bounds the wrong way round is not exotic.
         */
        // Fast path, inverted-range report, unreachable-range report and the slice are four
        // returns; the guard clauses keep the actual slicing arithmetic at the end, flat and
        // readable.
        @Suppress("ReturnCount")
        fun sliceLines(
            text: String,
            startLine: Int,
            endLine: Int,
        ): String {
            if (startLine <= 1 && endLine == Int.MAX_VALUE) return text
            if (endLine < startLine) {
                return "RLM: the range ${rangeLabel(startLine, endLine)} is inverted - endLine comes " +
                    "before startLine, so no lines can be returned. Send startLine <= endLine."
            }
            val lines = text.lineSequence().toList()
            if (startLine > lines.size) {
                return "RLM: asked for lines ${rangeLabel(startLine, endLine)} but the delegate " +
                    "returned only ${lines.size} line(s). codebase_read takes no line range and " +
                    "truncates long files, so this range is not reachable through it."
            }
            val from = (startLine - 1).coerceIn(0, lines.size)
            val to = if (endLine == Int.MAX_VALUE) lines.size else endLine.coerceIn(from, lines.size)
            return lines.subList(from, to).joinToString("\n")
        }

        fun rangeLabel(
            startLine: Int,
            endLine: Int,
        ): String = "$startLine..${if (endLine == Int.MAX_VALUE) "EOF" else endLine.toString()}"
    }
}

/**
 * The per-call node budget.
 *
 * [nextId] and [consume] are separate because a node that was refused for budget still needs
 * an identity in the rendered tree - it is shown, with the reason it did not run, which is
 * more useful than an unexplained gap.
 */
private class RlmBudget {
    private var issued = 0
    private var executed = 0

    var truncated: Boolean = false
        private set

    val spent: Int get() = executed

    fun nextId(): Int = ++issued

    fun consume(): Boolean {
        if (executed >= RlmLimits.MAX_NODES) {
            truncated = true
            return false
        }
        executed += 1
        return true
    }
}

/**
 * Memoizes delegate calls for the life of one [RlmCodebaseEngine.run].
 *
 * The scope is deliberately the narrowest one that is useful, and it is the whole design.
 *
 * Why it earns its place: a tree that asks for the same file twice - two branches of a
 * SUBQUERY reaching one shared source file is the obvious case - should not pay twice. And
 * `READ_RANGE` sharpens it, because `codebase_read` takes no line range: two *different*
 * ranges of one file are the *same* delegate call, so without this the second one re-reads
 * the whole file only to throw all but a few lines away.
 *
 * Why it must not outlive the run: two runs see two different worlds. An agent editing the
 * codebase between them is the normal case, and is usually why it is asking again - so a
 * result carried across runs would be a stale answer presented as a current one. Nothing is
 * persisted, nothing is shared between engines, and a second `run()` starts empty.
 *
 * Why it is visible: a hit skips `McpToolRegistryImpl.invoke`, and that call is where the
 * policy consult and the ledger record happen. So a reused answer says so on its node and the
 * run counts its hits, and the ledger's record count can still be reconciled against the
 * tree. A cache that silently removed entries from an audit trail would be a governance bug,
 * not an optimisation.
 */
private class RlmDelegateCache {
    private val entries = mutableMapOf<String, McpToolResult>()

    /** How many lookups were answered without invoking. */
    var hits: Int = 0
        private set

    fun get(
        toolName: String,
        argumentsJson: String,
    ): McpToolResult? = entries[key(toolName, argumentsJson)]?.also { hits += 1 }

    fun put(
        toolName: String,
        argumentsJson: String,
        result: McpToolResult,
    ) {
        if (entries.size >= MAX_ENTRIES) return
        entries[key(toolName, argumentsJson)] = result
    }

    /**
     * A separator that cannot occur in a tool name, so `("a", "b:c")` and `("a:b", "c")` do
     * not collide onto one key.
     */
    private fun key(
        toolName: String,
        argumentsJson: String,
    ): String = toolName + '\u0000' + argumentsJson

    private companion object {
        /**
         * Unreachable in practice - [RlmLimits.MAX_NODES] already caps a run at 23 delegate
         * calls - but a bound that only holds because another bound holds is the kind that
         * quietly stops being true.
         */
        const val MAX_ENTRIES: Int = RlmLimits.MAX_NODES
    }
}

/** A delegate's answer, and whether this run had already asked for it. */
private data class DelegateAnswer(
    val result: McpToolResult,
    val fromCache: Boolean,
)
