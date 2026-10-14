package ai.rever.boss.search

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins where a path-only file hit draws its underline.
 *
 * [SearchResult.FileResult.matchRanges] index the file NAME, while the match that produced them was
 * found in the whole relative path, so the ranges have to be shifted back past the leading
 * directories. That shift used to look for `/` only. [IndexedFile.relativePath] is built by
 * relativizing two absolute paths, so on Windows it is joined with `\` and the search found no
 * separator at all - the ranges were then shifted by nothing and underlined whatever characters
 * happened to sit at those offsets in the name.
 *
 * The rule is deliberately platform-dependent, so the Windows case cannot be proven by feeding a
 * backslash path to a POSIX host - the shift there is correctly zero. The separator is injected
 * instead, which is what makes the two shift tests below assert the same thing on every runner.
 * The end-to-end test uses the HOST's separator, and so is a red test only on Windows; it is a
 * regression guard elsewhere, and is written that way on purpose rather than by accident.
 */
class GlobalSearchFileHighlightTest {
    @BeforeTest
    fun setUp() {
        SearchSources.clearForTests()
    }

    @AfterTest
    fun tearDown() {
        SearchSources.clearForTests()
    }

    private companion object {
        /** Built from its code point so the separator survives any tooling that rewrites escapes. */
        val BACKSLASH: Char = Char(92)

        /** `uiButton` is a subsequence of `app<sep>ui<sep>Button.kt` but NOT of `Button.kt`. */
        const val QUERY = "uiButton"

        const val NAME = "Button.kt"
    }

    // ---- the shift itself, asserted identically on every OS ----

    @Test
    fun `a windows path shifts past its last backslash`() {
        val relative = "app${BACKSLASH}ui${BACKSLASH}Button.kt"

        assertEquals(7, GlobalSearchService.fileNameStartIn(relative, separator = BACKSLASH))
    }

    @Test
    fun `a posix path shifts past its last slash on any host`() {
        assertEquals(7, GlobalSearchService.fileNameStartIn("app/ui/Button.kt", separator = '/'))
    }

    @Test
    fun `a bare file name shifts by nothing`() {
        assertEquals(0, GlobalSearchService.fileNameStartIn(NAME, separator = BACKSLASH))
        assertEquals(0, GlobalSearchService.fileNameStartIn(NAME, separator = '/'))
    }

    @Test
    fun `a backslash inside a posix file name is not mistaken for a separator`() {
        // A backslash is a legal POSIX file-name character. With '/' as the separator the name
        // starts after the slash, not after the backslash inside it.
        val relative = "dir/we${BACKSLASH}ird.kt"

        assertEquals(4, GlobalSearchService.fileNameStartIn(relative, separator = '/'))
    }

    // ---- what the dialog actually draws, through the real search ----

    /**
     * Red on Windows, where the separator is `\`. On a POSIX host the shift already worked, so this
     * guards against a regression rather than proving the fix.
     */
    @Test
    fun `a path hit underlines the file name, not an offset slice of it`() {
        val sep = File.separatorChar
        val file =
            IndexedFile(
                name = NAME,
                path = "${File.separator}proj${sep}app${sep}ui$sep$NAME",
                relativePath = "app${sep}ui$sep$NAME",
            )

        val result =
            runBlocking {
                GlobalSearchService
                    .search(rawQuery = QUERY, windowId = null, indexedFiles = listOf(file))
                    .filterIsInstance<SearchResult.FileResult>()
                    .single()
            }

        val underlined = result.matchRanges.map { result.name.substring(it.start, it.end) }

        // Before the fix, on Windows, this was ["on", "kt"]: the ranges for `ui` and `Button` in the
        // full path were rebased against 0, landing on "Butt(on)" and "Button.(kt)".
        assertEquals(listOf("Button"), underlined, "underlined text")
        assertEquals(listOf(MatchRange(0, 6)), result.matchRanges, "ranges into the file name")

        val wanted = QUERY.lowercase().toSet()
        for (text in underlined) {
            for (ch in text.lowercase()) {
                assertTrue(ch in wanted, "underlined '$ch' from \"$text\", which is not in \"$QUERY\"")
            }
        }
    }
}
