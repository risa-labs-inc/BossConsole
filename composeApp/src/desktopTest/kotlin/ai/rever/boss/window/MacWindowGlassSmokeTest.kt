package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.number
import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.string
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.IntSize
import com.sun.jna.Pointer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.assertEquals

/** Opt-in native smoke test: owns a small unfocusable window, never a user's app window. */
@EnabledOnOs(OS.MAC)
@EnabledIfEnvironmentVariable(named = "BOSS_TEST_NATIVE_GLASS", matches = "1")
class MacWindowGlassSmokeTest {
    @Test
    fun `native material installs changes style and detaches`() {
        val events = LinkedBlockingQueue<Boolean>()
        lateinit var window: ComposeWindow
        lateinit var controller: MacWindowGlass
        val request = GlassRequest(true, IntSize(320, 180), dark = true, clear = false, fullscreen = false)
        SwingUtilities.invokeAndWait {
            window =
                ComposeWindow().apply {
                    title = "BOSS glass test"
                    isUndecorated = true
                    isTransparent = true
                    focusableWindowState = false
                    setSize(320, 180)
                    setContent { }
                    isVisible = true
                }
            controller = MacWindowGlass(window.windowHandle) { events.offer(it) }
        }
        try {
            controller.update(request)
            assertEquals(true, events.poll(10, TimeUnit.SECONDS), "native backdrop should install")
            controller.update(request.copy(dark = false, clear = true))
            assertEquals(true, events.poll(10, TimeUnit.SECONDS), "light clear glass should stay installed")
            controller.update(request.copy(fullscreen = true))
            assertEquals(true, events.poll(10, TimeUnit.SECONDS), "fullscreen glass should stay installed")
            assertEquals(true, hasFullscreenBackdrop(window.windowHandle), "fullscreen needs an in-window backdrop")
            controller.update(request)
            assertEquals(true, events.poll(10, TimeUnit.SECONDS), "windowed glass should stay installed")
            assertEquals(false, hasFullscreenBackdrop(window.windowHandle), "windowed mode must detach wallpaper")
            controller.update(request.copy(fullscreen = true))
            assertEquals(true, events.poll(10, TimeUnit.SECONDS))
            controller.update(request.copy(enabled = false))
            assertEquals(false, events.poll(10, TimeUnit.SECONDS), "disabled glass must detach")
            assertEquals(false, hasFullscreenBackdrop(window.windowHandle), "disabled glass must detach wallpaper")
        } finally {
            controller.close()
            SwingUtilities.invokeAndWait { window.dispose() }
        }
    }

    private fun hasFullscreenBackdrop(handle: Long): Boolean? {
        val result = LinkedBlockingQueue<Boolean>()
        MacToolbarRuntime.dispatch {
            val content = pointer(Pointer(handle), "contentView")
            val siblings = pointer(pointer(content, "superview"), "subviews")
            result.offer(
                (0 until number(siblings, "count")).any { index ->
                    val view = pointer(siblings, "objectAtIndex:", index)
                    val identifier = pointer(view, "identifier")
                    number(identifier, "isEqualToString:", string("boss.fullscreen.wallpaper")) and 0xffL != 0L
                },
            )
        }
        return result.poll(10, TimeUnit.SECONDS)
    }
}
