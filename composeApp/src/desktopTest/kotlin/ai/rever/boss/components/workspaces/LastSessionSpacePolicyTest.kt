package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.workspace.SplitConfig
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LastSessionSpacePolicyTest {
    private fun space(id: String) =
        LayoutWorkspace(
            id = id,
            name = "Last Session",
            description = "Recovery test",
            layout = SplitConfig.SinglePanel(PanelConfig("p", listOf(TabConfig(type = "browser", title = "Work")))),
            projectPath = "/tmp/project",
        )

    @Test
    fun `repeated legacy restores reuse the newest recovery identity`() {
        val legacy = space(LAST_SESSION_ID)
        val first = reusableSessionSpaceIdentity(legacy, false, emptyList())
        val again = reusableSessionSpaceIdentity(legacy.copy(timestamp = 20), false, listOf(first))
        assertEquals(first.id, again.id)
        val older = first.copy(id = "old", timestamp = 1)
        val newer = first.copy(id = "new", timestamp = 2)
        assertEquals("new", reusableSessionSpaceIdentity(legacy, false, listOf(older, newer)).id)
        assertSame(legacy, reusableSessionSpaceIdentity(legacy, true, listOf(newer)))
    }

    @Test
    fun `fresh and existing settings default to disabled while explicit opt in persists`() {
        assertFalse(WorkspaceSettings().enableLastSessionSpace)
        assertFalse(Json.decodeFromString<WorkspaceSettings>("{}").enableLastSessionSpace)
        val settings = WorkspaceSettings(enableLastSessionSpace = true)
        assertTrue(Json.decodeFromString<WorkspaceSettings>(Json.encodeToString(settings)).enableLastSessionSpace)
    }

    @Test
    fun `only the reserved slot is hidden including when a user space shares its name`() {
        val spaces = listOf(space(LAST_SESSION_ID), space("mine"))
        assertEquals(listOf("mine"), visibleSessionSpaces(spaces, false).map { it.id })
        assertEquals(spaces, visibleSessionSpaces(spaces, true))
    }

    @Test
    fun `legacy recovery keeps tabs and project without restoring the disabled slot identity`() {
        val legacy = space(LAST_SESSION_ID)
        val adopted = sessionSpaceIdentity(legacy, false) { "recovered" }
        assertEquals("recovered", adopted.id)
        assertEquals("Recovered Space", adopted.name)
        assertEquals(legacy.layout, adopted.layout)
        assertEquals(legacy.projectPath, adopted.projectPath)
        assertSame(legacy, sessionSpaceIdentity(legacy, true))
    }

    @Test
    fun `a known space keeps its identity regardless of the setting`() {
        val named = space("mine")
        assertSame(named, sessionSpaceIdentity(named, false))
        assertSame(named, sessionSpaceIdentity(named, true))
    }
}
