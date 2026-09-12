package ai.rever.boss.pet

import java.awt.GraphicsEnvironment
import java.awt.Rectangle

/** A saved anchor may name a disconnected display. Keep the whole card on a connected screen. */
internal fun petPosition(
    savedX: Int?,
    savedY: Int?,
    screens: List<Rectangle>,
    width: Int,
    height: Int,
): Pair<Int, Int> {
    val available = screens.filter { it.width >= width && it.height >= height }
    val primary = available.firstOrNull() ?: Rectangle(0, 0, 1200, 800)
    val screen =
        if (savedX != null && savedY != null) {
            available.firstOrNull { it.contains(savedX, savedY) }
        } else {
            null
        }
    val target = screen ?: primary
    val x = if (screen != null) savedX ?: target.x else target.x + target.width - width - 40
    val y = if (screen != null) savedY ?: target.y else target.y + target.height - height - 80
    return x.coerceIn(target.x, target.x + target.width - width) to
        y.coerceIn(target.y, target.y + target.height - height)
}

internal fun connectedPetScreens(): List<Rectangle> =
    runCatching {
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val primary = environment.defaultScreenDevice
        (listOf(primary) + environment.screenDevices.filter { it != primary })
            .map { it.defaultConfiguration.bounds }
    }.getOrDefault(emptyList())
