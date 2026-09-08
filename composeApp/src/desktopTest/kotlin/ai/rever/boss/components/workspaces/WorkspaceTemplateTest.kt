package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.workspace.SplitConfig.HorizontalSplit
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import ai.rever.boss.plugin.workspace.SplitConfig.VerticalSplit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Materialising a template into a Space.
 *
 * The rule these pin is that a template is answered from its LAYOUT - `requiresProject()`, an
 * unsubstituted project placeholder - and that materialising one closes that question for good:
 * the copy that comes out is a Space, so it can never be listed as a template again. That
 * round trip is the whole feature, and it is the one thing an `isTemplate` field would have
 * given away for a hard break on the plugin api.
 */
class WorkspaceTemplateTest {
    private fun tab(
        type: String = "terminal",
        title: String = "T",
        url: String? = null,
        filePath: String? = null,
        initialCommand: String? = null,
        workingDirectory: String? = null,
    ) = TabConfig(
        type = type,
        title = title,
        url = url,
        filePath = filePath,
        initialCommand = initialCommand,
        workingDirectory = workingDirectory,
    )

    private fun template(
        name: String = "Claude Code",
        layout: SplitConfig,
    ) = LayoutWorkspace(
        id = "workspace-claude-code",
        name = name,
        description = "Terminal with Claude CLI + Browser with GitHub",
        layout = layout,
    )

    /**
     * The substitution the real one is: `{projectPath}` for a path, quoted only where asked.
     * Everything else is left alone, so a test can see which field went through which arm.
     */
    private fun substitution(path: String): (String, Boolean) -> String =
        { content, quote ->
            content.replace("{projectPath}", if (quote) "'$path'" else path)
        }

    private fun materialise(
        template: LayoutWorkspace,
        path: String = "/Users/me/Boss",
    ) = materialiseTemplate(
        template = template,
        id = "workspace-1700000000000",
        projectPath = path,
        now = 1_700_000_000_000,
        substitute = substitution(path),
    )

    private fun tabsOf(layout: SplitConfig): List<TabConfig> =
        when (layout) {
            is SinglePanel -> layout.panel.tabs
            is VerticalSplit -> tabsOf(layout.left) + tabsOf(layout.right)
            is HorizontalSplit -> tabsOf(layout.top) + tabsOf(layout.bottom)
        }

    // ==================== the predicate ====================

    @Test
    fun `a built-in layout carrying a project placeholder is a template`() {
        // Not a fixture: the shipped list is what the picker groups, so the rule is asserted
        // against it. Browser Only is the one that stands without a project, which is what lets
        // it be applied on a fresh start at all.
        val templates = PredefinedWorkspaces.allWorkspaces.filter { it.requiresProject() }
        val spaces = PredefinedWorkspaces.allWorkspaces.filterNot { it.requiresProject() }

        assertEquals(
            listOf("Browser Only"),
            spaces.map { it.name },
            "Browser Only is the only built-in that needs no project; everything else is a template",
        )
        assertTrue(templates.size >= 7, "the seven project-shaped built-ins are templates")
    }

    // ==================== materialising ====================

    @Test
    fun `materialising resolves the placeholders in every field of every pane`() {
        val materialised =
            materialise(
                template(
                    layout =
                        VerticalSplit(
                            left =
                                SinglePanel(
                                    PanelConfig(
                                        id = "panel-predefined-1",
                                        tabs =
                                            listOf(
                                                tab(
                                                    initialCommand = "cd {projectPath} && claude",
                                                    workingDirectory = "{projectPath}",
                                                ),
                                            ),
                                    ),
                                ),
                            right =
                                SinglePanel(
                                    PanelConfig(
                                        id = "panel-predefined-2",
                                        tabs =
                                            listOf(
                                                tab(type = "editor", filePath = "{projectPath}/README.md"),
                                                tab(type = "browser", url = "{projectPath}/index.html"),
                                            ),
                                    ),
                                ),
                        ),
                ),
            )

        val tabs = tabsOf(materialised.layout)
        assertEquals("cd '/Users/me/Boss' && claude", tabs[0].initialCommand)
        assertEquals("/Users/me/Boss", tabs[0].workingDirectory)
        assertEquals("/Users/me/Boss/README.md", tabs[1].filePath)
        assertEquals("/Users/me/Boss/index.html", tabs[2].url)
    }

    /**
     * The quoting split is `createTabFromWorkspaceConfig`'s, and getting it backwards is a real
     * bug both ways: an unquoted path with a space in it makes `cd /Users/me/My Project` two
     * arguments, and a quoted one in a `filePath` opens a file whose name contains the quotes.
     */
    @Test
    fun `only the shell command is quoted`() {
        val materialised =
            materialise(
                template(
                    layout =
                        SinglePanel(
                            PanelConfig(
                                id = "main",
                                tabs =
                                    listOf(
                                        tab(initialCommand = "cd {projectPath}", workingDirectory = "{projectPath}"),
                                    ),
                            ),
                        ),
                ),
                path = "/Users/me/My Project",
            )

        val restored = tabsOf(materialised.layout).single()
        assertEquals("cd '/Users/me/My Project'", restored.initialCommand)
        assertEquals("/Users/me/My Project", restored.workingDirectory)
    }

    @Test
    fun `a materialised template is an ordinary Space, not a template any more`() {
        val materialised =
            materialise(
                template(
                    layout =
                        SinglePanel(
                            PanelConfig(
                                id = "main",
                                tabs = listOf(tab(initialCommand = "cd {projectPath} && claude")),
                            ),
                        ),
                ),
            )

        assertFalse(
            materialised.requiresProject(),
            "a materialised Space has real paths, so it must never be grouped as a template again",
        )
    }

    @Test
    fun `materialising names the Space for the template and the project, with a fresh identity`() {
        val original =
            template(
                layout = SinglePanel(PanelConfig(id = "main", tabs = listOf(tab(workingDirectory = "{projectPath}")))),
            )
        val materialised = materialise(original)

        assertEquals("Claude Code (Boss)", materialised.name)
        assertEquals("/Users/me/Boss", materialised.projectPath)
        assertEquals(1_700_000_000_000, materialised.timestamp)
        assertTrue(
            materialised.id != original.id,
            "a fresh id, or materialising would overwrite the template's own entry",
        )
        assertEquals(original.description, materialised.description, "the description is the template's")
    }

    /**
     * Two projects, two Spaces. `WorkspaceManager` keys saved Spaces by NAME - it writes to
     * `generateFileName(name)` and replaces the list entry whose name matches - so a name that
     * did not carry the project would make the second materialisation destroy the first.
     */
    @Test
    fun `the same template against two projects gives two names`() {
        val original =
            template(
                layout = SinglePanel(PanelConfig(id = "main", tabs = listOf(tab(workingDirectory = "{projectPath}")))),
            )

        assertEquals("Claude Code (Boss)", materialise(original, "/Users/me/Boss").name)
        assertEquals("Claude Code (BossTerm)", materialise(original, "/Users/me/BossTerm").name)
    }

    @Test
    fun `a project name is the last path segment, whatever the separator or the trailing slash`() {
        assertEquals("Boss", projectNameFor("/Users/me/Boss"))
        assertEquals("Boss", projectNameFor("/Users/me/Boss/"))
        assertEquals("Boss", projectNameFor("C:\\Users\\me\\Boss"))
        assertEquals("Project", projectNameFor("/"), "a path with no segment still names something")
    }
}
