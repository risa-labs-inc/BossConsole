package ai.rever.boss.components.bars.horizontal

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the editor breadcrumb in the bottom bar.
 *
 * [editorBreadcrumbSegments] never reads the host's separator, so every case here asserts the same
 * thing on every CI runner. The Windows cases are the ones the previous inline version failed:
 * it appended `/` to a `C:\…` project path, the prefix never matched, and the breadcrumb showed
 * the whole absolute path as one segment.
 */
class EditorBreadcrumbSegmentsTest {
    // region inside the project

    @Test
    fun `a windows file inside the project shows only its project-relative segments`() {
        assertEquals(
            listOf("src", "main", "App.kt"),
            editorBreadcrumbSegments("C:\\Users\\dev\\repo\\src\\main\\App.kt", "C:\\Users\\dev\\repo"),
        )
    }

    @Test
    fun `a windows project path with a trailing separator is still recognised`() {
        assertEquals(
            listOf("src", "App.kt"),
            editorBreadcrumbSegments("C:\\Users\\dev\\repo\\src\\App.kt", "C:\\Users\\dev\\repo\\"),
        )
    }

    @Test
    fun `a project at a drive root keeps everything below the drive`() {
        assertEquals(listOf("src", "App.kt"), editorBreadcrumbSegments("D:\\src\\App.kt", "D:\\"))
    }

    /** The shape the git panel builds on Windows: a native project path joined with `/`. */
    @Test
    fun `a mixed-separator path is relative to the project`() {
        assertEquals(listOf("src", "App.kt"), editorBreadcrumbSegments("C:\\repo/src/App.kt", "C:\\repo"))
    }

    @Test
    fun `a posix file inside the project shows only its project-relative segments`() {
        assertEquals(listOf("src", "App.kt"), editorBreadcrumbSegments("/home/dev/repo/src/App.kt", "/home/dev/repo"))
        assertEquals(listOf("src", "App.kt"), editorBreadcrumbSegments("/home/dev/repo/src/App.kt", "/home/dev/repo/"))
    }

    @Test
    fun `repeated separators do not produce empty segments`() {
        assertEquals(listOf("src", "App.kt"), editorBreadcrumbSegments("/repo//src///App.kt", "/repo"))
        assertEquals(listOf("src", "App.kt"), editorBreadcrumbSegments("C:\\repo\\\\src\\App.kt", "C:\\repo"))
    }

    // endregion

    // region outside the project

    @Test
    fun `a sibling directory sharing the project's prefix is not inside it`() {
        assertEquals(listOf("repo2", "App.kt"), editorBreadcrumbSegments("/repo2/App.kt", "/repo"))
        assertEquals(listOf("C:", "repo2", "App.kt"), editorBreadcrumbSegments("C:\\repo2\\App.kt", "C:\\repo"))
    }

    @Test
    fun `a file outside the project shows its full path`() {
        assertEquals(listOf("tmp", "notes.md"), editorBreadcrumbSegments("/tmp/notes.md", "/home/dev/repo"))
    }

    /** `Project("No Project", "", 0L)` is the bar's fallback when no project is selected. */
    @Test
    fun `no selected project shows the full path`() {
        assertEquals(listOf("home", "dev", "App.kt"), editorBreadcrumbSegments("/home/dev/App.kt", ""))
        assertEquals(listOf("C:", "dev", "App.kt"), editorBreadcrumbSegments("C:\\dev\\App.kt", ""))
    }

    // endregion
}
