package ai.rever.boss.components.overlays

import androidx.compose.material.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class FocusedTooltipWindowTest {
    @get:Rule val rule = createComposeRule()

    @Test fun focusedNativeTooltipHidesWhenOwningWindowLosesFocus() {
        val previousMode = OverlayConfig.useHeavyweightPopups
        val previousShow = OverlayConfig.heavyweightTooltip
        val previousHide = OverlayConfig.hideHeavyweightTooltip
        val focused = mutableStateOf(true)
        var shown = 0
        var hidden = 0
        try {
            // Other suite tests already registered the startup mode. Each fixture is a new host.
            resetOverlayFieldForTest("useHeavyweightOverlays")
            OverlayConfig.useHeavyweightPopups = true
            OverlayConfig.heavyweightTooltip = { shown++ }
            OverlayConfig.hideHeavyweightTooltip = { hidden++ }
            rule.setContent {
                CompositionLocalProvider(
                    LocalWindowInfo provides
                        object : WindowInfo {
                            override val isWindowFocused = focused.value
                        },
                ) {
                    HoverTooltipBox("Purpose", focused = true) { Text("Tool") }
                }
            }
            rule.runOnIdle {
                assertEquals(1, shown)
                focused.value = false
            }
            rule.runOnIdle {
                assertEquals(1, hidden)
                focused.value = true
            }
            rule.runOnIdle {
                assertEquals(2, shown)
                focused.value = false
            }
            rule.runOnIdle { assertEquals(2, hidden) }
        } finally {
            resetOverlayFieldForTest("useHeavyweightOverlays")
            OverlayConfig.useHeavyweightPopups = previousMode
            OverlayConfig.heavyweightTooltip = previousShow
            OverlayConfig.hideHeavyweightTooltip = previousHide
        }
    }
}
