package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.browser.callback.ShowContextMenuCallback
import com.teamdev.jxbrowser.frame.EditorCommand
import com.teamdev.jxbrowser.frame.Frame
import com.teamdev.jxbrowser.menu.ContextMenuContentType
import java.awt.Component
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities

private val logger = BossLogger.forComponent("PopupWindowContextMenu")

/** One entry in the popup window's context menu. */
internal data class PopupMenuEntry(
    val label: String,
    val command: EditorCommand?,
    val enabled: Boolean,
) {
    /** Separators carry no command and are never enabled. */
    val isSeparator: Boolean get() = command == null
}

/**
 * Whether the system clipboard currently holds text that Paste could insert.
 *
 * A local AWT call, not Chromium IPC, so it does not reopen the EDT-blocking concern that moved
 * enablement off `isCommandEnabled`. Defaults to **enabled** when the clipboard cannot be read: it
 * is briefly lockable by another process on Windows, and a Paste that turns out to be a no-op is a
 * far smaller annoyance than Paste greyed out on a login form when the user does have something to
 * paste.
 */
internal fun clipboardHasText(): Boolean =
    try {
        Toolkit.getDefaultToolkit().systemClipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)
    } catch (e: IllegalStateException) {
        logger.debug(
            LogCategory.BROWSER,
            "Clipboard unavailable - assuming paste is possible",
            mapOf("error" to e.toString()),
        )
        true
    }

/**
 * The menu for a right-click, decided entirely from the click target.
 *
 * Pure and derived from [ShowContextMenuCallback.Params] values rather than from the browser,
 * for two reasons:
 *
 *  - **It runs on the EDT.** Asking the browser `isCommandEnabled` per item would be four blocking
 *    IPC round-trips into the Chromium process while the UI thread waits, and Compose Desktop
 *    shares that thread — a busy renderer would freeze the whole app on right-click. This repo
 *    already learned that here: `BrowserHandleImpl` replaced `browser.title()` with a cached value
 *    on this exact path because "being slow hurts as much as throwing".
 *  - **It is testable** without an engine.
 *
 * Enablement mirrors what each command would actually do: Cut and Paste need an editable target,
 * Cut and Copy need a selection, Paste needs something on the clipboard, and Select All is always
 * available.
 */
internal fun popupMenuEntriesFor(
    contentTypes: List<ContextMenuContentType>,
    selectedText: String,
    clipboardHasText: Boolean,
): List<PopupMenuEntry> {
    val editable = contentTypes.contains(ContextMenuContentType.EDITABLE)
    val hasSelection = selectedText.isNotEmpty()
    return listOf(
        PopupMenuEntry("Cut", EditorCommand.cut(), editable && hasSelection),
        PopupMenuEntry("Copy", EditorCommand.copy(), hasSelection),
        PopupMenuEntry("Paste", EditorCommand.paste(), editable && clipboardHasText),
        PopupMenuEntry("", null, false),
        PopupMenuEntry("Select All", EditorCommand.selectAll(), true),
    )
}

/**
 * True when the menu would be nothing but greyed-out items, which is worse than no menu.
 *
 * Note this buys nothing today: `Select All` is unconditionally enabled, so [popupMenuEntriesFor]
 * cannot currently produce a list this rejects. It is a guard against a future entry set, not live
 * behaviour — what a right-click on a plain page actually shows is a one-usable-item menu
 * (`Select All`, under three greyed entries).
 */
internal fun hasAnyEnabledEntry(entries: List<PopupMenuEntry>): Boolean = entries.any { it.enabled }

/**
 * Whether the menu should still be shown by the time the EDT gets to it.
 *
 * Separated from the callback so the guard that actually prevents the reported crash is testable:
 * a disposed or hidden view has no location on screen, and asking for one is what threw.
 */
internal fun shouldShowPopupMenu(
    viewShowing: Boolean,
    browserClosed: Boolean,
    entries: List<PopupMenuEntry>,
): Boolean = viewShowing && !browserClosed && hasAnyEnabledEntry(entries)

