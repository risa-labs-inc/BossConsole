package ai.rever.boss.components.overlays

import java.awt.Font
import java.awt.MouseInfo
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JWindow
import javax.swing.SwingUtilities
import java.awt.Color as AwtColor

/**
 * Swing-based hover tooltip for HARDWARE_ACCELERATED browser mode.
 *
 * A lightweight Compose `Popup` renders BEHIND JxBrowser's heavyweight GPU surface, so the
 * plugin-icon hover tooltips were hidden by the browser. This shows the tooltip text in a tiny,
 * undecorated [JWindow] which — being a heavyweight native window — layers above the browser.
 *
 * Why a JWindow rather than the draft [HeavyweightPopup]:
 *  - It packs to its content, so it can't capture clicks over a large transparent area.
 *  - A JWindow never takes focus, so it can't steal focus from the page (correct for a tooltip).
 *  - It's positioned from [MouseInfo] (exact screen coordinates), avoiding window/inset/DPI
 *    coordinate conversion — a tooltip near the cursor is the conventional placement anyway.
 *
 * Only used when [OverlayConfig.useHeavyweightPopups] is true (HARDWARE mode); otherwise callers
 * keep using the normal Compose tooltip, so this cannot affect the OFF_SCREEN default.
 */
object SwingTooltip {
    private var window: JWindow? = null
    private var label: JLabel? = null

    /**
     * One window is created and then REUSED, rather than built and disposed per hover.
     *
     * Disposing per hover also made the tooltip order-dependent: moving the pointer straight from
     * one hinted button to another enqueues the first one's hide() and the second one's show() on
     * the EDT, and if they land in that order the new tooltip is destroyed immediately after being
     * shown. Reusing one window turns hide-then-show into setVisible(false) followed by
     * setVisible(true) on the same object, which ends visible whichever way round they run.
     */
    fun show(text: String) {
        SwingUtilities.invokeLater {
            val existing = label
            val w: JWindow
            if (existing != null && window != null) {
                existing.text = text
                w = window!!
                w.pack() // re-fit to the new text
            } else {
                val fresh =
                    JLabel(text).apply {
                        isOpaque = true
                        background = AwtColor(0x2B, 0x2B, 0x2B)
                        foreground = AwtColor.WHITE
                        font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
                        border =
                            BorderFactory.createCompoundBorder(
                                BorderFactory.createLineBorder(AwtColor(0x3C, 0x3F, 0x41), 1),
                                BorderFactory.createEmptyBorder(4, 8, 4, 8),
                            )
                    }
                w =
                    JWindow().apply {
                        // A JWindow is non-focusable by default; make it explicit so it can never
                        // steal focus from the browser when it appears.
                        focusableWindowState = false
                        contentPane.add(fresh)
                        pack() // size to the label
                    }
                label = fresh
            }
            // Position just below-right of the cursor (screen coords from MouseInfo — exact), then
            // CLAMP fully inside the working area of whichever monitor the cursor is on, so the
            // tooltip never spills off the right/bottom edge (or onto the taskbar).
            val cursor = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
            if (cursor != null) {
                val size = w.size
                val ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                val gc =
                    ge.screenDevices
                        .map { it.defaultConfiguration }
                        .firstOrNull { it.bounds.contains(cursor) }
                        ?: ge.defaultScreenDevice.defaultConfiguration
                val b = gc.bounds
                val insets =
                    java.awt.Toolkit
                        .getDefaultToolkit()
                        .getScreenInsets(gc)
                val minX = b.x + insets.left
                val maxX = (b.x + b.width - insets.right - size.width).coerceAtLeast(minX)
                val minY = b.y + insets.top
                val maxY = (b.y + b.height - insets.bottom - size.height).coerceAtLeast(minY)
                val x = (cursor.x + 12).coerceIn(minX, maxX)
                val y = (cursor.y + 18).coerceIn(minY, maxY)
                w.setLocation(x, y)
            }
            w.isVisible = true
            window = w
        }
    }

    /**
     * Hides the tooltip without destroying it, so the next [show] can reuse the window. The window
     * is intentionally never disposed: it is one small non-focusable window for the life of the
     * app, and keeping it is what makes an interleaved hide/show pair order-independent.
     */
    fun hide() {
        SwingUtilities.invokeLater { window?.isVisible = false }
    }
}
