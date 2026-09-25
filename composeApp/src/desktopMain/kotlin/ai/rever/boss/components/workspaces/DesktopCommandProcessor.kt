package ai.rever.boss.components.workspaces

import ai.rever.boss.run.ShellUtils

actual object CommandProcessor {
    actual fun normalizeCommand(command: String): String =
        if (ShellUtils.isWindows && command.contains(" && ")) {
            replaceUnquotedSeparators(command)
        } else {
            command
        }

    internal fun replaceUnquotedSeparators(command: String): String {
        val regions = ShellQuoteRegions.scan(command, quoteEscapeCharacter())
        if (!regions.balanced) return command.replace(" && ", "; ")
        val result = StringBuilder(command.length)
        var i = 0
        while (i < command.length) {
            if (regions.quoteBefore(i) == null && command.startsWith(" && ", i)) {
                result.append("; ")
                i += 4
            } else {
                result.append(command[i])
                i++
            }
        }
        return result.toString()
    }

    actual fun quoteEscapeCharacter(): Char = if (ShellUtils.isWindows) '`' else '\\'

    actual fun escapeInsideQuote(
        value: String,
        quote: Char,
    ): String =
        if (ShellUtils.isWindows) {
            ShellPathQuoting.powershellInsideQuote(value, quote)
        } else {
            ShellPathQuoting.posixInsideQuote(value, quote)
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
