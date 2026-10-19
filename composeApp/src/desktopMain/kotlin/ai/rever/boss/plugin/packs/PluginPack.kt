package ai.rever.boss.plugin.packs

import ai.rever.boss.components.plugin.PluginDependencyResolution
import ai.rever.boss.mcp.McpPolicyAction
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One plugin a pack asks for.
 *
 * @property version an exact version, or null for "whatever the store publishes"
 * @property optional a failure to satisfy this row does not make the pack fail
 */
data class PackPlugin(
    val pluginId: String,
    val version: String?,
    val optional: Boolean,
)

/** Whether a policy rule is keyed by one tool name or by the provider contributing tools. */
enum class PackRuleScope { TOOL, PROVIDER }

/** One MCP policy rule a pack asks to add. */
data class PackRule(
    val scope: PackRuleScope,
    val subject: String,
    val action: McpPolicyAction,
)

/**
 * A plugin pack: the plugins a desk needs and the MCP policy rules that make its tools usable.
 *
 * Packs are argument-shaped rather than file-shaped on purpose. The pack is the argument of the
 * mutating `pack_apply` tool, so the MCP approval dialog shows the operator every plugin and every
 * rule they are approving, and the host never opens a path a caller named.
 */
data class PluginPack(
    val id: String,
    val plugins: List<PackPlugin>,
    val rules: List<PackRule>,
)

/**
 * Parses and bounds a pack from a tool call's raw JSON arguments.
 *
 * The wire shape is flat so it renders legibly in the approval dialog, which shows each top-level
 * argument as one line:
 *
 * ```json
 * {
 *   "pack": "team-backend",
 *   "plugins": ["ai.rever.boss.plugin.dynamic.terminaltab", "ai.rever.boss.plugin.dynamic.codebase@1.4.2?"],
 *   "allow_tools": ["run_tests"],
 *   "ask_providers": ["ai.rever.boss.plugin.dynamic.codebase"]
 * }
 * ```
 *
 * A plugin entry is `<pluginId>[@<version>][?]`: no version means the store's current release, and a
 * trailing `?` marks the row optional. Rule lists are `allow_tools`, `ask_tools`, `deny_tools`,
 * `allow_providers`, `ask_providers` and `deny_providers`.
 *
 * Every limit below exists so the operator sees the whole pack: [McpArgumentSanitizer] omits an
 * argument string over 16 KiB and cuts each top-level value at 4096 characters, and a pack whose
 * dialog rendering would be cut is refused rather than approved half-seen.
 */
object PluginPackParser {
    const val MAX_RAW_ARGUMENT_CHARS = 16_000
    const val MAX_RENDERED_FIELD_CHARS = 4_000
    const val MAX_PLUGINS = 40
    const val MAX_RULES = 80

    /** The provider id of the pack tools themselves. A pack may not write rules for it. */
    const val PACK_PROVIDER_ID = "boss-plugin-packs"

    /** The pack tools. A pack that could pre-approve these could approve every later pack. */
    val PACK_TOOL_NAMES = setOf("pack_plan", "pack_apply", "pack_status")

    private const val PACK_KEY = "pack"
    private const val PLUGINS_KEY = "plugins"
    private val RULE_KEYS: Map<String, Pair<PackRuleScope, McpPolicyAction>> =
        mapOf(
            "allow_tools" to (PackRuleScope.TOOL to McpPolicyAction.ALLOW),
            "ask_tools" to (PackRuleScope.TOOL to McpPolicyAction.ASK),
            "deny_tools" to (PackRuleScope.TOOL to McpPolicyAction.DENY),
            "allow_providers" to (PackRuleScope.PROVIDER to McpPolicyAction.ALLOW),
            "ask_providers" to (PackRuleScope.PROVIDER to McpPolicyAction.ASK),
            "deny_providers" to (PackRuleScope.PROVIDER to McpPolicyAction.DENY),
        )
    private val KNOWN_KEYS = RULE_KEYS.keys + PACK_KEY + PLUGINS_KEY

    private val PACK_ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    private val PLUGIN_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val VERSION = Regex("[0-9A-Za-z][0-9A-Za-z.+-]{0,63}")
    private val TOOL_NAME = Regex("[A-Za-z0-9_.-]{1,128}")

    /** The parsed pack, or every problem found with it, so a caller can fix them in one pass. */
    fun parse(raw: String): Result<PluginPack> {
        val root =
            when {
                raw.length > MAX_RAW_ARGUMENT_CHARS -> null
                else -> jsonObjectOrNull(raw)
            }
        return when {
            raw.length > MAX_RAW_ARGUMENT_CHARS -> {
                invalid("The pack is ${raw.length} characters; the limit is $MAX_RAW_ARGUMENT_CHARS.")
            }

            root == null -> {
                invalid("The pack must be a JSON object.")
            }

            else -> {
                parseObject(root)
            }
        }
    }

