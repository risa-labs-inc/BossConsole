package ai.rever.boss.window

import ai.rever.boss.sharing.AppInputEvent
import ai.rever.boss.sharing.AwtAppInputSink
import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Pointer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Two synthetic unfocusable windows exercise the production scoped-input path, without global OS input. */
@EnabledOnOs(OS.MAC)
@EnabledIfEnvironmentVariable(named = "BOSS_TEST_NATIVE_TOOLBAR", matches = "1")
class MacToolbarRemoteInputSmokeTest {
    @Test
    @Suppress("LongMethod") // One lifetime owns both native controllers, input sinks and their cleanup.
    fun `background title bar control edits and submits its own address and retires with the toolbar`() {
        val windows = mutableListOf<ComposeWindow>()
        val controllers = mutableListOf<MacSidebarToolbar>()
        val sinks = mutableListOf<AwtAppInputSink>()
        val clicks = List(2) { AtomicInteger() }
        val toolClicks = List(2) { AtomicInteger() }
        val localOnlyClicks = AtomicInteger()
        val menus = mutableListOf<NativeToolbarMenuRequest>()
        val submitted = mutableListOf<String>()
        try {
            onEdt {
                repeat(2) { index ->
                    val window =
                        ComposeWindow().apply {
                            focusableWindowState = false
                            setBounds(40 + index * 40, 400 + index * 40, 1100, 180)
                            setContent { }
                            isVisible = true
                        }
                    windows.add(window)
                    val controller = MacSidebarToolbar(window.windowHandle, {}, {}, menus::add)
                    controller.remoteInput.enabled = true
                    controllers.add(controller)
                    sinks.add(AwtAppInputSink(window, requireForeground = false))
                    controller.update(
                        "Synthetic remote toolbar",
                        listOf(
                            NativeTitleBarAction(
                                "sidebar",
                                "Sidebar",
                                symbol = "sidebar.left",
                                onClick = { clicks[index].incrementAndGet() },
                            ),
                            NativeTitleBarAction(
                                "browser_url",
                                "Address",
                                textInput =
                                    NativeTitleBarTextInput(
                                        "synthetic-browser-$index",
                                        "initial.example",
                                        { submitted.add(it) },
                                        {},
                                    ),
                                onClick = {},
                            ),
                            NativeTitleBarAction(
                                "search",
                                "Search",
                                symbol = "magnifyingglass",
                                onClick = { toolClicks[index].incrementAndGet() },
                            ),
                            NativeTitleBarAction(
                                "tools",
                                "Tools",
                                symbol = "wrench",
                                menu =
                                    listOf(
                                        NativeTitleBarAction(
                                            "synthetic-tool",
                                            "Synthetic tool",
                                            onClick = { toolClicks[index].incrementAndGet() },
                                        ),
                                        NativeTitleBarAction(
                                            "start-capture",
                                            "Share BossConsole Window",
                                            localOnly = true,
                                            onClick = { localOnlyClicks.incrementAndGet() },
                                        ),
                                    ),
                                onClick = {},
                            ),
                            NativeTitleBarAction("toolbox", "Toolbox", symbol = "shippingbox", onClick = {}),
                            NativeTitleBarAction(
                                "local-only",
                                "Local capture consent",
                                symbol = "video",
                                localOnly = true,
                                onClick = { localOnlyClicks.incrementAndGet() },
                            ),
                        ),
                        dark = false,
                        background = -1,
                        icons = emptyMap(),
                    )
                }
            }
            val sidebar = awaitTarget(windows[0], "sidebar")
            val address = awaitTarget(windows[0], "browser_url")
            val search = awaitTarget(windows[0], "search")
            val tools = awaitTarget(windows[0], "tools")
            val localOnly = awaitTarget(windows[0], "local-only")
            awaitTarget(windows[1], "browser_url")
            onEdt {
                assertFalse(windows[0].isFocused)
                click(sinks[0], windows[0], sidebar)
                assertEquals(1, clicks[0].get())
                assertEquals(0, clicks[1].get())
                click(sinks[0], windows[0], search)
                assertEquals(1, toolClicks[0].get())
                assertEquals(0, toolClicks[1].get())
                click(sinks[0], windows[0], tools)
                assertEquals(1, menus.size)
                menus.single().select("synthetic-tool")
                assertEquals(2, toolClicks[0].get())
                val captureEntry = menus.single().entries.single { it.id == "start-capture" }
                assertFalse(captureEntry.enabled)
                menus.single().select("start-capture")
                assertFalse(localOnly.owner.activate(localOnly))
                assertEquals(
                    0,
                    localOnlyClicks.get(),
                    "Remote menu selection and direct clicks cannot authorize capture",
                )
                click(sinks[0], windows[0], address)
                for (char in "example.testx") key(sinks[0], "Key${char.uppercaseChar()}", char.toString())
                key(sinks[0], "Backspace", "Backspace")
                key(sinks[0], "Enter", "Enter")
            }
            onEdt {
                assertEquals(listOf("example.test"), submitted)
                assertFalse(windows[0].isFocused)
            }
            // Replacing a browser invalidates the old borrowed field and key target.
            controllers[0].update(
                "Synthetic replacement",
                listOf(
                    NativeTitleBarAction("sidebar", "Sidebar", symbol = "sidebar.left", onClick = {}),
                ),
                dark = false,
                background = -1,
                icons = emptyMap(),
            )
            onAppKit { Unit }
            onEdt {
                assertFalse(address.isCurrent())
                assertFalse(sinks[0].apply(AppInputEvent.Key("down", "KeyA", "a", false, false, false, false)))
                controllers[0].close()
                assertFalse(sidebar.isCurrent())
                assertFalse(sidebar.owner.activate(sidebar))
                menus.single().select("synthetic-tool")
                assertEquals(2, toolClicks[0].get(), "Retired toolbar menus cannot dispatch old callbacks")
            }
            var expiredRan = false
            assertFalse(
                scopedNativeToolbarCall(System.currentTimeMillis() - 1) {
                    expiredRan = true
                    true
                },
            )
            onAppKit { Unit }
            assertFalse(expiredRan)
        } finally {
            onEdt {
                sinks.forEach { it.releaseAll() }
                controllers.forEach { it.close() }
            }
            onAppKit { Unit }
            onEdt { windows.forEach { it.dispose() } }
        }
    }

