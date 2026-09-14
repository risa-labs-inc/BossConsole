package ai.rever.boss.startup

import ai.rever.boss.components.overlays.HeavyweightCorner
import ai.rever.boss.components.overlays.HeavyweightGhost
import ai.rever.boss.components.overlays.HeavyweightHud
import ai.rever.boss.components.overlays.HeavyweightModal
import ai.rever.boss.components.overlays.HeavyweightPopup
import ai.rever.boss.components.overlays.OverlayConfig
import ai.rever.boss.components.overlays.SwingTooltip
import ai.rever.boss.components.plugin.MissingPluginOffer
import ai.rever.boss.config.JxBrowserConfig
import ai.rever.boss.plugin.MissingDependencyReporter
import ai.rever.boss.plugin.ui.BossOverlayHost
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.teamdev.jxbrowser.engine.RenderingMode

/**
 * Configures overlay hosting and heavyweight popup rendering strategies.
 */
object OverlaySetup {
    private val logger by lazy { BossLogger.forComponent("OverlaySetup") }

    /**
     * Wires heavyweight overlay renderers (popups, modals, tooltips, HUDs, ghosts, corners)
     * and missing plugin installer factory.
     */
    fun configure() {
        // Route app overlays (context menus, dropdowns, tooltips) through heavyweight windows when the
        // browser is GPU-composited. In HARDWARE_ACCELERATED mode the JxBrowser view is a heavyweight
        // native surface that paints above lightweight Compose, so an ordinary Compose Popup renders
        // BEHIND the page. Dormant - a no-op - wherever OFF_SCREEN is the mode (macOS, Linux), so the
        // unchanged platforms cannot regress.
        // Install diagnostics before guarded renderers so registration conflicts are visible.
        BossOverlayHost.diagnostics = { message ->
            logger.warn(LogCategory.UI, message)
        }

        OverlayConfig.heavyweightPopup = { onDismiss, anchorInWindow, anchoring, popupOffset, focusable, popupContent ->
            HeavyweightPopup(onDismiss, anchorInWindow, anchoring, popupOffset, focusable, popupContent)
        }

        OverlayConfig.heavyweightModal = { properties, onDismiss, modalContent ->
            HeavyweightModal(properties, onDismiss, modalContent)
        }

        // Lets host UI in commonMain offer to install a plugin it needs. The installer is built in
        // desktopMain because resolving and downloading a plugin is a desktop concern.
        MissingPluginOffer.installerFactory = { manager ->
            MissingDependencyReporter.installerFor(manager)
        }

        OverlayConfig.heavyweightTooltip = { text ->
            SwingTooltip.show(text)
        }

        OverlayConfig.hideHeavyweightTooltip = {
            SwingTooltip.hide()
        }

        OverlayConfig.heavyweightHud = { alignment, hudContent ->
            HeavyweightHud(alignment, hudContent)
        }

        OverlayConfig.heavyweightGhost = { size, hotspot, ghostContent ->
            HeavyweightGhost(size, hotspot, ghostContent)
        }

        OverlayConfig.heavyweightCorner = { alignment, initialSize, inset, focusable, regionInWindow, cornerContent ->
            HeavyweightCorner(alignment, initialSize, inset, focusable, regionInWindow, cornerContent)
        }

        OverlayConfig.useHeavyweightPopups =
            JxBrowserConfig.renderingMode == RenderingMode.HARDWARE_ACCELERATED
    }
}
