package ai.rever.boss.filetypes

/**
 * Builds the `.reg` script that registers BOSS for a set of file extensions, and
 * parses the shell's answer back out of `reg query` output.
 *
 * Pure, so both halves are testable without Windows - which matters more here
 * than usual, because nobody developing on macOS will notice this breaking and
 * the failure is silent (an association that exists but does not launch BOSS).
 *
 * **Why a script instead of `reg add` calls.** The previous version ran five
 * `reg add` processes per extension, 83 extensions, five categories: on the order
 * of 415 processes for one "Set all", each with its own 15 second ceiling, and 83
 * more `reg query` processes just to read the status. `reg import` does the whole
 * lot in one process.
 *
 * It also removes a quoting hazard rather than trying to get it right. The
 * `shell\open\command` value has to be `"C:\path\BOSS.exe" "%1"` - a string that
 * both begins and ends with a double quote. Java's Windows `ProcessImpl` treats
 * an argument in that shape as already quoted and passes it through without
 * escaping, so `reg` would see the value and `"%1"` as two separate tokens and
 * store only the first. That is the single write that makes a double-clicked file
 * actually reach BOSS. In a `.reg` file the value is escaped by [regEscape] and
 * never passes through a command line at all, so the question does not arise.
 */
internal object WindowsRegistryScript {
    /** `reg import` requires this exact header for a Unicode script; shared with protocol registration. */
    internal const val HEADER = "Windows Registry Editor Version 5.00"

    private const val CLASSES = """HKEY_CURRENT_USER\Software\Classes"""

    private const val CAPABILITIES =
        """HKEY_CURRENT_USER\Software\Clients\StartMenuInternet\BOSS\Capabilities\FileAssociations"""

    private const val FILE_EXTS =
        """HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Explorer\FileExts"""

    /**
     * ProgID for an extension: `BOSS.md`, `BOSS.kt`.
     *
     * One per extension rather than one per category, because `FileAssociations`
     * and `UserChoice` are both keyed by extension - a shared ProgID would make
     * "BOSS opens markdown" and "BOSS opens Kotlin" the same switch.
     */
    fun progIdFor(extension: String): String = "BOSS.${extension.lowercase()}"

    /** The `FileExts` key whose `UserChoice` holds the shell's recorded answer for [extension]. */
    fun userChoiceKey(extension: String): String = """$FILE_EXTS\.${extension.lowercase()}\UserChoice"""

    /**
     * Escapes a string for a `.reg` value.
     *
     * Backslashes double and quotes are backslash-escaped, which is what makes a
     * Windows path safe to embed. Getting this wrong writes a broken command into
     * the registry, so it is the one thing here with its own tests.
     */
    fun regEscape(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")

    /**
     * The full script for [extensions], all pointing at [appPath].
     *
     * @param categoryDisplayName shown by Explorer as the file kind, so it reads
     *   as a description rather than a ProgID.
     */
    fun buildScript(
        extensions: List<String>,
        appPath: String,
        categoryDisplayName: String,
    ): String {
        val exe = regEscape(appPath)
        val description = regEscape("$categoryDisplayName (BOSS)")

        return buildString {
            appendLine(HEADER)
            appendLine()
            extensions.map { it.lowercase() }.distinct().forEach { extension ->
                val progId = progIdFor(extension)

                appendLine("""[$CLASSES\$progId]""")
                appendLine("""@="$description"""")
                appendLine()

                appendLine("""[$CLASSES\$progId\DefaultIcon]""")
                appendLine("""@="$exe,0"""")
                appendLine()

                appendLine("""[$CLASSES\$progId\shell\open\command]""")
                // The value Explorer runs. Escaped, never shell-quoted.
                appendLine("""@="\"$exe\" \"%1\""""")
                appendLine()

                // An OpenWithProgids hint puts BOSS in the "Open with" list for the
                // extension even before it is the default, which is how the user
                // finds it in the Settings picker at all. The empty REG_NONE value
                // is the documented form.
                appendLine("""[$CLASSES\.$extension\OpenWithProgids]""")
                appendLine(""""$progId"=hex(0):""")
                appendLine()

                appendLine("""[$CAPABILITIES]""")
                appendLine(""""".$extension"="$progId"""")
                appendLine()
            }
        }
    }

    /**
     * Pulls `.ext -> ProgId` out of a single `reg query <FileExts> /s` dump.
     *
     * One process for every extension instead of one each. `reg query /s` prints
     * a key line, then indented `name<tab>type<tab>value` lines, so the parse is
     * "remember the last key, and when a ProgId value appears under a
     * `.ext\UserChoice` key, record it".
     *
     * Unparseable lines are skipped rather than failing the read: this drives a
     * status display, and a shell that prints something unexpected should cost one
     * unknown row, not the whole screen.
     */
    fun parseUserChoices(output: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var extension: String? = null

        output.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("HKEY_", ignoreCase = true)) {
                extension = userChoiceExtensionOf(line)
            } else {
                val current = extension
                val progId = if (current == null) null else progIdValueOf(line)
                if (current != null && progId != null) result[current] = progId
            }
        }
        return result
    }

    /**
     * The extension a `FileExts\.md\UserChoice` key names, or null for any other
     * key - including the extension key itself, whose `Progid` value is not the
     * user's choice and must not be read as one.
     */
    private fun userChoiceExtensionOf(keyLine: String): String? {
        val tail = keyLine.substringAfterLast("""\FileExts\""", "").takeIf { it.isNotEmpty() } ?: return null
        val parts = tail.split('\\')
        if (!parts.getOrNull(1).equals("UserChoice", ignoreCase = true)) return null
        return parts
            .getOrNull(0)
            ?.removePrefix(".")
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * The value of a `ProgId` line, or null when the line is something else.
     *
     * Split on runs of whitespace because the separator is tabs in some shells and
     * spaces in others, and a ProgID never contains whitespace.
     */
    private fun progIdValueOf(valueLine: String): String? {
        if (!valueLine.startsWith("ProgId", ignoreCase = true)) return null
        return valueLine
            .split(Regex("\\s{2,}|\\t+"))
            .lastOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("ProgId", ignoreCase = true) }
    }
}
