package ai.rever.boss.components.workspaces

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What happens to the workspace you are leaving when you switch.
 *
 * The value is a string in a settings file, so the interesting cases are the ones a file can
 * hold that this build did not write.
 */
class WorkspaceSwitchSettingTest {
    private fun settings(value: String) = WorkspaceSettings(onWorkspaceSwitch = value)

    @Test
    fun `a fresh install asks`() {
        assertEquals(WorkspaceSwitchAction.ASK, WorkspaceSettings().resolveOnWorkspaceSwitch())
    }

    @Test
    fun `each written value resolves to itself`() {
        assertEquals(WorkspaceSwitchAction.ASK, settings(WorkspaceSettings.SWITCH_ASK).resolveOnWorkspaceSwitch())
        assertEquals(WorkspaceSwitchAction.KEEP, settings(WorkspaceSettings.SWITCH_KEEP).resolveOnWorkspaceSwitch())
        assertEquals(WorkspaceSwitchAction.CLOSE, settings(WorkspaceSettings.SWITCH_CLOSE).resolveOnWorkspaceSwitch())
    }

    @Test
    fun `an unrecognised value asks rather than deciding`() {
        // A settings file written by a build this one does not know about must not decide, on its
        // own, to throw away someone's tabs. Falling back to ASK puts it in front of the user.
        assertEquals(WorkspaceSwitchAction.ASK, settings("keep-forever").resolveOnWorkspaceSwitch())
        assertEquals(WorkspaceSwitchAction.ASK, settings("").resolveOnWorkspaceSwitch())
    }

    @Test
    fun `the switch setting is independent of the project-selection one`() {
        // Two separate questions that both have an "ask" answer, and sharing a constant between
        // them would make one silently follow the other.
        val s = WorkspaceSettings(defaultWorkspaceId = WorkspaceSettings.NO_WORKSPACE_ID)
        assertEquals(WorkspaceSwitchAction.ASK, s.resolveOnWorkspaceSwitch())
        assertEquals(ProjectSelectionWorkspace.None, s.resolveOnProjectSelection())
    }

    @Test
    fun `migration leaves the switch setting alone`() {
        // The migrations exist for defaultWorkspaceId. A file being version-stamped must not
        // quietly re-answer a different question.
        val old = WorkspaceSettings(onWorkspaceSwitch = WorkspaceSettings.SWITCH_CLOSE, settingsVersion = 0)
        val migrated = WorkspaceSettingsMigrations.migrate(old, isWindows = false)

        assertEquals(WorkspaceSettings.SWITCH_CLOSE, migrated?.onWorkspaceSwitch)
    }
}
