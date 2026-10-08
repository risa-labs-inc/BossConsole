package ai.rever.boss.window

import ai.rever.boss.components.sidebar.SidebarOverlayWindow
import ai.rever.boss.plugin.browser.LocalAwtWindow
import ai.rever.boss.sharing.onEdt
import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.send
import ai.rever.boss.window.MacToolbarRuntime.string
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.Structure
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Runs the real composable, including native realization and owned-dialog showing. */
@OptIn(ExperimentalTestApi::class)
@EnabledOnOs(OS.MAC)
@EnabledIfEnvironmentVariable(named = "BOSS_TEST_NATIVE_GLASS", matches = "1")
class SidebarOverlayNativeLifecycleTest {
    @Test
    fun `the actual hover overlay attaches after realization and remains anchored during native resize`(): Unit =
        runComposeUiTest {
            val fixture = SidebarFixture()
            setContent { SidebarWindow(fixture) }
            waitUntil(timeoutMillis = 10_000) {
                onEdt { fixture.owner?.isShowing == true && fixture.child?.isShowing == true }
            }
            val (owner, child) =
                onEdt {
                    Pointer(assertNotNull(fixture.owner).windowHandle) to
                        Pointer(assertNotNull(fixture.child).windowHandle)
                }
            waitForIdle()
            onAppKit {
                assertNativeResize(owner, child)
            }
            waitForIdle()
            onAppKit { assertAnchored(owner, child, 100.0) }
            // Recompose with deliberately stale body height after the owner's native resize.
            runOnIdle { fixture.width = 55.dp }
            // The real Window has its own composition, outside the test scene frame clock.
            waitUntil(timeoutMillis = 10_000) { onAppKit { frame(child)[2] == 55.0 } }
            waitForIdle()
            onAppKit {
                assertAnchored(owner, child, 55.0)
                val current = frame(owner)
                send(
                    owner,
                    "setFrame:display:",
                    SidebarLifecycleRect(
                        current[0] + 25,
                        current[1] + 20,
                        current[2] - 25,
                        current[3] - 50,
                    ),
                    0.toByte(),
                )
                assertAnchored(owner, child, 55.0)
                val moved = frame(owner)
                send(owner, "setFrameOrigin:", SidebarTestPoint(moved[0] + 30, moved[1] - 10))
                assertAnchored(owner, child, 55.0)
            }
            waitForIdle()
            onAppKit { assertAnchored(owner, child, 55.0) }
        }

    private fun assertNativeResize(
        owner: Pointer,
        child: Pointer,
    ) {
        assertEquals(owner, pointer(child, "parentWindow"), "the composed body must actually attach")
        val beforeOwner = frame(owner)
        val beforeChild = frame(child)
        val resized =
            SidebarLifecycleRect(
                beforeOwner[0] - 60,
                beforeOwner[1] - 30,
                beforeOwner[2] + 60,
                beforeOwner[3] + 90,
            )
        send(owner, "setFrame:display:", resized, 0.toByte())
        val afterChild = frame(child)
        assertEquals(beforeChild[0] - 60, afterChild[0], 0.01, "left-edge resize must keep body attached")
        assertEquals(
            beforeChild[3] + 90,
            afterChild[3],
            0.01,
            "height must resize in the same AppKit operation",
        )
        val oldTopGap = beforeOwner[1] + beforeOwner[3] - beforeChild[1] - beforeChild[3]
        val afterOwner = frame(owner)
        val newTopGap = afterOwner[1] + afterOwner[3] - afterChild[1] - afterChild[3]
        assertEquals(oldTopGap, newTopGap, 0.01, "body must stay directly below its title-bar header")
    }

    private fun assertAnchored(
        owner: Pointer,
        child: Pointer,
        width: Double,
    ) {
        val parent = frame(owner)
        val body = frame(child)
        assertEquals(owner, pointer(child, "parentWindow"))
        assertEquals(parent[0], body[0], 0.01, "left edge must follow the current owner")
        assertEquals(parent[1] + 20, body[1], 0.01, "bottom inset must remain fixed")
        assertEquals(parent[3] - 60, body[3], 0.01, "height must follow the current owner")
        assertEquals(width, body[2], 0.01, "animation width must not restore stale screen bounds")
    }

    @Composable
    private fun SidebarWindow(fixture: SidebarFixture) {
        Window(
            onCloseRequest = {},
            title = "BOSS sidebar lifecycle fixture",
            state = rememberWindowState(width = 320.dp, height = 200.dp, position = WindowPosition(80.dp, 80.dp)),
            undecorated = true,
            focusable = false,
            icon = BossWindowIcon.painter,
        ) {
            ApplyBossWindowIcon(window)
            DisposableEffect(window) {
                fixture.owner = window
                onDispose { }
            }
            Box(Modifier.fillMaxSize().background(Color.DarkGray)) {
                CompositionLocalProvider(LocalAwtWindow provides window) {
                    SidebarOverlayWindow(DpSize(fixture.width, 140.dp), IntRect(0, 40, 100, 180), bottomInset = 20.dp) {
                        val child = LocalAwtWindow.current as ComposeDialog
                        DisposableEffect(child) {
                            fixture.child = child
                            onDispose { }
                        }
                        Box(Modifier.fillMaxSize().background(Color.Black))
                    }
                }
            }
        }
    }

    private fun frame(window: Pointer): DoubleArray =
        Memory(32).use { bytes ->
            send(pointer(window, "valueForKey:", string("frame")), "getValue:size:", bytes, 32L)
            DoubleArray(4) { bytes.getDouble(it * 8L) }
        }

    @Suppress("TooGenericExceptionCaught")
    private fun <T> onAppKit(action: () -> T): T {
        val result = CompletableFuture<T>()
        MacToolbarRuntime.dispatch {
            try {
                result.complete(action())
            } catch (failure: Throwable) {
                result.completeExceptionally(failure)
            }
        }
        return result.get(10, TimeUnit.SECONDS)
    }

    private class SidebarFixture {
        var width by mutableStateOf(100.dp)
        var owner: ComposeWindow? = null
        var child: ComposeDialog? = null
    }
}

@Structure.FieldOrder("x", "y", "width", "height")
internal class SidebarLifecycleRect(
    @JvmField var x: Double = 0.0,
    @JvmField var y: Double = 0.0,
    @JvmField var width: Double = 0.0,
    @JvmField var height: Double = 0.0,
) : Structure(),
    Structure.ByValue
