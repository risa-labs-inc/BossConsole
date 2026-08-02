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
 * A local AWT call, not Chromium IPC, but not free either: the JDK's Windows clipboard open is a
 * retry-with-sleep loop, so on the one platform this crash was reported on it can take tens of
 * milliseconds while another process holds the clipboard. Hence it runs after `tell.close()`, and
 * only when the target is editable.
 *
 * **Must not throw.** Callers use it while assembling the menu, and an escaping exception there is
 * caught by an outer handler that leaves the entry list empty — which suppresses the whole menu,
 * silently, instead of just mis-enabling one item. All three documented ways this call fails are
 * therefore handled, and every one of them defaults to *enabled*: a Paste that turns out to be a
 * no-op is a far smaller annoyance than Paste greyed out on a login form when the user does have
 * something to paste.
 *
 *  - [IllegalStateException] — the clipboard is momentarily locked by another process (Windows).
 *  - [java.awt.HeadlessException] — no display; an `UnsupportedOperationException`, *not* an
 *    `IllegalStateException`, so it needs its own branch.
 *  - [SecurityException] — `AWTPermission("accessClipboard")` denied.
 */
internal fun clipboardHasText(): Boolean =
    try {
        Toolkit.getDefaultToolkit().systemClipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)
    } catch (e: IllegalStateException) {
        clipboardUnavailable(e)
    } catch (e: UnsupportedOperationException) {
        clipboardUnavailable(e)
    } catch (e: SecurityException) {
        clipboardUnavailable(e)
    }

private fun clipboardUnavailable(e: Exception): Boolean {
    logger.debug(
        LogCategory.BROWSER,
        "Clipboard unavailable - assuming paste is possible",
        mapOf("error" to e.toString()),
    )
    return true
}

/**
 * The menu for a right-click, decided entirely from the click target.
 *
 * Pure and derived from [ShowContextMenuCallback.Params] values rather than from the browser,
 * for two reasons:
 *
 *  - **No blocking IPC on the right-click path.** Asking the browser `isCommandEnabled` per item
 *    would be four synchronous round-trips into the Chromium process before the menu could be
 *    built, delaying it on every right-click and stalling on a busy renderer. This callback runs
 *    on a **JxBrowser thread**, not the EDT — which is why showing the menu needs
 *    `SwingUtilities.invokeLater` — so the cost lands on menu latency rather than on the UI
 *    thread. `BrowserHandleImpl` reached the same conclusion for the same callback, replacing
 *    `browser.title()` with a cached value because "being slow hurts as much as throwing".
 *  - **It is testable** without an engine.
 *
 * Enablement mirrors what each command would actually do: Cut and Paste need an editable target,
 * Cut and Copy need a selection, Paste needs something on the clipboard, and Select All is always
 * available.
 *
 * [clipboardHasText] is a lambda, not a value, so the probe is skipped on a non-editable target —
 * the majority of right-clicks in an OAuth window, and the case where its answer is discarded
 * anyway. It is not free: see [clipboardHasText] on what a Windows clipboard read can cost.
 */
internal fun popupMenuEntriesFor(
    contentTypes: List<ContextMenuContentType>,
    selectedText: String,
    clipboardHasText: () -> Boolean,
): List<PopupMenuEntry> {
    val editable = contentTypes.contains(ContextMenuContentType.EDITABLE)
    val hasSelection = selectedText.isNotEmpty()
    return listOf(
        PopupMenuEntry("Cut", EditorCommand.cut(), editable && hasSelection),
        PopupMenuEntry("Copy", EditorCommand.copy(), hasSelection),
        PopupMenuEntry("Paste", EditorCommand.paste(), editable && clipboardHasText()),
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
            // Only params reads go in here - params is valid for this call only - and nothing is
            // derived from them yet. A partial read then degrades to a MINIMAL menu rather than to
            // none: the list is built below from whatever was captured, and Select All stays
            // enabled regardless, so a failure costs some greyed items instead of a right-click
            // that silently does nothing. WARN, not debug: "the menu is wrong" should be
            // diagnosable from a user's log, unlike the routine teardown races.
            var frame: Frame? = null
            var x = 0
            var y = 0
            var contentTypes: List<ContextMenuContentType> = emptyList()
            var selectedText = ""
            try {
                val location = params.location()
                x = location.x()
                y = location.y()
                // The frame Chromium resolved for the ACTUAL click. browser.focusedFrame() would
                // answer for the wrong frame inside an iframe - a lesson already recorded in
                // BrowserHandleImpl - and iframed OAuth/payment pages are the main case here.
                frame = params.frame().orElse(null)
                contentTypes = params.contentTypes()
                selectedText = params.selectedText()
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "Could not fully read the popup context-menu target", error = e)
            } finally {
                // Answer on every path: an un-responded callback leaves Chromium waiting and shows
                // nothing at all. This close() is also what suppresses the built-in menu.
                tell.close()
            }

            val target = frame ?: return@ShowContextMenuCallback

            // Built AFTER tell.close(), so Chromium is already released before the clipboard read.
            // Being outside the try also keeps a throw here from emptying the list, which would
            // suppress the menu entirely.
            val entries = popupMenuEntriesFor(contentTypes, selectedText, ::clipboardHasText)
            SwingUtilities.invokeLater {
                // The try wraps the guard as well as the show. `popupBrowser.isClosed` is a call
                // into JxBrowser on the EDT during teardown - the exact window in which things are
                // being disposed - so an exception from the check itself would land uncaught on the
                // EDT, which is the failure class this whole file exists to remove.
                try {
                    // Check-then-act, and safe today only because both frame.dispose() call sites
                    // go through invokeLater, so a disposal cannot land between this check and
                    // show(). The catch keeps that robust rather than merely lucky: a disposal
                    // added off the EDT later would otherwise reduce this to the race being fixed.
                    if (!shouldShowPopupMenu(view.isShowing, popupBrowser.isClosed, entries)) {
                        logger.debug(LogCategory.BROWSER, "Skipping popup context menu - view is gone")
                        return@invokeLater
                    }
                    buildPopupWindowMenu(target, entries).show(view, x, y)
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
        // WARN, not debug: if this install fails the built-in menu stays, and with it the EDT crash
        // this file exists to remove - live for that popup, for the rest of its life. That is a
        // different order of failure from the dismissal handler's (a menu that sticks) and needs to
        // be visible in a log a user would actually send.
        logger.warn(
            LogCategory.BROWSER,
            "Could not install the popup context menu - keeping JxBrowser's built-in one",
            error = e,
        )
    }
    // Deliberately outside the catch above: if the menu failed to install, the built-in menu is
    // still there and still needs dismissing on an in-page click.
    FluckEngine.setupSwingPopupDismissOnPageClick(popupBrowser)
}

/** Renders [entries] against the frame the click resolved to. */
@Suppress("TooGenericExceptionCaught") // See installPopupWindowContextMenu - Error must propagate.
internal fun buildPopupWindowMenu(
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
