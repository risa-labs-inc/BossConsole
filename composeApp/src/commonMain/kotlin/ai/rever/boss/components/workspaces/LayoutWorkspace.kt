@file:Suppress("UNUSED")
package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import ai.rever.boss.plugin.workspace.SplitConfig.VerticalSplit
import ai.rever.boss.plugin.workspace.SplitConfig.HorizontalSplit
import ai.rever.boss.plugin.workspace.extractPanels as pluginExtractPanels

/**
 * Workspace types re-exported from plugin-workspace-types for backward compatibility.
 */

// Re-export all types via typealiases
typealias TabConfig = ai.rever.boss.plugin.workspace.TabConfig
typealias PanelConfig = ai.rever.boss.plugin.workspace.PanelConfig
typealias SplitConfig = ai.rever.boss.plugin.workspace.SplitConfig
typealias BreadcrumbConfig = ai.rever.boss.plugin.workspace.BreadcrumbConfig
typealias LayoutWorkspace = ai.rever.boss.plugin.workspace.LayoutWorkspace
typealias WorkspaceSerializer = ai.rever.boss.plugin.workspace.WorkspaceSerializer

// Re-export extension function
fun SplitConfig.extractPanels(prefix: String = ""): List<Pair<String, String>> =
    this.pluginExtractPanels(prefix)

/**
 * Predefined workspaces matching the split templates.
 * Uses placeholders that are resolved at runtime:
 * - {projectPath}: Current project directory
 * - {gitRemoteUrl}: Git remote origin URL
 *
 * Note: This object stays in composeApp as it contains project-specific configuration.
 */
object PredefinedWorkspaces {
    private fun generatePanelId() = "panel-${System.currentTimeMillis()}-${(Math.random() * 10000).toInt()}"

