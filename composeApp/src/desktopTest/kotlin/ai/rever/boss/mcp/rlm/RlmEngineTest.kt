package ai.rever.boss.mcp.rlm

import ai.rever.boss.mcp.MAX_MCP_RESULT_CHARS
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The engine's contract with the tools it delegates to, and with its own bounds.
 *
 * The delegate argument names asserted here are not this repository's invention - they are
 * what `boss-plugin-codebase`'s `CodebaseMcpTools` / `CodebaseGitMcpTools` actually read
 * (`path`, `depth` for `codebase_tree`; `path` for `codebase_read`; `query`, `pathPattern`,
 * `maxResults` for `project_search`). Those handlers live in a different repository that
 * nothing here links against at compile time, so this suite is the only thing that fails
 * when one of those names moves. That is deliberate: the failure mode otherwise is an empty
 * result that looks exactly like a successful search over a repository with no matches.
 *
 * The engine is driven through [RlmToolInvoker] rather than the real registry, for the same
 * reason `McpToolRegistryCore` takes a nullable `disabledFile` - the singleton resolves its
 * state from the user's `~/.boss`, and a test that touched it would mutate live state.
 */
class RlmEngineTest {
    // ------------------------------------------------------------------
    // Delegation: names, argument translation and clamping
    // ------------------------------------------------------------------

    @Test
    fun `LIST_TREE delegates to codebase_tree with path and depth`() =
        runBlocking {
            val invoker = RecordingInvoker()
            val run = RlmCodebaseEngine(invoker).run(RlmQuery(action = "LIST_TREE", path = "src", treeDepth = 3))

            val call = invoker.calls.single()
            assertEquals("codebase_tree", call.tool)
            assertEquals("src", call.args["path"]?.jsonPrimitive?.content)
            assertEquals(3, call.args["depth"]?.jsonPrimitive?.int)
            assertFalse(run.root.isError)
        }

    @Test
    fun `LIST_TREE without a path omits it so the delegate falls back to the project root`() =
        runBlocking {
            val invoker = RecordingInvoker()
            RlmCodebaseEngine(invoker).run(RlmQuery(action = "LIST_TREE"))

            val call = invoker.calls.single()
            assertEquals("codebase_tree", call.tool)
            // Absent, not empty-string: the delegate treats a blank path as "no path given" and
            // then uses the open project root, which is the answer an agent wants here.
            assertNull(call.args["path"])
        }

    @Test
    fun `GREP delegates to project_search, translating glob to pathPattern and clamping maxResults`() =
        runBlocking {
            val invoker = RecordingInvoker()
            RlmCodebaseEngine(invoker).run(
                RlmQuery(action = "GREP", query = "fun main", glob = "**/*.kt", maxResults = 9_999),
            )

            val call = invoker.calls.single()
            assertEquals("project_search", call.tool)
            assertEquals("fun main", call.args["query"]?.jsonPrimitive?.content)
            assertEquals("**/*.kt", call.args["pathPattern"]?.jsonPrimitive?.content)
            assertEquals(RlmLimits.MAX_GREP_RESULTS, call.args["maxResults"]?.jsonPrimitive?.int)
        }

    @Test
    fun `GREP without maxResults sends the documented default`() =
        runBlocking {
            val invoker = RecordingInvoker()
            RlmCodebaseEngine(invoker).run(RlmQuery(action = "GREP", query = "x"))

            assertEquals(
                RlmLimits.DEFAULT_GREP_RESULTS,
                invoker.calls
                    .single()
                    .args["maxResults"]
                    ?.jsonPrimitive
                    ?.int,
            )
        }

    @Test
    fun `READ_RANGE delegates to codebase_read with path only, then slices host-side`() =
        runBlocking {
            // codebase_read takes no line range - it returns the whole file - so the range has
            // to be applied here. This test is what pins that split.
            val invoker = RecordingInvoker { McpToolResult((1..100).joinToString("\n") { "line$it" }) }
            val run =
                RlmCodebaseEngine(invoker).run(
                    RlmQuery(action = "READ_RANGE", path = "src/A.kt", startLine = 3, endLine = 5),
                )

            val call = invoker.calls.single()
            assertEquals("codebase_read", call.tool)
            assertEquals("src/A.kt", call.args["path"]?.jsonPrimitive?.content)
            assertFalse(call.args.containsKey("startLine"), "codebase_read has no line-range argument")
            assertFalse(call.args.containsKey("endLine"), "codebase_read has no line-range argument")
            assertEquals("line3\nline4\nline5", run.root.text)
        }

