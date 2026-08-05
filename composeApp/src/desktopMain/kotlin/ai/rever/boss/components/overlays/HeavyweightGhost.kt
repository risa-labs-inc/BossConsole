package ai.rever.boss.components.overlays

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.Toolkit

/**
 * How far from the cursor the ghost sits - below-right normally, above-left at a screen edge (see
 * [clampGhostToScreens]).
 *
 * NOT cosmetic. A non-focusable AWT window still receives mouse events, and the JVM has no portable
 * click-through, so a ghost drawn under the pointer would swallow the drag that is moving it and the
 * drop would never land. The gap keeps the pointer outside the window for the whole drag. It also
 * matches [SwingTooltip], which places itself off the cursor for the same reason.
 */
private const val GHOST_GAP_PX = 16

/**
 * Heavyweight drag-ghost host for HARDWARE_ACCELERATED browser mode.
 *
 * Renders [content] in a small transparent, undecorated, always-on-top, non-focusable window of
 * [size] that tracks the cursor, so a drag ghost stays visible while it crosses the heavyweight
 * JxBrowser surface instead of disappearing behind the page. Injected into
 * [OverlayConfig.heavyweightGhost].
 *
 * **Content-sized, not parent-sized**, which is the one hard difference from [HeavyweightHud]. A
 * parent-sized ghost window would cover the whole app and eat the pointer events that constitute the
 * drag, ending it the moment it began.
 *
 * Position is read from [MouseInfo] on the frame clock rather than from the caller's Compose
 * coordinates. Two reasons: the cursor is authoritative for something that follows the cursor, and
 * converting window coordinates to screen coordinates has to go through the CONTENT PANE rather than
 * the window (via the window it is off by the title-bar height) - a conversion this sidesteps
 * entirely.
 */
@Composable
fun HeavyweightGhost(
    size: DpSize,
    content: @Composable () -> Unit,
) {
    val screens = remember { screenRects() }
    // Place it correctly on the FIRST frame. Leaving the initial position at the origin and letting
    // the frame loop below correct it shows the ghost at the top-left corner for one frame, which
    // reads as a flicker at the start of every drag.
    val initial =
        remember(size, screens) {
            val cursor = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
            clampGhostToScreens(
                cursorX = cursor?.x ?: 0,
                cursorY = cursor?.y ?: 0,
                width = size.width.value.toInt(),
                height = size.height.value.toInt(),
                screens = screens,
            )
        }
    val state =
        rememberWindowState(size = size, position = WindowPosition(initial.first.dp, initial.second.dp))

    // Drive from the frame clock, not from recomposition: the ghost has to keep up with the pointer
    // whether or not anything it reads has changed, and the caller's drag state lives in a different
    // subtree. Only assign when the point actually moves - each assignment is a native setLocation,
    // and a still pointer should cost nothing.
    LaunchedEffect(size) {
        var last: Pair<Int, Int>? = initial
        while (true) {
            withFrameNanos { }
            val cursor = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull() ?: continue
            val placed =
                clampGhostToScreens(
                    cursorX = cursor.x,
                    cursorY = cursor.y,
                    width = size.width.value.toInt(),
                    height = size.height.value.toInt(),
                    screens = screens,
                )
            if (placed != last) {
                last = placed
                state.position = WindowPosition(placed.first.dp, placed.second.dp)
            }
        }
    }

    Window(
        onCloseRequest = {},
        state = state,
        undecorated = true,
        transparent = true,
        alwaysOnTop = true,
        focusable = false,
        resizable = false,
    ) {
        EnsureOverlayWindowTransparent(window)
        Box(modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}

/**
 * Where a ghost of [width] x [height] goes for a cursor at ([cursorX], [cursorY]): offset from the
 * pointer, and inside the working area of whichever monitor the pointer is on.
 *
 * Below-right by default. When that would spill off an edge the ghost **flips to the other side of
 * the cursor** rather than being pulled back across it - pulling back is what a plain clamp does, and
 * near the right edge it slides the window under the pointer, which is exactly the state
 * [GHOST_GAP_PX] exists to prevent. Flipping keeps the pointer outside the ghost on every edge, so
 * the gap guarantee holds everywhere and not just mid-screen.
 *
 * Pure so the placement can be pinned by a test, which is the only part of this reachable without a
 * display.
 */
internal fun clampGhostToScreens(
    cursorX: Int,
    cursorY: Int,
    width: Int,
    height: Int,
    screens: List<IntArray>,
): Pair<Int, Int> {
    val screen =
        screens.firstOrNull { it.contains(cursorX, cursorY) }
            ?: screens.firstOrNull()
            ?: return Pair(cursorX + GHOST_GAP_PX, cursorY + GHOST_GAP_PX)
    return Pair(
        flipOrClamp(cursorX, width, screen[0], screen[2]),
        flipOrClamp(cursorY, height, screen[1], screen[3]),
    )
}

/** Whether this `[x, y, width, height]` rect holds the point ([x], [y]). */
private fun IntArray.contains(
    x: Int,
    y: Int,
): Boolean = x >= this[0] && x < this[0] + this[2] && y >= this[1] && y < this[1] + this[3]

/**
 * One axis of the placement: [cursor] + gap, flipped to `cursor - gap - extent` when that does not
 * fit, and clamped only if NEITHER side does.
 *
 * Both candidates are tested against both ends of the range, not just the far one. A cursor that is
 * outside the monitor entirely - which happens when a display is unplugged mid-drag and the cached
 * rects no longer describe where the pointer is - otherwise passes the far-edge test trivially and
 * the ghost is placed thousands of pixels off-screen, invisible for the rest of the drag.
 *
 * The final `coerceIn` is the neither-side-fits case, i.e. a ghost bigger than the monitor. It uses
 * `coerceAtLeast(origin)` on the upper bound because an inverted range makes `coerceIn` throw, and an
 * exception here would take the whole drag gesture down with it.
 */
private fun flipOrClamp(
    cursor: Int,
    extent: Int,
    origin: Int,
    available: Int,
): Int {
    val limit = origin + available

    fun fits(candidate: Int) = candidate >= origin && candidate + extent <= limit
    val after = cursor + GHOST_GAP_PX
    val before = cursor - GHOST_GAP_PX - extent
    return when {
        fits(after) -> after
        fits(before) -> before
        else -> after.coerceIn(origin, (limit - extent).coerceAtLeast(origin))
    }
}

/**
 * Every monitor's working area as `[x, y, width, height]`, i.e. bounds minus insets (taskbar, dock,
 * menu bar). Read once per ghost: monitors do not come and go mid-drag.
 */
private fun screenRects(): List<IntArray> =
    runCatching {
        GraphicsEnvironment
            .getLocalGraphicsEnvironment()
            .screenDevices
            .map { device ->
                val bounds: Rectangle = device.defaultConfiguration.bounds
                val insets = Toolkit.getDefaultToolkit().getScreenInsets(device.defaultConfiguration)
                intArrayOf(
                    bounds.x + insets.left,
                    bounds.y + insets.top,
                    bounds.width - insets.left - insets.right,
                    bounds.height - insets.top - insets.bottom,
                )
            }
    }.getOrDefault(emptyList())
