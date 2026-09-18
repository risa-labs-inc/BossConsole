package ai.rever.boss.mcp

import ai.rever.boss.testsupport.repoRoot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [McpArgumentSanitizer]'s credential-shape pattern is a copy of `LogSanitizer`'s, and this pins
 * the two together.
 *
 * They are one rule serving two audiences: the operator deciding whether to approve a mutating
 * tool call, and whoever later reads the ledger or the log. A shape masked in one place and
 * printed in the other is the worst of both, and the files sit in different modules, so widening
 * one alone is the easy mistake. BossConsole#870 added the Supabase `sb_*` shapes to the shared
 * sanitizer, which is what prompted this guard.
 *
 * The comparison is on the pattern SOURCE read out of both files rather than on behaviour,
 * because a behavioural sample can only prove the cases someone thought to list. It is a text
 * check, with one limit: it finds the `Regex(` literal assigned to `credentialShapePattern` and
 * compares the concatenated string parts, so reformatting either literal across different line
 * breaks is fine but moving it behind a helper function would need this updated.
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
     * Collapses the `Regex(` argument list to its string content: drops the concatenation, the
     * quotes and any comment lines between the parts, so formatting differences do not register
     * as drift while a changed alternation does.
     */
    private fun credentialShapeLiteral(source: String): String? {
        val start = source.indexOf("credentialShapePattern")
        val open = if (start < 0) -1 else source.indexOf("Regex(", start)
        if (open < 0) return null
        var depth = 0
        var i = open + "Regex".length
        val body = StringBuilder()
        while (i < source.length) {
            val c = source[i]
            if (c == '(') depth++
            if (c == ')') {
                depth--
                if (depth == 0) break
            }
            body.append(c)
            i++
        }
        return body
            .lines()
            .filterNot { it.trim().startsWith("//") }
            .joinToString("")
            .replace(Regex("\"\"\"|\""), "")
            .replace(Regex("""[\s+]"""), "")
    }
}
