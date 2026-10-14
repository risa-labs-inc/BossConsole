package ai.rever.boss.mcp

import ai.rever.boss.testsupport.repoRoot
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins the discipline behind #804: every engine policy consult on the invoke path carries the
 * provider's own `readOnly` declaration.
 *
 * `policyFor`'s third parameter is `declaredReadOnly: Boolean? = null` so the ~90 existing test
 * call sites keep compiling, but that default is the exact inverse of the `isDisabled` convention
 * AGENTS.md records for `PluginDependencyResolution.blockingDependentsOf`: a new consult that
 * forgets the argument silently gets the lenient name-only classification - #804 again, shipped
 * one review at a time. This drift test fails that regression at build time instead.
 *
 * A convention test in the style of `SettingsSearchIndexDriftTest`, heuristic for the same reason:
 * it cannot parse Kotlin, so it checks the two things a three-argument call cannot fake textually -
 * at least two top-level commas, and the declaration visible among the arguments.
 */
class McpPolicyForDeclarationDriftTest {
    @Test
    fun `every registry policy consult carries the declared read-only signal`() {
        val source =
            repoRoot()
                .resolve("composeApp/src/commonMain/kotlin/ai/rever/boss/mcp/McpToolRegistryImpl.kt")
                .readText()

        val sites = extractPolicyForArgs(source)
        assertTrue(sites.isNotEmpty(), "no policyFor call sites found - did the invoke path move?")

        sites.forEachIndexed { index, args ->
            val topLevelCommas = args.count { it == ',' }
            assertTrue(
                topLevelCommas >= 2,
                "policyFor call #${index + 1} has $topLevelCommas top-level arguments" +
                    " - a consult without all three (name, provider, declaredReadOnly) silently" +
                    " gets the lenient name-only classification, the exact hole #804 closed",
            )
            assertTrue(
                "readOnly" in args,
                "policyFor call #${index + 1} does not mention readOnly - the declaration must" +
                    " reach every consult the invoke gate uses",
            )
        }
    }

    /**
     * Returns the argument text of every `policyFor(` call in [source], each cut at the
     * matching close parenthesis (nested parens counted, so multiline calls survive).
     */
    private fun extractPolicyForArgs(source: String): List<String> {
        val args = mutableListOf<String>()
        var cursor = 0
        while (true) {
            val open = source.indexOf("policyFor(", cursor)
            if (open == -1) return args
            var depth = 1
            var i = open + "policyFor(".length
            val blob = StringBuilder()
            while (i < source.length && depth > 0) {
                val c = source[i]
                if (c == '(') depth++
                if (c == ')') depth--
                if (depth > 0) blob.append(c)
                i++
            }
            args += blob.toString()
            cursor = i
        }
    }
}
