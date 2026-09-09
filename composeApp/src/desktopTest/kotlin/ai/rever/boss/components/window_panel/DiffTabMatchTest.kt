package ai.rever.boss.components.window_panel

import ai.rever.boss.plugin.tab.diff.DiffTabInfo
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The diff-tab reuse semantics, pinned without a UI: [diffTabMatches] is the
 * predicate `findPanelWithDiffTab` runs over the tab list, and a wrong answer
 * either duplicates a tab the user can see or - worse - focuses a DIFFERENT
 * view than the one asked for (a staged diff is not a working-tree diff).
 */
class DiffTabMatchTest {
    @Test
    fun `a working-tree file diff matches its own scope`() {
        val tab = DiffTabInfo.create(filePath = "a/b.kt")
        assertTrue(diffTabMatches(tab, "a/b.kt", staged = false, fromRef = null, toRef = null))
    }

    @Test
    fun `the same file spelled differently matches`() {
        val tab = DiffTabInfo.create(filePath = "a/b.kt")
        assertTrue(diffTabMatches(tab, "a//b.kt", staged = false, fromRef = null, toRef = null))
    }

    @Test
    fun `a staged diff is a different view from a working-tree diff`() {
        val workingTree = DiffTabInfo.create(filePath = "a/b.kt", staged = false)
        val staged = DiffTabInfo.create(filePath = "a/b.kt", staged = true)

        assertFalse(diffTabMatches(workingTree, "a/b.kt", staged = true, fromRef = null, toRef = null))
        assertFalse(diffTabMatches(staged, "a/b.kt", staged = false, fromRef = null, toRef = null))
        assertTrue(diffTabMatches(staged, "a/b.kt", staged = true, fromRef = null, toRef = null))
    }

    @Test
    fun `a commit diff restricted to a file is not the file diff`() {
        val commit = DiffTabInfo.create(filePath = "a/b.kt", fromRef = "abc123")
        val fileDiff = DiffTabInfo.create(filePath = "a/b.kt")

        assertFalse(diffTabMatches(fileDiff, "a/b.kt", staged = false, fromRef = "abc123", toRef = null))
        assertFalse(diffTabMatches(commit, "a/b.kt", staged = false, fromRef = null, toRef = null))
        assertTrue(diffTabMatches(commit, "a/b.kt", staged = false, fromRef = "abc123", toRef = null))
    }

    @Test
    fun `a ref-range diff matches only its exact refs`() {
        val range = DiffTabInfo.create(filePath = "", fromRef = "a1", toRef = "b2")

        assertFalse(diffTabMatches(range, "", staged = false, fromRef = "a1", toRef = null))
        assertFalse(diffTabMatches(range, "", staged = false, fromRef = "a1", toRef = "c3"))
        assertTrue(diffTabMatches(range, "", staged = false, fromRef = "a1", toRef = "b2"))
    }

    @Test
    fun `a blank query path with no refs never matches`() {
        // normalize("") is "", so without the blank guard a blank, refless
        // query would focus the first diff tab whose filePath is also blank
        // - whatever its actual scope.
        val fileDiff = DiffTabInfo.create(filePath = "")

        assertFalse(diffTabMatches(fileDiff, "", staged = false, fromRef = null, toRef = null))
        assertFalse(diffTabMatches(fileDiff, "   ", staged = false, fromRef = null, toRef = null))
    }

    @Test
    fun `a blank path with refs is the range-diff scope and matches its own tab`() {
        // A commit/range diff of the whole tree carries a blank filePath - that
        // scope must still reuse its tab, or every second click opens a
        // duplicate.
        val range = DiffTabInfo.create(filePath = "", fromRef = "a1", toRef = "b2")
        assertTrue(diffTabMatches(range, "", staged = false, fromRef = "a1", toRef = "b2"))
        assertFalse(diffTabMatches(range, "", staged = false, fromRef = "a1", toRef = null))
    }

    @Test
    fun `different files never match`() {
        val tab = DiffTabInfo.create(filePath = "a/b.kt")
        assertFalse(diffTabMatches(tab, "a/c.kt", staged = false, fromRef = null, toRef = null))
        assertFalse(diffTabMatches(tab, "other/b.kt", staged = false, fromRef = null, toRef = null))
    }
}
