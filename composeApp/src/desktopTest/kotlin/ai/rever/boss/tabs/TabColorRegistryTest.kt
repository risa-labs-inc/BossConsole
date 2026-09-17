package ai.rever.boss.tabs

import androidx.compose.ui.graphics.Color
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TabColorRegistryTest {
    @BeforeTest
    fun setUp() {
        TabColorRegistry.clear()
    }

    @Test
    fun `setTag stores color tag for a tab ID`() {
        TabColorRegistry.setTag("tab-101", TabCategory.WORK)

        val tag = TabColorRegistry.getTag("tab-101")
        assertNotNull(tag)
        assertEquals(TabCategory.WORK, tag.category)
        assertEquals("Work", tag.displayLabel)
    }

    @Test
    fun `setTag supports custom labels`() {
        TabColorRegistry.setTag("tab-102", TabCategory.DEV, customLabel = "Backend API")

        val tag = TabColorRegistry.getTag("tab-102")
        assertNotNull(tag)
        assertEquals("Backend API", tag.displayLabel)
    }

    @Test
    fun `removeTag removes assigned color tag`() {
        TabColorRegistry.setTag("tab-103", TabCategory.DOCS)
        TabColorRegistry.removeTag("tab-103")

        assertNull(TabColorRegistry.getTag("tab-103"))
    }

    @Test
    fun `parseColorHex converts valid 6-character hex to Color`() {
        val color = parseColorHex("#10B981")
        assertEquals(Color(0xFF10B981), color)
    }

    @Test
    fun `clear resets all registered color tags`() {
        TabColorRegistry.setTag("t1", TabCategory.DEV)
        TabColorRegistry.setTag("t2", TabCategory.WORK)
        TabColorRegistry.clear()

        assertNull(TabColorRegistry.getTag("t1"))
        assertNull(TabColorRegistry.getTag("t2"))
    }
}
