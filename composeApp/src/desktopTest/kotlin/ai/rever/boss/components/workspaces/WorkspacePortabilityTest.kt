package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.workspace.LayoutWorkspace
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.plugin.workspace.TabConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Round-trip tests for [WorkspacePortability]: a materialised Space is exported to a
 * project-relative form and resolved back against a project path faithfully, including the
 * shell-quoted project path inside a terminal command.
 */
class WorkspacePortabilityTest {
    private val projectPath = "/Users/me/proj"

    private fun sampleWorkspace(pp: String?): LayoutWorkspace {
        val command = "cd ${CommandProcessor.quotePath("/Users/me/proj")} && claude"
        val panel =
            PanelConfig(
                id = "main",
                tabs =
                    listOf(
                        TabConfig(type = "browser", title = "B", url = "/Users/me/proj/index.html"),
                        TabConfig(type = "editor", title = "E", filePath = "/Users/me/proj/src/A.kt"),
                        TabConfig(
                            type = "terminal",
                            title = "T",
                            initialCommand = command,
                            workingDirectory = "/Users/me/proj",
                        ),
                    ),
            )
        return LayoutWorkspace(
            id = "workspace-1",
            name = "Sample",
            description = "d",
            layout = SplitConfig.SinglePanel(panel),
            projectPath = pp,
        )
    }

    private fun tabs(ws: LayoutWorkspace) = (ws.layout as SplitConfig.SinglePanel).panel.tabs

    @Test
    fun `toPortable replaces the project path with the placeholder and clears projectPath`() {
        val portable = WorkspacePortability.toPortable(sampleWorkspace(projectPath))
        assertNull(portable.projectPath)
        val portableTabs = tabs(portable)
        assertEquals("${WorkspacePortability.PLACEHOLDER}/index.html", portableTabs[0].url)
        assertEquals("${WorkspacePortability.PLACEHOLDER}/src/A.kt", portableTabs[1].filePath)
        assertEquals(WorkspacePortability.PLACEHOLDER, portableTabs[2].workingDirectory)
        assertEquals("cd ${WorkspacePortability.PLACEHOLDER} && claude", portableTabs[2].initialCommand)
        // The absolute path must not survive anywhere in the serialized portable form.
        assertFalse(WorkspacePortability.toPortableJson(sampleWorkspace(projectPath)).contains(projectPath))
    }

    @Test
    fun `a round trip to the same project path reproduces the original tabs`() {
        val original = sampleWorkspace(projectPath)
        val portable = WorkspacePortability.toPortable(original)
        val restored = WorkspacePortability.fromPortable(portable, projectPath)

        assertEquals(tabs(original), tabs(restored))
        assertEquals(projectPath, restored.projectPath)
    }

    @Test
    fun `export leaves sibling paths and command arguments untouched`() {
        val workspace = sampleWorkspace(projectPath)
        val originalPanel = (workspace.layout as SplitConfig.SinglePanel).panel
        val sibling = "/Users/me/proj-old/src/A.kt"
        val altered =
            workspace.copy(
                layout =
                    SplitConfig.SinglePanel(
                        originalPanel.copy(
                            tabs =
                                listOf(
                                    TabConfig(type = "editor", title = "Sibling", filePath = sibling),
                                    TabConfig(type = "terminal", title = "Command", initialCommand = "cat $sibling"),
                                ),
                        ),
                    ),
            )

        val portable = WorkspacePortability.toPortable(altered)
        assertEquals(sibling, tabs(portable)[0].filePath)
        assertEquals("cat $sibling", tabs(portable)[1].initialCommand)
    }

    @Test
    fun `fromPortable binds a new project path and mints a fresh id`() {
        val portable = WorkspacePortability.toPortable(sampleWorkspace(projectPath))
        val restored = WorkspacePortability.fromPortable(portable, "/opt/other")

        val restoredTabs = tabs(restored)
        assertEquals("/opt/other/index.html", restoredTabs[0].url)
        assertEquals("/opt/other/src/A.kt", restoredTabs[1].filePath)
        assertEquals("/opt/other", restoredTabs[2].workingDirectory)
        assertEquals("cd ${CommandProcessor.quotePath("/opt/other")} && claude", restoredTabs[2].initialCommand)
        assertEquals("/opt/other", restored.projectPath)
        assertNotEquals("workspace-1", restored.id)
    }

    @Test
    fun `a Space with no project path keeps its unknown absolute tab paths`() {
        val ws = sampleWorkspace(null)
        assertSame(ws, WorkspacePortability.toPortable(ws))
        assertEquals("/Users/me/proj/src/A.kt", tabs(WorkspacePortability.toPortable(ws))[1].filePath)
    }

