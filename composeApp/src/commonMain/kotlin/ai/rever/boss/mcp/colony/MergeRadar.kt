package ai.rever.boss.mcp.colony

import kotlinx.serialization.Serializable

/** One file a worktree has changed, as the lines it added and removed. */
@Serializable
data class ColonyChangedFile(
    val path: String,
    val addedLines: List<String>,
    val removedLines: List<String>,
)

/** A worktree's in-progress change set. */
@Serializable
data class ColonyWorktreeDiff(
    val worktreeId: String,
    val files: List<ColonyChangedFile>,
)

/** How much two change sets look like they are about the same thing. */
@Serializable
enum class CollisionConfidence {
    LOW,
    MEDIUM,
    HIGH,
}

/**
 * What Merge Radar concluded about two worktrees.
 *
 * This is a *warning*. There is no action on it, no `resolve`, no `merge`, no `block`: the type has
 * no field a caller could act on, and nothing in this file writes to a worktree. It exists to be
 * shown in a grid and read by a human or an agent that is free to ignore it.
 */
@Serializable
data class ColonyCollisionAssessment(
    val worktrees: List<String>,
    val confidence: CollisionConfidence,
    /** The concrete overlaps found, each phrased so it can be shown without further formatting. */
    val semanticOverlap: List<String>,
    /** Why the confidence is what it is, in the order the signals were found. */
    val reasoning: List<String>,
)

/**
 * Merge Radar: semantic collision prediction between two worktrees' in-progress changes.
 *
 * **What it is not.** It does not call a model. There is no RLM-lite in this tree - no reasoning
 * action loop, no depth cap, nothing to reuse - and inventing a second reasoning engine was
 * explicitly out of scope, so this is a deterministic analysis over the *symbols* a diff declares
 * and references. It answers a narrower question than a model would, and it answers it the same way
 * twice.
 *
 * **What it does.** The reason a path-based check is not enough is that two worktrees routinely
 * implement the same interface in different files, or rename a symbol the other one still calls,
 * and neither shows up as a file collision. So the comparison is over identifiers:
 *
 * - a symbol **declared** on both sides, in different files: two implementations of one thing;
 * - a symbol **declared on one side and referenced on the other**: one is building what the other
 *   is still calling, which is the rename case;
 * - a symbol **removed on one side and referenced on the other**: the caller breaks.
 *
 * Confidence is then a function of which signals fired, not of how many lines changed: a shared
 * declaration is `HIGH` on its own, a one-sided reference is `MEDIUM`, and `LOW` means nothing
 * recognisable overlapped - which is a statement about this analysis, not a promise that merging is
 * safe.
 */
object MergeRadar {
    /**
     * How long a snapshot is trusted before the radar will look again.
     *
     * Chosen against the cost of the work, not against a latency budget: a full re-index of every
     * active worktree is linear in total changed lines, and nothing about a collision warning needs
     * to be fresher than the agent's own edit cadence. Fifteen seconds is slower than a human
     * notices a badge change and far slower than an edit, so the common case - an agent making a
     * burst of edits - collapses into one analysis per burst.
     */
    const val MIN_ANALYSIS_INTERVAL_MS: Long = 15_000L

    /** Declarations whose names are worth comparing, per language family. */
    private val DECLARATION_PATTERN =
        Regex(
            """^\s*(?:public |private |internal |protected |open |abstract )*""" +
                """(?:final |sealed |data |suspend |override )*""" +
                """(?:class|interface|object|enum class|fun|val|var|def|function|struct|type|const)\s+""" +
                """([A-Za-z_][A-Za-z0-9_]*)""",
        )

    /** Identifiers referenced anywhere in a line, used for the one-sided-reference signals. */
    private val IDENTIFIER_PATTERN = Regex("""[A-Za-z_][A-Za-z0-9_]{2,}""")

    /**
     * Words that carry no signal. Small on purpose: a stop list that grows turns the analysis into a
     * keyword filter, and a keyword filter is exactly the file-path check this replaces.
     */
    private val NOISE =
        setOf(
            "the",
            "and",
            "for",
            "with",
            "this",
            "that",
            "from",
            "into",
            "null",
            "true",
            "false",
            "val",
            "var",
            "fun",
            "class",
            "object",
            "interface",
            "return",
            "import",
            "package",
            "private",
            "public",
            "internal",
            "override",
            "suspend",
            "else",
            "when",
            "where",
            "it",
        )

