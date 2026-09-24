package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.plugin.workspace.SplitConfig.HorizontalSplit
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import ai.rever.boss.plugin.workspace.SplitConfig.VerticalSplit
import ai.rever.boss.plugin.workspace.TabConfig
import ai.rever.boss.plugin.workspace.WorkspaceSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests verifying the predefined workspace presets tailored to core hackathon personas:
 * - Data Science Studio (Data Science & Students)
 * - Academic & Literature Research (Academic Researchers)
 * - DevOps & Infrastructure (DevOps Engineers)
 *
 * Verifies valid split ratios and structures, unique preset IDs, non-empty tab descriptors,
 * serialization round-trip integrity, and project requirements.
 */
class PredefinedWorkspacesTest {
    private fun collectPanels(splitConfig: SplitConfig): List<PanelConfig> =
        when (splitConfig) {
            is SinglePanel -> listOf(splitConfig.panel)
            is VerticalSplit -> collectPanels(splitConfig.left) + collectPanels(splitConfig.right)
            is HorizontalSplit -> collectPanels(splitConfig.top) + collectPanels(splitConfig.bottom)
        }

    // ==================== Valid Split Ratios & Structure ====================

    @Test
    fun `data science studio has valid split structure and panel counts`() {
        val studio =
            PredefinedWorkspaces.allWorkspaces.single {
                it.id == PredefinedWorkspaces.DATA_SCIENCE_STUDIO_ID
            }
        assertEquals("Data Science Studio", studio.name)

        // 50% left documentation browser, 50% right split top/bottom
        val root = assertIs<VerticalSplit>(studio.layout)
        val leftPanel = assertIs<SinglePanel>(root.left).panel
        assertEquals(1, leftPanel.tabs.size)
        assertEquals("browser", leftPanel.tabs.first().type)
        assertEquals("https://docs.python.org/3/", leftPanel.tabs.first().url)

        val rightSplit = assertIs<HorizontalSplit>(root.right)
        val editorPanel = assertIs<SinglePanel>(rightSplit.top).panel
        assertEquals(1, editorPanel.tabs.size)
        assertEquals("editor", editorPanel.tabs.first().type)
        assertEquals("{projectPath}/main.py", editorPanel.tabs.first().filePath)

        val terminalPanel = assertIs<SinglePanel>(rightSplit.bottom).panel
        assertEquals(1, terminalPanel.tabs.size)
        assertEquals("terminal", terminalPanel.tabs.first().type)
        assertEquals("cd {projectPath}", terminalPanel.tabs.first().initialCommand)

        val panels = collectPanels(studio.layout)
        assertEquals(3, panels.size)
    }

    @Test
    fun `academic research has 3-pane vertical split structure`() {
        val academic =
            PredefinedWorkspaces.allWorkspaces.single {
                it.id == PredefinedWorkspaces.ACADEMIC_RESEARCH_ID
            }
        assertEquals("Academic & Literature Research", academic.name)

        val root = assertIs<VerticalSplit>(academic.layout)
        val leftPanel = assertIs<SinglePanel>(root.left).panel
        assertEquals(1, leftPanel.tabs.size)
        assertEquals("browser", leftPanel.tabs.first().type)
        assertEquals("https://arxiv.org", leftPanel.tabs.first().url)

        val rightSplit = assertIs<VerticalSplit>(root.right)
        val editorPanel = assertIs<SinglePanel>(rightSplit.left).panel
        assertEquals(1, editorPanel.tabs.size)
        assertEquals("editor", editorPanel.tabs.first().type)
        assertEquals("{projectPath}/notes.md", editorPanel.tabs.first().filePath)

        val terminalPanel = assertIs<SinglePanel>(rightSplit.right).panel
        assertEquals(1, terminalPanel.tabs.size)
        assertEquals("terminal", terminalPanel.tabs.first().type)

        val panels = collectPanels(academic.layout)
        assertEquals(3, panels.size)
    }