/**
 * Context menu for the Swing popup windows BOSS opens for `window.open` popups (OAuth, payment).
 *
 * These browsers live in their own [javax.swing.JFrame] rather than in a tab, so BOSS's own context
 * menu — Compose, drawn by the browser plugin — cannot reach them. Until now they fell through to
 * **JxBrowser's built-in Swing menu**, which crashes:
 *
 * ```
 * java.awt.IllegalComponentStateException: component must be showing on the screen to determine its location
 *     at ...view.swing.internal.menu.SuggestionsPopup.lambda$show$2(SuggestionsPopup.java:104)
 * ```
 *
 * That menu resolves its position from `getLocationOnScreen()` inside an `invokeLater`, so anything
 * that stops the view showing between the right-click and that lambda throws on the EDT. A popup
 * window is where that races: an OAuth popup closes *itself* when the flow completes
 * (`BrowserClosed` → `frame.dispose()`), so a right-click landing on that boundary asks a disposed
 * component where it is. Reported as BossConsole-Releases#17.
 *
 * Installing any [ShowContextMenuCallback] suppresses the built-in menu, which is what removes the
 * crash. This substitutes a small editing menu rather than leaving popup windows with nothing —
 * right-click paste into a login form is a reasonable thing to want.
 *
 * Known differences from the menu it replaces, all accepted:
 *
 *  - **No spell-check suggestions** — that is what `SuggestionsPopup` was, and it is not worth
 *    re-implementing for a transient OAuth window.
 *  - **No Back / Forward / Reload / Print / View source.** Reload in a stuck OAuth window is the
 *    one worth revisiting if anyone asks.
 *  - **Mouse-dismiss only.** Escape does not close it and arrows do not navigate it, for the same
 *    reason the page-click dismissal is needed at all: the popup browser owns the focused native
 *    surface and has no `PressKeyCallback`, so those keys never reach Swing's
 *    `MenuSelectionManager`.
 *  - **One blocking IPC call remains on the EDT** — `Frame.execute` in the action listener.
 *    Deciding the menu is free, but running the chosen command is a synchronous round-trip, so a
 *    wedged page can stall the UI thread on Cut/Copy/Paste. Accepted: it follows an explicit user
 *    action rather than every right-click, and matches JxBrowser's own Swing sample.
 *
 * Prefer [installPopupWindowChrome] over calling this directly — the menu is only correct when
 * paired with the page-click dismissal.
 */
// Exception, not Throwable, at a callback boundary: a fatal Error (OOM, StackOverflow) is not
// this code's to swallow, which is exactly what runCatching here would do. detekt keeps this rule
// on deliberately, so this is an argued exemption rather than a baseline entry.
@Suppress("TooGenericExceptionCaught")
internal fun installPopupWindowContextMenu(
    popupBrowser: Browser,
    view: Component,
) {
    popupBrowser.set(
        ShowContextMenuCallback::class.java,
        ShowContextMenuCallback { params, tell ->
            // Everything needed is read here, while params is still valid, and NOT re-read later:
            // params belongs to this call only.
            //
            // params.frame() is the frame Chromium resolved for the actual click. Using
            // browser.focusedFrame() instead would answer for the wrong frame inside an iframe -
            // a lesson already recorded in BrowserHandleImpl - and OAuth and payment pages are
            // frequently iframed, which is exactly what this menu serves.
            //
            // Exception rather than Throwable: a genuinely fatal Error is not this boundary's to
            // swallow, matching the policy BrowserHandleImpl.deliverContextMenu states for the
            // same callback path.
            var frame: Frame? = null
            var entries: List<PopupMenuEntry> = emptyList()
            var x = 0
            var y = 0
            try {
                val location = params.location()
                x = location.x()
                y = location.y()
                frame = params.frame().orElse(null)
                entries = popupMenuEntriesFor(params.contentTypes(), params.selectedText(), clipboardHasText())
            } catch (e: Exception) {
                logger.debug(
                    LogCategory.BROWSER,
                    "Could not read the popup context-menu target",
                    mapOf("error" to e.toString()),
                )
            } finally {
                // Answer on every path: an un-responded callback leaves Chromium waiting and shows
                // nothing at all. This close() is also what suppresses the built-in menu.
                tell.close()
            }

            val target = frame ?: return@ShowContextMenuCallback
            SwingUtilities.invokeLater {
                // Check-then-act, and safe today only because both frame.dispose() call sites go
                // through invokeLater, so a disposal cannot land between this check and show().
                // The catch below keeps that robust rather than merely lucky: a disposal added off
                // the EDT later would otherwise reduce this to the very race being fixed.
                if (!shouldShowPopupMenu(view.isShowing, popupBrowser.isClosed, entries)) {
                    logger.debug(LogCategory.BROWSER, "Skipping popup context menu - view is gone")
                    return@invokeLater
                }
                try {
                    buildMenu(target, entries).show(view, x, y)
                } catch (e: Exception) {
                    logger.warn(LogCategory.BROWSER, "Popup context menu failed to show", error = e)
                }
            }
        },
    )
}

