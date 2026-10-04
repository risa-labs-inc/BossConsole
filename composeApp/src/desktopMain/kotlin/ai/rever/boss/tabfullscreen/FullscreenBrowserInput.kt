package ai.rever.boss.tabfullscreen

import com.teamdev.jxbrowser.browser.Browser
import java.awt.Window
import java.awt.event.ActionEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.lang.ref.WeakReference
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

internal val fullscreenBrowserInput = FullscreenBrowserInput<Browser>()

/** EDT-owned publication; Chromium reads only an immutable snapshot, never AWT state. */
internal class FullscreenBrowserInput<B : Any> {
    private data class Target<B>(
        val browser: WeakReference<B>,
        val owner: String,
        val token: Any,
        val showing: Boolean,
        val focused: Boolean,
    )

    @Volatile private var target: Target<B>? = null

    fun attach(
        browser: B,
        owner: String,
        showing: Boolean,
        focused: Boolean,
    ): Any {
        val token = Any()
        target = Target(WeakReference(browser), owner, token, showing, focused)
        return token
    }

    fun update(
        token: Any,
        showing: Boolean,
        focused: Boolean,
    ) {
        target?.takeIf { it.token === token }?.let {
            target = it.copy(showing = showing, focused = focused)
        }
    }

    /** A hidden or closed surface yields ordinary routing; a visible unfocused surface rejects input. */
    fun focusFor(
        browser: B,
        owner: String?,
    ): Boolean? {
        val current = target?.takeIf { it.browser.get() === browser && it.showing } ?: return null
        // A different owner must not authorize keys for the fullscreen session.
        return current.owner == owner && current.focused
    }

    fun detach(token: Any) {
        if (target?.token === token) clear()
    }

    fun clear() {
        target = null
    }
}

/** Called after the fullscreen lifecycle checks its epoch, for both native and replacement frames. */
internal fun observeFullscreenBrowserInput(
    browser: Browser,
    owner: String?,
    window: Window,
) {
    check(SwingUtilities.isEventDispatchThread())
    if (owner == null) return
    val token = fullscreenBrowserInput.attach(browser, owner, window.isShowing, window.isFocused)
    val visibility =
        object : ComponentAdapter() {
            override fun componentShown(event: ComponentEvent?) {
                fullscreenBrowserInput.update(token, window.isShowing, window.isFocused)
            }

            override fun componentHidden(event: ComponentEvent?) {
                fullscreenBrowserInput.update(token, showing = false, focused = false)
            }
        }
    val focus =
        object : WindowAdapter() {
            override fun windowGainedFocus(event: WindowEvent?) {
                fullscreenBrowserInput.update(token, window.isShowing, focused = true)
            }

            override fun windowLostFocus(event: WindowEvent?) {
                fullscreenBrowserInput.update(token, window.isShowing, focused = false)
            }

            override fun windowClosed(event: WindowEvent?) {
                fullscreenBrowserInput.detach(token)
                window.removeWindowFocusListener(this)
                window.removeWindowListener(this)
                window.removeComponentListener(visibility)
            }
        }
    window.addComponentListener(visibility)
    window.addWindowFocusListener(focus)
    window.addWindowListener(focus)
}

/** The detached page window owns Escape even though application shortcuts keep their tab owner. */
internal fun installFullscreenExitShortcut(
    frame: JFrame,
    onExit: () -> Unit,
) {
    val action = "exit-fullscreen"
    frame.rootPane
        .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), action)
    frame.rootPane.actionMap.put(
        action,
        object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) = onExit()
        },
    )
}
