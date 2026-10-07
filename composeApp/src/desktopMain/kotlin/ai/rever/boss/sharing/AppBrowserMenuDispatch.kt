package ai.rever.boss.sharing

import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.ui.Point
import java.awt.event.MouseEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A one-shot remote menu anchor. A page cannot publish a point or authorize a later menu. */
internal object AppBrowserMenuDispatch {
    class Click(
        val event: MouseEvent,
        val current: () -> Boolean,
        val point: Point,
    )

    private val pending = ConcurrentHashMap<Browser, Click>()
    private val expiry =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "boss-remote-menu-anchor").apply { isDaemon = true }
        }

    fun record(
        browser: Browser,
        event: MouseEvent,
        point: Point,
        current: () -> Boolean,
    ) {
        val click = Click(event, current, point)
        pending[browser] = click
        // Pages with custom DOM menus never call the native callback. Retire their unused anchor.
        expiry.schedule({ pending.remove(browser, click) }, 1, TimeUnit.SECONDS)
    }

    fun consume(
        browser: Browser,
        point: Point,
    ): Click? {
        val click = pending[browser] ?: return null
        return click.takeIf { it.point.x() == point.x() && it.point.y() == point.y() && pending.remove(browser, click) }
    }
}
