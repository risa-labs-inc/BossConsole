package ai.rever.boss.tabfullscreen

import com.teamdev.jxbrowser.browser.Browser
import java.awt.Window
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.KeyStroke

internal val fullscreenBrowserInput = FullscreenBrowserInput<Browser, Window> { it.isShowing && it.isFocused }

/** Publishes the exact fullscreen surface to Chromium's callback thread without changing tab ownership. */
internal class FullscreenBrowserInput<B : Any, W : Any>(
    private val acceptsInput: (W) -> Boolean,
) {
    private data class Target<B, W>(
        val browser: B,
        val owner: String,
        val window: W,
    )

    @Volatile private var target: Target<B, W>? = null

    fun attach(
        browser: B,
        owner: String,
        window: W,
    ) {
        target = Target(browser, owner, window)
    }

    /** Null means this browser still uses its ordinary host; false must not fall back to that host. */
    fun focusFor(
        browser: B,
        owner: String?,
    ): Boolean? {
        val current = target?.takeIf { it.browser === browser } ?: return null
        return current.owner == owner && acceptsInput(current.window)
    }

    fun clear() {
        target = null
    }
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
