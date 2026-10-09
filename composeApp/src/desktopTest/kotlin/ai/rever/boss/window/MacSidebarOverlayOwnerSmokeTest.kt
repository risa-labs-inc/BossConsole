package ai.rever.boss.window

import ai.rever.boss.components.sidebar.updateSidebarOverlayBounds
import ai.rever.boss.window.MacToolbarRuntime.number
import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.send
import ai.rever.boss.window.MacToolbarRuntime.string
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.Dialog
import java.awt.Rectangle
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Owns only unfocusable test windows. Native moves deliberately bypass AWT and Compose updates. */
@EnabledOnOs(OS.MAC)
@EnabledIfEnvironmentVariable(named = "BOSS_TEST_NATIVE_GLASS", matches = "1")
class MacSidebarOverlayOwnerSmokeTest {
    @Test
    fun `title bar ordering and native dragging keep the sidebar attached above its owner`() {
        val (window, dialog) = createWindows()
        val owner = Pointer(window.windowHandle)
        val child = Pointer(dialog.windowHandle)
        val attachment = MacSidebarOverlayOwner(window.windowHandle, dialog.windowHandle)
        try {
            attachment.attach()
            onAppKit {
                // Compose UI lifecycle tests can leave this test JVM's application hidden.
                // Reveal only the test process, without activating or touching the user's app.
                val app = pointer(MacToolbarRuntime.clazz("NSApplication"), "sharedApplication")
                send(app, "unhideWithoutActivation")
                send(owner, "orderFrontRegardless")
                send(child, "orderFrontRegardless")
            }
            awaitVisible(owner, child)
            onAppKit {
                assertEquals(owner, pointer(child, "parentWindow"))
                assertEquals(number(owner, "level"), number(child, "level"))
                send(owner, "orderFront:", null)
                // NSApplication.orderedWindows excludes utility panels. Read WindowServer's
                // actual order, using only these test windows' IDs and no screen image capture.
                val ordered = windowOrder()
                val childIndex = ordered.indexOf(number(child, "windowNumber"))
                val ownerIndex = ordered.indexOf(number(owner, "windowNumber"))
                assertTrue(
                    childIndex >= 0 && ownerIndex >= 0 && childIndex < ownerIndex,
                    "WindowServer must keep the utility body above its owner: child=$childIndex, owner=$ownerIndex",
                )
                val originalOwner = frame(owner)
                val originalChild = frame(child)
                send(owner, "setFrameOrigin:", SidebarTestPoint(originalOwner[0] + 73.0, originalOwner[1] + 47.0))
                // Same AppKit operation, before AWT componentMoved or a Compose frame can run.
                assertEquals(originalChild[0] + 73.0, frame(child)[0], 0.01)
                assertEquals(originalChild[1] + 47.0, frame(child)[1], 0.01)
            }
            attachment.close()
            onAppKit { assertEquals(null, pointer(child, "parentWindow")) }
            attachment.attach()
            onAppKit { assertEquals(null, pointer(child, "parentWindow"), "disposed ownership must not reattach") }
        } finally {
            attachment.close()
            SwingUtilities.invokeAndWait {
                dialog.dispose()
                window.dispose()
            }
        }
    }

    @Test
    fun `delayed owner screen bounds do not move an attached sidebar back`() {
        val (window, dialog) = createWindows()
        val attachment = MacSidebarOverlayOwner(window.windowHandle, dialog.windowHandle)
        val anchor = SidebarOverlayAnchor(0.0, 40.0, 20.0, 100.0)
        try {
            attachment.updateGeometry(anchor)
            attachment.attach()
            val moved =
                onAppKit {
                    val parent = Pointer(window.windowHandle)
                    val original = frame(parent)
                    send(parent, "setFrameOrigin:", SidebarTestPoint(original[0] + 61.0, original[1] + 39.0))
                    frame(Pointer(dialog.windowHandle))
                }
            attachment.updateGeometry(anchor)
            onAppKit {
                assertEquals(moved.toList(), frame(Pointer(dialog.windowHandle)).toList())
            }
        } finally {
            attachment.close()
            SwingUtilities.invokeAndWait {
                dialog.dispose()
                window.dispose()
            }
        }
    }

    @Test
    fun `disposed sidebar dialogs ignore late AWT bounds updates`() {
        val (owner, child) = createWindows()
        try {
            SwingUtilities.invokeAndWait {
                child.dispose()
                val previous = child.bounds
                updateSidebarOverlayBounds(child, Rectangle(10, 20, 50, 60), Rectangle())
                assertEquals(previous, child.bounds)
                assertTrue(!child.isDisplayable)
            }
        } finally {
            SwingUtilities.invokeAndWait {
                child.dispose()
                owner.dispose()
            }
        }
    }

    private fun createWindows(): Pair<ComposeWindow, ComposeDialog> {
        lateinit var owner: ComposeWindow
        lateinit var child: ComposeDialog
        SwingUtilities.invokeAndWait {
            owner =
                ComposeWindow().apply {
                    title = "BOSS sidebar owner test"
                    isUndecorated = true
                    focusableWindowState = false
                    isAutoRequestFocus = false
                    setBounds(80, 80, 320, 200)
                    setContent { Box(Modifier.fillMaxSize().background(Color.DarkGray)) }
                    isVisible = true
                }
            child =
                ComposeDialog(owner, Dialog.ModalityType.MODELESS).apply {
                    isUndecorated = true
                    isTransparent = true
                    type = java.awt.Window.Type.UTILITY
                    focusableWindowState = false
                    isAutoRequestFocus = false
                    setBounds(80, 120, 100, 140)
                    setContent { Box(Modifier.fillMaxSize().background(Color.Black)) }
                    isVisible = true
                }
        }
        return owner to child
    }

    private fun frame(window: Pointer): DoubleArray =
        Memory(32).use { bytes ->
            send(pointer(window, "valueForKey:", string("frame")), "getValue:size:", bytes, 32L)
            DoubleArray(4) { bytes.getDouble(it * 8L) }
        }

    private fun windowOrder(): List<Long> {
        val graphics = NativeLibrary.getInstance("CoreGraphics")
        val windows = graphics.getFunction("CGWindowListCopyWindowInfo").invokePointer(arrayOf(1, 0))
        try {
            return (0 until number(windows, "count")).map { index ->
                val info = pointer(windows, "objectAtIndex:", index)
                number(pointer(info, "objectForKey:", string("kCGWindowNumber")), "longLongValue")
            }
        } finally {
            NativeLibrary.getInstance("CoreFoundation").getFunction("CFRelease").invokeVoid(arrayOf(windows))
        }
    }

    private fun awaitVisible(
        owner: Pointer,
        child: Pointer,
    ) {
        repeat(100) {
            val shown =
                onAppKit {
                    val ordered = windowOrder()
                    ordered.contains(number(owner, "windowNumber")) && ordered.contains(number(child, "windowNumber"))
                }
            if (shown) return
            Thread.sleep(20)
        }
        val details =
            onAppKit {
                "owner=${number(owner, "windowNumber")}, child=${number(child, "windowNumber")}, " +
                    "visible=${number(child, "isVisible")}, frame=${frame(child).toList()}"
            }
        error("Test windows never appeared in WindowServer: $details")
    }

    @Suppress("TooGenericExceptionCaught") // Native callbacks must report assertions instead of unwinding into AppKit.
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
}

@Structure.FieldOrder("x", "y")
internal class SidebarTestPoint(
    @JvmField var x: Double = 0.0,
    @JvmField var y: Double = 0.0,
) : Structure(),
    Structure.ByValue
