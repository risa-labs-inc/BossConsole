package ai.rever.boss.pet

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class BossPetPositionTest {
    private val primary = Rectangle(0, 0, 1200, 800)

    @Test
    fun `a disconnected monitor position returns to primary screen`() {
        assertEquals(940 to 664, petPosition(1800, 200, listOf(primary), 220, 56))
    }

    @Test
    fun `a connected screen left of primary retains its negative anchor`() {
        val left = Rectangle(-1200, 0, 1200, 800)
        assertEquals(-900 to 200, petPosition(-900, 200, listOf(primary, left), 220, 56))
    }

    @Test
    fun `an anchor near the edge keeps the complete card reachable`() {
        assertEquals(980 to 744, petPosition(1190, 790, listOf(primary), 220, 56))
    }

    @Test
    fun `first run and missing screen inventory have a reachable fallback`() {
        assertEquals(940 to 664, petPosition(null, null, listOf(primary), 220, 56))
        assertEquals(940 to 664, petPosition(null, null, emptyList(), 220, 56))
    }
}
