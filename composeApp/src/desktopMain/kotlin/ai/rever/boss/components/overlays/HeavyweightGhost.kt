package ai.rever.boss.components.overlays

import ai.rever.boss.window.ApplyBossWindowIcon
import ai.rever.boss.window.BossWindowIcon
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Rectangle

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
 * [hotspot] is where the POINTER sits inside the ghost, so this window lands exactly where the
 * lightweight ghost does: centred under the cursor for the sidebar icon, a quarter in from the
 * leading edge for a tab card. It used to be placed 16px below-right of the cursor instead, on the
 * theory that a window under the pointer would swallow the drag that is moving it (the JVM has no
 * portable click-through, and a non-focusable AWT window still receives mouse events). Measured on
 * macOS, that is not what happens for a ghost of this kind: the press that starts the drag lands on
 * the source window first and takes the implicit mouse grab, after which a 22x22 always-on-top
 * window centred on the cursor receives NOTHING - every drag event and the release still go to the
 * source. The gap only mattered for a ghost that is already up when the button goes down, which
 * neither of ours is (both appear after a long press or a drag threshold). What the gap did do was
 * park the ghost ~27px diagonally off the pointer, which reads as an icon that is not being carried.
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
    hotspot: DpOffset,
    content: @Composable () -> Unit,
) {
    val screens = remember { screenRects() }
    // Place it correctly on the FIRST frame. Leaving the initial position at the origin and letting
    // the frame loop below correct it shows the ghost at the top-left corner for one frame, which
    // reads as a flicker at the start of every drag.
    val initial =
        remember(size, hotspot, screens) {
            val cursor = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
            placeGhost(cursor?.x ?: 0, cursor?.y ?: 0, size, hotspot, screens)
        }
    val state =
        rememberWindowState(size = size, position = WindowPosition(initial.first.dp, initial.second.dp))

    // The AWT window, once it exists. See FollowCursor for why it is moved directly.
    var awtWindow by remember { mutableStateOf<java.awt.Window?>(null) }
    FollowCursor(size, hotspot, screens, initial, state) { awtWindow }

    Window(
        onCloseRequest = {},
        state = state,
        undecorated = true,
        transparent = true,
        alwaysOnTop = true,
        focusable = false,
        resizable = false,
        icon = BossWindowIcon.painter,
    ) {
        EnsureOverlayWindowTransparent(window, kind = "ghost")
        ApplyBossWindowIcon(window)
        DisposableEffect(window) {
            awtWindow = window
            onDispose { awtWindow = null }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}

/**
 * Keeps the ghost window on the cursor for as long as it is composed.
 *
 * Driven from the frame clock, not from recomposition: the ghost has to keep up with the pointer
 * whether or not anything it reads has changed, and the caller's drag state lives in a different
 * subtree. Only assigns when the point actually moves - each assignment is a native setLocation,
 * and a still pointer should cost nothing.
 *
 * [window] is moved directly rather than through [WindowState.position]: assigning snapshot state
 * inside `withFrameNanos` is only applied by the NEXT frame, so the ghost trailed the pointer by a
 * frame on top of the frame the cursor read is already old. Both callbacks run on the EDT, which is
 * where `setLocation` has to be called from. The state is the fallback for the frames before the
 * window exists.
 *
 * That leaves two things that can place this window, so they must not disagree: Compose installs a
 * `componentMoved` listener that writes the moved position straight back into [state], which keeps
 * it in step with every `setLocation` here. It is worth knowing that this is what makes the split
 * safe - if that write-back ever goes away, anything that re-applies [WindowState.position] would
 * teleport the ghost back to where the drag started, and this loop would only correct it on the
 * next pointer move.
 */
@Composable
private fun FollowCursor(
    size: DpSize,
    hotspot: DpOffset,
    screens: List<IntArray>,
    initial: Pair<Int, Int>,
    state: WindowState,
    window: () -> java.awt.Window?,
) {
    LaunchedEffect(size, hotspot) {
        var last: Pair<Int, Int>? = initial
        while (true) {
            withFrameNanos { }
            val cursor = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull() ?: continue
            val placed = placeGhost(cursor.x, cursor.y, size, hotspot, screens)
            if (placed != last) {
                last = placed
                val awt = window()
                if (awt != null) {
                    awt.setLocation(placed.first, placed.second)
                } else {
                    state.position = WindowPosition(placed.first.dp, placed.second.dp)
                }
            }
        }
    }
}

/**
 * [clampGhostToScreens] in the units the caller has: dp, which AWT reports 1:1 as logical pixels.
 */
private fun placeGhost(
    cursorX: Int,
    cursorY: Int,
    size: DpSize,
    hotspot: DpOffset,
    screens: List<IntArray>,
): Pair<Int, Int> =
    clampGhostToScreens(
        cursorX = cursorX,
        cursorY = cursorY,
        size = IntSize(size.width.value.toInt(), size.height.value.toInt()),
        hotspot = IntOffset(hotspot.x.value.toInt(), hotspot.y.value.toInt()),
        screens = screens,
    )

/**
 * Where a ghost of [size] goes for a cursor at ([cursorX], [cursorY]): hung off the pointer by
 * [hotspot], and pulled back onto a monitor only if that would hang it off the edge of the desktop.
 *
 * The clamp is the one thing allowed to move the ghost off its hotspot, and it exists for exactly
 * one case: a ghost half outside every display is half invisible for the rest of the drag. So it is
 * asked only when the ghost would actually land there. **A ghost that fits inside the union of the
 * monitors is left exactly on the pointer**, which is what keeps a drag across the seam between two
 * displays from yanking the card up to its hotspot's width sideways and snapping it back on the
 * other side. Clamping to the monitor the cursor happens to be on cannot tell the two cases apart -
 * spilling onto the next display is free.
 *
 * Coverage is tested by the ghost's corners rather than by area: monitors are rectangles laid out in
 * a grid, so a rectangle whose four corners are all on some screen spans at worst a seam between
 * them, which is exactly the case being allowed. And it is tested against [screenRects], which is
 * full display bounds rather than working areas - a reserved strip is still a place with a screen
 * under it, and treating a dock or a menu bar as "no screen" put the seam artifact back in a band
 * along the top and bottom of every display.
 *
 * Pure so the placement can be pinned by a test, which is the only part of this reachable without a
 * display.
 */
internal fun clampGhostToScreens(
    cursorX: Int,
    cursorY: Int,
    size: IntSize,
    hotspot: IntOffset,
    screens: List<IntArray>,
): Pair<Int, Int> {
    val x = cursorX - hotspot.x
    val y = cursorY - hotspot.y
    // Nothing known to clamp against, or nothing to clamp: the ghost stays on the pointer.
    if (screens.isEmpty() || screens.covers(x, y, size)) return Pair(x, y)

    val screen =
        screens.firstOrNull { it.contains(cursorX, cursorY) }
            ?: screens.first()
    return Pair(
        pinInside(x, size.width, screen[0], screen[2]),
        pinInside(y, size.height, screen[1], screen[3]),
    )
}

/** Whether every corner of the ghost at ([x], [y]) sits on some monitor's working area. */
private fun List<IntArray>.covers(
    x: Int,
    y: Int,
    size: IntSize,
): Boolean {
    // The far edges are exclusive, matching contains(): a ghost ending exactly on the boundary is
    // inside, and one pixel past it is not.
    val corners =
        listOf(
            x to y,
            x + size.width - 1 to y,
            x to y + size.height - 1,
            x + size.width - 1 to y + size.height - 1,
        )
    return corners.all { (cx, cy) -> any { it.contains(cx, cy) } }
}

/** Whether this `[x, y, width, height]` rect holds the point ([x], [y]). */
private fun IntArray.contains(
    x: Int,
    y: Int,
): Boolean = x >= this[0] && x < this[0] + this[2] && y >= this[1] && y < this[1] + this[3]

/**
 * One axis of the placement: [at], kept inside `[origin, origin + available]` for an extent of
 * [extent].
 *
 * The `coerceAtLeast(origin)` on the upper bound is the ghost-bigger-than-the-monitor case: an
 * inverted range makes `coerceIn` throw, and an exception here would take the whole drag gesture
 * down with it. A cursor that is outside the monitor entirely - which happens when a display is
 * unplugged mid-drag and the cached rects no longer describe where the pointer is - is pulled back
 * onto the monitor by the same clamp rather than being left thousands of pixels off-screen.
 */
private fun pinInside(
    at: Int,
    extent: Int,
    origin: Int,
    available: Int,
): Int = at.coerceIn(origin, (origin + available - extent).coerceAtLeast(origin))

/**
 * Every monitor's FULL bounds as `[x, y, width, height]`. Read once per ghost: monitors do not come
 * and go mid-drag.
 *
 * Bounds rather than the working area (bounds minus dock, taskbar and menu bar), which is what this
 * used to subtract. A working area answers "where may a window be placed", and a drag ghost is not
 * being placed - it is a transient always-on-top overlay tracking the pointer, and the pointer goes
 * over the dock like anything else. Subtracting the insets made those reserved strips read as "no
 * screen here", so a ghost whose far corner reached into the menu-bar strip of the display next
 * door was treated as off the desktop and yanked back onto the cursor's monitor: the seam artifact
 * this is meant to avoid, restored in a band the height of a menu bar. See [clampGhostToScreens].
 */
private fun screenRects(): List<IntArray> =
    runCatching {
        GraphicsEnvironment
            .getLocalGraphicsEnvironment()
            .screenDevices
            .map { device ->
                val bounds: Rectangle = device.defaultConfiguration.bounds
                intArrayOf(bounds.x, bounds.y, bounds.width, bounds.height)
            }
    }.getOrDefault(emptyList())
