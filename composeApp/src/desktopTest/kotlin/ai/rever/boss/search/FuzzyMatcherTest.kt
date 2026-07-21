package ai.rever.boss.search

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for FuzzyMatcher component.
 *
 * Tests cover:
 * - Basic matching (characters in order)
 * - Consecutive character bonuses
 * - Word boundary bonuses (camelCase, underscore)
 * - Position bonuses (earlier matches score higher)
 * - Edge cases (empty strings, no matches)
 * - Match range correctness
 */
class FuzzyMatcherTest {

    // ==================== BASIC MATCHING TESTS ====================

    @Test
    fun `should match all characters in order`() {
        val result = FuzzyMatcher.match("abc", "aXbXc", "axbxc")
        assertNotNull(result)
        assertEquals(3, result.matchRanges.sumOf { it.end - it.start })
    }

    @Test
    fun `should return null when pattern longer than target`() {
        val result = FuzzyMatcher.match("abcdef", "abc", "abc")
        assertNull(result)
    }

    @Test
    fun `should return null when not all characters match`() {
        val result = FuzzyMatcher.match("xyz", "abc", "abc")
        assertNull(result)
    }

    @Test
    fun `should return empty result for empty pattern`() {
        val result = FuzzyMatcher.match("", "abc", "abc")
        assertNotNull(result)
        assertEquals(0, result.score)
        assertTrue(result.matchRanges.isEmpty())
    }

    @Test
    fun `should match case-insensitively`() {
        val result = FuzzyMatcher.match("abc", "ABC", "abc")
        assertNotNull(result)
    }

    // ==================== SCORING TESTS ====================

    @Test
    fun `should give higher score for consecutive matches`() {
        val consecutive = FuzzyMatcher.match("abc", "abcdef", "abcdef")
        val scattered = FuzzyMatcher.match("abc", "aXbXcX", "axbxcx")

        assertNotNull(consecutive)
        assertNotNull(scattered)
        assertTrue(consecutive.score > scattered.score,
            "Consecutive match (${consecutive.score}) should score higher than scattered (${scattered.score})")
    }

    @Test
    fun `should give higher score for camelCase word boundary matches`() {
        val result = FuzzyMatcher.match("bta", "BossTopApp", "bosstopapp")
        assertNotNull(result)
        // Word boundaries at B(0), T(4), A(7) should boost score
        assertTrue(result.score > 50, "CamelCase match should have high score, got ${result.score}")
    }

    @Test
    fun `should give higher score for underscore word boundary matches`() {
        val result = FuzzyMatcher.match("ga", "get_all_items", "get_all_items")
        assertNotNull(result)
        // Word boundary after underscores should boost score
        assertTrue(result.score > 30, "Underscore boundary match should have good score, got ${result.score}")
    }

    @Test
    fun `should give higher score for path separator word boundaries`() {
        val result = FuzzyMatcher.match("sf", "src/foo/bar.kt", "src/foo/bar.kt")
        assertNotNull(result)
    }

    @Test
    fun `should give higher score for matches at beginning`() {
        val startMatch = FuzzyMatcher.match("ab", "abcdef", "abcdef")
        val endMatch = FuzzyMatcher.match("ef", "abcdef", "abcdef")

        assertNotNull(startMatch)
        assertNotNull(endMatch)
        assertTrue(startMatch.score > endMatch.score,
            "Start match (${startMatch.score}) should score higher than end match (${endMatch.score})")
    }

    @Test
    fun `should give bonus for exact match`() {
        val exact = FuzzyMatcher.match("abc", "abc", "abc")
        val partial = FuzzyMatcher.match("abc", "abcdef", "abcdef")

        assertNotNull(exact)
        assertNotNull(partial)
        assertTrue(exact.score > partial.score,
            "Exact match (${exact.score}) should score higher than partial (${partial.score})")
    }

    @Test
    fun `should give bonus for shorter targets`() {
        val short = FuzzyMatcher.match("ab", "abc", "abc")
        val long = FuzzyMatcher.match("ab", "abcdefghijklmnopqrstuvwxyz", "abcdefghijklmnopqrstuvwxyz")

        assertNotNull(short)
        assertNotNull(long)
        assertTrue(short.score > long.score,
            "Short target (${short.score}) should score higher than long (${long.score})")
    }

    // ==================== MATCH RANGE TESTS ====================

    @Test
    fun `should collapse consecutive matches into single range`() {
        val result = FuzzyMatcher.match("abc", "abcdef", "abcdef")
        assertNotNull(result)
        assertEquals(1, result.matchRanges.size)
        assertEquals(MatchRange(0, 3), result.matchRanges[0])
    }

    @Test
    fun `should create separate ranges for non-consecutive matches`() {
        val result = FuzzyMatcher.match("ac", "abc", "abc")
        assertNotNull(result)
        assertEquals(2, result.matchRanges.size)
        assertEquals(MatchRange(0, 1), result.matchRanges[0]) // 'a'
        assertEquals(MatchRange(2, 3), result.matchRanges[1]) // 'c'
    }

    @Test
    fun `should handle mixed consecutive and non-consecutive`() {
        val result = FuzzyMatcher.match("abef", "abcdef", "abcdef")
        assertNotNull(result)
        assertEquals(2, result.matchRanges.size)
        assertEquals(MatchRange(0, 2), result.matchRanges[0]) // 'ab'
        assertEquals(MatchRange(4, 6), result.matchRanges[1]) // 'ef'
    }

    // ==================== REAL-WORLD PATTERN TESTS ====================

    @Test
    fun `should match file names with fuzzy patterns`() {
        // Common patterns users type
        assertNotNull(FuzzyMatcher.match("bapp", "BossApp.kt", "bossapp.kt"))
        assertNotNull(FuzzyMatcher.match("gss", "GlobalSearchService.kt", "globalsearchservice.kt"))
        assertNotNull(FuzzyMatcher.match("fm", "FuzzyMatcher.kt", "fuzzymatcher.kt"))
    }

    @Test
    fun `should match command descriptions`() {
        assertNotNull(FuzzyMatcher.match("new tab", "Open new tab dialog", "open new tab dialog"))
        assertNotNull(FuzzyMatcher.match("reload", "Reload the current browser tab", "reload the current browser tab"))
        assertNotNull(FuzzyMatcher.match("zoom", "Reset browser zoom to 100%", "reset browser zoom to 100%"))
    }

    @Test
    fun `should rank exact prefix higher than scattered match`() {
        val prefix = FuzzyMatcher.match("Boss", "BossApp.kt", "bossapp.kt")
        val scattered = FuzzyMatcher.match("Boss", "BlazeOwnServerSystem.kt", "blazeownserversystem.kt")

        assertNotNull(prefix)
        assertNotNull(scattered)
        assertTrue(prefix.score > scattered.score,
            "Prefix match (${prefix.score}) should score higher than scattered (${scattered.score})")
    }

    // ==================== COMPARISON TESTS ====================

    @Test
    fun `compareResults should sort by score descending`() {
        val high = FuzzyMatcher.MatchResult(100, emptyList())
        val low = FuzzyMatcher.MatchResult(50, emptyList())

        assertTrue(FuzzyMatcher.compareResults(high, low) < 0, "Higher score should come first")
        assertTrue(FuzzyMatcher.compareResults(low, high) > 0, "Lower score should come second")
        assertEquals(0, FuzzyMatcher.compareResults(high, high), "Equal scores should return 0")
    }
}
