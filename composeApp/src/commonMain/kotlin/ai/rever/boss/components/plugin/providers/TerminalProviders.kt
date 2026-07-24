package ai.rever.boss.components.plugin.providers

import ai.rever.boss.components.events.PanelEventBus
import ai.rever.boss.window.MenuActionsHandler

class PanelEventProviderImpl : ai.rever.boss.plugin.api.PanelEventProvider {
    override suspend fun closePanel(
        panelId: ai.rever.boss.plugin.api.PanelId,
        windowId: String,
    ) {
        PanelEventBus.closePanel(panelId, sourceWindowId = windowId)
    }

    override suspend fun openPanel(
        panelId: ai.rever.boss.plugin.api.PanelId,
        windowId: String,
    ) {
        PanelEventBus.openPanel(panelId, sourceWindowId = windowId)
    }
}

class SettingsProviderImpl : ai.rever.boss.plugin.api.SettingsProvider {
    override fun openSettings(
        windowId: String,
        section: String,
    ) {
        MenuActionsHandler.triggerOpenSettings(windowId, section)
    }
}