    @Test
    @Suppress("LongMethod") // One lifetime owns the native controls and cleanup of two test-only windows.
    fun `traffic lights retain local actions and remote clicks are scoped and cancelable`() {
        val windows = mutableListOf<ComposeWindow>()
        val controllers = mutableListOf<MacSidebarToolbar>()
        val sinks = mutableListOf<AwtAppInputSink>()
        val closed = List(2) { AtomicInteger() }
        try {
            onEdt {
                repeat(2) { index ->
                    val window =
                        ComposeWindow().apply {
                            focusableWindowState = false
                            defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
                            rootPane.putClientProperty("apple.awt.fullscreenable", false)
                            setBounds(60 + index * 40, 400 + index * 40, 640, 240)
                            setContent { }
                            addWindowListener(
                                object : WindowAdapter() {
                                    override fun windowClosing(event: WindowEvent) {
                                        closed[index].incrementAndGet()
                                    }
                                },
                            )
                            isVisible = true
                        }
                    windows.add(window)
                    val controller = MacSidebarToolbar(window.windowHandle, {}, {}, {})
                    controllers.add(controller)
                    sinks.add(AwtAppInputSink(window, requireForeground = false))
                    controller.update("Synthetic traffic lights", emptyList(), false, -1, emptyMap())
                }
            }
            val before = onAppKit { nativeButtons(windows[0]) }
            onAppKit {
                controllers.forEach {
                    it.remoteInput.enabled = true
                    it.measure()
                }
            }
            val close = awaitTarget(windows[0], "window_close")
            val minimize = awaitTarget(windows[0], "window_minimize")
            val zoom = awaitTarget(windows[0], "window_zoom")
            val otherClose = awaitTarget(windows[1], "window_close")
            assertEquals(
                before,
                onAppKit { nativeButtons(windows[0]) },
                "Sharing must not replace local button actions",
            )

            onEdt {
                assertFalse(close.owner.activate(close, context = true))
                assertFalse(close.owner.activate(close, validUntilMillis = System.currentTimeMillis() - 1))
                assertFalse(close.owner.activate(close.copy(view = otherClose.view)))
                assertFalse(close.owner.activate(close.copy(windowButton = 1L)))
                val x = (close.bounds.left + close.bounds.width / 2.0) / (windows[0].width - 1)
                val y = (close.bounds.top + close.bounds.height / 2.0) / (windows[0].height - 1)
                assertTrue(sinks[0].apply(AppInputEvent.Pointer("down", x, y, 0)))
                sinks[0].releaseAll()
                assertFalse(sinks[0].apply(AppInputEvent.Pointer("up", x, y, 0)))
            }
            onAppKit { MacToolbarRuntime.send(close.view, "setEnabled:", 0.toByte()) }
            onEdt { assertFalse(close.owner.activate(close)) }
            onAppKit { MacToolbarRuntime.send(close.view, "setEnabled:", 1.toByte()) }
            onEdt {
                assertEquals(0, closed[0].get())
                click(sinks[0], windows[0], close)
            }
            awaitCondition("Remote close must reach this window's normal close delegate") { closed[0].get() == 1 }
            assertEquals(0, closed[1].get())
            // Exercise the unchanged local native button action while remote input remains enabled.
            onAppKit { MacToolbarRuntime.send(close.view, "performClick:", null) }
            awaitCondition("Local close remains operational while sharing") { closed[0].get() == 2 }

            onEdt { click(sinks[0], windows[0], minimize) }
            awaitCondition("Remote minimize must operate only on its owner") {
                onAppKit { MacToolbarRuntime.number(Pointer(windows[0].windowHandle), "isMiniaturized") != 0L }
            }
            assertEquals(0L, onAppKit { MacToolbarRuntime.number(Pointer(windows[1].windowHandle), "isMiniaturized") })
            onEdt {
                assertFalse(
                    sinks[0].apply(AppInputEvent.Pointer("down", 0.5, 0.5, 0)),
                    "Minimized windows reject coordinate input",
                )
                assertFalse(sinks[0].apply(AppInputEvent.Key("down", "KeyA", "a", false, false, false, false)))
                assertFalse(sinks[0].apply(AppInputEvent.Window("restore"), System.currentTimeMillis() - 1))
                assertTrue(sinks[0].apply(AppInputEvent.Window("restore")))
            }
            awaitCondition("Test window restores before zoom") {
                onAppKit { MacToolbarRuntime.number(Pointer(windows[0].windowHandle), "isMiniaturized") == 0L }
            }
            onEdt {
                assertTrue(sinks[0].apply(AppInputEvent.Window("restore")), "Repeated restore is harmless")
            }
            onAppKit { Unit }
            onEdt {
                assertTrue(
                    sinks[0].apply(AppInputEvent.Window("exit-fullscreen")),
                    "Exiting windowed mode cannot enter fullscreen",
                )
                assertFalse(sinks[0].apply(AppInputEvent.Window("start-capture")))
            }
            assertEquals(
                0L,
                onAppKit { MacToolbarRuntime.number(Pointer(windows[0].windowHandle), "styleMask") and (1L shl 14) },
            )
            val zoomed = onAppKit { MacToolbarRuntime.number(Pointer(windows[0].windowHandle), "isZoomed") }
            val beforeBounds = onEdt { windows[0].bounds }
            val currentZoom = awaitTarget(windows[0], "window_zoom")
            onEdt {
                assertTrue(
                    currentZoom.owner.activate(currentZoom),
                    "Native green button must remain enabled and current",
                )
            }
            awaitCondition("Remote green button must zoom, resize, or enter fullscreen") {
                val nativeChanged =
                    onAppKit {
                        val native = Pointer(windows[0].windowHandle)
                        MacToolbarRuntime.number(native, "isZoomed") != zoomed ||
                            MacToolbarRuntime.number(native, "styleMask") and (1L shl 14) != 0L
                    }
                nativeChanged || onEdt { windows[0].bounds != beforeBounds }
            }
            awaitCondition("Native green transition settles") { !controllers[0].remoteInput.fullscreenTransitioning }
            onEdt { sinks[0].apply(AppInputEvent.Window("exit-fullscreen")) }
            awaitCondition("Green test returns to windowed mode") {
                !controllers[0].remoteInput.fullscreenTransitioning &&
                    onAppKit {
                        MacToolbarRuntime.number(Pointer(windows[0].windowHandle), "styleMask") and (1L shl 14) == 0L
                    }
            }
            onEdt {
                controllers[0].close()
                assertFalse(close.owner.activate(close))
                assertFalse(zoom.isCurrent())
                assertFalse(sinks[0].apply(AppInputEvent.Window("restore")))
            }
        } finally {
            onEdt {
                sinks.forEach { it.releaseAll() }
                controllers.forEach { it.close() }
            }
            onAppKit { Unit }
            onEdt { windows.forEach { it.dispose() } }
        }
    }

