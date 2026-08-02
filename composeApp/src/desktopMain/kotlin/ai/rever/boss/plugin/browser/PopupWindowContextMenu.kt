package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.browser.callback.ShowContextMenuCallback
import com.teamdev.jxbrowser.frame.EditorCommand
import java.awt.Component
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities

private val logger = BossLogger.forComponent("PopupWindowContextMenu")

/**
 * Context menu for the Swing popup windows BOSS opens for `window.open` popups (OAuth, payment).
 *
 * These browsers live in their own [javax.swing.JFrame] rather than in a tab, so BOSS's own
 * context menu — which is Compose, drawn by the browser plugin — cannot reach them. Until now they
 * fell through to **JxBrowser's built-in Swing menu**, which crashes:
 *
 * ```
 * java.awt.IllegalComponentStateException: component must be showing on the screen to determine its location
 *     at ...view.swing.internal.menu.SuggestionsPopup.lambda$show$2(SuggestionsPopup.java:104)
 * ```
 *
 * That menu positions itself from `getLocationOnScreen()` inside an `invokeLater`, so anything that
 * stops the view showing between the right-click and that lambda throws on the EDT. A popup window
 * is exactly where that races: an OAuth popup closes *itself* when the flow completes
 * (`BrowserClosed` → `frame.dispose()`), so a right-click landing as the flow finishes disposes the
 * frame out from under the pending menu. Reported as BossConsole-Releases#17, on Windows, after a
 * session full of auth activity.
 *
 * Installing any [ShowContextMenuCallback] suppresses the built-in menu, which is what removes the
 * crash. Rather than leave popup windows with no menu at all, this substitutes a small Swing menu
 * with the editing actions that matter in a login form. Two differences from the built-in one are
 * deliberate:
 *
 *  - It is shown only when the view [Component.isShowing], and positioned from coordinates
 *    relative to that component rather than from screen coordinates — so the failure mode above is
 *    unreachable rather than merely less likely.
 *  - It offers no spell-check suggestions. Those are what `SuggestionsPopup` exists for, and they
 *    are not worth re-implementing for a transient OAuth window.
 */
internal fun installPopupWindowContextMenu(
    popupBrowser: Browser,
    view: Component,
) {
    popupBrowser.set(
        ShowContextMenuCallback::class.java,
        ShowContextMenuCallback { params, tell ->
            // Read the location before hopping threads: params is only valid for this call.
            val location =
                runCatching { params.location() }
                    .onFailure {
                        logger.debug(
                            LogCategory.BROWSER,
                            "Could not read popup context-menu location",
                            mapOf("error" to it.toString()),
                        )
                    }.getOrNull()

            // Answer unconditionally, including on the failure path above: an un-responded
            // callback leaves Chromium waiting and shows nothing at all. This close() is also
            // what suppresses JxBrowser's built-in menu.
            tell.close()
            if (location == null) return@ShowContextMenuCallback

            val x = location.x()
            val y = location.y()
            SwingUtilities.invokeLater {
                // The whole point of this class: by the time this runs the popup may have closed
                // itself. A disposed or hidden view has no location on screen, and asking for one
                // is what threw.
                if (!view.isShowing || popupBrowser.isClosed) {
                    logger.debug(LogCategory.BROWSER, "Skipping popup context menu - view is gone")
                    return@invokeLater
                }
                runCatching { buildMenu(popupBrowser).show(view, x, y) }
                    .onFailure {
                        logger.warn(LogCategory.BROWSER, "Popup context menu failed to show", error = it)
                    }
            }
        },
    )
}

/**
 * Cut / Copy / Paste / Select All, enabled according to what the focused frame actually supports.
 *
 * Built fresh per open so the enabled states reflect the current selection instead of the one from
 * whenever a cached menu was created.
 */
private fun buildMenu(browser: Browser): JPopupMenu {
    val menu = JPopupMenu()
    val frame = browser.focusedFrame().orElse(null) ?: browser.mainFrame().orElse(null)

    fun add(
        label: String,
        command: EditorCommand,
    ) {
        val item = JMenuItem(label)
        // isCommandEnabled reflects the live selection - greying out Copy with nothing selected is
        // the difference between a menu that looks native and one that looks broken.
        item.isEnabled =
            frame != null &&
            runCatching { frame.isCommandEnabled(command.name()) }.getOrDefault(false)
        item.addActionListener {
            runCatching { frame?.execute(command) }
                .onFailure {
                    logger.debug(
                        LogCategory.BROWSER,
                        "Popup editor command failed",
                        mapOf("command" to label, "error" to it.toString()),
                    )
                }
        }
        menu.add(item)
    }

    add("Cut", EditorCommand.cut())
    add("Copy", EditorCommand.copy())
    add("Paste", EditorCommand.paste())
    menu.addSeparator()
    add("Select All", EditorCommand.selectAll())
    return menu
}
