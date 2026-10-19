package ai.rever.boss.window

import ai.rever.boss.config.JxBrowserConfig
import ai.rever.boss.plugin.browser.BrowserAddressBarState
import ai.rever.boss.plugin.browser.BrowserHandle
import ai.rever.boss.plugin.browser.ChromiumToolkitPreload
import ai.rever.boss.plugin.browser.FluckEngine
import ai.rever.boss.sharing.AppBrowserKeyDispatch
import ai.rever.boss.sharing.AppInputEvent
import ai.rever.boss.sharing.onEdt
import ai.rever.boss.utils.WindowFocusManager
import ai.rever.boss.window.MacToolbarRuntime.pointer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.awt.SwingPanel
import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.engine.Engine
import com.teamdev.jxbrowser.engine.EngineOptions
import com.teamdev.jxbrowser.engine.RenderingMode
import com.teamdev.jxbrowser.ui.KeyCode
import com.teamdev.jxbrowser.ui.MouseButton
import com.teamdev.jxbrowser.ui.Point
import com.teamdev.jxbrowser.ui.event.KeyPressed
import com.teamdev.jxbrowser.ui.event.KeyReleased
import com.teamdev.jxbrowser.ui.event.KeyTyped
import com.teamdev.jxbrowser.ui.event.MousePressed
import com.teamdev.jxbrowser.ui.event.MouseReleased
import com.teamdev.jxbrowser.view.swing.BrowserView
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Native URL editor and real Chromium, with an isolated profile and no foreground input. */
@EnabledOnOs(OS.MAC)
@EnabledIfEnvironmentVariable(named = "BOSS_TEST_NATIVE_TOOLBAR", matches = "1")
@EnabledIfEnvironmentVariable(named = "BOSS_TEST_APP_BROWSER_INPUT", matches = "1")
class MacBrowserAddressFocusSmokeTest {
    @TempDir lateinit var temporary: Path

    @Suppress("LongMethod") // One lifecycle exercises both directions of the native focus handoff.
    @ParameterizedTest
    @EnumSource(RenderingMode::class, names = ["OFF_SCREEN", "HARDWARE_ACCELERATED"])
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `URL and webpage never receive the same typing after changing focus`(mode: RenderingMode) {
        val engine = engine(mode)
        val browser = engine.newBrowser()
        val identity = "synthetic-address-browser"
        val window =
            onEdt {
                ComposeWindow().apply {
                    focusableWindowState = false
                    setSize(800, 240)
                    setContent {
                        SwingPanel(factory = { BrowserView.newInstance(browser) }, modifier = Modifier.fillMaxSize())
                    }
                    isVisible = true
                    validate()
                    WindowFocusManager.registerWindow(identity, this)
                }
            }
        val controller = onEdt { MacSidebarToolbar(window.windowHandle, {}, {}) }
        val lostFocus = AtomicInteger()
        try {
            controller.update("", listOf(address(identity, lostFocus)), false, -1, emptyMap())
            onAppKit { Unit }
            FluckEngine.setupKeyboardInterceptor(browser, identity, handle(identity))
            FluckEngine.setupSwingPopupDismissOnPageClick(browser) {
                if (releaseNativeAddressForPage(identity)) browser.focus()
            }
            browser.resize(800, 180)
            browser.navigation().loadUrlAndWait("about:blank", Duration.ofSeconds(10))
            val frame = browser.mainFrame().orElseThrow()
            frame.executeJavaScript<Any?>(
                "document.body.style.margin='0';" +
                    "document.body.innerHTML='<input id=field style=\"width:240px;height:40px\">';" +
                    "globalThis.keys=0;field.addEventListener('keydown',()=>keys++);field.focus();",
            )

            controller.focusAddress()
            onAppKit {
                val editing = controller.addressField.editing
                assertTrue(
                    nativeAddressOwnsBrowserKeys(identity),
                    "view=${editing.view}, editor=${pointer(editing.view, "currentEditor")}, " +
                        "identity=${editing.input?.identity}, active=${editing.active}",
                )
            }
            assertFalse(releaseNativeAddressForPage("another-browser"))
            assertTrue(nativeAddressOwnsBrowserKeys(identity))
            // Force stale Chromium focus, including authenticated remote keys. Native editing still owns them.
            browser.focus()
            type(browser, window, 'z')
            onAppKit {
                assertTrue(
                    editNativeAddress(
                        controller.addressField.editing,
                        AppInputEvent.Key("down", "KeyU", "u", false, false, false, false),
                    ),
                )
            }
            assertEquals("u", onAppKit { addressText(controller) })
            assertEquals("", frame.executeJavaScript<String>("field.value"))
            assertEquals(0.0, frame.executeJavaScript<Double>("keys"))

            val focusLossesBeforeClick = onEdt { lostFocus.get() }
            clickPage(browser)
            await { !nativeAddressOwnsBrowserKeys(identity) }
            onAppKit { assertContentResponder(controller) }
            type(browser, window, 'p')
            await { frame.executeJavaScript<String>("field.value") == "p" }
            assertEquals("u", onAppKit { addressText(controller) })
            onEdt { assertEquals(focusLossesBeforeClick + 1, lostFocus.get()) }

            // Returning to the URL must stop page editing again, including typed events and backspace.
            onAppKit {
                val field = controller.addressField.editing.view
                MacToolbarRuntime.send(pointer(field, "window"), "makeFirstResponder:", field)
                MacToolbarRuntime.send(field, "selectText:", null)
                assertTrue(
                    nativeAddressOwnsBrowserKeys(identity),
                    "editor=${pointer(field, "currentEditor")}, responder=" +
                        pointer(pointer(field, "window"), "firstResponder"),
                )
            }
            browser.focus()
            type(browser, window, 'z')
            AppBrowserKeyDispatch.press(browser, window, KeyPressed.newBuilder(KeyCode.KEY_CODE_BACK).build()) { true }
            onAppKit {
                assertTrue(
                    editNativeAddress(
                        controller.addressField.editing,
                        AppInputEvent.Key("down", "KeyV", "v", false, false, false, false),
                    ),
                )
            }
            assertEquals("v", onAppKit { addressText(controller) })
            assertEquals("p", frame.executeJavaScript<String>("field.value"))

            // A tab switch must retire the old editor, rather than gate an unrelated browser.
            controller.update("", listOf(address("next-browser", lostFocus)), false, -1, emptyMap())
            onAppKit {
                assertFalse(nativeAddressOwnsBrowserKeys(identity))
                assertFalse(nativeAddressOwnsBrowserKeys("next-browser"))
                assertContentResponder(controller)
            }
            controller.focusAddress()
            onAppKit { assertTrue(nativeAddressOwnsBrowserKeys("next-browser")) }
            onAppKit {
                assertTrue(controller.addressField.editing.command("insertNewline:", false))
                assertFalse(nativeAddressOwnsBrowserKeys("next-browser"))
                assertContentResponder(controller)
            }
            controller.focusAddress()
            onAppKit { assertTrue(nativeAddressOwnsBrowserKeys("next-browser")) }
        } finally {
            controller.close()
            onAppKit { assertFalse(nativeAddressOwnsBrowserKeys("next-browser")) }
            onEdt {
                WindowFocusManager.unregisterWindow(identity)
                window.dispose()
            }
            engine.close()
        }
    }

