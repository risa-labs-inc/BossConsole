package ai.rever.boss.search

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The replacement-expansion contract, pinned on the shared function BOTH the disk and
 * buffer paths call. The bug this guards against: a literal query with a `$` in its
 * replacement was expanded as a capture reference ("$40 off" → "0 off"), and the two
 * paths disagreed about whether `$1` was literal or a group.
 */
class ReplaceExpansionTest {
    private val svc = ContentSearchService(projectPathProvider = { null })

    private fun run(
        text: String,
        pattern: String,
        repl: String,
        isRegex: Boolean,
    ) = svc.computeReplaced(text, Regex(pattern), repl, isRegex)

    @Test
    fun `a literal query keeps a dollar-digit in the replacement literal`() {
        val out = run("PRICE", "PRICE", "$40 off", isRegex = false)
        assertEquals("$40 off", out.text)
        assertEquals(1, out.count)
    }

    @Test
    fun `a literal query keeps backslashes`() {
        assertEquals("C:\\temp", run("x", "x", "C:\\temp", isRegex = false).text)
    }

    @Test
    fun `a regex query expands capture references`() {
        val out = run("fun foo(", "fun (\\w+)\\(", "fun new_$1(", isRegex = true)
        assertEquals("fun new_foo(", out.text)
    }

    @Test
    fun `a lookahead regex advances past every match`() {
        val out = run("ab ab", "a(?=b)", "X", isRegex = true)
        assertEquals("Xb Xb", out.text)
        assertEquals(2, out.count)
    }

    @Test
    fun `the changed span is the minimal edit`() {
        val span = svc.changedSpan("hello world", "hello brave world")
        assertEquals("hello ".length, span.oldStart)
        assertEquals("hello ".length, span.oldEndExclusive) // pure insertion
        assertEquals("hello brave ".length, span.newEndExclusive)
    }
}
