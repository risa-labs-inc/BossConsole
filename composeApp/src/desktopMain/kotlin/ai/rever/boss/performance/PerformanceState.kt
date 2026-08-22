package ai.rever.boss.performance

import ai.rever.boss.components.events.PanelEventBus
import ai.rever.boss.components.plugin.DynamicPluginManager
import ai.rever.boss.components.plugin.MissingDependencyInstaller
import ai.rever.boss.components.plugin.MissingDependencyPrompt
import ai.rever.boss.components.plugin.MissingPluginDependency
import ai.rever.boss.components.plugin.PluginDependencyEventBus
import ai.rever.boss.plugin.MissingDependencyReporter
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

/** The plugin that draws the Performance panel the status-bar indicator opens. */
internal const val PERFORMANCE_PLUGIN_ID = "ai.rever.boss.plugin.dynamic.performance"

/**
 * Stands in for a dependent plugin id on a prompt the host itself raises.
 *
 * Only reaches logs: the decline key for an optional dependency is the *missing* id, so this
 * value never affects which prompts are suppressed.
 */
private const val HOST_ID = "ai.rever.boss.host"

/**
 * Desktop implementation of PerformanceState.
 * Uses PerformanceMonitor and PerformanceSettingsManager to provide state.
 */
actual object PerformanceState {
    private val logger = BossLogger.forComponent("PerformanceState")
    private val scope = CoroutineScope(Dispatchers.Main)

    /**
     * Order 15, matching what the plugin declares in its own `PerformanceInfo`.
     *
     * Not `PanelIds.PERFORMANCE`, which says order 2. The panel registry keys on the whole
     * `PanelId` including its order, so the two are different keys and the wrong one silently
     * matches nothing.
     */
    private val PERFORMANCE_PANEL = PanelId("performance", 15)

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

    actual fun setIndicatorMounted(mounted: Boolean) {
        FootprintDisplay.setMounted(mounted)
    }

    actual fun openPerformancePanel() {
        scope.launch {
            val focusedWindowId = WindowFocusManager.focusedWindowFlow.value
            if (focusedWindowId == null) {
                logger.debug(LogCategory.UI, "No window focused, cannot open performance panel")
                return@launch
            }
            if (offerToInstallPanel()) return@launch
            PanelEventBus.openPanel(PERFORMANCE_PANEL, sourceWindowId = focusedWindowId)
        }
    }

    actual fun togglePerformancePanel() {
        scope.launch {
            val focusedWindowId = WindowFocusManager.focusedWindowFlow.value
            if (focusedWindowId == null) {
                logger.debug(LogCategory.UI, "No window focused, cannot toggle performance panel")
                return@launch
            }
            if (offerToInstallPanel()) return@launch
            PanelEventBus.togglePanel(PERFORMANCE_PANEL, sourceWindowId = focusedWindowId)
        }
    }

    /**
     * Offer to install the panel's plugin when it is absent, returning whether it did.
     *
     * The status-bar indicator is drawn by the host, but the panel it opens is a plugin. Without
     * this, clicking the indicator on an install that lacks that plugin emitted a panel event
     * nothing was listening for: no panel, no dialog, no log line. A control that does nothing
     * and says nothing is indistinguishable from a broken one.
     *
     * Reuses the install-time dependency prompt rather than adding a second dialog, so the offer
     * comes with a working Install button. Both entry points are guarded, not just the toggle -
     * the View menu reaches [openPerformancePanel] and would otherwise keep the silent failure.
     *
     * Fails **open**: with no active manager there is nothing to ask and nothing to install, so
     * the panel event is emitted as before. A wiring gap must not be able to make the indicator
     * unclickable on an install where the plugin is present.
     */
    private fun offerToInstallPanel(): Boolean {
        val prompt =
            DynamicPluginManager
                .anyActiveManager()
                ?.let { MissingDependencyReporter.installerFor(it) }
                ?.let { performancePanelPrompt(it) }
                ?: return false

        logger.info(LogCategory.UI, "Performance panel requested but its plugin is not installed")
        PluginDependencyEventBus.report(prompt)
        return true
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

/**
 * The prompt to raise for a missing performance panel, or null when it is already there.
 *
 * Pure apart from the installer it is handed, so the wording and the "already installed"
 * short-circuit are testable without a plugin manager behind them.
 *
 * Asks the installer rather than reading plugin states directly. That is the one definition
 * of "installed" the Install button also uses - AGENTS.md records this prompt breaking once
 * already when two halves of it disagreed, and a plugin whose jar was rejected as binary
 * incompatible still leaves a registered entry behind.
 *
 * Declared `optional`, which is both true and what makes the copy read correctly: BOSS does
 * work without this plugin, it is one panel that does not. The alternative phrasing claims
 * BOSS needs it, which would be a lie told in a consent dialog for downloading code.
 */
internal fun performancePanelPrompt(installer: MissingDependencyInstaller): MissingDependencyPrompt? {
    if (installer.isInstalled(PERFORMANCE_PLUGIN_ID)) return null
    return MissingDependencyPrompt(
        missing =
            MissingPluginDependency(
                dependentPluginId = HOST_ID,
                dependentDisplayName = "BOSS",
                missingPluginId = PERFORMANCE_PLUGIN_ID,
                optional = true,
            ),
        installer = installer,
        // A click, so it is asked again even after the offer was dismissed once.
        userInitiated = true,
    )
}
