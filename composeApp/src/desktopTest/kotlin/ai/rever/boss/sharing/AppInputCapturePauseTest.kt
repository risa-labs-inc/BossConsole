package ai.rever.boss.sharing

import ai.rever.boss.window.OwnedWindowControls
import androidx.compose.ui.awt.ComposeWindow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Synthetic owned window only; no global input or user application state changes. */
@EnabledIfEnvironmentVariable(named = "BOSS_TEST_APP_CAPTURE", matches = "1")
class AppInputCapturePauseTest {
    @Test
    @Suppress("LongMethod") // Capture loss, recovery and resume must share the same live peer and held button.
    fun `missing pixels release input and permit only scoped recovery until fresh surfaces`() =
        onEdt {
            val button = JButton("Synthetic button")
            val window =
                ComposeWindow().apply {
                    focusableWindowState = false
                    setContent { }
                    glassPane = JPanel(BorderLayout()).apply { add(button, BorderLayout.CENTER) }
                    glassPane.isVisible = true
                    setBounds(120, 120, 320, 180)
                    isVisible = true
                    validate()
                }
            var recoveries = 0
            var closes = 0
            var allowed = true
            val sink = AwtAppInputSink(window, privateSurfaceAllowed = { allowed }, requireForeground = false)
            val controls =
                OwnedWindowControls.register(
                    "synthetic-paused-input",
                    window,
                    mapOf(
                        "restore" to { recoveries++ },
                        "exit-fullscreen" to { recoveries++ },
                        "close" to { closes++ },
                    ),
                )
            try {
                val snapshot = captureSurfaceSnapshot(window)
                sink.updateSurfaces(snapshot)
                val down = AppInputEvent.Pointer("down", 0.5, 0.6, 0)
                assertTrue(sink.apply(down))
                assertTrue(button.model.isPressed)
                sink.pauseCapture()
                assertFalse(button.model.isPressed, "Capture loss releases held input")
                assertFalse(sink.isAvailable())
                val blocked =
                    listOf(
                        down,
                        AppInputEvent.Wheel(0.5, 0.6, 0.0, 1.0),
                        AppInputEvent.Key("down", "KeyA", "a", false, false, false, false),
                        AppInputEvent.Window("close"),
                        AppInputEvent.Window("minimize"),
                        AppInputEvent.Window("maximize"),
                        AppInputEvent.Window("unmaximize"),
                    )
                blocked.forEach { assertFalse(sink.apply(it), "Paused input must reject $it") }
                assertTrue(sink.apply(AppInputEvent.Window("restore")))
                sink.pauseCapture()
                sink.pauseCapture()
                assertTrue(sink.apply(AppInputEvent.Window("exit-fullscreen")))
                assertEquals(2, recoveries)
                assertEquals(0, closes)
                allowed = false
                assertFalse(sink.apply(AppInputEvent.Window("restore")))
                allowed = true
                assertFalse(sink.apply(AppInputEvent.Window("restore"), 0))
                assertFalse(sink.isAvailable(), "Recovery itself does not resume pixel input")
                // Even identical geometry needs a fresh frame callback to resume.
                sink.updateSurfaces(snapshot)
                assertTrue(sink.isAvailable())
                assertTrue(sink.apply(down))
                assertTrue(button.model.isPressed)
                assertTrue(sink.apply(down.copy(action = "up")))
                assertFalse(button.model.isPressed)
            } finally {
                sink.releaseAll()
                controls.close()
                window.dispose()
            }
        }
}
