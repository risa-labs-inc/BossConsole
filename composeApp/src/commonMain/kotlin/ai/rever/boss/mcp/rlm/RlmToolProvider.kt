package ai.rever.boss.mcp.rlm

import ai.rever.boss.mcp.McpToolRegistryImpl
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The part of the tool description that speaks to an agent working *in parallel with others*.
 *
 * A swarm gives each agent its own worktree checked out as its own project, which makes "the
 * codebase" ambiguous in a way it is not for a lone agent: the answer an agent almost always
 * wants is "the checkout I am in", and a tool that searched the whole repository instead would
 * hand it a sibling's uncommitted state. Paths here resolve against the open project root,
 * which *is* that worktree, so the scoping falls out of the delegates rather than being
 * asserted here - the hint only has to tell the agent that this is so.
 *
 * A constant rather than a literal spliced into the description, so the description and the
 * test that guards it cannot drift apart.
 */
internal const val SWARM_CONTEXT_HINT: String =
    "Working in parallel with other agents? Paths resolve against the open project root, which " +
        "is the checkout you are in - your own worktree and its branch, not a sibling agent's. " +
        "Prefer one recursive query over a burst of small reads: the fan-out runs host-side under " +
        "a single depth cap and node budget, and each sub-call is still policy-checked and " +
        "recorded in the MCP ledger on its own, so the operator sees one query tree instead of an " +
        "unexplained burst of calls."

/**
 * Contributes `codebase_query_rlm` to the host registry.
 *
 * A provider of its own rather than another entry in `WorkspaceMcpToolProvider`, so the whole
 * RLM surface has one identity: an operator can trust or withhold it with a single
 * `providerRules` entry keyed `boss-rlm`, and the ledger records the same provider id for
 * every query that came through it. The tool it contributes delegates to the `codebase_*` /
 * `project_*` tools, which arrive under a *different* provider - so a sub-call is attributed
 * to whoever actually served it, not to this one.
 *
 * **Read-only, and that is load-bearing.** `readOnly = true` is what puts the tool on the
 * policy engine's read-only default (`ALLOW`) instead of the mutating one (`ASK`). The claim
 * is true because of what the action set can reach: `codebase_read`, `codebase_tree` and
 * `project_search` are all declared `readOnly = true` by their own plugin, and
 * `codebase_write` / `project_replace` are not among the delegates and cannot be named by a
 * caller - [RlmAction] is a closed set and the delegate names are constants. If a mutating
 * delegate is ever added, this flag has to move with it.
 */
object RlmToolProvider : McpToolProvider {
    /** As an agent types it, modulo the client's `mcp__boss__` prefix. */
    const val TOOL_NAME: String = "codebase_query_rlm"

    override val providerId: String = "boss-rlm"

    /**
     * The session's recursive queries, newest first, for the operator-facing tree view.
     *
     * Held here rather than inside the engine so one log covers every run whoever built the
     * engine, and so a test can construct its own engine without touching process-wide state.
     */
    val runLog: RlmRunLog = RlmRunLog()

    /**
     * Lenient on unknown keys so an agent that sends a delegate-specific field this build
     * does not know yet gets its query run instead of an opaque parse failure. Absent fields
     * keep their documented defaults, and an absent `action` still fails below.
     */
    private val requestJson = Json { ignoreUnknownKeys = true }

    override fun tools(): List<McpToolDefinition> =
        listOf(
            McpToolDefinition(
                name = TOOL_NAME,
                description =
                    "Query the codebase recursively from the host, in one governed call. Actions: " +
                        "READ_RANGE (a file's lines), GREP (find in files), LIST_TREE (a directory tree), " +
                        "SUBQUERY (nest any of these under one node). The whole tree runs host-side under " +
                        "one depth cap (3) and one node budget (24), and every sub-call is policy-checked " +
                        "and recorded in the MCP ledger individually. Returns the executed tree with its " +
                        "depth and cost. Read-only. " +
                        SWARM_CONTEXT_HINT,
                inputSchema = QUERY_SCHEMA,
                readOnly = true,
                handler = McpToolHandler { args -> handle(args) },
            ),
        )