    /**
     * Compare two worktrees.
     *
     * The *verdict* is symmetric: swapping the two sides cannot change the confidence, because every
     * signal is an intersection and the filters around them are symmetric. The wording is not. The
     * overlap and reasoning lines name each worktree in the role it played, so a reader can tell
     * which side declares and which side calls, and [ColonyCollisionAssessment.worktrees] is in the
     * order the pair was given.
     *
     * A worktree compared with itself reports no collision - a single agent editing one worktree
     * cannot collide with itself, and reporting otherwise would make every cell warn.
     */
    fun assess(
        left: ColonyWorktreeDiff,
        right: ColonyWorktreeDiff,
    ): ColonyCollisionAssessment {
        if (left.worktreeId == right.worktreeId) return sameWorktree(left.worktreeId)

        val leftIndex = SymbolIndex.of(left)
        val rightIndex = SymbolIndex.of(right)
        val findings =
            listOf(
                sharedDeclarations(leftIndex, rightIndex),
                oneSidedReferences(left, right, leftIndex, rightIndex),
                removalsWithLiveCallers(left, right, leftIndex, rightIndex),
            )
        // The verdict is the strongest signal that fired. Every signal is an intersection, so
        // swapping the two sides cannot change which one that is.
        val confidence = findings.mapNotNull { it.confidence }.maxByOrNull { it.ordinal } ?: CollisionConfidence.LOW
        return ColonyCollisionAssessment(
            worktrees = listOf(left.worktreeId, right.worktreeId),
            confidence = confidence,
            semanticOverlap = findings.flatMap { it.overlap },
            reasoning = findings.mapNotNull { it.reason }.ifEmpty { listOf(noOverlapReason(left, right)) },
        )
    }

    /** What one detection signal found: the lines it contributes, its reason, and how severe a hit is. */
    private data class SignalFinding(
        val overlap: List<String>,
        val reason: String?,
        val confidence: CollisionConfidence?,
    )

    private fun sameWorktree(worktreeId: String) =
        ColonyCollisionAssessment(
            worktrees = listOf(worktreeId),
            confidence = CollisionConfidence.LOW,
            semanticOverlap = emptyList(),
            reasoning = listOf("Same worktree on both sides; nothing to compare."),
        )

    private fun noOverlapReason(
        left: ColonyWorktreeDiff,
        right: ColonyWorktreeDiff,
    ): String =
        "no shared declaration, no one-sided reference and no removal with a live caller " +
            "across ${left.files.size + right.files.size} changed file(s)"

    /** 1. Declared on both sides, in different files: two implementations of one thing. */
    private fun sharedDeclarations(
        leftIndex: SymbolIndex,
        rightIndex: SymbolIndex,
    ): SignalFinding {
        val symbols =
            (leftIndex.declared intersect rightIndex.declared)
                .filter { symbol -> leftIndex.declarationFiles(symbol) != rightIndex.declarationFiles(symbol) }
                .sorted()
        return SignalFinding(
            overlap =
                symbols.map { symbol ->
                    "'$symbol' is declared in both worktrees (${leftIndex.declarationFiles(symbol)} " +
                        "and ${rightIndex.declarationFiles(symbol)})"
                },
            reason =
                symbols
                    .takeIf { it.isNotEmpty() }
                    ?.let { "${it.size} symbol(s) declared on both sides in different files" },
            confidence = CollisionConfidence.HIGH.takeIf { symbols.isNotEmpty() },
        )
    }

    /** 2. Declared on one side, referenced on the other: the rename / still-calling case. */
    private fun oneSidedReferences(
        left: ColonyWorktreeDiff,
        right: ColonyWorktreeDiff,
        leftIndex: SymbolIndex,
        rightIndex: SymbolIndex,
    ): SignalFinding {
        val declaredLeftReferencedRight = (leftIndex.declared intersect rightIndex.referenced).sorted()
        val declaredRightReferencedLeft = (rightIndex.declared intersect leftIndex.referenced).sorted()
        val overlap =
            declaredLeftReferencedRight.map {
                "'$it' is declared by ${left.worktreeId} and referenced by ${right.worktreeId}"
            } +
                declaredRightReferencedLeft.map {
                    "'$it' is declared by ${right.worktreeId} and referenced by ${left.worktreeId}"
                }
        val count = declaredLeftReferencedRight.size + declaredRightReferencedLeft.size
        return SignalFinding(
            overlap = overlap,
            reason = if (count > 0) "one side declares a symbol the other still references ($count)" else null,
            confidence = CollisionConfidence.MEDIUM.takeIf { count > 0 },
        )
    }

