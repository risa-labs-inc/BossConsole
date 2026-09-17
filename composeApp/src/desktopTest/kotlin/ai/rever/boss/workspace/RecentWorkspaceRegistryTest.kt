package ai.rever.boss.workspace

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecentWorkspaceRegistryTest {
    @BeforeTest
    fun setUp() {
        RecentWorkspaceRegistry.clear()
    }

    @Test
    fun `addWorkspace stores workspace path in MRU order`() {
        RecentWorkspaceRegistry.addWorkspace("/Users/boss/project-a", "Project A")

        val workspaces = RecentWorkspaceRegistry.getRecentWorkspaces()
        assertEquals(1, workspaces.size)
        assertEquals("/Users/boss/project-a", workspaces[0].path)
        assertEquals("Project A", workspaces[0].name)
    }

    @Test
    fun `addWorkspace promotes existing workspace to top`() {
        RecentWorkspaceRegistry.addWorkspace("/Users/boss/project-1", "P1")
        RecentWorkspaceRegistry.addWorkspace("/Users/boss/project-2", "P2")
        RecentWorkspaceRegistry.addWorkspace("/Users/boss/project-1", "P1")

        val workspaces = RecentWorkspaceRegistry.getRecentWorkspaces()
        assertEquals(2, workspaces.size)
        assertEquals("/Users/boss/project-1", workspaces[0].path)
        assertEquals("/Users/boss/project-2", workspaces[1].path)
    }

    @Test
    fun `togglePin pins workspace and prevents eviction`() {
        RecentWorkspaceRegistry.addWorkspace("/Users/boss/pinned-project", "Pinned")
        val isPinned = RecentWorkspaceRegistry.togglePin("/Users/boss/pinned-project")

        assertTrue(isPinned)
        val pinned = RecentWorkspaceRegistry.getPinnedWorkspaces()
        assertEquals(1, pinned.size)
        assertEquals("Pinned", pinned[0].name)
    }

    @Test
    fun `removeWorkspace deletes workspace from registry`() {
        RecentWorkspaceRegistry.addWorkspace("/Users/boss/temp-project", "Temp")
        RecentWorkspaceRegistry.removeWorkspace("/Users/boss/temp-project")

        val remaining = RecentWorkspaceRegistry.getRecentWorkspaces()
        assertEquals(0, remaining.size)
    }

    @Test
    fun `capacity limit retains at most 10 workspaces`() {
        for (i in 1..15) {
            RecentWorkspaceRegistry.addWorkspace("/Users/boss/project-$i", "Project $i")
        }

        val workspaces = RecentWorkspaceRegistry.getRecentWorkspaces()
        assertEquals(10, workspaces.size)
        assertEquals("/Users/boss/project-15", workspaces[0].path)
        assertEquals("/Users/boss/project-6", workspaces[9].path)
    }
}
