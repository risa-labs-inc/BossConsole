package ai.rever.boss.sharing

import ai.rever.boss.utils.WindowFocusManager
import ai.rever.boss.window.BossWindowIcon
import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.ui.KeyCode
import com.teamdev.jxbrowser.ui.KeyModifiers
import com.teamdev.jxbrowser.ui.Point
import com.teamdev.jxbrowser.ui.event.KeyPressed
import com.teamdev.jxbrowser.ui.event.KeyTyped
import com.teamdev.jxbrowser.ui.event.MousePressed
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GridLayout
import java.awt.Window
import java.awt.geom.Rectangle2D
import java.lang.reflect.Proxy
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Synthetic background windows and recording browser endpoints; no engine/profile/network. */
@EnabledIfEnvironmentVariable(named = "BOSS_TEST_APP_CAPTURE", matches = "1")
class AppBrowserInputTest {
    @Test fun `split browsers receive their own focused typing with content-pane coordinates`() {
        onEdt {
            Fixture().use { fixture ->
                click(fixture, fixture.left)
                assertTrue(fixture.activePane === fixture.left)
                type(fixture.sink, "left")
                click(fixture, fixture.right)
                assertTrue(fixture.activePane === fixture.right)
                type(fixture.sink, "right")
                assertEquals("left", fixture.first.typed.toString())
                assertEquals("right", fixture.second.typed.toString())
                assertEquals(Point.of(20, 20), fixture.first.pressed.single())
                assertEquals(Point.of(20, 20), fixture.second.pressed.single())
                assertFalse(fixture.first.focused)
                assertTrue(fixture.second.focused)
                assertFalse(fixture.window.isFocused)
            }
        }
    }

    @Test fun `retired browser composition cannot receive keys or cleanup in its successor`() {
        onEdt {
            Fixture().use { fixture ->
                click(fixture, fixture.left)
                type(fixture.sink, "a")
                fixture.firstCurrent = false
                assertFalse(fixture.sink.apply(key('b')))
                assertEquals("a", fixture.first.typed.toString())
                click(fixture, fixture.right)
                type(fixture.sink, "c")
                assertEquals("a", fixture.first.typed.toString())
                assertEquals("c", fixture.second.typed.toString())
            }
        }
    }

    @Test fun `clicking host address field clears browser authority and preserves ordinary AWT typing`() {
        onEdt {
            Fixture().use { fixture ->
                click(fixture, fixture.left)
                type(fixture.sink, "page")
                click(fixture, fixture.address)
                type(fixture.sink, "url")
                assertEquals("page", fixture.first.typed.toString())
                assertEquals("url", fixture.address.text)
                assertFalse(fixture.first.focused)
                assertEquals("", fixture.second.typed.toString())
            }
        }
    }

    @Test fun `background key permit is exact one-shot scoped and cleared after dispatch`() {
        onEdt {
            Fixture().use { fixture ->
                val foreign = JFrame("Synthetic foreign browser owner").apply { iconImages = BossWindowIcon.images }
                val ownerId = "synthetic-key-owner"
                val foreignId = "synthetic-foreign-owner"
                WindowFocusManager.registerWindow(ownerId, fixture.window)
                WindowFocusManager.registerWindow(foreignId, foreign)
                val pressed = KeyPressed.newBuilder(KeyCode.KEY_CODE_A).keyChar('a').build()
                var current = false
                try {
                    fixture.first.browser.focus()
                    AppBrowserKeyDispatch.register(fixture.first.browser)
                    fixture.first.onPressed = { event ->
                        assertFalse(AppBrowserKeyDispatch.consume(fixture.first.browser, event, ownerId))
                        current = true
                        assertFalse(AppBrowserKeyDispatch.consume(fixture.second.browser, event, ownerId))
                        assertFalse(AppBrowserKeyDispatch.consume(fixture.first.browser, event, foreignId))
                        assertFalse(AppBrowserKeyDispatch.consume(fixture.first.browser, event, null))
                        val otherKey = KeyPressed.newBuilder(KeyCode.KEY_CODE_B).build()
                        assertFalse(AppBrowserKeyDispatch.consume(fixture.first.browser, otherKey, ownerId))
                        val shifted =
                            KeyPressed
                                .newBuilder(KeyCode.KEY_CODE_A)
                                .keyModifiers(KeyModifiers.newBuilder().shiftDown(true).build())
                                .build()
                        assertFalse(AppBrowserKeyDispatch.consume(fixture.first.browser, shifted, ownerId))
                        assertTrue(AppBrowserKeyDispatch.consume(fixture.first.browser, event, ownerId))
                        assertFalse(AppBrowserKeyDispatch.consume(fixture.first.browser, event, ownerId))
                    }
                    AppBrowserKeyDispatch.press(fixture.first.browser, fixture.window, pressed) { current }
                    assertFalse(AppBrowserKeyDispatch.consume(fixture.first.browser, pressed, ownerId))
                } finally {
                    WindowFocusManager.unregisterWindow(ownerId)
                    WindowFocusManager.unregisterWindow(foreignId)
                    foreign.dispose()
                }
            }
        }
    }

