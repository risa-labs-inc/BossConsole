package ai.rever.boss.window

import ai.rever.boss.plugin.ui.BossThemeController
import ai.rever.boss.theme.AppThemeSettingsManager
import ai.rever.boss.theme.WindowGlass
import ai.rever.boss.theme.isGlassTheme
import ai.rever.boss.utils.SystemUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.Timer

/** Window owns native resources. The timer also catches system accessibility and frame changes. */
@Composable
internal fun rememberNativeWindowGlass(
    window: ComposeWindow,
    fullscreen: Boolean,
): WindowGlass {
    val settings by AppThemeSettingsManager.settings.collectAsState()
    val theme = BossThemeController.current
    var installed by remember(window) { mutableStateOf(false) }
    val mac = SystemUtils.isMacOS
    val enabled = mac && isGlassTheme(theme.id) && settings.glassCoverage in setOf("sidebar", "window")
    val currentFullscreen by rememberUpdatedState(fullscreen)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentDark by rememberUpdatedState(!theme.isLight)
    val currentClear by rememberUpdatedState(settings.glassStyle == "clear")
    var refresh by remember(window) { mutableStateOf<(() -> Unit)?>(null) }
    // Keep the native material and cached fullscreen wallpaper across theme/style changes.
    DisposableEffect(window) {
        var controller: MacWindowGlass? = null

        fun update() {
            if (mac && window.isShowing && window.windowHandle != 0L) {
                if (controller == null) controller = MacWindowGlass(window.windowHandle) { installed = it }
                controller?.update(
                    GlassRequest(
                        currentEnabled,
                        androidx.compose.ui.unit
                            .IntSize(window.width.coerceAtLeast(1), window.height.coerceAtLeast(1)),
                        currentDark,
                        currentClear,
                        currentFullscreen,
                    ),
                )
            }
        }
        val listener =
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) = update()

                override fun componentShown(e: ComponentEvent) = update()
            }
        val timer = Timer(2000) { update() }
        if (mac) {
            window.addComponentListener(listener)
            timer.start()
            refresh = ::update
            update()
        }
        onDispose {
            refresh = null
            timer.stop()
            window.removeComponentListener(listener)
            controller?.close()
            installed = false
        }
    }
    // Updated state is committed before this runs; the timer is only a fallback for
    // native accessibility/frame changes, not the delivery path for Compose settings.
    LaunchedEffect(refresh, enabled, theme.isLight, settings.glassStyle, fullscreen) { refresh?.invoke() }
    return WindowGlass(installed && enabled, settings.glassCoverage, settings.glassTint, settings.glassOpacity)
}