    val allWorkspaces = listOf(
        // Claude Code: Terminal + Browser
        LayoutWorkspace(
            id = "workspace-claude-code",
            name = "Claude Code",
            description = "Terminal with Claude CLI + Browser with GitHub",
            layout = VerticalSplit(
                left = SinglePanel(
                    PanelConfig(
                        id = generatePanelId(),
                        tabs = listOf(
                            TabConfig(
                                type = "terminal",
                                title = "Claude Code",
                                initialCommand = "cd {projectPath} && clear && claude {claudeContinueFlag} --dangerously-skip-permissions",
                                workingDirectory = "{projectPath}"
                            )
                        )
                    )
                ),
                right = SinglePanel(
                    PanelConfig(
                        id = generatePanelId(),
                        tabs = listOf(
                            TabConfig(
                                type = "browser",
                                title = "GitHub",
                                url = "{gitRemoteUrl}"
                            )
                        )
                    )
                )
            )
        ),

        // Code Review: Editor (left) + Browser (right) + Terminal (bottom)
        LayoutWorkspace(
            id = "workspace-code-review",
            name = "Code Review",
            description = "README + GitHub + Claude Code",
            layout = HorizontalSplit(
                top = VerticalSplit(
                    left = SinglePanel(
                        PanelConfig(
                            id = generatePanelId(),
                            tabs = listOf(
                                TabConfig(
                                    type = "editor",
                                    title = "README.md",
                                    filePath = "{projectPath}/README.md"
                                )
                            )
                        )
                    ),
                    right = SinglePanel(
                        PanelConfig(
                            id = generatePanelId(),
                            tabs = listOf(
                                TabConfig(
                                    type = "browser",
                                    title = "GitHub",
                                    url = "{gitRemoteUrl}"
                                )
                            )
                        )
                    )
                ),
                bottom = SinglePanel(
                    PanelConfig(
                        id = generatePanelId(),
                        tabs = listOf(
                            TabConfig(
                                type = "terminal",
                                title = "Claude Code",
                                initialCommand = "cd {projectPath} && clear && claude {claudeContinueFlag} --dangerously-skip-permissions",
                                workingDirectory = "{projectPath}"
                            )
                        )
                    )
                )
            )
        ),

        // Gemini: Terminal + Browser
        LayoutWorkspace(
            id = "workspace-gemini",
            name = "Gemini",
            description = "Gemini CLI + GitHub",
            layout = VerticalSplit(
                left = SinglePanel(
                    PanelConfig(
                        id = generatePanelId(),
                        tabs = listOf(
                            TabConfig(
                                type = "terminal",
                                title = "Gemini",
                                initialCommand = "cd {projectPath} && clear && gemini",
                                workingDirectory = "{projectPath}"
                            )
                        )
                    )
                ),
                right = SinglePanel(
                    PanelConfig(
                        id = generatePanelId(),
                        tabs = listOf(
                            TabConfig(
                                type = "browser",
                                title = "GitHub",
                                url = "{gitRemoteUrl}"
                            )
                        )
                    )
                )
            )
        ),

        // Codex: Terminal + Browser
        LayoutWorkspace(
            id = "workspace-codex",
            name = "Codex",
            description = "OpenAI Codex CLI + GitHub",
            layout = VerticalSplit(
                left = SinglePanel(
                    PanelConfig(
                        id = generatePanelId(),
                        tabs = listOf(
                            TabConfig(
                                type = "terminal",
                                title = "Codex",
                                initialCommand = "cd {projectPath} && clear && codex",
                                workingDirectory = "{projectPath}"
                            )
                        )
                    )
                ),
                right = SinglePanel(
                    PanelConfig(
                        id = generatePanelId(),
                        tabs = listOf(
                            TabConfig(
                                type = "browser",
                                title = "GitHub",
                                url = "{gitRemoteUrl}"
                            )
                        )
                    )
                )
            )
        ),

        // OpenCode: Terminal + Browser
        LayoutWorkspace(
            id = "workspace-opencode",
            name = "OpenCode",
            description = "OpenCode AI CLI + GitHub",
            layout = VerticalSplit(
                left = SinglePanel(
                    PanelConfig(
                        id = generatePanelId(),
                        tabs = listOf(
                            TabConfig(
                                type = "terminal",
                                title = "OpenCode",
                                initialCommand = "cd {projectPath} && clear && opencode",
                                workingDirectory = "{projectPath}"
                            )
                        )
                    )
                ),
                right = SinglePanel(
                    PanelConfig(
                        id = generatePanelId(),
                        tabs = listOf(
                            TabConfig(
                                type = "browser",
                                title = "GitHub",
                                url = "{gitRemoteUrl}"
                            )
                        )
                    )
                )
            )
        ),

        // Terminal + Browser
        LayoutWorkspace(
            id = "workspace-terminal-browser",
            name = "Terminal + Browser",
            description = "Terminal on left, Browser on right",
            layout = VerticalSplit(
                left = SinglePanel(
                    PanelConfig(
                        id = generatePanelId(),
                        tabs = listOf(
                            TabConfig(
                                type = "terminal",
                                title = "Terminal",
                                initialCommand = "cd {projectPath}",
                                workingDirectory = "{projectPath}"
                            )
                        )
                    )
                ),
                right = SinglePanel(
                    PanelConfig(
                        id = generatePanelId(),
                        tabs = listOf(
                            TabConfig(
                                type = "browser",
                                title = "Google",
                                url = "https://google.com"
                            )
                        )
                    )
                )
            )
        ),

        // Dual Terminal
        LayoutWorkspace(
            id = "workspace-dual-terminal",
            name = "Dual Terminal",
            description = "Two terminals side by side",
            layout = VerticalSplit(
                left = SinglePanel(
                    PanelConfig(
                        id = generatePanelId(),
                        tabs = listOf(
                            TabConfig(
                                type = "terminal",
                                title = "Terminal 1",
                                initialCommand = "cd {projectPath}",
                                workingDirectory = "{projectPath}"
                            )
                        )
                    )
                ),
                right = SinglePanel(
                    PanelConfig(
                        id = generatePanelId(),
                        tabs = listOf(
                            TabConfig(
                                type = "terminal",
                                title = "Terminal 2",
                                initialCommand = "cd {projectPath}",
                                workingDirectory = "{projectPath}"
                            )
                        )
                    )
                )
            )
        )
    )
}
