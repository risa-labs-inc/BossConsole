package ai.rever.boss.mcp

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [mcpJsonNestingExceeds] guards every parse of agent-supplied arguments on the invoke path, so a
 * mis-tracked string state is a safety bug: believing it is still inside a string, the scanner
 * under-counts real nesting and hands a payload that overflows the parser straight to it.
 */
class McpJsonDepthTest {
    private val limit = 128

    @Test
    fun `unmatched closers cannot hide excessive nesting`() {
        assertTrue(mcpJsonNestingExceeds("}".repeat(300) + "[".repeat(limit + 1), limit))
    }

    @Test
    fun `nesting past the limit is caught, and nesting at it is not`() {
        assertTrue(mcpJsonNestingExceeds("[".repeat(limit + 1) + "]".repeat(limit + 1), limit))
        assertFalse(mcpJsonNestingExceeds("[".repeat(limit) + "]".repeat(limit), limit))
        assertTrue(mcpJsonNestingExceeds("{\"a\":".repeat(limit + 1) + "1" + "}".repeat(limit + 1), limit))
    }

    // Review on #1650: only an escaped quote changes the parity of the quote toggles. Without the
    // escape state, `\"` would end the string early, the `"` after y would reopen one, and the 300
    // real levels after it would be read as string text - under-counted to nothing.
    @Test
    fun `an escaped quote inside a string does not hide the nesting after it`() {
        val payload = """{"a":"x\"y","b":""" + "[".repeat(300) + "]".repeat(300) + "}"

        assertTrue(mcpJsonNestingExceeds(payload, limit))
    }

    @Test
    fun `brackets inside a string are text, including after an escaped backslash`() {
        assertFalse(mcpJsonNestingExceeds("""{"t":"${"[".repeat(500)}"}""", limit))
        assertFalse(mcpJsonNestingExceeds("""{"t":"a\\\\","u":"${"{".repeat(500)}"}""", limit))
    }
}
