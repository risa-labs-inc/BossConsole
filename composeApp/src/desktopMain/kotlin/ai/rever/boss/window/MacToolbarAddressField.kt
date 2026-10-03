package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.clazz
import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.send
import ai.rever.boss.window.MacToolbarRuntime.string
import com.sun.jna.Pointer

/** Stock AppKit text field: native editing, selection, clipboard and accessibility. */
internal class MacToolbarAddressField {
    val editing = MacAddressEditing()
    val copyButton = MacAddressCopyButton()
    val bounds = androidx.compose.runtime.mutableStateOf<androidx.compose.ui.unit.IntRect?>(null)
    private var identity: String? = null
    private var field: Pointer? = null

    fun update(
        item: Pointer,
        input: NativeTitleBarTextInput,
        target: Pointer?,
        favicon: Pointer?,
    ) {
        val view = field ?: create(item, target)
        field = view
        val changedBrowser = identity != input.identity
        if (changedBrowser) send(pointer(view, "window"), "makeFirstResponder:", null)
        editing.view = view
        editing.update(input, changedBrowser)
        claimEditorFocus()
        val searchCell = pointer(pointer(view, "cell"), "searchButtonCell")
        val image =
            favicon.takeIf { input.address?.text == input.value } ?: pointer(
                clazz("NSImage"),
                "imageWithSystemSymbolName:accessibilityDescription:",
                string("magnifyingglass"),
                string("Search"),
            )
        send(searchCell, "setImage:", image)
        identity = input.identity
        send(item, "setLabel:", string("Address"))
        send(view, "setToolTip:", string(input.value))
    }

    fun updateBounds(window: Pointer) {
        val view = field?.takeIf { ownsNativeToolbarView(window, it) } ?: return
        val next = nativeAddressBounds(view, window) ?: return
        javax.swing.SwingUtilities.invokeLater { if (!editing.closed) bounds.value = next }
    }

    fun focus() {
        val view = field ?: return
        send(pointer(view, "window"), "makeFirstResponder:", view)
        send(view, "selectText:", null)
        claimEditorFocus()
    }

    fun claimEditorFocus() {
        val editor = pointer(editing.view, "currentEditor")
        if (editor != null && pointer(pointer(editing.view, "window"), "firstResponder") == editor) {
            editing.notification("begin")
        }
    }

    private fun create(
        item: Pointer,
        target: Pointer?,
    ): Pointer {
        val view = checkNotNull(pointer(MacAddressFocusField.fieldClass, "new"))
        send(view, "setEditable:", 1.toByte())
        send(view, "setSelectable:", 1.toByte())
        send(view, "setBezeled:", 1.toByte())
        send(view, "setPlaceholderString:", string("Search or enter address"))
        send(view, "setUsesSingleLineMode:", 1.toByte())
        send(pointer(view, "cell"), "setScrollable:", 1.toByte())
        send(view, "setDelegate:", target)
        send(view, "setSendsWholeSearchString:", 1.toByte())
        send(view, "setSendsSearchStringImmediately:", 0.toByte())
        send(view, "setTarget:", target)
        // NSSearchField also sends its action when cleared. Only the delegate's
        // insertNewline command should navigate, never a search-field change action.
        send(view, "setAction:", null)
        copyButton.install(view, target)
        val height = installNativeAddressBackground(item, view)
        send(item, "setMinSize:", ToolbarIconSize(180.0, height))
        send(item, "setMaxSize:", ToolbarIconSize(10_000.0, height))
        send(item, "setVisibilityPriority:", 2000L)
        send(item, "setAutovalidates:", 0.toByte())
        send(item, "setEnabled:", 1.toByte())
        send(view, "release")
        return view
    }
}
