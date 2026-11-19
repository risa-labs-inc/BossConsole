package ai.rever.boss.utils

import ai.rever.boss.filetypes.WindowsRegistryScript

/**
 * Builds the small `.reg` file used to register the `boss:` URL scheme.
 *
 * `reg add ... /d "\"<exe>\" \"%1\""` cannot safely carry the command value: Java's Windows
 * process launcher treats an argument beginning and ending in a quote as already quoted, so
 * `reg.exe` receives the executable and `%1` as separate arguments. A `.reg` file has no second
 * command-line parser, making the value that Explorer executes explicit and stable.
 */
internal object WindowsProtocolRegistryScript {
    /** The complete registry-import script for the current BOSS executable. */
    fun buildScript(appPath: String): String {
        val executable = WindowsRegistryScript.regEscape(appPath)
        return buildString {
            appendLine(WindowsRegistryScript.HEADER)
            appendLine()

            appendLine("[$PROTOCOL_KEY]")
            appendLine("@=\"URL:BOSS Protocol\"")
            appendLine("\"URL Protocol\"=\"\"")
            appendLine()

            appendLine("[$PROTOCOL_KEY\\DefaultIcon]")
            appendLine("@=\"$executable,0\"")
            appendLine()

            appendLine("[$PROTOCOL_KEY\\shell\\open\\command]")
            // Explorer must receive the executable and URL as two separately quoted values.
            appendLine("@=\"${WindowsRegistryScript.regEscape(commandValue(appPath))}\"")
        }
    }

    /** The unescaped command Explorer should execute for a `boss://` URL. */
    internal fun commandValue(appPath: String): String = "\"$appPath\" \"%1\""
}
