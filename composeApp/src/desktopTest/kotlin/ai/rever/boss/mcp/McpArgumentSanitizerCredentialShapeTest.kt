package ai.rever.boss.mcp

import ai.rever.boss.testsupport.repoRoot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [McpArgumentSanitizer]'s credential-shape pattern is a copy of `LogSanitizer`'s, and this pins
 * the two together.
 *
 * They are one rule serving two audiences: the operator deciding whether to approve a mutating
 * tool call, and whoever later reads the ledger or the log. A shape masked in one place and
 * printed in the other is the worst of both, and the files sit in different modules, so widening
 * one alone is the easy mistake. BossConsole#870 recorded the Supabase `sb_*` shapes as
 * follow-up scope; widening both copies at once is what made drift possible, and this guard is
 * what keeps them one rule.
 *
 * The comparison is on the pattern SOURCE read out of both files rather than on behaviour,
 * because a behavioural sample can only prove the cases someone thought to list. It is a text
 * check, with one limit: it finds the `Regex(` literal assigned to `credentialShapePattern` and
 * concatenates the raw source of its quoted string segments, escapes included. Reformatting
 * either literal across different line breaks between its segments is fine, but changing a
 * segment's quote style or escaping, or moving it behind a helper function, would need this
 * updated.
 */
class McpArgumentSanitizerCredentialShapeTest {
    private val sources =
        mapOf(
            "McpArgumentSanitizer" to
                "composeApp/src/commonMain/kotlin/ai/rever/boss/mcp/McpArgumentSanitizer.kt",
            "LogSanitizer" to
                "plugin-platform/plugin-logging/src/desktopMain/kotlin/ai/rever/boss/plugin/logging/LogSanitizer.kt",
        )

    @Test
    fun `both credential-shape patterns are the same rule`() {
        val patterns =
            sources.mapValues { (name, path) ->
                val file = File(repoRoot(), path)
                check(file.isFile) { "$name moved or was renamed: $path" }
                credentialShapeLiteral(file.readText()) ?: error("no credentialShapePattern literal in $name")
            }
        assertEquals(
            patterns.getValue("LogSanitizer"),
            patterns.getValue("McpArgumentSanitizer"),
            "the two credential-shape patterns have drifted; an operator and a log reader would " +
                "see different redactions",
        )
    }

    /**
     * The old normalization deleted every `+` from the source, so the JWT branch's
     * `[A-Za-z0-9_-]+` quantifier did not survive it, and a copy that dropped the `+` in one
     * file would normalize to the same string. The quoted-segment extraction must keep every
     * character inside the quotes, so this pins that the quantifier survives it.
     */
    @Test
    fun `normalization keeps the quantifier characters of the pattern`() {
        val path = "plugin-platform/plugin-logging/src/desktopMain/kotlin/ai/rever/boss/plugin/logging/LogSanitizer.kt"
        val pattern =
            credentialShapeLiteral(File(repoRoot(), path).readText())
                ?: error("no credentialShapePattern literal in LogSanitizer")
        assertTrue(
            pattern.contains("[A-Za-z0-9_-]+"),
            "the + quantifier must survive normalization: $pattern",
        )
    }

    @Test
    fun `a supabase secret key is redacted from a tool argument`() {
        val secret = "sb_secret_A1b2C3d4E5f6G7h8J9k0L1m2"
        val out = McpArgumentSanitizer.sanitize(mapOf("note" to "apikey $secret"))
        assertFalse(out.getValue("note").contains(secret), out.toString())
    }

    @Test
    fun `a supabase publishable key is redacted from a tool argument`() {
        val key = "sb_publishable_Zx9Yw8Vu7Ts6Rq5Pn4Mk3Jh2"
        val out = McpArgumentSanitizer.sanitize(mapOf("note" to "using $key"))
        assertFalse(out.getValue("note").contains(key), out.toString())
    }

    /** A path or command is why this sanitizer exists; widening the pattern must not eat one. */
    @Test
    fun `an ordinary argument a reviewer needs to read survives`() {
        val out = McpArgumentSanitizer.sanitize(mapOf("command" to "ls /home/nikhil/sb_config_reloaded"))
        assertTrue(out.getValue("command").contains("sb_config_reloaded"), out.toString())
    }

