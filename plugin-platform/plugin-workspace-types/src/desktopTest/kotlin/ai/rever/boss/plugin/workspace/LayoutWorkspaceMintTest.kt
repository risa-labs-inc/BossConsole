package ai.rever.boss.plugin.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `LayoutWorkspace.generateId` is the seam every Space identity flows from: the record's
 * file name, the registry row and the session set all key on it, so one ID means one
 * record, and the second Space to mint it replaces the first instead of standing beside it.
 *
 * It used to be a bare clock read, so any two mints inside one clock millisecond got the
 * same ID. The guard under test hands every mint a strictly increasing millisecond,
 * bumping past the previous mint when the clock has not ticked, so rapid back-to-back
 * mints cannot collide wherever the millisecond boundary falls.
 *
 * The `workspace-<digits>` shape is load-bearing too: adopted ids deliberately end in
 * `-saved` precisely so they can never be mistaken for one this function minted, and the
 * picker's built-in check is a set, not a prefix. A guard that changed the shape would
 * break both, so it is pinned here.
 */
class LayoutWorkspaceMintTest {
    @Test
    fun `back-to-back mints never share an id`() {
        val ids = (1..50).map { LayoutWorkspace.generateId() }

        assertEquals(50, ids.toSet().size, "every mint is a new Space, not a replacement")
    }

    @Test
    fun `a minted id stays workspace digits so an adopted id remains recognisable`() {
        val id = LayoutWorkspace.generateId()

        assertTrue(
            id.matches(Regex("workspace-\\d+")),
            "the shape an adopted -saved id depends on for contrast must not change",
        )
    }
}
