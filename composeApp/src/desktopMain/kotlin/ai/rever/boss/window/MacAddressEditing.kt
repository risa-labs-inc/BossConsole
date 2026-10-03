package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.number
import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.send
import ai.rever.boss.window.MacToolbarRuntime.string
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.Structure
import javax.swing.SwingUtilities

/** AppKit delegate events are delivered in order to the browser's Compose state on the EDT. */
internal class MacAddressEditing {
    @Volatile var input: NativeTitleBarTextInput? = null
    var view: Pointer? = null

    @Volatile var closed = false

    @Volatile var active = false
        private set
    private var typed = ""
    private var revision = -1
    private var completing = false
    private var allowCompletion = true

    fun update(
        next: NativeTitleBarTextInput,
        changedBrowser: Boolean,
    ) {
        if (changedBrowser) notification("end")
        input = next
        val model = next.address
        val editor = pointer(view, "currentEditor")
        val reset = changedBrowser || revision != model?.revision
        if (editor == null || reset) {
            completing = false
            val text = model?.text ?: next.value
            send(view, "setStringValue:", string(text))
            typed = text
            if (editor != null && model != null) {
                val start = minOf(model.selectionStart, model.selectionEnd).coerceIn(0, text.length)
                val end = maxOf(model.selectionStart, model.selectionEnd).coerceIn(start, text.length)
                val range = MacTextRange(start.toLong(), (end - start).toLong())
                send(editor, "setSelectedRange:", range)
                send(editor, "scrollRangeToVisible:", MacTextRange(end.toLong(), 0L))
            }
        }
        revision = model?.revision ?: -1
        if (editor != null && model != null) applyCompletion(editor, model)
    }

    private fun applyCompletion(
        editor: Pointer,
        model: ai.rever.boss.plugin.browser.BrowserAddressBarState,
    ) {
        if (completing || !allowCompletion || number(editor, "hasMarkedText") != 0L) return
        val caret = selection(editor)
        val suffix =
            addressCompletion(typed, model.text, model.completion, caret.first, caret.second)
                ?: return
        send(editor, "setString:", string(suffix))
        val range = MacTextRange(typed.length.toLong(), (suffix.length - typed.length).toLong())
        send(editor, "setSelectedRange:", range)
        // Keep the insertion point beside the typed prefix visible, rather than jumping
        // to the end of a long suggested URL or leaving the caret outside the field.
        send(editor, "scrollRangeToVisible:", MacTextRange(typed.length.toLong(), 0L))
        completing = true
    }

    fun notification(name: String) {
        val current = input ?: return
        when (name) {
            "begin" -> {
                if (!active && !closed) {
                    active = true
                    deliver {
                        // Ignore a delayed begin after a page click or a browser switch.
                        if (active && input?.identity == current.identity) {
                            val handle =
                                ai.rever.boss.plugin.browser.ActiveBrowserRegistry
                                    .handleById(current.identity)
                            (handle as? ai.rever.boss.plugin.browser.BrowserHandleImpl)?.unfocusPageForAddressEditing()
                        }
                    }
                }
            }

            "end" -> {
                if (active) {
                    active = false
                    deliver { current.address?.onFocusLost?.invoke() }
                }
            }

            "change" -> {
                val editor = pointer(view, "currentEditor") ?: return
                val text = pointer(pointer(editor, "string"), "UTF8String")?.getString(0).orEmpty()
                val selection = selection(editor)
                allowCompletion = text.length >= typed.length
                typed = text
                completing = false
                deliver { current.address?.onEdit?.invoke(text, selection.first, selection.second) }
            }
        }
    }

    /** Only resign this field's editor, never a newly focused page or another native control. */
    @Suppress("ReturnCount") // Explicit guards protect the borrowed native editor before any operation.
    fun releaseForPage(): Boolean {
        if (closed || !active) return false
        val window = pointer(view, "window") ?: return false
        val editor = pointer(view, "currentEditor")
        if (editor == null || pointer(window, "firstResponder") != editor) {
            notification("end")
            return true
        }
        // A nil responder falls back to NSWindow, which beeps for ordinary text input.
        // Return the native editor to this same window's AWT content view instead.
        val content = pointer(window, "contentView") ?: return false
        val released = number(window, "makeFirstResponder:", content) != 0L
        if (released) notification("end")
        return released
    }

    fun command(
        selector: String,
        shift: Boolean,
        onSubmit: () -> Unit = ::submit,
    ): Boolean =
        when {
            closed || input == null -> {
                false
            }

            selector == "insertNewline:" -> {
                onSubmit()
                true
            }

            else -> {
                editingCommand(selector, shift)
            }
        }

    private fun editingCommand(
        selector: String,
        shift: Boolean,
    ): Boolean {
        val model = input?.address ?: return false
        val command =
            when (selector) {
                "moveDown:" -> "next"
                "moveUp:" -> "previous"
                "insertTab:" -> if (model.completion != null) "accept" else null
                "moveRight:" -> if (completingSelection()) "right" else null
                "cancelOperation:" -> "cancel"
                "deleteBackward:", "deleteForward:" -> if (shift && model.hasSelectedSuggestion) "delete" else null
                else -> null
            }
        if (command != null) deliver { model.onCommand(command) }
        return command != null
    }

    private fun completingSelection(): Boolean {
        val editor = pointer(view, "currentEditor") ?: return false
        val range = selection(editor)
        return completing && range.first == typed.length && range.second > range.first
    }

    fun submit() {
        val current = input ?: return
        if (typed.isBlank()) return
        releaseForPage()
        deliver {
            val model = current.address
            if (model != null) model.onCommand("submit") else current.onSubmit(typed)
            val handle =
                ai.rever.boss.plugin.browser.ActiveBrowserRegistry
                    .handleById(current.identity)
            (handle as? ai.rever.boss.plugin.browser.BrowserHandleImpl)?.focusPageAfterAddressCommit()
        }
    }

    private fun deliver(action: () -> Unit) {
        SwingUtilities.invokeLater { if (!closed) action() }
    }
}

private fun selection(editor: Pointer): Pair<Int, Int> =
    Memory(16).use { bytes ->
        val value = pointer(editor, "valueForKey:", string("selectedRange"))
        send(value, "getValue:size:", bytes, 16L)
        val start = bytes.getLong(0).toInt().coerceAtLeast(0)
        start to (start + bytes.getLong(8).toInt().coerceAtLeast(0))
    }

/** Direct remote editing bypasses AppKit's key-event pass that normally reveals the caret. */
internal fun revealNativeAddressCaret(editor: Pointer) {
    val range = selection(editor)
    if (range.first == range.second) {
        send(pointer(editor, "layoutManager"), "ensureLayoutForTextContainer:", pointer(editor, "textContainer"))
        send(editor, "scrollRangeToVisible:", MacTextRange(range.first.toLong(), 0L))
    }
}

@Structure.FieldOrder("location", "length")
internal class MacTextRange(
    @JvmField var location: Long = 0,
    @JvmField var length: Long = 0,
) : Structure(),
    Structure.ByValue
