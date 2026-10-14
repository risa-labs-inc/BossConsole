package ai.rever.boss.sharing

import java.awt.Desktop
import java.awt.desktop.ScreenSleepEvent
import java.awt.desktop.ScreenSleepListener
import java.awt.desktop.SystemSleepEvent
import java.awt.desktop.SystemSleepListener
import java.awt.desktop.UserSessionEvent
import java.awt.desktop.UserSessionListener
import java.util.concurrent.atomic.AtomicBoolean

internal interface AppCaptureSessionMonitor {
    fun supported(): Boolean

    fun watch(onUnavailable: () -> Unit): AutoCloseable
}

/** Documented JDK session/lock and sleep events. A stopped publication never resumes automatically. */
internal class DesktopAppCaptureSessionMonitor : AppCaptureSessionMonitor {
    override fun supported(): Boolean =
        runCatching {
            Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_EVENT_USER_SESSION) &&
                Desktop.getDesktop().isSupported(Desktop.Action.APP_EVENT_SCREEN_SLEEP) &&
                Desktop.getDesktop().isSupported(Desktop.Action.APP_EVENT_SYSTEM_SLEEP)
        }.getOrDefault(false)

    override fun watch(onUnavailable: () -> Unit): AutoCloseable {
        check(supported()) { "OS session lock monitoring is unavailable" }
        val desktop = Desktop.getDesktop()
        val live = AtomicBoolean(true)
        val listener =
            object : UserSessionListener, ScreenSleepListener, SystemSleepListener {
                private fun stop() {
                    if (live.compareAndSet(true, false)) onUnavailable()
                }

                override fun userSessionDeactivated(event: UserSessionEvent) = stop()

                override fun screenAboutToSleep(event: ScreenSleepEvent) = stop()

                override fun systemAboutToSleep(event: SystemSleepEvent) = stop()

                override fun userSessionActivated(event: UserSessionEvent) = Unit

                override fun screenAwoke(event: ScreenSleepEvent) = Unit

                override fun systemAwoke(event: SystemSleepEvent) = Unit
            }
        desktop.addAppEventListener(listener)
        return AutoCloseable {
            live.set(false)
            desktop.removeAppEventListener(listener)
        }
    }
}
