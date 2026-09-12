@file:Suppress("UNUSED")

package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.workspace.SplitConfig.HorizontalSplit
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import ai.rever.boss.plugin.workspace.SplitConfig.VerticalSplit
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
fun SplitConfig.extractPanels(prefix: String = ""): List<Pair<String, String>> = this.pluginExtractPanels(prefix)

/**
 * Predefined workspaces matching the split templates.
 * Uses placeholders that are resolved at runtime:
 * - {projectPath}: Current project directory
 * - {gitRemoteUrl}: Git remote origin URL
 *
 * Note: This object stays in composeApp as it contains project-specific configuration.
 */
object PredefinedWorkspaces {
    // A counter, not a timestamp plus a random draw: this whole list is built in one
    // initializer, so every id would be minted in the same millisecond and collide on
    // a 1-in-10000 draw. Panel ids key the split tree, so a collision is not cosmetic.
    // A plain var is enough - the only caller is the single-threaded initializer below.
    private var panelIdCounter = 0

    private fun generatePanelId() = "panel-predefined-${++panelIdCounter}"

    /** Id of the browser-only workspace, the platform default on Windows. */
    const val BROWSER_ONLY_ID = "workspace-browser"

    /** Id of the terminal + browser workspace, the platform default everywhere else. */
    const val CLAUDE_CODE_ID = "workspace-claude-code"

    // The other six, promoted from literals in the list below so the whole built-in SET is
    // nameable in one place. What needs the set is the Space picker's Templates section: "a
    // template" is "one of the eight we ship", which is identity and cannot be derived from the
    // layout - a shipped layout with nothing to parameterise (Browser Only) is still one of ours,
    // and `LayoutWorkspace.generateId()` mints `workspace-<epoch millis>`, so a saved Space carries
    // the same `workspace-` prefix and a prefix test would call every Space a template.
    const val CODE_REVIEW_ID = "workspace-code-review"
    const val GEMINI_ID = "workspace-gemini"
    const val CODEX_ID = "workspace-codex"
    const val OPENCODE_ID = "workspace-opencode"
    const val TERMINAL_BROWSER_ID = "workspace-terminal-browser"
    const val DUAL_TERMINAL_ID = "workspace-dual-terminal"

    /**
     * Every id BOSS ships a layout for.
     *
     * **Identity, not shape.** This is what makes something a TEMPLATE in the Space picker, and it
     * is deliberately a different question from [requiresProject], which asks whether a layout has
     * placeholders left to substitute. The two agree on seven of these eight and disagree on
     * Browser Only: a single browser panel on a fixed URL is one of the layouts we ship and has
     * nothing to parameterise. Templates is the first question; materialising is the second.
     *
     * Derived from [allWorkspaces] rather than listed again, so a ninth built-in joins the set by
     * existing. The constants above are for naming one; this is for asking about all of them.
     */
    val allIds: Set<String> get() = allWorkspaces.map { it.id }.toSet()

    /**
     * Home page the browser-only workspace opens with.
     *
     * The `www` host, matching `JxBrowserConfig.defaultUrl`, `Fluck`'s fallback and the
     * recent-pages seed: the apex 301s here, so the other spelling would cost a redirect
     * and put the default workspace on a different origin from the browser's own default.
     */
    const val BROWSER_ONLY_URL = "https://www.risalabs.ai"

