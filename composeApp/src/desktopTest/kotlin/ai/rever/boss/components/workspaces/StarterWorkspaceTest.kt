package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import ai.rever.boss.plugin.workspace.SplitConfig.VerticalSplit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StarterWorkspaceTest {
    @Test
    fun `two browsers opens one browser in each side of the split`() {
        val workspace = starter(PredefinedWorkspaces.DUAL_BROWSER_ID)
        val panels = splitPanels(workspace)

        assertEquals(listOf("browser", "browser"), panels.map { it.tabs.single().type })
        assertTrue(panels.all { it.tabs.single().url == "about:blank" })
        assertFalse(workspace.requiresProject())
    }

    @Test
    fun `browser and terminal starts a plain shell without a project requirement`() {
        val workspace = starter(PredefinedWorkspaces.BROWSER_TERMINAL_ID)
        val panels = splitPanels(workspace)

        assertEquals(listOf("browser", "terminal"), panels.map { it.tabs.single().type })
        val browser = panels.first().tabs.single()
        assertEquals("about:blank", browser.url)
        val terminal = panels.last().tabs.single()
        assertNull(terminal.initialCommand, "opening a starter must not execute an agent or shell command")
        assertNull(terminal.workingDirectory, "the applier resolves the current project or its no-project default")
        assertFalse(workspace.requiresProject())
    }

    @Test
    fun `starters survive normal workspace serialization and resolve as explicit defaults`() {
        listOf(PredefinedWorkspaces.DUAL_BROWSER_ID, PredefinedWorkspaces.BROWSER_TERMINAL_ID).forEach { id ->
            val workspace = starter(id)
            assertEquals(workspace, WorkspaceSerializer.deserialize(WorkspaceSerializer.serialize(workspace)))
            val resolved =
                assertIs<ProjectSelectionWorkspace.Apply>(
                    WorkspaceSettings(defaultWorkspaceId = id).resolveOnProjectSelection(),
                )
            assertEquals(workspace, resolved.workspace)
        }
    }

    private fun starter(id: String): LayoutWorkspace = PredefinedWorkspaces.allWorkspaces.single { it.id == id }

    private fun splitPanels(workspace: LayoutWorkspace): List<PanelConfig> {
        val split = assertIs<VerticalSplit>(workspace.layout)
        return listOf(assertIs<SinglePanel>(split.left).panel, assertIs<SinglePanel>(split.right).panel)
    }
}
