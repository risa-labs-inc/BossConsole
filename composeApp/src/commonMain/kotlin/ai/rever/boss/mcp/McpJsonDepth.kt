package ai.rever.boss.mcp

/**
 * Nesting depth past which MCP tool arguments are not parsed at all. Real tool calls nest a
 * few levels; this only bounds a hostile payload.
 *
 * kotlinx's tree reader recurses once per nested array, so `Json.parseToJsonElement` on a deeply
 * nested payload throws StackOverflowError - an Error that `catch (e: Exception)` does not stop.
 * Every parse of agent-supplied arguments on the invoke path checks [mcpJsonNestingExceeds] first:
 * the registry's own argument parse, the risk evaluator's shell scan, and the argument sanitizer
 * the ledger record is built from. A payload that would overflow is rejected before the parser
 * sees it, and the ledger row is still written. One constant and one guard, so the three callers
 * cannot drift apart.
 *
 * The cost is deliberate (#1655): a legitimately deeper payload (129+ levels) is never parsed,
 * so its arguments are omitted from the ledger record (`[OMITTED: too deeply nested]`), the tool
 * receives empty arguments, and a shell tool carrying it rates CRITICAL on every call.
 */
internal const val MAX_MCP_ARGUMENT_DEPTH = 128

/**
 * Whether [raw] nests arrays or objects more than [limit] deep, counting brackets outside JSON
 * strings (escapes included). A linear scan with no recursion and no parse.
 */
internal fun mcpJsonNestingExceeds(
    raw: String,
    limit: Int = MAX_MCP_ARGUMENT_DEPTH,
): Boolean {
    var depth = 0
    var inString = false
    var escaped = false
    for (c in raw) {
        when {
            escaped -> escaped = false
            inString && c == '\\' -> escaped = true
            c == '"' -> inString = !inString
            inString -> Unit
            c == '[' || c == '{' -> if (++depth > limit) return true
            c == ']' || c == '}' -> depth = maxOf(0, depth - 1)
        }
    }
    return false
}