    val allWorkspaces =
        listOf(
            // Claude Code: Terminal + Browser
            LayoutWorkspace(
                id = CLAUDE_CODE_ID,
                name = "Claude Code",
                description = "Terminal with Claude CLI + Browser with GitHub",
                layout =
                    VerticalSplit(
                        left =
                            SinglePanel(
                                PanelConfig(
                                    id = generatePanelId(),
                                    tabs =
                                        listOf(
                                            TabConfig(
                                                type = "terminal",
                                                title = "Claude Code",
                                                initialCommand =
                                                    "cd {projectPath} && clear && " +
                                                        "claude {claudeContinueFlag} --dangerously-skip-permissions",
                                                workingDirectory = "{projectPath}",
                                            ),
                                        ),
                                ),
                            ),
                        right =
                            SinglePanel(
                                PanelConfig(
                                    id = generatePanelId(),
                                    tabs =
                                        listOf(
                                            TabConfig(
                                                type = "browser",
                                                title = "GitHub",
                                                url = "{gitRemoteUrl}",
                                            ),
                                        ),
                                ),
                            ),
                    ),
            ),
            // Code Review: Editor (left) + Browser (right) + Terminal (bottom)
            LayoutWorkspace(
                id = CODE_REVIEW_ID,
                name = "Code Review",
                description = "README + GitHub + Claude Code",
                layout =
                    HorizontalSplit(
                        top =
                            VerticalSplit(
                                left =
                                    SinglePanel(
                                        PanelConfig(
                                            id = generatePanelId(),
                                            tabs =
                                                listOf(
                                                    TabConfig(
                                                        type = "editor",
                                                        title = "README.md",
                                                        filePath = "{projectPath}/README.md",
                                                    ),
                                                ),
                                        ),
                                    ),
                                right =
                                    SinglePanel(
                                        PanelConfig(
                                            id = generatePanelId(),
                                            tabs =
                                                listOf(
                                                    TabConfig(
                                                        type = "browser",
                                                        title = "GitHub",
                                                        url = "{gitRemoteUrl}",
                                                    ),
                                                ),
                                        ),
                                    ),
                            ),
                        bottom =
                            SinglePanel(
                                PanelConfig(
                                    id = generatePanelId(),
                                    tabs =
                                        listOf(
                                            TabConfig(
                                                type = "terminal",
                                                title = "Claude Code",
                                                initialCommand =
                                                    "cd {projectPath} && clear && " +
                                                        "claude {claudeContinueFlag} --dangerously-skip-permissions",
                                                workingDirectory = "{projectPath}",
                                            ),
                                        ),
                                ),
                            ),
                    ),
            ),
            // Gemini: Terminal + Browser
            LayoutWorkspace(
                id = GEMINI_ID,
                name = "Gemini",
                description = "Gemini CLI + GitHub",
                layout =
                    VerticalSplit(
                        left =
                            SinglePanel(
                                PanelConfig(
                                    id = generatePanelId(),
                                    tabs =
                                        listOf(
                                            TabConfig(
                                                type = "terminal",
                                                title = "Gemini",
                                                initialCommand = "cd {projectPath} && clear && gemini",
                                                workingDirectory = "{projectPath}",
                                            ),
                                        ),
                                ),
                            ),
                        right =
                            SinglePanel(
                                PanelConfig(
                                    id = generatePanelId(),
                                    tabs =
                                        listOf(
                                            TabConfig(
                                                type = "browser",
                                                title = "GitHub",
                                                url = "{gitRemoteUrl}",
                                            ),
                                        ),
                                ),
                            ),
                    ),
            ),
            // Codex: Terminal + Browser
            LayoutWorkspace(
                id = CODEX_ID,
                name = "Codex",
                description = "OpenAI Codex CLI + GitHub",
                layout =
                    VerticalSplit(
                        left =
                            SinglePanel(
                                PanelConfig(
                                    id = generatePanelId(),
                                    tabs =
                                        listOf(
                                            TabConfig(
                                                type = "terminal",
                                                title = "Codex",
                                                initialCommand = "cd {projectPath} && clear && codex",
                                                workingDirectory = "{projectPath}",
                                            ),
                                        ),
                                ),
                            ),
                        right =
                            SinglePanel(
                                PanelConfig(
                                    id = generatePanelId(),
                                    tabs =
                                        listOf(
                                            TabConfig(
                                                type = "browser",
                                                title = "GitHub",
                                                url = "{gitRemoteUrl}",
                                            ),
                                        ),
                                ),
                            ),
                    ),
            ),
            // OpenCode: Terminal + Browser
            LayoutWorkspace(
                id = OPENCODE_ID,
                name = "OpenCode",
                description = "OpenCode AI CLI + GitHub",
                layout =
                    VerticalSplit(
                        left =
                            SinglePanel(
                                PanelConfig(
                                    id = generatePanelId(),
                                    tabs =
                                        listOf(
                                            TabConfig(
                                                type = "terminal",
                                                title = "OpenCode",
                                                initialCommand = "cd {projectPath} && clear && opencode",
                                                workingDirectory = "{projectPath}",
                                            ),
                                        ),
                                ),
                            ),
                        right =
                            SinglePanel(
                                PanelConfig(
                                    id = generatePanelId(),
                                    tabs =
                                        listOf(
                                            TabConfig(
                                                type = "browser",
                                                title = "GitHub",
                                                url = "{gitRemoteUrl}",
                                            ),
                                        ),
                                ),
                            ),
                    ),
            ),
            // Terminal + Browser
            LayoutWorkspace(
                id = TERMINAL_BROWSER_ID,
                name = "Terminal + Browser",
                description = "Terminal on left, Browser on right",
                layout =
                    VerticalSplit(
                        left =
                            SinglePanel(
                                PanelConfig(
                                    id = generatePanelId(),
                                    tabs =
                                        listOf(
                                            TabConfig(
                                                type = "terminal",
                                                title = "Terminal",
                                                initialCommand = "cd {projectPath}",
                                                workingDirectory = "{projectPath}",
                                            ),
                                        ),
                                ),
                            ),
                        right =
                            SinglePanel(
                                PanelConfig(
                                    id = generatePanelId(),
                                    tabs =
                                        listOf(
                                            TabConfig(
                                                type = "browser",
                                                title = "Google",
                                                url = "https://google.com",
                                            ),
                                        ),
                                ),
                            ),
                    ),
            ),
            // Dual Terminal
            LayoutWorkspace(
                id = DUAL_TERMINAL_ID,
                name = "Dual Terminal",
                description = "Two terminals side by side",
                layout =
                    VerticalSplit(
                        left =
                            SinglePanel(
                                PanelConfig(
                                    id = generatePanelId(),
                                    tabs =
                                        listOf(
                                            TabConfig(
                                                type = "terminal",
                                                title = "Terminal 1",
                                                initialCommand = "cd {projectPath}",
                                                workingDirectory = "{projectPath}",
                                            ),
                                        ),
                                ),
                            ),
                        right =
                            SinglePanel(
                                PanelConfig(
                                    id = generatePanelId(),
                                    tabs =
                                        listOf(
                                            TabConfig(
                                                type = "terminal",
                                                title = "Terminal 2",
                                                initialCommand = "cd {projectPath}",
                                                workingDirectory = "{projectPath}",
                                            ),
                                        ),
                                ),
                            ),
                    ),
            ),
            // Browser only: a single browser panel on the BOSS home page.
            // Was the Windows default until the default became "ask" everywhere
            // (see WorkspaceSettings.ASK_WORKSPACE_ID); still the one predefined
            // workspace that stands without a project, which is what lets it be
            // applied on a fresh start at all.
            //
            // Appended rather than prepended: this list is the order of the Settings
            // picker, the workspace menu and the home screen's cards, and it is not
            // worth reshuffling what everyone sees.
            //
            // "Browser Only", not "Browser": WorkspaceManager identifies predefined
            // workspaces by NAME (WorkspaceManager.kt:63 drops a saved workspace whose
            // name collides, and WorkspaceButton decides renameable/deletable the same
            // way), so a short generic name would silently swallow a user's own
            // hand-rolled single-browser layout.
            LayoutWorkspace(
                id = BROWSER_ONLY_ID,
                name = "Browser Only",
                description = "A single browser panel on $BROWSER_ONLY_URL",
                layout =
                    SinglePanel(
                        PanelConfig(
                            id = generatePanelId(),
                            tabs =
                                listOf(
                                    TabConfig(
                                        type = "browser",
                                        title = "RISA Labs",
                                        url = BROWSER_ONLY_URL,
                                    ),
                                ),
                        ),
                    ),
            ),
        )
}
