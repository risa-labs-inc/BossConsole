package ai.rever.boss.components.overlays

import java.awt.Dimension
import java.awt.Font
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
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
 * Always-on-top is a consequence of that JWindow layer, not a feature: the label sits above
 * EVERY application, not just BOSS, until hover exit hides it (a Cmd/Alt-Tab away with a label
 * up can leave it lingering over the other app), and toFront() on a non-focusable window is a
 * best-effort hint under some X11 window managers.
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
     * Disposing per hover also made the tooltip fragile when moving the pointer straight from one
     * hinted button to another: the first one's hide() and the second one's show() both land on
     * the EDT, and a disposed window cannot be revived by the show() that follows. Reusing one
     * window reduces that to setVisible(false)/setVisible(true) on the same object, so the pair
     * settles on whichever ran last instead of leaving a destroyed window behind. Compose orders
     * these correctly anyway — the leaving composable's onDispose runs before the entering one's
     * DisposableEffect — so this is about not depending on that.
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
                        // The hover drawer is an always-on-top native window too. Keeping this
                        // tooltip in that layer and raising it after show prevents a retained rail
                        // action's label from being covered by its sibling drawer.
                        isAlwaysOnTop = true
                        contentPane.add(fresh)
                        pack() // size to the label
                    }
                label = fresh
            }
            // Below-right of the cursor (screen coords from MouseInfo - exact), inside the working
            // area of whichever monitor the cursor is on. See [tooltipScreenPosition] for why a side
            // that does not fit FLIPS rather than clamps.
            val cursor = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
            if (cursor != null) {
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
                val area =
                    Rectangle(
                        b.x + insets.left,
                        b.y + insets.top,
                        b.width - insets.left - insets.right,
                        b.height - insets.top - insets.bottom,
                    )
                w.location = tooltipScreenPosition(cursor, w.size, area)
            }
            w.isVisible = true
            w.toFront()
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

private const val CURSOR_GAP_X = 12
private const val CURSOR_GAP_Y = 18

/** Space kept between the cursor and a tooltip flipped above or to the left of it. */
private const val FLIPPED_GAP = 8

/**
 * Where the tooltip window of [size] goes for a pointer at [cursor], inside [area].
 *
 * Below-right of the cursor by default. A side that does not fit FLIPS to the other side of the
 * cursor instead of being clamped: clamping a bottom-bar tooltip at the screen's bottom edge
 * pushed the window straight back up onto the pointer, which ended the hover that was showing it,
 * which hid it, which restored the hover - the MCP access tooltip blinked for as long as the
 * pointer rested low enough in the bar. The final clamp only matters for a tooltip larger than
 * the space on either side.
 */
internal fun tooltipScreenPosition(
    cursor: Point,
    size: Dimension,
    area: Rectangle,
): Point {
    val right = cursor.x + CURSOR_GAP_X
    val x =
        if (right + size.width <= area.x + area.width) right else cursor.x - FLIPPED_GAP - size.width
    val below = cursor.y + CURSOR_GAP_Y
    val y =
        if (below + size.height <= area.y + area.height) below else cursor.y - FLIPPED_GAP - size.height
    val maxX = (area.x + area.width - size.width).coerceAtLeast(area.x)
    val maxY = (area.y + area.height - size.height).coerceAtLeast(area.y)
    return Point(x.coerceIn(area.x, maxX), y.coerceIn(area.y, maxY))
}
