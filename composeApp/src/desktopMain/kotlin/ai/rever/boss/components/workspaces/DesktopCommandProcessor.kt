package ai.rever.boss.components.workspaces

import ai.rever.boss.run.ShellUtils

actual object CommandProcessor {
    actual fun normalizeCommand(command: String): String {
        // On Windows, replace " && " with "; " for PowerShell/CMD compatibility
        // Note: Preserves "&&" without surrounding spaces (edge case: logical operators)
        return if (ShellUtils.isWindows) {
            command.replace(" && ", "; ")
        } else {
            command // Keep Unix-style && on macOS/Linux
        }
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