    @Test
    fun `READ_RANGE past what the delegate returned says the range is unreachable, not empty`() =
        runBlocking {
            val invoker = RecordingInvoker { McpToolResult((1..10).joinToString("\n") { "l$it" }) }
            val run =
                RlmCodebaseEngine(invoker).run(
                    RlmQuery(action = "READ_RANGE", path = "big.kt", startLine = 4_000, endLine = 4_100),
                )

            assertFalse(run.root.isError)
            assertTrue(
                run.root.text.contains("not reachable through it"),
                "a range the delegate cannot serve must say so rather than read as an empty file: ${run.root.text}",
            )
        }

    @Test
    fun `READ_RANGE without a path fails without calling any delegate`() =
        runBlocking {
            val invoker = RecordingInvoker()
            val run = RlmCodebaseEngine(invoker).run(RlmQuery(action = "READ_RANGE"))

            assertTrue(run.root.isError)
            assertTrue(run.root.text.contains("'path'"))
            assertTrue(invoker.calls.isEmpty(), "a malformed query must not reach the filesystem")
        }

    @Test
    fun `a missing delegate surfaces as an error naming the tool`() =
        runBlocking {
            // Exactly what McpToolRegistryCore.invoke returns for an unregistered name - which
            // is what happens when boss-plugin-codebase is not installed.
            val invoker =
                RecordingInvoker {
                    McpToolResult("Unknown or disabled MCP tool: codebase_tree", isError = true)
                }
            val run = RlmCodebaseEngine(invoker).run(RlmQuery(action = "LIST_TREE"))

            assertTrue(run.root.isError)
            assertTrue(run.root.text.contains("Unknown or disabled MCP tool: codebase_tree"))
        }

    // ------------------------------------------------------------------
    // Recursion, depth and budget
    // ------------------------------------------------------------------

    @Test
    fun `SUBQUERY with no children fails closed instead of planning nothing`() =
        runBlocking {
            val run = RlmCodebaseEngine(RecordingInvoker()).run(RlmQuery(action = "SUBQUERY"))

            assertTrue(run.root.isError)
            assertTrue(run.root.text.contains("subqueries"))
        }

    @Test
    fun `recursion is capped at depth 3 and the refused node never runs its children`() =
        runBlocking {
            val invoker = RecordingInvoker()
            val root =
                RlmQuery(
                    action = "SUBQUERY",
                    subqueries =
                        listOf(
                            RlmQuery(
                                action = "SUBQUERY",
                                subqueries =
                                    listOf(
                                        RlmQuery(
                                            action = "SUBQUERY",
                                            subqueries = listOf(RlmQuery(action = "LIST_TREE")),
                                        ),
                                    ),
                            ),
                        ),
                )

            val run = RlmCodebaseEngine(invoker).run(root)

            val depth3 =
                run.root.children
                    .single()
                    .children
                    .single()
            assertEquals(3, depth3.depth)
            assertTrue(depth3.isError)
            assertTrue(depth3.text.contains("SUBQUERY refused"), depth3.text)
            assertTrue(depth3.text.contains("depth 3"), depth3.text)
            assertTrue(
                invoker.calls.isEmpty(),
                "the leaf under a refused SUBQUERY must not run - the cap has to bite before the work, not after",
            )
        }

