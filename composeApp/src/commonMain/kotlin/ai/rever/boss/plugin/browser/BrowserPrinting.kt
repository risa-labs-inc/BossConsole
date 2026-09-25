package ai.rever.boss.plugin.browser

import ai.rever.boss.keymap.model.KeyStroke
import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.model.ShortcutContext
import ai.rever.boss.keymap.presets.KeymapPresets.claimsChord

// Schedule printing after JavaScript evaluation returns: window.print can wait for the preview
// to close. This runs in page context: a site overriding window.print can intercept it.
// JxBrowser's Swing BrowserView installs DefaultPrintCallback.showPrintPreview().
internal const val PRINT_BROWSER_SCRIPT = "window.setTimeout(() => window.print(), 0); undefined;"

internal suspend fun printActiveBrowser(windowId: String) {
    ActiveBrowserRegistry.activeIn(windowId)?.executeJavaScript(PRINT_BROWSER_SCRIPT)
}

/** The native page handles Cmd/Ctrl+P; do not reclaim a disabled or reassigned chord. */
internal fun usesNativePrintChord(settings: KeymapSettings): Boolean {
    val chord = KeyStroke("P", listOf("Cmd"))
    val binding = settings.getBinding(KeymapActions.BROWSER_PRINT) ?: return false
    return binding.enabled && binding.claimsChord(chord, ShortcutContext.BROWSER) &&
        settings.shortcuts.values.none {
            it.actionId != binding.actionId && it.enabled && it.claimsChord(chord, ShortcutContext.BROWSER)
        }
}
