package ai.rever.boss.mcp.colony

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two Colony pieces that are not negotiation: the shared scratchpad and Merge Radar.
 *
 * Both are checked against the properties the feature claims and nothing else - that a board is
 * shared between worktrees within one session and nowhere else, and that a collision warning fires
 * on *meaning* rather than on file paths, which is the whole reason a path-based check was not
 * enough.
 */
class ColonyBrainAndRadarTest {
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun brain(): ColonyBrain {
        val dir = createTempDirectory("colony-brain-test").toFile()
        tempDirs.add(dir)
        return ColonyBrain(rootDir = dir)
    }

    private fun diff(
        worktreeId: String,
        path: String,
        added: List<String>,
        removed: List<String> = emptyList(),
    ) = ColonyWorktreeDiff(
        worktreeId = worktreeId,
        files = listOf(ColonyChangedFile(path = path, addedLines = added, removedLines = removed)),
    )

    // ---------------------------------------------------------------- the brain

    @Test
    fun `two worktrees share one session board`() {
        val brain = brain()
        val first = brain.write("session-1", "worktree-1", "renamed Foo to Bar")
        assertNotNull(first)
        brain.write("session-1", "worktree-2", "updated the Foo callers")

        val board = brain.read("session-1")
        assertEquals(listOf("worktree-1", "worktree-2"), board.map { it.worktreeId })
        assertEquals("renamed Foo to Bar", board.first().note)
        assertTrue(board.all { it.sessionId == "session-1" })
        assertEquals(board.size, board.map { it.id }.toSet().size, "notes get distinct ids")

        // A board belongs to one session: nothing is shared across sessions, and clearing a session
        // takes only its own board.
        assertTrue(brain.read("session-2").isEmpty())
        assertTrue(brain.clearSession("session-1"))
        assertTrue(brain.read("session-1").isEmpty())
        assertTrue(brain.read("session-2").isEmpty())
    }

    @Test
    fun `a session id that is a path is refused`() {
        val brain = brain()
        assertNull(brain.write("../../etc", "worktree-1", "note"), "a traversal must not become a path")
        assertTrue(brain.read("../../etc").isEmpty())
        assertTrue(brain.read("a/b").isEmpty())
        assertTrue(brain.read(".").isEmpty())

        assertTrue(ColonyBrain.isSafeSessionId("session-1"))
        assertFalse(ColonyBrain.isSafeSessionId("a/b"))
        assertFalse(ColonyBrain.isSafeSessionId(".."))
        assertFalse(ColonyBrain.isSafeSessionId(""))
    }

    // ------------------------------------------------------------ merge radar

    @Test
    fun `merge radar finds a semantic collision between file-disjoint diffs`() {
        // Two worktrees, no shared path and no shared line: a path-based check sees nothing here.
        val left =
            diff(
                "worktree-1",
                "src/shapes/Parser.kt",
                listOf("class ShapeParser {", "    fun parseShape(input: String): Shape = TODO()"),
            )
        val right =
            diff(
                "worktree-2",
                "src/render/Renderer.kt",
                listOf("class ShapeParser {", "    fun renderShape(shape: Shape) = TODO()"),
            )
        assertTrue(left.files.none { it.path in right.files.map { file -> file.path } })

        val assessment = MergeRadar.assess(left, right)
        assertEquals(listOf("worktree-1", "worktree-2"), assessment.worktrees)
        assertEquals(CollisionConfidence.HIGH, assessment.confidence)
        assertTrue(
            assessment.semanticOverlap.any { it.contains("ShapeParser") },
            assessment.semanticOverlap.toString(),
        )
        assertTrue(assessment.reasoning.isNotEmpty())

        // The verdict does not depend on which side was passed first, and the sweep over a set of
        // worktrees reports the same pair.
        assertEquals(assessment.confidence, MergeRadar.assess(right, left).confidence)
        assertEquals(listOf(assessment.confidence), MergeRadar.scan(listOf(left, right)).map { it.confidence })
    }