    /**
     * Parse, run, render.
     *
     * On `Dispatchers.IO` for the same reason the registry's approval paths are: this reaches
     * files through the delegates, and `docs/THREADING.md` puts file work off the calling
     * dispatcher whatever that turns out to be. The delegates dispatch for themselves too -
     * this is the outer bound, not the only one.
     */
    private suspend fun handle(args: McpToolArgs): McpToolResult =
        withContext(Dispatchers.IO) {
            val request =
                try {
                    requestJson.decodeFromString<RlmQuery>(args.raw)
                } catch (e: SerializationException) {
                    // Reported rather than treated as an empty query: "could not parse" and
                    // "ran and found nothing" must not look alike to the caller.
                    return@withContext parseFailureResult(e)
                } catch (e: IllegalArgumentException) {
                    // kotlinx raises this for malformed JSON content on some paths; same answer.
                    return@withContext parseFailureResult(e)
                }
            val run = RlmCodebaseEngine(invoker = RegistryRlmToolInvoker).run(request)
            // Recorded before rendering, so the operator's tree view and the agent's answer are
            // built from the same object - there is no second code path that could disagree.
            runLog.record(run)
            // isError only when the ROOT failed: a tree whose root succeeded but whose third
            // child hit a missing delegate is a partial answer, and the tree says so in place.
            McpToolResult(run.render(), isError = run.root.isError)
        }

    /**
     * The one answer a malformed request can get: named, not silent, and never confused
     * with a query that ran and found nothing.
     */
    private fun parseFailureResult(e: Exception): McpToolResult =
        McpToolResult(
            "$TOOL_NAME could not parse its arguments: ${e.message ?: e::class.simpleName}. " +
                "Expected a JSON object with an 'action' field.",
            isError = true,
        )

    private object RegistryRlmToolInvoker : RlmToolInvoker {
        /**
         * Straight through the registry, which is the whole design: a delegate reached this
         * way gets its own policy consult, its own approval prompt if the policy says ASK,
         * and its own ledger record. An unknown or disabled delegate comes back from
         * `invoke` as an error result, so a missing `boss-plugin-codebase` fails closed with
         * a message naming the tool rather than returning an empty answer.
         */
        override suspend fun invoke(
            toolName: String,
            argumentsJson: String,
        ): McpToolResult = McpToolRegistryImpl.invoke(toolName, argumentsJson)
    }

    private val QUERY_SCHEMA =
        """
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "enum": ["READ_RANGE", "GREP", "LIST_TREE", "SUBQUERY"],
              "description": "Which query to run."
            },
            "path": {
              "type": "string",
              "description":
                "READ_RANGE/LIST_TREE: file or directory to act on. LIST_TREE defaults to the open project root when omitted."
            },
            "startLine": {
              "type": "integer",
              "description": "READ_RANGE: first line to return, 1-based and inclusive. Defaults to 1."
            },
            "endLine": {
              "type": "integer",
              "description": "READ_RANGE: last line to return, 1-based and inclusive. Defaults to end of file."
            },
            "query": {
              "type": "string",
              "description": "GREP: literal text to find."
            },
            "glob": {
              "type": "string",
              "description": "GREP: optional glob filter on the project-relative path, e.g. **/*.kt."
            },
            "maxResults": {
              "type": "integer",
              "description": "GREP: cap on matches. Defaults to 50, clamped to 1..200."
            },
            "treeDepth": {
              "type": "integer",
              "description": "LIST_TREE: directory depth. Defaults to 2, clamped to 1..6."
            },
            "subqueries": {
              "type": "array",
              "items": { "type": "object" },
              "description":
                "SUBQUERY: child queries, each an object of this same shape. Nesting is capped at depth 3 and the whole call at 24 nodes."
            }
          },
          "required": ["action"]
        }
        """.trimIndent()
}
