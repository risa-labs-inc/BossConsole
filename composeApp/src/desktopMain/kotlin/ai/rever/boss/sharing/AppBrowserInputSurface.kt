package ai.rever.boss.sharing

import ai.rever.boss.plugin.browser.ActiveBrowserRegistry
import ai.rever.boss.plugin.browser.BrowserHandleImpl
import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.ui.Point
import java.awt.Component
import java.awt.Window
import java.awt.geom.Rectangle2D
import javax.swing.SwingUtilities

/** A visible Compose browser, bound to its exact composition and its own content-pane coordinates. */
internal class AppBrowserInputSurface(
    val browser: Browser,
    val origin: Component,
    val bounds: Rectangle2D.Double,
    private val current: () -> Boolean,
) {
    fun isCurrent(): Boolean = origin.isShowing && current()

    fun point(
        component: Component,
        x: Int,
        y: Int,
    ): Point {
        val local = SwingUtilities.convertPoint(component, x, y, origin)
        return Point.of((local.x - bounds.x).toInt(), (local.y - bounds.y).toInt())
    }

    fun contains(
        window: Window,
        x: Int,
        y: Int,
    ): Boolean {
        if (!isCurrent() || SwingUtilities.getWindowAncestor(origin) !== window) return false
        val local = SwingUtilities.convertPoint(window, x, y, origin)
        return bounds.contains(local.x.toDouble(), local.y.toDouble())
    }
}

/** Never choose the active tab or a first browser: the visible rectangle must match this window. */
internal fun appBrowserInputSurfaceAt(
    window: Window,
    x: Int,
    y: Int,
): AppBrowserInputSurface? =
    ActiveBrowserRegistry
        .composedHandles()
        .filterIsInstance<BrowserHandleImpl>()
        .mapNotNull { it.appInputSurface(window) }
        .filter { it.contains(window, x, y) }
        .singleOrNull()
