package ai.rever.boss.search

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The range handed to `applyEdit` for a live-buffer replace.
 *
 * The regression this pins is a silent text corruption: `applyEdit`'s end
 * position is exclusive, `MatchResult.range.last` is inclusive, and passing
 * one as the other replaced every match one character short - replacing
 * `subtract` with `add` produced `addt`.
 */
class BufferEditRangeTest {
    private fun rangeOf(
        text: String,
        needle: String,
    ): BufferEditRange {
        val m = Regex(Regex.escape(needle)).find(text)!!
        return BufferEditRange.of(ContentSearchService.LineMap(text), m.range)
    }

    @Test
    fun `the end column is one past the match, not on its last character`() {
        // "subtract" occupies columns 1..8, so the exclusive end is column 9.
        val r = rangeOf("subtract\n", "subtract")
        assertEquals(1, r.startLine)
        assertEquals(1, r.startCol)
        assertEquals(1, r.endLine)
        assertEquals(9, r.endCol, "an inclusive end here leaves the match's last character behind")
    }

    @Test
    fun `the replaced span covers the whole match`() {
        val text = "val x = subtract(a, b)\n"
        val r = rangeOf(text, "subtract")
        // Simulate what applyEdit does with these positions.
        val lines = ContentSearchService.LineMap(text)
        val start = offsetOf(lines, r.startLine, r.startCol)
        val end = offsetOf(lines, r.endLine, r.endCol)
        assertEquals("subtract", text.substring(start, end))
        assertEquals("val x = add(a, b)\n", text.replaceRange(start, end, "add"))
    }

    @Test
    fun `a match on a later line reports that line`() {
        val text = "first\nsecond subtract here\n"
        val r = rangeOf(text, "subtract")
        assertEquals(2, r.startLine)
        assertEquals(8, r.startCol)
        assertEquals(2, r.endLine)
        assertEquals(16, r.endCol)
    }

    @Test
    fun `a single-character match spans one column`() {
        val r = rangeOf("abc", "b")
        assertEquals(2, r.startCol)
        assertEquals(3, r.endCol)
    }

    @Test
    fun `a match ending at the very end of the text does not overrun`() {
        val text = "xy"
        val r = rangeOf(text, "y")
        assertEquals(1, r.endLine)
        assertEquals(3, r.endCol)
        val lines = ContentSearchService.LineMap(text)
        assertEquals("y", text.substring(offsetOf(lines, r.startLine, r.startCol), offsetOf(lines, r.endLine, r.endCol)))
    }

    @Test
    fun `a match ending exactly at a newline stays on its own line`() {
        val text = "ab\ncd"
        val r = rangeOf(text, "ab")
        assertEquals(1, r.endLine, "the exclusive end must not roll onto the next line")
        assertEquals(3, r.endCol)
    }

    /** The inverse of LineMap, mirroring applyEdit's positionToOffset(line-1, col-1). */
    private fun offsetOf(
        lines: ContentSearchService.LineMap,
        line: Int,
        col: Int,
    ): Int {
        var seen = 0
        lines.text.lines().forEachIndexed { index, text ->
            if (index + 1 == line) return seen + (col - 1)
            seen += text.length + 1
        }
        return seen
    }
}
