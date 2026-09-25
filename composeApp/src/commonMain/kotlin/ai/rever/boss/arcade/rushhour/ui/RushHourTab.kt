package ai.rever.boss.arcade.rushhour.ui

import ai.rever.boss.plugin.api.NewTabContext
import ai.rever.boss.plugin.api.NewTabSpec
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.arkivanov.decompose.ComponentContext
import java.util.UUID

/**
 * Tab type metadata for Boss Arcade: Rush Hour Gym.
 * Declares no input so that clicking it in the New Tab dialog or Home Screen
 * immediately opens the game tab.
 */
object RushHourTabType : TabTypeInfo {
    override val typeId: TabTypeId = TabTypeId("rushhour")
    override val displayName: String = "Rush Hour"
    override val icon: ImageVector = Icons.Outlined.Extension
    override val newTabSpec: NewTabSpec =
        NewTabSpec(
            order = 50,
            inputLabel = "",
            inputPlaceholder = "",
            inputOptional = true,
            confirmLabel = "Play",
        )

    override fun createTabInfo(
        input: String,
        context: NewTabContext,
    ): TabInfo = RushHourTabInfo()
}

/**
 * Tab state representation for an open Rush Hour game tab.
 */
data class RushHourTabInfo(
    override val id: String = "rushhour-tab-${UUID.randomUUID()}",
    override val title: String = "Rush Hour",
    override val icon: ImageVector = Icons.Outlined.Extension,
) : TabInfo {
    override val typeId: TabTypeId = RushHourTabType.typeId
}

/**
 * Tab component that hosts the Compose [RushHourScreen].
 */
class RushHourTabComponent(
    override val config: TabInfo,
    componentContext: ComponentContext,
) : TabComponentWithUI,
    ComponentContext by componentContext {
    override val tabTypeInfo: TabTypeInfo = RushHourTabType

    @Composable
    override fun Content() {
        RushHourScreen()
    }
}

/**
 * Registers the Rush Hour tab type on [TabRegistry].
 */
fun TabRegistry.registerRushHourTab() {
    if (isRegistered(RushHourTabType.typeId)) return
    registerTabType(RushHourTabType) { config, ctx ->
        RushHourTabComponent(config, ctx)
    }
}