    @Test
    fun `nested split branches all convert and resolve`() {
        fun paths(layout: SplitConfig): List<String?> =
            when (layout) {
                is SplitConfig.SinglePanel -> layout.panel.tabs.map { it.filePath }
                is SplitConfig.VerticalSplit -> paths(layout.left) + paths(layout.right)
                is SplitConfig.HorizontalSplit -> paths(layout.top) + paths(layout.bottom)
            }

        fun leaf(id: String) =
            SplitConfig.SinglePanel(
                PanelConfig(
                    id = id,
                    tabs = listOf(TabConfig(type = "editor", title = id, filePath = "$projectPath/$id.kt")),
                ),
            )
        val workspace =
            sampleWorkspace(projectPath).copy(
                layout =
                    SplitConfig.VerticalSplit(
                        leaf("left"),
                        SplitConfig.HorizontalSplit(leaf("top"), leaf("bottom")),
                    ),
            )

        val portable = WorkspacePortability.toPortable(workspace)
        assertEquals(
            listOf("{projectPath}/left.kt", "{projectPath}/top.kt", "{projectPath}/bottom.kt"),
            paths(portable.layout),
        )
        val restored = WorkspacePortability.fromPortable(portable, "/opt/other")
        assertEquals(
            listOf("/opt/other/left.kt", "/opt/other/top.kt", "/opt/other/bottom.kt"),
            paths(restored.layout),
        )
    }

    @Test
    fun `already portable tabs stay portable and quoted placeholders resolve once`() {
        val workspace =
            sampleWorkspace(null).copy(
                layout =
                    SplitConfig.SinglePanel(
                        PanelConfig(
                            id = "main",
                            tabs =
                                listOf(
                                    TabConfig(
                                        type = "terminal",
                                        title = "T",
                                        filePath = "{projectPath}/A.kt",
                                        initialCommand = "cd \"{projectPath}\" && pwd",
                                    ),
                                ),
                        ),
                    ),
            )
        val portable = WorkspacePortability.toPortable(workspace)
        assertSame(workspace, portable)
        val resolved = WorkspacePortability.fromPortable(portable, "/opt/my project")
        assertEquals("/opt/my project/A.kt", tabs(resolved)[0].filePath)
        assertEquals("cd \"/opt/my project\" && pwd", tabs(resolved)[0].initialCommand)
    }

    @Test
    fun `a command descendant quotes the whole resolved path`() {
        val workspace = sampleWorkspace(projectPath)
        val panel = (workspace.layout as SplitConfig.SinglePanel).panel
        val withDescendant =
            workspace.copy(
                layout =
                    SplitConfig.SinglePanel(
                        panel.copy(
                            tabs =
                                listOf(
                                    TabConfig(type = "terminal", title = "T", initialCommand = "cd $projectPath/src"),
                                ),
                        ),
                    ),
            )
        val portable = WorkspacePortability.toPortable(withDescendant)
        assertEquals("cd {projectPath}/src", tabs(portable)[0].initialCommand)
        val resolved = WorkspacePortability.fromPortable(portable, "/opt/my project")
        assertEquals("cd ${CommandProcessor.quotePath("/opt/my project/src")}", tabs(resolved)[0].initialCommand)
    }

    @Test
    fun `portable command inside a wider quote region uses the shared quote-context escaping`() {
        val workspace = sampleWorkspace(projectPath)
        val panel = (workspace.layout as SplitConfig.SinglePanel).panel
        val portable =
            workspace.copy(
                layout =
                    SplitConfig.SinglePanel(
                        panel.copy(
                            tabs =
                                listOf(
                                    TabConfig(
                                        type = "terminal",
                                        title = "T",
                                        initialCommand = "echo \"directory: $projectPath/sub\" && claude",
                                    ),
                                ),
                        ),
                    ),
            )

        val exported = WorkspacePortability.toPortable(portable)
        assertEquals(
            "echo \"directory: ${WorkspacePortability.PLACEHOLDER}/sub\" && claude",
            tabs(exported).single().initialCommand,
        )

        val destination = "/opt/a\"b$(touch nope)"
        val restored = WorkspacePortability.fromPortable(exported, destination)
        assertEquals(
            "echo \"directory: ${CommandProcessor.escapeInsideQuote(destination, '\"')}/sub\" && claude",
            tabs(restored).single().initialCommand,
        )
    }

    @Test
    fun `fromPortableJson returns null on invalid JSON`() {
        assertNull(WorkspacePortability.fromPortableJson("{ not json", "/opt/other"))
    }

    @Test
    fun `toPortableJson then fromPortableJson round-trips through serialization`() {
        val json = WorkspacePortability.toPortableJson(sampleWorkspace(projectPath))
        val restored = WorkspacePortability.fromPortableJson(json, projectPath)
        requireNotNull(restored)
        assertEquals(tabs(sampleWorkspace(projectPath)), tabs(restored))
        assertTrue(json.contains(WorkspacePortability.PLACEHOLDER))
    }
}
