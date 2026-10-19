package ai.rever.boss.window

import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Pointer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@EnabledOnOs(OS.MAC)
@EnabledIfEnvironmentVariable(named = "BOSS_TEST_NATIVE_TOOLBAR", matches = "1")
class MacSharingWindowControlsSmokeTest {
    private val controllers = mutableListOf<MacSidebarToolbar>()

    @Test
    @Suppress("LongMethod") // Own two windows, hidden system controls, native dispatch and cleanup together.
    fun `sharing controls work with hidden system buttons and retire after sharing`() {
        val windows = mutableListOf<ComposeWindow>()
        val closes = List(2) { AtomicInteger() }
        try {
            onEdt {
                repeat(2) { index ->
                    val window =
                        ComposeWindow().apply {
                            focusableWindowState = false
                            defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
                            setBounds(80 + index * 40, 400, 640, 240)
                            setContent { }
                            addWindowListener(
                                object : WindowAdapter() {
                                    override fun windowClosing(event: WindowEvent) {
                                        closes[index].incrementAndGet()
                                    }
                                },
                            )
                            isVisible = true
                        }
                    windows.add(window)
                    controllers.add(MacSidebarToolbar(window.windowHandle, {}, {}))
                }
            }
            val sidebar = NativeTitleBarAction("sidebar", "Sidebar", sidebarWidth = 240f, onClick = {})
            controllers.forEach {
                it.remoteInput.enabled = true
                it.update("Synthetic sharing controls", listOf(sidebar), false, -1, emptyMap())
            }
            onAppKit {
                controllers.forEach {
                    it.measure()
                    assertFalse(
                        MacSharingWindowControls.GROUP in it.identifiers(),
                        "Visible originals must prevent duplicates",
                    )
                }
                windows.forEachIndexed { index, window ->
                    val native = Pointer(window.windowHandle)
                    for (kind in 0L..2L) {
                        MacToolbarRuntime.send(
                            MacToolbarRuntime.pointer(native, "standardWindowButton:", kind),
                            "setHidden:",
                            1.toByte(),
                        )
                    }
                    controllers[index].measure()
                }
            }
            val close = awaitTarget(windows[0], "sharing_window_close")
            val other = awaitTarget(windows[1], "sharing_window_close")
            val extras = MacSharingWindowControls.selectors.keys.map { awaitTarget(windows[0], it) }
            listOf(120f, 40f, 240f).forEach { width ->
                controllers[0].update(
                    "Synthetic sharing controls",
                    listOf(sidebar.copy(sidebarWidth = width)),
                    false,
                    -1,
                    emptyMap(),
                )
                onAppKit {
                    assertTrue(
                        MacSharingWindowControls.GROUP in controllers[0].identifiers(),
                        "Geometry-only updates must preserve the sharing controls",
                    )
                }
                MacSharingWindowControls.selectors.keys.forEach { awaitTarget(windows[0], it) }
            }
            onAppKit {
                extras.forEachIndexed { index, target ->
                    val original =
                        MacToolbarRuntime.pointer(
                            Pointer(windows[0].windowHandle),
                            "standardWindowButton:",
                            index.toLong(),
                        )
                    assertEquals(
                        MacToolbarRuntime.pointer(MacToolbarRuntime.pointer(original, "cell"), "class"),
                        MacToolbarRuntime.pointer(MacToolbarRuntime.pointer(target.view, "cell"), "class"),
                        "Use the actual native traffic-light cell",
                    )
                }
                val original = MacToolbarRuntime.pointer(Pointer(windows[0].windowHandle), "standardWindowButton:", 0L)
                MacToolbarRuntime.send(original, "setHidden:", 0.toByte())
                controllers[0].measure()
                assertFalse(
                    MacSharingWindowControls.GROUP in controllers[0].identifiers(),
                    "One visible original hides the fallback",
                )
                assertFalse(close.isCurrent())
                MacToolbarRuntime.send(original, "setHidden:", 1.toByte())
                controllers[0].measure()
            }
            awaitTarget(windows[0], "sharing_window_close")
            onEdt {
                assertFalse(close.owner.activate(close, authorized = { false }))
                assertFalse(close.owner.activate(close, validUntilMillis = System.currentTimeMillis() - 1))
                assertFalse(close.owner.activate(close.copy(view = other.view)))
                assertTrue(close.owner.activate(close))
            }
            awaitCondition("Extra close reaches only its own normal close delegate") { closes[0].get() == 1 }
            assertEquals(0, closes[1].get())
            onAppKit { Unit } // The close delegate can fire before the native dispatch releases its guard.
            onEdt { assertTrue(controllers[0].remoteInput.sharingWindowAction("sharing_window_close")) }
            awaitCondition("Local extra close uses the same native close delegate") { closes[0].get() == 2 }
            onAppKit { Unit }
            val minimize = awaitTarget(windows[0], "sharing_window_minimize")
            onEdt { assertTrue(minimize.owner.activate(minimize)) }
            awaitCondition("Extra minimize works while the system buttons are hidden") {
                onAppKit { MacToolbarRuntime.number(Pointer(windows[0].windowHandle), "isMiniaturized") != 0L }
            }
            assertEquals(0L, onAppKit { MacToolbarRuntime.number(Pointer(windows[1].windowHandle), "isMiniaturized") })
            onEdt {
                assertTrue(MacToolbarInput.recover(windows[0], "restore", Long.MAX_VALUE) { true })
            }
            awaitCondition("Window restores") {
                onAppKit { MacToolbarRuntime.number(Pointer(windows[0].windowHandle), "isMiniaturized") == 0L }
            }
            controllers[0].remoteInput.enabled = false
            controllers[0].update("Sharing stopped", emptyList(), false, -1, emptyMap())
            onAppKit {
                assertFalse(MacSharingWindowControls.GROUP in controllers[0].identifiers())
                assertTrue(MacSharingWindowControls.GROUP in controllers[1].identifiers())
            }
            onEdt {
                assertFalse(close.owner.activate(close))
                assertFalse(controllers[0].remoteInput.sharingWindowAction("sharing_window_close"))
            }
        } finally {
            controllers.forEach { it.close() }
            onAppKit { Unit }
            onEdt { windows.forEach { it.dispose() } }
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
            // NativeSidebarTitleBar polls while sharing, including after deferred AppKit layout.
            onAppKit { controllers.forEach { it.measure() } }
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
