package ai.rever.boss.components.workspaces

import ai.rever.boss.app.spaceTitleMenu
import ai.rever.boss.window.NativeTitleBarAction
import ai.rever.boss.window.findNativeTitleBarAction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpaceMenuTest {
    @Test
    fun `recovery snapshots are excluded while user namesakes stay visible`() {
        val recovery = DefaultSpace.planetBerul.copy(id = "old-recovery", name = "Recovered Space", description = "Automatically saved session")
        val namesake = recovery.copy(id = "user-space", description = "My saved work")
        val groups = spaceMenuGroups(listOf(DefaultSpace.planetBerul, recovery, namesake), listOf(recovery.id))
        assertEquals(setOf(DefaultSpace.ID, namesake.id), groups.recent.map { it.id }.toSet())
        assertEquals(emptyList(), groups.more)
    }

    @Test
    fun `legacy duplicate names remain selectable with distinct labels`() {
        val spaces = (1..3).map { index ->
            DefaultSpace.planetBerul.copy(id = "recovery-$index", name = "Recovered Space", timestamp = index.toLong())
        }
        val rows = spaceMenuGroups(spaces, listOf("recovery-3")).recent
        assertEquals(listOf("Recovered Space", "Recovered Space (2)", "Recovered Space (3)"), rows.map { it.name })
        assertEquals(spaces.map { it.id }.toSet(), rows.map { it.id }.toSet())
    }

    private fun space(index: Int) = DefaultSpace.planetBerul.copy(id = "space-$index", name = "Space $index", timestamp = index.toLong())

    @Test
    fun `recent menu contains five real spaces ordered by last use and keeps templates separate`() {
        val saved = (1..8).map(::space)
        val groups = spaceMenuGroups(saved + PredefinedWorkspaces.allWorkspaces, listOf("space-2", "space-7", "missing"))
        assertEquals(listOf("space-2", "space-7", "space-8", "space-6", "space-5"), groups.recent.map { it.id })
        assertEquals(listOf("space-4", "space-3", "space-1"), groups.more.map { it.id })
        assertEquals(PredefinedWorkspaces.allIds, groups.templates.map { it.id }.toSet())
    }

    @Test
    fun `title menu creation and nested More actions are invokable`() {
        var opened: String? = null
        var creates = 0
        val menu = spaceTitleMenu((1..7).map(::space), emptyList(), "space-7", { opened = it.id }, { creates++ })
        assertEquals(5, menu.count { it.id.startsWith("space:") })
        val more = assertNotNull(menu.single { it.id == "more-spaces" }.menu)
        assertEquals(2, more.size)
        assertNotNull(findNativeTitleBarAction(menu, more.first().id)).onClick()
        assertEquals("space-2", opened)
        assertNotNull(findNativeTitleBarAction(menu, "create-space")).onClick()
        assertEquals(1, creates)
        assertTrue(menu.single { it.id == "space:space-7" }.active)
    }

    @Test
    fun `submenu callbacks respect disabled and local-only ancestors`() {
        val child = NativeTitleBarAction("child", "Child") {}
        val disabled = NativeTitleBarAction("parent", "Parent", enabled = false, menu = listOf(child)) {}
        assertNull(findNativeTitleBarAction(listOf(disabled), "child"))
        val local = disabled.copy(enabled = true, localOnly = true)
        assertNull(findNativeTitleBarAction(listOf(local), "child", allowLocalOnly = false))
        assertNotNull(findNativeTitleBarAction(listOf(local), "child"))
    }

    @Test
    fun `fresh store exposes only Planet Berul and new spaces persist independently`() = runBlocking {
        val dir = Files.createTempDirectory("boss-space-menu").toFile()
        try {
            val files = WorkspaceFileManager(dir.absolutePath)
            val manager = WorkspaceManager(files)
            withTimeout(5000) { manager.workspaces.first { it.isNotEmpty() } }
            val visible = withTimeout(5000) { manager.visibleWorkspaces.first { it.isNotEmpty() } }
            assertEquals(listOf("Planet Berul"), visible.map { it.name })
            assertFalse(DefaultSpace.planetBerul.requiresProject())
            val created = assertNotNull(manager.createSpace("Research"))
            assertEquals("Research", created.name)
            val stored = assertNotNull(files.loadWorkspace(WorkspaceFileManagerCommon.fileNameForId(created.id)))
            assertEquals(created, stored)
            assertEquals("Research 2", assertNotNull(manager.createSpace("Research")).name)
            assertEquals(listOf("Planet Berul", "Research", "Research 2"), manager.workspaces.value
                .filterNot { it.id in PredefinedWorkspaces.allIds }.map { it.name })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `recent space order survives settings serialization`() {
        val settings = WorkspaceSettings(recentSpaceIds = listOf("space-3", "space-1"))
        val written = Json.encodeToString(WorkspaceSettings.serializer(), settings)
        assertEquals(settings.recentSpaceIds, Json.decodeFromString<WorkspaceSettings>(written).recentSpaceIds)
    }
}
