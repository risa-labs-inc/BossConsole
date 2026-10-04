package ai.rever.boss.tabfullscreen

import ai.rever.boss.config.JxBrowserConfig
import ai.rever.boss.plugin.browser.ChromiumToolkitPreload
import ai.rever.boss.plugin.browser.FluckEngine
import ai.rever.boss.sharing.onEdt
import ai.rever.boss.window.BossWindowIcon
import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.browser.callback.input.ReleaseKeyCallback
import com.teamdev.jxbrowser.engine.Engine
import com.teamdev.jxbrowser.engine.EngineOptions
import com.teamdev.jxbrowser.engine.RenderingMode
import com.teamdev.jxbrowser.ui.KeyCode
import com.teamdev.jxbrowser.ui.event.KeyPressed
import com.teamdev.jxbrowser.ui.event.KeyReleased
import com.teamdev.jxbrowser.view.swing.BrowserView
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.awt.event.ActionEvent
import java.awt.event.WindowEvent
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import javax.swing.KeyStroke
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@EnabledOnOs(OS.MAC)
@EnabledIfEnvironmentVariable(named = "BOSS_TEST_APP_BROWSER_INPUT", matches = "1")
class FullscreenBrowserInputSmokeTest {
    @TempDir lateinit var temporary: Path

    @Suppress("LongMethod") // One native lifecycle owns the engine, detached surface, input checks and cleanup.
    @ParameterizedTest
    @EnumSource(RenderingMode::class, names = ["OFF_SCREEN", "HARDWARE_ACCELERATED"])
    fun `real Chromium keys follow detached frame events rather than the inactive host`(mode: RenderingMode) {
        val chromium = Path.of(requireNotNull(System.getenv("BOSS_TEST_APP_MEDIA_CHROMIUM_DIR")))
        ChromiumToolkitPreload.preload(chromium)
        val engine =
            Engine.newInstance(
                EngineOptions
                    .newBuilder(mode)
                    .licenseKey(JxBrowserConfig.licenseKey)
                    .chromiumDir(chromium)
                    .userDataDir(temporary.resolve("isolated-profile"))
                    .enableIncognito()
                    .build(),
            )
        val browser = engine.newBrowser()
        val window =
            onEdt {
                JFrame("Synthetic detached browser input").apply {
                    iconImages = BossWindowIcon.images
                    focusableWindowState = false
                    setBounds(120, 600, 400, 160)
                    add(BrowserView.newInstance(browser))
                    isVisible = true
                    observeFullscreenBrowserInput(browser, "unregistered-host", this)
                }
            }
        try {
            FluckEngine.setupKeyboardInterceptor(browser, "unregistered-host")
            browser.navigation().loadUrlAndWait("about:blank", Duration.ofSeconds(10))
            val page = browser.mainFrame().orElseThrow()
            page.executeJavaScript<Any?>("globalThis.keys=0;document.addEventListener('keydown',()=>keys++);")
            browser.focus()
            press(browser)
            assertKeysStay(browser, 0.0)
            onEdt {
                val event = WindowEvent(window, WindowEvent.WINDOW_GAINED_FOCUS)
                window.windowFocusListeners.forEach { it.windowGainedFocus(event) }
            }
            assertEquals(true, fullscreenBrowserInput.focusFor(browser, "unregistered-host"))
            press(browser)
            val deadline =
                System.nanoTime() +
                    java.util.concurrent.TimeUnit.SECONDS
                        .toNanos(5)
            while (page.executeJavaScript<Double>("keys") == 0.0 && System.nanoTime() < deadline) Thread.sleep(25)
            assertEquals(
                1.0,
                page.executeJavaScript<Double>("keys"),
                "The real Fluck callback must admit fullscreen typing",
            )
            onEdt {
                val event = WindowEvent(window, WindowEvent.WINDOW_LOST_FOCUS)
                window.windowFocusListeners.forEach { it.windowLostFocus(event) }
                var exits = 0
                installFullscreenExitShortcut(window) { exits++ }
                val action =
                    window.rootPane
                        .getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                        .get(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0))
                window.rootPane.actionMap
                    .get(action)
                    .actionPerformed(ActionEvent(window, 0, "escape"))
                assertEquals(1, exits)
            }
            press(browser)
            assertKeysStay(browser, 1.0)
            onEdt { window.isVisible = false }
            onEdt { Unit }
            assertNull(fullscreenBrowserInput.focusFor(browser, "unregistered-host"))
        } finally {
            onEdt {
                fullscreenBrowserInput.clear()
                window.dispose()
            }
            engine.close()
        }
    }

    private fun assertKeysStay(
        browser: Browser,
        expected: Double,
    ) {
        val page = browser.mainFrame().orElseThrow()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250)
        do {
            assertEquals(
                expected,
                page.executeJavaScript<Double>("keys"),
                "Rejected keys must remain absent after renderer delivery settles",
            )
            Thread.sleep(25)
        } while (System.nanoTime() < deadline)
    }

    private fun press(browser: Browser) {
        // Native dispatch is asynchronous. Retire each key before changing the observed focus.
        val completed = CountDownLatch(1)
        browser.set(
            ReleaseKeyCallback::class.java,
            ReleaseKeyCallback {
                completed.countDown()
                ReleaseKeyCallback.Response.proceed()
            },
        )
        browser.dispatch(KeyPressed.newBuilder(KeyCode.KEY_CODE_X).keyChar('x').build())
        browser.dispatch(KeyReleased.newBuilder(KeyCode.KEY_CODE_X).build())
        assertTrue(completed.await(5, TimeUnit.SECONDS), "Chromium must finish the key before the next focus event")
    }
}
