package ai.rever.boss.plugin.packs

import ai.rever.boss.mcp.McpArgumentSanitizer
import ai.rever.boss.mcp.McpPolicyAction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards [PluginPackParser], which decides what a pack may contain before anything is planned.
 *
 * The limits matter for more than tidiness: the pack is the argument the operator reads in the MCP
 * approval dialog, so a pack the dialog would cut short must be refused, not approved half-seen.
 * The dialog-fidelity cases run the real [McpArgumentSanitizer] rather than restating its limits.
 */
class PluginPackParserTest {
    private fun problemsOf(raw: String): List<String> {
        val error = PluginPackParser.parse(raw).exceptionOrNull() ?: fail("expected $raw to be refused")
        return assertIs<InvalidPackException>(error).problems
    }

    private fun packJson(
        plugins: List<String> = emptyList(),
        vararg rules: Pair<String, List<String>>,
        id: String = "team-backend",
    ): String =
        JsonObject(
            buildMap {
                put("pack", JsonPrimitive(id))
                if (plugins.isNotEmpty()) put("plugins", JsonArray(plugins.map { JsonPrimitive(it) }))
                rules.forEach { (key, names) -> put(key, JsonArray(names.map { JsonPrimitive(it) })) }
            },
        ).toString()

    @Test
    fun `plugin entries carry an optional exact version and an optional marker`() {
        val pack =
            PluginPackParser
                .parse(
                    packJson(
                        listOf(
                            "ai.rever.boss.plugin.dynamic.terminaltab",
                            "ai.rever.boss.plugin.dynamic.codebase@1.4.2?",
                        ),
                    ),
                ).getOrThrow()

        assertEquals(
            listOf(
                PackPlugin("ai.rever.boss.plugin.dynamic.terminaltab", version = null, optional = false),
                PackPlugin("ai.rever.boss.plugin.dynamic.codebase", version = "1.4.2", optional = true),
            ),
            pack.plugins,
        )
        assertEquals("team-backend", pack.id)
    }

    @Test
    fun `each rule list maps to its scope and action`() {
        val pack =
            PluginPackParser
                .parse(
                    packJson(
                        emptyList(),
                        "allow_tools" to listOf("run_tests"),
                        "deny_tools" to listOf("docker_rm"),
                        "ask_providers" to listOf("ai.rever.boss.plugin.dynamic.codebase"),
                    ),
                ).getOrThrow()

        assertEquals(
            setOf(
                PackRule(PackRuleScope.TOOL, "run_tests", McpPolicyAction.ALLOW),
                PackRule(PackRuleScope.TOOL, "docker_rm", McpPolicyAction.DENY),
                PackRule(PackRuleScope.PROVIDER, "ai.rever.boss.plugin.dynamic.codebase", McpPolicyAction.ASK),
            ),
            pack.rules.toSet(),
        )
    }

    @Test
    fun `a pack cannot set policy for the pack tools, so it cannot pre-approve later packs`() {
        for (subject in PluginPackParser.PACK_TOOL_NAMES) {
            val problems = problemsOf(packJson(emptyList(), "allow_tools" to listOf(subject)))
            assertTrue(problems.any { "pack tools themselves" in it }, "$subject: $problems")
        }
        val provider = problemsOf(packJson(emptyList(), "allow_providers" to listOf(PluginPackParser.PACK_PROVIDER_ID)))
        assertTrue(provider.any { "pack tools themselves" in it }, provider.toString())
    }

    @Test
    fun `host components are refused as pack plugins`() {
        val problems = problemsOf(packJson(listOf("ai.rever.boss.plugin.api")))

        assertTrue(problems.any { "part of the host" in it }, problems.toString())
    }

    @Test
    fun `every problem is reported in one pass`() {
        val raw =
            """{"pack":"Bad Id","plugins":["ok.plugin","ok.plugin","x@not a version"],""" +
                """"allow_tools":["run_tests"],"deny_tools":["run_tests"],"surprise":1}"""

        val problems = problemsOf(raw)

        assertTrue(problems.any { "Unknown field" in it && "surprise" in it }, problems.toString())
        assertTrue(problems.any { "\"pack\" must be" in it }, problems.toString())
        assertTrue(problems.any { "listed twice" in it && "ok.plugin" in it }, problems.toString())
        assertTrue(problems.any { "invalid version" in it }, problems.toString())
        assertTrue(problems.any { "more than one rule list" in it && "run_tests" in it }, problems.toString())
    }

    @Test
    fun `malformed shapes are refused rather than coerced`() {
        assertTrue(problemsOf("[]").single().contains("JSON object"))
        assertTrue(problemsOf("not json").single().contains("JSON object"))
        assertTrue(problemsOf("""{"pack":"p","plugins":"ai.rever.x"}""").any { "list of strings" in it })
        assertTrue(problemsOf("""{"pack":"p","plugins":[1]}""").any { "list of strings" in it })
        assertTrue(problemsOf("""{"pack":"p"}""").any { "no plugins and no rules" in it })
    }

    @Test
    fun `the largest accepted pack is shown in full by the approval dialog`() {
        // As long as the parser allows: 40 plugins and 80 rules with long names, kept just under the
        // per-field limit. If the parser ever allows more than the dialog shows, this fails.
        val plugins = (1..PluginPackParser.MAX_PLUGINS).map { "com.example.team.plugin-number-$it@10.20.$it?" }
        val tools = (1..40).map { "a_long_tool_name_for_the_dialog_$it" }
        val providers = (1..40).map { "com.example.provider.number-$it" }
        val raw = packJson(plugins, "allow_tools" to tools, "ask_providers" to providers)
        PluginPackParser.parse(raw).getOrThrow()

        val shown = McpArgumentSanitizer.sanitize(McpArgumentSanitizer.parseArguments(raw))

        (plugins.map { "plugins" to it } + tools.map { "allow_tools" to it } + providers.map { "ask_providers" to it })
            .forEach { (field, entry) ->
                assertTrue(shown.getValue(field).contains(entry), "the dialog does not show \"$entry\" in $field")
            }
    }

    @Test
    fun `a field the dialog would cut short is refused`() {
        // 40 valid entries of about 115 characters each: every entry is allowed, the field is not.
        val longId = "com.example." + "segment-".repeat(12)
        val plugins = (1..PluginPackParser.MAX_PLUGINS).map { "$longId$it@1.0.0" }
        assertTrue(plugins.joinToString().length > PluginPackParser.MAX_RENDERED_FIELD_CHARS)

        val problems = problemsOf(packJson(plugins))

        assertTrue(problems.any { "too long to show in full" in it }, problems.toString())
    }

    @Test
    fun `an oversized pack is refused before it is parsed`() {
        val problems = problemsOf(" ".repeat(PluginPackParser.MAX_RAW_ARGUMENT_CHARS + 1))

        assertTrue(problems.single().contains("limit"), problems.toString())
    }
}