    private fun nativeButtons(window: ComposeWindow): List<List<Pointer?>> =
        (0L..2L).map { kind ->
            val button = MacToolbarRuntime.pointer(Pointer(window.windowHandle), "standardWindowButton:", kind)
            listOf(button, MacToolbarRuntime.pointer(button, "target"), MacToolbarRuntime.pointer(button, "action"))
        }

    @Test
    fun `queued native window action retires before dispatch and permits only one pending operation`() {
        val pending = AtomicBoolean()
        val authorized = AtomicBoolean(true)
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val delivered = AtomicInteger()
        try {
            val accepted =
                onEdt {
                    queueNativeWindowAction(
                        System.currentTimeMillis() + 1000,
                        pending,
                        authorized::get,
                        {
                            MacToolbarRuntime.dispatch {
                                entered.countDown()
                                resume.await(2, TimeUnit.SECONDS)
                            }
                            true
                        },
                        { delivered.incrementAndGet() },
                    )
                }
            assertTrue(accepted)
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(
                queueNativeWindowAction(
                    System.currentTimeMillis() + 1000,
                    pending,
                    authorized::get,
                    { true },
                    { delivered.incrementAndGet() },
                ),
            )
            authorized.set(false)
            resume.countDown()
            onAppKit { Unit }
            assertEquals(0, delivered.get(), "Revocation before AppKit execution must drop the queued action")
            assertFalse(pending.get())
        } finally {
            resume.countDown()
            onAppKit { Unit }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "BOSS_TEST_NATIVE_FULLSCREEN", matches = "1")
    @Suppress("LongMethod") // The real fullscreen transition and guaranteed native cleanup share one fixture.
    fun `explicit recovery exits real native fullscreen without a visible traffic light target`() {
        val window =
            onEdt {
                ComposeWindow().apply {
                    focusableWindowState = false
                    rootPane.putClientProperty("apple.awt.fullscreenable", true)
                    setBounds(80, 400, 640, 240)
                    setContent { }
                    isVisible = true
                }
            }
        val controller = MacSidebarToolbar(window.windowHandle, {}, {}, {})
        val sink = AwtAppInputSink(window, requireForeground = false)
        val handle = Pointer(window.windowHandle)

        fun fullscreen() = onAppKit { MacToolbarRuntime.number(handle, "styleMask") and (1L shl 14) != 0L }
        try {
            controller.remoteInput.enabled = true
            controller.update("Synthetic fullscreen recovery", emptyList(), false, -1, emptyMap())
            onAppKit {
                for (kind in 0L..2L) {
                    MacToolbarRuntime.send(
                        MacToolbarRuntime.pointer(handle, "standardWindowButton:", kind),
                        "setHidden:",
                        1.toByte(),
                    )
                }
                controller.measure()
            }
            val fullscreenControl = awaitTarget(window, "sharing_window_fullscreen")
            onEdt { assertTrue(fullscreenControl.owner.activate(fullscreenControl)) }
            awaitCondition("Synthetic test window must finish entering real AppKit fullscreen") {
                fullscreen() && controller.remoteInput.fullscreenEntries > 0 &&
                    !controller.remoteInput.fullscreenTransitioning
            }
            onAppKit {
                // Hiding only this synthetic window's buttons proves recovery uses exact
                // window identity, not a visible button target, title, or global responder.
                for (kind in 0L..2L) {
                    MacToolbarRuntime.send(
                        MacToolbarRuntime.pointer(handle, "standardWindowButton:", kind),
                        "setHidden:",
                        1.toByte(),
                    )
                }
                controller.measure()
            }
            onEdt {
                assertTrue(sink.apply(AppInputEvent.Window("exit-fullscreen")))
                sink.apply(AppInputEvent.Window("exit-fullscreen")) // A transition may reject this duplicate.
            }
            awaitCondition("Authenticated scoped command must exit native fullscreen") {
                !fullscreen() && !controller.remoteInput.fullscreenTransitioning
            }
            onEdt { assertTrue(sink.apply(AppInputEvent.Window("exit-fullscreen"))) }
            assertFalse(fullscreen(), "Windowed exit remains a no-op")
        } finally {
            try {
                if (fullscreen()) {
                    onAppKit { MacToolbarRuntime.send(handle, "toggleFullScreen:", null) }
                    awaitCondition("Test cleanup leaves fullscreen") {
                        !fullscreen() && !controller.remoteInput.fullscreenTransitioning
                    }
                }
            } finally {
                onEdt {
                    sink.releaseAll()
                    controller.close()
                }
                onAppKit { Unit }
                onEdt { window.dispose() }
            }
        }
    }

    private fun awaitCondition(
        message: String,
        check: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (check()) return
            Thread.sleep(25)
        }
        assertTrue(check(), message)
    }