    @Test
    fun `the node budget stops the run and marks it truncated`() =
        runBlocking {
            // Distinct paths, not one repeated query: the intra-run cache collapses identical
            // delegate calls, and a test of the BUDGET must not have its delegate count
            // flattened by the cache. The cache/budget interaction is pinned on its own below.
            val invoker = RecordingInvoker()
            val run =
                RlmCodebaseEngine(invoker).run(
                    RlmQuery(
                        action = "SUBQUERY",
                        subqueries = (1..30).map { RlmQuery(action = "LIST_TREE", path = "dir$it") },
                    ),
                )

            assertTrue(run.truncated)
            assertEquals(RlmLimits.MAX_NODES, run.nodesExecuted)
            assertEquals(
                RlmLimits.MAX_NODES - 1,
                invoker.calls.size,
                "the root spends one node, so only the remaining budget may reach a delegate",
            )
        }

    @Test
    fun `the LLM seam fails closed rather than silently planning nothing`() =
        runBlocking {
            val invoker = RecordingInvoker()
            val run =
                RlmCodebaseEngine(invoker, UnavailableLlmSubQueryPlanner).run(
                    RlmQuery(action = "SUBQUERY", subqueries = listOf(RlmQuery(action = "LIST_TREE"))),
                )

            assertTrue(run.root.isError)
            assertTrue(run.root.text.contains("CredentialBrokerClient"))
            assertTrue(invoker.calls.isEmpty())
        }

    @Test
    fun `an unknown action is reported with the full action list`() =
        runBlocking {
            val invoker = RecordingInvoker()
            val run = RlmCodebaseEngine(invoker).run(RlmQuery(action = "read_range_typo"))

            assertTrue(run.root.isError)
            assertTrue(run.root.text.contains("READ_RANGE"))
            assertTrue(run.root.text.contains("SUBQUERY"))
            assertTrue(invoker.calls.isEmpty())
        }

    // ------------------------------------------------------------------
    // Visibility
    // ------------------------------------------------------------------

    @Test
    fun `the rendered tree carries depth, node id, cost and the delegate that ran`() =
        runBlocking {
            val run = RlmCodebaseEngine(RecordingInvoker()).run(RlmQuery(action = "LIST_TREE", path = "src"))
            val text = run.render()

            assertTrue(text.contains("1 node(s) executed"), text)
            assertTrue(text.contains("[d1, #1]"), text)
            assertTrue(text.contains("-> codebase_tree"), text)
        }

    @Test
    fun `a truncated run says so in the rendered header`() =
        runBlocking {
            val run =
                RlmCodebaseEngine(RecordingInvoker()).run(
                    RlmQuery(action = "SUBQUERY", subqueries = (1..30).map { RlmQuery(action = "LIST_TREE") }),
                )

            assertTrue(run.render().contains("TRUNCATED"), run.render())
        }

    @Test
    fun `sliceLines is 1-based, inclusive, and tolerates a range past the end`() {
        val text = "a\nb\nc\nd"

        assertEquals(text, RlmCodebaseEngine.sliceLines(text, 1, Int.MAX_VALUE))
        assertEquals("b\nc", RlmCodebaseEngine.sliceLines(text, 2, 3))
        assertEquals("d", RlmCodebaseEngine.sliceLines(text, 4, 99))
        assertTrue(RlmCodebaseEngine.sliceLines(text, 9, 12).contains("only 4 line(s)"))
    }

    @Test
    fun `sliceLines reports an inverted range rather than returning nothing`() {
        val text = "a\nb\nc\nd"

        // coerceIn folds 3..1 to subList(2, 2), which joins to the empty string - the one case that
        // would otherwise be silent, and indistinguishable from a file with no lines in that range.
        val inverted = RlmCodebaseEngine.sliceLines(text, 3, 1)
        assertTrue(inverted.contains("inverted"), inverted)
        assertTrue(inverted.contains("3..1"), inverted)

        // The same shape one step off: an end bound that is not a line number at all.
        val zeroEnd = RlmCodebaseEngine.sliceLines(text, 2, 0)
        assertTrue(zeroEnd.contains("inverted"), zeroEnd)
    }

    @Test
    fun `READ_RANGE with the bounds the wrong way round says so on the node`() =
        runBlocking {
            val invoker = RecordingInvoker { McpToolResult((1..10).joinToString("\n") { "l$it" }) }
            val run =
                RlmCodebaseEngine(invoker).run(
                    RlmQuery(action = "READ_RANGE", path = "A.kt", startLine = 6, endLine = 2),
                )

            assertTrue(run.root.text.contains("inverted"), run.root.text)
            assertFalse(
                run.root.text.isBlank(),
                "an empty body is the failure this guards: the node must say why it is empty",
            )
        }

