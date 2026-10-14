package ai.rever.boss.window

import ai.rever.boss.sharing.AppInputEvent
import ai.rever.boss.sharing.AwtAppInputSink
import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Pointer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@EnabledOnOs(OS.MAC)
@EnabledIfEnvironmentVariable(named = "BOSS_TEST_NATIVE_FULLSCREEN", matches = "1")
class MacFullscreenToolbarInputSmokeTest {
    private val controllers = mutableListOf<MacSidebarToolbar>()

    @Test
    @Suppress("LongMethod") // Own the fullscreen transition, scoped input and guaranteed cleanup together.
    fun `fullscreen toolbar keeps scoped actions and address editing`() {
        val window =
            onEdt {
                ComposeWindow().apply {
                    focusableWindowState = false
                    rootPane.putClientProperty("apple.awt.fullscreenable", true)
                    setBounds(80, 400, 1100, 240)
                    setContent { }
                    isVisible = true
                }
            }
        val menus = mutableListOf<NativeToolbarMenuRequest>()
        val controller = MacSidebarToolbar(window.windowHandle, {}, {}, menus::add).also { controllers.add(it) }
        val sink = AwtAppInputSink(window, requireForeground = false)
        val handle = Pointer(window.windowHandle)
        val clicks = AtomicInteger()
        val submitted = mutableListOf<String>()

        fun fullscreen() = onAppKit { MacToolbarRuntime.number(handle, "styleMask") and (1L shl 14) != 0L }
        try {
            controller.remoteInput.enabled = true
            controller.update(
                "Synthetic fullscreen toolbar",
                listOf(
                    NativeTitleBarAction("sidebar", "Sidebar", "sidebar.left") { clicks.incrementAndGet() },
                    NativeTitleBarAction("search", "Search", "magnifyingglass") { clicks.incrementAndGet() },
                    NativeTitleBarAction(
                        "tools",
                        "Tools",
                        "wrench",
                        menu = listOf(NativeTitleBarAction("test-tool", "Test action") { clicks.incrementAndGet() }),
                        onClick = {},
                    ),
                    NativeTitleBarAction(
                        "browser_url",
                        "Address",
                        textInput =
                            NativeTitleBarTextInput("fullscreen-browser", "initial.example", { submitted.add(it) }, {}),
                        onClick = {},
                    ),
                ),
                false,
                -1,
                emptyMap(),
            )
            awaitTarget(window, "browser_url")
            onAppKit { MacToolbarRuntime.send(handle, "toggleFullScreen:", null) }
            awaitCondition("Window must enter real native fullscreen") {
                fullscreen() && controller.remoteInput.fullscreenEntries > 0 &&
                    !controller.remoteInput.fullscreenTransitioning
            }
            onAppKit { controller.measure() }
            val snapshot =
                onEdt {
                    ai.rever.boss.sharing
                        .captureSurfaceSnapshot(window)
                }
            assertEquals(2, snapshot.surfaces.size, "Fullscreen capture includes its exact native toolbar")
            assertTrue(snapshot.y < window.y)
            sink.updateSurfaces(snapshot)
            if (System.getenv("BOSS_TEST_APP_CONTINUOUS_CAPTURE") == "1") verifyContinuousCapture(snapshot)
            val search = awaitTarget(window, "search")
            val tools = awaitTarget(window, "tools")
            val sidebar = awaitTarget(window, "sidebar")
            val address = awaitTarget(window, "browser_url")
            onEdt {
                click(sink, window, sidebar)
                assertEquals(1, clicks.get())
                click(sink, window, search)
                click(sink, window, tools)
                menus.single().select("test-tool")
                assertEquals(3, clicks.get())
                click(sink, window, address)
                for (char in "fullscreen.test") key(sink, "Key${char.uppercaseChar()}", char.toString())
                key(sink, "Enter", "Enter")
            }
            onEdt { assertEquals(listOf("fullscreen.test"), submitted) }
            onEdt { assertTrue(sink.apply(AppInputEvent.Window("exit-fullscreen"))) }
            awaitCondition("Return to windowed mode") {
                !fullscreen() && !controller.remoteInput.fullscreenTransitioning
            }
            awaitTarget(window, "browser_url")
            assertEquals(
                1,
                onEdt {
                    ai.rever.boss.sharing
                        .captureSurfaceSnapshot(window)
                        .surfaces.size
                },
            )
        } finally {
            if (fullscreen()) {
                onAppKit { MacToolbarRuntime.send(handle, "toggleFullScreen:", null) }
                awaitCondition("Cleanup fullscreen test window") {
                    !fullscreen() && !controller.remoteInput.fullscreenTransitioning
                }
            }
            onEdt {
                sink.releaseAll()
                controller.close()
            }
            onAppKit { Unit }
            onEdt { window.dispose() }
        }
    }

    private fun verifyContinuousCapture(snapshot: ai.rever.boss.sharing.AppSurfaceSnapshot) {
        val size =
            ai.rever.boss.sharing
                .appCaptureFrameSize(snapshot.width, snapshot.height, 1920)
        val frames =
            snapshot.surfaces.map { surface ->
                val geometry = surface.geometry
                val scaled =
                    ai.rever.boss.sharing
                        .appCaptureSurfaceFrameSize(geometry, snapshot.logicalWidth, size.width)
                ai.rever.boss.sharing.MacWindowStreamPlatform
                    .open(
                        geometry.nativeHandle,
                        scaled.width,
                        scaled.height,
                        { 30 },
                        "BGRA",
                        geometry,
                        null,
                    ).use { stream ->
                        var frame: ai.rever.boss.sharing.AppRawWindowFrame? = null
                        awaitCondition("Continuous native surface must deliver pixels") {
                            frame = stream.latest()
                            frame != null
                        }
                        checkNotNull(frame)
                    }
            }
        val chrome = frames.last()
        assertTrue(chrome.bgra.toSet().size > 8, "Toolbar capture contains native controls, not a blank strip")
        val composite =
            ai.rever.boss.sharing
                .composeRawWindowFrames(snapshot, frames)
        assertEquals(size.width, composite.width)
        assertEquals(size.height, composite.height)
        // The actual native toolbar starts at the top of the composed full-window video.
        assertEquals(chrome.bgra.take(chrome.width * 4), composite.bgra.take(chrome.width * 4))
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
            onAppKit { controllers.forEach { it.measure() } }
            val target =
                onEdt {
                    (-100 until 80 step 2).firstNotNullOfOrNull { y ->
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
        val snapshot =
            ai.rever.boss.sharing
                .captureSurfaceSnapshot(window)
        val x = (window.x + target.bounds.left + target.bounds.width / 2.0 - snapshot.x) / (snapshot.logicalWidth - 1)
        val y = (window.y + target.bounds.top + target.bounds.height / 2.0 - snapshot.y) / (snapshot.logicalHeight - 1)
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
