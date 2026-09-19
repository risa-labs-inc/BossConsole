package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.workspace.SplitConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the Project Studio template (the course-project layout from the hackathon Idea
 * Bench): it must exist in the built-in set, carry one tab of each phase the bench
 * names - research (browser), plan (editor), build (terminal), demo (jupyter) - and
 * materialise for a project like every other parameterised template.
 */
class ProjectStudioTemplateTest {
    @Test
    fun `project studio is one of the shipped templates`() {
        val studio = PredefinedWorkspaces.allWorkspaces.firstOrNull { it.id == PredefinedWorkspaces.PROJECT_STUDIO_ID }
        assertNotNull(studio, "Project Studio must exist in allWorkspaces so allIds and the picker pick it up")
        assertTrue(
            PredefinedWorkspaces.allIds.contains(PredefinedWorkspaces.PROJECT_STUDIO_ID),
            "allIds is derived from allWorkspaces, so the ninth built-in joins by existing",
        )
    }

    @Test
    fun `every phase of the bench brief has its tab`() {
        val studio = PredefinedWorkspaces.allWorkspaces.first { it.id == PredefinedWorkspaces.PROJECT_STUDIO_ID }
        val tabs = flatten(studio.layout).map { it.type }.toSet()

        assertTrue("browser" in tabs, "research phase: the browser tab")
        assertTrue("editor" in tabs, "plan phase: the editor tabs")
        assertTrue("terminal" in tabs, "build phase: the terminal tabs")
        assertTrue("jupyter" in tabs, "demo phase: the jupyter notebook tab")
    }

    @Test
    fun `the template is parameterised by a project so it materialises rather than opening`() {
        val studio = PredefinedWorkspaces.allWorkspaces.first { it.id == PredefinedWorkspaces.PROJECT_STUDIO_ID }
        val config = flatten(studio.layout)

        assertTrue(
            config.any { it.workingDirectory == "{projectPath}" },
            "the build terminal runs in the project directory",
        )
        assertTrue(
            config.any { it.filePath == "{projectPath}/PLAN.md" },
            "the plan tab points at the project's PLAN.md",
        )
    }

    @Test
    fun `a jupyter tab that no longer resolves falls back to an editor tab not a dropped one`() {
        // The restore contract for jupyter tabs (WorkspaceApplier): when the notebook
        // plugin is absent the tab is recreated as an editor tab. The template relies on
        // that contract, so pin the tab type the template declares.
        val studio = PredefinedWorkspaces.allWorkspaces.first { it.id == PredefinedWorkspaces.PROJECT_STUDIO_ID }
        val notebook = flatten(studio.layout).first { it.type == "jupyter" }
        assertEquals("{projectPath}/demo.ipynb", notebook.filePath)
        assertEquals("Demo notebook", notebook.title)
    }

    private fun flatten(node: SplitConfig): List<TabConfig> =
        when (node) {
            is SplitConfig.SinglePanel -> node.panel.tabs
            is SplitConfig.VerticalSplit -> flatten(node.left) + flatten(node.right)
            is SplitConfig.HorizontalSplit -> flatten(node.top) + flatten(node.bottom)
        }
}
