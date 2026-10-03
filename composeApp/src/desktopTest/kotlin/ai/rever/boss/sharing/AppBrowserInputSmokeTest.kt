package ai.rever.boss.sharing

import ai.rever.boss.config.JxBrowserConfig
import ai.rever.boss.plugin.browser.ChromiumToolkitPreload
import ai.rever.boss.plugin.browser.FluckEngine
import ai.rever.boss.utils.SystemUtils
import ai.rever.boss.utils.WindowFocusManager
import ai.rever.boss.window.BossWindowIcon
import com.teamdev.jxbrowser.engine.Engine
import com.teamdev.jxbrowser.engine.EngineOptions
import com.teamdev.jxbrowser.engine.RenderingMode
import com.teamdev.jxbrowser.frame.Frame
import com.teamdev.jxbrowser.ui.KeyCode
import com.teamdev.jxbrowser.ui.event.KeyPressed
import com.teamdev.jxbrowser.ui.event.KeyReleased
import com.teamdev.jxbrowser.ui.event.KeyTyped
import com.teamdev.jxbrowser.view.swing.BrowserView
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.awt.geom.Rectangle2D
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import javax.swing.JPanel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real Chromium, synthetic page, isolated profile, and scoped background input only. */
@EnabledIfEnvironmentVariable(named = "BOSS_TEST_APP_BROWSER_INPUT", matches = "1")
class AppBrowserInputSmokeTest {
    @TempDir lateinit var temporary: Path

    // One real browser/window lifecycle keeps focus and native-event assertions together.
    @Suppress("LongMethod")
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `background webpage receives trusted typing selection and backspace`() {
        val chromium = Path.of(requireNotNull(System.getenv("BOSS_TEST_APP_MEDIA_CHROMIUM_DIR")))
        ChromiumToolkitPreload.preload(chromium)
        val engine =
            Engine.newInstance(
                EngineOptions
                    .newBuilder(RenderingMode.OFF_SCREEN)
                    .licenseKey(JxBrowserConfig.licenseKey)
                    .chromiumDir(chromium)
                    .userDataDir(Files.createDirectory(temporary.resolve("private-browser-profile")))
                    .enableIncognito()
                    .disableDnsOverHttps()
                    .addSwitch("--disable-background-networking")
                    .addSwitch("--disable-component-update")
                    .addSwitch("--disable-sync")
                    .build(),
            )
        val window =
            onEdt {
                JFrame("Synthetic trusted browser input").apply {
                    iconImages = BossWindowIcon.images
                    focusableWindowState = false
                    contentPane = JPanel()
                    setSize(320, 180)
                    isVisible = true
                    validate()
                }
            }
        try {
            val browser = engine.newBrowser()
            onEdt {
                window.contentPane = BrowserView.newInstance(browser)
                window.validate()
                WindowFocusManager.registerWindow("synthetic-browser-input", window)
            }
            FluckEngine.setupKeyboardInterceptor(browser, "synthetic-browser-input")
            browser.resize(320, 180)
            browser.navigation().loadUrlAndWait("about:blank", Duration.ofSeconds(10))
            val frame = browser.mainFrame().orElseThrow()
            frame.executeJavaScript<Any?>(
                "document.body.style.margin='0';" +
                    "document.body.innerHTML='<input id=field style=\"width:240px;height:40px\">';" +
                    "globalThis.keyDownEvents=0;" +
                    "document.querySelector('#field').addEventListener('keydown',()=>keyDownEvents++);" +
                    "globalThis.inputEvents=[];document.querySelector('#field').addEventListener('input'," +
                    "e=>inputEvents.push(e.isTrusted));",
            )
            val surface =
                AppBrowserInputSurface(browser, window.contentPane, Rectangle2D.Double(0.0, 0.0, 320.0, 180.0)) { true }
            val sink =
                AwtAppInputSink(window, requireForeground = false) { candidate, x, y ->
                    surface.takeIf { it.contains(candidate, x, y) }
                }
            try {
                onEdt {
                    assertFalse(window.isFocused)
                    val x = 20.0 / (window.width - 1)
                    val y = (window.insets.top + 20.0) / (window.height - 1)
                    assertTrue(sink.apply(AppInputEvent.Pointer("down", x, y, 0)))
                    assertTrue(sink.apply(AppInputEvent.Pointer("up", x, y, 0)))
                    "Sharing 42!".forEach { char ->
                        val code =
                            when {
                                char.isLetter() -> "Key${char.uppercaseChar()}"
                                char.isDigit() -> "Digit$char"
                                char == ' ' -> "Space"
                                else -> "Digit1"
                            }
                        val key =
                            AppInputEvent.Key(
                                "down",
                                code,
                                char.toString(),
                                false,
                                false,
                                false,
                                char.isUpperCase() || char == '!',
                            )
                        assertTrue(sink.apply(key))
                        assertTrue(sink.apply(key.copy(action = "up")))
                    }
                }
                awaitValue {
                    val actual = frame.executeJavaScript<String>("document.querySelector('#field').value")
                    actual == "Sharing 42!"
                }
                val acceptedKeys = frame.executeJavaScript<Double>("keyDownEvents")
                assertTrue(requireNotNull(acceptedKeys) >= 11, "Authorized key presses must reach the background page")
                browser.dispatch(KeyPressed.newBuilder(KeyCode.KEY_CODE_Z).keyChar('z').build())
                browser.dispatch(KeyReleased.newBuilder(KeyCode.KEY_CODE_Z).build())
                Thread.sleep(100)
                assertEquals(acceptedKeys, frame.executeJavaScript<Double>("keyDownEvents"))
                assertEquals("Sharing 42!", frame.executeJavaScript<String>("document.querySelector('#field').value"))
                assertEquals("field", frame.executeJavaScript<String>("document.activeElement.id"))
                assertEquals(true, frame.executeJavaScript<Boolean>("inputEvents.length>0&&inputEvents.every(Boolean)"))
                onEdt {
                    val left = AppInputEvent.Key("down", "ArrowLeft", "ArrowLeft", false, false, false, false)
                    assertTrue(sink.apply(left))
                    assertTrue(sink.apply(left.copy(action = "up")))
                    val erase = AppInputEvent.Key("down", "Backspace", "Backspace", false, false, false, false)
                    assertTrue(sink.apply(erase))
                    assertTrue(sink.apply(erase.copy(action = "up")))
                }
                awaitValue { frame.executeJavaScript<String>("document.querySelector('#field').value") == "Sharing 4!" }
                onEdt {
                    val select =
                        AppInputEvent.Key("down", "KeyA", "a", false, !SystemUtils.isMacOS, SystemUtils.isMacOS, false)
                    assertTrue(sink.apply(select))
                    assertTrue(sink.apply(select.copy(action = "up")))
                    val erase = AppInputEvent.Key("down", "Backspace", "Backspace", false, false, false, false)
                    assertTrue(sink.apply(erase))
                    assertTrue(sink.apply(erase.copy(action = "up")))
                    assertFalse(window.isFocused)
                }
                awaitValue { frame.executeJavaScript<String>("document.querySelector('#field').value") == "" }
                assertScrollDirections(frame, window, sink)
            } finally {
                onEdt { sink.releaseAll() }
            }
        } finally {
            onEdt {
                WindowFocusManager.unregisterWindow("synthetic-browser-input")
                window.dispose()
            }
            engine.close()
        }
    }

