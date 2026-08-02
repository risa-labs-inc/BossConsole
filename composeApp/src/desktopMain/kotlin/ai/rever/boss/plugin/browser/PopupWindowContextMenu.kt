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

/** True when the menu would be nothing but greyed-out items, which is worse than no menu. */
internal fun hasAnyEnabledEntry(entries: List<PopupMenuEntry>): Boolean = entries.any { it.enabled }

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
 * Pairs with [FluckEngine.setupSwingPopupDismissOnPageClick], which the caller must also install on
 * the popup browser — without it a Swing menu over a heavyweight browser surface does not close
 * when the user clicks back into the page, because Chromium consumes that press before AWT sees it.
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
                if (!view.isShowing || popupBrowser.isClosed) {
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
private fun buildMenu(
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