    private fun parseObject(root: JsonObject): Result<PluginPack> {
        val problems = shapeProblems(root)
        val id = (root[PACK_KEY] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (id == null || !PACK_ID.matches(id)) {
            problems += "\"pack\" must be a lowercase id of letters, digits, '.', '_' or '-' (at most 64)."
        }
        val entryProblems = mutableListOf<String>()
        val plugins = parsePlugins(root[PLUGINS_KEY], entryProblems)
        val rules = parseRules(root, entryProblems)
        // Only when nothing was named. A malformed or refused entry has already been reported, and
        // "names no plugins" beside "must be a list of strings" would contradict the pack as written.
        if (plugins.isEmpty() && rules.isEmpty() && entryProblems.isEmpty()) {
            entryProblems += "The pack names no plugins and no rules."
        }
        problems += entryProblems

        return if (problems.isEmpty() && id != null) {
            Result.success(PluginPack(id, plugins, rules))
        } else {
            invalid(problems)
        }
    }

    /** Unknown fields, and fields the approval dialog could not show in full. */
    private fun shapeProblems(root: JsonObject): MutableList<String> {
        val problems = mutableListOf<String>()
        val unknown = root.keys - KNOWN_KEYS
        if (unknown.isNotEmpty()) problems += "Unknown field(s): ${unknown.sorted().joinToString()}."
        root
            .filter { (key, value) -> key in KNOWN_KEYS && value.toString().length > MAX_RENDERED_FIELD_CHARS }
            .forEach { (key, _) -> problems += "\"$key\" is too long to show in full in the approval dialog." }
        return problems
    }

    private fun jsonObjectOrNull(raw: String): JsonObject? =
        try {
            Json.parseToJsonElement(raw) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun parsePlugins(
        element: Any?,
        problems: MutableList<String>,
    ): List<PackPlugin> {
        val entries = stringList(PLUGINS_KEY, element, problems) ?: return emptyList()
        if (entries.size > MAX_PLUGINS) problems += "A pack may name at most $MAX_PLUGINS plugins."
        val plugins = entries.mapNotNull { parsePluginEntry(it, problems) }
        val duplicated = plugins.groupBy { it.pluginId }.filterValues { it.size > 1 }.keys
        if (duplicated.isNotEmpty()) problems += "Plugin(s) listed twice: ${duplicated.sorted().joinToString()}."
        return plugins
    }

    private fun parsePluginEntry(
        entry: String,
        problems: MutableList<String>,
    ): PackPlugin? {
        val optional = entry.endsWith("?")
        val body = entry.removeSuffix("?")
        val pluginId = body.substringBefore('@')
        val version = if ('@' in body) body.substringAfter('@') else null
        return when {
            !PLUGIN_ID.matches(pluginId) -> {
                problems += "\"$entry\" is not a plugin id."
                null
            }

            version != null && !VERSION.matches(version) -> {
                problems += "\"$entry\" has an invalid version; name one exact version or leave it out."
                null
            }

            pluginId in PluginDependencyResolution.NOT_USER_INSTALLABLE -> {
                problems += "$pluginId is part of the host and cannot be installed by a pack."
                null
            }

            else -> {
                PackPlugin(pluginId, version, optional)
            }
        }
    }

    private fun parseRules(
        root: JsonObject,
        problems: MutableList<String>,
    ): List<PackRule> {
        val rules =
            RULE_KEYS.flatMap { (key, kind) ->
                val (scope, action) = kind
                stringList(key, root[key], problems).orEmpty().mapNotNull { subject ->
                    when {
                        !TOOL_NAME.matches(subject) -> {
                            problems += "\"$subject\" in \"$key\" is not a tool or provider name."
                            null
                        }

                        subject in PACK_TOOL_NAMES || subject == PACK_PROVIDER_ID -> {
                            problems += "A pack cannot set policy for the pack tools themselves (\"$subject\")."
                            null
                        }

                        else -> {
                            PackRule(scope, subject, action)
                        }
                    }
                }
            }
        if (rules.size > MAX_RULES) problems += "A pack may set at most $MAX_RULES rules."
        val conflicting = rules.groupBy { it.scope to it.subject }.filterValues { it.size > 1 }.keys
        if (conflicting.isNotEmpty()) {
            problems += "Named in more than one rule list: ${conflicting.map { it.second }.sorted().joinToString()}."
        }
        return rules
    }

    private fun stringList(
        key: String,
        element: Any?,
        problems: MutableList<String>,
    ): List<String>? {
        val strings =
            (element as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        val wellFormed = strings != null && strings.none { it == null }
        if (element != null && element != JsonNull && !wellFormed) problems += "\"$key\" must be a list of strings."
        return if (wellFormed) strings.filterNotNull() else null
    }

    private fun invalid(problem: String): Result<PluginPack> = invalid(listOf(problem))

    private fun invalid(problems: List<String>): Result<PluginPack> = Result.failure(InvalidPackException(problems))
}

/** A pack that failed validation, with every problem found. */
class InvalidPackException(
    val problems: List<String>,
) : IllegalArgumentException(problems.joinToString(" "))
