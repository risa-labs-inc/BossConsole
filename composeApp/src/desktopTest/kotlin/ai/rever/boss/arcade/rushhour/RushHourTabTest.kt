@file:Suppress("MaxLineLength")

package ai.rever.boss.arcade.rushhour

import ai.rever.boss.arcade.rushhour.ui.RushHourTabInfo
import ai.rever.boss.arcade.rushhour.ui.RushHourTabType
import ai.rever.boss.arcade.rushhour.ui.registerRushHourTab
import ai.rever.boss.components.dialogs.needsNoInput
import ai.rever.boss.plugin.api.NewTabContext
import ai.rever.boss.plugin.api.TabRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RushHourTabTest {
    @Test
    fun `RushHourTabType has valid metadata and opens without input`() {
        assertEquals("rushhour", RushHourTabType.typeId.typeId)
        assertEquals("Rush Hour", RushHourTabType.displayName)
        assertNotNull(RushHourTabType.newTabSpec)
        assertTrue(RushHourTabType.newTabSpec.needsNoInput(), "Rush Hour tab must declare needsNoInput for instant opening")
    }

    @Test
    fun `RushHourTabType creates valid RushHourTabInfo`() {
        val tabInfo = RushHourTabType.createTabInfo("", NewTabContext())
        assertTrue(tabInfo is RushHourTabInfo)
        assertEquals("Rush Hour", tabInfo.title)
        assertEquals(RushHourTabType.typeId, tabInfo.typeId)
        assertTrue(tabInfo.id.startsWith("rushhour-tab-"))
    }

    @Test
    fun `registerRushHourTab registers type in TabRegistry`() {
        val registry = TabRegistry()
        registry.registerRushHourTab()
        assertTrue(registry.isRegistered(RushHourTabType.typeId))
        val typeInfo = registry.getTabTypeInfo(RushHourTabType.typeId)
        assertNotNull(typeInfo)
        assertEquals("Rush Hour", typeInfo.displayName)
    }
}
