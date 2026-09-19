package ai.rever.boss.mcp

import ai.rever.boss.plugin.api.McpToolArgs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Host-internal: a provider with a tool that would run shell commands the call's own arguments
 * do not show, because the arguments only name where the commands are stored.
 *
 * The case that exists today is `open_workspace`: a saved Space's terminal tabs carry an
 * `initialCommand` each, typed into a shell the moment the Space is applied, and an agent that
 * passes the Space's id passes none of them. The provider used to refuse such Spaces outright,
 * because approving `open_workspace(workspaceId = x)` is not approving whatever `x.json` says
 * to run. This seam lets the operator approve exactly that: the registry asks the source for the
 * commands before the prompt, shows them in the approval dialog, asks even where the tool is
 * trusted or allowed, and after approval hands the same list back to the handler under
 * [APPROVED_STORED_COMMANDS_KEY]. That key is the handler's only proof the operator saw the
 * commands, so the registry strips it from whatever the agent sent before anything else looks
 * at the arguments; a handler that finds it there knows the registry put it there.
 *
 * The contract for a source: return the commands the call would run for these arguments, as
 * the operator should read them, or an empty list when it would run none (an unknown id, a
 * shipped template, arguments the handler will refuse anyway). Never run anything, never
 * create anything. Throwing refuses the call: a source that cannot say what would run does not
 * get to run it.
 *
 * Not part of the plugin API on purpose. A plugin's tool receives its arguments and nothing
 * else, and its stored state is its own business; this exists for host-owned tools that apply
 * operator-saved configuration, where the host can vouch for what it read.
 */
interface McpStoredCommandSource {
    suspend fun storedCommandsFor(
        toolName: String,
        args: McpToolArgs,
    ): List<String>
}

/**
 * The argument under which the registry hands an approved list of stored commands to the
 * handler, and which it removes from every incoming call to a [McpStoredCommandSource]'s tool.
 */
internal const val APPROVED_STORED_COMMANDS_KEY: String = "approvedStartupCommands"

/** The most stored commands one call may carry to the prompt; more is refused before it. */
internal const val MAX_STORED_COMMANDS_PER_CALL: Int = 32

private val storedCommandsJson = Json { ignoreUnknownKeys = true }

/**
 * The argument tree with [APPROVED_STORED_COMMANDS_KEY] removed. Arguments that are not a JSON
 * object carry no keys and come back unchanged; the handler receives them as it always did.
 */
internal fun McpToolArgs.withoutApprovedStoredCommands(): McpToolArgs {
    val tree = if (APPROVED_STORED_COMMANDS_KEY in raw) parseObject(raw) else null
    return if (tree != null && APPROVED_STORED_COMMANDS_KEY in tree) {
        parseMcpToolArgs(JsonObject(tree - APPROVED_STORED_COMMANDS_KEY).toString())
    } else {
        this
    }
}

/** The argument tree with [APPROVED_STORED_COMMANDS_KEY] set to [commands], scalar map rebuilt. */
internal fun McpToolArgs.withApprovedStoredCommands(commands: List<String>): McpToolArgs {
    if (commands.isEmpty()) return this
    val tree = parseObject(raw) ?: JsonObject(emptyMap())
    val approved = JsonArray(commands.map { JsonPrimitive(it) })
    return parseMcpToolArgs(JsonObject(tree + (APPROVED_STORED_COMMANDS_KEY to approved)).toString())
}

/**
 * The commands the operator approved for this call, or `null` when the registry supplied none.
 * Only meaningful inside a handler reached through `McpToolRegistryCore.invoke`, which is the
 * only way a handler is reached.
 */
fun McpToolArgs.approvedStoredCommands(): List<String>? {
    val approved = parseObject(raw)?.get(APPROVED_STORED_COMMANDS_KEY) as? JsonArray
    return approved?.map { it.jsonPrimitive.content }
}

// Anything a malformed argument string throws means "not an object"; the handler sees the raw text as it always did.
@Suppress("TooGenericExceptionCaught", "SwallowedException")
private fun parseObject(raw: String): JsonObject? =
    try {
        storedCommandsJson.parseToJsonElement(raw) as? JsonObject
    } catch (t: Throwable) {
        null
    }
