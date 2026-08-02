package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.browser.callback.ShowContextMenuCallback
import com.teamdev.jxbrowser.frame.EditorCommand
import com.teamdev.jxbrowser.frame.Frame
import com.teamdev.jxbrowser.menu.ContextMenuContentType
import java.awt.Component
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
 * Enablement mirrors what the commands would actually do: Cut and Paste need an editable target,
 * Cut and Copy need a selection, and Select All is always available.
 */
internal fun popupMenuEntriesFor(
    contentTypes: List<ContextMenuContentType>,
    selectedText: String,
): List<PopupMenuEntry> {
    val editable = contentTypes.contains(ContextMenuContentType.EDITABLE)
    val hasSelection = selectedText.isNotEmpty()
    return listOf(
        PopupMenuEntry("Cut", EditorCommand.cut(), editable && hasSelection),
        PopupMenuEntry("Copy", EditorCommand.copy(), hasSelection),
        PopupMenuEntry("Paste", EditorCommand.paste(), editable),
        PopupMenuEntry("", null, false),
        PopupMenuEntry("Select All", EditorCommand.selectAll(), true),
    )
}

/**
 * True when the menu would be nothing but greyed-out items, which is worse than no menu.
 *
 * Note this buys nothing today: `Select All` is unconditionally enabled, so
 * [popupMenuEntriesFor] cannot currently produce a list this rejects. It is a guard against a
 * future entry set, not live behaviour — what a right-click on a plain page actually shows is a
 * one-usable-item menu (`Select All`, under three greyed entries).
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
 * right-click paste into a login form is a reasonable thing to want. It differs from the built-in
 * menu deliberately: it renders only while the view is showing, positions relative to the component
 * instead of from screen coordinates, and offers no spell-check suggestions.
 *
 * Prefer [installPopupWindowChrome] over calling this directly: the menu is only correct when
 * paired with the page-click dismissal, and that pairing should not be something a call site can
 * forget.
 *
 * One piece of blocking IPC remains on the EDT by choice: `Frame.execute` in the item's action
 * listener. Deciding the menu is now free, but running the chosen command is a synchronous
 * round-trip into the renderer, so a wedged page can still stall the UI thread on Cut/Copy/Paste.
 * Accepted because it happens after an explicit user action rather than on every right-click, and
 * it is what JxBrowser's own Swing sample does.
 */
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
            val target =
                runCatching {
                    Triple(
                        params.location(),
                        params.frame().orElse(null),
                        popupMenuEntriesFor(params.contentTypes(), params.selectedText()),
                    )
                }.onFailure {
                    logger.debug(
                        LogCategory.BROWSER,
                        "Could not read the popup context-menu target",
                        mapOf("error" to it.toString()),
                    )
                }.getOrNull()

            // Answer unconditionally, including on the failure path: an un-responded callback
            // leaves Chromium waiting and shows nothing at all. This close() is also what
            // suppresses JxBrowser's built-in menu.
            tell.close()
            if (target == null) return@ShowContextMenuCallback
            val (location, frame, entries) = target
            if (frame == null || !hasAnyEnabledEntry(entries)) return@ShowContextMenuCallback

            val x = location.x()
            val y = location.y()
            SwingUtilities.invokeLater {
                // Check-then-act, and safe today only because both frame.dispose() call sites go
                // through invokeLater, so a disposal cannot land between this check and show().
                // The runCatching below is what keeps that robust rather than merely lucky: a
                // disposal added off the EDT later would otherwise reduce this to the very race
                // being fixed.
                if (!shouldShowPopupMenu(view.isShowing, popupBrowser.isClosed, entries)) {
                    logger.debug(LogCategory.BROWSER, "Skipping popup context menu - view is gone")
                    return@invokeLater
                }
                runCatching { buildMenu(frame, entries).show(view, x, y) }
                    .onFailure {
                        logger.warn(LogCategory.BROWSER, "Popup context menu failed to show", error = it)
                    }
            }
        },
    )
}

/** Renders [entries] against the frame the click resolved to. */
internal fun buildMenu(
    frame: Frame,
    entries: List<PopupMenuEntry>,
): JPopupMenu {
    val menu = JPopupMenu()
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
                    runCatching { frame.execute(command) }
                        .onFailure {
                            logger.debug(
                                LogCategory.BROWSER,
                                "Popup editor command failed",
                                mapOf("command" to entry.label, "error" to it.toString()),
                            )
                        }
                }
            },
        )
    }
    return menu
}

/**
 * Everything a popup window's browser needs before it is shown.
 *
 * The two calls below are a pair, not a sequence: a Swing menu over a heavyweight browser surface
 * does not close when the user clicks back into the page — Chromium consumes that press before AWT
 * sees it — so installing the menu without the dismissal produces a menu that sticks. Bundling them
 * makes that impossible to get wrong at a call site, instead of relying on a comment at each one.
 *
 * A popup browser arrives from `params.popupBrowser()` and receives none of the setup a
 * BOSS-created browser gets, which is why this has to be explicit at all.
 */
internal fun installPopupWindowChrome(
    popupBrowser: Browser,
    view: Component,
) {
    installPopupWindowContextMenu(popupBrowser, view)
    FluckEngine.setupSwingPopupDismissOnPageClick(popupBrowser)
}
