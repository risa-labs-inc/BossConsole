package ai.rever.boss.mastery

import kotlin.test.Test
import kotlin.test.assertEquals

class MasteryReplayPlannerTest {
    private val planner = MasteryReplayPlanner()

    @Test
    fun `linear chain replay collects downstream nodes`() {
        val nodes = listOf(
            MasteryNode("A", "p", "a"),
            MasteryNode("B", "p", "b"),
            MasteryNode("C", "p", "c"),
            MasteryNode("D", "p", "d"),
        )
        val edges = listOf(
            MasteryEdge("A", "B", "o", "i"),
            MasteryEdge("B", "C", "o", "i"),
            MasteryEdge("C", "D", "o", "i"),
        )
        val mastery = MasteryDefinition(id = "m", name = "m", description = "", nodes = nodes, edges = edges)

        val result = planner.nodesToReplay(mastery, "B").map { it.id }
        assertEquals(listOf("B", "C", "D"), result)
    }

    @Test
    fun `branching graph reuses parallel branch not downstream`() {
        //   A -> B -> D
        //    \-> C -/
        val nodes = listOf(
            MasteryNode("A", "p", "a"),
            MasteryNode("B", "p", "b"),
            MasteryNode("C", "p", "c"),
            MasteryNode("D", "p", "d"),
        )
        val edges = listOf(
            MasteryEdge("A", "B", "o", "i"),
            MasteryEdge("A", "C", "o", "i"),
            MasteryEdge("B", "D", "o", "i"),
            MasteryEdge("C", "D", "o", "i"),
        )
        val mastery = MasteryDefinition(id = "m2", name = "m2", description = "", nodes = nodes, edges = edges)

        val resultB = planner.nodesToReplay(mastery, "B").map { it.id }
        // Should rerun B and D only; C is unrelated to B's descendants
        assertEquals(listOf("B", "D"), resultB)

        val resultC = planner.nodesToReplay(mastery, "C").map { it.id }
        assertEquals(listOf("C", "D"), resultC)
    }
}