    @Test
    fun `subqueries on a leaf action are reported rather than dropped in silence`() =
        runBlocking {
            val invoker = RecordingInvoker()
            val run =
                RlmCodebaseEngine(invoker).run(
                    RlmQuery(
                        action = "GREP",
                        query = "x",
                        subqueries = listOf(RlmQuery(action = "LIST_TREE")),
                    ),
                )

            // The action itself is valid, so it still runs and its answer is still the answer -
            // but the caller wrote children that did nothing, and that is what the node says.
            assertEquals(1, invoker.calls.size)
            assertFalse(run.root.isError)
            assertTrue(run.root.text.contains("'subqueries'"), run.root.text)
            assertTrue(run.root.text.contains("SUBQUERY"), run.root.text)
            assertTrue(run.render().contains("were not run"), run.render())
        }

    @Test
    fun `a payload nested past the wire cap is refused before the decoder sees it`() {
        // Thousands of levels is what makes the decoder's own recursion raise StackOverflowError -
        // an Error, so it would bypass the handler's catches and leave through the MCP server. The
        // guard is plain string work, so it can be pinned without involving a decoder at all.
        val nested = "{\"action\":\"SUBQUERY\",\"subqueries\":["
        val deep = nested.repeat(2_000) + "]".repeat(2_000) + "}"

        assertTrue(nestingExceeds(deep), "a payload this deep must be refused before it is parsed")
        assertFalse(nestingExceeds("""{"action":"SUBQUERY","subqueries":[{"action":"LIST_TREE"}]}"""))
        // Braces inside a string literal are content: a GREP for "{{{" is not nesting.
        assertFalse(nestingExceeds("""{"action":"GREP","query":"${"{".repeat(64)}"}"""))
        // The wire cap is deliberately looser than the execution cap, so a tree the engine will
        // only partly run still parses and gets per-node refusals with reasons.
        assertTrue(RlmLimits.MAX_WIRE_DEPTH > RlmLimits.MAX_DEPTH)
        assertFalse(nestingExceeds("""{"action":"LIST_TREE"}""", limit = 2))
        assertTrue(nestingExceeds("""{"a":{"b":{"c":1}}}""", limit = 2))
    }

    @Test
    fun `a maxed-out tree renders under the host cap and says what it left out`() =
        runBlocking {
            // The worst case the limits allow: MAX_NODES nodes each carrying MAX_NODE_CHARS, whose
            // product is larger than the host's cap for a whole result. That cap cuts from the tail,
            // so without a render budget the deepest nodes - the ones that answer the question -
            // would disappear, and the header would blame the node budget for it.
            val body = "x".repeat(RlmLimits.MAX_NODE_CHARS)
            val invoker = RecordingInvoker { McpToolResult(body) }
            val run =
                RlmCodebaseEngine(invoker).run(
                    RlmQuery(
                        action = "SUBQUERY",
                        subqueries = (1..RlmLimits.MAX_NODES).map { RlmQuery(action = "LIST_TREE", path = "dir$it") },
                    ),
                )

            val text = run.render()

            assertTrue(text.length <= MAX_MCP_RESULT_CHARS, "rendered ${text.length} characters")
            assertTrue(text.contains("render budget"), text.take(400))
            assertTrue(text.contains("missing below"), text.take(400))
            assertTrue(text.startsWith("RLM query tree - "), text.take(120))
        }

    // ------------------------------------------------------------------
    // The registered tool
    // ------------------------------------------------------------------

    @Test
    fun `the RLM tool is declared read-only under its own provider id`() {
        val def = RlmToolProvider.tools().single()

        assertEquals("codebase_query_rlm", def.name)
        assertEquals("boss-rlm", RlmToolProvider.providerId)
        // readOnly = true is what routes this to the policy engine's read-only default (ALLOW)
        // instead of the mutating default (ASK). If it ever flips, that is a policy change.
        assertTrue(def.readOnly)
        assertTrue(def.inputSchema.contains("SUBQUERY"))
        assertTrue(def.inputSchema.contains("\"required\": [\"action\"]"))
    }

