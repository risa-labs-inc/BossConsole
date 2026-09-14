package ai.rever.boss.config

/**
 * The shared line semantics of the `env_vars` file.
 *
 * The file has two in-repo readers - `ConfigLoader`'s `BOSS_MODE` precedence tier (desktop) and
 * `MicrokernelModePreference`'s toggle reader (common) - plus the secret-manager plugin, which
 * resolves API keys from it. The two in-repo readers must agree line by line: while they
 * disagreed (BossConsole#450's review), Settings could show Microkernel Mode ON for a file that
 * every runtime gate resolved as MONOLITH. Both readers now delegate to this one normalization,
 * and their tests pin the same line samples through each reader.
 *
 * Line rules, in order:
 * - blank lines are ignored;
 * - a line whose first non-blank character is `#` is a comment, indented or not - the disabled
 *   state `setEnabled` writes is exactly `# BOSS_MODE=KERNEL`, and operators may indent it;
 * - a leading `export ` prefix is shell convention and is stripped before the `KEY=value`
 *   split, because `env_vars` doubles as the secret-manager plugin's key file, where it is used;
 * - the first `=` splits key and value, both trimmed; a line with no `=`, or a blank key or
 *   value, assigns nothing.
 */

/**
 * The active `key` to `value` this line assigns, or null for a line that assigns nothing (blank,
 * comment, or malformed). A leading `#` marker makes the line inactive, so the commented-out
 * mode line a disable wrote is not a live assignment.
 */
internal fun envVarsKeyValue(line: String): Pair<String, String>? {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
    val body =
        if (trimmed.startsWith("export ")) trimmed.removePrefix("export ").trimStart() else trimmed
    val separator = body.indexOf('=')
    return if (separator <= 0) {
        null
    } else {
        val key = body.substring(0, separator).trim()
        val value = body.substring(separator + 1).trim()
        if (key.isEmpty() || value.isEmpty()) null else key to value
    }
}

/**
 * The key a line would assign under [envVarsKeyValue], ignoring a leading `#` comment marker.
 *
 * This is the "is this the BOSS_MODE line?" matcher `setEnabled` needs to find and replace the
 * mode line in place - including the commented-out one it itself wrote on a disable - instead of
 * appending a second, unrelated line and leaving the original underneath.
 */
internal fun envVarsKey(line: String): String? {
    val body =
        line
            .trimStart()
            .removePrefix("#")
            .trimStart()
            .removePrefix("export ")
            .trimStart()
    val separator = body.indexOf('=')
    if (separator <= 0) return null
    return body.substring(0, separator).trim().takeIf { it.isNotEmpty() }
}

/**
 * Parses [lines] the way both readers must: the last active assignment wins, matching
 * `Properties.putAll` applied over the same lines.
 */
internal fun parseEnvVarsLines(lines: List<String>): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    for (line in lines) {
        envVarsKeyValue(line)?.let { (key, value) -> result[key] = value }
    }
    return result
}
