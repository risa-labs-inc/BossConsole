package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.window.Project
import ai.rever.boss.window.WindowProjectState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the nullability contract two plugin-facing APIs declare and did not keep.
 *
 * `WindowProjectState.selectedProject` never emits null. Before a project is chosen
 * it holds a sentinel named "No Project" whose path is the empty string, which suits
 * the UI and traps everything else: `PluginContext.projectPath` and
 * `WindowProjectStateProvider.getSelectedProjectPath` are both declared `String?`,
 * and the former's KDoc promises null when nothing is selected.
 *
 * The failure is quiet in the worst way. A plugin writes `context.projectPath ?: x`,
 * the Elvis never fires because the value is not null, and the empty string it got
 * instead resolves against the filesystem root rather than throwing. Reported from
 * plugin development in BossConsole#332.
 */
class SelectedProjectPathTest {
    @Test
    fun `no project selected reads as null, not as an empty path`() {
        val state = WindowProjectState(windowId = "w1")
        // The state the trap depends on: something is always there to render.
        assertEquals("No Project", state.selectedProject.value.name)
        assertEquals("", state.selectedProject.value.path)
        assertNull(state.selectedProjectPath)
    }

    @Test
    fun `a selected project reads as its path`() {
        val state = WindowProjectState(windowId = "w1")
        state.selectProject(Project(name = "demo", path = "/home/dev/demo"))
        assertEquals("/home/dev/demo", state.selectedProjectPath)
    }

    @Test
    fun `a project carrying a blank path is treated as no project`() {
        // selectProject takes whatever it is given, so the sentinel is not the only
        // way a blank path arrives. A workspace restored from a config that lost its
        // path is the realistic case, and it must not resolve to the filesystem root.
        val state = WindowProjectState(windowId = "w1")
        state.selectProject(Project(name = "broken", path = "   "))
        assertNull(state.selectedProjectPath)
    }

    @Test
    fun `the provider answers null before a project is selected`() {
        val provider = WindowProjectStateProviderImpl(WindowProjectState(windowId = "w1"))
        assertNull(provider.getSelectedProjectPath())
    }

    @Test
    fun `the provider answers the path once one is selected`() {
        val state = WindowProjectState(windowId = "w1")
        val provider = WindowProjectStateProviderImpl(state)
        state.selectProject(Project(name = "demo", path = "/srv/demo"))
        assertEquals("/srv/demo", provider.getSelectedProjectPath())
    }

    @Test
    fun `a window with no project state at all still answers null`() {
        // The provider takes a nullable state, and a window without one must give the
        // same answer as a window that has one with nothing selected. Two different
        // shapes of "no project" reaching a plugin as different values is the bug in
        // miniature.
        assertNull(WindowProjectStateProviderImpl(null).getSelectedProjectPath())
    }
}