    private fun awaitTarget(
        window: ComposeWindow,
        id: String,
    ): MacToolbarInputTarget {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val target =
                onEdt {
                    (0 until 80 step 2).firstNotNullOfOrNull { y ->
                        (0 until window.width step 2).firstNotNullOfOrNull { x ->
                            MacToolbarInput.hit(window, x, y)?.takeIf { it.id == id }
                        }
                    }
                }
            if (target != null) return target
            onAppKit { Unit }
            Thread.sleep(25)
        }
        error("Native control $id must publish window-scoped geometry")
    }

    private fun click(
        sink: AwtAppInputSink,
        window: ComposeWindow,
        target: MacToolbarInputTarget,
    ) {
        val x = (target.bounds.left + target.bounds.width / 2.0) / (window.width - 1)
        val y = (target.bounds.top + target.bounds.height / 2.0) / (window.height - 1)
        assertTrue(sink.apply(AppInputEvent.Pointer("down", x, y, 0)), "Native ${target.id} down")
        assertTrue(sink.apply(AppInputEvent.Pointer("up", x, y, 0)), "Native ${target.id} up")
    }

    private fun key(
        sink: AwtAppInputSink,
        code: String,
        value: String,
    ) {
        val event = AppInputEvent.Key("down", code, value, false, false, false, false)
        assertTrue(sink.apply(event), "Native key $code")
        assertTrue(sink.apply(event.copy(action = "up")))
    }

    private fun <T> onEdt(action: () -> T): T {
        val result = CompletableFuture<T>()
        SwingUtilities.invokeAndWait { runCatching(action).fold(result::complete, result::completeExceptionally) }
        return result.get(10, TimeUnit.SECONDS)
    }

    private fun <T> onAppKit(action: () -> T): T {
        val result = CompletableFuture<T>()
        MacToolbarRuntime.dispatch { runCatching(action).fold(result::complete, result::completeExceptionally) }
        return result.get(10, TimeUnit.SECONDS)
    }
}
