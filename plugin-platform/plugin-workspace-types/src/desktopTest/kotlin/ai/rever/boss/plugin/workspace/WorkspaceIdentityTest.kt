package ai.rever.boss.plugin.workspace

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkspaceIdentityTest {
    @Test
    fun `new workspace identity includes random entropy independent of clock resolution`() {
        // Parsing/version checks fail deterministically for the old millisecond-only ids.
        val id = LayoutWorkspace.generateId()
        assertTrue(id.startsWith("workspace-"))
        val uuid = UUID.fromString(id.removePrefix("workspace-"))
        assertEquals(4, uuid.version())
        assertEquals(2, uuid.variant())
    }

    @Test
    fun `rapid allocations retain separate identities`() {
        val ids = List(10_000) { LayoutWorkspace.generateId() }
        assertEquals(ids.size, ids.toSet().size)
    }
}
