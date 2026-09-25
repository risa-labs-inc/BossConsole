package ai.rever.boss.window

import java.util.concurrent.ConcurrentHashMap

/**
 * Which windows have Compose focus somewhere inside a main content panel.
 *
 * Written by `BossMainPanel`'s `onFocusChanged` (the panel's own subtree, so a tab's address bar
 * or find bar counts, and a sidebar slot or a dialog does not) and read on the AWT event thread by
 * [ai.rever.boss.plugin.browser.ActiveBrowserRegistry.keyboardOwnerIn], hence the concurrent map.
 *
 * Keyed by a per-panel token rather than a flag per window. Focus moving from one split half to
 * the other reports the gain and the loss in an order this does not control, and a single flag
 * would end up false after a gain-then-loss sequence even though a panel still has focus.
 *
 * `java.util.concurrent` in `commonMain` for the reason `ActiveBrowserRegistry` gives: the module
 * has a single JVM target.
 */
object MainPanelFocusTracker {
    private val focusedPanels = ConcurrentHashMap<String, Set<Any>>()

    /** Record whether the panel identified by [panel] has Compose focus inside it in [windowId]. */
    fun update(
        windowId: String,
        panel: Any,
        hasFocus: Boolean,
    ) {
        focusedPanels.compute(windowId) { _, current ->
            val panels = current.orEmpty()
            (if (hasFocus) panels + panel else panels - panel).ifEmpty { null }
        }
    }

    /**
     * Forget [windowId]. Called when the window closes, so a window torn down without its panels'
     * disposal cannot leave a focus record behind.
     */
    fun clearWindow(windowId: String) {
        focusedPanels.remove(windowId)
    }

    /** Whether any main panel in [windowId] has Compose focus. */
    fun hasFocus(windowId: String): Boolean = !focusedPanels[windowId].isNullOrEmpty()
}