    @Test
    fun `the schema does not promise a default the engine never sends`() {
        val schema = RlmToolProvider.tools().single().inputSchema

        // The engine omits `treeDepth` when the caller does not name one, so the value that applies
        // is `codebase_tree`'s own default - a constant in a repository nothing here links against.
        // "Defaults to 2" would be a promise this side cannot keep, which is worse than saying whose
        // default it is.
        assertFalse(schema.contains("Defaults to 2"), schema)
        assertTrue(schema.contains("delegate's own default"), schema)
        // A payload deeper than the engine will execute is still parsed and refused per node, so the
        // wire cap has to be stated, and it has to stay looser than the execution cap.
        assertTrue(schema.contains(RlmLimits.MAX_WIRE_DEPTH.toString()), schema)
        assertTrue(RlmLimits.MAX_WIRE_DEPTH > RlmLimits.MAX_DEPTH)
    }

    @Test
    fun `the tool description carries the swarm hint, so a parallel agent is told its scope`() {
        val description = RlmToolProvider.tools().single().description

        // The hint has to actually reach the description - a constant nobody splices in is
        // invisible to every agent that reads the tool list, which is the whole point of it.
        assertTrue(description.contains(SWARM_CONTEXT_HINT), description)
        // And it has to still say the things that make it worth reading: which checkout it
        // reads, and why one query beats a burst of reads. Gutting the constant fails here.
        assertTrue(SWARM_CONTEXT_HINT.contains("worktree"), SWARM_CONTEXT_HINT)
        assertTrue(SWARM_CONTEXT_HINT.contains("project root"), SWARM_CONTEXT_HINT)
        assertTrue(SWARM_CONTEXT_HINT.contains("one recursive query"), SWARM_CONTEXT_HINT)
    }

    @Test
    fun `the delegate names are the ones boss-plugin-codebase actually reads`() {
        assertEquals("codebase_read", RlmCodebaseEngine.DELEGATE_READ)
        assertEquals("codebase_tree", RlmCodebaseEngine.DELEGATE_TREE)
        assertEquals("project_search", RlmCodebaseEngine.DELEGATE_GREP)
    }

    // ------------------------------------------------------------------
    // The intra-run delegate cache (SS2.7)
    // ------------------------------------------------------------------

    @Test
    fun `an identical delegate call in one tree is invoked once and the second node says so`() =
        runBlocking {
            val invoker = RecordingInvoker()
            val run =
                RlmCodebaseEngine(invoker).run(
                    RlmQuery(
                        action = "SUBQUERY",
                        subqueries =
                            listOf(
                                RlmQuery(action = "LIST_TREE", path = "src", treeDepth = 2),
                                RlmQuery(action = "LIST_TREE", path = "src", treeDepth = 2),
                            ),
                    ),
                )

            assertEquals(1, invoker.calls.size, "the second identical request must not reach the delegate")
            val (first, second) = run.root.children
            assertFalse(first.text.contains("cache hit"), first.text)
            assertTrue(second.text.contains("cache hit"), second.text)
            assertEquals(1, run.cacheHits)
        }

    @Test
    fun `two different ranges of one file cost one delegate read`() =
        runBlocking {
            // The sharpest case, and the reason this is worth having: codebase_read takes no
            // line range, so two ranges of one file are literally the same delegate call. Without
            // the cache the second range re-reads the whole file to keep two lines of it.
            val invoker = RecordingInvoker { McpToolResult((1..50).joinToString("\n") { "line$it" }) }
            val run =
                RlmCodebaseEngine(invoker).run(
                    RlmQuery(
                        action = "SUBQUERY",
                        subqueries =
                            listOf(
                                RlmQuery(action = "READ_RANGE", path = "A.kt", startLine = 1, endLine = 2),
                                RlmQuery(action = "READ_RANGE", path = "A.kt", startLine = 40, endLine = 41),
                            ),
                    ),
                )

            assertEquals(1, invoker.calls.size, "both ranges are one codebase_read")
            val (first, second) = run.root.children
            assertEquals("line1\nline2", first.text)
            // Cached is the delegate's answer, not the node: the host-side slice still applies.
            assertTrue(second.text.contains("line40\nline41"), second.text)
        }

