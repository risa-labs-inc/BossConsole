package ai.rever.boss.sharing

import ai.rever.boss.window.MacToolbarRuntime
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import com.sun.jna.Pointer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.awt.Frame
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@EnabledIfEnvironmentVariable(named = "BOSS_TEST_APP_CONTINUOUS_CAPTURE", matches = "1")
class AppCaptureMinimizeSmokeTest {
    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    @Suppress("LongMethod")
    fun `minimize clears pixels and restore resumes while close remains terminal`() {
        val window =
            onEdt {
                ComposeWindow().apply {
                    focusableWindowState = false
                    setSize(320, 240)
                    setContent { Box(Modifier.fillMaxSize().background(Color.Blue)) }
                    isVisible = true
                }
            }
        val minimizeRequested = AtomicBoolean()
        val initial = CountDownLatch(1)
        val blank = CountDownLatch(1)
        val restored = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val revision = AtomicLong()
        val stopReason =
            java.util.concurrent.atomic
                .AtomicReference<String?>()
        val monitor =
            object : AppCaptureSessionMonitor {
                override fun supported() = true

                override fun watch(onUnavailable: () -> Unit): AutoCloseable = AutoCloseable {}
            }
        try {
            AppContinuousWindowCapture(monitor)
                .start(
                    AppCaptureTarget(UUID.randomUUID().toString(), UUID.randomUUID().toString(), window),
                    { frame ->
                        if (frame == null) {
                            if (minimizeRequested.get()) blank.countDown()
                        } else {
                            if (revision.compareAndSet(0, frame.geometryRevision)) initial.countDown()
                            if (frame.geometryRevision > revision.get()) restored.countDown()
                        }
                    },
                    { reason ->
                        stopReason.set(reason)
                        stopped.countDown()
                    },
                    { true },
                ).use {
                    assertTrue(initial.await(15, TimeUnit.SECONDS), "Initial native pixels")
                    minimizeRequested.set(true)
                    onEdt { window.extendedState = Frame.ICONIFIED }
                    assertTrue(blank.await(5, TimeUnit.SECONDS), "Minimizing must retire captured pixels")
                    assertEquals(1L, stopped.count, "Minimize must retain publication for remote restore")
                    val handle = onEdt { window.windowHandle }
                    MacToolbarRuntime.dispatch {
                        val native = Pointer(handle)
                        if (MacToolbarRuntime.isLiveWindow(native)) {
                            MacToolbarRuntime.send(native, "deminiaturize:", null)
                        }
                    }
                    assertTrue(
                        restored.await(15, TimeUnit.SECONDS),
                        "Restore must resume: stop=${stopReason.get()}, state=${onEdt { window.extendedState }}, " +
                            "showing=${onEdt { window.isShowing }}, revision=${revision.get()}",
                    )
                    onEdt { window.dispose() }
                    assertTrue(stopped.await(5, TimeUnit.SECONDS), "Closing must stop, never masquerade as minimized")
                }
        } finally {
            onEdt { window.dispose() }
        }
    }
}