    /**
     * A `//` comment anywhere on a line must not leak into the extracted pattern. A trailing
     * comment is the case the whole-line skip used to miss: the depth pass skips it wherever
     * it starts, and so must the extraction pass, so one quoted word in a comment cannot
     * widen one file's pattern and report a false drift.
     */
    @Test
    fun `a trailing comment with a quoted word does not leak into the pattern`() {
        val source =
            """
            val credentialShapePattern =
                Regex(
                    "(a)" + // the "trailing" branch
                    "b",
                )
            """.trimIndent()
        assertEquals("(a)b", credentialShapeLiteral(source))
    }

    /**
     * A paren inside a quoted segment must not reach the depth counter. `[)]` is valid regex
     * and valid Kotlin, and as raw characters it is an unbalanced closer: a character-wise
     * counter would drop from 1 to 0 there and stop the walk mid-list, truncating both files
     * at the same point, the silent direction. The walk must run to the argument list's own
     * closing paren and extract every segment.
     */
    @Test
    fun `a paren inside a quoted segment does not stop the walk`() {
        val source =
            """
            val credentialShapePattern =
                Regex(
                    "[x" +
                    "[)]" +
                    "y",
                )
            """.trimIndent()
        assertEquals("[x[)]y", credentialShapeLiteral(source))
    }

    /**
     * An argument list with no quoted segment at all is a refactor away from literals, e.g. to
     * a constant. Extracting "" from both files would pass the comparison having compared
     * nothing, so the extractor reports it as a missing pattern and the test fails loudly.
     */
    @Test
    fun `a bare identifier in the argument list extracts as a missing pattern`() {
        val source =
            """
            val credentialShapePattern = Regex(CREDENTIAL_SHAPES)
            """.trimIndent()
        assertNull(credentialShapeLiteral(source))
    }

    /**
     * Collapses the `Regex(` argument list to the concatenated contents of its quoted string
     * segments. One pass over runs - a triple-quoted segment, an ordinary quoted segment, a
     * `//` comment to the line end, or a single character - counts the parens that bound the
     * list and collects the segments, so the depth pass and the extraction pass share one
     * scanning rule: a `//` comment anywhere on a line is skipped whole, and a paren inside a
     * quoted segment never reaches the depth counter. The segments are extracted rather than
     * the separators deleted, because deleting non-quote characters would also delete
     * characters that ARE the pattern: the `+` in the JWT branch's quantifier, or a space
     * inside a character class. Reformatting the literal across different line breaks between
     * its segments stays invisible; a changed alternation does not.
     */
    private fun credentialShapeLiteral(source: String): String? {
        // The declaration, not the first mention: an earlier KDoc cross-reference must not send
        // the search to a different pattern's literal.
        val start = source.indexOf("val credentialShapePattern")
        val open = if (start < 0) -1 else source.indexOf("Regex(", start)
        if (open < 0) return null
        val pattern = StringBuilder()
        var depth = 0
        var i = open + "Regex".length
        while (i < source.length) {
            if (source.startsWith("\"\"\"", i)) {
                val end = source.indexOf("\"\"\"", i + 3)
                check(end >= 0) { "unterminated triple-quoted pattern segment" }
                pattern.append(source, i + 3, end)
                i = end + 3
            } else if (source[i] == '"') {
                val end = quotedSegmentEnd(source, i + 1)
                pattern.append(source, i + 1, end - 1)
                i = end
            } else if (source[i] == '/' && source.getOrNull(i + 1) == '/') {
                val end = source.indexOf('\n', i)
                i = if (end < 0) source.length else end + 1
            } else if (source[i] == '(') {
                depth++
                i++
            } else if (source[i] == ')') {
                depth--
                i++
                if (depth == 0) break
            } else {
                i++
            }
        }
        // An empty result means the argument list held no quoted segment at all, e.g. a
        // refactor to a constant: comparing "" to "" would report success having compared
        // nothing, so an empty extraction is indistinguishable from a missing pattern.
        return pattern.toString().takeIf { it.isNotEmpty() }
    }

    /** The index just past the closing quote of the `"..."` segment started at [start]. */
    private fun quotedSegmentEnd(
        source: String,
        start: Int,
    ): Int {
        var i = start
        while (i < source.length) {
            val c = source[i]
            if (c == '\\') {
                i += 2
            } else if (c == '"') {
                return i + 1
            } else {
                i++
            }
        }
        return i
    }
}