    @Test
    fun `merge radar warns when one worktree removes a symbol the other still calls`() {
        val left =
            diff(
                "worktree-1",
                "src/a/Alpha.kt",
                added = listOf("class AlphaWriter {"),
                removed = listOf("class OldAlphaWriter {"),
            )
        val right = diff("worktree-2", "src/b/Beta.kt", added = listOf("    fun use() = OldAlphaWriter()"))

        val assessment = MergeRadar.assess(left, right)
        assertEquals(CollisionConfidence.HIGH, assessment.confidence)
        assertTrue(
            assessment.semanticOverlap.any { it.contains("OldAlphaWriter") },
            assessment.semanticOverlap.toString(),
        )
    }

    @Test
    fun `merge radar reports no collision for changes that share no symbols`() {
        val left =
            diff(
                "worktree-1",
                "src/a/Alpha.kt",
                listOf("class AlphaWriter {", "    fun writeAlpha(payload: AlphaPayload) = TODO()"),
            )
        val right =
            diff(
                "worktree-2",
                "src/b/Beta.kt",
                listOf("class BetaReader {", "    fun readBeta(source: BetaSource) = TODO()"),
            )

        val assessment = MergeRadar.assess(left, right)
        assertEquals(CollisionConfidence.LOW, assessment.confidence)
        assertTrue(assessment.semanticOverlap.isEmpty(), assessment.semanticOverlap.toString())
        assertTrue(assessment.reasoning.single().contains("no shared declaration"), assessment.reasoning.toString())
        assertTrue(MergeRadar.scan(listOf(left, right)).isEmpty(), "a LOW pair is not surfaced")
    }

    @Test
    fun `a worktree compared with itself reports no collision`() {
        val one = diff("worktree-1", "src/a/Alpha.kt", listOf("class AlphaWriter {"))
        val assessment = MergeRadar.assess(one, one)
        assertEquals(CollisionConfidence.LOW, assessment.confidence)
        assertEquals(listOf("worktree-1"), assessment.worktrees)
        assertTrue(assessment.semanticOverlap.isEmpty())
    }

    @Test
    fun `the scheduler does not re-analyse inside its interval`() {
        var now = 1_000L
        val scheduler = MergeRadarScheduler(minIntervalMs = 15_000L, now = { now })
        val first = diff("worktree-1", "src/a/Alpha.kt", listOf("class AlphaWriter {"))
        val second = diff("worktree-2", "src/b/Beta.kt", listOf("class BetaReader {"))

        assertTrue(scheduler.shouldAnalyze(listOf(first, second)))
        scheduler.record(listOf(first, second))

        // Unchanged content is never re-analysed, however long the swarm sits idle.
        now += 60_000L
        assertFalse(scheduler.shouldAnalyze(listOf(first, second)), "an idle swarm costs nothing")

        // Changed content after the interval is analysed, and the run is recorded even though it
        // found nothing, so the same pair is not re-analysed forever.
        val changed = diff("worktree-2", "src/b/Beta.kt", listOf("class BetaReader {", "    fun readBeta() = TODO()"))
        assertTrue(scheduler.shouldAnalyze(listOf(first, changed)))
        scheduler.record(listOf(first, changed))

        // Changed again straight away: held back until the interval elapses.
        val changedAgain =
            diff(
                "worktree-2",
                "src/b/Beta.kt",
                listOf("class BetaReader {", "    fun readBeta() = TODO()", "    fun close() = TODO()"),
            )
        assertFalse(scheduler.shouldAnalyze(listOf(first, changedAgain)), "at most one analysis per interval")
        now += 15_000L
        assertTrue(scheduler.shouldAnalyze(listOf(first, changedAgain)))

        // One worktree is not a pair.
        assertFalse(scheduler.shouldAnalyze(listOf(first)))
    }
}
