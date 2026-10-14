package ai.rever.boss.components.workspaces

import ai.rever.boss.dashboard.WorkspacePlaceholders
import ai.rever.boss.plugin.workspace.LayoutWorkspace
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.plugin.workspace.TabConfig

/**
 * Makes a saved Space shareable across machines.
 *
 * A materialised Space records the absolute project path of the machine it was built on, in its
 * `projectPath` and in every tab's `workingDirectory` / `filePath` / `url` (and, shell-quoted, in
 * `initialCommand`). Exported verbatim, those paths point at a directory that does not exist on
 * anyone else's machine. [toPortable] replaces the Space's own project path with the
 * `{projectPath}` placeholder - the same token `PredefinedWorkspaces` templates already use - so
 * the exported JSON is project-relative; [fromPortable] substitutes a chosen project path back in,
 * reusing the exact substitution `WorkspacePlaceholders` performs when a template is materialised,
 * so a round trip is faithful.
 *
 * Path-bearing tab fields (`workingDirectory`, `filePath`, `url`) are treated as raw paths;
 * `initialCommand` is treated as shell content, so on import `{projectPath}` there is shell-quoted
 * exactly the way the template path does it - a project path with spaces survives as one argument.
 */
@Suppress("TooManyFunctions")
object WorkspacePortability {
    const val PLACEHOLDER = WorkspacePlaceholders.PROJECT_PATH_PLACEHOLDER

    /**
     * The Space with its own [LayoutWorkspace.projectPath] rewritten to [PLACEHOLDER] everywhere it
     * appears, and `projectPath` cleared. A Space with no project path is returned unchanged;
     * without an origin path, embedded absolute tab paths cannot be identified safely.
     */
    fun toPortable(workspace: LayoutWorkspace): LayoutWorkspace {
        val projectPath = workspace.projectPath
        if (projectPath.isNullOrBlank()) return workspace
        return workspace.copy(
            projectPath = null,
            layout = mapTabs(workspace.layout) { parameterize(it, projectPath) },
        )
    }

    /**
     * A portable Space bound to [projectPath]: every [PLACEHOLDER] resolved to [projectPath] and
     * `projectPath` set. A fresh id is minted so importing a shared Space cannot collide with, or
     * overwrite, a Space already on this machine.
     */
    fun fromPortable(
        workspace: LayoutWorkspace,
        projectPath: String,
    ): LayoutWorkspace =
        workspace.copy(
            id = LayoutWorkspace.generateId(),
            projectPath = projectPath,
            layout = mapTabs(workspace.layout) { resolve(it, projectPath) },
        )

    /** Serialize [workspace] to portable JSON. */
    fun toPortableJson(workspace: LayoutWorkspace): String = WorkspaceSerializer.serialize(toPortable(workspace))

    /** Deserialize portable [json] and bind it to [projectPath], or null when the JSON is invalid. */
    fun fromPortableJson(
        json: String,
        projectPath: String,
    ): LayoutWorkspace? = runCatching { fromPortable(WorkspaceSerializer.deserialize(json), projectPath) }.getOrNull()

    /** Apply [transform] to every tab in the split tree, preserving its shape and every other field. */
    private fun mapTabs(
        layout: SplitConfig,
        transform: (TabConfig) -> TabConfig,
    ): SplitConfig =
        when (layout) {
            is SplitConfig.SinglePanel -> {
                layout.copy(panel = mapPanel(layout.panel, transform))
            }

            is SplitConfig.VerticalSplit -> {
                layout.copy(
                    left = mapTabs(layout.left, transform),
                    right = mapTabs(layout.right, transform),
                )
            }

            is SplitConfig.HorizontalSplit -> {
                layout.copy(
                    top = mapTabs(layout.top, transform),
                    bottom = mapTabs(layout.bottom, transform),
                )
            }
        }

    private fun mapPanel(
        panel: PanelConfig,
        transform: (TabConfig) -> TabConfig,
    ): PanelConfig = panel.copy(tabs = panel.tabs.map(transform))

    /** Replace [projectPath] with [PLACEHOLDER] in a tab's path-bearing fields. */
    private fun parameterize(
        tab: TabConfig,
        projectPath: String,
    ): TabConfig =
        tab.copy(
            url = tab.url?.let { parameterizePath(it, projectPath) },
            filePath = tab.filePath?.let { parameterizePath(it, projectPath) },
            workingDirectory = tab.workingDirectory?.let { parameterizePath(it, projectPath) },
            initialCommand = tab.initialCommand?.let { parameterizeCommand(it, projectPath) },
        )

    /** Only the project root or one of its descendants belongs to this Space. */
    private fun parameterizePath(
        path: String,
        projectPath: String,
    ): String =
        when {
            path.startsWith("file://") -> "file://" + parameterizePath(path.removePrefix("file://"), projectPath)
            path == projectPath -> PLACEHOLDER
            path.startsWith("$projectPath/") -> PLACEHOLDER + path.removePrefix(projectPath)
            else -> path
        }

    /**
     * A command holds the project path shell-quoted (that is how the template writes it), so the
     * quoted form is matched first and the raw form second - the exact inverse of
     * [WorkspacePlaceholders.substituteProjectPath] with `quote = true`.
     */
    private fun parameterizeCommand(
        command: String,
        projectPath: String,
    ): String {
        val quoted = command.replace(CommandProcessor.quotePath(projectPath), PLACEHOLDER)
        val pathPattern = Regex("(?<![\\w./~-])${Regex.escape(projectPath)}(?=/|$|[\\s\\\"';&|)])")
        return quoted.replace(pathPattern, PLACEHOLDER)
    }

    /** Resolve [PLACEHOLDER] back to [projectPath], raw for path fields and shell-quoted for commands. */
    private fun resolve(
        tab: TabConfig,
        projectPath: String,
    ): TabConfig =
        tab.copy(
            url = tab.url?.let { raw(it, projectPath) },
            filePath = tab.filePath?.let { raw(it, projectPath) },
            workingDirectory = tab.workingDirectory?.let { raw(it, projectPath) },
            initialCommand = tab.initialCommand?.let { cmd(it, projectPath) },
        )

    private fun raw(
        content: String,
        projectPath: String,
    ): String = WorkspacePlaceholders.substituteProjectPath(content, projectPath, false)

    private fun cmd(
        content: String,
        projectPath: String,
    ): String {
        // Quote the entire descendant path. PowerShell cannot concatenate a quoted root with
        // an unquoted /suffix the way POSIX shells can. The quote-region scan is shared with
        // WorkspacePlaceholders: looking only at the immediately preceding character would
        // mistake `{projectPath}` in `"prefix{projectPath}/sub"` for a bare argument.
        val regions = ShellQuoteRegions.scan(content, CommandProcessor.quoteEscapeCharacter())
        val descendant = Regex("\\{projectPath\\}(/[^\\s\"';&|)]*)")
        val quotedDescendants =
            descendant.replace(content) { match ->
                if (regions.quoteBefore(match.range.first) == null) {
                    CommandProcessor.quotePath(projectPath + match.groupValues[1])
                } else {
                    match.value
                }
            }
        return WorkspacePlaceholders.substituteProjectPath(quotedDescendants, projectPath, true)
    }
}
