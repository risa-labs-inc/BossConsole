package ai.rever.boss.mcp.rlm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The read side: the run log's bounds and ordering, the derivations the status bar and the dialog
 * both quote, and the flattened row list the tree view draws.
 *
 * These are the numbers an operator sees. `summary()` is asserted as an exact string on purpose -
 * the status bar and the dialog header both call it, so a change to its wording is a change to two
 * surfaces, and a test that only checked `contains("depth")` would not notice one drifting.
 */
class RlmRunLogTest {
    // ------------------------------------------------------------------
    // The log
    // ------------------------------------------------------------------

    @Test
    fun `the log keeps the newest run first and drops the oldest`() {
        val log = RlmRunLog(capacity = 3)

        for (id in 1..5) log.record(run(node(id, 1)))

        assertEquals(listOf(5, 4, 3), log.runs.value.map { it.root.id })
    }

    @Test
    fun `the log is bounded by its capacity`() {
        val log = RlmRunLog(capacity = 2)

        for (id in 1..10) log.record(run(node(id, 1)))

        assertEquals(2, log.runs.value.size)
    }

    @Test
    fun `a fresh log is empty and clear empties a used one`() {
        val log = RlmRunLog()

        assertTrue(log.runs.value.isEmpty())
        log.record(run(node(1, 1)))
        assertEquals(1, log.runs.value.size)
        log.clear()
        assertTrue(log.runs.value.isEmpty())
    }

    // ------------------------------------------------------------------
    // Derivations
    // ------------------------------------------------------------------

    @Test
    fun `summary counts delegate calls, depth and failures`() {
        val tree =
            run(
                node(
                    1,
                    1,
                    label = "subquery",
                    children =
                        listOf(
                            node(2, 2, delegate = "codebase_tree"),
                            node(3, 2, delegate = "project_search", isError = true),
                        ),
                ),
                nodesExecuted = 3,
            )

        // The root is a SUBQUERY, so it reaches no delegate itself - only its two children do.
        assertEquals(2, tree.delegateCallCount())
        assertEquals(2, tree.maxDepth())
        assertEquals(1, tree.errorCount())
        assertEquals("2 calls, depth 2/3, 1 failed", tree.summary())
    }

    @Test
    fun `summary stays singular and names truncation`() {
        val single = run(node(1, 1, delegate = "codebase_read"))
        assertEquals("1 call, depth 1/3", single.summary())

        val cut = run(node(1, 1), nodesExecuted = RlmLimits.MAX_NODES, truncated = true)
        assertEquals("0 calls, depth 1/3, truncated", cut.summary())
    }

    @Test
    fun `summary names cached calls, and only when there are some`() {
        // Absent from the wording at zero, so the ordinary run does not carry a "0 cached" that
        // reads like something happened.
        val uncached = run(node(1, 1, delegate = "codebase_tree"))
        assertEquals("1 call, depth 1/3", uncached.summary())

        val cached = run(node(1, 1, delegate = "codebase_tree"), cacheHits = 2)
        assertEquals("1 call, depth 1/3, 2 cached", cached.summary())
    }

    @Test
    fun `cached calls are counted apart from delegate nodes so the ledger can be reconciled`() {
        val tree =
            run(
                node(
                    1,
                    1,
                    label = "subquery",
                    children =
                        listOf(
                            node(2, 2, delegate = "codebase_tree"),
                            node(3, 2, delegate = "codebase_tree"),
                            node(4, 2, delegate = "codebase_tree"),
                        ),
                ),
                nodesExecuted = 4,
                cacheHits = 2,
            )

        // Three nodes name a delegate; only one call was actually made. The ledger records the
        // invocation, so the difference has to be readable somewhere - this is that somewhere.
        assertEquals(3, tree.delegateCallCount())
        assertEquals(2, tree.cacheHits)
        assertTrue(tree.summary().contains("2 cached"), tree.summary())
    }

    @Test
    fun `a refused node counts as an error even though it never ran`() {
        // What the budget does: the node exists in the tree so the gap is explained, and it is an
        // error because the query the caller asked for did not happen.
        val tree = run(node(1, 1, children = listOf(node(2, 2, isError = true, text = "budget exhausted"))), 2)

        assertEquals(1, tree.errorCount())
        assertTrue(tree.summary().contains("1 failed"), tree.summary())
    }

    // ------------------------------------------------------------------
    // Display rows
    // ------------------------------------------------------------------

    @Test
    fun `display rows indent each level and preview node text under it`() {
        val tree =
            run(
                node(
                    1,
                    1,
                    label = "root",
                    children = listOf(node(2, 2, label = "child", text = "hello\nworld")),
                ),
                nodesExecuted = 2,
            )

        val rows = buildRlmDisplayRows(listOf(tree))

        val expected =
            listOf(
                0 to "Run 1",
                1 to "root",
                2 to "child",
                3 to "hello",
                3 to "world",
            )
        assertEquals(expected, rows.map { it.indent to it.label })
        assertTrue(rows.first().isRoot)
        assertTrue(rows.drop(1).none { it.isRoot })
    }

    @Test
    fun `a preview is capped so one long node cannot bury the tree`() {
        val tree = run(node(1, 1, text = (1..10).joinToString("\n") { "line$it" }))

        val rows = buildRlmDisplayRows(listOf(tree))

        // header + the node + three preview lines, and nothing more.
        assertEquals(5, rows.size)
        assertEquals("line3", rows.last().label)
    }

    @Test
    fun `display rows carry the node id, its depth and the delegate that ran`() {
        val tree = run(node(1, 1, delegate = "codebase_tree"))

        val rows = buildRlmDisplayRows(listOf(tree))

        assertEquals("d1 #1 · codebase_tree", rows[1].detail)
        assertEquals("1 call, depth 1/3", rows[0].detail)
    }

    @Test
    fun `an errored node is marked in its own row and in its preview`() {
        val tree = run(node(1, 1, isError = true, text = "boom"))

        val rows = buildRlmDisplayRows(listOf(tree))

        assertTrue(rows[1].isError)
        assertTrue(rows[2].isError, "a preview line under a failed node belongs to that node's failure")
    }

    @Test
    fun `runs are numbered newest first`() {
        val log = RlmRunLog()
        log.record(run(node(1, 1)))
        log.record(run(node(2, 1)))

        val rows = buildRlmDisplayRows(log.runs.value)

        assertEquals(listOf("Run 1", "Run 2"), rows.filter { it.isRoot }.map { it.label })
        // Run 1 is the most recently recorded, so its tree is the second node.
        assertEquals("node-2", rows[1].label)
    }

    @Test
    fun `no runs means no rows`() {
        assertTrue(buildRlmDisplayRows(emptyList()).isEmpty())
    }

    // ------------------------------------------------------------------

    private fun node(
        id: Int,
        depth: Int,
        label: String = "node-$id",
        delegate: String? = null,
        isError: Boolean = false,
        text: String = "",
        children: List<RlmNode> = emptyList(),
    ) = RlmNode(id, depth, RlmAction.LIST_TREE, label, delegate, isError, text, children)

    private fun run(
        root: RlmNode,
        nodesExecuted: Int = 1,
        truncated: Boolean = false,
        cacheHits: Int = 0,
    ) = RlmRunResult(root, nodesExecuted, truncated, cacheHits)
}
