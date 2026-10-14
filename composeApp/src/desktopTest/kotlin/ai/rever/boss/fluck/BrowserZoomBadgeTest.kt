package ai.rever.boss.fluck

import ai.rever.boss.components.bars.horizontal.formatZoomPercentage
import ai.rever.boss.components.bars.horizontal.isNonDefaultZoom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserZoomBadgeTest {
    @Test
    fun `formatZoomPercentage formats 1_0 to 100 percent`() {
        assertEquals("100%", formatZoomPercentage(1.0))
    }

    @Test
    fun `formatZoomPercentage formats 1_25 to 125 percent`() {
        assertEquals("125%", formatZoomPercentage(1.25))
    }

    @Test
    fun `formatZoomPercentage formats 0_8 to 80 percent`() {
        assertEquals("80%", formatZoomPercentage(0.8))
    }

    @Test
    fun `formatZoomPercentage formats 2_0 to 200 percent`() {
        assertEquals("200%", formatZoomPercentage(2.0))
    }

    @Test
    fun `isNonDefaultZoom returns false for standard 1_0 zoom`() {
        assertFalse(isNonDefaultZoom(1.0))
    }

    @Test
    fun `isNonDefaultZoom returns false for tiny rounding difference`() {
        assertFalse(isNonDefaultZoom(1.0005))
    }

    @Test
    fun `isNonDefaultZoom returns true for zoomed in level`() {
        assertTrue(isNonDefaultZoom(1.25))
    }

    @Test
    fun `isNonDefaultZoom returns true for zoomed out level`() {
        assertTrue(isNonDefaultZoom(0.75))
    }
}
