package ai.rever.boss.app

import ai.rever.boss.components.events.PanelEventBus
import ai.rever.boss.components.plugin.PanelIds
import ai.rever.boss.components.plugin.PluginRecoveryContext
import ai.rever.boss.components.plugin.resolveRegisteredPanelId
import ai.rever.boss.plugin.api.PluginLoaderDelegate
import ai.rever.boss.utils.WindowFocusManager
import kotlinx.coroutines.launch

/** Provided only to host recovery and Settings dialogs, not plugin content. */
internal fun pluginRecoveryContextFor(state: BossAppState): PluginRecoveryContext =
    PluginRecoveryContext(
        manager = state.currentDefaultPlugin?.dynamicPluginManager,
        delegate = state.currentDefaultPlugin?.getPluginAPI(PluginLoaderDelegate::class.java),
        openToolbox = {
            val panel = state.panelRegistry.resolveRegisteredPanelId(PanelIds.PLUGIN_MANAGER)
            if (panel == null || !WindowFocusManager.isWindowOpen(state.windowId)) {
                false
            } else {
                state.coroutineScope.launch {
                    PanelEventBus.openPanel(panel, state.windowId)
                    WindowFocusManager.focusWindow(state.windowId)
                }
                true
            }
        },
    )