    private fun assertScrollDirections(
        frame: Frame,
        window: JFrame,
        sink: AwtAppInputSink,
    ) {
        frame.executeJavaScript<Any?>(
            "document.body.innerHTML='<div id=scroller style=width:240px;height:120px;overflow:scroll>" +
                "<div style=width:1200px;height:1200px></div></div>';" +
                "globalThis.wheelEvents=[];document.querySelector('#scroller').addEventListener('wheel'," +
                "e=>wheelEvents.push({x:e.deltaX,y:e.deltaY,trusted:e.isTrusted}),{passive:true});" +
                "globalThis.scrollReady=false;requestAnimationFrame(()=>requestAnimationFrame(()=>scrollReady=true));",
        )
        // Commit the new scroll container to Chromium's compositor before the first wheel gesture.
        awaitValue { frame.executeJavaScript<Boolean>("scrollReady") == true }
        for ((dx, dy) in listOf(0.0 to 48.0, 0.0 to -48.0, 48.0 to 0.0, -48.0 to 0.0)) {
            frame.executeJavaScript<Any?>("scroller.scrollTo(400,400);wheelEvents=[];")
            onEdt {
                val x = 80.0 / (window.width - 1)
                val y = (window.insets.top + 60.0) / (window.height - 1)
                assertTrue(sink.apply(AppInputEvent.Wheel(x, y, dx, dy)))
            }
            val position = if (dx == 0.0) "scrollTop" else "scrollLeft"
            val axis = if (dx == 0.0) "y" else "x"
            val comparison = if (dx + dy > 0) ">" else "<"
            awaitValue { frame.executeJavaScript<Boolean>("wheelEvents.length>0") == true }
            val received = frame.executeJavaScript<String>("JSON.stringify(wheelEvents)")
            val directionMatches =
                frame.executeJavaScript<Boolean>("wheelEvents.every(e=>e.trusted&&e.$axis${comparison}0)") == true
            assertTrue(directionMatches, "Viewer wheel ($dx, $dy) must retain its direction in the page: $received")
            awaitValue(
                message = {
                    "Wheel ($dx, $dy): " +
                        frame.executeJavaScript<String>(
                            "JSON.stringify({events:wheelEvents,x:scroller.scrollLeft,y:scroller.scrollTop})",
                        )
                },
            ) { frame.executeJavaScript<Boolean>("scroller.$position${comparison}400") == true }
            assertFalse(onEdt { window.isFocused }, "Remote scrolling must not focus the host window")
        }
    }

    private fun awaitValue(
        message: () -> String = { "Chromium must process the scoped native input" },
        check: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (check()) return
            Thread.sleep(25)
        }
        assertTrue(check(), message())
    }
}
