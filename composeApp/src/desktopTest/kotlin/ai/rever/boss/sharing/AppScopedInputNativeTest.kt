package ai.rever.boss.sharing

import ai.rever.boss.window.BossWindowIcon
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GraphicsEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** All UI is synthetic; no global Robot, key dispatch queue, or user application is touched. */
class AppScopedInputNativeTest {
    @Test fun `scoped delivery types into selected field and clicks selected button without changing another window`() {
        assumeTrue(System.getenv("BOSS_TEST_APP_CAPTURE") == "1", "Synthetic input smoke is opt-in")
        assumeTrue(!GraphicsEnvironment.isHeadless())
        onEdt {
            val field = JTextField()
            val otherField = JTextField("untouched")
            val clicks = AtomicInteger()
            val button = JButton("Synthetic action").apply { addActionListener { clicks.incrementAndGet() } }
            val selected = window(field, button)
            val other = window(otherField, JButton("Other action"))
            val sink = AwtAppInputSink(selected, requireForeground = false)
            try {
                assertTrue(sink.isAvailable())
                click(sink, selected, field)
                assertTrue(sink.apply(AppInputEvent.Key("down", "KeyA", "a", false, false, false, false)))
                assertTrue(sink.apply(AppInputEvent.Key("up", "KeyA", "a", false, false, false, false)))
                assertEquals("a", field.text)
                assertEquals("untouched", otherField.text)
                click(sink, selected, button)
                assertEquals(1, clicks.get())
                selected.isVisible = false
                assertFalse(sink.apply(AppInputEvent.Key("down", "KeyB", "b", false, false, false, false)))
                assertEquals("a", field.text)
            } finally {
                sink.releaseAll()
                selected.dispose()
                other.dispose()
            }
        }
    }

    // Keep popup geometry, background focus and cleanup in one real Compose lifecycle.
    @Suppress("LongMethod")
    @Test
    fun `background Compose delivery types and clicks without OS focus`() {
        assumeTrue(System.getenv("BOSS_TEST_APP_CAPTURE") == "1", "Synthetic input smoke is opt-in")
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val text = mutableStateOf("")
        val clicks = AtomicInteger()
        val ready = CountDownLatch(1)
        val selected =
            onEdt {
                ComposeWindow().apply {
                    title = "Synthetic background Compose input"
                    focusableWindowState = false
                    setSize(400, 260)
                    setContent {
                        Column(Modifier.onGloballyPositioned { ready.countDown() }) {
                            TextField(text.value, { text.value = it }, Modifier.fillMaxWidth().height(80.dp))
                            Button({ clicks.incrementAndGet() }, Modifier.fillMaxWidth().height(80.dp)) {
                                Text("Synthetic action")
                            }
                        }
                    }
                    isVisible = true
                }
            }
        val sink = AwtAppInputSink(selected, requireForeground = false)
        val popup =
            onEdt {
                ComposeDialog(selected, java.awt.Dialog.ModalityType.MODELESS, selected.graphicsConfiguration).apply {
                    focusableWindowState = false
                    setBounds(selected.x + 100, selected.y + 100, 150, 100)
                    setContent { Text("Synthetic address suggestions") }
                }
            }
        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Compose content must lay out")
            onEdt {
                assertFalse(selected.isFocused)
                assertTrue(sink.isAvailable())
                val x = 100.0 / (selected.width - 1)
                val fieldY = (selected.insets.top + 40.0) / (selected.height - 1)
                assertTrue(sink.apply(AppInputEvent.Pointer("down", x, fieldY, 0)))
                assertTrue(sink.apply(AppInputEvent.Pointer("up", x, fieldY, 0)))
                sink.updateSurfaces(captureSurfaceSnapshot(selected))
            }
            // Compose processes pointer focus asynchronously, outside this test's EDT invocation.
            Thread.sleep(200)
            onEdt {
                // Popup/geometry updates release held input while the background field stays selected.
                sink.releaseAll()
                popup.isVisible = true
                for ((index, char) in "rapidtyping".withIndex()) {
                    popup.setSize(150, 100 + index)
                    assertFalse(sink.isAvailable(), "Changed popup must still reject coordinate input")
                    val key =
                        AppInputEvent.Key(
                            action = "down",
                            code = "Key${char.uppercaseChar()}",
                            key = char.toString(),
                            alt = false,
                            ctrl = false,
                            meta = false,
                            shift = false,
                        )
                    assertTrue(sink.apply(key))
                    assertTrue(sink.apply(key.copy(action = "up")))
                }
                popup.isVisible = false
                sink.updateSurfaces(captureSurfaceSnapshot(selected))
                val x = 100.0 / (selected.width - 1)
                val buttonY = (selected.insets.top + 120.0) / (selected.height - 1)
                assertTrue(sink.apply(AppInputEvent.Pointer("down", x, buttonY, 0)))
                assertTrue(sink.apply(AppInputEvent.Pointer("up", x, buttonY, 0)))
            }
            Thread.sleep(200)
            onEdt {
                assertEquals("rapidtyping", text.value)
                assertEquals(1, clicks.get())
                assertFalse(selected.isFocused)
            }
        } finally {
            onEdt {
                sink.releaseAll()
                popup.dispose()
                selected.dispose()
            }
        }
    }

    private fun window(
        field: JTextField,
        button: JButton,
    ): JFrame =
        JFrame("Synthetic scoped input").apply {
            iconImages = BossWindowIcon.images
            contentPane =
                JPanel(BorderLayout()).apply {
                    add(field, BorderLayout.NORTH)
                    add(button, BorderLayout.CENTER)
                }
            setSize(320, 180)
            isVisible = true
            validate()
        }

    private fun click(
        sink: AwtAppInputSink,
        window: JFrame,
        component: Component,
    ) {
        val point = SwingUtilities.convertPoint(component, component.width / 2, component.height / 2, window)
        val x = point.x.toDouble() / (window.width - 1)
        val y = point.y.toDouble() / (window.height - 1)
        assertTrue(sink.apply(AppInputEvent.Pointer("down", x, y, 0)))
        assertTrue(sink.apply(AppInputEvent.Pointer("up", x, y, 0)))
    }
}
