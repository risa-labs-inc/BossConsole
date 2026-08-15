package ai.rever.boss.components.plugin

import ai.rever.boss.components.plugin.tab_types.PanelHostTabInfo
import ai.rever.boss.components.plugin.tab_types.PanelHostTabType
import ai.rever.boss.components.plugin.tab_types.panelIdFor
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `SplitViewOperations.openTab` picks a factory by [TabInfo.typeId] alone, so any plugin can hand
 * the host's panel-host factory a config of its own - and before `openPanelAsTab` existed, one
 * had a reason to try (BossConsole#177). Reading the panel id has to survive that.
 */
class PanelHostTabConfigTest {
    private val icon: ImageVector = Icons.Outlined.Tab

    @Test
    fun `reads the panel id from the host's own config`() {
        val panel = PanelId("codebase", 1)

        assertEquals(panel, panelIdFor(PanelHostTabInfo(panel, "Codebase", icon)))
    }

    /**
     * The point of the whole test: a look-alike returns null instead of throwing a
     * ClassCastException from inside the host, on a call a plugin made.
     */
    @Test
    fun `a plugin's look-alike config resolves to nothing rather than throwing`() {
        val impostor =
            object : TabInfo {
                override val id: String = "panel-tab:codebase"
                override val typeId: TabTypeId = PanelHostTabType.typeId
                override val title: String = "Codebase"
                override val icon: ImageVector = this@PanelHostTabConfigTest.icon
            }

        assertEquals(PanelHostTabType.typeId, impostor.typeId, "the impostor must reach the same factory")
        assertNull(panelIdFor(impostor))
    }

    @Test
    fun `an unrelated tab config resolves to nothing`() {
        val other =
            object : TabInfo {
                override val id: String = "terminal-1"
                override val typeId: TabTypeId = TabTypeId("terminal")
                override val title: String = "Terminal"
                override val icon: ImageVector = this@PanelHostTabConfigTest.icon
            }

        assertNull(panelIdFor(other))
    }
}
