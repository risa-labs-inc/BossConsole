package ai.rever.boss.performance

import ai.rever.boss.components.events.PanelEventBus
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.utils.WindowFocusManager
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Desktop implementation of PerformanceState.
 * Uses PerformanceMonitor and PerformanceSettingsManager to provide state.
 */
actual object PerformanceState {
    private val logger = BossLogger.forComponent("PerformanceState")
    private val scope = CoroutineScope(Dispatchers.Main)

    @Composable
    actual fun currentSnapshot(): PerformanceSnapshot? {
        val snapshot by PerformanceMonitor.currentSnapshot.collectAsState()
        return snapshot
    }

    @Composable
    actual fun currentHealth(): PerformanceHealth {
        val health by PerformanceMonitor.currentHealth.collectAsState()
        return health
    }

    @Composable
    actual fun shouldShowIndicator(): Boolean {
        val settings by PerformanceSettingsManager.currentSettings.collectAsState()
        return settings.showIndicator && settings.enabled
    }

    actual fun openPerformancePanel() {
        scope.launch {
            val focusedWindowId = WindowFocusManager.focusedWindowFlow.value
            if (focusedWindowId == null) {
                logger.debug(LogCategory.UI, "No window focused, cannot open performance panel")
                return@launch
            }
            PanelEventBus.openPanel(PanelId("performance", 15), sourceWindowId = focusedWindowId)
        }
    }

    actual fun togglePerformancePanel() {
        scope.launch {
            val focusedWindowId = WindowFocusManager.focusedWindowFlow.value
            if (focusedWindowId == null) {
                logger.debug(LogCategory.UI, "No window focused, cannot toggle performance panel")
                return@launch
            }
            PanelEventBus.togglePanel(PanelId("performance", 15), sourceWindowId = focusedWindowId)
        }
    }

    actual fun registerResourceProviders(
        browserTabs: () -> Int,
        terminals: () -> Int,
        editorTabs: () -> Int,
        panels: () -> Int,
        windows: () -> Int,
    ) {
        PerformanceMonitor.registerResourceProviders(
            browserTabs = browserTabs,
            terminals = terminals,
            editorTabs = editorTabs,
            panels = panels,
            windows = windows,
        )
    }

    actual fun registerDetailedResourceProviders(
        browserTabs: () -> List<BrowserTabInfo>,
        terminals: () -> List<TerminalInfo>,
        editorTabs: () -> List<EditorTabResourceInfo>,
    ) {
        PerformanceMonitor.registerDetailedResourceProviders(
            browserTabs = browserTabs,
            terminals = terminals,
            editorTabs = editorTabs,
        )
    }

    actual fun clearResourceProviders() {
        PerformanceMonitor.clearResourceProviders()
    }
}
