package ai.rever.boss.window

import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.keymap.model.ShortcutContext
import ai.rever.boss.plugin.browser.BrowserKeyboardOwner

// Which shortcut context a key press is in, for AWTKeyboardInterceptor. Split out of that object
// so the context rule reads in one place (BossConsole#1566) and is testable without AWT focus.

/**
 * Which shortcut context a key press is in, and whether the web page itself holds the keyboard.
 *
 * [pageFocused] is set only when the answer came from the page's own focus (see
 * [resolveKeyboardContext]). It is what lets [AWTKeyboardInterceptor.matchBinding] leave the chords the native key
 * callback in `FluckEngine` serves itself (see [PAGE_SERVED_ACTIONS]) to that callback.
 */
internal data class KeyboardContext(
    val context: ShortcutContext,
    val pageFocused: Boolean = false,
)

/**
 * BROWSER-context actions that the embedded page's native key callback (`FluckEngine`'s
 * `PressKeyCallback`) already serves while the page has focus: find (page-first, so a site
 * with its own find-in-page keeps it), reload and print.
 *
 * While the page holds the keyboard these BROWSER bindings are skipped, so the chord matches
 * exactly what it matched before BROWSER context could be reached in a main-window tab, when
 * the context there was always GLOBAL: nothing, or a GLOBAL binding on the same chord (the VS
 * Code preset's Cmd+P quick open is one). Claiming the BROWSER action would either act a second
 * time or, for find, take the chord away from the page before it can call `preventDefault()`.
 * The chrome (address bar, find bar) has no native callback, so there the interceptor serves
 * them like any other BROWSER binding.
 */
internal val PAGE_SERVED_ACTIONS =
    setOf(KeymapActions.BROWSER_FIND, KeymapActions.BROWSER_RELOAD, KeymapActions.BROWSER_PRINT)

/**
 * Combine the AWT focus walk with the browser keyboard owner. Pure, so the table is testable.
 *
 * | AWT walk  | browser owner | result                    | where the keys are going               |
 * |-----------|---------------|---------------------------|----------------------------------------|
 * | BROWSER   | any           | BROWSER                   | Swing `FullscreenBrowserWindow`        |
 * | GLOBAL    | PAGE          | BROWSER, page focused     | the active browser's web page          |
 * | TERMINAL  | PAGE          | BROWSER, page focused     | the page (the terminal is stale, below)|
 * | TERMINAL  | NONE / CHROME | TERMINAL                  | a BossTerm Swing component             |
 * | GLOBAL    | CHROME        | BROWSER                   | the browser tab's Compose chrome       |
 * | GLOBAL    | NONE          | GLOBAL                    | anything else (editor, sidebar, ...)   |
 *
 * PAGE beats TERMINAL because under HARDWARE_ACCELERATED Chromium's native view takes the
 * keyboard without AWT knowing: clicking the page after a terminal leaves the terminal as the
 * AWT focus owner, while the page's own focus event is the truthful one. The Swing
 * fullscreen browser keeps the old answer, and is not flagged page-focused, because there
 * AWT sees the key before Chromium does and the interceptor has always served it.
 */
internal fun resolveKeyboardContext(
    awtContext: ShortcutContext,
    browserOwner: BrowserKeyboardOwner,
): KeyboardContext =
    when {
        awtContext == ShortcutContext.BROWSER -> KeyboardContext(ShortcutContext.BROWSER)
        browserOwner == BrowserKeyboardOwner.PAGE -> KeyboardContext(ShortcutContext.BROWSER, pageFocused = true)
        awtContext == ShortcutContext.TERMINAL -> KeyboardContext(ShortcutContext.TERMINAL)
        browserOwner == BrowserKeyboardOwner.CHROME -> KeyboardContext(ShortcutContext.BROWSER)
        else -> KeyboardContext(ShortcutContext.GLOBAL)
    }

/**
 * Detect shortcut context by walking up the AWT component hierarchy.
 * JxBrowser components have "jxbrowser" in their package name.
 *
 * Only a Swing JxBrowser `BrowserView` (the fullscreen window) is an AWT component; the
 * Compose `BrowserView` a main-window tab uses is not, which is why this is no longer the
 * whole answer - see `AWTKeyboardInterceptor.detectCurrentContext`.
 */
internal fun detectContextFromAwtComponent(component: java.awt.Component?): ShortcutContext =
    generateSequence(component) { it.parent }
        .firstNotNullOfOrNull { contextOfComponentClass(it.javaClass.name) }
        ?: ShortcutContext.GLOBAL

private fun contextOfComponentClass(className: String): ShortcutContext? {
    val isTerminal = className.contains("bossterm", ignoreCase = true) || className.contains("TerminalPanel")
    return when {
        className.contains("jxbrowser", ignoreCase = true) -> ShortcutContext.BROWSER
        isTerminal -> ShortcutContext.TERMINAL
        else -> null
    }
}

/**
 * Check if a binding's context is eligible given the current active context.
 * GLOBAL and WORKSPACE bindings always match.
 * Component-specific bindings (BROWSER, TERMINAL, EDITOR) only match their context.
 */
private fun isContextEligible(
    bindingContext: ShortcutContext,
    currentContext: ShortcutContext,
): Boolean =
    when (bindingContext) {
        ShortcutContext.GLOBAL -> true
        ShortcutContext.WORKSPACE -> true
        else -> bindingContext == currentContext
    }

/**
 * [isContextEligible], less the actions the focused page's native key callback serves itself
 * (see [PAGE_SERVED_ACTIONS]).
 */
internal fun isBindingEligible(
    binding: KeyBinding,
    keyboardContext: KeyboardContext,
): Boolean =
    isContextEligible(binding.context, keyboardContext.context) &&
        !(keyboardContext.pageFocused && binding.actionId in PAGE_SERVED_ACTIONS)