    @Test
    fun `devops preset has split terminals on left and monitor tabs on right`() {
        val devops =
            PredefinedWorkspaces.allWorkspaces.single {
                it.id == PredefinedWorkspaces.DEVOPS_INFRA_ID
            }
        assertEquals("DevOps & Infrastructure", devops.name)

        val root = assertIs<VerticalSplit>(devops.layout)
        val leftSplit = assertIs<HorizontalSplit>(root.left)
        val topTerminal = assertIs<SinglePanel>(leftSplit.top).panel
        assertEquals(1, topTerminal.tabs.size)
        assertEquals("Tail Logs", topTerminal.tabs.first().title)

        val bottomTerminal = assertIs<SinglePanel>(leftSplit.bottom).panel
        assertEquals(1, bottomTerminal.tabs.size)
        assertEquals("Active Shell", bottomTerminal.tabs.first().title)

        val rightPanel = assertIs<SinglePanel>(root.right).panel
        assertEquals(2, rightPanel.tabs.size)
        assertEquals("Host Performance Monitor", rightPanel.tabs[0].title)
        assertEquals("Process Inspector", rightPanel.tabs[1].title)

        val panels = collectPanels(devops.layout)
        assertEquals(3, panels.size)
    }

    // ==================== Unique Preset IDs ====================

    @Test
    fun `every preset has a unique id`() {
        val ids = PredefinedWorkspaces.allWorkspaces.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "Duplicate preset IDs found: $ids")
    }

    @Test
    fun `persona preset ids exist and belong to allIds`() {
        val expectedPresetIds =
            setOf(
                PredefinedWorkspaces.DATA_SCIENCE_STUDIO_ID,
                PredefinedWorkspaces.ACADEMIC_RESEARCH_ID,
                PredefinedWorkspaces.DEVOPS_INFRA_ID,
            )

        expectedPresetIds.forEach { id ->
            assertTrue(id in PredefinedWorkspaces.allIds, "Missing ID in allIds: $id")
            assertTrue(id.startsWith("workspace-"), "Preset ID must follow naming convention: $id")
        }
    }

    // ==================== Non-Empty Tab Descriptors ====================

    private fun validateTab(
        tab: TabConfig,
        workspaceId: String,
    ) {
        assertFalse(tab.title.isBlank(), "Tab in $workspaceId has blank title")
        assertFalse(tab.type.isBlank(), "Tab ${tab.title} in $workspaceId has blank type")

        when (tab.type) {
            "browser" -> {
                assertFalse(tab.url.isNullOrBlank(), "Browser tab ${tab.title} has blank url")
            }

            "editor" -> {
                assertFalse(tab.filePath.isNullOrBlank(), "Editor tab ${tab.title} has blank path")
            }

            "terminal" -> {
                val hasCommandOrDir =
                    !tab.initialCommand.isNullOrBlank() || !tab.workingDirectory.isNullOrBlank()
                assertTrue(hasCommandOrDir, "Terminal tab ${tab.title} has neither command nor working dir")
            }
        }
    }

    @Test
    fun `every panel across all presets contains non-empty tab descriptors`() {
        PredefinedWorkspaces.allWorkspaces.forEach { workspace ->
            val panels = collectPanels(workspace.layout)
            assertTrue(panels.isNotEmpty(), "Workspace ${workspace.id} must have at least one panel")

            panels.forEach { panel ->
                assertTrue(panel.tabs.isNotEmpty(), "Panel ${panel.id} in ${workspace.id} has no tabs")
                panel.tabs.forEach { validateTab(it, workspace.id) }
            }
        }
    }

    // ==================== Serialization Integrity ====================

    @Test
    fun `all predefined presets round-trip faithfully through WorkspaceSerializer`() {
        PredefinedWorkspaces.allWorkspaces.forEach { workspace ->
            val json = WorkspaceSerializer.serialize(workspace)
            assertTrue(json.isNotBlank(), "Serialized JSON is blank for ${workspace.id}")

            val deserialized = WorkspaceSerializer.deserialize(json)
            assertEquals(workspace, deserialized, "Serialization round-trip mismatch for ${workspace.id}")
        }
    }

    // ==================== Project Requirements ====================

    @Test
    fun `all persona presets require a project`() {
        val personaIds =
            setOf(
                PredefinedWorkspaces.DATA_SCIENCE_STUDIO_ID,
                PredefinedWorkspaces.ACADEMIC_RESEARCH_ID,
                PredefinedWorkspaces.DEVOPS_INFRA_ID,
            )
        val personaWorkspaces = PredefinedWorkspaces.allWorkspaces.filter { it.id in personaIds }

        assertEquals(3, personaWorkspaces.size)
        personaWorkspaces.forEach { ws ->
            assertTrue(ws.requiresProject(), "Preset ${ws.id} should require a project")
        }
    }
}
