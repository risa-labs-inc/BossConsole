package ai.rever.boss.search

import kotlin.math.max

/**
 * Fuzzy string matching algorithm for file and command search.
 *
 * Implements a scoring algorithm similar to VS Code's quick open:
 * - Characters must match in order (but not consecutively)
 * - Consecutive matches score higher
 * - Word boundary matches (camelCase, underscore, path separator) score higher
 * - Earlier matches score higher than later matches
 */
object FuzzyMatcher {

    /**
     * Match result containing the score and matched character ranges.
     */
    data class MatchResult(
        val score: Int,
        val matchRanges: List<MatchRange>
    )

    /**
     * Attempt to fuzzy match a pattern against a target string.
     *
     * @param pattern The search query (lowercase for case-insensitive matching)
     * @param target The string to match against
     * @param targetLower The lowercase version of target (for performance)
     * @return MatchResult if pattern matches, null otherwise
     */
    fun match(pattern: String, target: String, targetLower: String = target.lowercase()): MatchResult? {
        if (pattern.isEmpty()) return MatchResult(0, emptyList())
        if (pattern.length > target.length) return null

        val patternLower = pattern.lowercase()
        val matchIndices = mutableListOf<Int>()

        var patternIdx = 0
        var targetIdx = 0

        // First pass: find if all characters match in order
        while (patternIdx < patternLower.length && targetIdx < targetLower.length) {
            if (patternLower[patternIdx] == targetLower[targetIdx]) {
                matchIndices.add(targetIdx)
                patternIdx++
            }
            targetIdx++
        }

        // If not all pattern characters were matched, no match
        if (patternIdx < patternLower.length) return null

        // Calculate score based on match quality
        val score = calculateScore(target, targetLower, matchIndices)
        val matchRanges = collapseToRanges(matchIndices)

        return MatchResult(score, matchRanges)
    }

    /**
     * Calculate the match score based on various factors.
     */
    private fun calculateScore(target: String, targetLower: String, matchIndices: List<Int>): Int {
        if (matchIndices.isEmpty()) return 0

        var score = 0
        var consecutiveBonus = 0

        for (i in matchIndices.indices) {
            val idx = matchIndices[i]

            // Base score for each match
            score += 1

            // Bonus for consecutive matches
            if (i > 0 && matchIndices[i - 1] == idx - 1) {
                consecutiveBonus += 5
                score += consecutiveBonus
            } else {
                consecutiveBonus = 0
            }

            // Bonus for word boundary matches
            if (isWordBoundary(target, idx)) {
                score += 10
            }

            // Bonus for exact case match
            if (i < target.length && target[idx] == targetLower[idx].uppercaseChar()) {
                score += 1
            }

            // Position bonus (earlier matches are better)
            // Max bonus of 10 for first character, decreasing
            val positionBonus = max(0, 10 - idx)
            score += positionBonus
        }

        // Bonus for shorter targets (more precise match)
        val lengthBonus = max(0, 50 - target.length)
        score += lengthBonus

        // Bonus if pattern starts at beginning of target
        if (matchIndices.isNotEmpty() && matchIndices[0] == 0) {
            score += 20
        }

        // Extra bonus for exact match
        if (matchIndices.size == target.length) {
            score += 50
        }

        return score
    }

    /**
     * Check if a position is at a word boundary.
     * Word boundaries are:
     * - Start of string
     * - After a path separator (/ or \)
     * - After an underscore or hyphen
     * - Transition from lowercase to uppercase (camelCase)
     * - After a dot
     */
    private fun isWordBoundary(text: String, index: Int): Boolean {
        if (index == 0) return true
        if (index >= text.length) return false

        val prevChar = text[index - 1]
        val currChar = text[index]

        return when {
            prevChar in listOf('/', '\\', '_', '-', '.', ' ') -> true
            prevChar.isLowerCase() && currChar.isUpperCase() -> true
            else -> false
        }
    }

    /**
     * Collapse a list of indices into contiguous ranges.
     */
    private fun collapseToRanges(indices: List<Int>): List<MatchRange> {
        if (indices.isEmpty()) return emptyList()

        val ranges = mutableListOf<MatchRange>()
        var rangeStart = indices[0]
        var rangeEnd = indices[0]

        for (i in 1 until indices.size) {
            if (indices[i] == rangeEnd + 1) {
                rangeEnd = indices[i]
            } else {
                ranges.add(MatchRange(rangeStart, rangeEnd + 1))
                rangeStart = indices[i]
                rangeEnd = indices[i]
            }
        }

        ranges.add(MatchRange(rangeStart, rangeEnd + 1))
        return ranges
    }

    /**
     * Compare two match results for sorting.
     * Higher scores should come first.
     */
    fun compareResults(a: MatchResult, b: MatchResult): Int {
        return b.score - a.score
    }
}
