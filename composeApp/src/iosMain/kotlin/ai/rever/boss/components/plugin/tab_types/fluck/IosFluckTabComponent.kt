package ai.rever.boss.components.plugin.tab_types.fluck

import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabIcon
import androidx.compose.ui.graphics.vector.ImageVector
import com.arkivanov.decompose.ComponentContext

actual fun createFluckTabComponent(
    config: TabInfo,
    componentContext: ComponentContext,
    onTitleUpdate: (String) -> Unit,
    onIconUpdate: (ImageVector) -> Unit,
    onTabIconUpdate: (TabIcon) -> Unit,
    onOpenInNewTab: (String) -> Unit,
    onNavigationUpdate: ((String, String) -> Unit)?
): FluckTabComponent {
    return FluckTabComponent(
        config = config,
        componentContext = componentContext,
        onTitleUpdate = onTitleUpdate,
        onIconUpdate = onIconUpdate,
        onTabIconUpdate = onTabIconUpdate,
        onOpenInNewTab = onOpenInNewTab,
        onNavigationUpdate = onNavigationUpdate
    )
}
