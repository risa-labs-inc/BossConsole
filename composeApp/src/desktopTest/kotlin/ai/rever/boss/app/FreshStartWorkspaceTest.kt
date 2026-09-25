package ai.rever.boss.app

import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.workspaces.PredefinedWorkspaces
import ai.rever.boss.components.workspaces.requiresProject
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.dashboard.WorkspacePlaceholders
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import ai.rever.boss.plugin.workspace.TabConfig
import ai.rever.boss.window.WindowProjectState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rule that decides whether a window which restored nothing opens on the default
 * workspace.
 *
 * Review of the first version of this PR found the gap this closes: the default workspace
 * was applied only from `LaunchedEffect(selectedProject.path)`, and a fresh profile has no
 * project, so a new Windows install never reached `workspace-browser` at all - the very
 * case the default exists for.
 *
 * The predicate is deliberately about the *workspace*, not the platform. Applying a
 * project-shaped workspace with no project is worse than applying nothing: `{projectPath}`
 * falls back to `~/BossProjects`, so the Claude Code default would open a terminal running
 * `claude --dangerously-skip-permissions` in an empty projects folder on first launch.
 */
class FreshStartWorkspaceTest {
    private val browserOnly =
        PredefinedWorkspaces.allWorkspaces.single { it.id == PredefinedWorkspaces.BROWSER_ONLY_ID }
    private val claudeCode =
        PredefinedWorkspaces.allWorkspaces.single { it.id == PredefinedWorkspaces.CLAUDE_CODE_ID }

    @Test
    fun `a refused fresh-start default does not claim an unapplied Space`() =
        runBlocking {
            val before = workspaceManager.currentWorkspace.value
            val splitViewState = SplitViewState(TabRegistry(), windowId = "fresh-start-refusal")
            val projectState = WindowProjectState(windowId = "fresh-start-refusal")
            val unavailable =
                browserOnly.copy(
                    id = "unavailable-default",
                    layout = SinglePanel(PanelConfig("main", listOf(TabConfig(type = "unknown", title = "Missing")))),
                )

            assertNull(applyDefaultWorkspaceOnFreshStart(splitViewState, projectState, unavailable))
            assertNull(splitViewState.currentWorkspaceId)
            assertEquals(before, workspaceManager.currentWorkspace.value)
        }

    @Test
    fun `a fresh window opens on a workspace that needs no project`() {
        assertTrue(shouldApplyOnFreshStart(browserOnly, hasProject = false))
    }

    @Test
    fun `a project-shaped workspace is never applied without a project`() {
        assertFalse(
            shouldApplyOnFreshStart(claudeCode, hasProject = false),
            "would run the Claude CLI in a directory the user never chose",
        )
    }

    /** With a project the reactive apply owns this, so applying here would do it twice. */
    @Test
    fun `nothing is applied when a project is already selected`() {
        assertFalse(shouldApplyOnFreshStart(browserOnly, hasProject = true))
        assertFalse(shouldApplyOnFreshStart(claudeCode, hasProject = true))
    }

    /** `getDefaultWorkspace()` returns null for "none", which means "do not auto-apply". */
    @Test
    fun `auto-apply disabled applies nothing`() {
        assertFalse(shouldApplyOnFreshStart(null, hasProject = false))
        assertFalse(shouldApplyOnFreshStart(null, hasProject = true))
    }

    /** Each project placeholder is enough on its own to hold a workspace back. */
    @Test
    fun `every project placeholder marks a workspace as needing one`() {
        val placeholders =
            listOf("{projectPath}", "{gitRemoteUrl}", "{currentFile}", "{claudeContinueFlag}")
        for (placeholder in placeholders) {
            val inUrl = browserOnly.copy(layout = singleBrowserLayout(url = placeholder))
            assertTrue(inUrl.requiresProject(), placeholder)
            assertFalse(shouldApplyOnFreshStart(inUrl, hasProject = false), placeholder)
        }
    }

    /**
     * The placeholder list mirrors `WorkspacePlaceholders.processPlaceholders`, and nothing
     * links the two. A fifth placeholder added there and missed here would not mark a
     * workspace as project-requiring, and the failure mode is the one the KDoc warns about:
     * a CLI running somewhere the user never chose.
     */
    @Test
    fun `every placeholder the substitution handles is treated as project-requiring`() {
        val handled = listOf("{projectPath}", "{gitRemoteUrl}", "{currentFile}", "{claudeContinueFlag}")
        for (placeholder in handled) {
            val substituted =
                WorkspacePlaceholders.processPlaceholders(placeholder, "/tmp/project", currentFile = "/tmp/f.kt")
            assertFalse(
                substituted.contains(placeholder),
                "$placeholder is substituted from the project, so requiresProject must know about it",
            )
            assertTrue(
                browserOnly.copy(layout = singleBrowserLayout(url = placeholder)).requiresProject(),
                placeholder,
            )
        }
    }

    /** A placeholder embedded in a longer string still counts. */
    @Test
    fun `an embedded placeholder counts`() {
        val embedded = browserOnly.copy(layout = singleBrowserLayout(url = "https://example.com/{projectPath}/tree"))
        assertTrue(embedded.requiresProject())
    }

    private fun singleBrowserLayout(url: String) =
        SinglePanel(
            PanelConfig(
                id = "panel-test",
                tabs = listOf(TabConfig(type = "browser", title = "t", url = url)),
            ),
        )
}
