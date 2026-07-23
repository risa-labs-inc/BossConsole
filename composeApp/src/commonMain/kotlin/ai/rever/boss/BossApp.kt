package ai.rever.boss

import BossTheme
import ai.rever.boss.app.BossAppCompositionLocals
import ai.rever.boss.app.BossAppDialogs
import ai.rever.boss.app.BossAppEventBusEffects
import ai.rever.boss.app.BossAppMenuActionEffects
import ai.rever.boss.app.BossAppScaffold
import ai.rever.boss.app.BossAppStartupEffects
import ai.rever.boss.app.rememberBossAppState
import ai.rever.boss.app.rememberFocusModeReveal
import ai.rever.boss.components.registery.PanelRegistry
import ai.rever.boss.focusmode.FocusModeSettingsManager
import ai.rever.boss.window.WindowAppearanceSettingsManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalDensity
import com.arkivanov.decompose.ComponentContext

/**
 * Root composable for one BOSS window.
 *
 * This is deliberately thin — it wires four layers together and nothing else:
 *  1. [rememberBossAppState] builds the window's component graph and UI state
 *     (see `ai.rever.boss.app.BossAppState`).
 *  2. Effect groups subscribe the window to the outside world:
 *     [BossAppStartupEffects] (lifecycle, workspace restore, updater, wizard),
 *     [BossAppEventBusEffects] (window-filtered event buses), and
 *     [BossAppMenuActionEffects] (menu-bar actions).
 *  3. [BossAppScaffold] renders the chrome (bars, sidebars, split view,
 *     overlays) with focus-mode hover reveal from [rememberFocusModeReveal].
 *  4. [BossAppDialogs] hosts every dialog, driven by flags on the state.
 *
 * Platform hooks live in PlatformHooks.kt; tab drag/drop handling in
 * TabDropHandler.kt (kept in this package for its unit test).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ComponentContext.BossApp(
    windowId: String,
    isFirstWindow: Boolean = false,
    panelRegistry: PanelRegistry,
    onToggleMaximize: (() -> Unit)? = null
) {
    val state = rememberBossAppState(
        windowId = windowId,
        isFirstWindow = isFirstWindow,
        panelRegistry = panelRegistry,
    )

    // Subscriptions and lifecycle effects (no UI).
    BossAppStartupEffects(state)
    BossAppEventBusEffects(state)

    // Focus mode + window appearance settings drive the chrome.
    val focusModeSettings by FocusModeSettingsManager.currentSettings.collectAsState()
    val windowAppearanceSettings by WindowAppearanceSettingsManager.currentSettings.collectAsState()
    val reveal = rememberFocusModeReveal(
        isFocusModeEnabled = focusModeSettings.enabled,
        revealDelayMs = focusModeSettings.revealDelayMs,
    )

    // Menu actions can force-reveal sidebars, so they take the reveal state too.
    BossAppMenuActionEffects(state, reveal)

    BossTheme {
        BossAppCompositionLocals(state) {
            BossAppScaffold(
                state = state,
                reveal = reveal,
                isFocusModeEnabled = focusModeSettings.enabled,
                isAutoRevealEnabled = focusModeSettings.autoRevealEnabled,
                revealOffsetDp = with(LocalDensity.current) { focusModeSettings.revealOffsetPx.toDp() },
                showTitleBar = windowAppearanceSettings.showTitleBar,
                onToggleMaximize = onToggleMaximize,
            )

            BossAppDialogs(state)
        }
    }
}
