package ai.rever.boss.search

/**
 * The 1-based, end-exclusive line/column range an `applyEdit` call needs for a
 * regex match.
 *
 * Its own file, and pure, because getting this wrong silently corrupts text:
 * `EditorTabPluginAPI.applyEdit` documents 1-based positions and applies
 * `document.replace(start, end, …)` with an **exclusive** end, while
 * `MatchResult.range.last` is the **inclusive** index of the match's last
 * character. Passing `last` straight through replaced one character too few -
 * replacing `subtract` with `add` produced `addt`.
 */
internal data class BufferEditRange(
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
) {
    companion object {
        /**
         * The positions for an arbitrary half-open offset span [[start], [endExclusive]).
         *
         * Unlike [of], which takes a match's inclusive range, this maps the two
         * offsets a whole-file diff produces - a single replaced span, which may be a
         * pure insertion ([start] == [endExclusive]) or a deletion (empty new text).
         */
        fun ofSpan(
            lines: LineOffsets,
            start: Int,
            endExclusive: Int,
        ): BufferEditRange =
            BufferEditRange(
                startLine = lines.lineOf(start),
                startCol = lines.columnOf(start),
                endLine = lines.lineOf(endExclusive),
                endCol = lines.columnOf(endExclusive),
            )

        /**
         * The positions for a match's INCLUSIVE range (the +1 to an exclusive end is
         * the whole point - see the class KDoc). No production caller since replace
         * moved to whole-file diffing, but kept and pinned by [BufferEditRangeTest]:
         * it is the smallest place that exercises the off-by-one this type exists for.
         *
         * @param lines line-start offsets of the text the match came from
         * @param range the match range, inclusive of its last character
         */
        fun of(
            lines: LineOffsets,
            range: IntRange,
        ): BufferEditRange {
            val endExclusive = range.last + 1
            return BufferEditRange(
                startLine = lines.lineOf(range.first),
                startCol = lines.columnOf(range.first),
                endLine = lines.lineOf(endExclusive),
                endCol = lines.columnOf(endExclusive),
            )
        }
    }
}

/** 1-based line/column lookup over precomputed line-start offsets. */
internal interface LineOffsets {
    fun lineOf(offset: Int): Int

    fun columnOf(offset: Int): Int
}
