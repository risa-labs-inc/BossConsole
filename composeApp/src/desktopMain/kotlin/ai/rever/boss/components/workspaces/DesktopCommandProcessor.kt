package ai.rever.boss.components.workspaces

import ai.rever.boss.run.ShellUtils

actual object CommandProcessor {
    actual fun normalizeCommand(command: String): String =
        if (ShellUtils.isWindows && command.contains(" && ")) {
            replaceUnquotedSeparators(command)
        } else {
            command
        }

    private fun replaceUnquotedSeparators(command: String): String {
        val result = StringBuilder(command.length)
        var inSingleQuote = false
        var inDoubleQuote = false
        var i = 0
        while (i < command.length) {
            val c = command[i]
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote
                result.append(c)
                i++
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote
                result.append(c)
                i++
            } else {
                val inQuotes = inSingleQuote || inDoubleQuote
                if (!inQuotes && command.startsWith(" && ", i)) {
                    result.append("; ")
                    i += 4
                } else {
                    result.append(c)
                    i++
                }
            }
        }
        return result.toString()
    }

    actual fun quotePath(path: String): String {
        // Assumes PowerShell on Windows (TerminalSettings.windowsShell defaults to
        // "powershell"), the SAME assumption normalizeCommand makes with its ";"
        // separator. Under the opt-in cmd.exe shell both are wrong together — a
        // pre-existing, shared gap, not introduced here.
        return if (ShellUtils.isWindows) {
            ShellPathQuoting.powershell(path)
        } else {
            ShellPathQuoting.posix(path)
        }
    }
}