    private class RecordingBrowser {
        var focused = false
        val typed = StringBuilder()
        val pressed = mutableListOf<Point>()
        var onPressed: ((KeyPressed) -> Unit)? = null
        val browser =
            Proxy.newProxyInstance(
                Browser::class.java.classLoader,
                arrayOf(Browser::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "focus" -> {
                        focused = true
                    }

                    "unfocus" -> {
                        focused = false
                    }

                    "dispatch" -> {
                        assertTrue(focused, "Browser input requires engine focus before dispatch")
                        when (val event = args?.single()) {
                            is KeyPressed -> onPressed?.invoke(event)
                            is KeyTyped -> typed.append(event.keyChar())
                            is MousePressed -> pressed.add(event.location())
                        }
                    }

                    "equals" -> {
                        return@newProxyInstance proxy === args?.single()
                    }

                    "hashCode" -> {
                        return@newProxyInstance System.identityHashCode(proxy)
                    }

                    "toString" -> {
                        return@newProxyInstance "Synthetic browser"
                    }

                    else -> {
                        error("Unexpected browser operation ${method.name}")
                    }
                }
                null
            } as Browser
    }

    private class Fixture : AutoCloseable {
        val address = JTextField()
        val left = JPanel()
        val right = JPanel()
        val first = RecordingBrowser()
        val second = RecordingBrowser()
        var firstCurrent = true
        var activePane: Component? = null
        val window =
            JFrame("Synthetic browser routing").apply {
                iconImages = BossWindowIcon.images
                focusableWindowState = false
                contentPane =
                    JPanel(BorderLayout()).apply {
                        add(address, BorderLayout.NORTH)
                        add(
                            JPanel(GridLayout(1, 2)).apply {
                                add(left)
                                add(right)
                            },
                            BorderLayout.CENTER,
                        )
                    }
                setSize(480, 240)
                isVisible = true
                validate()
            }
        private val surfaces =
            listOf(
                surface(first.browser, left) { firstCurrent },
                surface(second.browser, right) { true },
            )
        val sink =
            AwtAppInputSink(window, requireForeground = false) { candidate, x, y ->
                surfaces.filter { it.contains(candidate, x, y) }.singleOrNull()
            }

        private fun surface(
            browser: Browser,
            component: Component,
            current: () -> Boolean,
        ): AppBrowserInputSurface {
            val origin = window.contentPane
            val point = SwingUtilities.convertPoint(component, 0, 0, origin)
            return AppBrowserInputSurface(
                browser,
                origin,
                Rectangle2D.Double(
                    point.x.toDouble(),
                    point.y.toDouble(),
                    component.width.toDouble(),
                    component.height.toDouble(),
                ),
                activate = { activePane = component },
                current = current,
            )
        }

        override fun close() {
            sink.releaseAll()
            window.dispose()
        }
    }

    private fun click(
        fixture: Fixture,
        component: Component,
    ) {
        val point = SwingUtilities.convertPoint(component, 20, minOf(20, component.height / 2), fixture.window)
        val x = point.x.toDouble() / (fixture.window.width - 1)
        val y = point.y.toDouble() / (fixture.window.height - 1)
        assertTrue(fixture.sink.apply(AppInputEvent.Pointer("down", x, y, 0)))
        assertTrue(fixture.sink.apply(AppInputEvent.Pointer("up", x, y, 0)))
    }

    private fun type(
        sink: AppScopedInputSink,
        text: String,
    ) {
        text.forEach { char ->
            assertTrue(sink.apply(key(char)))
            assertTrue(sink.apply(key(char).copy(action = "up")))
        }
    }

    private fun key(char: Char): AppInputEvent.Key =
        AppInputEvent.Key(
            "down",
            "Key${char.uppercaseChar()}",
            char.toString(),
            false,
            false,
            false,
            false,
        )
}