    /** 3. Removed on one side, referenced on the other: the caller breaks. */
    private fun removalsWithLiveCallers(
        left: ColonyWorktreeDiff,
        right: ColonyWorktreeDiff,
        leftIndex: SymbolIndex,
        rightIndex: SymbolIndex,
    ): SignalFinding {
        val removedLeftReferencedRight = (leftIndex.removed intersect rightIndex.referenced).sorted()
        val removedRightReferencedLeft = (rightIndex.removed intersect leftIndex.referenced).sorted()
        val overlap =
            removedLeftReferencedRight.map {
                "'$it' is removed by ${left.worktreeId} and still referenced by ${right.worktreeId}"
            } +
                removedRightReferencedLeft.map {
                    "'$it' is removed by ${right.worktreeId} and still referenced by ${left.worktreeId}"
                }
        val count = removedLeftReferencedRight.size + removedRightReferencedLeft.size
        return SignalFinding(
            overlap = overlap,
            reason = if (count > 0) "one side removes a symbol the other still references" else null,
            confidence = CollisionConfidence.HIGH.takeIf { count > 0 },
        )
    }

    /** Every unordered pair in [diffs], with the assessments that are not [CollisionConfidence.LOW]. */
    fun scan(diffs: List<ColonyWorktreeDiff>): List<ColonyCollisionAssessment> =
        diffs
            .flatMapIndexed { index, left ->
                diffs.drop(index + 1).map { right -> assess(left, right) }
            }.filter { it.confidence != CollisionConfidence.LOW }

    /** A stable digest of a diff set, so the scheduler can tell "nothing changed" from "re-analyse". */
    fun digestOf(diffs: List<ColonyWorktreeDiff>): String =
        ColonyProtocol.contentId(
            "radar-",
            diffs
                .sortedBy { it.worktreeId }
                .flatMap { diff ->
                    listOf(diff.worktreeId) +
                        diff.files.sortedBy { it.path }.flatMap { listOf(it.path) + it.addedLines + it.removedLines }
                },
        )

    /** The identifiers a diff declares, references and removes, extracted once per worktree. */
    private class SymbolIndex(
        val declared: Set<String>,
        val referenced: Set<String>,
        val removed: Set<String>,
        private val declaredIn: Map<String, List<String>>,
    ) {
        fun declarationFiles(symbol: String): String {
            val files = declaredIn[symbol].orEmpty().sorted()
            return files.joinToString(", ").ifEmpty { "(unknown)" }
        }

        companion object {
            fun of(diff: ColonyWorktreeDiff): SymbolIndex {
                val declared = mutableMapOf<String, MutableList<String>>()
                val referenced = mutableSetOf<String>()
                val removed = mutableSetOf<String>()
                diff.files.forEach { file ->
                    file.addedLines.forEach { line ->
                        val declaredName = DECLARATION_PATTERN.find(line)?.groupValues?.get(1)
                        if (declaredName != null) {
                            declared.getOrPut(declaredName) { mutableListOf() } += file.path
                        }
                        referenced += identifiersIn(line)
                    }
                    file.removedLines.forEach { line ->
                        val removedName = DECLARATION_PATTERN.find(line)?.groupValues?.get(1)
                        if (removedName != null) removed += removedName
                    }
                }
                return SymbolIndex(
                    declared = declared.keys,
                    referenced = referenced,
                    removed = removed,
                    declaredIn = declared,
                )
            }

            private fun identifiersIn(line: String): List<String> =
                IDENTIFIER_PATTERN
                    .findAll(line)
                    .map { it.value }
                    .filter { it.lowercase() !in NOISE }
                    .toList()
        }
    }
}

/**
 * Decides when Merge Radar runs, so an expensive analysis is not run per tool call.
 *
 * Two gates, both cheap: a minimum wall-clock interval ([MergeRadar.MIN_ANALYSIS_INTERVAL_MS]) and a
 * content gate - the combined digest of the worktree diffs must have changed since the last
 * analysis. The interval alone would re-analyse an idle swarm forever; the digest alone would
 * re-analyse on every keystroke. Together, an idle swarm costs nothing and an editing swarm costs at
 * most one analysis per interval.
 *
 * Not thread-safe by construction: it is owned by whatever drives the swarm grid, and a caller that
 * shares it across threads should hold it under the same lock it holds the grid.
 */
class MergeRadarScheduler(
    private val minIntervalMs: Long = MergeRadar.MIN_ANALYSIS_INTERVAL_MS,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private var lastRunAtMs: Long? = null
    private var lastDigest: String? = null

    /** True when [diffs] should be analysed now. Does not record the run; call [record]. */
    fun shouldAnalyze(diffs: List<ColonyWorktreeDiff>): Boolean {
        if (diffs.size < 2 || MergeRadar.digestOf(diffs) == lastDigest) return false
        return lastRunAtMs?.let { previous -> now() - previous >= minIntervalMs } ?: true
    }

    /**
     * Record that [diffs] were analysed.
     *
     * Called even when the analysis found nothing, which is the point: a digest that only advances
     * on a hit would re-analyse the same clean pair on every interval forever.
     */
    fun record(diffs: List<ColonyWorktreeDiff>) {
        lastRunAtMs = now()
        lastDigest = MergeRadar.digestOf(diffs)
    }
}
