package ai.rever.boss.mcp.telemetry

/**
 * One frame as the sampler saw it, reduced to the parts aggregation needs.
 *
 * Deliberately not `StackTraceElement`: every function in [CollapsedStacks] is pure and is tested
 * without a live JVM, and a `StackTraceElement` cannot be constructed meaningfully in a fixture
 * without dragging a real stack in with it.
 */
internal data class SampledFrame(
    val declaringClass: String,
    val methodName: String,
    val lineNumber: Int,
) {
    /** The aggregation key. Line numbers vary within one method and must not split it. */
    val key: String get() = "$declaringClass.$methodName"
}

/**
 * A frame's share of the samples collected.
 *
 * [selfPercent] and [cumulativePercent] answer different questions and reviewers conflate them:
 * self is "the CPU was executing THIS frame", cumulative is "this frame was somewhere on the
 * stack". A dispatch method sits near 100% cumulative and near 0% self; the frame worth
 * optimising is the one with high self.
 */
internal data class FrameStat(
    val frame: String,
    val selfSamples: Int,
    val cumulativeSamples: Int,
    val selfPercent: Double,
    val cumulativePercent: Double,
    val hotLineNumber: Int?,
)

/**
 * Folds sampled stacks into per frame statistics.
 *
 * Pure, no I/O, no clock. This is where the profiler's arithmetic lives precisely so it can be
 * pinned by unit tests that need no child JVM: the live attach path is covered separately, and a
 * test that needs both is a test that fails for two unrelated reasons.
 */
internal object CollapsedStacks {
    /** Upper bound on [aggregate]'s `topK`, so a response cannot flood the model's context. */
    const val MAX_TOP_K: Int = 100

    /** Default number of frames reported. */
    const val DEFAULT_TOP_K: Int = 20

    /**
     * Per frame self and cumulative shares, hottest self time first.
     *
     * Each element of [stacks] is one sampled thread's stack, **top frame first**.
     *
     * A frame that recurses appears many times in one stack and is counted **once** for that
     * stack's cumulative tally. Counting each occurrence would let a recursive method report a
     * cumulative share above 100%, which is not a number anyone can act on.
     */
    fun aggregate(
        stacks: List<List<SampledFrame>>,
        topK: Int = DEFAULT_TOP_K,
    ): List<FrameStat> {
        val usable = stacks.filter { it.isNotEmpty() }
        if (usable.isEmpty()) return emptyList()

        val self = mutableMapOf<String, Int>()
        val cumulative = mutableMapOf<String, Int>()
        val selfLines = mutableMapOf<String, MutableMap<Int, Int>>()

        for (stack in usable) {
            val leaf = stack.first()
            self.merge(leaf.key, 1, Int::plus)
            if (leaf.lineNumber > 0) {
                selfLines.getOrPut(leaf.key) { mutableMapOf() }.merge(leaf.lineNumber, 1, Int::plus)
            }
            // Distinct, so recursion contributes one cumulative sample per stack, not one per level.
            for (key in stack.mapTo(mutableSetOf()) { it.key }) {
                cumulative.merge(key, 1, Int::plus)
            }
        }

        val total = usable.size.toDouble()
        val bounded = topK.coerceIn(1, MAX_TOP_K)
        return cumulative.keys
            .map { key ->
                val selfCount = self[key] ?: 0
                FrameStat(
                    frame = key,
                    selfSamples = selfCount,
                    cumulativeSamples = cumulative[key] ?: 0,
                    selfPercent = percent(selfCount, total),
                    cumulativePercent = percent(cumulative[key] ?: 0, total),
                    hotLineNumber = selfLines[key]?.maxByOrNull { it.value }?.key,
                )
            }
            // Tie broken by cumulative then name so the same input always produces the same
            // report: a response that reorders between identical runs is unreviewable.
            .sortedWith(
                compareByDescending<FrameStat> { it.selfSamples }
                    .thenByDescending { it.cumulativeSamples }
                    .thenBy { it.frame },
            ).take(bounded)
    }

    /**
     * Stacks in Brendan Gregg's folded format, `root;mid;leaf count`, one line per distinct stack.
     *
     * This is what every flamegraph renderer consumes, so emitting it means the profile can be
     * turned into a flamegraph without this code owning any rendering.
     */
    fun folded(stacks: List<List<SampledFrame>>): List<String> {
        val counts = mutableMapOf<String, Int>()
        for (stack in stacks) {
            if (stack.isEmpty()) continue
            // Folded format is root first; the sampler hands us leaf first.
            val line = stack.asReversed().joinToString(";") { it.key }
            counts.merge(line, 1, Int::plus)
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { "${it.key} ${it.value}" }
    }

    /** One decimal place, so a payload of many frames stays compact and diffable. */
    private fun percent(
        count: Int,
        total: Double,
    ): Double = kotlin.math.round(count * 1000.0 / total) / 10.0
}
