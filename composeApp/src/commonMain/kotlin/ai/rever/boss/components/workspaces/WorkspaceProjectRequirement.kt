package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.workspace.SplitConfig.HorizontalSplit
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import ai.rever.boss.plugin.workspace.SplitConfig.VerticalSplit

/**
 * Placeholders that only mean something once a project is selected.
 *
 * `{projectPath}` falls back to `~/BossProjects` and `{gitRemoteUrl}` to google.com when
 * there is no project, so a workspace using them does not fail - it quietly does the wrong
 * thing. The Claude Code default, applied with no project, would open a terminal running
 * `claude --dangerously-skip-permissions` in a directory the user never chose. The fallback
 * moving out of the home directory (see `DefaultWorkingDirectory`) makes that less alarming,
 * not correct: an agent still has no project to work on.
 */
private val PROJECT_PLACEHOLDERS =
    listOf(
        "{projectPath}",
        "{gitRemoteUrl}",
        "{currentFile}",
        "{claudeContinueFlag}",
    )

/**
 * Whether this workspace only makes sense with a project selected.
 *
 * Used by the fresh-start apply: a window that restored nothing and has no project
 * applies the configured default only if the default can stand on its own. That keeps
 * the rule platform-neutral - browser-only comes up on a fresh Windows install because
 * it needs nothing, and the terminal-first defaults keep waiting for a project on every
 * platform exactly as they did before.
 */
fun LayoutWorkspace.requiresProject(): Boolean = layout.collectTabs().any { it.usesProjectPlaceholder() }

private fun TabConfig.usesProjectPlaceholder(): Boolean =
    listOfNotNull(url, filePath, initialCommand, workingDirectory).any { it.hasProjectPlaceholder() }

private fun String.hasProjectPlaceholder(): Boolean = PROJECT_PLACEHOLDERS.any { it in this }

/**
 * Whether this layout needs a project for its placeholders but still stands without one.
 *
 * True when the ONLY thing a project would decide is which directory a plain shell starts in:
 * every tab that carries a placeholder is a terminal whose working directory is `{projectPath}`
 * and whose initial command, if any, is nothing but `cd {projectPath}`. With no project that is
 * the `~/BossProjects` fallback, which is a reasonable place for a shell to open - unlike the
 * hazard [PROJECT_PLACEHOLDERS] records, an AGENT started in a directory nobody chose.
 *
 * Shape rather than a list of ids, because the property is about what the layout runs: a built-in
 * edited to start `claude` in its terminal stops qualifying by itself, where a hard-coded id would
 * keep waving it through. Today exactly Terminal + Browser and Dual Terminal qualify.
 */
fun LayoutWorkspace.projectIsOptional(): Boolean {
    // A block body only because the expression form sits between ktlint's and detekt's line rules.
    return requiresProject() && layout.collectTabs().all { it.onlyPlacesAShell() }
}

private fun TabConfig.onlyPlacesAShell(): Boolean =
    !usesProjectPlaceholder() ||
        (
            listOfNotNull(url, filePath).none { it.hasProjectPlaceholder() } &&
                (workingDirectory == null || workingDirectory == PROJECT_DIRECTORY) &&
                (initialCommand?.trim() ?: SHELL_IN_PROJECT) == SHELL_IN_PROJECT
        )

private const val PROJECT_DIRECTORY = "{projectPath}"
private const val SHELL_IN_PROJECT = "cd $PROJECT_DIRECTORY"

private fun SplitConfig.collectTabs(): List<TabConfig> =
    when (this) {
        is SinglePanel -> panel.tabs
        is VerticalSplit -> left.collectTabs() + right.collectTabs()
        is HorizontalSplit -> top.collectTabs() + bottom.collectTabs()
    }
