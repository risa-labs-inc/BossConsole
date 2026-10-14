package ai.rever.boss.app

import ai.rever.boss.components.workspaces.LAST_SESSION_ID
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.PredefinedWorkspaces
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.window.Project
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Opening a project asks one question - This Space, New Space, or New Window - and "New Space"
 * then lists only the Spaces the project can actually open in.
 */
class ProjectOpenRequestsTest {
    private fun space(
        id: String,
        projectPath: String? = null,
    ) = LayoutWorkspace(
        id = id,
        name = "Space $id",
        description = "d",
        layout = SplitConfig.SinglePanel(PanelConfig(id = "main", tabs = emptyList())),
        projectPath = projectPath,
    )

    @Test
    fun `New Space offers templates, project-free Spaces and this project's, never another project's`() {
        val template = space(PredefinedWorkspaces.CLAUDE_CODE_ID, projectPath = "/elsewhere")
        val free = space("workspace-1")
        val mine = space("workspace-2", projectPath = "/work/app/")
        val theirs = space("workspace-3", projectPath = "/work/other")
        val lastSession = space(LAST_SESSION_ID)

        assertEquals(
            listOf(template, free, mine),
            spacesForProject(listOf(template, free, mine, theirs, lastSession), "/work/app"),
            "a trailing separator is the same directory; another project's Space would swap it out",
        )
    }

    @Test
    fun `a request reaches the window it names, and only that window`() =
        runBlocking {
            val project = Project(name = "app", path = "/work/app")
            val first = CompletableDeferred<Project>()
            val w1 = launch { first.complete(ProjectOpenRequests.requestsFor("w1").first()) }
            val w2 = launch { ProjectOpenRequests.requestsFor("w2").collect { } }
            yield()

            assertTrue(ProjectOpenRequests.ask("w2", Project(name = "other", path = "/work/other")))
            assertTrue(ProjectOpenRequests.ask("w1", project))
            assertEquals(project, first.await(), "w1 skipped the request addressed to w2")

            w1.join()
            w2.cancelAndJoin()
        }

    @Test
    fun `a window that is not listening is refused, so the caller selects directly`() =
        runBlocking {
            val project = Project(name = "app", path = "/work/app")
            assertFalse(ProjectOpenRequests.ask(null, project))
            assertFalse(
                ProjectOpenRequests.ask("nobody-listening", project),
                "a dropped request must not claim success",
            )

            val listener = launch { ProjectOpenRequests.requestsFor("brief").collect { } }
            yield()
            assertTrue(ProjectOpenRequests.ask("brief", project))
            listener.cancelAndJoin()
            assertFalse(ProjectOpenRequests.ask("brief", project), "unregistered once its collector stops")
        }
}
