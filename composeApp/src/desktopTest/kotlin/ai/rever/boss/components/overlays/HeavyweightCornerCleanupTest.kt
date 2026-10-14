package ai.rever.boss.components.overlays

import java.awt.Component
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentListener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeavyweightCornerCleanupTest {
    @Test
    fun `a failed parent removal still detaches the pane listener`() {
        var parentRemovals = 0
        val parent =
            object : Component() {
                override fun removeComponentListener(listener: ComponentListener?) {
                    parentRemovals++
                    error("parent listener removal failed")
                }
            }
        val pane = object : Component() {}
        val listener = object : ComponentAdapter() {}
        pane.addComponentListener(listener)

        CornerBoundsListenerCleanup.remove(parent, pane, listener)

        assertEquals(1, parentRemovals)
        assertTrue(pane.componentListeners.isEmpty())
    }
}
