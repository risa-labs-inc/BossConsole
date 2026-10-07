package ai.rever.boss.window

import java.awt.Insets
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class MaximizedBoundsPinTest {
    @Test
    fun `usable bounds are the screen minus menu bar and Dock`() {
        // A 1512x982 MacBook screen with a 37pt menu bar and a 60pt bottom Dock.
        assertEquals(
            Rectangle(0, 37, 1512, 885),
            usableScreenBounds(Rectangle(0, 0, 1512, 982), Insets(37, 0, 60, 0)),
        )
    }

    @Test
    fun `a secondary screen keeps its own origin`() {
        // A display to the left of the main one, with a side Dock on its right edge.
        assertEquals(
            Rectangle(-2560, 25, 2496, 1415),
            usableScreenBounds(Rectangle(-2560, 0, 2560, 1440), Insets(25, 0, 0, 64)),
        )
    }
}
