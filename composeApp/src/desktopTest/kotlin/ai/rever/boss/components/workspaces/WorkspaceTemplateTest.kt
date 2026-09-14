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
        stamp = MaterialisedStamp(id = "workspace-1700000000000", now = 1_700_000_000_000),
        projectPath = path,
        substitute = substitution(path),
    )

    private fun tabsOf(layout: SplitConfig): List<TabConfig> =
        when (layout) {
            is SinglePanel -> layout.panel.tabs
            is VerticalSplit -> tabsOf(layout.left) + tabsOf(layout.right)
            is HorizontalSplit -> tabsOf(layout.top) + tabsOf(layout.bottom)
        }

    // ==================== two different questions ====================

    /**
     * **Being a TEMPLATE is identity; needing a project is shape.** The Space picker groups on the
     * first and the host materialises on the second, and they agree on seven of the eight built-ins
     * and disagree on exactly Browser Only - a single browser panel on a fixed URL, which is one of
     * the layouts we ship and has nothing to parameterise. Conflating them in either direction is
     * the bug: one way files a shipped layout in with the user's own Spaces, the other way tries to
     * name a copy of it after a project the layout does not reference.
     */
    @Test
    fun `the built-in SET is identity, and only seven of the eight need a project`() {
        val ids = PredefinedWorkspaces.allIds
        val needProject = PredefinedWorkspaces.allWorkspaces.filter { it.requiresProject() }.map { it.id }

        assertEquals(EXPECTED_BUILT_IN_IDS, ids, "the shipped set, which the picker's copy mirrors")
        assertEquals(
            EXPECTED_BUILT_IN_IDS - PredefinedWorkspaces.BROWSER_ONLY_ID,
            needProject.toSet(),
            "every built-in but Browser Only has placeholders left to substitute",
        )
    }

    /**
     * Browser Only must keep `requiresProject() == false`, and this is the reason: it is the
     * fresh-start default that can stand on its own. `shouldApplyOnFreshStart` declines a layout
     * that needs a project, so if this flipped, a new install with no project would come up on an
     * empty window instead of on a browser.
     */
    @Test
    fun `Browser Only needs no project, which is what lets a fresh install come up on it`() {
        val browserOnly =
            PredefinedWorkspaces.allWorkspaces.single { it.id == PredefinedWorkspaces.BROWSER_ONLY_ID }

        assertFalse(browserOnly.requiresProject(), "it carries no placeholder to substitute")
        assertTrue(
            browserOnly.id in PredefinedWorkspaces.allIds,
            "and it is still one of ours, so the picker files it under Templates",
        )
    }

    @Test
    fun `every named constant is one of the shipped ids`() {
        // The constants are for naming ONE built-in; `allIds` is for asking about all of them.
        // They cannot drift, because `allIds` is derived from the list the constants are used in -
        // but a constant that stopped being used in it would go unnoticed without this.
        listOf(
            PredefinedWorkspaces.CLAUDE_CODE_ID,
            PredefinedWorkspaces.CODE_REVIEW_ID,
            PredefinedWorkspaces.GEMINI_ID,
            PredefinedWorkspaces.CODEX_ID,
            PredefinedWorkspaces.OPENCODE_ID,
            PredefinedWorkspaces.TERMINAL_BROWSER_ID,
            PredefinedWorkspaces.DUAL_TERMINAL_ID,
            PredefinedWorkspaces.BROWSER_ONLY_ID,
        ).forEach { id ->
            assertTrue(id in PredefinedWorkspaces.allIds, "$id is a constant for a layout nobody ships")
        }
    }

    /**
     * A saved Space can never be mistaken for a built-in, which is why the picker's rule is the
     * explicit SET and not a prefix.
     */
    @Test
    fun `a generated Space id is never a built-in id`() {
        val generated = LayoutWorkspace.generateId()

        assertTrue(generated.startsWith("workspace-"), "it shares the prefix, which is the trap")
        assertFalse(generated in PredefinedWorkspaces.allIds)
        assertFalse(
            materialise(template(layout = SinglePanel(PanelConfig(id = "main", tabs = listOf(tab()))))).id
                in PredefinedWorkspaces.allIds,
            "a materialised template is a Space of the user's, not one of ours",
        )
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
     * Two projects, two Spaces, two names - so the list says which is which.
     *
     * It used to be a data-safety rule as well: the file path was `generateFileName(name)`, so a
     * name that did not carry the project made the second materialisation destroy the first
     * layout. `WorkspaceFileManagerCommon.fileNameForId` closed that, so this is now about the
     * list being readable rather than about losing work.
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

    /**
     * The SAME template against the SAME project twice, which used to be the unguarded case: two
     * ids, one name, one file - and the second save atomically replaced the first Space's layout.
     * `materialisedTemplateName` goes through `uniqueWorkspaceName` now.
     */
    @Test
    fun `the same template against the same project twice gives two names`() {
        val original =
            template(
                layout = SinglePanel(PanelConfig(id = "main", tabs = listOf(tab(workingDirectory = "{projectPath}")))),
            )
        val first = materialise(original, "/Users/me/Boss")

        val second =
            materialiseTemplate(
                template = original,
                stamp = MaterialisedStamp(id = "workspace-1700000000001", now = 1_700_000_000_001),
                projectPath = "/Users/me/Boss",
                takenNames = setOf(first.name),
                substitute = substitution("/Users/me/Boss"),
            )

        assertEquals("Claude Code (Boss)", first.name)
        assertEquals("Claude Code (Boss) 2", second.name)
    }

    @Test
    fun `a project name is the last path segment, whatever the separator or the trailing slash`() {
        assertEquals("Boss", projectNameFor("/Users/me/Boss"))
        assertEquals("Boss", projectNameFor("/Users/me/Boss/"))
        assertEquals("Boss", projectNameFor("C:\\Users\\me\\Boss"))
        assertEquals("Project", projectNameFor("/"), "a path with no segment still names something")
    }

    private companion object {
        /**
         * The eight ids BOSS ships, written out rather than read off `allIds`.
         *
         * A test that derived the expectation from the thing under test would assert nothing. This
         * is also the list the Space picker's plugin-side copy mirrors, so a ninth built-in fails
         * here first and names the file to update.
         */
        val EXPECTED_BUILT_IN_IDS =
            setOf(
                "workspace-claude-code",
                "workspace-code-review",
                "workspace-gemini",
                "workspace-codex",
                "workspace-opencode",
                "workspace-terminal-browser",
                "workspace-dual-terminal",
                "workspace-browser",
            )
    }
}
