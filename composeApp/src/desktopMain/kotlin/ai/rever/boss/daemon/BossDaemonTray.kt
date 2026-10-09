package ai.rever.boss.daemon

import java.awt.BasicStroke
import java.awt.Color
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities

/** One host-owned B-in-a-square mark, regardless of how many plugins use the daemon. */
internal object BossDaemonTray {
    private var icon: TrayIcon? = null
    private var anchor: java.awt.Frame? = null

    @Suppress("ReturnCount") // Headless, EDT dispatch and already-installed guards.
    fun install(
        count: () -> Int,
        quit: () -> Unit,
    ) {
        if (java.awt.GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) return
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeAndWait { install(count, quit) }
            return
        }
        if (icon != null) return
        val popup = PopupMenu()
        popup.add(MenuItem("BOSS background services").apply { isEnabled = false })
        val services = MenuItem("Services: ${count()}").apply { isEnabled = false }
        popup.add(services)
        popup.addSeparator()
        popup.add(MenuItem("Open BOSS").apply { addActionListener { BossDaemonLauncher.openApplication() } })
        popup.add(MenuItem("Quit BOSS daemon").apply { addActionListener { quit() } })
        val frame =
            java.awt.Frame().apply {
                isUndecorated = true
                isResizable = false
                setSize(1, 1)
                add(popup)
            }
        anchor = frame
        val tray = TrayIcon(renderIcon(), "BOSS daemon")
        tray.addMouseListener(
            object : java.awt.event.MouseAdapter() {
                override fun mousePressed(e: java.awt.event.MouseEvent) {
                    if (SwingUtilities.isRightMouseButton(e)) {
                        services.label = "Services: ${count()}"
                        frame.setLocation(e.x, e.y)
                        frame.isVisible = true
                        popup.show(frame, 0, 0)
                    }
                }

                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) BossDaemonLauncher.openApplication()
                }
            },
        )
        runCatching {
            SystemTray.getSystemTray().add(tray)
            icon = tray
        }.onFailure { remove() }
    }

    fun remove() {
        if (icon == null && anchor == null) return
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeAndWait { remove() }
            return
        }
        icon?.let { SystemTray.getSystemTray().remove(it) }
        icon = null
        anchor?.dispose()
        anchor = null
    }

    internal fun renderIcon(): BufferedImage {
        val image = BufferedImage(18, 18, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply {
            try {
                setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                color = Color.WHITE
                stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                drawRoundRect(2, 2, 14, 14, 3, 3)
                val b =
                    Path2D.Float().apply {
                        moveTo(6.0, 5.0)
                        lineTo(9.3, 5.0)
                        curveTo(13.0, 5.0, 13.0, 9.0, 9.3, 9.0)
                        lineTo(6.0, 9.0)
                        moveTo(9.3, 9.0)
                        curveTo(13.5, 9.0, 13.5, 13.0, 9.3, 13.0)
                        lineTo(6.0, 13.0)
                        moveTo(6.0, 5.0)
                        lineTo(6.0, 13.0)
                    }
                draw(b)
            } finally {
                dispose()
            }
        }
        return image
    }
}
