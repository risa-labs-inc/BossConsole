package ai.rever.boss.mcp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The argument sanitizer builds the ledger record inside invoke's `finally`, so its parse must
 * survive a hostile shape: kotlinx's tree reader recurses per nested array, and the
 * StackOverflowError that would follow is not an Exception - it would escape `finally` and lose
 * the ledger row (review on #1650). [mcpJsonNestingExceeds] rejects the depth before parsing.
 */
class McpArgumentSanitizerDepthTest {
    // Under the sanitizer's 16,384-character cap, so only the depth guard stands in the way.
    private val deep = "{\"a\":" + "[".repeat(DEPTH) + "]".repeat(DEPTH) + "}"

    @Test
    fun `a payload nested past the cap is omitted, not parsed`() {
        check(deep.length < 16_384) { "the fixture must fit under the length cap" }

        assertEquals(
            mapOf("arguments" to "[OMITTED: too deeply nested]"),
            McpArgumentSanitizer.parseArguments(deep),
        )
    }

    @Test
    fun `ordinary nesting is still parsed`() {
        val parsed = McpArgumentSanitizer.parseArguments("""{"a":[[{"b":"c"}]]}""")

        assertEquals(setOf("a"), parsed.keys)
    }

    @Test
    fun `brackets inside a string do not count as nesting`() {
        val parsed = McpArgumentSanitizer.parseArguments("""{"text":"${"[".repeat(500)}"}""")

        assertEquals(setOf("text"), parsed.keys)
    }

    private companion object {
        const val DEPTH = 8_000
    }
}
