package ai.rever.boss.components.workspaces

import ai.rever.boss.cli.CLISecurityValidator
import ai.rever.boss.plugin.workspace.SplitConfig.HorizontalSplit
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import ai.rever.boss.plugin.workspace.SplitConfig.VerticalSplit

/**
 * Every terminal command applying this Space would type into a shell, in layout order.
 *
 * These are the `initialCommand`s of its terminal tabs, exactly as the file carries them
 * (placeholders such as `{projectPath}` unsubstituted), which is what an operator asked to
 * confirm the load should be shown. A blank command types nothing and is left out.
 */
internal fun LayoutWorkspace.terminalCommands(): List<String> = layout.terminalCommands()

private fun SplitConfig.terminalCommands(): List<String> =
    when (this) {
        is SinglePanel -> {
            panel.tabs
                .filter { it.type == TERMINAL_TAB_TYPE }
                .mapNotNull { tab -> tab.initialCommand?.takeIf { it.isNotBlank() } }
        }

        is VerticalSplit -> {
            left.terminalCommands() + right.terminalCommands()
        }

        is HorizontalSplit -> {
            top.terminalCommands() + bottom.terminalCommands()
        }
    }

/** The `TabConfig.type` `WorkspaceApplier` builds a terminal tab for. */
private const val TERMINAL_TAB_TYPE = "terminal"

/**
 * Most terminal commands one confirmation will list. A Space asking for more than a person can
 * review in one prompt is not one anybody can meaningfully approve, so it is not offered.
 */
internal const val SPACE_CONFIRM_MAX_COMMANDS = 8

/**
 * Longest single command a confirmation will show - the same bound the `boss://terminal`
 * prompt uses, for the same reason: the prompt has to be able to show it in full.
 */
internal const val SPACE_CONFIRM_MAX_COMMAND_LENGTH = 512

/** What a window does with a request to load a Space. */
internal enum class SpaceLoadDisposition {
    /** Load and apply it, starting its terminal commands. */
    LOAD,

    /** Show the operator its terminal commands and load it only if they confirm. */
    CONFIRM,

    /** Load nothing at all. */
    REJECT,
}

/**
 * Decides what happens to a Space load, from the terminal commands it carries and whether the
 * request needs the operator's confirmation.
 *
 * A load that needs no confirmation, or a Space with no terminal commands, is applied as it
 * always was: opening browser and editor tabs types nothing into a shell. Otherwise the load is
 * put in front of the operator first, and dropped outright when a command is malformed (a line
 * break would type lines the prompt never showed) or when the commands cannot all be shown.
 */
internal fun spaceLoadDisposition(
    commands: List<String>,
    requiresConfirmation: Boolean,
): SpaceLoadDisposition =
    when {
        !requiresConfirmation || commands.isEmpty() -> SpaceLoadDisposition.LOAD
        commands.size > SPACE_CONFIRM_MAX_COMMANDS -> SpaceLoadDisposition.REJECT
        commands.any { !CLISecurityValidator.isValidCommand(it) } -> SpaceLoadDisposition.REJECT
        commands.any { it.length > SPACE_CONFIRM_MAX_COMMAND_LENGTH } -> SpaceLoadDisposition.REJECT
        else -> SpaceLoadDisposition.CONFIRM
    }
