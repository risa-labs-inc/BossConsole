package ai.rever.boss.components.plugin.tab_types.fluck

import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabInfo
import androidx.compose.ui.graphics.vector.ImageVector
import com.arkivanov.decompose.ComponentContext

/**
 * Desktop actual for createFluckTabComponent.
 *
 * This is a minimal stub — the full browser tab is created by the dynamic
 * fluck-browser plugin. This actual only exists to satisfy the expect/actual
 * contract. The registerFluck() call site is already disabled.
 *
 * Note: The previous DesktopFluckTabComponent overrode reload(), zoomIn(),
 * zoomOut(), actualSize() with JxBrowser-specific implementations. Those
 * operations are now handled entirely by the dynamic plugin's
 * FluckBrowserTabComponent. This stub returns the base FluckTabComponent
 * whose no-op defaults are never called in practice.
 */
actual fun createFluckTabComponent(
    config: TabInfo,
    componentContext: ComponentContext,
    onTitleUpdate: (String) -> Unit,
    onIconUpdate: (ImageVector) -> Unit,
    onTabIconUpdate: (TabIcon) -> Unit,
    onOpenInNewTab: (String) -> Unit,
    onNavigationUpdate: ((String, String) -> Unit)?,
    onFaviconCacheKeyUpdate: ((String?) -> Unit)?,
    onCloseTab: (() -> Unit)?,
): FluckTabComponent =
    FluckTabComponent(
        config = config,
        componentContext = componentContext,
        onTitleUpdate = onTitleUpdate,
        onIconUpdate = onIconUpdate,
        onTabIconUpdate = onTabIconUpdate,
        onOpenInNewTab = onOpenInNewTab,
        onNavigationUpdate = onNavigationUpdate,
        onFaviconCacheKeyUpdate = onFaviconCacheKeyUpdate,
        onCloseTab = onCloseTab,
    )
