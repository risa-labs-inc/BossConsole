package ai.rever.boss.sharing

import ai.rever.boss.window.BossWindowIcon
import androidx.compose.ui.awt.ComposeWindow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.Frame
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Opt-in real user32/JNA check, using only this test's two exact Compose HWNDs. */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfEnvironmentVariable(named = "BOSS_TEST_WINDOWS_CAPTURE_STATE", matches = "1")
class WindowsAppCaptureStateSmokeTest {
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    fun `native minimized state belongs only to the supplied live window`() {
        val windows =
            onEdt {
                List(2) { index ->
                    ComposeWindow().apply {
                        title = "Synthetic capture state $index"
                        iconImages = BossWindowIcon.images
                        focusableWindowState = false
                        setSize(240, 160)
                        setLocation(80 + index * 260, 80)
                        isVisible = true
                    }
                }
            }
        try {
            val handles = onEdt { windows.map { it.windowHandle } }
            assertTrue(handles.all { it != 0L } && handles.distinct().size == 2)
            assertFalse(WindowsAppCaptureState.minimized(handles[0]))
            assertFalse(WindowsAppCaptureState.minimized(handles[1]))

            onEdt { windows[1].extendedState = Frame.ICONIFIED }
            awaitMinimized(handles[1])
            assertFalse(WindowsAppCaptureState.minimized(handles[0]), "Another minimized HWND cannot pause this source")

            onEdt { windows[0].extendedState = Frame.ICONIFIED }
            awaitMinimized(handles[0])
            onEdt { windows[0].dispose() }
            assertFalse(WindowsAppCaptureState.minimized(handles[0]), "Disposed HWND must never retain pause authority")
            assertTrue(
                WindowsAppCaptureState.minimized(handles[1]),
                "Disposing one synthetic source must not change the other",
            )
        } finally {
            onEdt { windows.forEach { it.dispose() } }
        }
    }

    private fun awaitMinimized(handle: Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (WindowsAppCaptureState.minimized(handle)) return
            Thread.sleep(10)
        }
        assertTrue(WindowsAppCaptureState.minimized(handle), "Native IsIconic did not observe the synthetic minimize")
    }
}
