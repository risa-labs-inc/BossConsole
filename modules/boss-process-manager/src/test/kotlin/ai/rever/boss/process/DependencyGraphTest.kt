package ai.rever.boss.process

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DependencyGraphTest {

    // ── Empty graph ─────────────────────────────────────────────────────

    @Test
    fun `empty graph returns empty startup order`() {
        val graph = DependencyGraph()
        val order = graph.getStartupOrder()
        assertTrue(order.isEmpty())
    }

    @Test
    fun `empty graph has size zero`() {
        val graph = DependencyGraph()
        assertEquals(0, graph.size)
    }

    // ── Single process with no dependencies ─────────────────────────────

    @Test
    fun `single process with no deps returns one level`() {
        val graph = DependencyGraph()
        graph.addProcess("A")

        val order = graph.getStartupOrder()
        assertEquals(1, order.size)
        assertEquals(listOf("A"), order[0])
    }

    @Test
    fun `single process canStart with empty running set`() {
        val graph = DependencyGraph()
        graph.addProcess("A")

        assertTrue(graph.canStart("A", emptySet()))
    }

    // ── Linear dependency chain (A -> B -> C) ───────────────────────────

    @Test
    fun `linear chain produces three levels`() {
        val graph = DependencyGraph()
        // C has no deps, B depends on C, A depends on B
        graph.addProcess("C")
        graph.addProcess("B", listOf("C"))
        graph.addProcess("A", listOf("B"))

        val order = graph.getStartupOrder()
        assertEquals(3, order.size)
        assertTrue("C" in order[0])
        assertTrue("B" in order[1])
        assertTrue("A" in order[2])
    }

    @Test
    fun `linear chain canStart checks`() {
        val graph = DependencyGraph()
        graph.addProcess("C")
        graph.addProcess("B", listOf("C"))
        graph.addProcess("A", listOf("B"))

        // A cannot start until B is running
        assertFalse(graph.canStart("A", emptySet()))
        assertFalse(graph.canStart("A", setOf("C")))
        assertTrue(graph.canStart("A", setOf("B")))
        assertTrue(graph.canStart("A", setOf("B", "C")))

        // B cannot start until C is running
        assertFalse(graph.canStart("B", emptySet()))
        assertTrue(graph.canStart("B", setOf("C")))

        // C can always start
        assertTrue(graph.canStart("C", emptySet()))
    }

    // ── Diamond dependency ──────────────────────────────────────────────
    //   D depends on A and B; A depends on C; B depends on C

    @Test
    fun `diamond dependency produces correct levels`() {
        val graph = DependencyGraph()
        graph.addProcess("C")
        graph.addProcess("A", listOf("C"))
        graph.addProcess("B", listOf("C"))
        graph.addProcess("D", listOf("A", "B"))

        val order = graph.getStartupOrder()
        assertEquals(3, order.size)

        // Level 0: C (no deps)
        assertEquals(listOf("C"), order[0])

        // Level 1: A and B (both depend only on C) — order within level may vary
        assertEquals(setOf("A", "B"), order[1].toSet())

        // Level 2: D (depends on A and B)
        assertEquals(listOf("D"), order[2])
    }

    @Test
    fun `diamond dependency canStart for D`() {
        val graph = DependencyGraph()
        graph.addProcess("C")
        graph.addProcess("A", listOf("C"))
        graph.addProcess("B", listOf("C"))
        graph.addProcess("D", listOf("A", "B"))

        assertFalse(graph.canStart("D", emptySet()))
        assertFalse(graph.canStart("D", setOf("A")))
        assertFalse(graph.canStart("D", setOf("B")))
        assertTrue(graph.canStart("D", setOf("A", "B")))
    }

    // ── Circular dependency detection ───────────────────────────────────

    @Test
    fun `circular dependency throws IllegalStateException`() {
        val graph = DependencyGraph()
        graph.addProcess("A", listOf("B"))
        graph.addProcess("B", listOf("A"))

        assertFailsWith<IllegalStateException> {
            graph.getStartupOrder()
        }
    }

    @Test
    fun `three-way circular dependency throws IllegalStateException`() {
        val graph = DependencyGraph()
        graph.addProcess("A", listOf("B"))
        graph.addProcess("B", listOf("C"))
        graph.addProcess("C", listOf("A"))

        assertFailsWith<IllegalStateException> {
            graph.getStartupOrder()
        }
    }

    // ── canStart with running processes ─────────────────────────────────

    @Test
    fun `canStart returns true for unknown process`() {
        val graph = DependencyGraph()
        // Process not in graph — no deps known, so it can start
        assertTrue(graph.canStart("unknown", emptySet()))
    }

    @Test
    fun `canStart returns true when all deps are running`() {
        val graph = DependencyGraph()
        graph.addProcess("svc", listOf("auth", "db"))

        assertFalse(graph.canStart("svc", setOf("auth")))
        assertFalse(graph.canStart("svc", setOf("db")))
        assertTrue(graph.canStart("svc", setOf("auth", "db")))
        assertTrue(graph.canStart("svc", setOf("auth", "db", "extra")))
    }

    // ── getDependents ───────────────────────────────────────────────────

    @Test
    fun `getDependents returns empty for leaf node`() {
        val graph = DependencyGraph()
        graph.addProcess("C")
        graph.addProcess("B", listOf("C"))
        graph.addProcess("A", listOf("B"))

        // A is the leaf — nothing depends on it
        assertEquals(emptySet(), graph.getDependents("A"))
    }

    @Test
    fun `getDependents returns direct dependents`() {
        val graph = DependencyGraph()
        graph.addProcess("C")
        graph.addProcess("B", listOf("C"))
        graph.addProcess("A", listOf("C"))

        // Both A and B depend on C directly
        assertEquals(setOf("A", "B"), graph.getDependents("C"))
    }

    @Test
    fun `getDependents returns transitive dependents`() {
        val graph = DependencyGraph()
        graph.addProcess("C")
        graph.addProcess("B", listOf("C"))
        graph.addProcess("A", listOf("B"))

        // A depends on B which depends on C, so both are dependents of C
        assertEquals(setOf("B", "A"), graph.getDependents("C"))
    }

    @Test
    fun `getDependents in diamond returns all downstream`() {
        val graph = DependencyGraph()
        graph.addProcess("C")
        graph.addProcess("A", listOf("C"))
        graph.addProcess("B", listOf("C"))
        graph.addProcess("D", listOf("A", "B"))

        // C's dependents: A, B, and D (transitively via A and B)
        assertEquals(setOf("A", "B", "D"), graph.getDependents("C"))

        // A's dependents: only D
        assertEquals(setOf("D"), graph.getDependents("A"))
    }

    @Test
    fun `getDependents for unknown node returns empty`() {
        val graph = DependencyGraph()
        graph.addProcess("A")

        assertEquals(emptySet(), graph.getDependents("unknown"))
    }
}