    private fun engine(mode: RenderingMode): Engine {
        val chromium = Path.of(requireNotNull(System.getenv("BOSS_TEST_APP_MEDIA_CHROMIUM_DIR")))
        ChromiumToolkitPreload.preload(chromium)
        return Engine.newInstance(
            EngineOptions
                .newBuilder(mode)
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
    }

    private fun handle(identity: String): BrowserHandle =
        Proxy.newProxyInstance(
            BrowserHandle::class.java.classLoader,
            arrayOf(BrowserHandle::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getId" -> identity
                else -> error("Unexpected browser handle call ${method.name}")
            }
        } as BrowserHandle

    private fun address(
        identity: String,
        lostFocus: AtomicInteger,
    ): NativeTitleBarAction =
        NativeTitleBarAction(
            "browser_url",
            "Address",
            textInput =
                NativeTitleBarTextInput(
                    identity,
                    "https://example.com/",
                    {},
                    {},
                    address =
                        BrowserAddressBarState(
                            "https://example.com/",
                            20,
                            20,
                            null,
                            false,
                            false,
                            0,
                            { _, _, _ -> },
                            {},
                            { lostFocus.incrementAndGet() },
                            {},
                            {},
                        ),
                ),
        ) {}

    private fun addressText(controller: MacSidebarToolbar): String =
        pointer(pointer(controller.addressField.editing.view, "stringValue"), "UTF8String")?.getString(0).orEmpty()

    private fun assertContentResponder(controller: MacSidebarToolbar) {
        val nativeWindow = pointer(controller.addressField.editing.view, "window")
        val responder = pointer(nativeWindow, "firstResponder")
        assertEquals(pointer(nativeWindow, "contentView"), responder)
        assertFalse(responder == nativeWindow, "NSWindow cannot handle ordinary text without ringing the bell")
    }

    private fun type(
        browser: Browser,
        window: ComposeWindow,
        char: Char,
    ) {
        val code = if (char == 'z') KeyCode.KEY_CODE_Z else KeyCode.KEY_CODE_P
        AppBrowserKeyDispatch.press(browser, window, KeyPressed.newBuilder(code).keyChar(char).build()) { true }
        AppBrowserKeyDispatch.type(browser, window, KeyTyped.newBuilder(code).keyChar(char).build()) { true }
        browser.dispatch(KeyReleased.newBuilder(code).build())
    }

    private fun clickPage(browser: Browser) {
        browser.focus()
        browser.dispatch(MousePressed.newBuilder(Point.of(20, 20)).button(MouseButton.PRIMARY).build())
        browser.dispatch(MouseReleased.newBuilder(Point.of(20, 20)).button(MouseButton.PRIMARY).build())
    }

    private fun await(check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (check()) return
            Thread.sleep(25)
        }
        assertTrue(check(), "Native editing and Chromium must finish the focus handoff")
    }

    private fun <T> onAppKit(action: () -> T): T {
        val done = CompletableFuture<T>()
        MacToolbarRuntime.dispatch { runCatching(action).fold(done::complete, done::completeExceptionally) }
        return done.get(10, TimeUnit.SECONDS)
    }
}
