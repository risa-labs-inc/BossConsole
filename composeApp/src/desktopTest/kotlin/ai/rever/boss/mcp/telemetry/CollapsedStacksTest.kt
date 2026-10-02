package ai.rever.boss.mcp.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [CollapsedStacks], the profiler's arithmetic.
 *
 * Deliberately needs no child JVM and no attach: the live path is covered separately, and a test
 * that needed both would fail for two unrelated reasons.
 */
class CollapsedStacksTest {
    private fun frame(
        name: String,
        line: Int = 1,
    ): SampledFrame =
        SampledFrame(
            declaringClass = name.substringBeforeLast('.'),
            methodName = name.substringAfterLast('.'),
            lineNumber = line,
        )

    /** Leaf first, the order the sampler produces. */
    private fun stack(vararg names: String): List<SampledFrame> = names.map { frame(it) }

    @Test
    fun `self counts only the leaf while cumulative counts the whole stack`() {
        val stats =
            CollapsedStacks.aggregate(
                listOf(
                    stack("a.Leaf.work", "a.Mid.dispatch", "a.Root.main"),
                    stack("a.Leaf.work", "a.Mid.dispatch", "a.Root.main"),
                ),
            )
        val byFrame = stats.associateBy { it.frame }

        assertEquals(2, byFrame.getValue("a.Leaf.work").selfSamples)
        assertEquals(100.0, byFrame.getValue("a.Leaf.work").selfPercent)

        // The dispatcher is on every stack but never executing.
        assertEquals(0, byFrame.getValue("a.Mid.dispatch").selfSamples)
        assertEquals(0.0, byFrame.getValue("a.Mid.dispatch").selfPercent)
        assertEquals(100.0, byFrame.getValue("a.Mid.dispatch").cumulativePercent)
    }

    @Test
    fun `a recursive frame cannot exceed one hundred percent cumulative`() {
        // The whole reason aggregate folds each stack through a Set first.
        val recursive = stack("a.R.step", "a.R.step", "a.R.step", "a.R.step", "a.Root.main")
        val stats = CollapsedStacks.aggregate(listOf(recursive))
        val r = stats.single { it.frame == "a.R.step" }

        assertEquals(1, r.cumulativeSamples, "four levels of one stack is still one sample")
        assertEquals(100.0, r.cumulativePercent)
        assertTrue(stats.all { it.cumulativePercent <= 100.0 })
    }

    @Test
    fun `hottest self time is reported first`() {
        val stats =
            CollapsedStacks.aggregate(
                listOf(
                    stack("a.Hot.burn", "a.Root.main"),
                    stack("a.Hot.burn", "a.Root.main"),
                    stack("a.Hot.burn", "a.Root.main"),
                    stack("a.Cold.idle", "a.Root.main"),
                ),
            )
        assertEquals("a.Hot.burn", stats.first().frame)
        assertEquals(75.0, stats.first().selfPercent)
    }

    @Test
    fun `topK is bounded so a response cannot flood the model context`() {
        val stacks = (1..500).map { stack("a.C$it.m", "a.Root.main") }

        assertEquals(5, CollapsedStacks.aggregate(stacks, topK = 5).size)
        // Above the ceiling, clamp rather than honour the request.
        assertEquals(CollapsedStacks.MAX_TOP_K, CollapsedStacks.aggregate(stacks, topK = 10_000).size)
        // Zero and negative are nonsense; clamp up rather than return nothing.
        assertEquals(1, CollapsedStacks.aggregate(stacks, topK = 0).size)
        assertEquals(1, CollapsedStacks.aggregate(stacks, topK = -7).size)
    }

    @Test
    fun `line numbers report the most sampled line and tolerate absence`() {
        val stats =
            CollapsedStacks.aggregate(
                listOf(
                    listOf(frame("a.L.m", line = 142)),
                    listOf(frame("a.L.m", line = 142)),
                    listOf(frame("a.L.m", line = 7)),
                    // A native frame has no line number and must not become the answer.
                    listOf(frame("a.N.m", line = -2)),
                ),
            )
        assertEquals(142, stats.single { it.frame == "a.L.m" }.hotLineNumber)
        assertNull(stats.single { it.frame == "a.N.m" }.hotLineNumber)
    }

    @Test
    fun `empty input yields no frames rather than dividing by zero`() {
        assertEquals(emptyList(), CollapsedStacks.aggregate(emptyList()))
        assertEquals(emptyList(), CollapsedStacks.aggregate(listOf(emptyList(), emptyList())))
    }

    @Test
    fun `ordering is stable for identical input`() {
        val stacks = listOf(stack("a.X.m", "a.Root.main"), stack("a.Y.m", "a.Root.main"))
        assertEquals(
            CollapsedStacks.aggregate(stacks).map { it.frame },
            CollapsedStacks.aggregate(stacks).map { it.frame },
        )
    }

    @Test
    fun `folded output is root first and counts identical stacks`() {
        val folded =
            CollapsedStacks.folded(
                listOf(
                    stack("a.Leaf.work", "a.Root.main"),
                    stack("a.Leaf.work", "a.Root.main"),
                    stack("a.Other.work", "a.Root.main"),
                ),
            )
        // Root first is what every flamegraph renderer expects.
        assertEquals("a.Root.main;a.Leaf.work 2", folded.first())
        assertTrue(folded.contains("a.Root.main;a.Other.work 1"))
    }
}
