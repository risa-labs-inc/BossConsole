package ai.rever.boss.window

import ai.rever.boss.utils.SystemUtils
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import java.awt.Frame
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

private val logger = BossLogger.forComponent("MaximizedBoundsPin")

/**
 * Gives a macOS window explicit maximized bounds, which keeps AWT's maximize from deadlocking.
 *
 * With no explicit bounds, JDK 17's `LWWindowPeer.getMaximizedBounds` computes them while holding
 * the peer's state lock, and that computation (`CGraphicsDevice.getScreenInsets`) waits for the
 * AppKit main thread. If the main thread is meanwhile delivering a mouse event to the same window,
 * it calls `LWComponentPeer.getBounds`, which needs that lock, and neither thread moves again. A
 * MAIN window starts maximized, so the race is run on every launch: `setVisible` maximizes it at
 * the moment it appears under the pointer and the mouse-entered event arrives. The app froze on
 * the splash with 0% CPU, ignored SIGTERM, and jstack reported no deadlock because one side is a
 * native wait.
 *
 * With explicit bounds, the peer returns them under the lock without any native call. They must be
 * in place before `setVisible` maximizes the window, so this runs in the window's first
 * composition, which Compose performs after creating the peer (`setMaximizedBounds` on a live peer
 * stores them without a native call) and before showing it. Measured on the running app: the pin
 * logs `peerCreated=true, showing=false`. The insets are read here instead, holding no lock.
 *
 * Pinned bounds no longer follow the screen by themselves, so they are re-read when the window
 * moves to another display and when it is activated (which is when a Dock or menu-bar change made
 * elsewhere would show). Windows and Linux are left alone: their peers are not affected, and on
 * Windows explicit bounds would override the platform's taskbar handling.
 */
@Composable
internal fun PinMaximizedBounds(window: Frame) {
    if (!SystemUtils.isMacOS) return
    remember(window) {
        pinMaximizedBounds(window)
        // showing must read false: the guarantee only holds if this ran before setVisible maximizes.
        logger.debug(
            LogCategory.UI,
            "Pinned maximized bounds",
            mapOf(
                "bounds" to window.maximizedBounds.toString(),
                "peerCreated" to window.isDisplayable,
                "showing" to window.isShowing,
            ),
        )
    }
    DisposableEffect(window) {
        var device = window.graphicsConfiguration?.device
        val moved =
            object : ComponentAdapter() {
                override fun componentMoved(e: ComponentEvent) {
                    val now = window.graphicsConfiguration?.device
                    if (now != device) {
                        device = now
                        pinMaximizedBounds(window)
                    }
                }
            }
        val activated =
            object : WindowAdapter() {
                override fun windowActivated(e: WindowEvent) = pinMaximizedBounds(window)
            }
        window.addComponentListener(moved)
        window.addWindowListener(activated)
        onDispose {
            window.removeComponentListener(moved)
            window.removeWindowListener(activated)
        }
    }
}

private fun pinMaximizedBounds(window: Frame) {
    val config: GraphicsConfiguration =
        window.graphicsConfiguration
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    val bounds = usableScreenBounds(config.bounds, Toolkit.getDefaultToolkit().getScreenInsets(config))
    if (bounds != window.maximizedBounds) window.maximizedBounds = bounds
}

/** The screen minus its insets: exactly what the peer would have computed itself. */
internal fun usableScreenBounds(
    screen: Rectangle,
    insets: Insets,
): Rectangle =
    Rectangle(
        screen.x + insets.left,
        screen.y + insets.top,
        screen.width - insets.left - insets.right,
        screen.height - insets.top - insets.bottom,
    )