/**
 * Everything a popup window's browser needs before it is shown.
 *
 * **Call this after `BrowserView.newInstance(popupBrowser)`.** The view anchors the menu, and
 * installing before it exists also risks the view's own construction registering a default
 * `ShowContextMenuCallback` over this one — JxBrowser allows a single callback per type, and a
 * second registration replaces the first silently, with no compile error.
 *
 * The two installs are a pair, not a sequence: a Swing menu over a heavyweight browser surface does
 * not close when the user clicks back into the page — Chromium consumes that press before AWT sees
 * it — so the menu without the dismissal is a menu that sticks. Bundling them makes that impossible
 * to get wrong at a call site.
 *
 * Neither install may fail the caller. Both call sites run inside a `try/catch` that closes the
 * popup browser, and this runs before the window is made visible, so an escaping exception would
 * mean the OAuth window never opens at all — strictly worse than the crash being fixed. Same
 * reasoning `FluckEngine.setupSwingPopupDismissOnPageClick` already applies to itself: a browser
 * that rejects a callback still works, it just keeps the older behaviour.
 */
@Suppress("TooGenericExceptionCaught") // See installPopupWindowContextMenu - Error must propagate.
internal fun installPopupWindowChrome(
    popupBrowser: Browser,
    view: Component,
) {
    try {
        installPopupWindowContextMenu(popupBrowser, view)
    } catch (e: Exception) {
        logger.debug(
            LogCategory.BROWSER,
            "Could not install the popup context menu - keeping JxBrowser's built-in one",
            mapOf("error" to e.toString()),
        )
    }
    // Deliberately outside the catch above: if the menu failed to install, the built-in menu is
    // still there and still needs dismissing on an in-page click.
    FluckEngine.setupSwingPopupDismissOnPageClick(popupBrowser)
}

/** Renders [entries] against the frame the click resolved to. */
@Suppress("TooGenericExceptionCaught") // See installPopupWindowContextMenu - Error must propagate.
internal fun buildMenu(
    frame: Frame,
    entries: List<PopupMenuEntry>,
): JPopupMenu {
    val menu = JPopupMenu()
    // main.kt sets this globally, but state it here too: a lightweight popup paints into the Swing
    // layer, which sits behind a heavyweight browser surface, and a menu that silently fails to
    // appear reads as a regression rather than as a bug.
    menu.isLightWeightPopupEnabled = false
    entries.forEach { entry ->
        val command = entry.command
        if (command == null) {
            menu.addSeparator()
            return@forEach
        }
        menu.add(
            JMenuItem(entry.label).apply {
                isEnabled = entry.enabled
                addActionListener {
                    try {
                        frame.execute(command)
                    } catch (e: Exception) {
                        logger.debug(
                            LogCategory.BROWSER,
                            "Popup editor command failed",
                            mapOf("command" to entry.label, "error" to e.toString()),
                        )
                    }
                }
            },
        )
    }
    return menu
}