    @Test
    fun `the cache does not survive a run, so a second query sees the world again`() =
        runBlocking {
            val invoker = RecordingInvoker()
            val engine = RlmCodebaseEngine(invoker)
            engine.run(RlmQuery(action = "LIST_TREE", path = "src"))
            engine.run(RlmQuery(action = "LIST_TREE", path = "src"))

            // An agent editing between queries is the normal case, and usually why it asks again.
            assertEquals(2, invoker.calls.size, "a cross-run hit would be a stale answer")
        }

    @Test
    fun `a failed delegate call is not cached, so one blip cannot poison the rest of the tree`() =
        runBlocking {
            var attempt = 0
            val invoker =
                RecordingInvoker {
                    attempt += 1
                    if (attempt == 1) McpToolResult("transient failure", isError = true) else McpToolResult("ok")
                }
            val run =
                RlmCodebaseEngine(invoker).run(
                    RlmQuery(
                        action = "SUBQUERY",
                        subqueries =
                            listOf(
                                RlmQuery(action = "LIST_TREE", path = "src"),
                                RlmQuery(action = "LIST_TREE", path = "src"),
                            ),
                    ),
                )

            assertEquals(2, invoker.calls.size, "a failure is retried, not remembered")
            assertEquals(0, run.cacheHits)
            val (first, second) = run.root.children
            assertTrue(first.isError)
            assertFalse(second.isError)
        }

    @Test
    fun `the run reports how many calls it answered from cache`() =
        runBlocking {
            val invoker = RecordingInvoker()
            val run =
                RlmCodebaseEngine(invoker).run(
                    RlmQuery(
                        action = "SUBQUERY",
                        subqueries =
                            listOf(
                                RlmQuery(action = "LIST_TREE", path = "src"),
                                RlmQuery(action = "LIST_TREE", path = "src"),
                                RlmQuery(action = "LIST_TREE", path = "src"),
                            ),
                    ),
                )

            // The reconciliation the ledger depends on: three nodes name a delegate, one ran.
            assertEquals(3, run.delegateCallCount(), "the tree still shows a delegate per node")
            assertEquals(2, run.cacheHits)
            assertEquals(1, invoker.calls.size)
            assertTrue(run.render().contains("2 delegate call(s) served from cache"), run.render())
        }

    @Test
    fun `cache hits do not refund the node budget`() =
        runBlocking {
            // The interaction the budget test had to be fixed to avoid: a tree of IDENTICAL
            // queries is served almost entirely from cache, and a hit costs no delegate call -
            // but it still costs a node. The budget must not be refillable by the cache, or a
            // caller could loop one repeated query past MAX_NODES forever.
            val invoker = RecordingInvoker()
            val run =
                RlmCodebaseEngine(invoker).run(
                    RlmQuery(action = "SUBQUERY", subqueries = (1..30).map { RlmQuery(action = "LIST_TREE") }),
                )

            assertTrue(run.truncated)
            assertEquals(RlmLimits.MAX_NODES, run.nodesExecuted)
            // Root + the one real invocation spend the budget; every later identical node was
            // a hit. MAX_NODES - 2: the root node and the first (uncached) delegate call.
            assertEquals(1, invoker.calls.size)
            assertEquals(RlmLimits.MAX_NODES - 2, run.cacheHits)
        }

    /** Records every delegate call so a test can assert both the tool and its arguments. */
    private class RecordingInvoker(
        private val reply: (String) -> McpToolResult = { McpToolResult("ok") },
    ) : RlmToolInvoker {
        data class Call(
            val tool: String,
            val args: JsonObject,
        )

        val calls = mutableListOf<Call>()

        override suspend fun invoke(
            toolName: String,
            argumentsJson: String,
        ): McpToolResult {
            calls += Call(toolName, Json.parseToJsonElement(argumentsJson).jsonObject)
            return reply(toolName)
        }
    }
}
