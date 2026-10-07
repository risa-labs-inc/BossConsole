package ai.rever.boss.plugin.browser

import ai.rever.boss.sharing.AppBrowserMenuDispatch
import com.teamdev.jxbrowser.frame.Frame
import com.teamdev.jxbrowser.ui.Point
import org.junit.jupiter.api.Test
import java.awt.event.MouseEvent
import java.lang.reflect.Proxy
import javax.swing.JPanel
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class RemoteBrowserMenuContextTest {
    @Test fun `remote anchor preserves frame authority across form enrichment and popup geometry changes`() {
        val frame = fakeFrame()
        val authority = BrowserMenuContextAuthority()
        var current = true
        val event = MouseEvent(JPanel(), MouseEvent.MOUSE_PRESSED, 0, 0, 20, 30, 420, 530, 1, true, MouseEvent.BUTTON3)
        val click = AppBrowserMenuDispatch.Click(event, { current }, Point.of(20, 30))
        val context = authority.capture(frame, remoteClick = click)
        assertSame(frame, authority.resolve(context))
        val anchor = assertIs<MouseEvent>(context)
        assertEquals(java.awt.Point(420, 530), anchor.locationOnScreen)
        val info = BrowserContextMenuInfo(isEditable = true, menuContext = context).withFormField(null)
        assertSame(context, info.menuContext)
        assertNull(BrowserMenuContextAuthority().resolve(context), "A token cannot act on a different browser")
        current = false
        assertSame(
            frame,
            authority.resolve(context),
            "Opening an owned popup changes input geometry without changing its frame",
        )
    }

    @Test fun `navigation invalidates remote and local menu contexts alike`() {
        val frame = fakeFrame()
        val authority = BrowserMenuContextAuthority()
        val event = MouseEvent(JPanel(), MouseEvent.MOUSE_PRESSED, 0, 0, 0, 0, 0, 0, 1, true, MouseEvent.BUTTON3)
        val remote =
            authority.capture(frame, remoteClick = AppBrowserMenuDispatch.Click(event, { true }, Point.of(0, 0)))
        val local = authority.capture(frame)
        authority.invalidate()
        assertNull(authority.resolve(remote))
        assertNull(authority.resolve(local))
    }

    private fun fakeFrame(): Frame =
        Proxy.newProxyInstance(Frame::class.java.classLoader, arrayOf(Frame::class.java)) { _, _, _ -> null } as Frame
}
