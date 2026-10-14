package ai.rever.boss.sharing

import ai.rever.boss.utils.SystemUtils
import ai.rever.boss.window.BossWindowIcon
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An unfocusable, synthetic owned window; traversal must never focus another application. */
@EnabledIfEnvironmentVariable(named = "BOSS_TEST_APP_CAPTURE", matches = "1")
class AppScopedNavigationInputTest {
    @Test fun `Tab and Shift Tab move the remote text target inside its window`() {
        onEdt {
            val first = JTextField()
            val second = JTextField()
            val window = fixture(first, second)
            val sink = AwtAppInputSink(window, requireForeground = false)
            try {
                click(sink, window, first, 0)
                press(sink, "Tab", "Tab")
                press(sink, "KeyB", "b")
                assertEquals("", first.text)
                assertEquals("b", second.text)
                press(sink, "Tab", "Tab", shift = true)
                press(sink, "KeyA", "a")
                assertEquals("a", first.text)
                assertEquals("b", second.text)
                assertFalse(window.isFocused)
            } finally {
                sink.releaseAll()
                window.dispose()
            }
        }
    }

    @Test fun `secondary click emits the platform popup trigger without foreground focus`() {
        onEdt {
            val field = JTextField("Synthetic context menu")
            val events = mutableListOf<MouseEvent>()
            field.addMouseListener(
                object : MouseAdapter() {
                    override fun mousePressed(event: MouseEvent) {
                        events.add(event)
                    }

                    override fun mouseReleased(event: MouseEvent) {
                        events.add(event)
                    }
                },
            )
            val window = fixture(field, JTextField())
            val sink = AwtAppInputSink(window, requireForeground = false)
            try {
                click(sink, window, field, 2)
                assertEquals(listOf(MouseEvent.BUTTON3, MouseEvent.BUTTON3), events.map { it.button })
                val trigger = if (SystemUtils.isMacOS) MouseEvent.MOUSE_PRESSED else MouseEvent.MOUSE_RELEASED
                assertEquals(listOf(trigger), events.filter { it.isPopupTrigger }.map { it.id })
                assertFalse(window.isFocused)
            } finally {
                sink.releaseAll()
                window.dispose()
            }
        }
    }

    private fun fixture(
        first: JTextField,
        second: JTextField,
    ): JFrame =
        JFrame("Synthetic remote navigation").apply {
            iconImages = BossWindowIcon.images
            focusableWindowState = false
            contentPane =
                JPanel(GridLayout(2, 1)).apply {
                    add(first)
                    add(second)
                }
            setSize(320, 180)
            isVisible = true
            validate()
        }

    private fun click(
        sink: AwtAppInputSink,
        window: JFrame,
        field: JTextField,
        button: Int,
    ) {
        val point = SwingUtilities.convertPoint(field, field.width / 2, field.height / 2, window)
        val x = point.x.toDouble() / (window.width - 1)
        val y = point.y.toDouble() / (window.height - 1)
        assertTrue(sink.apply(AppInputEvent.Pointer("down", x, y, button)))
        assertTrue(sink.apply(AppInputEvent.Pointer("up", x, y, button)))
    }

    private fun press(
        sink: AwtAppInputSink,
        code: String,
        key: String,
        shift: Boolean = false,
    ) {
        val event = AppInputEvent.Key("down", code, key, false, false, false, shift)
        assertTrue(sink.apply(event))
        assertTrue(sink.apply(event.copy(action = "up")))
    }
}
